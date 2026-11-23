/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

/** Verified on jinghu's two IIC keyboard interfaces. No name-only matching. */
final class FnKeyMap {
    static final int KEYBOARD = 0xa3, CONSUMER = 0xa4;
    record Device(String name, int vendor, int product, boolean virtual) {
        boolean supported() {
            return !virtual && vendor == 0x15d9 &&
                    ((product == KEYBOARD && "Xiaomi Keyboard".equals(name)) ||
                     (product == CONSUMER && "Xiaomi Consumer".equals(name)));
        }
    }
    record Output(int keyCode, int scanCode) {}
    private static final int[] SPECIAL_KEYS = {220,221,333,120,337,336,88,85,87,164,25,24};
    private static final int[] SPECIAL_SCANS = {224,225,190,99,194,193,165,164,163,113,114,115};
    private static final int[] F_SCANS = {59,60,61,62,63,64,65,66,67,68,87,88};
    // Physical F3/F5/F6: mic mute (BUTTON_4), assistant (BUTTON_3), DND (BUTTON_2).
    static final int MIC_MUTE_SHORTCUT = 191;
    static final int ASSISTANT_SHORTCUT = 190;
    static final int DO_NOT_DISTURB_SHORTCUT = 189;
    private static final int[] MIUI_SHORTCUT_KEYS = {
            0, 0, MIC_MUTE_SHORTCUT, 0, ASSISTANT_SHORTCUT, DO_NOT_DISTURB_SHORTCUT, 0, 0, 0, 0, 0, 0
    };

    static Output swap(Device device, int key, int scan, boolean legacyMiuiShortcuts) {
        if (device == null || !device.supported()) return null;
        for (int i = 0; i < 12; i++) {
            int legacy = MIUI_SHORTCUT_KEYS[i];
            if (device.product == KEYBOARD && key == 131 + i && scan == F_SCANS[i]) {
                int special = legacyMiuiShortcuts && legacy != 0 ? legacy : SPECIAL_KEYS[i];
                return new Output(special, SPECIAL_SCANS[i]);
            }
            int product = i >= 2 && i <= 5 ? KEYBOARD : CONSUMER;
            if (device.product == product && scan == SPECIAL_SCANS[i]
                    && (key == SPECIAL_KEYS[i] || (legacyMiuiShortcuts && legacy != 0 && key == legacy))) {
                return new Output(131 + i, F_SCANS[i]);
            }
        }
        return null;
    }
    static boolean isMiuiShortcut(Output output) {
        return output.keyCode == MIC_MUTE_SHORTCUT || output.keyCode == ASSISTANT_SHORTCUT
                || output.keyCode == DO_NOT_DISTURB_SHORTCUT;
    }
    private FnKeyMap() {}
}
