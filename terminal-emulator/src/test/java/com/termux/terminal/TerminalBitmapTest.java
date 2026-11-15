package com.termux.terminal;

public class TerminalBitmapTest extends TerminalTestCase {

    public void testTextStyleBitmapEncodingUnsigned() {
        int[] testNums = {0, 1, 32767, 32768, 65535};
        int[] testCoords = {0, 1, 2047, 2048, 4095};
        for (int num : testNums) {
            for (int coord : testCoords) {
                long style = TextStyle.encodeTerminalBitmap(num, coord, 4095 - coord);
                assertTrue(TextStyle.isTerminalBitmap(style));
                assertEquals(num, TextStyle.getTerminalBitmapNum(style));
                assertEquals(coord, TextStyle.getTerminalBitmapX(style));
                assertEquals(4095 - coord, TextStyle.getTerminalBitmapY(style));
            }
        }
        assertFalse(TextStyle.isTerminalBitmap(TextStyle.NORMAL));
        assertEquals(-1, TextStyle.getTerminalBitmapNum(TextStyle.NORMAL));
        assertEquals(-1, TextStyle.getTerminalBitmapX(TextStyle.NORMAL));
        assertEquals(-1, TextStyle.getTerminalBitmapY(TextStyle.NORMAL));
    }

    public void testDecrqmSixelModes() {
        withTerminalSized(10, 5);
        enterString("\033[?80$p\033[?8452$p");
        assertEquals("\033[?80;2$y\033[?8452;2$y", mOutput.getOutputAndClear());

        enterString("\033[?80h\033[?8452h");
        enterString("\033[?80$p\033[?8452$p");
        assertEquals("\033[?80;1$y\033[?8452;1$y", mOutput.getOutputAndClear());

        enterString("\033[?80l\033[?8452l");
        enterString("\033[?80$p\033[?8452$p");
        assertEquals("\033[?80;2$y\033[?8452;2$y", mOutput.getOutputAndClear());
    }

    public void testSixelExactHeightPixelsAndBufferGrowth() {
        TerminalSixel sixel = TerminalSixel.build(null, 8, 8);
        assertNotNull(sixel);

        // '?' (0x3F -> 0): no lit pixels, height remains 0 while width advances.
        assertTrue(sixel.readData('?', 4));
        assertEquals(4, sixel.getWidth());
        assertEquals(0, sixel.getHeight());
        assertEquals(0, sixel.getPixel(0, 0));

        // Set pure red on color #1 and light bit 0 ('@') across 20 columns (forces buffer growth past 8x8).
        sixel.setRGBColor(1, 100, 0, 0);
        assertTrue(sixel.readData('@', 20));
        assertEquals(24, sixel.getWidth());
        assertEquals(1, sixel.getHeight());
        assertEquals(0xFFFF0000, sixel.getPixel(4, 0));
        assertEquals(0xFFFF0000, sixel.getPixel(23, 0));
        assertEquals(0, sixel.getPixel(4, 1));

        // Carriage return '$' overprints same band without clearing previously lit pixels.
        assertTrue(sixel.readData('$', 1));
        sixel.setRGBColor(2, 0, 100, 0);
        // 'A' (0x41 -> bit 1 = y=1) at x=4:
        assertTrue(sixel.readData('?', 4));
        assertTrue(sixel.readData('A', 1));
        assertEquals(0xFFFF0000, sixel.getPixel(4, 0));
        assertEquals(0xFF00FF00, sixel.getPixel(4, 1));
        assertEquals(2, sixel.getHeight());

        // 'B' (0x42 -> bits 0 and 1) on third sixel band (mCurY=12): lowest lit pixel is 14, not 18.
        assertTrue(sixel.readData('-', 1));
        assertTrue(sixel.readData('-', 1));
        assertEquals(12, sixel.getCurY());
        assertTrue(sixel.readData('B', 1));
        assertEquals(14, sixel.getHeight());
        assertEquals(0xFF00FF00, sixel.getPixel(0, 12));
        assertEquals(0xFF00FF00, sixel.getPixel(0, 13));
        assertEquals(0, sixel.getPixel(0, 14));
    }

