package cn.folotoy.passportcompanion;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.media.session.MediaSessionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import org.json.JSONException;
import org.json.JSONObject;

public final class RelayService extends NotificationListenerService {
    private static final String DEVICE_NAME = "Passport BB";
    private static final UUID SERVICE = UUID.fromString("7c5a0001-3e4b-4f2a-9d6c-1a2b3c4d5e6f");
    private static final UUID RX = UUID.fromString("7c5a0002-3e4b-4f2a-9d6c-1a2b3c4d5e6f");
    private static final UUID LOCATOR = UUID.fromString("7c5a0003-3e4b-4f2a-9d6c-1a2b3c4d5e6f");
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final int MAX_QUEUE = 32;
    private static final int CHUNK = 20;
    static volatile String status = "等待连接";
    static volatile boolean locatorReady;
    static volatile String locateStatus = "";
    private static volatile RelayService active;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int MAX_OBSERVED_APPS = 96;
    private static final Map<String, String> observed = new LinkedHashMap<>();
    private static final class QueuedNotification {
        final String source;
        final byte[] frame;
        QueuedNotification(String source, byte[] frame) { this.source = source; this.frame = frame; }
    }
    private final ArrayDeque<QueuedNotification> pending = new ArrayDeque<>(MAX_QUEUE);
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rx, locator;
    private boolean locatorSetup, writingLocator;
    private final ArrayDeque<byte[]> controls = new ArrayDeque<>();
    private int writingOpcode, settingsToken;
    private final Runnable settingsTimeout = () -> finishSettings("设备未响应，可以重新读取");
    static volatile DeviceSettings deviceSettings;
    static volatile boolean settingsBusy;
    static volatile String settingsStatus = "";
    static final class DeviceSettings {
        final int revision, sleep, volume, brightness, flags, start, end;
        DeviceSettings(int revision, int sleep, int volume, int brightness, int flags, int start, int end) {
            this.revision = revision;
            this.sleep = sleep;
            this.volume = volume;
            this.brightness = brightness;
            this.flags = flags;
            this.start = start;
            this.end = end;
        }
    }
    private Ringtone phoneAlarm;
    private final Runnable stopPhoneAlarm = () -> ringPhone(false);
    private BluetoothLeScanner scanner;
    private byte[] sending;
    private int offset, inFlight;
    private boolean connected, paused, authorized, discovering, manualHold;
    private final Runnable retry = this::connect;
    private final Runnable timeout = this::loseConnection;
    private final Runnable writeTimeout = this::loseConnection;

    static Map<String, String> observedApps() {
        synchronized (observed) { return new LinkedHashMap<>(observed); }
    }

    static Set<String> blockedApps(Context context) {
        return context.getSharedPreferences("relay", MODE_PRIVATE)
            .getStringSet("blocked_apps", Collections.emptySet());
    }

    static void setForwardAllowed(Context context, String source, boolean allowed) {
        Set<String> excluded = new HashSet<>(blockedApps(context));
        if (allowed) excluded.remove(source);
        else excluded.add(source);
        context.getSharedPreferences("relay", MODE_PRIVATE)
            .edit().putStringSet("blocked_apps", excluded).apply();
        RelayService service = active;
        if (!allowed && service != null)
            service.handler.post(() -> service.pending.removeIf(item -> item.source.equals(source)));
    }

    private static void observeApp(String source, String label) {
        synchronized (observed) {
            if (!observed.containsKey(source) && observed.size() >= MAX_OBSERVED_APPS)
                observed.remove(observed.keySet().iterator().next());
            observed.put(source, label);
        }
    }

    static void findDevice(boolean start) {
        RelayService service = active;
        if (service == null) return;
        service.handler.post(() -> {
            if (!start) service.ringPhone(false);
            if (!locatorReady || service.paused || !service.authorized) {
                locateStatus = "查找需要连接支持此功能的固件";
                return;
            }
            service.queueControl(new byte[]{(byte)(start ? 1 : 0)});
        });
    }

