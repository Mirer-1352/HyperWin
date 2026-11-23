/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyEvent;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Preserves native key repeat and existing accessibility filters. No polling or replacement filter. */
final class FnLockController {
    record Target(Object identity, IBinder token, int uid, boolean enabled) {}
    interface Host {
        Target current();
        void error(String message, Throwable error);
    }
    private static final int INJECTED = 0x01000000, TRUSTED = 0x02000000;
    private static final int FILTERED = 0x04000000, INTERACTIVE = 0x20000000, PASS_TO_USER = 0x40000000;
    private record EventId(int device, int key, int scan, long downTime) {
        static EventId of(KeyEvent event) {
            return new EventId(event.getDeviceId(), event.getKeyCode(), event.getScanCode(), event.getDownTime());
        }
    }
    private record Route(Target target) {}
    private record Pending(Active held) {}
    private static final class Active {
        final FnLockPresses.Press press;
        final Target target;
        final Object service;
        final KeyEvent original;
        final int flags;
        final AtomicBoolean ended = new AtomicBoolean();
        boolean emitted;
        Active(FnLockPresses.Press press, Target target, Object service, KeyEvent event, int flags) {
            this.press = press;
            this.target = target;
            this.service = service;
            original = new KeyEvent(event);
            this.flags = flags;
        }
    }

    final Method queue, dispatch, filter, start, setFilter;
    private final Method inject, enableFilter, recycle, displayId;
    private final Field nativeService, existingFilter, filterLock, keyCode, scanCode, metaState;
    private final boolean legacyMiuiShortcuts;
    private final Host host;
    private final FnLockPresses presses = new FnLockPresses();
    private final ConcurrentHashMap<FnLockPresses.Press, Active> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EventId, Route> routes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EventId, Pending> pending = new ConcurrentHashMap<>();
    private final ThreadLocal<Boolean> rewriting = new ThreadLocal<>();
    private final AtomicBoolean recycleFailureReported = new AtomicBoolean();
    private volatile Map<Integer, FnKeyMap.Device> devices = Map.of();
    private volatile Handler handler;
    private volatile Object serviceInstance;
    private volatile boolean enabled, failed, filterActive;

    FnLockController(ClassLoader loader, Host host) throws ReflectiveOperationException {
        this.host = host;
        Class<?> service = Class.forName("com.android.server.input.InputManagerService", false, loader);
        Class<?> nativeType = Class.forName("com.android.server.input.NativeInputManagerService", false, loader);
        queue = method(service,"interceptKeyBeforeQueueing",int.class,KeyEvent.class,int.class);
        dispatch = method(service,"interceptKeyBeforeDispatching",long.class,IBinder.class,KeyEvent.class,int.class);
        filter = method(service,"filterInputEvent",boolean.class,InputEvent.class,int.class);
        start = method(service,"start",void.class);
        setFilter = method(service,"setInputFilter",void.class,Class.forName("android.view.IInputFilter",false,loader));
        inject = method(nativeType,"injectInputEvent",int.class,InputEvent.class,boolean.class,
                int.class,int.class,int.class,int.class);
        enableFilter = method(nativeType,"setInputFilterEnabled",void.class,boolean.class);
        recycle = method(KeyEvent.class,"recycle",void.class);
        displayId = method(KeyEvent.class,"getDisplayId",int.class);
        nativeService = field(service,"mNative");
        existingFilter = field(service,"mInputFilter");
        filterLock = field(service,"mInputFilterLock");
        keyCode = field(KeyEvent.class,"mKeyCode");
        scanCode = field(KeyEvent.class,"mScanCode");
        metaState = field(KeyEvent.class,"mMetaState");
        legacyMiuiShortcuts = supportsLegacyMiuiShortcuts(loader);
    }

    void bind(Object service) { serviceInstance = service; }

    void start(Context context) {
        if (handler != null) return;
        HandlerThread thread = new HandlerThread("HyperWinFnLock");
        thread.start();
        handler = new Handler(thread.getLooper());
        InputManager manager = context.getSystemService(InputManager.class);
        if (manager == null) throw new IllegalStateException("InputManager not ready");
        Runnable refresh = () -> {
            Map<Integer,FnKeyMap.Device> next = new HashMap<>();
            for (int id : manager.getInputDeviceIds()) {
                InputDevice device = manager.getInputDevice(id);
                if (device == null) continue;
                FnKeyMap.Device identity = new FnKeyMap.Device(device.getName(),device.getVendorId(),
                        device.getProductId(),device.isVirtual());
                if (identity.supported()) next.put(id,identity);
            }
            for (int old : devices.keySet()) if (!next.containsKey(old)) {
                presses.removeDevice(old);
                for (Active held : active.values()) if (held.original.getDeviceId()==old) cancel(held);
            }
            devices = Map.copyOf(next);
        };
        manager.registerInputDeviceListener(new InputManager.InputDeviceListener() {
            @Override public void onInputDeviceAdded(int id) { refresh.run(); }
            @Override public void onInputDeviceRemoved(int id) { refresh.run(); }
            @Override public void onInputDeviceChanged(int id) { refresh.run(); }
        },handler);
        refresh.run();
    }

