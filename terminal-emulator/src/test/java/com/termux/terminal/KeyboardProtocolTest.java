package com.termux.terminal;

import android.view.KeyEvent;

public class KeyboardProtocolTest extends TerminalTestCase {

    public void testKittyCsiPushPopSetAndQuery() {
        withTerminalSized(10, 4);
        assertEquals(0, mTerminal.getKittyKeyboardFlags());

        // Query initial state: CSI ? u -> CSI ? 0 u
        enterString("\033[?u");
        assertEquals("\033[?0u", mOutput.getOutputAndClear());

        // Push flags=1: CSI > 1 u
        enterString("\033[>1u\033[?u");
        assertEquals(1, mTerminal.getKittyKeyboardFlags());
        assertEquals("\033[?1u", mOutput.getOutputAndClear());

        // Push flags=31 (all 5 bits): bit 2 (REPORT_EVENT_TYPES) must be masked out -> 29 (1|4|8|16)
        enterString("\033[>31u\033[?u");
        assertEquals(29, mTerminal.getKittyKeyboardFlags());
        assertEquals("\033[?29u", mOutput.getOutputAndClear());

        // In-place bitwise OR (mode 2) and AND-NOT (mode 3) via CSI = flags ; mode u
        enterString("\033[=16;3u\033[?u");
        assertEquals(13, mTerminal.getKittyKeyboardFlags());
        assertEquals("\033[?13u", mOutput.getOutputAndClear());

        enterString("\033[=16;2u\033[?u");
        assertEquals(29, mTerminal.getKittyKeyboardFlags());
        assertEquals("\033[?29u", mOutput.getOutputAndClear());

        // In-place set (default mode=1): CSI = 5 u
        enterString("\033[=5u\033[?u");
        assertEquals(5, mTerminal.getKittyKeyboardFlags());
        assertEquals("\033[?5u", mOutput.getOutputAndClear());

        // Pop 1 entry -> restores 1
        enterString("\033[<u\033[?u");
        assertEquals(1, mTerminal.getKittyKeyboardFlags());
        assertEquals("\033[?1u", mOutput.getOutputAndClear());

        // Pop more entries than stack depth -> cleanly resets to 0
        enterString("\033[<5u\033[?u");
        assertEquals(0, mTerminal.getKittyKeyboardFlags());
        assertEquals("\033[?0u", mOutput.getOutputAndClear());
    }

    public void testKittyStackMaxDepthEviction() {
        withTerminalSized(10, 4);
        // Push 20 entries: first pushes 0 onto stack, subsequent ones push 1..19 (masked to supported bits).
        for (int i = 0; i < 20; i++) {
            enterString("\033[>" + ((i % 2 == 0) ? 1 : 5) + "u");
        }
        assertEquals(5, mTerminal.getKittyKeyboardFlags());
        // Popping MAX_STACK_DEPTH (16) entries reaches bottom of bounded stack; one more resets to 0.
        enterString("\033[<16u");
        assertEquals(5, mTerminal.getKittyKeyboardFlags());
        enterString("\033[<1u");
        assertEquals(0, mTerminal.getKittyKeyboardFlags());
    }

    public void testDualScreenStackIsolationOnCrashedTui() {
        withTerminalSized(10, 4);
        // Shell on main screen enables Flag 1
        enterString("\033[>1u");
        assertEquals(1, mTerminal.getKittyKeyboardFlags());

        // TUI enters alternate screen (DECSET 1049) and pushes Flag 1 | 8 = 9
        enterString("\033[?1049h");
        assertEquals(0, mTerminal.getKittyKeyboardFlags());
        enterString("\033[>9u");
        assertEquals(9, mTerminal.getKittyKeyboardFlags());

        // TUI crashes / exits alt screen WITHOUT popping Kitty stack
        enterString("\033[?1049l");
        assertEquals(1, mTerminal.getKittyKeyboardFlags());

        // Re-entering alt screen starts clean at 0 (no leaked state from previous TUI)
        enterString("\033[?1049h");
        assertEquals(0, mTerminal.getKittyKeyboardFlags());
        enterString("\033[?1049l");
        assertEquals(1, mTerminal.getKittyKeyboardFlags());

        // Soft reset (DECSTR) and hard reset (RIS) clear protocol state
        enterString("\033[!p");
        assertEquals(0, mTerminal.getKittyKeyboardFlags());
    }

