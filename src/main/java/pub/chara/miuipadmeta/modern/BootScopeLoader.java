/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Loads LSPosed scope at boot and on explicit refresh. Policy IPC stays off the input thread. */
final class BootScopeLoader {
    private static final long[] START_DELAYS_MS = {0, 500, 1000, 2000, 4000, 8000, 12000};
    private static final long[] SERVICE_DELAYS_MS = {0, 250, 750, 1500, 3000, 6000};
    private final Uri provider;
    private final Listener listener;
    private final int capabilities;
    private Handler worker;
    private ModuleControl.Snapshot snapshot;
    private String preview;
    private Runnable expirePreview;

    interface Listener {
        void applied(ModuleControl.Snapshot snapshot, Context context);
        void preview(Integer mask);
        void failed(Throwable error);
    }

    BootScopeLoader(String modulePackage, int capabilities, Listener listener) {
        provider = Uri.parse("content://" + modulePackage + ".XposedService");
        this.capabilities = capabilities;
        this.listener = listener;
    }

    void start() {
        HandlerThread thread = new HandlerThread("HyperWinPolicy");
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(this::initialize);
    }

    private void initialize() {
        Throwable last = null;
        for (long delay : START_DELAYS_MS) {
            if (delay != 0) SystemClock.sleep(delay);
            try {
                Context context = systemContext();
                UserManager users = context.getSystemService(UserManager.class);
                if (users == null) throw new IllegalStateException("UserManager is not ready");
                if (users.isUserUnlocked()) afterUnlock(context);
                else awaitUnlock(context, users);
                return;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                last = error;
            }
        }
        listener.failed(last);
    }

    private void awaitUnlock(Context context, UserManager users) {
        AtomicBoolean requested = new AtomicBoolean();
        BroadcastReceiver unlock = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (!Intent.ACTION_USER_UNLOCKED.equals(intent.getAction()) || !users.isUserUnlocked()
                        || !requested.compareAndSet(false, true)) return;
                context.unregisterReceiver(this);
                worker.post(() -> afterUnlock(context));
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(unlock, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(unlock, filter);
        if (users.isUserUnlocked()) unlock.onReceive(context, new Intent(Intent.ACTION_USER_UNLOCKED));
    }

    private void afterUnlock(Context context) {
        registerCommands(context);
        readOnBoot(context, 0);
    }

    private void readOnBoot(Context context, int attempt) {
        if (snapshot != null) return;
        try {
            read(context, true);
        } catch (RuntimeException | LinkageError error) {
            int next = attempt + 1;
            if (next < SERVICE_DELAYS_MS.length) {
                worker.postDelayed(() -> readOnBoot(context, next), SERVICE_DELAYS_MS[next]);
            } else listener.failed(error);
        }
    }

    private void registerCommands(Context context) {
        BroadcastReceiver commands = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (!isOrderedBroadcast() || !ModuleControl.ACTION.equals(intent.getAction())) return;
                String command = intent.getStringExtra(ModuleControl.COMMAND);
                if (command == null) return;
                Bundle response = new Bundle();
                boolean applied = false;
                try {
                    switch (command) {
                        case ModuleControl.QUERY -> applied = snapshot != null;
                        case ModuleControl.REFRESH -> { read(context, true); applied = true; }
                        case ModuleControl.APPLY -> { read(context, snapshot == null); applied = true; }
                        case ModuleControl.PREVIEW -> applied = startPreview(intent);
                        case ModuleControl.END -> {
                            if (preview != null && preview.equals(intent.getStringExtra(ModuleControl.PREVIEW_ID))) {
                                endPreview();
                            }
                            applied = true;
                        }
                        default -> { return; }
                    }
                } catch (RuntimeException | LinkageError error) {
                    Log.w("HyperWin", "POLICY_COMMAND_FAILED " + command, error);
                }
                response.putInt(ModuleControl.CAPABILITIES, capabilities);
                response.putBoolean(ModuleControl.APPLIED, applied);
                if (snapshot != null) {
                    response.putBoolean(ModuleControl.SYSTEM, snapshot.system());
                    response.putStringArrayList(ModuleControl.PACKAGES, new ArrayList<>(snapshot.apps()));
                }
                setResult(Activity.RESULT_OK, null, response);
            }
        };
        IntentFilter filter = new IntentFilter(ModuleControl.ACTION);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(commands, filter, ModuleControl.PERMISSION, worker, Context.RECEIVER_EXPORTED);
        } else context.registerReceiver(commands, filter, ModuleControl.PERMISSION, worker);
    }

    private boolean startPreview(Intent intent) {
        String id = intent.getStringExtra(ModuleControl.PREVIEW_ID);
        String pkg = intent.getStringExtra(ModuleControl.PROFILE);
        if (snapshot == null || !snapshot.system() || id == null || id.length() > 64
                || (pkg != null && !snapshot.apps().contains(pkg))) return false;
        endPreview();
        preview = id;
        listener.preview(pkg == null ? snapshot.defaults() : snapshot.mask(pkg));
        expirePreview = this::endPreview;
        worker.postDelayed(expirePreview, ModuleControl.TEST_MILLIS);
        return true;
    }

    private void endPreview() {
        if (preview == null) return;
        preview = null;
        if (expirePreview != null) worker.removeCallbacks(expirePreview);
        expirePreview = null;
        listener.preview(null);
    }

    private void read(Context context, boolean refreshScope) {
        Bundle args = null;
        if (!refreshScope) {
            args = new Bundle();
            args.putStringArrayList(ModuleControl.PACKAGES, new ArrayList<>(snapshot.apps()));
        }
        Bundle reply = context.getContentResolver().call(provider,
                refreshScope ? ScopeSelection.READ_METHOD : ModuleControl.READ_POLICIES, null, args);
        if (reply == null || reply.getBoolean(ScopeSelection.RESPONSE_PENDING)) {
            throw new IllegalStateException("LSPosed scope is not available");
        }
        Set<String> apps;
        boolean systemScoped;
        if (refreshScope) {
            ArrayList<String> raw = reply.getStringArrayList(ScopeSelection.RESPONSE_SCOPE);
            if (raw == null) throw new IllegalStateException("Missing LSPosed scope response");
            apps = ScopeSelection.apps(raw);
            systemScoped = raw.contains("system");
        } else {
            apps = snapshot.apps();
            systemScoped = snapshot.system();
        }
        Bundle masks = reply.getBundle(ModuleControl.MASKS);
        if (masks == null) throw new IllegalStateException("Missing app policies");
        Map<String, Integer> policies = new HashMap<>();
        for (String pkg : apps) {
            if (!masks.containsKey(pkg)) throw new IllegalStateException("Missing policy for scoped package");
            policies.put(pkg, ShortcutFeatures.sanitize(masks.getInt(pkg)));
        }
        ModuleControl.Snapshot next = new ModuleControl.Snapshot(apps, Map.copyOf(policies),
                ShortcutFeatures.sanitize(reply.getInt(ModuleControl.DEFAULTS)), systemScoped);
        endPreview();
        listener.applied(next, context);
        snapshot = next;
    }

    private static Context systemContext() throws ReflectiveOperationException {
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("currentActivityThread").invoke(null);
        if (thread == null) throw new IllegalStateException("ActivityThread is not ready");
        return (Context) type.getMethod("getSystemContext").invoke(thread);
    }
}
