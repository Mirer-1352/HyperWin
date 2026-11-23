/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

import android.app.AlertDialog;
import android.text.InputFilter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** The feature catalog and app/group editors are independent from the input hooks. */
final class FeaturePage {
    private record Option(int flag, String title, String detail) {}
    private static final Option[][] OPTIONS = {
        {
            new Option(ShortcutFeatures.WIN_UI, "屏蔽本机 Win 动作", "单按不打开 Dock，长按不打开快捷键列表。"),
            new Option(ShortcutFeatures.WIN_PASS, "透传 Win 键", "将 Win 的按下和松开交给应用。"),
        }, {
            new Option(ShortcutFeatures.ALT_DISPATCH, "跳过本机 Alt+Tab", "在分发阶段让应用处理组合键。"),
            new Option(ShortcutFeatures.ALT_QUEUE, "保护按键序列", "在排队阶段避免系统提前截走 Tab 或取消 Alt。"),
        }, {
            new Option(ShortcutFeatures.WIN_TAB_FALLBACK, "屏蔽本机多任务兜底", "应用没有处理 Win+Tab 时，平板不接手打开多任务。"),
            new Option(ShortcutFeatures.WIN_TAB_PASS, "透传 Win+Tab", "使用 Win 透传让应用先接收组合键，需同时启用透传 Win 键。"),
        }, {
            new Option(ShortcutFeatures.FN_LOCK, "启用小米键盘 Fn 锁", "直接使用 F1–F12。按住 Fn 使用键帽原功能。"),
        },
    };
    private static final String[] SECTIONS = {"Win 键", "Alt+Tab", "Win+Tab", "Fn 锁"};

    private final SettingsActivity activity;
    private final SettingsViews ui;
    private String editingId;
    private boolean editingGroup;

    FeaturePage(SettingsActivity activity, SettingsViews ui) {
        this.activity = activity;
        this.ui = ui;
    }

    boolean closeEditor() {
        if (editingId == null) return false;
        editingId = null;
        return true;
    }

    void render(LinearLayout page) {
        Profiles profiles = activity.profiles();
        List<String> packages = activity.apps();
        if (editingId != null && (editingGroup ? profiles.groups().containsKey(editingId)
                : packages.contains(editingId) && profiles.groupFor(editingId) == null)) {
            renderEditor(page, profiles);
            return;
        }
        editingId = null;
        ui.add(page, ui.heading("功能配置", 27), 0);
        ui.add(page, ui.label("应用来自 LSPosed 作用域。每个应用可独立设置，或加入一个组并使用组设置。", 14, ui.secondary), 6);
        if (packages.isEmpty()) {
            ui.add(page, ui.label("当前没有可配置的应用。请在 LSPosed 勾选应用，再回主页刷新。", 14, ui.secondary), 15);
        }
        ui.section(page, "分组");
        ui.add(page, ui.button("＋ 新建分组", true, () -> askName("新建分组", "", name ->
                activity.edit(p -> editingId = p.addGroup(name), () -> { editingGroup = true; activity.render(); }))), 8);
        for (var entry : profiles.groups().entrySet()) {
            String id = entry.getKey();
            long count = packages.stream().filter(pkg -> id.equals(profiles.groupFor(pkg))).count();
            boolean dormant = profiles.memberships().containsValue(id) && count == 0;
            ui.row(page, entry.getValue().name(), count + " 个作用域应用" + (dormant ? " · 成员暂不在作用域" : ""),
                    () -> edit(id, true));
        }
        ui.section(page, "未分组应用");
        List<String> ungrouped = packages.stream().filter(pkg -> profiles.groupFor(pkg) == null)
                .collect(Collectors.toList());
        if (ungrouped.isEmpty()) ui.add(page, ui.label("暂无未分组应用", 14, ui.secondary), 10);
        for (String pkg : ungrouped) ui.row(page, activity.appLabel(pkg), pkg, () -> edit(pkg, false));
    }

    private void edit(String id, boolean group) {
        editingId = id;
        editingGroup = group;
        activity.render();
    }

