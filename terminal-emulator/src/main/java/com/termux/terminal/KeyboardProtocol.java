package com.termux.terminal;

/**
 * State machine and key sequence encoder for modern terminal keyboard protocols:
 * <ul>
 *   <li><b>Kitty Keyboard Protocol</b> (progressive enhancement flags {@code 1 | 4 | 8 | 16},
 *       with isolated main/alternate screen stacks so a crashed TUI cannot poison the shell).</li>
 *   <li><b>xterm {@code modifyOtherKeys}</b> ({@code CSI > 4 ; Pv m}, levels 0, 1, and 2).</li>
 * </ul>
 * <p>
 * Flag {@code 2} ({@code REPORT_EVENT_TYPES}, key release/repeat reporting) is intentionally masked
 * out of {@link #SUPPORTED_KITTY_FLAGS} because Android software input methods commit text via
 * {@code InputConnection#commitText} without emitting paired key-up events, which would leave TUI
 * applications waiting indefinitely for key releases. Masking unsupported flags and reporting the
 * active subset via {@code CSI ? u} follows the Kitty progressive enhancement specification.
 */
public final class KeyboardProtocol {

    /** Bit 0 (1): Disambiguate escape codes. */
    public static final int KITTY_FLAG_DISAMBIGUATE_ESCAPE_CODES = 1;
    /** Bit 1 (2): Report event types (press/repeat/release) — intentionally unsupported on Android IMEs. */
    public static final int KITTY_FLAG_REPORT_EVENT_TYPES = 1 << 1;
    /** Bit 2 (4): Report alternate keys (shifted key codepoint). */
    public static final int KITTY_FLAG_REPORT_ALTERNATE_KEYS = 1 << 2;
    /** Bit 3 (8): Report all keys as escape codes. */
    public static final int KITTY_FLAG_REPORT_ALL_KEYS_AS_ESCAPE_CODES = 1 << 3;
    /** Bit 4 (16): Report associated text with key events. */
    public static final int KITTY_FLAG_REPORT_ASSOCIATED_TEXT = 1 << 4;

    /** Mask of Kitty progressive enhancement flags supported by Termux. */
    public static final int SUPPORTED_KITTY_FLAGS =
        KITTY_FLAG_DISAMBIGUATE_ESCAPE_CODES |
        KITTY_FLAG_REPORT_ALTERNATE_KEYS |
        KITTY_FLAG_REPORT_ALL_KEYS_AS_ESCAPE_CODES |
        KITTY_FLAG_REPORT_ASSOCIATED_TEXT;

    /** Maximum depth per screen stack, evicting oldest entry on overflow per Kitty spec. */
    public static final int MAX_STACK_DEPTH = 16;

    private final int[] mMainStack = new int[MAX_STACK_DEPTH];
    private int mMainStackDepth = 0;
    private int mMainFlags = 0;

    private final int[] mAltStack = new int[MAX_STACK_DEPTH];
    private int mAltStackDepth = 0;
    private int mAltFlags = 0;

    /** xterm {@code modifyOtherKeys} level: 0 (off), 1, or 2. */
    private int mModifyOtherKeysMode = 0;

    public int getKittyFlags(boolean altScreen) {
        return altScreen ? mAltFlags : mMainFlags;
    }

    /** Push current flags onto the active screen's stack and set {@code rawFlags} ({@code CSI > flags u}). */
    public void pushKittyFlags(boolean altScreen, int rawFlags) {
        int sanitized = Math.max(0, rawFlags) & SUPPORTED_KITTY_FLAGS;
        if (altScreen) {
            mAltStackDepth = pushToStack(mAltStack, mAltStackDepth, mAltFlags);
            mAltFlags = sanitized;
        } else {
            mMainStackDepth = pushToStack(mMainStack, mMainStackDepth, mMainFlags);
            mMainFlags = sanitized;
        }
    }

    /** Pop {@code count} entries from the active screen's stack ({@code CSI < count u}). */
    public void popKittyFlags(boolean altScreen, int count) {
        int effectiveCount = (count <= 0) ? 1 : count;
        if (altScreen) {
            if (effectiveCount > mAltStackDepth) {
                mAltStackDepth = 0;
                mAltFlags = 0;
            } else {
                mAltStackDepth -= effectiveCount;
                mAltFlags = mAltStack[mAltStackDepth];
            }
        } else {
            if (effectiveCount > mMainStackDepth) {
                mMainStackDepth = 0;
                mMainFlags = 0;
            } else {
                mMainStackDepth -= effectiveCount;
                mMainFlags = mMainStack[mMainStackDepth];
            }
        }
    }