    public void testSixelScrollingDefaultAnd8452CursorModes() {
        // Cell size is 10x10. 5 rows = 50px tall.
        withTerminalSized(10, 5);
        mTerminal.resize(10, 5, 10, 10);
        enterString("HEADER\r\n");
        mTerminal.clearScrollCounter();

        // Cursor is at row 1, col 0. Enable ?8452h (cursor right of sixel).
        // Send a 38px-tall, 15px-wide sixel (7 bands: 6 * 6 + 2 = 38px -> 4 rows: rows 1..4, 2 cols: cols 0..1).
        StringBuilder sixel = new StringBuilder("\033[?8452h\033Pq#1;2;100;0;0#1");
        for (int band = 0; band < 6; band++) {
            sixel.append("!15~-");
        }
        sixel.append("!15B\033\\");
        enterString(sixel.toString());

        // Touching the bottom row under ?8452h must NOT scroll the screen!
        assertEquals(0, mTerminal.getScrollCounter());
        assertEquals('H', mTerminal.getScreen().allocateFullLineIfNecessary(
            mTerminal.getScreen().externalToInternalRow(0)).mText[0]);
        assertEquals(4, mTerminal.getCursorRow());
        assertEquals(2, mTerminal.getCursorCol());

        // Verify exact partial-edge geometry on bottom-right slice (x=1 -> cols 10..15, y=3 -> rows 30..38).
        long brStyle = mTerminal.getScreen().getStyleAt(4, 1);
        assertTrue(TextStyle.isTerminalBitmap(brStyle));
        assertEquals(1, TextStyle.getTerminalBitmapX(brStyle));
        assertEquals(3, TextStyle.getTerminalBitmapY(brStyle));
        TerminalBitmap brBitmap = mTerminal.getScreen().getTerminalBitmap(brStyle);
        assertNotNull(brBitmap);
        assertEquals(15, brBitmap.getWidth());
        assertEquals(38, brBitmap.getHeight());

        // Now reset ?8452l (default VT340/xterm): cursor starts at (row 1, col 3), draw 4-row sixel (rows 1..4) ->
        // trailing linefeed scrolls 1 line and leaves cursor at (row 4, startCol=3).
        enterString("\033[?8452l\033[2;4H");
        assertEquals(1, mTerminal.getCursorRow());
        assertEquals(3, mTerminal.getCursorCol());
        mTerminal.clearScrollCounter();

        StringBuilder defaultSixel = new StringBuilder("\033Pq#1");
        for (int band = 0; band < 6; band++) {
            defaultSixel.append("!10~-");
        }
        defaultSixel.append("!10B\033\\");
        enterString(defaultSixel.toString());

        assertEquals(1, mTerminal.getScrollCounter());
        assertEquals(4, mTerminal.getCursorRow());
        assertEquals(3, mTerminal.getCursorCol());
    }

    public void testDecsdm80hAnchorsTopLeftClipsAndNeverMovesCursor() {
        withTerminalSized(8, 4);
        mTerminal.resize(8, 4, 10, 10);

        // Enable BOTH ?80h (DECSDM: no scrolling, origin 0,0, no cursor move) and ?8452h,
        // and park cursor at (row=2, col=5) [1-based CUP 3;6].
        enterString("\033[?80h\033[?8452h\033[3;6H");
        assertEquals(2, mTerminal.getCursorRow());
        assertEquals(5, mTerminal.getCursorCol());
        mTerminal.clearScrollCounter();

        // Emit a 60px-tall (10 bands) x 20px-wide sixel, taller than the 40px screen.
        StringBuilder sixel = new StringBuilder("\033Pq#1;2;0;0;100#1");
        for (int band = 0; band < 10; band++) {
            sixel.append("!20~");
            if (band < 9) sixel.append('-');
        }
        sixel.append("\033\\");
        enterString(sixel.toString());

        // DECSDM (?80h) must anchor at (0, 0), clip height to 40px, not scroll, and NEVER move cursor!
        assertEquals(0, mTerminal.getScrollCounter());
        assertEquals(2, mTerminal.getCursorRow());
        assertEquals(5, mTerminal.getCursorCol());

        long topLeftStyle = mTerminal.getScreen().getStyleAt(0, 0);
        assertTrue(TextStyle.isTerminalBitmap(topLeftStyle));
        TerminalBitmap tb = mTerminal.getScreen().getTerminalBitmap(topLeftStyle);
        assertNotNull(tb);
        assertEquals(20, tb.getWidth());
        assertEquals(40, tb.getHeight());
        assertFalse(TextStyle.isTerminalBitmap(mTerminal.getScreen().getStyleAt(0, 2)));
    }

