/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

public final class FnLockTest {
    private static int checks;
    private static void check(boolean value, String label) {
        checks++;
        if (!value) throw new AssertionError(label);
    }

    private static FnKeyMap.Output swap(FnKeyMap.Device device, int key, int scan) {
        return FnKeyMap.swap(device, key, scan, false);
    }
    public static void main(String[] args) {
        FnKeyMap.Device keyboard = new FnKeyMap.Device("Xiaomi Keyboard",0x15d9,0xa3,false);
        FnKeyMap.Device consumer = new FnKeyMap.Device("Xiaomi Consumer",0x15d9,0xa4,false);
        // Independent expectations transcribed from the operator's two raw event captures + Generic.kl.
        int[] special = {220,221,333,120,337,336,88,85,87,164,25,24};
        int[] scans = {224,225,190,99,194,193,165,164,163,113,114,115};
        int[] fs = {59,60,61,62,63,64,65,66,67,68,87,88};
        for (int i=0;i<12;i++) {
            FnKeyMap.Device origin = i>=2 && i<=5 ? keyboard : consumer;
            check(new FnKeyMap.Output(131+i,fs[i]).equals(swap(origin,special[i],scans[i])), "ordinary F"+(i+1));
            check(new FnKeyMap.Output(special[i],scans[i]).equals(swap(keyboard,131+i,fs[i])), "Fn F"+(i+1));
            check(swap(origin,special[i],scans[i]+1000)==null,"scan mismatch "+i);
            check(swap(origin,special[i]+1000,scans[i])==null,"key mismatch "+i);
            check(swap(consumer,131+i,fs[i])==null,"consumer cannot impersonate F row "+i);
            int legacy = switch (i + 1) { case 3 -> 191; case 5 -> 190; case 6 -> 189; default -> 0; };
            int expectedShortcut = legacy == 0 ? special[i] : legacy;
            check(new FnKeyMap.Output(expectedShortcut,scans[i]).equals(
                    FnKeyMap.swap(keyboard,131+i,fs[i],true)), "HyperOS Fn F"+(i+1));
            if (legacy != 0) {
                check(new FnKeyMap.Output(131+i,fs[i]).equals(
                        FnKeyMap.swap(keyboard,legacy,scans[i],true)), "HyperOS direct F"+(i+1));
                check(FnKeyMap.swap(keyboard,legacy,scans[i]+1,true)==null,"wrong shortcut scan "+i);
                check(FnKeyMap.swap(consumer,legacy,scans[i],true)==null,"wrong shortcut interface "+i);
                check(swap(keyboard,legacy,scans[i])==null,"unrecognized ROM shortcut "+i);
            }
        }
        check(FnKeyMap.swap(keyboard, 189, 190, true) == null, "F6 shortcut cannot match F3 scan");
        check(FnKeyMap.swap(keyboard, 191, 193, true) == null, "F3 shortcut cannot match F6 scan");
        check(swap(null,220,224)==null,"missing device");
        for (FnKeyMap.Device excluded : new FnKeyMap.Device[]{
                new FnKeyMap.Device("Xiaomi Keyboard",1,0xa3,false),
                new FnKeyMap.Device("Xiaomi Keyboard",0x15d9,0xa5,false),
                new FnKeyMap.Device("Other Keyboard",0x15d9,0xa3,false),
                new FnKeyMap.Device("Xiaomi Keyboard",0x15d9,0xa3,true),
                new FnKeyMap.Device("Xiaomi Consumer",0x15d9,0xa3,false)}) {
            check(swap(excluded,131,59)==null,"unsupported identity "+excluded);
        }
        check(swap(keyboard,25,114)==null,"ordinary keyboard volume is not verified Consumer row");
        check(swap(keyboard,29,30)==null,"letter unchanged");
        FnLockPresses state = new FnLockPresses();
        FnKeyMap.Output f1 = new FnKeyMap.Output(131,59);
        Object app = new Object(), other = new Object();
        check(state.route(10,224,1,false,0,f1,app,true).action()==FnLockPresses.Action.PASS,"orphan UP");
        var press = state.route(10,224,2,true,0,f1,app,true);
        check(press.action()==FnLockPresses.Action.EMIT,"eligible DOWN");
        check(state.route(10,224,2,true,1,f1,app,true).press()==press.press(),"repeat preserves press");
        check(state.route(10,224,2,true,0,f1,other,true).action()==FnLockPresses.Action.CONSUME,"retry cannot change focus");
        check(state.route(10,224,2,false,0,f1,other,true).action()==FnLockPresses.Action.CONSUME,"UP outside consumes original");
        check(state.route(10,224,2,false,0,f1,app,true).action()==FnLockPresses.Action.PASS,"UP cleared");
        check(state.route(10,224,3,true,0,f1,app,false).action()==FnLockPresses.Action.PASS,"disabled DOWN");
        check(state.route(10,224,3,true,0,f1,app,true).action()==FnLockPresses.Action.PASS,"enable during hold cannot remap");
        check(state.route(10,224,3,false,0,f1,app,true).action()==FnLockPresses.Action.PASS,"disabled DOWN gets original UP");
        check(state.route(10,224,4,true,0,f1,null,true).action()==FnLockPresses.Action.PASS,"outside scope");
        check(state.route(10,224,4,true,1,f1,app,true).action()==FnLockPresses.Action.PASS,"entering scope cannot adopt repeat");
        state.route(10,224,4,false,0,f1,app,true);
        state.route(10,224,5,true,0,f1,app,true);
        state.invalidate();
        check(state.route(10,224,5,true,0,f1,app,true).action()==FnLockPresses.Action.CONSUME,"off/on cannot resurrect press");
        check(state.route(10,224,5,false,0,f1,app,true).action()==FnLockPresses.Action.CONSUME,"invalidated UP remains consumed");
        var a = state.route(10,224,6,true,0,f1,app,true);
        var b = state.route(13,224,6,true,0,f1,app,true);
        check(!a.press().equals(b.press()),"same timestamp on two devices remains independent");
        state.removeDevice(10);
        check(state.route(10,224,6,false,0,f1,app,true).action()==FnLockPresses.Action.PASS,"removed device cleared");
        check(state.route(13,224,6,false,0,f1,app,true).action()==FnLockPresses.Action.EMIT,"other device untouched");
        for (int i=0;i<FnLockPresses.LIMIT;i++) state.route(50,i,7,true,0,f1,app,true);
        check(state.route(51,0,7,true,0,f1,app,true).action()==FnLockPresses.Action.PASS,"capacity does not clear held keys");
        check(state.route(50,0,7,false,0,f1,app,true).action()==FnLockPresses.Action.EMIT,"capacity retains original release");
        System.out.println("FnLockTest: all " + checks + " checks passed");
    }
}
