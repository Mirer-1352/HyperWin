/* SPDX-License-Identifier: GPL-3.0-only
 * System-server Win hooks adapted from CwithW/MiuiPadMeta (45fdee9).
 */
package pub.chara.miuipadmeta.modern;

import android.os.IBinder;
import android.os.Process;
import android.os.UserHandle;
import android.util.Log;
import android.view.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.libxposed.api.XposedModule;

public final class WinModule extends XposedModule {
    private static final String TAG = "HyperWin";
    private static final int MAX_TRACES = 96;
    // KeyGestureController uses this bit to start keyboard combinations only on an interactive display.
    private static final int POLICY_INTERACTIVE = 0x20000000;
    private record Focus(String pkg, IBinder token, Object policy, int uid) {}
    private record Target(Focus focus, int mask) {
        boolean has(int feature) { return ShortcutFeatures.has(mask, feature); }
    }
    private record Dispatch(KeyEvent event, Target target, String phase) {}

    private final WinPresses uiPresses = new WinPresses();
    private final WinPresses dispatchPresses = new WinPresses();
    private final WinPresses winTabPresses = new WinPresses();
    private final AltTabPresses altQueue = new AltTabPresses();
    private final AltTabPresses altDispatch = new AltTabPresses();
    private final ThreadLocal<Dispatch> allowedEvent = new ThreadLocal<>();
    private final AtomicInteger traces = new AtomicInteger();
    private final AtomicInteger altTraces = new AtomicInteger();
    private final AtomicInteger winTabFallbackTraces = new AtomicInteger();