    /**
     * Update flags in-place ({@code CSI = flags ; mode u}).
     * {@code mode}: 1 = set, 2 = bitwise OR, 3 = bitwise AND-NOT.
     */
    public void setKittyFlags(boolean altScreen, int rawFlags, int mode) {
        int sanitized = Math.max(0, rawFlags) & SUPPORTED_KITTY_FLAGS;
        int current = altScreen ? mAltFlags : mMainFlags;
        int updated;
        switch (mode) {
            case 1:
                updated = sanitized;
                break;
            case 2:
                updated = current | sanitized;
                break;
            case 3:
                updated = current & ~sanitized;
                break;
            default:
                return;
        }
        if (altScreen) {
            mAltFlags = updated;
        } else {
            mMainFlags = updated;
        }
    }

    /** Clear alternate-screen Kitty state on alternate buffer entry/exit so crashed TUIs cannot leak state. */
    public void clearAltScreenState() {
        mAltFlags = 0;
        mAltStackDepth = 0;
    }

    public int getModifyOtherKeysMode() {
        return mModifyOtherKeysMode;
    }

    public void setModifyOtherKeysMode(int mode) {
        if (mode <= 0) {
            mModifyOtherKeysMode = 0;
        } else if (mode == 1 || mode == 2) {
            mModifyOtherKeysMode = mode;
        }
    }

    /** Reset all keyboard protocol state on terminal reset ({@code RIS}). */
    public void reset() {
        mMainFlags = 0;
        mMainStackDepth = 0;
        mAltFlags = 0;
        mAltStackDepth = 0;
        mModifyOtherKeysMode = 0;
    }

    private static int pushToStack(int[] stack, int depth, int value) {
        if (depth == stack.length) {
            System.arraycopy(stack, 1, stack, 0, stack.length - 1);
            stack[stack.length - 1] = value;
            return stack.length;
        }
        stack[depth] = value;
        return depth + 1;
    }

    /** Compute standard 1-based modifier parameter ({@code 1 + bitmask}) from {@link KeyHandler} flags. */
    public static int modifierParamFromKeyMod(int keyMod) {
        boolean shift = (keyMod & KeyHandler.KEYMOD_SHIFT) != 0;
        boolean alt = (keyMod & KeyHandler.KEYMOD_ALT) != 0;
        boolean ctrl = (keyMod & KeyHandler.KEYMOD_CTRL) != 0;
        return modifierParam(ctrl, alt, shift);
    }

    /** Compute standard 1-based modifier parameter ({@code 1 + (shift=1 | alt=2 | ctrl=4)}). */
    public static int modifierParam(boolean ctrl, boolean alt, boolean shift) {
        int mask = 0;
        if (shift) mask |= 1;
        if (alt) mask |= 2;
        if (ctrl) mask |= 4;
        return 1 + mask;
    }

    /**
     * Encode special keys ({@code Enter=13}, {@code Tab=9}, {@code Backspace=127}, {@code Escape=27})
     * when Kitty protocol or {@code modifyOtherKeys} applies.
     *
     * @return the protocol escape sequence, or {@code null} if legacy handling should run.
     */
    public static String encodeSpecialKey(int keyCodePoint, boolean ctrl, boolean alt, boolean shift,
                                          int kittyFlags, int modifyOtherKeysMode) {
        final int modParam = modifierParam(ctrl, alt, shift);

        if (kittyFlags != 0) {
            final boolean reportAll = (kittyFlags & KITTY_FLAG_REPORT_ALL_KEYS_AS_ESCAPE_CODES) != 0;
            final boolean disambiguate = (kittyFlags & KITTY_FLAG_DISAMBIGUATE_ESCAPE_CODES) != 0;
            if (!reportAll && !disambiguate) return null;

            switch (keyCodePoint) {
                case 27: // Escape is always disambiguated under Flag 1 or Flag 8.
                    return formatKittyCsiU(27, 0, modParam, 0);
                case 13: // Enter
                case 9:  // Tab
                case 127: // Backspace
                    // Per Kitty specification, unmodified Enter/Tab/Backspace keep legacy bytes under
                    // Flag 1 alone so shell subcommands remain usable, and switch to CSI u when modified
                    // or when Flag 8 (report all keys as escape codes) is enabled.
                    if (reportAll || modParam > 1) {
                        return formatKittyCsiU(keyCodePoint, 0, modParam, 0);
                    }
                    return null;
                default:
                    return null;
            }
        }

        if (modifyOtherKeysMode > 0 && modParam > 1) {
            switch (keyCodePoint) {
                case 27: // Escape
                    if (modifyOtherKeysMode == 2 || ctrl || shift) {
                        return formatModifyOtherKeys(modParam, 27);
                    }
                    return null;
                case 13: // Enter
                    // In Mode 1, bare Alt+Enter keeps legacy ESC CR; Shift/Ctrl combinations use CSI 27.
                    if (modifyOtherKeysMode == 2 || ctrl || shift) {
                        return formatModifyOtherKeys(modParam, 13);
                    }
                    return null;
                case 9: // Tab
                    // In Mode 1, bare Shift+Tab keeps legacy CSI Z (back-tab); Ctrl/Alt combinations use CSI 27.
                    if (modifyOtherKeysMode == 2 || ctrl || alt) {
                        return formatModifyOtherKeys(modParam, 9);
                    }
                    return null;
                case 127: // Backspace
                    if (modifyOtherKeysMode == 2) {
                        return formatModifyOtherKeys(modParam, 127);
                    }
                    return null;
                default:
                    return null;
            }
        }

        return null;
    }