    public void testOtherLessThanAndEqualsCsiRemainIgnored() {
        withTerminalSized(6, 2);
        // Tertiary DA (CSI = c) and private sequences like CSI < 1 t must be silently ignored, printing nothing
        enterString("AB\033[=c\033[=0c\033[<1;2tCD")
            .assertLinesAre("ABCD  ", "      ");
    }

    public void testKittyKeyDisambiguationAndProgressiveFlags() {
        // Flag 1: Disambiguate escape codes
        int f1 = KeyboardProtocol.KITTY_FLAG_DISAMBIGUATE_ESCAPE_CODES;
        // Unmodified Enter, Tab, Backspace keep legacy bytes under Flag 1 alone
        assertEquals("\r", KeyHandler.getCode(KeyEvent.KEYCODE_ENTER, 0, false, false, f1, 0));
        assertEquals("\t", KeyHandler.getCode(KeyEvent.KEYCODE_TAB, 0, false, false, f1, 0));
        assertEquals("\u007f", KeyHandler.getCode(KeyEvent.KEYCODE_DEL, 0, false, false, f1, 0));
        assertNull(KeyboardProtocol.encodeCodePoint('\r', '\r', false, false, false, f1, 0));
        assertNull(KeyboardProtocol.encodeCodePoint('\t', '\t', false, false, false, f1, 0));
        assertNull(KeyboardProtocol.encodeCodePoint('a', 'a', false, false, false, f1, 0));

        // Escape and modified special keys use CSI u under Flag 1
        assertEquals("\033[27u", KeyHandler.getCode(KeyEvent.KEYCODE_ESCAPE, 0, false, false, f1, 0));
        assertEquals("\033[13;2u", KeyHandler.getCode(KeyEvent.KEYCODE_ENTER, KeyHandler.KEYMOD_SHIFT, false, false, f1, 0));
        assertEquals("\033[13;5u", KeyHandler.getCode(KeyEvent.KEYCODE_ENTER, KeyHandler.KEYMOD_CTRL, false, false, f1, 0));
        assertEquals("\033[9;2u", KeyHandler.getCode(KeyEvent.KEYCODE_TAB, KeyHandler.KEYMOD_SHIFT, false, false, f1, 0));
        assertEquals("\033[127;5u", KeyHandler.getCode(KeyEvent.KEYCODE_DEL, KeyHandler.KEYMOD_CTRL, false, false, f1, 0));
        // Soft keyboard Shift+Enter (ExtraKeys SHIFT + IME '\r')
        assertEquals("\033[13;2u", KeyboardProtocol.encodeCodePoint('\r', '\r', false, false, true, f1, 0));

        // Disambiguating classic C0 collisions: Ctrl+I vs Tab, Ctrl+M vs Enter, Ctrl+[ vs Escape
        assertEquals("\033[105;5u", KeyboardProtocol.encodeCodePoint('i', 'i', true, false, false, f1, 0));
        assertEquals("\033[109;5u", KeyboardProtocol.encodeCodePoint('m', 'm', true, false, false, f1, 0));
        assertEquals("\033[91;5u", KeyboardProtocol.encodeCodePoint('[', '[', true, false, false, f1, 0));
        assertEquals("\033[97;6u", KeyboardProtocol.encodeCodePoint('A', 'a', true, false, true, f1, 0));
        assertEquals("\033[59;5u", KeyboardProtocol.encodeCodePoint(';', ';', true, false, false, f1, 0));

        // Space flows through encodeCodePoint when Kitty or modifyOtherKeys is active
        assertNull(KeyHandler.getCode(KeyEvent.KEYCODE_SPACE, KeyHandler.KEYMOD_CTRL, false, false, f1, 0));
        assertEquals("\033[32;5u", KeyboardProtocol.encodeCodePoint(' ', ' ', true, false, false, f1, 0));

        // Flag 1 | Flag 4 (report alternate shifted key)
        int f1_4 = f1 | KeyboardProtocol.KITTY_FLAG_REPORT_ALTERNATE_KEYS;
        assertEquals("\033[97:65;6u", KeyboardProtocol.encodeCodePoint('A', 'a', true, false, true, f1_4, 0));
        assertEquals("\033[59:58;6u", KeyboardProtocol.encodeCodePoint(':', ';', true, false, true, f1_4, 0));

        // Flag 8 | Flag 16 (report all keys + associated text)
        int fAll = KeyboardProtocol.SUPPORTED_KITTY_FLAGS;
        assertEquals("\033[13u", KeyHandler.getCode(KeyEvent.KEYCODE_ENTER, 0, false, false, fAll, 0));
        assertEquals("\033[9u", KeyHandler.getCode(KeyEvent.KEYCODE_TAB, 0, false, false, fAll, 0));
        assertEquals("\033[127u", KeyHandler.getCode(KeyEvent.KEYCODE_DEL, 0, false, false, fAll, 0));
        assertEquals("\033[97;1;97u", KeyboardProtocol.encodeCodePoint('a', 'a', false, false, false, fAll, 0));
        assertEquals("\033[97:65;2;65u", KeyboardProtocol.encodeCodePoint('A', 'a', false, false, true, fAll, 0));
        assertEquals("\033[20320;1;20320u", KeyboardProtocol.encodeCodePoint('你', '你', false, false, false, fAll, 0));
    }

