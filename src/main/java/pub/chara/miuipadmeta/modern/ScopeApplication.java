/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

import android.app.Application;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** The framework sends the module app its own service binder through XposedProvider. */
public final class ScopeApplication extends Application implements XposedServiceHelper.OnServiceListener {
    private volatile XposedService service;
    private ProfileStore profiles;

    @Override public void onCreate() {
        super.onCreate();
        profiles = new ProfileStore(this);
        XposedServiceHelper.registerListener(this);
    }

    @Override public synchronized void onServiceBind(XposedService connected) {
        service = connected;
    }

    @Override public synchronized void onServiceDied(XposedService disconnected) {
        if (service == disconnected) service = null;
    }

    XposedService service() {
        return service;
    }

    ProfileStore profiles() { return profiles; }
}
