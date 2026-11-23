/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import java.util.Set;
import java.util.function.Consumer;

/** Signature-protected app-to-system commands. Only system_server reads the private provider. */
final class ModuleControl {
    static final String ACTION = "pub.chara.miuipadmeta.modern.MODULE_CONTROL";
    static final String PERMISSION = "pub.chara.miuipadmeta.modern.permission.CONFIGURE";
    static final String QUERY = "query", REFRESH = "refresh", APPLY = "apply", PREVIEW = "preview", END = "end";
    static final String COMMAND = "command", PREVIEW_ID = "previewId", PROFILE = "profile";
    static final String PACKAGES = "packages", MASKS = "masks", DEFAULTS = "defaults", SYSTEM = "system";
    static final String CAPABILITIES = "capabilities", APPLIED = "applied";
    static final String READ_POLICIES = "ReadPolicies";
    static final int TEST_MILLIS = 30_000;

    record Snapshot(Set<String> apps, java.util.Map<String, Integer> policies, int defaults, boolean system) {
        int mask(String pkg) { return policies.getOrDefault(pkg, 0); }
        boolean fnNeeded() {
            return policies.values().stream().anyMatch(mask -> ShortcutFeatures.has(mask, ShortcutFeatures.FN_LOCK));
        }
    }

    static void send(Context context, String command, String session, String profile, Consumer<Bundle> result) {
        Intent intent = new Intent(ACTION).setPackage("android").putExtra(COMMAND, command)
                .putExtra(PREVIEW_ID, session).putExtra(PROFILE, profile);
        context.sendOrderedBroadcast(intent, null, new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent response) {
                result.accept(getResultCode() == Activity.RESULT_OK ? getResultExtras(false) : null);
            }
        }, null, Activity.RESULT_CANCELED, null, null);
    }

    private ModuleControl() {}
}
