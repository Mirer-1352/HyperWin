/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

/** Small, host-testable policy. This stage intentionally contains no Alt+Tab rule. */
final class WinPolicy {
    private WinPolicy() {}

    static boolean isWinEvent(int keyCode, int metaState) {
        // Android KEYCODE_META_LEFT=117, META_RIGHT=118, META_META_ON=0x10000.
        // Explicit key codes include the final UP, whose meta state is normally zero.
        return isWinKey(keyCode) || (metaState & 0x10000) != 0;
    }

    static boolean isWinKey(int keyCode) {
        return keyCode == 117 || keyCode == 118;
    }

    static boolean isWinTab(int keyCode, int modifiers) {
        // KeyEvent.getModifiers() normalizes left/right Meta into META_META_ON.
        return keyCode == 61 && (modifiers & 0x10000) != 0 && (modifiers & ~0x70000) == 0;
    }
}
