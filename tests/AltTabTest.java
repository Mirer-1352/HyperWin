package pub.chara.miuipadmeta.modern;

public final class AltTabTest {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        AltTabPresses state = new AltTabPresses();
        Object first = new Object(), next = new Object();
        int device = 10, alt = AltTabPresses.ALT_LEFT, right = AltTabPresses.ALT_RIGHT, tab = AltTabPresses.TAB;

        check(!state.route(device, tab, 1, true, false, false, first), "ordinary Tab uses Android policy");
        check(!state.route(device, tab, 2, true, false, true, first), "modifier bit alone cannot create a held Alt");
        check(!state.route(device, alt, 10, true, false, true, first), "Alt DOWN is observed without overriding policy");
        check(!state.route(device, tab, 11, true, false, false, first), "unrelated modifier combination stays native");
        check(!state.route(device + 1, tab, 12, true, false, true, first), "different keyboard cannot inherit Alt");
        check(!state.route(device, tab, 13, true, false, true, next), "different window cannot inherit Alt");
        check(!state.route(device, tab, 13, true, false, true, first), "retry of rejected Tab cannot be reclassified");
        check(state.route(device, tab, 20, true, false, true, first), "Alt+Tab DOWN has its original target");
        check(state.route(device, tab, 20, false, false, true, first), "repeat keeps its target");
        check(!state.route(device, alt, 10, false, true, false, first), "Alt UP stays native");
        check(state.route(device, tab, 20, false, true, false, first), "Tab UP survives releasing Alt first");
        state.finishTab(device, 20);
        check(!state.route(device, tab, 20, false, true, false, first), "finished chord does not pass another Tab UP");
        check(!state.route(device, right, 30, true, false, true, null), "Alt starting outside scope is native");
        check(!state.route(device, tab, 31, true, false, true, next), "Alt held outside scope cannot move into selection");
        state.route(device, right, 40, true, false, true, next);
        check(state.route(device, tab, 41, true, false, true, next), "right Alt can initiate a chord");
        check(!state.route(device, tab, 41, false, true, false, first), "stale Tab UP cannot affect a new scope");
        System.out.println("AltTabTest: " + checks + " checks passed");
    }
}