    static void requestSettings(DeviceSettings desired) {
        RelayService service = active;
        if (service == null) { settingsStatus = "请先连接 ESP32"; return; }
        service.handler.post(() -> {
            if (!locatorReady || !service.authorized || service.paused) {
                settingsStatus = "请先连接 ESP32";
                return;
            }
            if (settingsBusy) return;
            settingsBusy = true;
            settingsStatus = desired == null ? "正在读取设备设置…" : "正在保存到 ESP32…";
            int token = ++service.settingsToken & 255;
            byte[] request = desired == null ? new byte[]{2, (byte)token} : new byte[] {
                3, (byte)token, (byte)desired.revision, (byte)(desired.revision >> 8),
                (byte)desired.sleep, (byte)desired.volume, (byte)desired.brightness,
                (byte)desired.flags, (byte)desired.start, (byte)desired.end
            };
            service.handler.postDelayed(service.settingsTimeout, 9000);
            service.queueControl(request);
        });
    }

    private void queueControl(byte[] request) {
        if (controls.size() >= 8) {
            if (request[0] >= 2) finishSettings("蓝牙正忙，请重试");
            else locateStatus = "蓝牙正忙，请重试";
            return;
        }
        controls.addLast(request);
        sendNext();
    }

    private void finishSettings(String message) {
        handler.removeCallbacks(settingsTimeout);
        settingsBusy = false;
        settingsStatus = message;
    }

    private void ringPhone(boolean start) {
        handler.removeCallbacks(stopPhoneAlarm);
        if (phoneAlarm != null) {
            phoneAlarm.stop();
            phoneAlarm = null;
        }
        if (!start) {
            if ("ESP32 正在查找这部手机".equals(locateStatus)) locateStatus = "";
            return;
        }
        try {
            phoneAlarm = RingtoneManager.getRingtone(this,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM));
            if (phoneAlarm == null)
                phoneAlarm = RingtoneManager.getRingtone(this,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
            if (phoneAlarm == null) { locateStatus = "手机没有可播放的铃声"; return; }
            phoneAlarm.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            if (Build.VERSION.SDK_INT >= 28) phoneAlarm.setLooping(true);
            phoneAlarm.play();
            locateStatus = "ESP32 正在查找这部手机";
            handler.postDelayed(stopPhoneAlarm, 10000);
        } catch (IllegalStateException | SecurityException error) {
            ringPhone(false);
            locateStatus = "手机无法播放查找铃声";
        }
    }

    static void ensureBinding(Context context) {
        if (active != null) return;
        ComponentName component = new ComponentName(context, RelayService.class);
        if (context.getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(component))
            NotificationListenerService.requestRebind(component);
    }

    static void resumeSession(boolean resume) {
        RelayService service = active;
        if (service == null) return;
        service.handler.post(() -> {
            service.paused = !resume;
            if (resume) service.connectAutomatically();
            else {
                service.pending.clear();
                service.close();
                status = "已停止";
            }
        });
    }

    static void reconnect() {
        RelayService service = active;
        if (service == null) return;
        service.handler.post(() -> {
            if (!service.authorized || service.paused) return;
            service.manualHold = false;
            service.getSharedPreferences("relay", MODE_PRIVATE).edit().putBoolean("manual_hold", false).apply();
            service.close();
            status = "正在重新连接";
            service.handler.postDelayed(service.retry, 350);
        });
    }

    static void disconnectManually() {
        RelayService service = active;
        if (service == null) return;
        service.handler.post(() -> {
            service.manualHold = true;
            service.getSharedPreferences("relay", MODE_PRIVATE).edit().putBoolean("manual_hold", true).apply();
            service.close();
            status = "已手动断开";
        });
    }

    static void automaticReconnectChanged() {
        RelayService service = active;
        if (service == null) return;
        service.handler.post(() -> {
            if (service.automaticReconnect()) service.connectAutomatically();
            else if (service.gatt == null && service.scanner == null) {
                service.handler.removeCallbacks(service.retry);
                if (!service.manualHold && service.authorized && !service.paused)
                    status = "等待手动连接";
            }
        });
    }

    private boolean automaticReconnect() {
        return getSharedPreferences("relay", MODE_PRIVATE).getBoolean("automatic_reconnect", true);
    }

