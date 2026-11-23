/* SPDX-License-Identifier: GPL-3.0-only */
package pub.chara.miuipadmeta.modern;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Layout and colors shared by the three pages. Widgets use the native Android theme. */
final class SettingsViews {
    final Context context;
    final int background, surface, text, secondary, accent, accentSurface, outline;

    SettingsViews(Context context) {
        this.context = context;
        boolean dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        background = dark ? 0xff10151c : 0xfff2f5fa;
        surface = dark ? 0xff1c2531 : 0xffffffff;
        text = dark ? 0xffe8eef7 : 0xff17243a;
        secondary = dark ? 0xffbac8da : 0xff485971;
        accent = dark ? 0xffa6caff : 0xff235db7;
        accentSurface = dark ? 0xff283f5f : 0xffe0ecff;
        outline = dark ? 0xff364358 : 0xffdce4ef;
    }

    int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    TextView label(String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    TextView heading(String value, int size) {
        TextView view = label(value, size, text);
        view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        view.setAccessibilityHeading(true);
        return view;
    }

    void section(LinearLayout parent, String title) { add(parent, heading(title, 17), 20); }

    LinearLayout card(LinearLayout parent) {
        LinearLayout card = column();
        card.setBackground(shape(surface, 18, outline));
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        add(parent, card, 10);
        return card;
    }

    void add(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(top);
        parent.addView(view, lp);
    }

    Button button(String title, boolean highlighted, Runnable action) {
        Button button = new Button(context);
        button.setText(title);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setMinHeight(dp(48));
        button.setTextColor(highlighted ? accent : text);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x35618ac6),
                shape(highlighted ? accentSurface : surface, 14, outline), null));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    void row(LinearLayout parent, String title, String detail, Runnable action) {
        LinearLayout row = column();
        row.setPadding(dp(13), dp(11), dp(13), dp(11));
        row.setMinimumHeight(dp(56));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x35618ac6), shape(surface, 13, outline), null));
        row.addView(heading(title, 16));
        if (detail != null) add(row, label(detail, 13, secondary), 2);
        row.setOnClickListener(view -> action.run());
        row.setFocusable(true);
        add(parent, row, 8);
    }

    GradientDrawable shape(int color, int radius, int border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        if (border != 0) shape.setStroke(dp(1), border);
        return shape;
    }
}
