/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

import java.util.HashMap;
import java.util.Map;

/** Physical DOWN fixes a decision until its matching UP; repeats never adopt a new target. */
final class FnLockPresses {
    static final int LIMIT = 64;
    enum Action { PASS, EMIT, CONSUME }
    record Press(int device, int scan, long downTime, FnKeyMap.Output output, Object target, long generation) {}
    record Decision(Action action, Press press) {}
    private final Map<Long, Press> held = new HashMap<>();
    private long generation;

    synchronized Decision route(int device, int scan, long downTime, boolean down, int repeat,
            FnKeyMap.Output candidate, Object target, boolean enabled) {
        long index = ((long) device << 32) | (scan & 0xffffffffL);
        Press press = held.get(index);
        if (down && repeat == 0 && (press == null || press.downTime != downTime)) {
            if (press == null && held.size() >= LIMIT) return new Decision(Action.PASS, null);
            press = new Press(device, scan, downTime, enabled && target != null ? candidate : null, target, generation);
            held.put(index, press);
        }
        if (press == null || press.downTime != downTime) return new Decision(Action.PASS, null);
        if (!down) held.remove(index);
        if (press.output == null) return new Decision(Action.PASS, press);
        return new Decision(enabled && press.target == target && press.generation == generation
                ? Action.EMIT : Action.CONSUME, press);
    }

    synchronized void removeDevice(int device) { held.keySet().removeIf(key -> (int) (key >> 32) == device); }
    synchronized void invalidate() { generation++; }
}
