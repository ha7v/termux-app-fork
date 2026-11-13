package com.termux.view;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Unit tests for {@link GlyphWidthCache}. */
public class GlyphWidthCacheTest {

    @Test
    public void hitMissAndZeroWidthCaching() {
        GlyphWidthCache cache = new GlyphWidthCache(16);
        assertEquals(GlyphWidthCache.MISS, cache.get(0x4E2D), 0f);

        cache.put(0x4E2D, 28.5f);
        assertEquals(28.5f, cache.get(0x4E2D), 0f);

        // Zero-width code points must also be cached as hits rather than treated as MISS.
        cache.put(0x200D, 0f);
        assertEquals(0f, cache.get(0x200D), 0f);
    }

    @Test
    public void fourWayLruEvictsLeastRecentlyUsedOnlyWithinSameSet() {
        // 1 set (= 4 ways total) forces every key to collide into the same 4-way set.
        GlyphWidthCache cache = new GlyphWidthCache(1);
        cache.put(1001, 10f);
        cache.put(1002, 20f);
        cache.put(1003, 30f);
        cache.put(1004, 40f);

        // Touch 1001 so 1002 becomes the LRU entry.
        assertEquals(10f, cache.get(1001), 0f);

        // Insert 1005 -> should evict 1002 while keeping 1001, 1003, 1004.
        cache.put(1005, 50f);
        assertEquals(GlyphWidthCache.MISS, cache.get(1002), 0f);
        assertEquals(10f, cache.get(1001), 0f);
        assertEquals(30f, cache.get(1003), 0f);
        assertEquals(40f, cache.get(1004), 0f);
        assertEquals(50f, cache.get(1005), 0f);
    }

    @Test
    public void survivesLargeUnicodeFloodWithoutDegrading() {
        GlyphWidthCache cache = new GlyphWidthCache(1024);
        // Stream 30,000 distinct code points (> 7x capacity) including supplementary planes.
        for (int cp = 0x4E00; cp < 0x4E00 + 30000; cp++) {
            cache.put(cp, (cp & 1) == 0 ? 24f : 12f);
        }
        // Most recent working set (e.g. Box Drawing U+2500..U+257F) hits cleanly.
        for (int cp = 0x2500; cp <= 0x257F; cp++) {
            cache.put(cp, 12f);
        }
        for (int cp = 0x2500; cp <= 0x257F; cp++) {
            assertEquals(12f, cache.get(cp), 0f);
        }
    }
}