    public void testSixelRespectsDecstbmScrollMarginsAndUpdatesScrollCounter() {
        withTerminalSized(10, 5);
        mTerminal.resize(10, 5, 10, 10);

        // Write fixed header on row 0 and fixed status bar on row 4.
        enterString("\033[1;1HTOP_FIXED\033[5;1HBOT_FIXED");
        // Set DECSTBM scroll region to 1-based rows 2..4 (0-based rows 1..3, mBottomMargin=4).
        enterString("\033[2;4r\033[?8452h\033[3;1H");
        assertEquals(2, mTerminal.getCursorRow());
        mTerminal.clearScrollCounter();

        // Draw a 3-row (30px = 5 bands of 6px) sixel starting at row 2 inside rows 1..3:
        // row 0 of graphic -> row 2, row 1 -> row 3, row 2 -> scrolls region 1..3 once and lands at row 3!
        StringBuilder sixel = new StringBuilder("\033Pq#2");
        for (int band = 0; band < 5; band++) {
            sixel.append("!10~");
            if (band < 4) sixel.append('-');
        }
        sixel.append("\033\\");
        enterString(sixel.toString());

        assertEquals(1, mTerminal.getScrollCounter());
        assertEquals(3, mTerminal.getCursorRow());
        assertEquals(1, mTerminal.getCursorCol());

        // Fixed top and bottom rows outside DECSTBM must remain completely intact!
        TerminalBuffer screen = mTerminal.getScreen();
        assertEquals('T', screen.allocateFullLineIfNecessary(screen.externalToInternalRow(0)).mText[0]);
        assertFalse(TextStyle.isTerminalBitmap(screen.getStyleAt(0, 0)));
        assertEquals('B', screen.allocateFullLineIfNecessary(screen.externalToInternalRow(4)).mText[0]);
        assertFalse(TextStyle.isTerminalBitmap(screen.getStyleAt(4, 0)));

        // Rows 1, 2, 3 hold slices y=0, y=1, y=2 of the sixel!
        assertEquals(0, TextStyle.getTerminalBitmapY(screen.getStyleAt(1, 0)));
        assertEquals(1, TextStyle.getTerminalBitmapY(screen.getStyleAt(2, 0)));
        assertEquals(2, TextStyle.getTerminalBitmapY(screen.getStyleAt(3, 0)));
    }