    /**
     * Encode a Unicode code point according to active Kitty Keyboard Protocol or {@code modifyOtherKeys}.
     *
     * @param codePoint          the produced Unicode code point (with Shift applied if applicable).
     * @param unshiftedCodePoint the base (unshifted) key code point from the keymap, or {@code 0} if unknown.
     * @return the protocol escape sequence to transmit, or {@code null} to fall back to legacy UTF-8/C0 output.
     */
    public static String encodeCodePoint(int codePoint, int unshiftedCodePoint,
                                         boolean ctrl, boolean alt, boolean shift,
                                         int kittyFlags, int modifyOtherKeysMode) {
        if (codePoint < 0) return null;

        // Route C0 / DEL special keys uniformly whether they arrived from KeyEvent or InputConnection.
        if (codePoint == '\r' || codePoint == '\t' || codePoint == 27 || codePoint == 127) {
            return encodeSpecialKey(codePoint, ctrl, alt, shift, kittyFlags, modifyOtherKeysMode);
        }

        if (codePoint < 32) {
            return null;
        }

        final int modParam = modifierParam(ctrl, alt, shift);

        if (kittyFlags != 0) {
            final boolean reportAll = (kittyFlags & KITTY_FLAG_REPORT_ALL_KEYS_AS_ESCAPE_CODES) != 0;
            final boolean disambiguate = (kittyFlags & KITTY_FLAG_DISAMBIGUATE_ESCAPE_CODES) != 0;
            final boolean reportAltKeys = (kittyFlags & KITTY_FLAG_REPORT_ALTERNATE_KEYS) != 0;
            final boolean reportText = (kittyFlags & KITTY_FLAG_REPORT_ASSOCIATED_TEXT) != 0;

            if (reportAll || (disambiguate && (ctrl || alt))) {
                int baseKey = (unshiftedCodePoint >= 32 && unshiftedCodePoint != 127)
                    ? Character.toLowerCase(unshiftedCodePoint)
                    : Character.toLowerCase(codePoint);
                int shiftedKey = (reportAltKeys && shift && codePoint != baseKey) ? codePoint : 0;
                int associatedText = (reportAll && reportText && !ctrl && !alt) ? codePoint : 0;
                return formatKittyCsiU(baseKey, shiftedKey, modParam, associatedText);
            }
            return null;
        }

        if (modifyOtherKeysMode > 0 && (ctrl || alt)) {
            if (modifyOtherKeysMode == 2) {
                return formatModifyOtherKeys(modParam, codePoint);
            }
            // Mode 1: only encode combinations that lack a distinct traditional C0 / Meta representation.
            if (ctrl && !alt) {
                if (shift || !hasLegacyUnshiftedControlMapping(codePoint)) {
                    return formatModifyOtherKeys(modParam, codePoint);
                }
            } else if (ctrl && alt) {
                if (shift || !hasLegacyUnshiftedControlMapping(codePoint)) {
                    return formatModifyOtherKeys(modParam, codePoint);
                }
            }
        }

        return null;
    }

    private static boolean hasLegacyUnshiftedControlMapping(int cp) {
        if (cp >= 'a' && cp <= 'z') return true;
        switch (cp) {
            case ' ':
            case '@':
            case '[':
            case '\\':
            case ']':
            case '^':
            case '_':
            case '/':
            case '?':
            case '2':
            case '3':
            case '4':
            case '5':
            case '6':
            case '7':
            case '8':
                return true;
            default:
                return false;
        }
    }

    private static String formatKittyCsiU(int baseKey, int shiftedKey, int modParam, int associatedText) {
        StringBuilder sb = new StringBuilder(20);
        sb.append("\033[").append(baseKey);
        if (shiftedKey > 0) {
            sb.append(':').append(shiftedKey);
        }
        if (associatedText > 0) {
            sb.append(';').append(modParam).append(';').append(associatedText);
        } else if (modParam > 1) {
            sb.append(';').append(modParam);
        }
        sb.append('u');
        return sb.toString();
    }

    private static String formatModifyOtherKeys(int modParam, int codePoint) {
        return "\033[27;" + modParam + ";" + codePoint + "~";
    }
}
