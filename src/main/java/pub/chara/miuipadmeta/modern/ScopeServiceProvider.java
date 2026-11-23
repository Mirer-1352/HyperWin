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
        if (ScopeSelection.READ_METHOD.equals(method)) {
            if (caller != Process.SYSTEM_UID) throw new SecurityException("Only system_server may read boot scope");
            long identity = Binder.clearCallingIdentity();
            try {
                return readBootScope();
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
        if (caller != 0 && caller != Process.SYSTEM_UID) {
            throw new SecurityException("The Xposed service binder must come from the framework");
        }
        return delegate.call(method, arg, extras);
    }

    private Bundle readBootScope() {
        Bundle reply = new Bundle();
        reply.putBoolean(FnSettings.KEY, FnSettings.enabled(getContext()));
        android.content.Context app = getContext().getApplicationContext();
        if (app instanceof ScopeApplication module) {
            XposedService service = module.service();
            if (service != null) {
                List<String> scope = service.getScope();
                if (scope == null) throw new IllegalStateException("LSPosed returned no scope");
                reply.putStringArrayList(ScopeSelection.RESPONSE_SCOPE, new ArrayList<>(scope));
                return reply;
            }
        }
        reply.putBoolean(ScopeSelection.RESPONSE_PENDING, true);
        return reply;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
}