    public void testBitmapLifecycleOverwriteClearTranscriptAltScreenAndSplitScrollOut() {
        withTerminalSized(6, 4);
        mTerminal.resize(6, 4, 10, 10);
        TerminalBuffer mainBuf = mTerminal.getScreen();

        // 1. In-place overwrite (?80h) immediately GCs superseded frames and uses roving IDs.
        enterString("\033[?80h");
        enterString("\033Pq!10~\033\\");
        long firstStyle = mainBuf.getStyleAt(0, 0);
        int firstId = TextStyle.getTerminalBitmapNum(firstStyle);
        assertEquals(1, mainBuf.getTerminalBitmapsCount());

        enterString("\033Pq!10~\033\\");
        long secondStyle = mainBuf.getStyleAt(0, 0);
        int secondId = TextStyle.getTerminalBitmapNum(secondStyle);
        assertTrue(secondId != firstId);
        assertEquals(1, mainBuf.getTerminalBitmapsCount());
        assertNull(mainBuf.getTerminalBitmap(firstStyle));
        assertNotNull(mainBuf.getTerminalBitmap(secondStyle));

        // 2. clearTranscript (\033[3J) must NOT wipe bitmaps still visible on the active screen!
        enterString("\033[3J");
        assertEquals(1, mainBuf.getTerminalBitmapsCount());
        assertNotNull(mainBuf.getTerminalBitmap(secondStyle));

        // 3. Alternate screen (?1049h / ?1049l) must release alt-buffer bitmaps on exit.
        enterString("\033[?1049h\033Pq!10~\033\\");
        TerminalBuffer altBuf = mTerminal.getScreen();
        assertNotSame(mainBuf, altBuf);
        assertEquals(1, altBuf.getTerminalBitmapsCount());
        enterString("\033[?1049l");
        assertSame(mainBuf, mTerminal.getScreen());
        assertEquals(0, altBuf.getTerminalBitmapsCount());
        assertNotNull(mainBuf.getTerminalBitmap(secondStyle));

        // 4. Split-bitmap scroll-out: draw a 3-row sixel on rows 0..2, overwrite middle row 1 with text,
        // and verify evicting row 0 preserves the bitmap for row 2!
        enterString("\033[?80l\033[2J\033[H");
        mainBuf.doTerminalBitmapsGC(0);
        assertEquals(0, mainBuf.getTerminalBitmapsCount());

        // 5 bands = 30px = 3 rows (rows 0, 1, 2)
        enterString("\033[?8452h\033Pq!10~-!10~-!10~-!10~-!10~\033\\");
        long splitStyleRow0 = mainBuf.getStyleAt(0, 0);
        long splitStyleRow2 = mainBuf.getStyleAt(2, 0);
        assertEquals(TextStyle.getTerminalBitmapNum(splitStyleRow0), TextStyle.getTerminalBitmapNum(splitStyleRow2));
        // Overwrite middle row 1 with plain text:
        enterString("\033[2;1HMIDDLE");
        assertFalse(TextStyle.isTerminalBitmap(mainBuf.getStyleAt(1, 0)));

        int internalRow0 = mainBuf.externalToInternalRow(0);
        mainBuf.removeScrolledOutTerminalBitmaps(internalRow0);
        mainBuf.allocateFullLineIfNecessary(internalRow0).clear(TextStyle.NORMAL);
        // Row 2 still needs this bitmap even though adjacent row 1 was plain text!
        assertNotNull(mainBuf.getTerminalBitmap(splitStyleRow2));

        int internalRow2 = mainBuf.externalToInternalRow(2);
        mainBuf.removeScrolledOutTerminalBitmaps(internalRow2);
        mainBuf.allocateFullLineIfNecessary(internalRow2).clear(TextStyle.NORMAL);
        assertNull(mainBuf.getTerminalBitmap(splitStyleRow2));
        assertEquals(0, mainBuf.getTerminalBitmapsCount());
    }

    public void testDeccaraAndWideCharacterInteractionsWithBitmaps() {
        withTerminalSized(10, 4);
        mTerminal.resize(10, 4, 10, 10);
        TerminalBuffer screen = mTerminal.getScreen();

        // Put CJK wide char '中' at cols 0..1 of row 0, then draw 1-cell sixel at col 1 (splitting '中').
        enterString("中\033[1;2H\033[?8452h\033Pq!10~\033\\");
        // Col 0 must be cleared to space WITHOUT duplicating the bitmap style onto col 0!
        assertFalse(TextStyle.isTerminalBitmap(screen.getStyleAt(0, 0)));
        assertTrue(TextStyle.isTerminalBitmap(screen.getStyleAt(0, 1)));
        long bitmapStyle = screen.getStyleAt(0, 1);

        // Write plain text at col 3 and apply DECCARA bold + DECRARA inverse across the entire row.
        enterString("\033[1;4HAB");
        enterString("\033[1;1;1;10;1$r\033[1;1;1;10;7$t");
        // Bitmap cell style at col 1 must remain completely uncorrupted!
        assertEquals(bitmapStyle, screen.getStyleAt(0, 1));
        assertEquals(
            TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_INVERSE,
            TextStyle.decodeEffect(screen.getStyleAt(0, 3)) & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_INVERSE));

        // Combining diacritics targeting a bitmap cell must be ignored rather than exposing '+'.
        enterString("\033[1;2H\u0301");
        assertEquals(bitmapStyle, screen.getStyleAt(0, 1));

