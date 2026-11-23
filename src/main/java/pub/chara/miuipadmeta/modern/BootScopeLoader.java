/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Log;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Takes one scope snapshot per system_server startup. All binder calls are off the input thread. */
final class BootScopeLoader {
    private static final long[] START_DELAYS_MS = {0, 500, 1000, 2000, 4000, 8000, 12000};
    private static final long[] SERVICE_DELAYS_MS = {0, 250, 750, 1500, 3000, 6000};
    private final Uri provider;
    private final Listener listener;

    interface Listener {
        void applied(Set<String> apps, boolean systemScoped);
        void failed(Throwable error);
    }

    BootScopeLoader(String modulePackage, Listener listener) {
        provider = Uri.parse("content://" + modulePackage + ".XposedService");
        this.listener = listener;
    }

    void start() {
        runWorker(this::initialize);
    }

    private void initialize() {
        Throwable last = null;
        for (long delay : START_DELAYS_MS) {
            if (delay != 0) SystemClock.sleep(delay);
            try {
                Context context = systemContext();
                UserManager users = context.getSystemService(UserManager.class);
                if (users == null) throw new IllegalStateException("UserManager is not ready");
                if (users.isUserUnlocked()) readScope(context);
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
                if (!Intent.ACTION_USER_UNLOCKED.equals(intent.getAction()) || !requested.compareAndSet(false, true)) return;
                try {
                    context.unregisterReceiver(this);
                } catch (IllegalArgumentException error) {
                    Log.w("HyperWin", "Unlock receiver already removed", error);
                }
                runWorker(() -> readScope(context));
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_UNLOCKED);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(unlock, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(unlock, filter);
        // Cover a user unlock between the first check and receiver registration.
        try {
            if (users.isUserUnlocked()) unlock.onReceive(context, new Intent(Intent.ACTION_USER_UNLOCKED));
        } catch (RuntimeException error) {
            if (!requested.get()) Log.w("HyperWin", "Waiting for owner unlock broadcast", error);
        }
    }

    private void readScope(Context context) {
        Throwable last = null;
        for (long delay : SERVICE_DELAYS_MS) {
            if (delay != 0) SystemClock.sleep(delay);
            try {
                Bundle reply = context.getContentResolver().call(provider, ScopeSelection.READ_METHOD, null, null);
                if (reply == null || reply.getBoolean(ScopeSelection.RESPONSE_PENDING)) {
                    throw new IllegalStateException("LSPosed module service binder has not arrived");
                }
                ArrayList<String> raw = reply.getStringArrayList(ScopeSelection.RESPONSE_SCOPE);
                if (raw == null) throw new IllegalStateException("Missing LSPosed scope response");
                listener.applied(ScopeSelection.fromFramework(raw), raw.contains("system"));
                return;
            } catch (RuntimeException | LinkageError error) {
                last = error;
            }
        }
        listener.failed(last);
    }

    private static Context systemContext() throws ReflectiveOperationException {
        Class<?> type = Class.forName("android.app.ActivityThread");
        Object thread = type.getMethod("currentActivityThread").invoke(null);
        if (thread == null) throw new IllegalStateException("ActivityThread is not ready");
        return (Context) type.getMethod("getSystemContext").invoke(thread);
    }

    private static void runWorker(Runnable work) {
        Thread thread = new Thread(work, "HyperWinBootScope");
        thread.setDaemon(true);
        thread.start();
    }
}
