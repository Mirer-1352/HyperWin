/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

/** Stable mask shared by the settings UI and system_server. */
final class ShortcutFeatures {
    static final int WIN_UI = 1, WIN_PASS = 1 << 1;
    static final int ALT_DISPATCH = 1 << 2, ALT_QUEUE = 1 << 3;
    static final int WIN_TAB_FALLBACK = 1 << 4, WIN_TAB_PASS = 1 << 5;
    static final int FN_LOCK = 1 << 6;
    static final int BETA2 = WIN_UI | WIN_PASS | ALT_DISPATCH | ALT_QUEUE | WIN_TAB_FALLBACK | WIN_TAB_PASS;
    static final int ALL = BETA2 | FN_LOCK;

    static int sanitize(int mask) { return mask & ALL; }
    static boolean has(int mask, int feature) { return (mask & feature) == feature; }

    static boolean allowWinChord(int mask, boolean winTab) {
        return has(mask, WIN_PASS) && (!winTab || has(mask, WIN_TAB_PASS));
    }

    private ShortcutFeatures() {}
}