    private void connectAutomatically() {
        if (!authorized || paused || gatt != null || scanner != null) return;
        if (manualHold) status = "已手动断开";
        else if (automaticReconnect()) connect();
        else status = "等待手动连接";
    }

    private final BroadcastReceiver bond = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (gatt == null || device == null || !device.equals(gatt.getDevice())) return;
            int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
            if (state == BluetoothDevice.BOND_BONDED && connected) startDiscovery();
            else if (state == BluetoothDevice.BOND_NONE) loseConnection();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bond, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(bond, filter);
    }

    @Override public void onListenerConnected() {
        active = this;
        handler.post(() -> {
            authorized = true;
            paused = getSharedPreferences("relay", MODE_PRIVATE).getBoolean("paused", false);
            manualHold = getSharedPreferences("relay", MODE_PRIVATE).getBoolean("manual_hold", false);
            connectAutomatically();
        });
    }

    @Override public void onListenerDisconnected() { stopRelay(); }

    @Override public void onDestroy() {
        stopRelay();
        unregisterReceiver(bond);
        super.onDestroy();
    }

    private void stopRelay() {
        if (active == this) active = null;
        authorized = false;
        paused = true;
        pending.clear();
        close();
        status = "等待通知使用权";
    }

    private boolean has(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void later() {
        handler.removeCallbacks(retry);
        if (authorized && !paused && !manualHold && automaticReconnect()) handler.postDelayed(retry, 4000);
    }

    private void connect() {
        if (paused || manualHold || !authorized || gatt != null || scanner != null) return;
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager.getAdapter();
        if (Build.VERSION.SDK_INT >= 31 &&
                (!has(Manifest.permission.BLUETOOTH_SCAN) || !has(Manifest.permission.BLUETOOTH_CONNECT))) {
            status = "等待蓝牙权限";
            later();
            return;
        }
        if (Build.VERSION.SDK_INT < 31 && !has(Manifest.permission.ACCESS_FINE_LOCATION)) {
            status = "等待扫描权限";
            later();
            return;
        }
        try {
            if (adapter == null || !adapter.isEnabled()) {
                status = "等待打开蓝牙";
                later();
                return;
            }
            String address = getSharedPreferences("relay", MODE_PRIVATE).getString("device", "");
            if (BluetoothAdapter.checkBluetoothAddress(address)) {
                BluetoothDevice known = adapter.getRemoteDevice(address);
                if (known.getBondState() == BluetoothDevice.BOND_BONDED) {
                    open(known);
                    return;
                }
            }
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { later(); return; }
            status = "正在查找 Passport BB";
            ScanFilter filter = new ScanFilter.Builder().setDeviceName(DEVICE_NAME).build();
            scanner.startScan(Collections.singletonList(filter),
                new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scans);
            handler.postDelayed(timeout, 10000);
        } catch (SecurityException | IllegalStateException blocked) {
            loseConnection();
        }
    }

    private final ScanCallback scans = new ScanCallback() {
        @Override public void onScanResult(int kind, ScanResult result) {
            handler.post(() -> {
                if (scanner == null || paused || !authorized) return;
                stopScan();
                open(result.getDevice());
            });
        }

        @Override public void onScanFailed(int error) { handler.post(RelayService.this::loseConnection); }
    };

    private void stopScan() {
        if (scanner != null) {
            try { scanner.stopScan(scans); } catch (SecurityException | IllegalStateException ignored) {}
            scanner = null;
        }
        handler.removeCallbacks(timeout);
    }

    private void open(BluetoothDevice device) {
        status = "正在连接 / 配对";
        try {
            gatt = device.connectGatt(this, false, callbacks, BluetoothDevice.TRANSPORT_LE);
            if (gatt == null) { later(); return; }
            handler.postDelayed(timeout, 35000);
        } catch (SecurityException | IllegalStateException blocked) {
            loseConnection();
        }
    }

    private void close() {
        stopScan();
        handler.removeCallbacks(retry);
        handler.removeCallbacks(writeTimeout);
        BluetoothGatt previous = gatt;
        gatt = null;
        connected = false;
        discovering = false;
        rx = null;
        locator = null;
        locatorSetup = writingLocator = locatorReady = false;
        controls.clear();
        deviceSettings = null;
        finishSettings("");
        locateStatus = "";
        ringPhone(false);
        sending = null;
        offset = inFlight = 0;
        if (previous != null) {
            try { previous.disconnect(); } catch (SecurityException ignored) {}
            try { previous.close(); } catch (SecurityException ignored) {}
        }
    }

    private void loseConnection() {
        close();
        status = manualHold ? "已手动断开" : automaticReconnect() ? "设备离线 · 正在重连" : "设备离线";
        later();
    }

    private void startDiscovery() {
        if (gatt == null || discovering || rx != null) return;
        try {
            discovering = gatt.discoverServices();
            if (!discovering) loseConnection();
        } catch (SecurityException blocked) { loseConnection(); }
    }

    private void startBondIfNeeded(BluetoothGatt connection) {
        if (connection != gatt || !connected) return;
        try {
            int state = connection.getDevice().getBondState();
            if (state == BluetoothDevice.BOND_BONDED) startDiscovery();
            else if (state == BluetoothDevice.BOND_NONE) connection.getDevice().createBond();
        } catch (SecurityException blocked) { loseConnection(); }
    }

    private final BluetoothGattCallback callbacks = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt connection, int result, int state) {
            handler.post(() -> {
                if (connection != gatt) return;
                if (result != BluetoothGatt.GATT_SUCCESS || state != BluetoothProfile.STATE_CONNECTED) {
                    loseConnection();
                    return;
                }
                connected = true;
                try {
                    int bondState = connection.getDevice().getBondState();
                    if (bondState == BluetoothDevice.BOND_BONDED) startDiscovery();
                    else if (bondState == BluetoothDevice.BOND_NONE) {
                        // Give the peripheral's own security request time to reach Android.
                        handler.postDelayed(() -> startBondIfNeeded(connection), 1800);
                    }
                } catch (SecurityException blocked) { loseConnection(); }
            });
        }

        @Override public void onServicesDiscovered(BluetoothGatt connection, int result) {
            handler.post(() -> {
                if (connection != gatt) return;
                discovering = false;
                BluetoothGattService service = connection.getService(SERVICE);
                rx = service == null ? null : service.getCharacteristic(RX);
                if (result != BluetoothGatt.GATT_SUCCESS || rx == null) { loseConnection(); return; }
                locator = service.getCharacteristic(LOCATOR);
                if (locator != null && subscribeLocator(connection)) {
                    locatorSetup = true;
                    return;
                }
                finishSetup(connection, false);
            });
        }

        @Override public void onDescriptorWrite(BluetoothGatt connection, BluetoothGattDescriptor descriptor, int result) {
            handler.post(() -> {
                if (connection == gatt && locatorSetup && CCCD.equals(descriptor.getUuid())
                    && LOCATOR.equals(descriptor.getCharacteristic().getUuid()))
                    finishSetup(connection, result == BluetoothGatt.GATT_SUCCESS);
            });
        }

        @Override public void onCharacteristicChanged(BluetoothGatt connection,
                BluetoothGattCharacteristic characteristic, byte[] value) {
            handleLocatorEvent(connection, characteristic, value);
        }

        @Override public void onCharacteristicChanged(BluetoothGatt connection, BluetoothGattCharacteristic characteristic) {
            byte[] value = characteristic.getValue();
            if (value != null) handleLocatorEvent(connection, characteristic, value.clone());
        }

        @Override public void onCharacteristicWrite(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, int result) {
            handler.post(() -> {
                if (connection != gatt) return;
                if (LOCATOR.equals(characteristic.getUuid()) && writingLocator) {
                    handler.removeCallbacks(writeTimeout);
                    writingLocator = false;
                    if (writingOpcode < 2)
                        locateStatus = result == BluetoothGatt.GATT_SUCCESS ? "已发送响铃控制请求" : "查找请求失败";
                    else if (result != BluetoothGatt.GATT_SUCCESS)
                        finishSettings("设备未接受设置请求，请确认固件已更新");
                    sendNext();
                    return;
                }
                if (!RX.equals(characteristic.getUuid()) || sending == null) return;
                handler.removeCallbacks(writeTimeout);
                if (result != BluetoothGatt.GATT_SUCCESS) { loseConnection(); return; }
                offset += inFlight;
                if (offset < sending.length) { write(); return; }
                if (!pending.isEmpty() && pending.peekFirst().frame == sending) pending.removeFirst();
                sending = null;
                offset = inFlight = 0;
                sendNext();
            });
        }
    };

    private boolean subscribeLocator(BluetoothGatt connection) {
        BluetoothGattDescriptor descriptor = locator.getDescriptor(CCCD);
        if (descriptor == null) return false;
        try {
            if (!connection.setCharacteristicNotification(locator, true)) return false;
            byte[] enabled = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
            if (Build.VERSION.SDK_INT >= 33)
                return connection.writeDescriptor(descriptor, enabled) == BluetoothStatusCodes.SUCCESS;
            descriptor.setValue(enabled);
            return connection.writeDescriptor(descriptor);
        } catch (SecurityException | IllegalStateException blocked) { return false; }
    }

    private void finishSetup(BluetoothGatt connection, boolean supportsLocator) {
        locatorSetup = false;
        locatorReady = supportsLocator;
        handler.removeCallbacks(timeout);
        getSharedPreferences("relay", MODE_PRIVATE).edit()
            .putString("device", connection.getDevice().getAddress()).apply();
        status = "已连接 Passport BB";
        long now = System.currentTimeMillis();
        try {
            JSONObject clock = new JSONObject();
            clock.put("clock", now / 1000);
            clock.put("zone", TimeZone.getDefault().getOffset(now) / 60000);
            sending = frame(clock);
            write();
        } catch (JSONException failure) { sendNext(); }
    }

    private void handleLocatorEvent(BluetoothGatt connection, BluetoothGattCharacteristic characteristic, byte[] value) {
        if (!LOCATOR.equals(characteristic.getUuid())) return;
        handler.post(() -> {
            if (connection != gatt || !locatorReady || !authorized || paused) return;
            if (value.length == 1 && (value[0] == 0 || value[0] == 1)) {
                ringPhone(value[0] == 1);
            } else if (value.length == 1 && value[0] >= 3 && value[0] <= 5) {
                mediaTransport(value[0] - 3);
            } else if (value.length == 11 && value[0] == 2 && settingsBusy &&
                       (value[1] & 255) == (settingsToken & 255)) {
                int response = value[2] & 255;
                int revision = (value[3] & 255) | (value[4] & 255) << 8;
                deviceSettings = new DeviceSettings(revision, value[5] & 255, value[6] & 255,
                    value[7] & 255, value[8] & 255, value[9] & 255, value[10] & 255);
                finishSettings(response == 0 ? "已同步设备设置" : response == 1 ? "设置已在设备上更改，请重试" :
                               response == 2 ? "设备拒绝了无效设置" : "设备保存失败");
            }
        });
    }

    /* 0 = previous, 1 = next, 2 = play/pause for whatever is currently playing.
     * Notification access is what allows reading other apps' media sessions here. */
    private void mediaTransport(int action) {
        try {
            MediaSessionManager manager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            if (manager == null) return;
            java.util.List<MediaController> sessions =
                manager.getActiveSessions(new ComponentName(this, RelayService.class));
            MediaController target = null;
            for (MediaController candidate : sessions) {
                PlaybackState state = candidate.getPlaybackState();
                if (state == null) continue;
                int code = state.getState();
                if (code == PlaybackState.STATE_PLAYING) { target = candidate; break; }
                if (target == null && code != PlaybackState.STATE_STOPPED &&
                    code != PlaybackState.STATE_ERROR && code != PlaybackState.STATE_NONE) target = candidate;
            }
            if (target == null) return;
            MediaController.TransportControls controls = target.getTransportControls();
            if (controls == null) return;
            if (action == 0) controls.skipToPrevious();
            else if (action == 1) controls.skipToNext();
            else {
                PlaybackState state = target.getPlaybackState();
                boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
                if (playing) controls.pause(); else controls.play();
            }
        } catch (Exception ignored) { }
    }

    private void sendNext() {
        if (rx == null || locatorSetup || writingLocator || sending != null) return;
        while (!controls.isEmpty()) {
            byte[] request = controls.removeFirst();
            if (request[0] >= 2 && (!settingsBusy || (request[1] & 255) != (settingsToken & 255))) continue;
            if (locatorReady && writeValue(locator, request)) {
                writingOpcode = request[0];
                writingLocator = true;
                handler.postDelayed(writeTimeout, 7000);
                return;
            }
            if (request[0] >= 2) finishSettings("设置请求发送失败");
            else locateStatus = "查找请求失败";
        }
        if (pending.isEmpty()) return;
        sending = pending.peekFirst().frame;
        write();
    }

    private boolean writeValue(BluetoothGattCharacteristic characteristic, byte[] data) {
        try {
            if (Build.VERSION.SDK_INT >= 33)
                return gatt.writeCharacteristic(characteristic, data,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS;
            characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            characteristic.setValue(data);
            return gatt.writeCharacteristic(characteristic);
        } catch (SecurityException | IllegalStateException blocked) { return false; }
    }

    private void write() {
        inFlight = Math.min(CHUNK, sending.length - offset);
        byte[] fragment = Arrays.copyOfRange(sending, offset, offset + inFlight);
        if (writeValue(rx, fragment)) handler.postDelayed(writeTimeout, 7000);
        else loseConnection();
    }

    private static byte[] frame(JSONObject data) {
        return ("\u001e" + data + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String field(CharSequence source, int limit) {
        if (source == null) return "";
        StringBuilder output = new StringBuilder();
        int used = 0;
        for (int i = 0; i < source.length();) {
            int codepoint = Character.codePointAt(source, i);
            i += Character.charCount(codepoint);
            if (codepoint < 32) codepoint = ' ';
            int bytes = codepoint <= 127 ? 1 : codepoint <= 2047 ? 2 : codepoint <= 65535 ? 3 : 4;
            if (used + bytes > limit) break;
            used += bytes;
            output.appendCodePoint(codepoint);
        }
        return output.toString();
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn, RankingMap map) {
        if (sbn == null || sbn.getPackageName().equals(getPackageName())) return;
        Notification notification = sbn.getNotification();
        if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        Ranking ranking = new Ranking();
        boolean silent = map != null && map.getRanking(sbn.getKey(), ranking)
            && ranking.getImportance() <= NotificationManager.IMPORTANCE_LOW;
        handler.post(() -> enqueue(sbn, silent));
    }

    private void enqueue(StatusBarNotification sbn, boolean silent) {
        if (!authorized || paused) return;
        String source = sbn.getPackageName();
        Notification notification = sbn.getNotification();
        Bundle extras = notification.extras;
        if (extras == null) return;
        String app = source;
        try {
            PackageManager manager = getPackageManager();
            app = manager.getApplicationLabel(manager.getApplicationInfo(app, 0)).toString();
        } catch (PackageManager.NameNotFoundException ignored) {}
        observeApp(source, app);
        if (blockedApps(this).contains(source)) {
            android.util.Log.d("PassportRelay", "Notification filtered by local app preference");
            return;
        }
        CharSequence body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (body == null || body.length() == 0) body = extras.getCharSequence(Notification.EXTRA_TEXT);
        try {
            JSONObject data = new JSONObject();
            data.put("app", field(app, 88));
            data.put("title", field(extras.getCharSequence(Notification.EXTRA_TITLE), 152));
            data.put("body", field(body, 460));
            data.put("time", sbn.getPostTime() / 1000);
            data.put("silent", silent);
            if (Notification.CATEGORY_CALL.equals(notification.category)) data.put("type", 1);
            if (Notification.CATEGORY_ALARM.equals(notification.category)) data.put("type", 2);
            byte[] encoded = frame(data);
            if (encoded.length > 1100) {
                data.put("body", field(body, 320));
                encoded = frame(data);
            }
            if (pending.size() == MAX_QUEUE) pending.removeFirst();
            pending.addLast(new QueuedNotification(source, encoded));
            sendNext();
        } catch (JSONException discarded) {}
    }
}
