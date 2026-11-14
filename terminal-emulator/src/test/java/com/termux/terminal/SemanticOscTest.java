package com.termux.terminal;

import java.util.ArrayList;
import java.util.List;

public class SemanticOscTest extends TerminalTestCase {

    public void testOsc8ZeroAllocationOnPlainRowsAndBasicBinding() {
        withTerminalSized(12, 4);
        enterString("plain text\r\n");
        TerminalRow row0 = mTerminal.getScreen().mLines[mTerminal.getScreen().externalToInternalRow(0)];
        assertNull(row0.mHyperlinkIds);
        assertNull(mTerminal.getScreen().getHyperlinkAt(0, 0));

        // OSC 8 with id param + BEL, and closing OSC 8 with ST
        enterString("\033]8;id=link1;https://termux.dev/docs\007Docs\033]8;;\033\\ plain");
        assertEquals("https://termux.dev/docs", mTerminal.getScreen().getHyperlinkAt(0, 1));
        assertEquals("https://termux.dev/docs", mTerminal.getScreen().getHyperlinkAt(3, 1));
        assertNull(mTerminal.getScreen().getHyperlinkAt(4, 1));
        assertNull(mTerminal.getScreen().getHyperlinkAt(6, 1));
    }

    public void testOsc8WideCharactersOverwriteAndErase() {
        withTerminalSized(10, 3);
        // Wide CJK hyperlink occupies columns 0..1 and 2..3
        enterString("\033]8;;https://example.com/cjk\007点击\033]8;;\007AB");
        assertEquals("https://example.com/cjk", mTerminal.getScreen().getHyperlinkAt(0, 0));
        assertEquals("https://example.com/cjk", mTerminal.getScreen().getHyperlinkAt(1, 0));
        assertEquals("https://example.com/cjk", mTerminal.getScreen().getHyperlinkAt(2, 0));
        assertEquals("https://example.com/cjk", mTerminal.getScreen().getHyperlinkAt(3, 0));
        assertNull(mTerminal.getScreen().getHyperlinkAt(4, 0));

        // Overwrite first wide char at col 0 with unlinked ASCII 'X' -> clears both col 0 and col 1
        enterString("\rX");
        assertNull(mTerminal.getScreen().getHyperlinkAt(0, 0));
        assertNull(mTerminal.getScreen().getHyperlinkAt(1, 0));
        assertEquals("https://example.com/cjk", mTerminal.getScreen().getHyperlinkAt(2, 0));

        // Erase to end of line (CSI K) clears remaining hyperlinks
        enterString("\033[K");
        assertNull(mTerminal.getScreen().getHyperlinkAt(2, 0));
        assertNull(mTerminal.getScreen().getHyperlinkAt(3, 0));
    }

    public void testOsc8ReflowPreservesHyperlinks() {
        withTerminalSized(10, 4);
        enterString("go \033]8;;https://example.org/reflow\007ABCDEF\033]8;;\007!");
        assertEquals("https://example.org/reflow", mTerminal.getScreen().getHyperlinkAt(3, 0));
        assertEquals("https://example.org/reflow", mTerminal.getScreen().getHyperlinkAt(8, 0));
        assertNull(mTerminal.getScreen().getHyperlinkAt(9, 0));

        // Narrow terminal to 6 columns -> "go ABC" on row 0, "DEF!" on row 1
        resize(6, 4);
        assertNull(mTerminal.getScreen().getHyperlinkAt(2, 0));
        assertEquals("https://example.org/reflow", mTerminal.getScreen().getHyperlinkAt(3, 0));
        assertEquals("https://example.org/reflow", mTerminal.getScreen().getHyperlinkAt(5, 0));
        assertEquals("https://example.org/reflow", mTerminal.getScreen().getHyperlinkAt(0, 1));
        assertEquals("https://example.org/reflow", mTerminal.getScreen().getHyperlinkAt(2, 1));
        assertNull(mTerminal.getScreen().getHyperlinkAt(3, 1));
    }

