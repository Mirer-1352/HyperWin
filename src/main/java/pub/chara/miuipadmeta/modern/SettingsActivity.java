/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** LSPosed settings entry only: deliberately no desktop launcher. */
public final class SettingsActivity extends Activity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        TextView title = new TextView(this);
        title.setText("HyperWin · 键盘设置");
        title.setTextSize(24);
        root.addView(title);
        Switch toggle = new Switch(this);
        toggle.setText("小米键盘 Fn 锁");
        toggle.setTextSize(18);
        toggle.setPadding(0, pad, 0, pad);
        toggle.setChecked(FnSettings.enabled(this));
        root.addView(toggle);
        TextView detail = new TextView(this);
        detail.setText("开启后，在 LSPosed 勾选的应用内直接使用 F1–F12，按住 Fn 才使用亮度、媒体等原功能。\n\n" +
                "仅支持已验证的小米妙控键盘，默认关闭。开关保存后即时通知模块；首次安装此版本及修改作用域后需软重启并解锁。\n\n" +
                "当前支持：jinghu，Keyboard 15d9:00a3 / Consumer 15d9:00a4。其他键盘保持原有行为。");
        root.addView(detail);
        TextView status = new TextView(this);
        root.addView(status);
        boolean[] reverting = {false};
        toggle.setOnCheckedChangeListener((button, checked) -> {
            if (reverting[0]) return;
            if (FnSettings.save(this, checked)) status.setText("已保存并发送通知。请切回作用域内应用测试。");
            else {
                status.setText("保存失败，请重新打开设置后重试。");
                reverting[0] = true;
                button.setChecked(!checked);
                reverting[0] = false;
            }
        });
        setContentView(root);
    }
}
