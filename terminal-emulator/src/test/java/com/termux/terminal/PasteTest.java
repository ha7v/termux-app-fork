package com.termux.terminal;

/** Tests for {@link TerminalEmulator#paste(String)}. */
public class PasteTest extends TerminalTestCase {

    public void testPasteStripsEscC1AndNormalizesNewlines() {
        withTerminalSized(5, 3);
        mTerminal.paste("a\r\nb\nc\r\r\nd");
        assertEquals("a\rb\rc\r\rd", mOutput.getOutputAndClear());
        // ESC sitting between \r and \n is removed first, so \r\n collapses to a single \r.
        mTerminal.paste("x\r\u001B\ny");
        assertEquals("x\ry", mOutput.getOutputAndClear());
        mTerminal.paste("\u001Bab\u0085cd\u009F");
        assertEquals("abcd", mOutput.getOutputAndClear());
    }

    public void testFullyStrippedPasteWritesNothing() {
        withTerminalSized(5, 3);
        mTerminal.paste("\u001B\u009B");
        assertEquals("", mOutput.getOutputAndClear());
    }

    public void testBracketedPasteFramesSanitizedBody() {
        withTerminalSized(5, 3);
        enterString("\033[?2004h");
        // An embedded "\033[201~" must not be able to terminate the bracket early.
        mTerminal.paste("a\033[201~b\r\nc");
        assertEquals("\033[200~a[201~b\rc\033[201~", mOutput.getOutputAndClear());
    }

}
