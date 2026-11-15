package com.termux.view;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

/**
 * Pure-JVM unit tests for {@link TerminalRenderer} row run segmentation, bitmap span coalescing,
 * and multi-row selection column bounds (no Android graphics mocks required).
 */
public class TerminalRendererTest {

    private static final TerminalOutput NOOP_OUTPUT = new TerminalOutput() {
        @Override public void write(byte[] data, int offset, int count) {}
        @Override public void titleChanged(String oldTitle, String newTitle) {}
        @Override public void onCopyTextToClipboard(String text) {}
        @Override public void onPasteTextFromClipboard() {}
        @Override public void onBell() {}
        @Override public void onColorsChanged() {}
    };

    @Test
    public void zeroEncodedStyleStartsNewRunAtRowStartAndAfterBitmapSpan() {
        TerminalEmulator emulator = new TerminalEmulator(NOOP_OUTPUT, 10, 4, 10, 20, 20, null);
        byte[] input = "\033[30;40mHELLO".getBytes(StandardCharsets.UTF_8);
        emulator.append(input, input.length);

        // SGR 30;40 (ANSI color 0 fg + ANSI color 0 bg + no effects) encodes to literally 0L.
        long zeroStyle = emulator.getScreen().getStyleAt(0, 0);
        assertEquals(0L, zeroStyle);

        // When lastRunStartColumn is -1 (at column 0 or right after a bitmap span), a cell with
        // style == 0L must still start a new text run rather than being dropped.
        assertTrue(TerminalRenderer.shouldStartNewTextRun(
            -1, zeroStyle, 0L, false, false, false, false, false, false));

        // Subsequent cells with identical 0L style and state coalesce into the open run.
        assertFalse(TerminalRenderer.shouldStartNewTextRun(
            0, zeroStyle, 0L, false, false, false, false, false, false));
    }

    @Test
    public void multiRowSelectionBoundsCoverContinuationRowsWithoutFalsePositiveAtNegativeRows() {
        // Inactive selection (-1, -1, -1, -1) must stay (-1, -1) even when scrolled into transcript at row == -1.
        assertEquals(-1, TerminalRenderer.rowSelectionStartCol(-1, -1, -1, -1, -1));
        assertEquals(-1, TerminalRenderer.rowSelectionEndCol(-1, 10, -1, -1, -1, -1));

        // Multi-row selection from (col 4, row 1) through (col 6, row 3) on a 10-column screen:
        assertEquals(-1, TerminalRenderer.rowSelectionStartCol(0, 1, 3, 4, 6));
        assertEquals(-1, TerminalRenderer.rowSelectionEndCol(0, 10, 1, 3, 4, 6));

        assertEquals(4, TerminalRenderer.rowSelectionStartCol(1, 1, 3, 4, 6));
        assertEquals(10, TerminalRenderer.rowSelectionEndCol(1, 10, 1, 3, 4, 6));

        // Continuation rows (row 2 and row 3) must start at col 0 rather than -1 so bitmap spans highlight.
        assertEquals(0, TerminalRenderer.rowSelectionStartCol(2, 1, 3, 4, 6));
        assertEquals(10, TerminalRenderer.rowSelectionEndCol(2, 10, 1, 3, 4, 6));

        assertEquals(0, TerminalRenderer.rowSelectionStartCol(3, 1, 3, 4, 6));
        assertEquals(6, TerminalRenderer.rowSelectionEndCol(3, 10, 1, 3, 4, 6));

        assertEquals(-1, TerminalRenderer.rowSelectionStartCol(4, 1, 3, 4, 6));
        assertEquals(-1, TerminalRenderer.rowSelectionEndCol(4, 10, 1, 3, 4, 6));
    }

    @Test
    public void bitmapSpanColumnsCoalescesOnlyContiguousHorizontalSlices() {
        TerminalRow row = new TerminalRow(10, TextStyle.encodeTerminalBitmap(1, 0, 0));
        row.clear(0L);

        // Cols 0..3: contiguous slices x=0..3 of bitmap #5, row slice y=2.
        for (int x = 0; x < 4; x++) {
            row.setChar(x, ' ', TextStyle.encodeTerminalBitmap(5, x, 2));
        }
        // Col 4: same bitmap #5, x=4, but different y=3 -> must not coalesce with cols 0..3.
        row.setChar(4, ' ', TextStyle.encodeTerminalBitmap(5, 4, 3));
        // Cols 5..6: contiguous slices x=0..1 of bitmap #6, y=0.
        row.setChar(5, ' ', TextStyle.encodeTerminalBitmap(6, 0, 0));
        row.setChar(6, ' ', TextStyle.encodeTerminalBitmap(6, 1, 0));
        // Col 7: plain text cell.
        row.setChar(7, 'A', 0L);

        assertEquals(4, TerminalRenderer.bitmapSpanColumns(row, 0, 10));
        assertEquals(1, TerminalRenderer.bitmapSpanColumns(row, 4, 10));
        assertEquals(2, TerminalRenderer.bitmapSpanColumns(row, 5, 10));

        // Verify advanceOneCellInText skips surrogate pairs and combining marks cleanly.
        char[] text = new char[]{'e', '\u0301', '\uD83D', '\uDE00', 'Z'};
        int idx = TerminalRenderer.advanceOneCellInText(text, 0, text.length);
        assertEquals(2, idx); // 'e' + U+0301 combining acute accent
        idx = TerminalRenderer.advanceOneCellInText(text, idx, text.length);
        assertEquals(4, idx); // surrogate pair U+1F600
        idx = TerminalRenderer.advanceOneCellInText(text, idx, text.length);
        assertEquals(5, idx); // 'Z'
    }
}