    public void testModifyOtherKeysModesAndQuery() {
        withTerminalSized(10, 4);
        assertEquals(0, mTerminal.getModifyOtherKeysMode());

        // Query initial state: CSI ? 4 m -> CSI > 4 ; 0 m
        enterString("\033[?4m");
        assertEquals("\033[>4;0m", mOutput.getOutputAndClear());

        // Enable mode 2: CSI > 4 ; 2 m
        enterString("\033[>4;2m\033[?4m");
        assertEquals(2, mTerminal.getModifyOtherKeysMode());
        assertEquals("\033[>4;2m", mOutput.getOutputAndClear());

        // Mode 1 vs Mode 2 encoding semantics
        // Mode 1 preserves well-known C0/Meta mappings (Ctrl+I, Ctrl+Space, Shift+Tab, Alt+Enter)
        assertNull(KeyboardProtocol.encodeCodePoint('i', 'i', true, false, false, 0, 1));
        assertNull(KeyboardProtocol.encodeCodePoint(' ', ' ', true, false, false, 0, 1));
        assertEquals("\033[Z", KeyHandler.getCode(KeyEvent.KEYCODE_TAB, KeyHandler.KEYMOD_SHIFT, false, false, 0, 1));
        assertEquals("\033\r", KeyHandler.getCode(KeyEvent.KEYCODE_ENTER, KeyHandler.KEYMOD_ALT, false, false, 0, 1));
        // Mode 1 encodes ambiguous combinations like Shift+Enter, Ctrl+Enter, Ctrl+;, Ctrl+Shift+A
        assertEquals("\033[27;2;13~", KeyHandler.getCode(KeyEvent.KEYCODE_ENTER, KeyHandler.KEYMOD_SHIFT, false, false, 0, 1));
        assertEquals("\033[27;5;13~", KeyHandler.getCode(KeyEvent.KEYCODE_ENTER, KeyHandler.KEYMOD_CTRL, false, false, 0, 1));
        assertEquals("\033[27;5;59~", KeyboardProtocol.encodeCodePoint(';', ';', true, false, false, 0, 1));
        assertEquals("\033[27;6;65~", KeyboardProtocol.encodeCodePoint('A', 'a', true, false, true, 0, 1));

        // Mode 2 encodes all modified keys including Ctrl+I, Ctrl+M, Shift+Tab, Alt+Enter, Ctrl+Backspace
        assertEquals("\033[27;5;105~", KeyboardProtocol.encodeCodePoint('i', 'i', true, false, false, 0, 2));
        assertEquals("\033[27;5;109~", KeyboardProtocol.encodeCodePoint('m', 'm', true, false, false, 0, 2));
        assertEquals("\033[27;2;9~", KeyHandler.getCode(KeyEvent.KEYCODE_TAB, KeyHandler.KEYMOD_SHIFT, false, false, 0, 2));
        assertEquals("\033[27;3;13~", KeyHandler.getCode(KeyEvent.KEYCODE_ENTER, KeyHandler.KEYMOD_ALT, false, false, 0, 2));
        assertEquals("\033[27;5;127~", KeyHandler.getCode(KeyEvent.KEYCODE_DEL, KeyHandler.KEYMOD_CTRL, false, false, 0, 2));

        // Reset via CSI > 4 m or CSI > 4 ; 0 m
        enterString("\033[>4m\033[?4m");
        assertEquals(0, mTerminal.getModifyOtherKeysMode());
        assertEquals("\033[>4;0m", mOutput.getOutputAndClear());
    }

