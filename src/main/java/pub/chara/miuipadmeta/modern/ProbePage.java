/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Hosts a 30-second foreground probe with an optional temporary app profile. */
final class ProbePage {
    private static final int MAX_LINES = 300;
    private final SettingsActivity activity;
    private final SettingsViews ui;
    private final KeyboardProbe probe = new KeyboardProbe();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable timeout = () -> end("已到 30 秒");
    private final ArrayList<String> lines = new ArrayList<>();
    private String app;
    private boolean baseline;
    private String session;
    private boolean capturing;
    private String status = "等待开始";
    private TextView output, statusView;
    private ScrollView logScroll;

    ProbePage(SettingsActivity activity, SettingsViews ui) {
        this.activity = activity;
        this.ui = ui;
    }

    boolean running() { return session != null; }

    void render(LinearLayout page) {
        if (app != null && !activity.apps().contains(app) && !running()) app = null;
        ui.add(page, ui.heading("按键测试", 27), 0);
        ui.add(page, ui.label("点击开始后记录 30 秒。可测试 Win、Alt+Tab、Win+Tab 和顶部功能键。记录只在本页显示。", 14, ui.secondary), 6);
        ui.row(page, baseline ? "原始输入" : app == null ? "默认配置" : activity.appLabel(app),
                baseline ? "查看系统直接送到本页的事件" : "临时在本页启用所选配置",
                () -> { if (!running()) chooseProfile(); });
        LinearLayout actions = new LinearLayout(activity);
        actions.addView(ui.button("开始 30 秒", true, this::start), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams stopSize = new LinearLayout.LayoutParams(0, -2, 1);
        stopSize.leftMargin = ui.dp(8);
        actions.addView(ui.button("结束", false, () -> end("手动停止")), stopSize);
        ui.add(page, actions, 10);
        statusView = ui.label(status, 14, ui.accent);
        ui.add(page, statusView, 12);
        logScroll = new ScrollView(activity) {
            @Override public boolean onInterceptTouchEvent(android.view.MotionEvent event) {
                getParent().requestDisallowInterceptTouchEvent(true);
                return super.onInterceptTouchEvent(event);
            }
        };
        logScroll.setFillViewport(true);
        logScroll.setBackground(ui.shape(ui.surface, 16, ui.outline));
        output = ui.label(String.join("\n", lines), 12, ui.text);
        output.setTypeface(Typeface.MONOSPACE);
        output.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        output.setTextIsSelectable(true);
        logScroll.addView(output);
        LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(-1, ui.dp(295));
        box.topMargin = ui.dp(9);
        page.addView(logScroll, box);
    }

    private void chooseProfile() {
        List<String> packages = List.copyOf(activity.apps());
        String[] names = new String[packages.size() + 2];
        names[0] = "默认配置";
        names[1] = "原始输入";
        for (int i = 0; i < packages.size(); i++) names[i + 2] = activity.appLabel(packages.get(i));
        int selected = baseline ? 1 : app == null ? 0 : packages.indexOf(app) + 2;
        new AlertDialog.Builder(activity).setTitle("选择测试配置").setSingleChoiceItems(names, selected,
                (dialog, which) -> {
                    baseline = which == 1;
                    app = which >= 2 ? packages.get(which - 2) : null;
                    dialog.dismiss();
                    activity.render();
                }).setNegativeButton("取消", null).show();
    }

    private void start() {
        if (running()) return;
        String requested = UUID.randomUUID().toString();
        session = requested;
        lines.clear();
        updateOutput();
        if (baseline) {
            beginCapture("原始输入");
            return;
        }
        setStatus("正在向系统应用测试配置…");
        ModuleControl.send(activity, ModuleControl.PREVIEW, requested, app, result -> {
            if (!requested.equals(session) || !activity.alive()) return;
            if (result == null || !result.getBoolean(ModuleControl.APPLIED)) {
                session = null;
                setStatus("测试配置尚未应用。请回主页刷新，或选择原始输入。");
                return;
            }
            beginCapture(app == null ? "默认配置" : activity.appLabel(app));
        });
    }

    private void beginCapture(String profile) {
        probe.start();
        capturing = true;
        setStatus("记录中 · " + profile);
        append(probe.line("START focus=" + activity.hasWindowFocus()));
        main.postDelayed(timeout, ModuleControl.TEST_MILLIS);
    }

    void end(String reason) {
        String id = session;
        if (id == null) return;
        if (capturing) append(probe.line("END " + reason));
        capturing = false;
        session = null;
        probe.stop();
        main.removeCallbacks(timeout);
        setStatus("已结束 · " + reason);
        if (!baseline) ModuleControl.send(activity, ModuleControl.END, id, null, ignored -> {});
    }

    void event(KeyEvent event) {
        if (!capturing) return;
        String line = probe.format(event);
        if (line != null) append(line);
    }

    void focus(boolean focused) {
        if (capturing) append(probe.line(focused ? "FOCUS GAIN" : "FOCUS LOST"));
    }

    private void append(String line) {
        if (lines.size() >= MAX_LINES) lines.remove(0);
        lines.add(line);
        updateOutput();
        if (logScroll != null) {
            ScrollView current = logScroll;
            current.post(() -> current.scrollTo(0, current.getChildAt(0).getHeight()));
        }
    }

    private void updateOutput() {
        if (output != null) output.setText(String.join("\n", lines));
    }

    private void setStatus(String text) {
        status = text;
        if (statusView != null) statusView.setText(text);
    }
}
