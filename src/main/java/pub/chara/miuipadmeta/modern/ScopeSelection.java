/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

import java.util.Collections;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

final class ScopeSelection {
    static final String READ_METHOD = "ReadBootScope";
    static final String RESPONSE_SCOPE = "scope";
    static final String RESPONSE_PENDING = "pending";
    private static final Pattern PACKAGE_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");

    private ScopeSelection() {}

    static Set<String> fromFramework(Collection<String> scope) {
        return scope.contains("system") ? apps(scope) : Set.of();
    }

    static Set<String> apps(Iterable<String> raw) {
        TreeSet<String> apps = new TreeSet<>();
        if (raw != null) {
            for (String name : raw) {
                if (name != null && PACKAGE_NAME.matcher(name).matches()) apps.add(name);
            }
        }
        return Collections.unmodifiableSet(apps);
    }
}
