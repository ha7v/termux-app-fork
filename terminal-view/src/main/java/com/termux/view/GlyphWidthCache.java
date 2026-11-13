package com.termux.view;

import java.util.Arrays;

/**
 * Zero-allocation, 4-way set-associative LRU cache mapping Unicode code points ({@code >= 127}) to
 * measured glyph advance widths ({@code >= 0f}).
 *
 * <p>All 4 ways of a set live in adjacent array slots (16 bytes of keys), fitting inside a single
 * CPU cache line. Bounded associativity guarantees strict {@code O(1)} lookup and insertion even
 * if a session streams tens of thousands of distinct CJK or supplementary code points, without ever
 * resizing or allocating on the render path.
 */
final class GlyphWidthCache {

    static final float MISS = -1f;

    private static final int WAYS_SHIFT = 2; // 4 ways per set
    private static final int WAYS = 1 << WAYS_SHIFT;

    private final int mSetMask;
    private final int[] mKeys;
    private final float[] mWidths;

    /** @param numSets must be a positive power of two (e.g. 1024 -> 4096 cached glyphs, 32 KB). */
    GlyphWidthCache(int numSets) {
        if (numSets <= 0 || (numSets & (numSets - 1)) != 0) {
            throw new IllegalArgumentException("numSets must be a power of two: " + numSets);
        }
        mSetMask = numSets - 1;
        int totalSlots = numSets << WAYS_SHIFT;
        mKeys = new int[totalSlots];
        mWidths = new float[totalSlots];
        Arrays.fill(mKeys, -1);
    }

    /**
     * Look up the cached width for {@code codePoint}, promoting hits to the MRU slot of the set,
     * or return {@link #MISS} ({@code -1f}) if absent.
     */
    float get(int codePoint) {
        final int base = setBase(codePoint);
        final int[] keys = mKeys;
        if (keys[base] == codePoint) {
            return mWidths[base];
        }
        for (int way = 1; way < WAYS; way++) {
            int idx = base + way;
            int k = keys[idx];
            if (k == codePoint) {
                float w = mWidths[idx];
                // Promote hit to MRU position (base).
                for (int s = idx; s > base; s--) {
                    keys[s] = keys[s - 1];
                    mWidths[s] = mWidths[s - 1];
                }
                keys[base] = codePoint;
                mWidths[base] = w;
                return w;
            }
            if (k == -1) {
                break;
            }
        }
        return MISS;
    }

    /** Insert or update {@code codePoint} at the MRU position of its set, evicting LRU if full. */
    void put(int codePoint, float width) {
        final float clampedWidth = Math.max(0f, width);
        final int base = setBase(codePoint);
        final int[] keys = mKeys;
        final float[] widths = mWidths;

        int shiftFrom = base + (WAYS - 1);
        for (int way = 0; way < WAYS; way++) {
            int idx = base + way;
            if (keys[idx] == codePoint || keys[idx] == -1) {
                shiftFrom = idx;
                break;
            }
        }
        for (int s = shiftFrom; s > base; s--) {
            keys[s] = keys[s - 1];
            widths[s] = widths[s - 1];
        }
        keys[base] = codePoint;
        widths[base] = clampedWidth;
    }

    private int setBase(int codePoint) {
        // MurmurHash3 32-bit finalizer mix so contiguous Unicode blocks (e.g. Box Drawing U+2500..257F
        // or CJK U+4E00..9FFF) distribute evenly across sets.
        int h = codePoint;
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return (h & mSetMask) << WAYS_SHIFT;
    }
}
