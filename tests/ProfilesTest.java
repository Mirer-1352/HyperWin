/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

import java.util.Map;
import java.util.Set;

public final class ProfilesTest {
    private static int checks;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }

    public static void main(String[] args) {
        String windows = "com.microsoft.rdc.androidx", moonlight = "com.limelight.qiin", probe = "com.example.probe";
        Set<String> scoped = Set.of(windows, moonlight, probe);
        int beta = ShortcutFeatures.BETA2, fn = ShortcutFeatures.FN_LOCK, win = ShortcutFeatures.WIN_PASS;
        Profiles profiles = new Profiles(beta | fn, Map.of(), Map.of(), Map.of());
        check(profiles.effective(windows) == (beta | fn), "legacy enabled Fn carries into new app defaults");
        check((beta & fn) == 0, "fresh installation starts with Fn off");
        profiles.setAppMask(windows, win | fn);
        profiles.setAppMask(moonlight, 0);
        String remote = profiles.addGroup("远程");
        check(profiles.groups().get(remote).mask() == (beta | fn), "new groups start from defaults");
        profiles.setGroupMask(remote, ShortcutFeatures.ALT_QUEUE);
        profiles.setMembers(remote, scoped, Set.of(windows, moonlight));
        check(profiles.effective(windows) == ShortcutFeatures.ALT_QUEUE, "group replaces member masks");
        check(profiles.appMask(windows) == (win | fn), "joining preserves the individual mask");
        check(profiles.effective(probe) == (beta | fn), "unrelated app uses its own default");
        String another = profiles.addGroup("另一个组");
        profiles.setGroupMask(another, ShortcutFeatures.WIN_UI);
        profiles.setMembers(another, scoped, Set.of(windows));
        check(another.equals(profiles.groupFor(windows)), "moving replaces former membership");
        check(profiles.effective(windows) == ShortcutFeatures.WIN_UI, "moving uses new group immediately");
        check(remote.equals(profiles.groupFor(moonlight)), "moving one app leaves other group members alone");
        profiles.setMembers(remote, Set.of(windows), Set.of());
        check(remote.equals(profiles.groupFor(moonlight)), "editing visible members preserves hidden apps");
        check(!profiles.policies(Set.of(windows)).containsKey(moonlight), "removed scope is not in runtime policy");
        check(profiles.policies(Set.of(moonlight)).get(moonlight) == ShortcutFeatures.ALT_QUEUE,
                "returning scope regains membership");
        profiles.renameGroup(another, "新组名");
        check("新组名".equals(profiles.groups().get(another).name())
                && another.equals(profiles.groupFor(windows)), "renaming keeps membership");
        profiles.deleteGroup(another);
        check(profiles.groupFor(windows) == null && profiles.effective(windows) == (win | fn),
                "deleting restores individual configuration");
        profiles.setMembers(remote, scoped, Set.of());
        check(profiles.groupFor(moonlight) == null && profiles.effective(moonlight) == 0,
                "removing a member preserves explicitly disabled features");
        Profiles copy = profiles.copy();
        copy.setAppMask(windows, 0x40000000);
        check(copy.effective(windows) == 0 && profiles.effective(windows) == (win | fn),
                "saved edit is isolated and strips future flag bits");
        check(ShortcutFeatures.allowWinChord(beta, true), "existing Win+Tab defaults enabled");
        check(!ShortcutFeatures.allowWinChord(ShortcutFeatures.WIN_TAB_PASS, true), "Win+Tab requires Win delivery");
        check(!ShortcutFeatures.allowWinChord(win, true), "Win+Tab has an independent delivery gate");
        check(ShortcutFeatures.allowWinChord(win, false), "generic Win combinations continue without Win+Tab");
        System.out.println("ProfilesTest: all " + checks + " checks passed");
    }
}
