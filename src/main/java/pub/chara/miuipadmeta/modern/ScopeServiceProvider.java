/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedProvider;

/** Keep the framework's binder handoff protocol, with an explicit caller check. */
public final class ScopeServiceProvider extends ContentProvider {
    private final XposedProvider delegate = new XposedProvider();

    @Override public boolean onCreate() {
        delegate.attachInfo(getContext(), null);
        return true;
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        int caller = Binder.getCallingUid();
        if (ScopeSelection.READ_METHOD.equals(method) || ModuleControl.READ_POLICIES.equals(method)) {
            if (caller != Process.SYSTEM_UID) throw new SecurityException("Only system_server may read input policy");
            long identity = Binder.clearCallingIdentity();
            try {
                return readPolicies(ScopeSelection.READ_METHOD.equals(method), extras);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        if (caller != 0 && caller != Process.SYSTEM_UID) {
            throw new SecurityException("The Xposed service binder must come from the framework");
        }
        return delegate.call(method, arg, extras);
    }

    private Bundle readPolicies(boolean refreshScope, Bundle extras) {
        Bundle reply = new Bundle();
        android.content.Context app = getContext().getApplicationContext();
        ScopeApplication module = (ScopeApplication) app;
        Set<String> scoped;
        if (refreshScope) {
            XposedService service = module.service();
            if (service == null) {
                reply.putBoolean(ScopeSelection.RESPONSE_PENDING, true);
                return reply;
            }
            List<String> scope = service.getScope();
            if (scope == null) throw new IllegalStateException("LSPosed returned no scope");
            reply.putStringArrayList(ScopeSelection.RESPONSE_SCOPE, new ArrayList<>(scope));
            scoped = ScopeSelection.apps(scope);
        } else {
            scoped = ScopeSelection.apps(extras.getStringArrayList(ModuleControl.PACKAGES));
        }
        Profiles profiles = module.profiles().read();
        Bundle masks = new Bundle();
        profiles.policies(scoped).forEach(masks::putInt);
        reply.putBundle(ModuleControl.MASKS, masks);
        reply.putInt(ModuleControl.DEFAULTS, profiles.defaults);
        return reply;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
}
