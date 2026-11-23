/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

import java.util.HashMap;
import java.util.Map;

/** An initial DOWN chooses a target. Retries, repeats and UP cannot choose a new one. */
final class WinPresses {
    private static final int MAX_PRESSES = 32;
    private record Press(long downTime, Object target) {}
    private final Map<Long, Press> presses = new HashMap<>();

    synchronized Object target(int device, int key, long downTime, boolean initialDown, Object selected) {
        long index = index(device, key);
        Press press = presses.get(index);
        if (initialDown && (press == null || press.downTime != downTime)) {
            if (press == null && presses.size() >= MAX_PRESSES) presses.clear();
            press = new Press(downTime, selected);
            presses.put(index, press);
        }
        return press != null && press.downTime == downTime ? press.target : null;
    }

    synchronized boolean hasTarget(int device, Object target) {
        return hasTarget(device, 117, 118, target);
    }

    synchronized boolean hasTarget(int device, int leftKey, int rightKey, Object target) {
        if (target == null) return false;
        Press left = presses.get(index(device, leftKey));
        Press right = presses.get(index(device, rightKey));
        return (left != null && left.target == target) || (right != null && right.target == target);
    }

    synchronized void finish(int device, int key, long downTime) {
        long index = index(device, key);
        Press press = presses.get(index);
        if (press != null && press.downTime == downTime) presses.remove(index);
    }

    private static long index(int device, int key) {
        return ((long) device << 32) | key;
    }
}
