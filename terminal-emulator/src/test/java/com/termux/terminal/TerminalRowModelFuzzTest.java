package com.termux.terminal;

import junit.framework.TestCase;

import java.util.Random;

/**
 * Differential fuzz test of {@link TerminalRow} against an independent cell model.
 *
 * <p>Unlike an oracle that rescans the same {@link TerminalRow#mText} without the column cursor
 * cache, the model never looks at {@code mText}: it tracks each column as either a base character
 * plus combining characters or the continuation of a wide character. If a stale cache ever made
 * {@link TerminalRow#setChar} write at a wrong index, the resulting text would diverge here even if
 * every subsequent uncached rescan agreed with the cached lookups.
 */
public class TerminalRowModelFuzzTest extends TestCase {

    private static final int MAX_COMBINING_PER_COLUMN = 15;

    private static final int[] ALPHABET = {
        ' ', 'a', 'Z', '~',
        0x00E9,           // é (width 1, BMP)
        0x679C, 0x679D,   // CJK (width 2, BMP)
        0x2070E, 0x20731, // CJK Ext-B (width 2, surrogate pair)
        0x1D11E,          // G clef (width 1, surrogate pair)
        0x1F600,          // emoji (width 2, surrogate pair)
        0x0308, 0x0303,   // combining marks (width 0, BMP)
        0x200B,           // zero width space (width 0)
        0x1D167,          // combining (width 0, surrogate pair)
    };

    /** Independent model of a terminal row. */
    private static final class RowModel {
        final int columns;
        final StringBuilder[] cells;
        final boolean[] continuation;

        RowModel(int columns) {
            this.columns = columns;
            cells = new StringBuilder[columns];
            continuation = new boolean[columns];
            clear();
        }

        void clear() {
            for (int i = 0; i < columns; i++) {
                cells[i] = new StringBuilder(" ");
                continuation[i] = false;
            }
        }

        boolean isWideStart(int col) {
            return col >= 0 && col + 1 < columns && continuation[col + 1];
        }

        void setChar(int col, int codePoint) {
            int width = WcWidth.width(codePoint);
            if (width <= 0) {
                if (continuation[col]) col--;
                String cell = cells[col].toString();
                if (zeroWidthCount(cell) >= MAX_COMBINING_PER_COLUMN) return;
                cells[col].appendCodePoint(codePoint);
                return;
            }

            if (continuation[col]) setChar(col - 1, ' ');
            if (width == 2 && isWideStart(col + 1)) setChar(col + 1, ' ');

            int oldWidth = isWideStart(col) ? 2 : 1;
            cells[col] = new StringBuilder().appendCodePoint(codePoint);
            if (oldWidth == 2 && width == 1) {
                continuation[col + 1] = false;
                cells[col + 1] = new StringBuilder(" ");
            } else if (width == 2) {
                continuation[col + 1] = true;
                cells[col + 1] = new StringBuilder();
            }
        }

        String text() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < columns; i++) {
                if (!continuation[i]) sb.append(cells[i]);
            }
            return sb.toString();
        }

        /** Expected {@link TerminalRow#findStartOfColumn(int)}. */
        int startOfColumn(int col) {
            if (col == columns) return text().length();
            int target = continuation[col] ? col - 1 : col;
            int index = 0;
            for (int i = 0; i < target; i++) {
                if (!continuation[i]) index += cells[i].length();
            }
            return index;
        }

        private static int zeroWidthCount(String s) {
            int count = 0;
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                if (WcWidth.width(cp) <= 0) count++;
                i += Character.charCount(cp);
            }
            return count;
        }
    }

    private static void assertRowMatchesModel(String context, TerminalRow row, RowModel model) {
        String expected = model.text();
        String actual = new String(row.mText, 0, row.getSpaceUsed());
        if (!expected.equals(actual)) {
            fail(context + "\n expected=" + escape(expected) + "\n actual  =" + escape(actual));
        }
    }

    private static void assertLookupsMatchModel(String context, TerminalRow row, RowModel model, Random rng, boolean sweep) {
        if (sweep) {
            for (int c = 0; c <= model.columns; c++) {
                assertEquals(context + " sweep col=" + c, model.startOfColumn(c), row.findStartOfColumn(c));
            }
        } else {
            // Non-monotonic probes exercise the backwards cache-miss path.
            for (int q = 0; q < 4; q++) {
                int c = rng.nextInt(model.columns + 1);
                assertEquals(context + " probe col=" + c, model.startOfColumn(c), row.findStartOfColumn(c));
            }
        }
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c < 0x7F) sb.append(c);
            else sb.append(String.format("\\u%04X", (int) c));
        }
        return sb.toString();
    }

    private void runFuzz(long seed, int columns, int steps, boolean sequentialBias) {
        Random rng = new Random(seed);
        TerminalRow row = new TerminalRow(columns, TextStyle.NORMAL);
        RowModel model = new RowModel(columns);
        int cursor = 0;

        for (int step = 0; step < steps; step++) {
            if (rng.nextInt(400) == 0) {
                row.clear(TextStyle.NORMAL);
                model.clear();
            }

            int cp = ALPHABET[rng.nextInt(ALPHABET.length)];
            int maxCol = (WcWidth.width(cp) == 2) ? columns - 1 : columns;
            int col;
            if (sequentialBias && rng.nextInt(4) != 0) {
                // Mostly sequential output, like a shell printing mixed CJK/ASCII text.
                col = Math.min(cursor, maxCol - 1);
            } else {
                col = rng.nextInt(maxCol);
            }

            String context = "seed=" + seed + " step=" + step + " col=" + col + " cp=U+" + Integer.toHexString(cp);
            row.setChar(col, cp, TextStyle.NORMAL);
            model.setChar(col, cp);

            int width = WcWidth.width(cp);
            if (width > 0) cursor = (col + width) % columns;

            assertRowMatchesModel(context, row, model);
            assertLookupsMatchModel(context, row, model, rng, step % 64 == 0);
        }
    }

    public void testRandomWritesMatchIndependentModel() {
        for (long seed = 1; seed <= 12; seed++) {
            runFuzz(seed, 20 + (int) (seed * 7), 4000, false);
        }
    }

    public void testSequentialWritesMatchIndependentModel() {
        for (long seed = 100; seed <= 112; seed++) {
            runFuzz(seed, 40 + (int) (seed % 5) * 30, 4000, true);
        }
    }

    public void testTinyRowsMatchIndependentModel() {
        // Two and three column rows stress the last-column / truncation branches.
        for (long seed = 200; seed <= 230; seed++) {
            runFuzz(seed, 2 + (int) (seed % 3), 600, false);
        }
    }

}