        // Overwrite cols 0..1 with wide char '中', then overwrite col 0 with narrow 'x':
        // col 1 must NOT resurrect a stale zombie bitmap slice!
        enterString("\033[0m\033[1;1H中\033[1;1Hx");
        assertFalse(TextStyle.isTerminalBitmap(screen.getStyleAt(0, 0)));
        assertFalse(TextStyle.isTerminalBitmap(screen.getStyleAt(0, 1)));
    }

    public void testSixelOversizedRasterAttributesDoNotCrashOrPoisonState() {
        withTerminalSized(10, 4);
        mTerminal.resize(10, 4, 10, 10);
        TerminalBuffer screen = mTerminal.getScreen();

        // Ph=1_000_000_000, Pv=2 previously kept Ph*Pv inside positive 32-bit int range and
        // passed ~1.02B to StringBuilder.ensureCapacity(), throwing OutOfMemoryError.
        enterString("\033Pq\"1;1;1000000000;2#0!1~\033\\");
        assertEquals(0, screen.getTerminalBitmapsCount());

        // Dimensions exceeding MAX_BITMAP_DIMENSION are likewise rejected cleanly.
        enterString("\033Pq\"1;1;50000;50000#0!1~\033\\");
        assertEquals(0, screen.getTerminalBitmapsCount());

        // Subsequent valid Sixel and normal text remain completely functional.
        enterString("\033[?8452h\033Pq!10~\033\\OK");
        assertTrue(TextStyle.isTerminalBitmap(screen.getStyleAt(0, 0)));
        assertLineIs(0, "+OK       ");
    }

    public void testDecseraErasesUnprotectedBitmapCells() {
        withTerminalSized(10, 4);
        mTerminal.resize(10, 4, 10, 10);
        TerminalBuffer screen = mTerminal.getScreen();

        // Place a 2-cell bitmap at cols 0..1, protected 'P' at col 2, and bold unprotected 'U' at col 3.
        enterString("\033[?8452h\033Pq!20~\033\\\033[1\"qP\033[0\"q\033[1mU\033[0m");
        assertTrue(TextStyle.isTerminalBitmap(screen.getStyleAt(0, 0)));
        assertTrue(TextStyle.isTerminalBitmap(screen.getStyleAt(0, 1)));

        // DECSERA across row 1 cols 1..10 must erase unprotected bitmap cells cleanly instead of
        // preserving their TERMINAL_BITMAP slice styles, while preserving protected 'P' and bold on col 3.
        enterString("\033[1;1;1;10${");
        assertFalse(TextStyle.isTerminalBitmap(screen.getStyleAt(0, 0)));
        assertFalse(TextStyle.isTerminalBitmap(screen.getStyleAt(0, 1)));
        assertLineIs(0, "  P       ");
        assertEquals(TextStyle.CHARACTER_ATTRIBUTE_BOLD,
            TextStyle.decodeEffect(screen.getStyleAt(0, 3)) & TextStyle.CHARACTER_ATTRIBUTE_BOLD);
    }

    public void testSixelAbortViaCanSubOverflowAndReset() {
        withTerminalSized(10, 4);
        mTerminal.resize(10, 4, 10, 10);
        TerminalBuffer screen = mTerminal.getScreen();

        // CAN (0x18) and SUB (0x1A) mid-Sixel cancel immediately without printing garbage or wedging DCS.
        enterString("\033Pq\"1;1;20;20#0!10~\u0018A");
        enterString("\033Pq\"1;1;20;20#0!10~\u001aB");
        assertEquals(0, screen.getTerminalBitmapsCount());
        assertLineIs(0, "AB        ");

        // Overlong unrecognized DCS must reset ESC_DCS__CHECK_IF_SIXEL on abort so future Sixel still works.
        enterString("\033P");
        String chunk = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        for (int i = 0; i < 300; i++) {
            enterString(chunk);
        }
        enterString("\033[2J\033[H\033[?8452h\033Pq!10~\033\\");
        assertTrue(TextStyle.isTerminalBitmap(screen.getStyleAt(0, 0)));

        // Calling reset() while a Sixel stream is in-flight clears fast-path DCS state cleanly.
        enterString("\033[2J\033[H\033Pq#0!10~");
        mTerminal.reset();
        enterString("HI");
        assertLineIs(0, "HI        ");
    }

}