    private volatile boolean ready;
    private volatile boolean altTabReady;
    private Map<String, Integer> policies = Map.of();
    private String modulePackage;
    private boolean configuredFn;
    private Integer previewMask;
    private volatile Focus focused;
    private volatile Target routeTarget;
    private Method keyguardOn;
    private FnLockController fnLock;

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        if (param.isSystemServer()) info("LOAD api=" + getApiVersion());
    }

    @Override public void onSystemServerStarting(SystemServerStartingParam param) {
        if (ready || getApiVersion() < 102) return;
        try {
            installHooks(param.getClassLoader());
            ready = true;
            boolean altReady = installAltQueue(param.getClassLoader());
            boolean winTabReady = installWinTabFallback(param.getClassLoader());
            installFnLock(param.getClassLoader());
            modulePackage = getModuleApplicationInfo().packageName;
            int capabilities = ShortcutFeatures.WIN_UI | ShortcutFeatures.WIN_PASS | ShortcutFeatures.WIN_TAB_PASS;
            if (altReady) capabilities |= ShortcutFeatures.ALT_DISPATCH | ShortcutFeatures.ALT_QUEUE;
            if (winTabReady) capabilities |= ShortcutFeatures.WIN_TAB_FALLBACK;
            if (fnLock != null) capabilities |= ShortcutFeatures.FN_LOCK;
            info("READY scope=lsposed-boot-snapshot defaultDisplay=true ownerUserOnly=true altTab=" + altReady
                    + " winTabFallback=" + winTabReady);
            new BootScopeLoader(modulePackage, capabilities, new BootScopeLoader.Listener() {
                @Override public void applied(ModuleControl.Snapshot snapshot,
                        android.content.Context context, String source) {
                    if (fnLock != null) {
                        try { fnLock.start(context); }
                        catch (RuntimeException | LinkageError error) { failure("FN_LOCK_START_FAILED", error); }
                    }
                    applyPolicies(snapshot, source);
                }

                @Override public void preview(Integer mask) { applyPreview(mask); }

                @Override public void failed(Throwable error) {
                    failure("SCOPE_BOOT_ERROR using Android defaults", error);
                }
            }).start();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            failure("DISABLED unsupported ROM or hook failure", error);
        }
    }

    private void installHooks(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> base = Class.forName("com.android.server.policy.BaseMiuiPhoneWindowManager", false, loader);
        Class<?> phone = Class.forName("com.android.server.policy.PhoneWindowManager", false, loader);
        Class<?> windowPolicy = Class.forName("com.android.server.policy.WindowManagerPolicy$WindowState", false, loader);
        Class<?> window = Class.forName("com.android.server.wm.WindowState", false, loader);
        Class<?> gestures = Class.forName("com.android.server.input.KeyGestureController", false, loader);
        Class<?> input = Class.forName("com.android.server.input.InputManagerServiceStubImpl", false, loader);

        Method focusChanged = find(base, "onDefaultDisplayFocusChangedLw", void.class, windowPolicy);
        Method ui = find(base, "handleMetaKey", void.class, KeyEvent.class);
        Method dispatch = find(gestures, "interceptKeyBeforeDispatching", long.class, IBinder.class, KeyEvent.class, int.class);
        Method vendor = find(input, "shouldSkipShortcutForFocusedApp", boolean.class, KeyEvent.class);
        Method owner = find(windowPolicy, "getOwningPackage", String.class);
        Method ownerUid = find(window, "getOwningUid", int.class);
        Method display = find(window, "getDisplayId", int.class);
        Field token = window.getDeclaredField("mInputChannelToken");
        if (token.getType() != IBinder.class) throw new NoSuchFieldException("Unsupported input-channel token");
        token.setAccessible(true);
        keyguardOn = find(phone, "keyguardOn", boolean.class);

        hook(focusChanged).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            Object original = chain.proceed();
            Object win = chain.getArg(0);
            Focus next = null;
            try {
                if (window.isInstance(win)
                        && ((Integer) display.invoke(win)) == 0
                        && ownerUid.invoke(win) instanceof Integer uid
                        && uid >= Process.FIRST_APPLICATION_UID
                        && UserHandle.getUserHandleForUid(uid).equals(Process.myUserHandle())
                        && owner.invoke(win) instanceof String pkg
                        && token.get(win) instanceof IBinder binder) {
                    next = new Focus(pkg, binder, chain.getThisObject(), uid);
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                failure("FOCUS unavailable", error);
            }
            updateFocus(next);
            return original;
        });

        hook(ui).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (!ready || !(chain.getArg(0) instanceof KeyEvent event)) return chain.proceed();
            Target target = currentTarget(null);
            if (WinPolicy.isWinKey(event.getKeyCode())) {
                // Consume a matching UP if this press skipped the native UI on DOWN.
                // Otherwise let the native handler finish presses it started outside scope.
                boolean suppress = matchPress(uiPresses, event, withFeature(target, ShortcutFeatures.WIN_UI)) != null;
                if (isUp(event)) finish(uiPresses, event);
                trace("UI", event, target, suppress, null);
                return suppress ? null : chain.proceed();
            }
            if (event.isMetaPressed() && uiPresses.hasTarget(event.getDeviceId(), target)) return null;
            return chain.proceed();
        });

        hook(dispatch).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            if (!ready || !(chain.getArg(1) instanceof KeyEvent event)) return chain.proceed();
            boolean winEvent = WinPolicy.isWinEvent(event.getKeyCode(), event.getMetaState());
            boolean altEvent = altTabReady && AltTabPresses.observes(event.getKeyCode());
            if (!winEvent && !altEvent && event.getKeyCode() != AltTabPresses.TAB) return chain.proceed();

            boolean win = WinPolicy.isWinKey(event.getKeyCode());
            Target target = chain.getArg(0) instanceof IBinder binder ? currentTarget(binder) : null;
            boolean allowWin = routeWin(event, target, winEvent, win);
            boolean allowAlt = altEvent && routeAltTab(altDispatch, event,
                    withFeature(target, ShortcutFeatures.ALT_DISPATCH));

            Dispatch previous = allowedEvent.get();
            if (allowWin || allowAlt) allowedEvent.set(new Dispatch(event, target, "DISPATCH"));
            else allowedEvent.remove();
            try {
                Object result = chain.proceed();
                if (win) {
                    if (result instanceof Long decision && (decision < 0 || (isUp(event) && decision == 0))) {
                        finish(dispatchPresses, event);
                    }
                    trace("DISPATCH", event, target, allowWin, result);
                }
                if (altEvent) {
                    if (event.getKeyCode() == AltTabPresses.TAB && result instanceof Long decision
                            && (decision < 0 || (isUp(event) && decision == 0))) {
                        altDispatch.finishTab(event.getDeviceId(), event.getDownTime());
                    }
                    traceAlt("DISPATCH", event, target, allowAlt, result);
                }
                if (event.getKeyCode() == AltTabPresses.TAB && result instanceof Long decision
                        && (decision < 0 || (isUp(event) && decision == 0))) finish(winTabPresses, event);
                return result;
            } finally {
                if (previous == null) allowedEvent.remove();
                else allowedEvent.set(previous);
            }
        });

        hook(vendor).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
            Dispatch dispatchForWin = allowedEvent.get();
            if (ready && dispatchForWin != null && routeTarget == dispatchForWin.target
                    && chain.getArg(0) == dispatchForWin.event) {
                KeyEvent event = dispatchForWin.event;
                if (altTabReady && event.getKeyCode() == AltTabPresses.TAB && !event.isMetaPressed()) {
                    traceAlt("SKIP_" + dispatchForWin.phase, event, dispatchForWin.target, true, true);
                }
                return Boolean.TRUE;
            }
            return chain.proceed();
        });
        info("HOOK focus=" + focusChanged.toGenericString());
        info("HOOK ui=" + ui.toGenericString());
        info("HOOK dispatch=" + dispatch.toGenericString());
        info("HOOK pass=" + vendor.toGenericString());
    }

    private boolean routeWin(KeyEvent event, Target target, boolean winEvent, boolean win) {
        Target eligible = target != null && ShortcutFeatures.allowWinChord(target.mask, false) ? target : null;
        if (win) {
            Object original = matchPress(dispatchPresses, event, eligible);
            return eligible != null && original == eligible;
        }
        boolean heldWin = dispatchPresses.hasTarget(event.getDeviceId(), eligible);
        boolean winTab = WinPolicy.isWinTab(event.getKeyCode(), event.getModifiers());
        if (event.getKeyCode() == AltTabPresses.TAB) {
            Target chord = heldWin && winTab && ShortcutFeatures.allowWinChord(eligible.mask, true) ? eligible : null;
            Object original = matchPress(winTabPresses, event, chord);
            if (winTab) return chord != null && original == chord;
            if (isUp(event) && target != null && original == target) return true;
        }
        return winEvent && heldWin;
    }

    private void installFnLock(ClassLoader loader) {
        try {
            FnLockController controller = new FnLockController(loader, new FnLockController.Host() {
                @Override public FnLockController.Target current() {
                    Target target = currentTarget(null);
                    return target == null ? null : new FnLockController.Target(target.focus,
                            target.focus.token, target.focus.uid, target.has(ShortcutFeatures.FN_LOCK));
                }
                @Override public void info(String message) { WinModule.this.info(message); }
                @Override public void error(String message, Throwable error) { failure(message, error); }
            });
            hook(controller.start).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                controller.bind(chain.getThisObject());
                return result;
            });
            hook(controller.setFilter).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                controller.filterChanged(chain.getThisObject());
                return result;
            });
            hook(controller.filter).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!(chain.getArg(0) instanceof KeyEvent event)) return chain.proceed();
                return controller.onFilter(chain, event, (Integer) chain.getArg(1));
            });
            hook(controller.queue).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!(chain.getArg(0) instanceof KeyEvent event)) return chain.proceed();
                return controller.onQueue(chain, event, (Integer) chain.getArg(1));
            });
            hook(controller.dispatch).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!(chain.getArg(1) instanceof KeyEvent event)) return chain.proceed();
                IBinder token = chain.getArg(0) instanceof IBinder binder ? binder : null;
                Long decision = controller.dispatchDecision(token, event);
                return decision == null ? chain.proceed() : decision;
            });
            fnLock = controller;
            info("HOOK fnLock=" + controller.queue.toGenericString());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            failure("FN_LOCK_UNAVAILABLE unsupported ROM; original Win hooks retained", error);
        }
    }

    private boolean installWinTabFallback(ClassLoader loader) {
        try {
            Class<?> service = Class.forName("com.android.server.input.InputManagerService", false, loader);
            Method unhandled = find(service, "dispatchUnhandledKey", KeyEvent.class, IBinder.class, KeyEvent.class, int.class);
            hook(unhandled).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!(chain.getArg(1) instanceof KeyEvent event)
                        || event.getAction() != KeyEvent.ACTION_DOWN
                        || !WinPolicy.isWinTab(event.getKeyCode(), event.getModifiers())
                        || !(chain.getArg(0) instanceof IBinder inputToken)) return chain.proceed();
                Target target = currentTarget(inputToken);
                if (withFeature(target, ShortcutFeatures.WIN_TAB_FALLBACK) == null) return chain.proceed();

                if (event.getRepeatCount() == 0 && winTabFallbackTraces.getAndUpdate(n -> Math.min(n + 1, 24)) < 24) {
                    info("WIN_TAB_FALLBACK_BLOCKED focus=" + target.focus.pkg + " down=" + event.getDownTime());
                }
                // The app already received this event. Stop the subsequent system shortcut
                // and fallback-key path without changing anything in normal dispatch.
                return null;
            });
            info("HOOK winTabFallback=" + unhandled.toGenericString());
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            failure("WIN_TAB_FALLBACK_DISABLED unsupported input policy", error);
            return false;
        }
    }

    private boolean installAltQueue(ClassLoader loader) {
        try {
            Class<?> service = Class.forName("com.android.server.input.InputManagerService", false, loader);
            Method queue = find(service, "interceptKeyBeforeQueueing", int.class, KeyEvent.class, int.class);
            // This wraps both the Android gesture check and the MIUI queue policy.
            // Only eligible Tab events get the same official ROM skip decision as Win.
            hook(queue).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                if (!ready || !altTabReady || !(chain.getArg(0) instanceof KeyEvent event)
                        || !AltTabPresses.observes(event.getKeyCode())) return chain.proceed();
                Target target = (((Integer) chain.getArg(1)) & POLICY_INTERACTIVE) != 0 ? currentTarget(null) : null;
                boolean allow = routeAltTab(altQueue, event, withFeature(target, ShortcutFeatures.ALT_QUEUE));
                Dispatch previous = allowedEvent.get();
                if (allow) allowedEvent.set(new Dispatch(event, target, "QUEUE"));
                else allowedEvent.remove();
                try {
                    Object result = chain.proceed();
                    traceAlt("QUEUE", event, target, allow, result);
                    return result;
                } finally {
                    if (event.getKeyCode() == AltTabPresses.TAB && isUp(event)) {
                        altQueue.finishTab(event.getDeviceId(), event.getDownTime());
                    }
                    if (previous == null) allowedEvent.remove();
                    else allowedEvent.set(previous);
                }
            });
            info("HOOK altQueue=" + queue.toGenericString());
            altTabReady = true;
            observeAltCombination(loader);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            failure("ALT_TAB_DISABLED queue hook unavailable", error);
            return false;
        }
    }

    private void observeAltCombination(ClassLoader loader) {
        try {
            Class<?> gestures = Class.forName("com.android.server.input.KeyGestureController", false, loader);
            Method combinations = find(gestures, "interceptMultiKeyCombination", long.class, KeyEvent.class);
            hook(combinations).setExceptionMode(ExceptionMode.PROTECTIVE).intercept(chain -> {
                Object result = chain.proceed();
                Dispatch current = allowedEvent.get();
                if (current != null && chain.getArg(0) == current.event
                        && current.event.getKeyCode() == AltTabPresses.TAB && !current.event.isMetaPressed()) {
                    traceAlt("MULTI_KEY", current.event, current.target, true, result);
                }
                return result;
            });
            info("OBSERVE altCombination=" + combinations.toGenericString());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            failure("ALT_MULTI_KEY observer unavailable", error);
        }
    }

    private static boolean routeAltTab(AltTabPresses presses, KeyEvent event, Target target) {
        if (event.getAction() != KeyEvent.ACTION_DOWN && !isUp(event)) return false;
        boolean firstDown = event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0;
        boolean altTabModifiers = KeyEvent.metaStateHasModifiers(event.getModifiers() & ~KeyEvent.META_SHIFT_MASK,
                KeyEvent.META_ALT_ON);
        return presses.route(event.getDeviceId(), event.getKeyCode(), event.getDownTime(), firstDown, isUp(event),
                altTabModifiers, target);
    }

    private void traceAlt(String stage, KeyEvent event, Target target, boolean scoped, Object result) {
        if ((event.getAction() != KeyEvent.ACTION_DOWN && !isUp(event)) || event.getRepeatCount() != 0
                || altTraces.getAndUpdate(n -> Math.min(n + 1, MAX_TRACES)) >= MAX_TRACES) return;
        Focus focus = focused;
        info("ALT_" + stage + " " + KeyEvent.keyCodeToString(event.getKeyCode()) + " " + (isUp(event) ? "UP" : "DOWN")
                + " focus=" + (focus == null ? "unknown" : focus.pkg) + " eligible=" + (target != null)
                + " chord=" + scoped + " meta=0x" + Integer.toHexString(event.getMetaState())
                + " canceled=" + event.isCanceled() + " down=" + event.getDownTime() + " result=" + result);
    }

    private synchronized void applyPolicies(ModuleControl.Snapshot snapshot, String source) {
        policies = snapshot.system() ? snapshot.policies() : Map.of();
        configuredFn = snapshot.system() && snapshot.fnNeeded();
        refreshTarget();
        if (fnLock != null) fnLock.setEnabled(configuredFn);
        info("SCOPE_APPLIED source=" + source + " system=" + snapshot.system() + " apps=" + snapshot.apps());
    }

    private synchronized void applyPreview(Integer mask) {
        previewMask = mask;
        refreshTarget();
        if (fnLock != null) fnLock.setEnabled(configuredFn
                || (mask != null && ShortcutFeatures.has(mask, ShortcutFeatures.FN_LOCK)));
    }

    private synchronized void updateFocus(Focus next) {
        if (java.util.Objects.equals(focused, next)) return;
        focused = next;
        refreshTarget();
    }

    private void refreshTarget() {
        Integer mask = focused == null ? null : policies.get(focused.pkg);
        if (focused != null && modulePackage != null && focused.pkg.equals(modulePackage)) mask = previewMask;
        Target next = mask == null ? null : new Target(focused, mask);
        if (java.util.Objects.equals(routeTarget, next)) return;
        routeTarget = next;
        if (fnLock != null) fnLock.focusChanged();
    }

    private static Target withFeature(Target target, int feature) {
        return target != null && target.has(feature) ? target : null;
    }

    private Target currentTarget(IBinder eventToken) {
        Target target = routeTarget;
        if (!ready || target == null || (eventToken != null && !eventToken.equals(target.focus.token))) return null;
        try {
            return Boolean.FALSE.equals(keyguardOn.invoke(target.focus.policy)) && routeTarget == target ? target : null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static Object matchPress(WinPresses tracker, KeyEvent event, Target selected) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (!down && !isUp(event)) return null;
        return tracker.target(event.getDeviceId(), event.getKeyCode(), event.getDownTime(),
                down && event.getRepeatCount() == 0, selected);
    }

    private static boolean isUp(KeyEvent event) {
        return event.getAction() == KeyEvent.ACTION_UP;
    }

    private static void finish(WinPresses tracker, KeyEvent event) {
        tracker.finish(event.getDeviceId(), event.getKeyCode(), event.getDownTime());
    }

    private void trace(String stage, KeyEvent event, Target target, boolean scoped, Object result) {
        if (event.getRepeatCount() != 0 || traces.getAndUpdate(n -> Math.min(n + 1, MAX_TRACES)) >= MAX_TRACES) return;
        Focus focus = focused;
        info("WIN_" + stage + " " + (isUp(event) ? "UP" : "DOWN")
                + " focus=" + (focus == null ? "unknown" : focus.pkg)
                + " eligible=" + (target != null) + " scoped=" + scoped
                + (result == null ? "" : " result=" + result));
    }

    private static Method find(Class<?> owner, String name, Class<?> result, Class<?>... parameters)
            throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, parameters);
        if (method.getReturnType() != result) throw new NoSuchMethodException(method.toString());
        method.setAccessible(true);
        return method;
    }

    private void info(String message) {
        Log.i(TAG, message);
        log(Log.INFO, TAG, message);
    }

    private void failure(String message, Throwable error) {
        Log.e(TAG, message, error);
        log(Log.ERROR, TAG, message, error);
    }
}