    public void testOsc8SchemeSecurityAndGarbageCollection() {
        withTerminalSized(12, 3);
        // Reject dangerous schemes
        enterString("\033]8;;javascript:alert(1)\007JS\033]8;;\007");
        enterString("\033]8;;file:///etc/passwd\007F\033]8;;\007");
        enterString("\033]8;;intent://scan/#Intent;end\007I\033]8;;\007");
        enterString("\033]8;;content://media/external\007C\033]8;;\007");
        for (int c = 0; c < 6; c++) {
            assertNull(mTerminal.getScreen().getHyperlinkAt(c, 0));
        }

        // Accept mailto: and https:
        enterString("\r\n\033]8;;mailto:dev@termux.dev\007Mail\033]8;;\007");
        assertEquals("mailto:dev@termux.dev", mTerminal.getScreen().getHyperlinkAt(0, 1));

        // Verify GC reclaims scrolled-out links across > MAX_HYPERLINKS (1024) unique URLs
        withTerminalSized(12, 3);
        for (int i = 0; i < 1400; i++) {
            enterString("\033]8;;https://example.com/" + i + "\007L\033]8;;\007\r\n");
        }
        enterString("\033]8;;https://example.com/final\007END\033]8;;\007");
        assertEquals("https://example.com/final", mTerminal.getScreen().getHyperlinkAt(0, 2));
        List<CharSequence> live = new ArrayList<>(mTerminal.getScreen().collectHyperlinks());
        assertTrue(live.contains("https://example.com/final"));
        assertFalse(live.contains("https://example.com/0"));
    }

    public void testOsc7WorkingDirectoryParsingAndSshSafety() {
        withTerminalSized(10, 4);
        assertNull(mTerminal.getWorkingDirectory());

        // Local empty host and localhost with UTF-8 percent encoding + literal '+'
        enterString("\033]7;file:///data/data/com.termux/files/home/%E4%B8%AD%E6%96%87%20dir/c++\007");
        assertEquals("/data/data/com.termux/files/home/中文 dir/c++", mTerminal.getWorkingDirectory());

        enterString("\033]7;kitty-shell-cwd://localhost/sdcard/Download\033\\");
        assertEquals("/sdcard/Download", mTerminal.getWorkingDirectory());

        // Matching explicit local hostname succeeds; remote SSH hostname is rejected and clears stale local OSC 7
        assertEquals("/tmp/work",
            TerminalEmulator.parseOsc7WorkingDirectory("file://pixel-9/tmp/work", "pixel-9"));
        assertNull(
            TerminalEmulator.parseOsc7WorkingDirectory("file://prod-server/var/log", "pixel-9"));

        enterString("\033]7;file://remote-ssh-host/etc\007");
        assertNull(mTerminal.getWorkingDirectory());
    }

    public void testOsc133PromptNavigationAndCommandOutputExtraction() {
        withTerminalSized(10, 4);
        // Command 1 (will scroll into transcript):
        enterString("\033]133;A\007$ \033]133;B\007echo 1\r\n");
        enterString("\033]133;C\007first\r\n");
        // Command 2 (multi-line + wrapped line):
        enterString("\033]133;D;0\007\033]133;A\007$ \033]133;B\007cmd2\r\n");
        enterString("\033]133;C\0070123456789ABCDEF\r\nline2\r\n");
        // Next prompt waiting for input:
        enterString("\033]133;D;0\007\033]133;A\007$ ");

        // Extract last command output: wrapped line joined + newline + second line, excluding prompts!
        assertTrue(mTerminal.getScreen().hasSemanticCommandOutput());
        assertEquals("0123456789ABCDEF\nline2", mTerminal.getScreen().getLastCommandOutput());

        // Prompt navigation across screen & scrollback:
        Integer prev1 = mTerminal.getScreen().findPromptRow(3, true);
        assertNotNull(prev1);
        Integer prev2 = mTerminal.getScreen().findPromptRow(prev1, true);
        assertNotNull(prev2);
        assertTrue(prev2 < prev1);
        assertEquals(prev1, mTerminal.getScreen().findPromptRow(prev2, false));

        // Zero-output command (e.g. `true`):
        enterString("\033]133;B\007true\r\n\033]133;C\007\033]133;D;0\007\033]133;A\007$ ");
        assertEquals("", mTerminal.getScreen().getLastCommandOutput());
    }

    public void testOsc1337ReportCellSizeFixedPrefix() {
        withTerminalSized(10, 4);
        mTerminal.resize(10, 4, 18, 36);
        enterString("\033]1337;ReportCellSize\007");
        assertEquals("\033]1337;ReportCellSize=36;18\007", mOutput.getOutputAndClear());
    }
}