    void setEnabled(boolean next) {
        if (enabled == next) return;
        if (next != enabled) presses.invalidate();
        if (next && !failed && !filterActive) {
            try {
                if (serviceInstance == null) throw new IllegalStateException("Input service start not observed");
                enableFilter.invoke(nativeService.get(serviceInstance),true);
                filterActive = true;
            } catch (ReflectiveOperationException | RuntimeException error) { fail(error); }
        }
        enabled = next;
        if (!next) for (Active held : active.values()) cancel(held);
        // Keep native filtering enabled after first opt-in so held physical UPs can be consumed safely.
        // Disabled/out-of-scope events simply use the original filter method; no timer is running.
    }

    void filterChanged(Object service) {
        if (!filterActive) return;
        presses.invalidate();
        handler.post(() -> { for (Active held : active.values()) cancel(held); });
        try { enableFilter.invoke(nativeService.get(service),true); }
        catch (ReflectiveOperationException | RuntimeException error) { fail(error); }
    }

    Object onQueue(XposedInterface.Chain chain, KeyEvent event, int flags) throws Throwable {
        if (!filterActive || Boolean.TRUE.equals(rewriting.get()) || (flags&INJECTED)!=0 || (flags&TRUSTED)==0
                || (Integer)displayId.invoke(event)!=0 || !event.isFromSource(InputDevice.SOURCE_KEYBOARD)
                || (event.getAction()!=KeyEvent.ACTION_DOWN && event.getAction()!=KeyEvent.ACTION_UP)) {
            return chain.proceed();
        }
        FnKeyMap.Output output = FnKeyMap.swap(devices.get(event.getDeviceId()), event.getKeyCode(),
                event.getScanCode(), legacyMiuiShortcuts);
        if (output == null) return chain.proceed();
        boolean down = event.getAction()==KeyEvent.ACTION_DOWN;
        Target current = (flags&INTERACTIVE)!=0 ? host.current() : null;
        Target target = current != null && current.enabled ? current : null;
        FnLockPresses.Decision decision = presses.route(event.getDeviceId(),event.getScanCode(),event.getDownTime(),
                down,event.getRepeatCount(),output,target==null ? null : target.identity,enabled && !failed);
        if (decision.action()==FnLockPresses.Action.PASS) return chain.proceed();
        Active held = active.get(decision.press());
        if (decision.action()==FnLockPresses.Action.EMIT && held==null && down) {
            // A new DOWN after a lost UP must retire the previous physical press too.
            for (Active old : active.values()) if (old.original.getDeviceId()==event.getDeviceId()
                    && old.original.getScanCode()==event.getScanCode()) cancel(old);
            held = new Active(decision.press(),target,chain.getThisObject(),event,flags);
            active.put(decision.press(),held);
        }
        if (decision.action()==FnLockPresses.Action.CONSUME && held!=null) cancel(held);
        pending.put(EventId.of(event),new Pending(decision.action()==FnLockPresses.Action.EMIT ? held : null));
        // Do not drop in policy: native policy drops cancel all keys on the device, including Ctrl/Alt.
        // The very next native filter callback consumes this original WITHOUT enqueueing it.
        return 1;
    }

