package com.termux.terminal;

import java.util.BitSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;

/**
 * Bounded, deduplicated hyperlink table for {@code OSC 8} native terminal hyperlinks.
 * <p>
 * Valid URIs are assigned compact {@code short} IDs in {@code [1, MAX_HYPERLINKS]} stored lazily in
 * {@link TerminalRow#mHyperlinkIds} only on rows that actually contain hyperlinks, keeping memory
 * overhead at zero on plain rows. Unsafe schemes (such as {@code javascript:}, {@code file:},
 * {@code intent:}, {@code content:}, and {@code data:}) are rejected at ingestion.
 */
public final class HyperlinkTable {

    /** Maximum number of distinct live hyperlinks per {@link TerminalBuffer}. */
    public static final int MAX_HYPERLINKS = 1024;
    /** Maximum accepted URI length in UTF-16 code units. */
    public static final int MAX_URI_LENGTH = 2048;

    private final String[] mIdToUri = new String[MAX_HYPERLINKS + 1];
    private final HashMap<String, Short> mUriToId = new HashMap<>();
    private int mSearchCursor = 1;

    /**
     * Validate and register an {@code OSC 8} URI, returning its positive {@code short} ID,
     * {@code 0} if empty/unsafe/invalid, or {@code -1} if the table is currently full so the caller
     * can run garbage collection and retry.
     */
    public short registerOrSignalFull(String rawUri) {
        if (!isSafeHyperlinkUri(rawUri)) return 0;

        Short existing = mUriToId.get(rawUri);
        if (existing != null) return existing;

        if (mUriToId.size() >= MAX_HYPERLINKS) {
            return -1;
        }

        for (int i = 0; i < MAX_HYPERLINKS; i++) {
            int candidate = ((mSearchCursor - 1 + i) % MAX_HYPERLINKS) + 1;
            if (mIdToUri[candidate] == null) {
                short id = (short) candidate;
                mIdToUri[candidate] = rawUri;
                mUriToId.put(rawUri, id);
                mSearchCursor = (candidate % MAX_HYPERLINKS) + 1;
                return id;
            }
        }
        return -1;
    }

    public String getUri(short id) {
        if (id <= 0 || id > MAX_HYPERLINKS) return null;
        return mIdToUri[id];
    }

    public boolean isEmpty() {
        return mUriToId.isEmpty();
    }

    public int size() {
        return mUriToId.size();
    }

    public void clear() {
        for (int i = 1; i <= MAX_HYPERLINKS; i++) {
            mIdToUri[i] = null;
        }
        mUriToId.clear();
        mSearchCursor = 1;
    }

    public void removeId(short id) {
        if (id <= 0 || id > MAX_HYPERLINKS) return;
        String uri = mIdToUri[id];
        if (uri != null) {
            mIdToUri[id] = null;
            mUriToId.remove(uri);
            mSearchCursor = id;
        }
    }

    /** Sweep any hyperlink IDs not marked in {@code referencedIds}. */
    public void retainOnly(BitSet referencedIds) {
        if (mUriToId.isEmpty()) return;
        for (int id = 1; id <= MAX_HYPERLINKS; id++) {
            String uri = mIdToUri[id];
            if (uri != null && !referencedIds.get(id)) {
                mIdToUri[id] = null;
                mUriToId.remove(uri);
            }
        }
    }

    /** Append URIs referenced on {@code row} in left-to-right order. */
    public void collectRowHyperlinks(TerminalRow row, LinkedHashSet<CharSequence> out) {
        if (row == null || row.mHyperlinkIds == null || mUriToId.isEmpty()) return;
        short prevId = 0;
        for (int col = 0; col < row.mHyperlinkIds.length; col++) {
            short id = row.mHyperlinkIds[col];
            if (id > 0 && id != prevId) {
                String uri = getUri(id);
                if (uri != null) out.add(uri);
            }
            prevId = id;
        }
    }

    /**
     * Allow only safe, external user-navigable URI schemes for {@code OSC 8} links so untrusted
     * terminal output cannot smuggle {@code file://}, {@code intent://}, {@code content://}, or
     * {@code javascript:} payloads.
     */
    public static boolean isSafeHyperlinkUri(String uri) {
        if (uri == null) return false;
        int len = uri.length();
        if (len == 0 || len > MAX_URI_LENGTH) return false;

        for (int i = 0; i < len; i++) {
            char c = uri.charAt(i);
            if (c <= 0x20 || c == 0x7F) return false;
        }

        int colonIdx = uri.indexOf(':');
        if (colonIdx <= 0 || colonIdx > 16) return false;

        String scheme = uri.substring(0, colonIdx).toLowerCase(Locale.US);
        switch (scheme) {
            case "http":
            case "https":
            case "ftp":
                return uri.regionMatches(colonIdx + 1, "//", 0, 2) && len > colonIdx + 3;
            case "mailto":
            case "tel":
                return len > colonIdx + 1;
            default:
                return false;
        }
    }
}
