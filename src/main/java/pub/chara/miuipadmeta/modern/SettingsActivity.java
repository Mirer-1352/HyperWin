/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Single LSPosed-only entry point. Screen controllers do not interact with input hooks. */
public final class SettingsActivity extends Activity {
    private static final String[] TABS = {"主页", "测试", "功能"};
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, String> appLabels = new HashMap<>();
    private Profiles profiles;
    private List<String> scopedApps = List.of();
    private SettingsViews ui;
    private FeaturePage features;
    private ProbePage probe;
    private ScrollView scroll;
    private LinearLayout root;
    private int tab;
    private boolean busy;
    private boolean active;
    private int capabilities;
    private String refreshStatus = "正在读取模块状态…";

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (!Process.myUserHandle().equals(UserHandle.getUserHandleForUid(Process.FIRST_APPLICATION_UID))) {
            finish();
            return;
        }
        ui = new SettingsViews(this);
        features = new FeaturePage(this, ui);
        probe = new ProbePage(this, ui);
        tab = saved == null ? 0 : Math.max(0, Math.min(2, saved.getInt("tab")));
        try { profiles = application().profiles().read(); }
        catch (RuntimeException error) {
            refreshStatus = "配置读取失败";
        }
        root = ui.column();
        getWindow().setDecorFitsSystemWindows(false);
        root.setBackgroundColor(ui.background);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var padding = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(padding.left, padding.top, padding.right, padding.bottom);
            return insets;
        });
        setContentView(root);
        WindowInsetsController bars = getWindow().getInsetsController();
        if (bars != null) {
            boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            bars.setSystemBarsAppearance(dark ? 0 : mask, mask);
        }
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (root != null) refresh(false);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        out.putInt("tab", tab);
        super.onSaveInstanceState(out);
    }

    @Override protected void onStop() {
        if (probe != null) probe.end("离开应用");
        super.onStop();
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (tab == 2 && features.closeEditor()) render();
        else if (tab != 0) selectTab(0);
        else super.onBackPressed();
    }

    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (tab == 1 && hasWindowFocus()) probe.event(event);
        return super.dispatchKeyEvent(event);
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (tab == 1 && probe != null) probe.focus(focus);
    }

    void render() { renderAt(0); }
    void renderKeepingScroll() { renderAt(scroll == null ? 0 : scroll.getScrollY()); }

    private void renderAt(int previousScroll) {
        if (root == null || isDestroyed() || isFinishing()) return;
        root.removeAllViews();
        int width = ui.dp(Math.min(750, getResources().getConfiguration().screenWidthDp));
        LinearLayout header = ui.column();
        header.setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12));
        header.addView(ui.heading("HyperWin", 23));
        LinearLayout nav = new LinearLayout(this);
        for (int i = 0; i < TABS.length; i++) {
            int selected = i;
            Button button = ui.button(TABS[i], tab == i, () -> selectTab(selected));
            button.setSelected(tab == i);
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, ui.dp(48), 1);
            if (i > 0) size.leftMargin = ui.dp(7);
            nav.addView(button, size);
        }
        ui.add(header, nav, 10);
        LinearLayout.LayoutParams headerSize = new LinearLayout.LayoutParams(width, -2);
        headerSize.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(header, headerSize);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout page = ui.column();
        page.setPadding(ui.dp(18), ui.dp(9), ui.dp(18), ui.dp(28));
        scroll.addView(page, new android.widget.FrameLayout.LayoutParams(width, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        if (tab == 0) renderHome(page);
        else if (tab == 1) probe.render(page);
        else if (profiles != null) features.render(page);
        else ui.add(page, ui.label("无法读取已保存的配置", 16, ui.secondary), 0);
        ScrollView current = scroll;
        if (previousScroll != 0) current.post(() -> current.scrollTo(0, previousScroll));
    }

    private void selectTab(int next) {
        if (tab == next) return;
        if (tab == 1) probe.end("切换页面");
        tab = next;
        render();
    }

    private void renderHome(LinearLayout page) {
        ui.add(page, ui.heading("模块状态", 27), 0);
        LinearLayout status = ui.card(page);
        status.addView(ui.heading(active ? "系统模块已激活" : "系统模块未确认激活", 18));
        if (active && capabilities != ShortcutFeatures.ALL) {
            ui.add(status, ui.label("当前 ROM 只加载了部分功能。", 14, ui.secondary), 5);
        }
        ui.add(status, ui.label(refreshStatus, 14, ui.secondary), 5);
        ui.add(status, ui.button("刷新作用域列表", true, () -> refresh(true)), 10);
        ui.section(page, "作者");
        LinearLayout author = ui.card(page);
        author.addView(ui.heading("Mirer-1352", 18));
        ui.add(author, ui.label("HyperWin · 基于 Chara White 的 MiuiPadMeta", 14, ui.secondary), 4);
    }

    private void refresh(boolean manual) {
        if (!alive()) return;
        if (manual) {
            refreshStatus = "正在刷新 LSPosed 作用域…";
            if (tab == 0) render();
        }
        ModuleControl.send(this, manual ? ModuleControl.REFRESH : ModuleControl.QUERY, null, null, result -> {
            if (!alive()) return;
            if (result != null && result.getBoolean(ModuleControl.APPLIED)) {
                acceptState(result);
                refreshStatus = scopedApps.size() + " 个作用域应用";
                if (!active) refreshStatus += " · 请在 LSPosed 勾选系统框架";
                else if (manual) refreshStatus += " · 已同步";
                if (tab != 1 || !probe.running()) renderKeepingScroll();
                return;
            }
            active = false;
            // The service may report scope before system_server has loaded this module.
            worker.execute(() -> {
                List<String> raw = null;
                try {
                    var service = application().service();
                    if (service != null) raw = service.getScope();
                } catch (RuntimeException ignored) {}
                List<String> found = raw;
                runOnUiThread(() -> {
                    if (!alive()) return;
                    if (found != null) {
                        setApps(new ArrayList<>(ScopeSelection.apps(found)));
                        refreshStatus = scopedApps.size() + " 个作用域应用 · 系统尚未应用，请检查模块并软重启";
                    } else {
                        refreshStatus = "尚未取得作用域。请确认 LSPosed 已启用 HyperWin。";
                    }
                    if (tab != 1 || !probe.running()) renderKeepingScroll();
                });
            });
        });
    }

    private void acceptState(Bundle result) {
        setApps(result.getStringArrayList(ModuleControl.PACKAGES));
        active = result.getBoolean(ModuleControl.SYSTEM);
        capabilities = result.getInt(ModuleControl.CAPABILITIES);
    }

    private void setApps(List<String> raw) {
        if (raw == null) return;
        List<String> copy = new ArrayList<>(ScopeSelection.apps(raw));
        copy.sort(Comparator.comparing(this::appLabel, String.CASE_INSENSITIVE_ORDER).thenComparing(s -> s));
        scopedApps = List.copyOf(copy);
    }

    Profiles profiles() { return profiles; }
    List<String> apps() { return scopedApps; }
    boolean alive() { return !isDestroyed() && !isFinishing(); }

    String appLabel(String pkg) {
        return appLabels.computeIfAbsent(pkg, key -> {
            try {
                PackageManager pm = getPackageManager();
                return pm.getApplicationLabel(pm.getApplicationInfo(key, 0)).toString();
            } catch (PackageManager.NameNotFoundException error) { return key; }
        });
    }

    void edit(Consumer<Profiles> change, Runnable completion) {
        if (busy || profiles == null) {
            Toast.makeText(this, "正在保存，请稍候。", Toast.LENGTH_SHORT).show();
            return;
        }
        Profiles edited = profiles.copy();
        change.accept(edited);
        busy = true;
        worker.execute(() -> {
            boolean saved = application().profiles().save(edited);
            runOnUiThread(() -> {
                busy = false;
                if (saved) {
                    // Send even if the user left this activity while the write was running.
                    ModuleControl.send(getApplicationContext(), ModuleControl.APPLY, null, null, result -> {
                        if (!alive()) return;
                        if (result != null && result.getBoolean(ModuleControl.APPLIED)) acceptState(result);
                        else Toast.makeText(this, "已保存。模块连通后可在主页刷新应用。", Toast.LENGTH_SHORT).show();
                    });
                }
                if (!alive()) return;
                if (saved) {
                    profiles = edited;
                    completion.run();
                } else {
                    Toast.makeText(this, "保存失败，原配置已保留。", Toast.LENGTH_SHORT).show();
                    renderKeepingScroll();
                }
            });
        });
    }

    private ScopeApplication application() { return (ScopeApplication) getApplication(); }
}
