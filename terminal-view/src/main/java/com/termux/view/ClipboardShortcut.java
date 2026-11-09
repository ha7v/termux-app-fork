package com.termux.view;

/**
 * Pure decision logic for the desktop-style clipboard shortcuts.
 *
 * <p>Kept free of Android and app-module types so that every input source that can deliver a
 * character or a key (hardware key events, the extra keys bar, the soft keyboard/IME and, later,
 * touch gestures) can share one implementation, and so that the whole matrix of
 * modifier/selection/paste-mode combinations can be unit tested on the JVM.
 *
 * <p>The caller supplies the state it knows about; this class only decides <em>what</em> a key
 * means, never <em>how</em> to copy or paste. In particular {@code shift} and {@code alt} are
 * explicit parameters rather than being folded into {@code ctrl}, so that future semantics (for
 * example "Shift forces terminal-local selection") can be added without changing the signature.
 */
public final class ClipboardShortcut {

    /** What a key resolves to. */
    public enum Action {
        /** The key is not a clipboard shortcut and must be processed as usual. */
        NONE,
        /** Copy the current selection. */
        COPY,
        /** Paste the clipboard. */
        PASTE,
    }

    /** Bare {@code Ctrl+V} is sent to the program unchanged. */
    public static final int BARE_CTRL_V_OFF = 0;
    /** Bare {@code Ctrl+V} pastes only if the program enabled bracketed paste (DECSET 2004). */
    public static final int BARE_CTRL_V_BRACKETED = 1;
    /** Bare {@code Ctrl+V} always pastes. */
    public static final int BARE_CTRL_V_ALWAYS = 2;

    private ClipboardShortcut() {}

    /**
     * Resolve the clipboard action of a key.
     *
     * @param codePoint          the unmodified character of the key, e.g. {@code 'c'} or {@code 'v'}.
     * @param ctrl               whether Ctrl is held.
     * @param shift              whether Shift is held.
     * @param alt                whether Alt is held. Alt combinations are application shortcuts
     *                           (such as {@code Ctrl+Alt+V} for paste), so they never resolve here.
     * @param hasSelection       whether text is currently selected.
     * @param bracketedPaste     whether the running program enabled bracketed paste (DECSET 2004).
     * @param bareCtrlVPasteMode one of {@link #BARE_CTRL_V_OFF}, {@link #BARE_CTRL_V_BRACKETED},
     *                           {@link #BARE_CTRL_V_ALWAYS}.
     * @return the action to perform, or {@link Action#NONE} to let the key through unchanged.
     */
    public static Action resolve(int codePoint, boolean ctrl, boolean shift, boolean alt,
                                 boolean hasSelection, boolean bracketedPaste, int bareCtrlVPasteMode) {
        if (!ctrl || alt) return Action.NONE;

        switch (Character.toLowerCase((char) codePoint)) {
            case 'c':
                // Copy only when there is something to copy; otherwise Ctrl+C must reach the
                // program as SIGINT. Ctrl+Shift+C is the explicit desktop copy shortcut and is
                // covered by the same branch since it does not change the meaning of 'c'.
                return hasSelection ? Action.COPY : Action.NONE;
            case 'v':
                // Ctrl+Shift+V always pastes; bare Ctrl+V depends on the preference.
                if (shift) return Action.PASTE;
                switch (bareCtrlVPasteMode) {
                    case BARE_CTRL_V_ALWAYS:
                        return Action.PASTE;
                    case BARE_CTRL_V_BRACKETED:
                        return bracketedPaste ? Action.PASTE : Action.NONE;
                    default:
                        return Action.NONE;
                }
            default:
                return Action.NONE;
        }
    }
}
