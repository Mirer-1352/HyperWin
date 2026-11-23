package pub.chara.miuipadmeta.modern;

/** Check which keyboard events match the module. */
public final class WinPolicyTest {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
    public static void main(String[] args) {
        check(WinPolicy.isWinEvent(117, 0x30000), "left Win down");
        check(WinPolicy.isWinEvent(117, 0), "left Win up with cleared modifiers");
        check(WinPolicy.isWinEvent(118, 0x50000), "right Win down");
        check(WinPolicy.isWinEvent(118, 0), "right Win up");
        check(WinPolicy.isWinEvent(32, 0x30000), "Win+D down / up while held");
        check(WinPolicy.isWinEvent(52, 0x50000), "right Win+X");
        for (int key : new int[]{0, 3, 4, 24, 25, 26, 29, 32, 52, 57, 58, 61, 111, 113, 114, 141, 142}) {
            check(!WinPolicy.isWinEvent(key, 0), "unmodified unrelated key " + key);
            check(!WinPolicy.isWinEvent(key, 0x12), "Alt combination left alone " + key);
        }
        check(!WinPolicy.isWinEvent(61, 0x13), "Alt+Shift+Tab unchanged");
        check(!WinPolicy.isWinEvent(31, 0x3000), "Ctrl+C unchanged");
        check(!WinPolicy.isWinKey(32), "Win+D does not count as a standalone Win key in policy logs");
        check(WinPolicy.isWinTab(61, 0x30000), "Win+Tab recognizes left Meta");
        check(WinPolicy.isWinTab(61, 0x50000), "Win+Tab recognizes right Meta");
        check(!WinPolicy.isWinTab(61, 0), "bare Tab must retain Android fallback");
        check(!WinPolicy.isWinTab(61, 0x12), "Alt+Tab must retain its existing path");
        check(!WinPolicy.isWinTab(61, 0x30001), "Win+Shift+Tab is a distinct shortcut");
        check(!WinPolicy.isWinTab(32, 0x30000), "Win+D must retain Android fallback");
        System.out.println("WinPolicyTest: " + checks + " checks passed");
    }
}