    Object onFilter(XposedInterface.Chain chain, KeyEvent event, int flags) throws Throwable {
        Pending selected = pending.remove(EventId.of(event));
        if (selected == null) return chain.proceed();
        try {
            Active held = selected.held;
            if (held != null) synchronized (held) {
                if (!held.ended.get()) {
                    emit(held,event,flags,false);
                    if (event.getAction()==KeyEvent.ACTION_UP) {
                        held.ended.set(true);
                        active.remove(held.press,held);
                        retire(held);
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) { fail(error); }
        finally {
            try { recycle.invoke(event); }
            catch (ReflectiveOperationException | RuntimeException error) {
                if (recycleFailureReported.compareAndSet(false, true)) host.error("FN_LOCK_RECYCLE_FAILED", error);
            }
        }
        return Boolean.FALSE;
    }

    Long dispatchDecision(IBinder token, KeyEvent event) {
        Route route = routes.get(EventId.of(event));
        if (route == null) return null;
        Target current = host.current();
        if (current==null || current.identity!=route.target.identity || !route.target.token.equals(token)) return -1L;
        return event.isCanceled() ? 0L : null;
    }

    void focusChanged() {
        presses.invalidate();
        Handler worker = handler;
        if (worker != null) worker.post(() -> {
            Target current = host.current();
            for (Active held : active.values()) {
                if (current == null || !current.enabled || current.identity != held.target.identity) cancel(held);
            }
        });
    }

    private void cancel(Active held) {
        synchronized (held) {
            if (!held.ended.compareAndSet(false,true)) return;
            Target current = host.current();
            if (held.emitted && current!=null && current.identity==held.target.identity) {
                KeyEvent up = KeyEvent.changeAction(held.original,KeyEvent.ACTION_UP);
                up = KeyEvent.changeTimeRepeat(up,SystemClock.uptimeMillis(),0,up.getFlags()|KeyEvent.FLAG_CANCELED);
                try { emit(held,up,held.flags|FILTERED,true); }
                catch (ReflectiveOperationException | RuntimeException error) { host.error("FN_LOCK_RELEASE_FAILED",error); }
            }
            // InputDispatcher itself sends cancellation to the previous window on a focus change.
            active.remove(held.press,held);
            retire(held);
        }
    }

    private void emit(Active held, KeyEvent source, int flags, boolean canceled) throws ReflectiveOperationException {
        Target current = host.current();
        if (current==null || current.identity!=held.target.identity) return;
        FnKeyMap.Output output = held.press.output();
        KeyEvent mapped = new KeyEvent(source); // Also preserves hidden displayId.
        keyCode.setInt(mapped,output.keyCode());
        scanCode.setInt(mapped,output.scanCode());
        metaState.setInt(mapped,mapped.getMetaState() & ~KeyEvent.META_FUNCTION_ON);
        int result = 1;
        if (!canceled) {
            rewriting.set(true);
            try { result = (Integer)queue.invoke(held.service,mapped,flags); }
            finally { rewriting.remove(); }
        }
        // These are system shortcuts. MIUI needs both edges, but it can consume just
        // the UP to invoke the action. Keep both out of the app to avoid an orphan DOWN.
        if (FnKeyMap.isMiuiShortcut(output)) return;
        routes.putIfAbsent(EventId.of(mapped),new Route(held.target));
        if (routes.size()>256) throw new IllegalStateException("Too many pending Fn routes");
        int policy = (flags & ~PASS_TO_USER) | FILTERED;
        if ((result&1)!=0) policy |= PASS_TO_USER;
        boolean accessibility;
        synchronized (filterLock.get(held.service)) { accessibility = existingFilter.get(held.service)!=null; }
        if (accessibility) {
            // Existing accessibility owns its filter and host; pass the translated event through it.
            filter.invoke(held.service,new KeyEvent(mapped),policy);
        } else {
            int injected = (Integer)inject.invoke(nativeService.get(held.service),mapped,true,held.target.uid,0,0,policy);
            if (injected!=0) throw new IllegalStateException("Fn lock injection result="+injected);
        }
        held.emitted = true;
    }

    private void retire(Active held) {
        EventId id = new EventId(held.original.getDeviceId(),held.press.output().keyCode(),
                held.press.output().scanCode(),held.original.getDownTime());
        Route route = routes.get(id);
        if (route!=null) handler.postDelayed(() -> routes.remove(id,route),10000);
    }
    private void fail(Throwable error) {
        if (failed) return;
        failed = true;
        presses.invalidate();
        host.error("FN_LOCK_DISABLED mapping failed; original Win hooks remain",error);
        if (handler!=null) handler.post(() -> { for (Active held : active.values()) cancel(held); });
    }

    private static boolean supportsLegacyMiuiShortcuts(ClassLoader loader) {
        try {
            Class<?> util = Class.forName("miui.hardware.input.MiuiKeyEventUtil", false, loader);
            Method match = method(util, "matchMiuiKeyForIICKeyboard", int.class, int.class);
            return (Integer) match.invoke(null, FnKeyMap.MIC_MUTE_SHORTCUT) == 9996
                    && (Integer) match.invoke(null, FnKeyMap.ASSISTANT_SHORTCUT) == 9997
                    && (Integer) match.invoke(null, FnKeyMap.DO_NOT_DISTURB_SHORTCUT) == 9998;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }
    private static Field field(Class<?> owner,String name) throws ReflectiveOperationException {
        Field value = owner.getDeclaredField(name);
        value.setAccessible(true);
        return value;
    }
    private static Method method(Class<?> owner,String name,Class<?> result,Class<?>... args)
            throws ReflectiveOperationException {
        Method value = owner.getDeclaredMethod(name,args);
        if (value.getReturnType()!=result) throw new NoSuchMethodException(name);
        value.setAccessible(true);
        return value;
    }
}
