/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

import android.content.Context;
import android.content.Intent;

final class FnSettings {
    static final String KEY = "fn_lock";
    static final String ACTION = "pub.chara.miuipadmeta.modern.FN_LOCK_CHANGED";
    static final String PERMISSION = "pub.chara.miuipadmeta.modern.permission.CONFIGURE";
    static boolean enabled(Context context) {
        return context.getSharedPreferences("keyboard", Context.MODE_PRIVATE).getBoolean(KEY, false);
    }
    static boolean save(Context context, boolean value) {
        if (!context.getSharedPreferences("keyboard", Context.MODE_PRIVATE).edit().putBoolean(KEY, value).commit()) {
            return false;
        }
        context.sendBroadcast(new Intent(ACTION).setPackage("android").putExtra(KEY, value));
        return true;
    }
    private FnSettings() {}
}
