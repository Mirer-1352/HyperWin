/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/** Read and write the configuration in the module app process only. */
final class ProfileStore {
    private static final String TAG = "HyperWin";
    private final AtomicFile file;
    private final int legacyDefaults;

    ProfileStore(Context context) {
        file = new AtomicFile(new File(context.getFilesDir(), "profiles.json"));
        boolean legacyFn = context.getSharedPreferences("keyboard", Context.MODE_PRIVATE).getBoolean("fn_lock", false);
        legacyDefaults = ShortcutFeatures.BETA2 | (legacyFn ? ShortcutFeatures.FN_LOCK : 0);
    }

    synchronized Profiles read() {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile().getPath() + ".bak").exists()) {
            return new Profiles(legacyDefaults, Map.of(), Map.of(), Map.of());
        }
        try {
            JSONObject root = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            Map<String, Integer> apps = new LinkedHashMap<>();
            JSONObject appObject = root.getJSONObject("apps");
            for (var keys = appObject.keys(); keys.hasNext();) {
                String pkg = keys.next();
                apps.put(pkg, ShortcutFeatures.sanitize(appObject.getInt(pkg)));
            }
            Map<String, Profiles.Group> groups = new LinkedHashMap<>();
            JSONObject groupObject = root.getJSONObject("groups");
            for (var keys = groupObject.keys(); keys.hasNext();) {
                String id = keys.next();
                JSONObject group = groupObject.getJSONObject(id);
                groups.put(id, new Profiles.Group(group.getString("name"),
                        ShortcutFeatures.sanitize(group.getInt("mask"))));
            }
            Map<String, String> members = new LinkedHashMap<>();
            JSONObject memberObject = root.getJSONObject("memberships");
            for (var keys = memberObject.keys(); keys.hasNext();) {
                String pkg = keys.next();
                members.put(pkg, memberObject.getString(pkg));
            }
            return new Profiles(root.getInt("defaults"), apps, groups, members);
        } catch (IOException | JSONException error) {
            throw new IllegalStateException("Cannot read HyperWin profiles", error);
        }
    }

    synchronized boolean save(Profiles profiles) {
        FileOutputStream stream = null;
        try {
            JSONObject groups = new JSONObject();
            for (var entry : profiles.groups().entrySet()) {
                Profiles.Group group = entry.getValue();
                groups.put(entry.getKey(), new JSONObject().put("name", group.name()).put("mask", group.mask()));
            }
            JSONObject root = new JSONObject().put("defaults", profiles.defaults)
                    .put("apps", new JSONObject(profiles.apps())).put("groups", groups)
                    .put("memberships", new JSONObject(profiles.memberships()));
            stream = file.startWrite();
            stream.write(root.toString().getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
            file.finishWrite(stream);
            return true;
        } catch (IOException | JSONException error) {
            if (stream != null) file.failWrite(stream);
            Log.e(TAG, "PROFILE_SAVE_FAILED", error);
            return false;
        }
    }
}
