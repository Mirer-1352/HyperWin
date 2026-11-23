/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

/** Tracks paired Alt+Tab edges without changing ordinary Alt or Tab. */
final class AltTabPresses {
    static final int ALT_LEFT = 57;
    static final int ALT_RIGHT = 58;
    static final int TAB = 61;
    private final WinPresses alt = new WinPresses();
    private final WinPresses tab = new WinPresses();

    static boolean observes(int key) {
        return key == TAB || key == ALT_LEFT || key == ALT_RIGHT;
    }

    boolean route(int device, int key, long downTime, boolean firstDown, boolean up,
                  boolean hasAltTabModifiers, Object focus) {
        if (key == ALT_LEFT || key == ALT_RIGHT) {
            if (firstDown) alt.target(device, key, downTime, true, focus);
            if (up) alt.finish(device, key, downTime);
            return false;
        }
        if (key != TAB) return false;
        Object selected = firstDown && hasAltTabModifiers && alt.hasTarget(device, ALT_LEFT, ALT_RIGHT, focus)
                ? focus : null;
        Object original = tab.target(device, key, downTime, firstDown, selected);
        return focus != null && original == focus;
    }

    void finishTab(int device, long downTime) {
        tab.finish(device, TAB, downTime);
    }
}
