package cn.folotoy.passportcompanion;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.function.IntConsumer;

public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(31, 47, 33);
    private static final int MUTED = Color.rgb(82, 96, 77);
    private static final int CANVAS = Color.rgb(239, 241, 231);
    private static final int PAPER = Color.rgb(251, 252, 245);
    private static final int BORDER = Color.rgb(216, 222, 204);
    private static final int LCD = Color.rgb(190, 207, 154);
    private static final int DARK = Color.rgb(36, 53, 39);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, locatorMessage, notificationState, bluetoothState, batteryState;
    private View connectionDot;
    private Button findButton, stopButton;
    private Boolean lastConnected;
    private ScrollView homeScroll;
    private TextView connectionSettingsStatus;
    private Button reconnectButton, disconnectButton;
    private boolean connectionPage, devicePage, findPage;
    private TextView deviceSettingsStatus;
    private RelayService.DeviceSettings displayedSettings;
    private static final String[] SLEEP_OPTIONS = {"15 秒", "30 秒", "60 秒", "120 秒", "300 秒", "关闭"};
    private Button sleepPreset;
    private int sleepPresetIndex = 1;
    private Button quietStart, quietEnd;
    private int quietStartHour = 22, quietEndHour = 7;
    private SeekBar volumeSlider, brightnessSlider;
    private Switch quietSwitch, clockSwitch;
    private Button saveDeviceButton, readDeviceButton;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean access = getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(
                new ComponentName(MainActivity.this, RelayService.class));
            boolean nearby = hasBluetoothPermissions();
            boolean connected = access && RelayService.status.startsWith("已连接");
            boolean canFind = access && RelayService.locatorReady;
            updateText(status, connected ? "已连接" : access ? RelayService.status : "等待通知使用权");
            if (lastConnected == null || lastConnected != connected) {
                connectionDot.setBackground(shape(connected ? 0xff6f9643 : 0xff8d7032, 8, 0));
                lastConnected = connected;
            }
            if (findPage) {
                String alert = RelayService.locateStatus;
                updateText(locatorMessage, canFind && alert.isEmpty() ? "已连接，可双向查找" :
                           !alert.isEmpty() ? alert :
                           connected ? "此连接暂不可用，请重连设备" : "连接设备后可双向响铃");
                setAvailable(findButton, canFind);
                setAvailable(stopButton, canFind);
            }
            updateText(notificationState, access ? "已授权" : "待授权");
            updateText(bluetoothState, nearby ? "已授权" : "待授权");
            PowerManager power = getSystemService(PowerManager.class);
            updateText(batteryState, power.isIgnoringBatteryOptimizations(getPackageName()) ? "已豁免" : "查看");
            if (connectionPage) {
                updateText(connectionSettingsStatus, access ? RelayService.status : "等待通知使用权");
                setAvailable(reconnectButton, access && nearby);
                setAvailable(disconnectButton, connected);
            }
            if (devicePage) refreshDeviceSettings(access);
            handler.postDelayed(this, 700);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) getWindow().setNavigationBarContrastEnforced(false);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);

        ScrollView scroll = pageScroll();
        homeScroll = scroll;
        LinearLayout content = column();
        content.setPadding(dp(20), dp(16), dp(20), dp(20));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);
        scroll.requestApplyInsets();

        LinearLayout brand = row();
        ImageView icon = new ImageView(this);
        icon.setImageResource(getApplicationInfo().icon);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        brand.addView(icon, new LinearLayout.LayoutParams(dp(39), dp(39)));
        TextView title = text(getApplicationInfo().loadLabel(getPackageManager()).toString(), 24, INK, true);
        LinearLayout.LayoutParams titleSize = new LinearLayout.LayoutParams(0, -2, 1f);
        titleSize.leftMargin = dp(11);
        brand.addView(title, titleSize);
        content.addView(brand, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout device = column();
        device.setBackground(shape(DARK, 22, 0));
        device.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout screen = column();
        screen.setBackground(shape(LCD, 13, 0));
        screen.setPadding(dp(16), dp(13), dp(16), dp(14));
        TextView name = text("PASSPORT BB", 13, INK, true);
        name.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        name.setLetterSpacing(.12f);
        screen.addView(name);
        LinearLayout connection = row();
        connection.setPadding(0, dp(7), 0, 0);
        connectionDot = new View(this);
        connectionDot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        connection.addView(connectionDot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        status = text("等待连接", 21, INK, true);
        LinearLayout.LayoutParams statusSize = new LinearLayout.LayoutParams(0, -2, 1);
        statusSize.leftMargin = dp(8);
        connection.addView(status, statusSize);
        TextView manage = text("连接设置  ›", 13, INK, true);
        manage.setPadding(dp(8), 0, 0, 0);
        connection.addView(manage);
        screen.addView(connection);
        screen.setForeground(ripple(Color.TRANSPARENT, 13, 0));
        screen.setFocusable(true);
        screen.setOnClickListener(view -> showConnectionSettings());
        device.addView(screen, new LinearLayout.LayoutParams(-1, -2));
        addSpaced(content, device, 16);

        addSpaced(content, settingsTile("查找设置", "让设备或手机响铃", "⌖", this::showFindSettings), 19);
        addSpaced(content, settingsTile("PASSPORT设置", "屏幕、声音与通知", "⚙︎", this::showDeviceSettings), 17);
    }

    private void addConnectionPermissions(LinearLayout content) {
        section(content, "权限与后台", 21);
        LinearLayout permissions = column();
        permissions.setBackground(shape(PAPER, 16, BORDER));
        notificationState = setting(permissions, "通知使用权", () -> open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        divider(permissions);
        bluetoothState = setting(permissions, "蓝牙权限", this::bluetooth);
        divider(permissions);
        batteryState = setting(permissions, "电池白名单", () -> open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        divider(permissions);
        setting(permissions, "应用系统设置", () -> startActivity(new Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        addSpaced(content, permissions, 8);

        LinearLayout helpCard = card();
        TextView help = text("设备长按确定键，进入「蓝牙配对设置」开放配对。首次连接请确认系统配对提示。\n\n"
            + "小米：在应用管理中允许自启动，电池策略设为无限制。\n"
            + "华为：在应用启动管理中允许自启动和后台活动。\n"
            + "OPPO / vivo：在电池或权限管理中允许自启动、后台运行和通知使用权。\n\n"
            + "安卓 11 及以下为兼容蓝牙扫描需要定位权限。App 不读取位置。", 14, MUTED, false);
        help.setLineSpacing(dp(3), 1f);
        help.setVisibility(View.GONE);
        Button disclosure = action("查看后台运行指引", false, () -> {
            boolean showing = help.getVisibility() == View.VISIBLE;
            help.setVisibility(showing ? View.GONE : View.VISIBLE);
        });
        helpCard.addView(disclosure, new LinearLayout.LayoutParams(-1, -2));
        addSpaced(helpCard, help, 10);
        addSpaced(content, helpCard, 12);
        addSpaced(content, action("退出并清空待发通知", false, () -> {
            getSharedPreferences("relay", MODE_PRIVATE).edit().putBoolean("paused", true).apply();
            RelayService.resumeSession(false);
            finishAndRemoveTask();
        }), 10);
    }

    private ScrollView pageScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(CANVAS);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        applyPageInsets(scroll);
        return scroll;
    }

    private void applyPageInsets(View page) {
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            int top, bottom, left, right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top; bottom = bars.bottom; left = bars.left; right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft(); right = insets.getSystemWindowInsetRight();
            }
            view.setPadding(left, top, right, bottom);
            return insets;
        });
    }

    private void showConnectionSettings() {
        if (connectionPage) return;
        ScrollView scroll = pageScroll();
        LinearLayout content = column();
        content.setPadding(dp(20), dp(16), dp(20), dp(20));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout toolbar = row();
        Button back = action("‹ 返回", false, this::showHome);
        toolbar.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView title = text("连接设置", 23, INK, true);
        LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLayout.leftMargin = dp(14);
        toolbar.addView(title, titleLayout);
        content.addView(toolbar);

        LinearLayout device = column();
        device.setBackground(shape(LCD, 16, BORDER));
        device.setPadding(dp(16), dp(15), dp(16), dp(15));
        TextView name = text("PASSPORT BB", 13, INK, true);
        name.setTypeface(Typeface.create("monospace", Typeface.BOLD));
        name.setLetterSpacing(.12f);
        device.addView(name);
        connectionSettingsStatus = text("等待连接", 19, INK, true);
        addSpaced(device, connectionSettingsStatus, 5);
        addSpaced(content, device, 17);

        LinearLayout controls = row();
        reconnectButton = action("重新连接", true, () -> {
            RelayService.ensureBinding(this);
            RelayService.reconnect();
        });
        disconnectButton = action("断开连接", false, RelayService::disconnectManually);
        controls.addView(reconnectButton, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, -2, 1);
        second.leftMargin = dp(8);
        controls.addView(disconnectButton, second);
        addSpaced(content, controls, 11);

        LinearLayout preferences = card();
        Switch reconnect = new Switch(this);
        reconnect.setText("自动重连");
        reconnect.setTextColor(INK);
        reconnect.setTextSize(16);
        reconnect.setPadding(dp(4), 0, dp(3), 0);
        reconnect.setMinimumHeight(dp(48));
        reconnect.setThumbTintList(new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
            new int[]{DARK, 0xff898f80}));
        reconnect.setTrackTintList(new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
            new int[]{0xffa0b67e, 0xffcfd4c5}));
        reconnect.setChecked(getSharedPreferences("relay", MODE_PRIVATE).getBoolean("automatic_reconnect", true));
        reconnect.setOnCheckedChangeListener((button, checked) -> {
            getSharedPreferences("relay", MODE_PRIVATE).edit().putBoolean("automatic_reconnect", checked).apply();
            RelayService.automaticReconnectChanged();
        });
        preferences.addView(reconnect, new LinearLayout.LayoutParams(-1, -2));
        TextView hint = text("意外掉线后自动恢复连接。手动断开后，点「重新连接」恢复。", 14, MUTED, false);
        hint.setPadding(dp(4), 0, dp(4), dp(3));
        preferences.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        addSpaced(content, preferences, 18);

        LinearLayout bluetooth = column();
        bluetooth.setBackground(shape(PAPER, 16, BORDER));
        setting(bluetooth, "系统蓝牙设置", () -> open(Settings.ACTION_BLUETOOTH_SETTINGS));
        addSpaced(content, bluetooth, 13);
        TextView pairing = text("首次连接时，先在 ESP32 的「蓝牙配对设置」开启配对。", 14, MUTED, false);
        pairing.setPadding(dp(4), 0, dp(4), 0);
        addSpaced(content, pairing, 12);
        addConnectionPermissions(content);

        connectionPage = true;
        setContentView(scroll);
        scroll.requestApplyInsets();
        handler.removeCallbacks(refresh);
        refresh.run();
    }

    private void showFindSettings() {
        if (findPage) return;
        ScrollView scroll = pageScroll();
        LinearLayout content = column();
        content.setPadding(dp(20), dp(16), dp(20), dp(20));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout toolbar = row();
        Button back = action("‹", false, this::showHome);
        back.setTextSize(25);
        back.setContentDescription("返回");
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(46), dp(48)));
        TextView title = text("查找设置", 23, INK, true);
        LinearLayout.LayoutParams titleSize = new LinearLayout.LayoutParams(0, -2, 1f);
        titleSize.leftMargin = dp(13);
        toolbar.addView(title, titleSize);
        content.addView(toolbar);

        locatorMessage = text("连接设备后可双向响铃", 14, INK, true);
        locatorMessage.setBackground(shape(LCD, 10, 0));
        locatorMessage.setPadding(dp(11), dp(8), dp(11), dp(8));
        addSpaced(content, locatorMessage, 12);

        section(content, "查找设备", 20);
        LinearLayout find = card();
        find.addView(text("让 PASSPORT BB 亮屏并响铃", 14, MUTED, false));
        LinearLayout actions = row();
        findButton = action("查找 PASSPORT", true, () -> RelayService.findDevice(true));
        stopButton = action("停止响铃", false, () -> RelayService.findDevice(false));
        actions.addView(findButton, new LinearLayout.LayoutParams(0, -2, 1.35f));
        LinearLayout.LayoutParams stopSize = new LinearLayout.LayoutParams(0, -2, 1f);
        stopSize.leftMargin = dp(8);
        actions.addView(stopButton, stopSize);
        addSpaced(find, actions, 11);
        addSpaced(content, find, 7);

        section(content, "查找手机", 19);
        LinearLayout phone = card();
        TextView guide = text("在 PASSPORT BB 上打开「功能菜单 → 查找设备」，按确定让手机响铃。", 14, MUTED, false);
        guide.setLineSpacing(dp(3), 1f);
        phone.addView(guide);
        addSpaced(content, phone, 7);

        findPage = true;
        setContentView(scroll);
        scroll.requestApplyInsets();
        handler.removeCallbacks(refresh);
        refresh.run();
    }

    private void showDeviceSettings() {
        if (devicePage) return;
        LinearLayout page = column();
        page.setBackgroundColor(CANVAS);
        applyPageInsets(page);

        LinearLayout top = column();
        top.setPadding(dp(20), dp(12), dp(20), dp(10));
        LinearLayout toolbar = row();
        Button back = action("‹", false, this::showHome);
        back.setTextSize(25);
        back.setContentDescription("返回");
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(46), dp(48)));
        TextView title = text("PASSPORT设置", 23, INK, true);
        LinearLayout.LayoutParams titleLayout = new LinearLayout.LayoutParams(0, -2, 1f);
        titleLayout.leftMargin = dp(13);
        toolbar.addView(title, titleLayout);
        top.addView(toolbar);
        deviceSettingsStatus = text("等待读取设备设置", 13, INK, true);
        deviceSettingsStatus.setBackground(shape(LCD, 10, 0));
        deviceSettingsStatus.setPadding(dp(11), dp(7), dp(11), dp(7));
        addSpaced(top, deviceSettingsStatus, 9);
        page.addView(top);

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout content = column();
        content.setPadding(dp(20), dp(0), dp(20), dp(13));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));

        section(content, "显示", 3);
        LinearLayout display = settingsGroup();
        brightnessSlider = slider(display, "屏幕亮度", 10);
        divider(display);
        sleepPreset = choiceRow(display, "自动息屏",
            () -> showChoiceSheet("自动息屏", SLEEP_OPTIONS, sleepPresetIndex, 2, selected -> {
                sleepPresetIndex = selected;
                updateSleepLabel();
            }));
        updateSleepLabel();
        divider(display);
        clockSwitch = switchRow(display, "息屏时钟");
        addSpaced(content, display, 7);

        section(content, "声音与通知", 15);
        LinearLayout sound = settingsGroup();
        volumeSlider = slider(sound, "提示音音量", 0);
        divider(sound);
        setting(sound, "通知应用管理", () -> startActivity(new Intent(this, NotificationAppsActivity.class)));
        addSpaced(content, sound, 7);

        section(content, "免打扰", 15);
        LinearLayout quiet = settingsGroup();
        quietSwitch = switchRow(quiet, "启用免打扰");
        LinearLayout schedule = column();
        divider(schedule);
        LinearLayout interval = row();
        interval.setPadding(dp(13), dp(9), dp(13), dp(12));
        quietStart = intervalControl(interval, "开始时间", true);
        TextView dash = text("—", 15, MUTED, false);
        dash.setPadding(dp(8), dp(17), dp(8), 0);
        interval.addView(dash);
        quietEnd = intervalControl(interval, "结束时间", false);
        schedule.addView(interval);
        updateQuietHourLabels();
        quiet.addView(schedule);
        schedule.setVisibility(View.GONE);
        quietSwitch.setOnCheckedChangeListener((button, checked) ->
            schedule.setVisibility(checked ? View.VISIBLE : View.GONE));
        addSpaced(content, quiet, 7);

        View rule = new View(this);
        rule.setBackgroundColor(BORDER);
        page.addView(rule, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout buttons = row();
        buttons.setBackgroundColor(PAPER);
        buttons.setPadding(dp(20), dp(11), dp(20), dp(11));
        readDeviceButton = action("重新读取", false, () -> RelayService.requestSettings(null));
        saveDeviceButton = action("保存到 PASSPORT", true, this::saveDeviceSettings);
        buttons.addView(readDeviceButton, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams saveSize = new LinearLayout.LayoutParams(0, -2, 1.65f);
        saveSize.leftMargin = dp(8);
        buttons.addView(saveDeviceButton, saveSize);
        page.addView(buttons);

        devicePage = true;
        displayedSettings = null;
        setContentView(page);
        page.requestApplyInsets();
        RelayService.requestSettings(null);
        handler.removeCallbacks(refresh);
        refresh.run();
    }

    private LinearLayout settingsGroup() {
        LinearLayout group = column();
        group.setBackground(shape(PAPER, 16, BORDER));
        return group;
    }

    private LinearLayout settingsTile(String title, String description, String symbol, Runnable open) {
        LinearLayout tile = row();
        tile.setBackground(ripple(PAPER, 16, BORDER));
        tile.setPadding(dp(13), dp(11), dp(13), dp(11));
        tile.setMinimumHeight(dp(67));
        tile.setFocusable(true);
        tile.setOnClickListener(view -> open.run());

        TextView icon = text(symbol, 24, INK, false);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(shape(LCD, 11, 0));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(43), dp(43)));
        LinearLayout labels = column();
        labels.addView(text(title, 16, INK, true));
        labels.addView(text(description, 13, MUTED, false));
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1f);
        size.leftMargin = dp(12);
        tile.addView(labels, size);
        TextView arrow = text("›", 23, MUTED, false);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        tile.addView(arrow);
        return tile;
    }

    private void refreshDeviceSettings(boolean access) {
        boolean ready = access && RelayService.locatorReady;
        boolean busy = RelayService.settingsBusy;
        RelayService.DeviceSettings current = RelayService.deviceSettings;
        if (current != null && current != displayedSettings) {
            sleepPresetIndex = current.sleep;
            updateSleepLabel();
            volumeSlider.setProgress(current.volume);
            brightnessSlider.setProgress(current.brightness - 10);
            quietSwitch.setChecked((current.flags & 1) != 0);
            clockSwitch.setChecked((current.flags & 2) != 0);
            quietStartHour = current.start;
            quietEndHour = current.end;
            updateQuietHourLabels();
            displayedSettings = current;
        }
        boolean editable = ready && !busy && current != null;
        setAvailable(readDeviceButton, ready && !busy);
        setAvailable(saveDeviceButton, editable);
        sleepPreset.setEnabled(editable);
        volumeSlider.setEnabled(editable);
        brightnessSlider.setEnabled(editable);
        quietSwitch.setEnabled(editable);
        clockSwitch.setEnabled(editable);
        quietStart.setEnabled(editable && quietSwitch.isChecked());
        quietEnd.setEnabled(editable && quietSwitch.isChecked());
        updateText(deviceSettingsStatus, !ready ? "请先连接 ESP32" : RelayService.settingsStatus.isEmpty()
            ? "点「重新读取」获取设备设置" : RelayService.settingsStatus);
    }

    private void saveDeviceSettings() {
        if (displayedSettings == null) return;
        int flags = (quietSwitch.isChecked() ? 1 : 0) | (clockSwitch.isChecked() ? 2 : 0);
        RelayService.requestSettings(new RelayService.DeviceSettings(displayedSettings.revision,
            sleepPresetIndex, volumeSlider.getProgress(), brightnessSlider.getProgress()+10,
            flags, quietStartHour, quietEndHour));
    }

    private static String hourLabel(int hour) {
        return (hour < 10 ? "0" : "") + hour + ":00";
    }

    private void updateQuietHourLabels() {
        updateText(quietStart, hourLabel(quietStartHour));
        quietStart.setContentDescription("开始时间 " + hourLabel(quietStartHour));
        updateText(quietEnd, hourLabel(quietEndHour));
        quietEnd.setContentDescription("结束时间 " + hourLabel(quietEndHour));
    }

    private void updateSleepLabel() {
        updateText(sleepPreset, SLEEP_OPTIONS[sleepPresetIndex] + "  ›");
        sleepPreset.setContentDescription("自动息屏 " + SLEEP_OPTIONS[sleepPresetIndex]);
    }

    private Button choiceRow(LinearLayout parent, String title, Runnable pick) {
        LinearLayout line = row();
        line.setPadding(dp(13), dp(4), dp(10), dp(4));
        line.addView(text(title, 15, INK, true), new LinearLayout.LayoutParams(0, -2, 1f));
        Button option = action("", false, pick);
        line.addView(option, new LinearLayout.LayoutParams(dp(104), dp(48)));
        parent.addView(line, new LinearLayout.LayoutParams(-1, -2));
        return option;
    }

    private Button intervalControl(LinearLayout row, String title, boolean start) {
        LinearLayout field = column();
        TextView caption = text(title, 12, MUTED, false);
        caption.setGravity(Gravity.CENTER);
        field.addView(caption, new LinearLayout.LayoutParams(-1, -2));
        Button option = action("", false, () -> {
            String[] hours = new String[24];
            for (int i = 0; i < hours.length; i++) hours[i] = hourLabel(i);
            showChoiceSheet(title, hours, start ? quietStartHour : quietEndHour, 4, selected -> {
                if (start) quietStartHour = selected;
                else quietEndHour = selected;
                updateQuietHourLabels();
            });
        });
        option.setTextSize(19);
        addSpaced(field, option, 4);
        row.addView(field, new LinearLayout.LayoutParams(0, -2, 1f));
        return option;
    }

    private void styleChoice(Button button, boolean selected) {
        button.setSelected(selected);
        button.setTextColor(selected ? Color.rgb(247, 250, 239) : INK);
        button.setBackground(ripple(selected ? DARK : PAPER, 10, selected ? 0 : BORDER));
    }

    private void showChoiceSheet(String title, String[] values, int current, int columns, IntConsumer apply) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout sheet = column();
        GradientDrawable background = shape(CANVAS, 24, 0);
        int corner = dp(24);
        background.setCornerRadii(new float[]{corner, corner, corner, corner, 0, 0, 0, 0});
        sheet.setBackground(background);
        sheet.setPadding(dp(18), dp(17), dp(18), dp(16));

        LinearLayout heading = row();
        TextView name = text(title, 21, INK, true);
        heading.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView chosen = text(values[current], 14, INK, true);
        chosen.setBackground(shape(LCD, 9, 0));
        chosen.setPadding(dp(9), dp(5), dp(9), dp(5));
        heading.addView(chosen);
        sheet.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        int[] draft = {current};
        Button[] choices = new Button[values.length];
        LinearLayout grid = column();
        for (int first = 0; first < values.length; first += columns) {
            LinearLayout items = row();
            for (int i = first; i < first + columns; i++) {
                LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, dp(48), 1f);
                if (i > first) size.leftMargin = dp(6);
                if (i >= values.length) {
                    items.addView(new View(this), size);
                    continue;
                }
                final int index = i;
                Button item = action(values[i], false, () -> {
                    styleChoice(choices[draft[0]], false);
                    draft[0] = index;
                    styleChoice(choices[index], true);
                    updateText(chosen, values[index]);
                });
                item.setPadding(dp(3), dp(6), dp(3), dp(6));
                choices[i] = item;
                styleChoice(item, i == current);
                items.addView(item, size);
            }
            addSpaced(grid, items, first == 0 ? 0 : 6);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(grid, new ScrollView.LayoutParams(-1, -2));
        int available = getResources().getConfiguration().screenHeightDp;
        int gridHeight = ((values.length + columns - 1) / columns) * 54 - 6;
        LinearLayout.LayoutParams gridSize = new LinearLayout.LayoutParams(-1, dp(Math.min(gridHeight, Math.max(72, available - 144))));
        gridSize.topMargin = dp(13);
        sheet.addView(scroll, gridSize);

        LinearLayout actions = row();
        actions.addView(action("取消", false, dialog::dismiss), new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams confirm = new LinearLayout.LayoutParams(0, -2, 1.5f);
        confirm.leftMargin = dp(8);
        actions.addView(action("确定", true, () -> {
            apply.accept(draft[0]);
            dialog.dismiss();
        }), confirm);
        addSpaced(sheet, actions, 13);

        dialog.setContentView(sheet);
        dialog.setCanceledOnTouchOutside(true);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setGravity(Gravity.BOTTOM);
            dialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }
        dialog.show();
        if (dialog.getWindow() != null)
            dialog.getWindow().setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                         android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private SeekBar slider(LinearLayout parent, String title, int minimum) {
        LinearLayout heading = row();
        heading.setPadding(dp(13), dp(3), dp(13), 0);
        heading.addView(text(title, 15, INK, true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView amount = text(minimum + "%", 13, INK, true);
        amount.setGravity(Gravity.CENTER);
        amount.setMinWidth(dp(47));
        amount.setPadding(dp(6), dp(3), dp(6), dp(3));
        amount.setBackground(shape(0xffe6ecd8, 8, 0));
        heading.addView(amount);
        addSpaced(parent, heading, 7);
        SeekBar slider = new SeekBar(this);
        slider.setContentDescription(title);
        slider.setMax(100 - minimum);
        slider.setProgressTintList(ColorStateList.valueOf(DARK));
        slider.setThumbTintList(ColorStateList.valueOf(DARK));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                updateText(amount, (progress + minimum) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        LinearLayout.LayoutParams sliderLayout = new LinearLayout.LayoutParams(-1, dp(37));
        sliderLayout.leftMargin = dp(3);
        sliderLayout.rightMargin = dp(3);
        parent.addView(slider, sliderLayout);
        return slider;
    }

    private Switch switchRow(LinearLayout parent, String title) {
        Switch toggle = new Switch(this);
        toggle.setText(title);
        toggle.setTextSize(15);
        toggle.setTextColor(INK);
        toggle.setMinimumHeight(dp(48));
        toggle.setPadding(dp(13), 0, dp(13), 0);
        toggle.setThumbTintList(new ColorStateList(
            new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
            new int[]{DARK, 0xff898f80}));
        toggle.setTrackTintList(ColorStateList.valueOf(0xffb3c59b));
        parent.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
        return toggle;
    }

    private void showHome() {
        if (!connectionPage && !devicePage && !findPage) return;
        connectionPage = false;
        devicePage = false;
        findPage = false;
        setContentView(homeScroll);
        homeScroll.requestApplyInsets();
    }

    @Override public void onBackPressed() {
        if (connectionPage || devicePage || findPage) showHome();
        else super.onBackPressed();
    }

    private static void updateText(TextView view, String value) {
        if (view != null && !value.contentEquals(view.getText())) view.setText(value);
    }

    private static void setAvailable(Button button, boolean enabled) {
        if (button.isEnabled() == enabled) return;
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : .55f);
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

    private LinearLayout card() {
        LinearLayout layout = column();
        layout.setBackground(shape(PAPER, 16, BORDER));
        layout.setPadding(dp(12), dp(13), dp(12), dp(13));
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

    private GradientDrawable shape(int color, int radius, int outline) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (outline != 0) drawable.setStroke(dp(1), outline);
        return drawable;
    }

    private RippleDrawable ripple(int color, int radius, int outline) {
        return new RippleDrawable(ColorStateList.valueOf(0x2637442D),
            shape(color, radius, outline), shape(Color.WHITE, radius, 0));
    }

    private Button action(String name, boolean primary, Runnable run) {
        Button button = new Button(this);
        button.setText(name);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTextColor(primary ? Color.rgb(247, 250, 239) : INK);
        button.setPadding(dp(8), dp(8), dp(8), dp(8));
        button.setMinimumHeight(dp(48));
        button.setMinHeight(dp(48));
        button.setMinWidth(0);
        button.setStateListAnimator(null);
        button.setBackground(ripple(primary ? DARK : 0xffe8eddd, 12, primary ? 0 : BORDER));
        button.setOnClickListener(view -> run.run());
        return button;
    }

    private void addSpaced(LinearLayout parent, View child, int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(margin);
        parent.addView(child, params);
    }

    private void section(LinearLayout parent, String name, int margin) {
        addSpaced(parent, text(name, 17, INK, true), margin);
    }

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.leftMargin = dp(13);
        params.rightMargin = dp(13);
        parent.addView(line, params);
    }

    private TextView setting(LinearLayout parent, String name, Runnable action) {
        LinearLayout item = row();
        item.setBackground(ripple(Color.TRANSPARENT, 10, 0));
        item.setPadding(dp(13), dp(9), dp(13), dp(9));
        item.setMinimumHeight(dp(49));
        item.setFocusable(true);
        item.setOnClickListener(view -> action.run());
        item.addView(text(name, 15, INK, true), new LinearLayout.LayoutParams(0, -2, 1f));
        TextView state = text("", 13, MUTED, false);
        item.addView(state);
        TextView arrow = text("›", 22, MUTED, false);
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = dp(8);
        item.addView(arrow, params);
        parent.addView(item, new LinearLayout.LayoutParams(-1, -2));
        return state;
    }

    private void open(String action) {
        try { startActivity(new Intent(action)); }
        catch (android.content.ActivityNotFoundException unavailable) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
    }

    private String[] bluetoothPermissions() {
        return Build.VERSION.SDK_INT >= 31
            ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
            : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasBluetoothPermissions() {
        for (String permission : bluetoothPermissions())
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private void bluetooth() {
        if (!hasBluetoothPermissions()) {
            requestPermissions(bluetoothPermissions(), 12);
            return;
        }
        BluetoothAdapter adapter = getSystemService(android.bluetooth.BluetoothManager.class).getAdapter();
        if (adapter != null && !adapter.isEnabled()) startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
        else open(Settings.ACTION_BLUETOOTH_SETTINGS);
    }

    @Override public void onRequestPermissionsResult(int code, String[] requested, int[] results) {
        super.onRequestPermissionsResult(code, requested, results);
        if (code == 12) RelayService.resumeSession(true);
    }

    @Override protected void onStart() {
        super.onStart();
        getSharedPreferences("relay", MODE_PRIVATE).edit().putBoolean("paused", false).apply();
        RelayService.ensureBinding(this);
        RelayService.resumeSession(true);
        handler.post(refresh);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(refresh);
        super.onStop();
    }
}
