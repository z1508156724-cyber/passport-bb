package cn.folotoy.passportcompanion;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.util.LruCache;
import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class NotificationAppsActivity extends Activity {
    private static final int INK = 0xff1f2f21, MUTED = 0xff52604d, CANVAS = 0xffeff1e7;
    private static final int PAPER = 0xfffbfcf5, BORDER = 0xffd8decc, LCD = 0xffbecf9a;
    private final ArrayList<App> apps = new ArrayList<>(), shown = new ArrayList<>();
    private TextView count, empty;
    private EditText search;
    private Rows rows;
    private final LruCache<String, Drawable.ConstantState> icons = new LruCache<>(48);

    private static final class App {
        final String name, id;
        App(String name, String id) { this.name = name; this.id = id; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);

        LinearLayout root = column();
        root.setBackgroundColor(CANVAS);
        root.setFocusableInTouchMode(true);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left + dp(20), bars.top + dp(16),
                                bars.right + dp(20), Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom) + dp(12));
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft() + dp(20), insets.getSystemWindowInsetTop() + dp(16),
                    insets.getSystemWindowInsetRight() + dp(20), insets.getSystemWindowInsetBottom() + dp(12));
            }
            return insets;
        });

        LinearLayout header = row();
        Button back = new Button(this);
        back.setText("‹ 返回");
        back.setTextColor(INK);
        back.setTextSize(15);
        back.setAllCaps(false);
        back.setMinimumHeight(dp(48));
        back.setBackground(shape(0xffe8eddd, 12));
        back.setOnClickListener(v -> finish());
        header.addView(back);
        TextView title = text("通知应用管理", 23, INK, true);
        LinearLayout.LayoutParams titleSize = new LinearLayout.LayoutParams(0, -2, 1f);
        titleSize.leftMargin = dp(14);
        header.addView(title, titleSize);
        root.addView(header);

        LinearLayout summary = column();
        summary.setBackground(shape(LCD, 15));
        summary.setPadding(dp(14), dp(12), dp(14), dp(12));
        count = text("正在读取应用…", 16, INK, true);
        summary.addView(count);
        add(summary, text("关闭的应用将停止向 BB 机转发", 13, INK, false), 4);
        add(root, summary, 16);

        search = new EditText(this);
        search.setTextColor(INK);
        search.setHintTextColor(MUTED);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setHint("搜索应用名称或包名");
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(dp(13), 0, dp(13), 0);
        search.setMinimumHeight(dp(48));
        search.setBackground(shape(PAPER, 12));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) { filter(); }
            @Override public void afterTextChanged(Editable value) {}
        });
        add(root, search, 12);

        android.widget.FrameLayout result = new android.widget.FrameLayout(this);
        ListView list = new ListView(this);
        list.setDivider(new android.graphics.drawable.ColorDrawable(BORDER));
        list.setDividerHeight(dp(1));
        list.setBackground(shape(PAPER, 14));
        list.setClipToOutline(true);
        list.setItemsCanFocus(true);
        rows = new Rows();
        list.setAdapter(rows);
        result.addView(list, new android.widget.FrameLayout.LayoutParams(-1, -1));
        empty = text("正在读取应用…", 15, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        result.addView(empty, new android.widget.FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        LinearLayout.LayoutParams resultSize = new LinearLayout.LayoutParams(-1, 0, 1);
        resultSize.topMargin = dp(12);
        root.addView(result, resultSize);
        setContentView(root);
        root.requestFocus();
        root.requestApplyInsets();
        loadApps();
    }

    private void loadApps() {
        new Thread(() -> {
            PackageManager packages = getPackageManager();
            Map<String, String> labels = new LinkedHashMap<>();
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo match : packages.queryIntentActivities(launcher, 0)) {
                if (match.activityInfo == null) continue;
                String id = match.activityInfo.packageName;
                labels.put(id, match.loadLabel(packages).toString());
            }
            labels.putAll(RelayService.observedApps());
            for (String blocked : RelayService.blockedApps(this)) labels.putIfAbsent(blocked, blocked);
            labels.remove(getPackageName());
            ArrayList<App> result = new ArrayList<>();
            labels.forEach((id, name) -> result.add(new App(name, id)));
            Collator order = Collator.getInstance();
            result.sort((a, b) -> order.compare(a.name, b.name));
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                apps.clear();
                apps.addAll(result);
                filter();
            });
        }, "passport-app-list").start();
    }

    private void filter() {
        if (rows == null) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (App app : apps)
            if (app.name.toLowerCase(Locale.ROOT).contains(query) || app.id.toLowerCase(Locale.ROOT).contains(query))
                shown.add(app);
        empty.setText(query.isEmpty() ? "没有可管理的应用" : "没有匹配的应用");
        updateCount();
        rows.notifyDataSetChanged();
    }

    private void updateCount() {
        int enabled = 0;
        java.util.Set<String> blocked = RelayService.blockedApps(this);
        for (App app : apps) if (!blocked.contains(app.id)) enabled++;
        count.setText("允许转发 " + enabled + " / " + apps.size());
    }

    private Drawable iconFor(String id) {
        Drawable.ConstantState cached = icons.get(id);
        if (cached != null) return cached.newDrawable(getResources()).mutate();
        Drawable icon;
        try {
            icon = getPackageManager().getApplicationIcon(id);
        } catch (PackageManager.NameNotFoundException unavailable) {
            icon = getPackageManager().getDefaultActivityIcon();
        }
        Drawable.ConstantState state = icon.getConstantState();
        if (state != null) icons.put(id, state);
        return icon;
    }

    private final class Rows extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public App getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View reusable, ViewGroup parent) {
            Holder holder;
            if (reusable == null) {
                LinearLayout line = row();
                line.setPadding(dp(13), dp(7), dp(13), dp(7));
                line.setMinimumHeight(dp(57));
                holder = new Holder(line);
                line.setTag(holder);
                reusable = line;
            } else holder = (Holder)reusable.getTag();
            App app = getItem(position);
            holder.icon.setImageDrawable(iconFor(app.id));
            holder.title.setText(app.name);
            holder.subtitle.setText(app.id);
            holder.control.setOnCheckedChangeListener(null);
            holder.control.setContentDescription(app.name + " 转发");
            holder.control.setChecked(!RelayService.blockedApps(NotificationAppsActivity.this).contains(app.id));
            holder.control.setOnCheckedChangeListener((button, allowed) -> {
                RelayService.setForwardAllowed(NotificationAppsActivity.this, app.id, allowed);
                updateCount();
            });
            return reusable;
        }
    }

    private final class Holder {
        final ImageView icon;
        final TextView title, subtitle;
        final Switch control;
        Holder(LinearLayout line) {
            icon = new ImageView(NotificationAppsActivity.this);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(dp(36), dp(36));
            iconSize.rightMargin = dp(12);
            line.addView(icon, iconSize);
            LinearLayout labels = column();
            title = text("", 15, INK, true);
            subtitle = text("", 12, MUTED, false);
            labels.addView(title);
            labels.addView(subtitle);
            LinearLayout.LayoutParams width = new LinearLayout.LayoutParams(0, -2, 1f);
            width.rightMargin = dp(8);
            line.addView(labels, width);
            control = new Switch(NotificationAppsActivity.this);
            control.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{INK, 0xff898f80}));
            control.setTrackTintList(ColorStateList.valueOf(0xffb8cba3));
            control.setMinimumHeight(dp(48));
            line.addView(control);
            line.setOnClickListener(v -> control.setChecked(!control.isChecked()));
        }
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }
    private TextView text(String value, int size, int color, boolean emphasis) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (emphasis) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }
    private void add(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        parent.addView(child, params);
    }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), BORDER);
        return drawable;
    }
}