    private void renderEditor(LinearLayout page, Profiles profiles) {
        String id = editingId;
        boolean grouped = editingGroup;
        ui.add(page, ui.button("返回功能列表", false, () -> { closeEditor(); activity.render(); }), 0);
        Profiles.Group group = grouped ? profiles.groups().get(id) : null;
        ui.add(page, ui.heading(grouped ? group.name() : activity.appLabel(id), 26), 18);
        if (grouped) {
            List<String> members = activity.apps().stream().filter(pkg -> id.equals(profiles.groupFor(pkg)))
                    .collect(Collectors.toList());
            long hidden = profiles.memberships().entrySet().stream()
                    .filter(entry -> id.equals(entry.getValue()) && !members.contains(entry.getKey())).count();
            String names = members.isEmpty() ? "暂无作用域内成员"
                    : members.stream().map(activity::appLabel).collect(Collectors.joining("、"));
            if (hidden > 0) names += " · " + hidden + " 个成员暂不在作用域";
            ui.add(page, ui.label(names, 14, ui.secondary), 6);
            ui.add(page, ui.button("添加或移出应用", true, () -> chooseMembers(id)), 10);
            ui.add(page, ui.button("重命名分组", false, () -> askName("重命名分组", group.name(),
                    name -> activity.edit(p -> p.renameGroup(id, name), activity::render))), 6);
            ui.add(page, ui.button("删除分组", false, () -> new AlertDialog.Builder(activity)
                    .setTitle("删除分组")
                    .setMessage("成员将全部移出分组，并恢复各自的个人配置。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("删除", (dialog, which) -> activity.edit(p -> p.deleteGroup(id),
                            () -> { closeEditor(); activity.render(); })).show()), 6);
        } else ui.add(page, ui.label(id, 14, ui.secondary), 4);

        int mask = grouped ? group.mask() : profiles.appMask(id);
        for (int i = 0; i < OPTIONS.length; i++) {
            ui.section(page, SECTIONS[i]);
            LinearLayout card = ui.card(page);
            for (Option option : OPTIONS[i]) {
                Switch toggle = new Switch(activity);
                toggle.setText(option.title);
                toggle.setTextColor(ui.text);
                toggle.setTextSize(15);
                toggle.setMinHeight(ui.dp(44));
                toggle.setChecked(ShortcutFeatures.has(mask, option.flag));
                if (option.flag == ShortcutFeatures.WIN_TAB_PASS) {
                    toggle.setEnabled(ShortcutFeatures.has(mask, ShortcutFeatures.WIN_PASS));
                }
                toggle.setOnCheckedChangeListener((button, checked) -> {
                    button.setEnabled(false);
                    activity.edit(p -> {
                        int before = grouped ? p.groups().get(id).mask() : p.appMask(id);
                        int after = checked ? before | option.flag : before & ~option.flag;
                        if (grouped) p.setGroupMask(id, after);
                        else p.setAppMask(id, after);
                    }, activity::renderKeepingScroll);
                });
                ui.add(card, toggle, 2);
                ui.add(card, ui.label(option.detail, 13, ui.secondary), 0);
            }
        }
    }

    private void chooseMembers(String id) {
        List<String> packages = activity.apps();
        Profiles profiles = activity.profiles();
        if (packages.isEmpty()) {
            new AlertDialog.Builder(activity).setMessage("请先在主页刷新 LSPosed 作用域。")
                    .setPositiveButton("确定", null).show();
            return;
        }
        String[] labels = new String[packages.size()];
        boolean[] checked = new boolean[packages.size()];
        for (int i = 0; i < packages.size(); i++) {
            String pkg = packages.get(i);
            String owner = profiles.groupFor(pkg);
            checked[i] = id.equals(owner);
            labels[i] = activity.appLabel(pkg) + "\n" + pkg
                    + (owner != null && !checked[i] ? " · 当前组 " + profiles.groups().get(owner).name() : "");
        }
        new AlertDialog.Builder(activity).setTitle("选择分组成员")
                .setMultiChoiceItems(labels, checked, (dialog, which, selected) -> checked[which] = selected)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存成员", (dialog, which) -> {
                    Set<String> selected = new HashSet<>();
                    for (int i = 0; i < packages.size(); i++) if (checked[i]) selected.add(packages.get(i));
                    activity.edit(p -> p.setMembers(id, Set.copyOf(packages), selected), activity::render);
                }).show();
    }

    private void askName(String title, String current, Consumer<String> action) {
        EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setHint("分组名称");
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(40)});
        input.setText(current);
        input.setSelectAllOnFocus(true);
        LinearLayout box = ui.column();
        box.setPadding(ui.dp(24), 0, ui.dp(24), 0);
        box.addView(input);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title).setView(box)
                .setNegativeButton("取消", null).setPositiveButton("保存", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) { input.setError("请输入名称"); return; }
            dialog.dismiss();
            action.accept(name);
        }));
        dialog.show();
    }
}
