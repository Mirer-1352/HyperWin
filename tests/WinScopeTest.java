package pub.chara.miuipadmeta.modern;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public final class WinScopeTest {
    private static int checks;

    private static void check(boolean result, String label) {
        checks++;
        if (!result) throw new AssertionError(label);
    }

    public static void main(String[] args) {
        check(ScopeSelection.apps(Arrays.asList("android", "system", "com.limelight.qiin",
                        null, "com.limelight.qiin", "", "other/com.test", "com.microsoft.rdc.androidx"))
                .equals(Set.of("com.limelight.qiin", "com.microsoft.rdc.androidx")), "manager scope filters virtual entries");
        check(ScopeSelection.apps(List.of("system")).isEmpty(), "removing last app yields empty scope");
        check(ScopeSelection.fromFramework(List.of("com.limelight.qiin")).isEmpty(), "boot snapshot requires system scope");
        check(ScopeSelection.fromFramework(List.of("system", "android", "com.limelight.qiin"))
                .equals(Set.of("com.limelight.qiin")), "boot snapshot retains approved apps only");
        check(ScopeSelection.apps(List.of("com.miwinkey.diagnostic", "com.limelight.qiin")).contains("com.miwinkey.diagnostic"),
                "diagnostic package remains selectable");

        final int device = 4, left = 117, right = 118;
        WinPresses presses = new WinPresses();
        Object first = new Object(), next = new Object();
        check(presses.target(device, left, 100, false, first) == null, "orphan UP cannot gain scope");
        check(presses.target(device, left, 100, true, first) == first, "selected DOWN captures focus");
        check(presses.hasTarget(device, first), "Win chord uses initial focus");
        check(!presses.hasTarget(device, next), "Win chord cannot move to next focus");
        check(!presses.hasTarget(device + 1, first), "separate keyboard cannot inherit Win hold");
        check(presses.target(device, left, 100, true, next) == first, "delayed redispatch cannot retarget original DOWN");
        check(presses.target(device, left, 100, false, next) == first, "UP retains original target for UI cleanup");
        presses.finish(device, left, 100);
        check(!presses.hasTarget(device, first), "UP clears hold");
        check(presses.target(device, left, 200, true, null) == null, "unselected DOWN chooses Android default");
        check(presses.target(device, left, 200, true, next) == null, "unselected retry cannot adopt a selected focus");
        check(presses.target(device, right, 210, true, next) == next, "right Win is independent");
        presses.finish(device, left, 200);
        check(presses.hasTarget(device, next), "releasing left Win preserves right Win");
        check(presses.target(device, right, 220, true, first) == first, "new DOWN replaces a held key without prior UP");
        presses.finish(device, right, 210);
        check(presses.hasTarget(device, first), "stale UP cannot clear latest press");
        presses.finish(device, right, 220);
        check(!presses.hasTarget(device, first), "fresh UP clears latest press");
        System.out.println("WinScopeTest: " + checks + " checks passed");
    }
}
