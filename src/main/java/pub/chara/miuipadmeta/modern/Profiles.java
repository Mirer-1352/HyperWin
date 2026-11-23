/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One group per package. Group membership never erases the package's own settings. */
final class Profiles {
    record Group(String name, int mask) {}
    final int defaults;
    private final Map<String, Integer> apps;
    private final Map<String, Group> groups;
    private final Map<String, String> memberships;

    Profiles(int defaults, Map<String, Integer> apps, Map<String, Group> groups, Map<String, String> memberships) {
        this.defaults = ShortcutFeatures.sanitize(defaults);
        this.apps = new LinkedHashMap<>(apps);
        this.groups = new LinkedHashMap<>(groups);
        this.memberships = new LinkedHashMap<>(memberships);
        this.memberships.values().removeIf(id -> !this.groups.containsKey(id));
    }

    Profiles copy() { return new Profiles(defaults, apps, groups, memberships); }
    Map<String, Integer> apps() { return Map.copyOf(apps); }
    Map<String, Group> groups() { return new LinkedHashMap<>(groups); }
    Map<String, String> memberships() { return Map.copyOf(memberships); }
    int appMask(String pkg) { return apps.getOrDefault(pkg, defaults); }
    String groupFor(String pkg) { return memberships.get(pkg); }
    int effective(String pkg) {
        Group group = groups.get(groupFor(pkg));
        return group == null ? appMask(pkg) : group.mask;
    }

    void setAppMask(String pkg, int mask) { apps.put(pkg, ShortcutFeatures.sanitize(mask)); }
    void setGroupMask(String id, int mask) {
        Group group = groups.get(id);
        if (group != null) groups.put(id, new Group(group.name, ShortcutFeatures.sanitize(mask)));
    }

    String addGroup(String name) {
        String id = UUID.randomUUID().toString();
        groups.put(id, new Group(name, defaults));
        return id;
    }

    void renameGroup(String id, String name) {
        Group group = groups.get(id);
        if (group != null) groups.put(id, new Group(name, group.mask));
    }

    void deleteGroup(String id) {
        groups.remove(id);
        memberships.values().removeIf(id::equals);
    }

    // Apply the visible selection only. Out-of-scope memberships return if LSPosed re-adds the app.
    void setMembers(String id, Set<String> visible, Set<String> checked) {
        if (!groups.containsKey(id)) return;
        for (String pkg : visible) {
            if (checked.contains(pkg)) memberships.put(pkg, id);
            else memberships.remove(pkg, id);
        }
    }

    Map<String, Integer> policies(Set<String> scoped) {
        Map<String, Integer> effective = new LinkedHashMap<>(scoped.size());
        for (String pkg : scoped) effective.put(pkg, effective(pkg));
        return Map.copyOf(effective);
    }
}
