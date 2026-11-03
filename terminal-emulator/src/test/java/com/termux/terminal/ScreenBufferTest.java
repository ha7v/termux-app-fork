package com.termux.terminal;

public class ScreenBufferTest extends TerminalTestCase {

	public void testBasics() {
		TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
		assertEquals("", screen.getTranscriptText());
		screen.setChar(0, 0, 'a', 0);
		assertEquals("a", screen.getTranscriptText());
		screen.setChar(0, 0, 'b', 0);
		assertEquals("b", screen.getTranscriptText());
		screen.setChar(2, 0, 'c', 0);
		assertEquals("b c", screen.getTranscriptText());
		screen.setChar(2, 2, 'f', 0);
		assertEquals("b c\n\n  f", screen.getTranscriptText());
		screen.blockSet(0, 0, 2, 2, 'X', 0);
	}

	public void testBlockSet() {
		TerminalBuffer screen = new TerminalBuffer(5, 3, 3);
		screen.blockSet(0, 0, 2, 2, 'X', 0);
		assertEquals("XX\nXX", screen.getTranscriptText());
		screen.blockSet(1, 1, 2, 2, 'Y', 0);
		assertEquals("XX\nXYY\n YY", screen.getTranscriptText());
	}

	public void testGetSelectedText() {
		withTerminalSized(5, 3).enterString("ABCDEFGHIJ").assertLinesAre("ABCDE", "FGHIJ", "     ");
		assertEquals("AB", mTerminal.getSelectedText(0, 0, 1, 0));
		assertEquals("BC", mTerminal.getSelectedText(1, 0, 2, 0));
		assertEquals("CDE", mTerminal.getSelectedText(2, 0, 4, 0));
		assertEquals("FG", mTerminal.getSelectedText(0, 1, 1, 1));
		assertEquals("GH", mTerminal.getSelectedText(1, 1, 2, 1));
		assertEquals("HIJ", mTerminal.getSelectedText(2, 1, 4, 1));

		assertEquals("ABCDEFG", mTerminal.getSelectedText(0, 0, 1, 1));
		withTerminalSized(5, 3).enterString("ABCDE\r\nFGHIJ").assertLinesAre("ABCDE", "FGHIJ", "     ");
		assertEquals("ABCDE\nFG", mTerminal.getSelectedText(0, 0, 1, 1));
	}

	public void testGetSelectedTextJoinFullLines() {
		withTerminalSized(5, 3).enterString("ABCDE\r\nFG");
		assertEquals("ABCDEFG", mTerminal.getScreen().getSelectedText(0, 0, 1, 1, true, true));

		withTerminalSized(5, 3).enterString("ABC\r\nFG");
		assertEquals("ABC\nFG", mTerminal.getScreen().getSelectedText(0, 0, 1, 1, true, true));
	}

	public void testGetSelectedTextKeepsTrailingSpaceOfWrappedLine() {
		withTerminalSized(5, 3).enterString("ABCD EF");
		assertEquals("ABCD EF", mTerminal.getSelectedText(0, 0, 4, 1));
	}

	public void testGetSelectedTextWithWideCharMovedToNextRow() {
		// A wide character that does not fit in the last column is moved to the next row, leaving the
		// last column blank although nothing was written to it, so it is not part of the text.
		withTerminalSized(5, 3).enterString("ABCD中");
		assertEquals("ABCD中", mTerminal.getSelectedText(0, 0, 4, 1));
	}

	public void testGetSelectedTextKeepsWrittenSpaceBeforeWideChar() {
		// The last column holds a space that was written to it, which is part of the text even though
		// the wide character after it did not fit and was moved to the next row.
		withTerminalSized(5, 3).enterString("ABCD 中");
		assertEquals("ABCD 中", mTerminal.getSelectedText(0, 0, 4, 1));
	}

	public void testGetSelectedTextWithWideCharAfterCursorMovedToLastColumn() {
		// The last column was written before the cursor was moved back to it, so its character is part
		// of the text even though the wide character wraps before writing anything.
		withTerminalSized(5, 3).enterString("ABCDE\033[1;5H中");
		assertEquals("ABCDE中", mTerminal.getSelectedText(0, 0, 4, 1));
	}

	public void testGetSelectedTextWithLastColumnWrittenAfterWideCharWrap() {
		// The last column is written after the wide character wrapped, so its character is part of the
		// text even though the wrap left the column blank at the time.
		withTerminalSized(5, 3).enterString("ABCD中\033[1;5HX");
		assertEquals("ABCDX中", mTerminal.getSelectedText(0, 0, 4, 1));
	}

	public void testGetWordAtLocation() {
		withTerminalSized(5, 3).enterString("ABCDEFGHIJ\r\nKLMNO");
		assertEquals("ABCDEFGHIJKLMNO", mTerminal.getScreen().getWordAtLocation(0, 0));
		assertEquals("ABCDEFGHIJKLMNO", mTerminal.getScreen().getWordAtLocation(4, 1));
		assertEquals("ABCDEFGHIJKLMNO", mTerminal.getScreen().getWordAtLocation(4, 2));

		withTerminalSized(5, 3).enterString("ABC DEF GHI ");
		assertEquals("ABC", mTerminal.getScreen().getWordAtLocation(0, 0));
		assertEquals("", mTerminal.getScreen().getWordAtLocation(3, 0));
		assertEquals("DEF", mTerminal.getScreen().getWordAtLocation(4, 0));
		assertEquals("DEF", mTerminal.getScreen().getWordAtLocation(0, 1));
		assertEquals("DEF", mTerminal.getScreen().getWordAtLocation(1, 1));
		assertEquals("GHI", mTerminal.getScreen().getWordAtLocation(0, 2));
		assertEquals("", mTerminal.getScreen().getWordAtLocation(1, 2));
		assertEquals("", mTerminal.getScreen().getWordAtLocation(2, 2));
	}

	public void testGetWordAtLocationAcrossTranscriptBoundary() {
		// A wrapped word whose head has scrolled into the transcript must
		// still be returned whole when tapping its visible tail (#5368).
		String url = "https://www.google.com/search?q=termux+terminal+onclick+url+open+wrapped+long+url+tap+opens+only+first+line";
		withTerminalSized(80, 3).enterString("pad1\r\npad2\r\npad3\r\npad4\r\n" + url + "\r\n$ \r\nlast");
		assertEquals(url, mTerminal.getScreen().getWordAtLocation(5, 0));
		assertEquals(url, mTerminal.getScreen().getWordAtLocation(10, -1));
	}
}
