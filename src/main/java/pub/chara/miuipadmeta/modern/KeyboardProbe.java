/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

import android.os.SystemClock;
import android.view.KeyEvent;
import java.util.Locale;

/** Foreground MetaProbe event formatting. Text and ordinary character input are excluded. */
final class KeyboardProbe {
    private long start = -1;

    void start() { start = SystemClock.uptimeMillis(); }
    void stop() { start = -1; }

    String format(KeyEvent event) {
        if (start < 0 || SystemClock.uptimeMillis() - start >= ModuleControl.TEST_MILLIS
                || !observes(event.getKeyCode())) return null;
        String action = switch (event.getAction()) {
            case KeyEvent.ACTION_DOWN -> "DOWN";
            case KeyEvent.ACTION_UP -> "UP";
            default -> "OTHER";
        };
        return line(String.format(Locale.ROOT,
                "%s %s  code=%d scan=%d meta=0x%X repeat=%d canceled=%s event=%d down=%d device=%d",
                KeyEvent.keyCodeToString(event.getKeyCode()), action, event.getKeyCode(), event.getScanCode(),
                event.getMetaState(), event.getRepeatCount(), event.isCanceled(), event.getEventTime(),
                event.getDownTime(), event.getDeviceId()));
    }

    String line(String message) {
        return String.format(Locale.ROOT, "%6dms  %s", SystemClock.uptimeMillis() - start, message);
    }

    private static boolean observes(int key) {
        if ((key >= KeyEvent.KEYCODE_F1 && key <= KeyEvent.KEYCODE_F12)
                || (key >= KeyEvent.KEYCODE_F13 && key <= KeyEvent.KEYCODE_F24)) return true;
        return switch (key) {
            case KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
                    KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
                    KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
                    KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
                    KeyEvent.KEYCODE_TAB, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_X,
                    KeyEvent.KEYCODE_SYSRQ, KeyEvent.KEYCODE_FUNCTION,
                    KeyEvent.KEYCODE_BRIGHTNESS_DOWN, KeyEvent.KEYCODE_BRIGHTNESS_UP,
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_VOLUME_MUTE,
                    KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP -> true;
            default -> false;
        };
    }
}