    public void testModernTuiStartupHandshakeAndShiftEnter() {
        withTerminalSized(80, 24);

        // Exact startup sequence emitted by Bubbletea v2 / Ultraviolet TUIs (e.g. Antigravity CLI `agy`):
        // OSC 11 query + OSC 7501 + DECST8C (?5W) + Bracketed Paste + modifyOtherKeys=2 + Kitty Push(5) + Kitty Query + DECSCUSR
        enterString("\033]11;?\007\033]7501;?\007\033[?5W\033[?2004h\033[>4;2m\033[>5u\033[?u\033[0 q");

        // OSC 11 background reply followed by Kitty ?5u (Flags 1 | 4)
        String reply = mOutput.getOutputAndClear();
        assertTrue("Expected Kitty ?5u reply in: " + reply, reply.endsWith("\033[?5u"));
        assertEquals(5, mTerminal.getKittyKeyboardFlags());
        assertEquals(2, mTerminal.getModifyOtherKeysMode());

        int kittyFlags = mTerminal.getKittyKeyboardFlags();
        int mokMode = mTerminal.getModifyOtherKeysMode();

        // Unmodified Enter still sends legacy CR so regular submission works
        assertEquals("\r", KeyHandler.getCode(KeyEvent.KEYCODE_ENTER, 0, false, false, kittyFlags, mokMode));
        assertNull(KeyboardProtocol.encodeCodePoint('\r', '\r', false, false, false, kittyFlags, mokMode));

        // Shift+Enter across Hardware Enter, Hardware Numpad Enter, and Soft Keyboard / ExtraKeys Shift+Enter
        // all emit CSI 13;2 u (inserting a newline instead of submitting in `agy` / Claude Code / Codex)
        assertEquals("\033[13;2u", KeyHandler.getCode(KeyEvent.KEYCODE_ENTER, KeyHandler.KEYMOD_SHIFT, false, false, kittyFlags, mokMode));
        assertEquals("\033[13;2u", KeyHandler.getCode(KeyEvent.KEYCODE_NUMPAD_ENTER, KeyHandler.KEYMOD_SHIFT, false, false, kittyFlags, mokMode));
        assertEquals("\033[13;2u", KeyboardProtocol.encodeCodePoint('\r', '\r', false, false, true, kittyFlags, mokMode));

        // XTVERSION query (CSI > q and CSI > 0 q) responds with DCS > | Termux ST
        enterString("\033[>q");
        assertEquals("\033P>|Termux\033\\", mOutput.getOutputAndClear());
        enterString("\033[>0q");
        assertEquals("\033P>|Termux\033\\", mOutput.getOutputAndClear());
    }
}
