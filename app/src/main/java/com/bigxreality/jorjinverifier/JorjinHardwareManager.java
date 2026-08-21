package com.bigxreality.jorjinverifier;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;

import com.jorjin.jjsdk.camera.CameraManager;
import com.jorjin.jjsdk.tof.TofDevicesAttachListener;
import com.jorjin.jjsdk.tof.TofGestureEvent;
import com.jorjin.jjsdk.tof.TofGestureEventListener;
import com.jorjin.jjsdk.tof.TofFrameData;
import com.jorjin.jjsdk.tof.TofIncomingFrameListener;
import com.jorjin.jjsdk.tof.TofManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns every piece of glasses hardware: USB enumeration and grants, the JJSDK RGB camera and the
 * JJSDK ToF module.  The activity above it only renders what {@link Listener} reports.
 *
 * <h2>Why gestures never arrived before</h2>
 * The RGB camera and the ToF module are two distinct USB devices.  The app previously located the
 * UVC video device, requested a grant for that one, and then built both {@code CameraManager} and
 * {@code TofManager}.  JJSDK's shared USB monitor ({@code d.a}) does have a fallback that would
 * request the ToF grant itself, but it is effectively one-shot: it runs a single poll 300 ms after
 * a manager is constructed, re-arms only on {@code USB_DEVICE_ATTACHED}/{@code DETACHED} or on the
 * SDK's own {@code com.jorjin.jjsdk.USB_PERMISSION} broadcast, gates every request behind a shared
 * "a request is already pending" latch, and clears its per-subsystem request bit permanently once
 * a device is seen as granted.  A grant obtained through <em>our</em> dialog re-arms none of that,
 * so {@code TofManager} was frequently never handed its device, never opened the CDC port, and
 * never emitted a single event - while the camera, whose grant we did hold at the right moment,
 * worked fine.
 *
 * <h2>The fix</h2>
 * Enumerate every USB device, classify each one with the same vendor/product rule JJSDK uses, and
 * obtain grants for <em>all</em> required devices ourselves before constructing any SDK manager.
 * By the time the SDK's single poll runs, both devices already report {@code hasPermission}, so
 * its dispatch path delivers the ToF device on the first pass.  A watchdog rebuilds
 * {@code TofManager} (whose constructor re-arms that poll) if the module is present and granted
 * but has still not come up, which covers a poll that raced a late enumeration.
 */
final class JorjinHardwareManager {
    private static final String TAG = "JorjinVerifier";
    /** Our own action; the SDK's internal com.jorjin.jjsdk.USB_PERMISSION never reaches us. */
    private static final String ACTION_USB_PERMISSION =
            "com.bigxreality.jorjinverifier.USB_PERMISSION";
    private static final long WATCHDOG_INTERVAL_MS = 1000L;
    /** How long the ToF may sit granted-but-not-ready before we rebuild {@link TofManager}. */
    private static final long TOF_REBUILD_AFTER_MS = 6000L;
    private static final int MAX_TOF_REBUILDS = 3;

    /** Per-layer state so on-device testing can see exactly which layer is stuck. */
    enum LayerState { UNKNOWN, OK, WAITING, FAILED }

    /** Everything the UI renders; all callbacks land on the main thread. */
    interface Listener {
        void onCameraState(LayerState state, String detail);
        void onCameraResolution(String resolution);
        void onFrameCount(long frames);
        void onTofUsbState(LayerState detected, LayerState permission, String detail);
        void onTofManagerState(LayerState state, String detail);
        void onTofRuntimeState(LayerState state, String firmware, boolean listenerRegistered);
        void onGesture(String label, int gesture, String source, long count);
        void onError(String message);
        void onUsbInventory(String inventory);
    }

    private final Context context;
    private final Listener listener;
    private final UsbManager usbManager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicLong frameCount = new AtomicLong();
    private final AtomicLong tofFrameCount = new AtomicLong();
    private final GestureController gestureController;
    private final TofGestureRecognizer depthRecognizer;

    private CameraManager cameraManager;
    private TofManager tofManager;
    private SurfaceHolder surfaceHolder;
    private boolean started;
    private boolean receiverRegistered;
    private boolean awaitingPermission;
    private int generation;
    private long tofManagerBuiltAt;
    private int tofRebuilds;
    private boolean tofAttachedReported;

    JorjinHardwareManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
        this.gestureController = new GestureController((label, gesture, source, count) ->
                handler.post(() -> listener.onGesture(label, gesture, source.label, count)));
        // Second, independent gesture source: computed here from the depth frames, so the app
        // still reports gestures on a module whose firmware has no gesture engine.
        this.depthRecognizer = new TofGestureRecognizer((gesture, action, eventTimeNanos) ->
                gestureController.onRawEvent(action, gesture, eventTimeNanos,
                        android.os.SystemClock.elapsedRealtime(),
                        GestureController.Source.DEPTH_FRAME));
    }

    void setSurfaceHolder(SurfaceHolder holder) {
        this.surfaceHolder = holder;
    }

    // ---------------------------------------------------------------- USB inventory

    /** Snapshot of one attached USB device, used for classification and for the logcat dump. */
    private static final class UsbEntry {
        final UsbDevice device;
        final boolean isTof;
        final boolean isTofUsableBySdk;
        final boolean isTofInDfu;
        final boolean isCamera;

        UsbEntry(UsbDevice device) {
            this.device = device;
            int vid = device.getVendorId();
            int pid = device.getProductId();
            this.isTofUsableBySdk = JorjinUsbDevices.isSdkSupportedTof(vid, pid);
            this.isTof = JorjinUsbDevices.isTofCandidate(vid, pid);
            this.isTofInDfu = JorjinUsbDevices.isTofInDfuMode(vid, pid);
            this.isCamera = JorjinUsbDevices.isSdkSupportedCamera(vid, pid)
                    || hasInterfaceClass(device, JorjinUsbDevices.CLASS_VIDEO);
        }
    }

    private static boolean hasInterfaceClass(UsbDevice device, int interfaceClass) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            if (device.getInterface(i).getInterfaceClass() == interfaceClass) return true;
        }
        return false;
    }

    private List<UsbEntry> enumerate() {
        List<UsbEntry> entries = new ArrayList<>();
        if (usbManager == null) return entries;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            entries.add(new UsbEntry(device));
        }
        return entries;
    }

    /**
     * Dumps every enumerated device, its interfaces and its grant state.  This is the single most
     * useful artefact when a pair of glasses behaves differently from the ones we tested on, so it
     * is written unconditionally on every start and mirrored into the on-screen panel.
     */
    private String describeInventory(List<UsbEntry> entries) {
        StringBuilder text = new StringBuilder();
        text.append("USB 裝置枚舉：共 ").append(entries.size()).append(" 個");
        for (UsbEntry entry : entries) {
            UsbDevice device = entry.device;
            boolean granted = usbManager != null && usbManager.hasPermission(device);
            text.append('\n')
                    .append(JorjinUsbDevices.formatIds(device.getVendorId(), device.getProductId()))
                    .append(" deviceId=").append(device.getDeviceId())
                    .append(" deviceClass=").append(device.getDeviceClass())
                    .append('/').append(device.getDeviceSubclass())
                    .append('/').append(device.getDeviceProtocol())
                    .append(" interfaces=").append(device.getInterfaceCount())
                    .append(" permission=").append(granted ? "GRANTED" : "MISSING")
                    .append(" role=").append(entry.isTof
                            ? (entry.isTofUsableBySdk ? "TOF" : "TOF(SDK 未支援此 PID)")
                            : entry.isTofInDfu ? "TOF-DFU" : entry.isCamera ? "RGB" : "OTHER");
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                UsbInterface usbInterface = device.getInterface(i);
                text.append("\n  interface[").append(i).append("] class=")
                        .append(usbInterface.getInterfaceClass())
                        .append('/').append(usbInterface.getInterfaceSubclass())
                        .append('/').append(usbInterface.getInterfaceProtocol())
                        .append(" endpoints=").append(usbInterface.getEndpointCount());
            }
        }
        String inventory = text.toString();
        for (String line : inventory.split("\n")) Log.i(TAG, line);
        return inventory;
    }

    // ---------------------------------------------------------------- lifecycle

    void start() {
        if (started) return;
        registerReceiver();
        List<UsbEntry> entries = enumerate();
        String inventory = describeInventory(entries);
        listener.onUsbInventory(inventory);

        UsbEntry camera = firstCamera(entries);
        UsbEntry tof = firstTof(entries);
        reportTofUsb(entries, tof);

        if (camera == null && tof == null) {
            listener.onCameraState(LayerState.FAILED, "未偵測到眼鏡");
            listener.onError("未偵測到任何眼鏡 USB 裝置；請確認 USB-C 線具資料傳輸能力、"
                    + "眼鏡已接妥且供電充足。");
            return;
        }

        // Both grants first, then the SDK.  Requesting them after constructing a manager is what
        // used to leave the ToF permanently un-dispatched.
        if (requestMissingPermission(camera) || requestMissingPermission(tof)) return;

        started = true;
        generation++;
        tofRebuilds = 0;
        frameCount.set(0);
        tofFrameCount.set(0);
        gestureController.reset();
        depthRecognizer.reset();
        startCamera(camera, generation);
        startTof(tof);
        handler.removeCallbacks(watchdog);
        handler.post(watchdog);
    }

    void stop() {
        started = false;
        generation++;
        awaitingPermission = false;
        handler.removeCallbacks(watchdog);
        unregisterReceiver();
        releaseCamera();
        releaseTof();
    }

    void restart() {
        stop();
        listener.onGesture("尚未偵測", -1, "—", 0);
        start();
    }

    private UsbEntry firstCamera(List<UsbEntry> entries) {
        for (UsbEntry entry : entries) if (entry.isCamera && !entry.isTof) return entry;
        return null;
    }

    private UsbEntry firstTof(List<UsbEntry> entries) {
        for (UsbEntry entry : entries) if (entry.isTof) return entry;
        // Some units enumerate the ToF with ids neither table knows.  Fall back to any pure
        // CDC-ACM node that is not the video device rather than reporting "no ToF" outright.
        for (UsbEntry entry : entries) {
            if (entry.isCamera || entry.isTofInDfu) continue;
            if (hasInterfaceClass(entry.device, JorjinUsbDevices.CLASS_CDC_CONTROL)
                    && hasInterfaceClass(entry.device, JorjinUsbDevices.CLASS_CDC_DATA)) {
                return entry;
            }
        }
        return null;
    }

    private void reportTofUsb(List<UsbEntry> entries, UsbEntry tof) {
        if (tof == null) {
            for (UsbEntry entry : entries) {
                if (entry.isTofInDfu) {
                    listener.onTofUsbState(LayerState.FAILED, LayerState.UNKNOWN,
                            "ToF 停在 DFU 韌體更新模式，無法輸出手勢");
                    return;
                }
            }
            listener.onTofUsbState(LayerState.FAILED, LayerState.UNKNOWN, "未偵測到 ToF USB 裝置");
            return;
        }
        boolean granted = usbManager != null && usbManager.hasPermission(tof.device);
        String detail = JorjinUsbDevices.formatIds(
                tof.device.getVendorId(), tof.device.getProductId());
        if (!tof.isTofUsableBySdk) detail += "（JJSDK 1.3.3 未列此 PID）";
        listener.onTofUsbState(LayerState.OK, granted ? LayerState.OK : LayerState.WAITING, detail);
    }

    // ---------------------------------------------------------------- USB permission

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            awaitingPermission = false;
            // An immutable PendingIntent can arrive without the system's fill-in extras, so
            // re-run the whole start path rather than trusting EXTRA_DEVICE.
            Log.i(TAG, "USB 授權結果回傳，重新評估硬體啟動條件");
            start();
        }
    };

    private void registerReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(permissionReceiver, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterReceiver() {
        if (!receiverRegistered) return;
        receiverRegistered = false;
        try { context.unregisterReceiver(permissionReceiver); }
        catch (Throwable error) { Log.w(TAG, "解除 USB 廣播接收器失敗", error); }
    }

    /** @return true when a dialog was raised and {@link #start()} must wait for the result. */
    private boolean requestMissingPermission(UsbEntry entry) {
        if (entry == null || usbManager == null) return false;
        if (usbManager.hasPermission(entry.device)) return false;
        if (awaitingPermission) return true;
        awaitingPermission = true;
        String ids = JorjinUsbDevices.formatIds(
                entry.device.getVendorId(), entry.device.getProductId());
        Log.i(TAG, "請求 USB 授權：" + ids + (entry.isTof ? " (ToF)" : " (RGB)"));
        if (entry.isTof) {
            listener.onTofUsbState(LayerState.OK, LayerState.WAITING, "等待使用者授權 " + ids);
        } else {
            listener.onCameraState(LayerState.WAITING, "等待 USB 授權 " + ids);
        }
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName());
        usbManager.requestPermission(entry.device, PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_IMMUTABLE));
        return true;
    }

    // ---------------------------------------------------------------- camera

    private void startCamera(UsbEntry camera, final int currentGeneration) {
        if (camera == null) {
            listener.onCameraState(LayerState.FAILED, "未偵測到 RGB 相機裝置");
            return;
        }
        try {
            listener.onCameraState(LayerState.WAITING, "啟動中");
            CameraManager manager = new CameraManager(context);
            cameraManager = manager;
            String[] resolutions = manager.getResolutionList();
            if (resolutions == null || resolutions.length == 0) {
                throw new IllegalStateException("SDK 未回傳任何可用解析度");
            }
            manager.setResolutionIndex(0);
            listener.onCameraResolution(resolutions[0]);
            if (surfaceHolder != null) manager.addSurfaceHolder(surfaceHolder);
            manager.setCameraFrameListener((buffer, width, height, format) -> {
                if (started && generation == currentGeneration) frameCount.incrementAndGet();
            });
            manager.startCamera(CameraManager.COLOR_FORMAT_RGBA);
            listener.onCameraState(LayerState.OK, "已啟動");
        } catch (Throwable error) {
            Log.e(TAG, "啟動 JJSDK RGB 鏡頭失敗", error);
            listener.onCameraState(LayerState.FAILED, describe(error));
            listener.onError("啟動 JJSDK RGB 鏡頭失敗：" + describe(error));
            releaseCamera();
        }
    }

    private void releaseCamera() {
        CameraManager manager = cameraManager;
        cameraManager = null;
        if (manager == null) return;
        try { manager.setCameraFrameListener(null); }
        catch (Throwable error) { Log.w(TAG, "解除相機 listener 失敗", error); }
        try { manager.stopCamera(); }
        catch (Throwable error) { Log.w(TAG, "停止相機失敗", error); }
        try { manager.release(); }
        catch (Throwable error) { Log.w(TAG, "釋放相機失敗", error); }
    }

    // ---------------------------------------------------------------- ToF

    /**
     * Counts depth frames arriving over the ToF CDC link.
     *
     * <p>This is what separates "our pipeline is broken" from "the module is not reporting
     * gestures". JJSDK reads the gesture bitfield out of bytes 586-590 of the very same frame
     * it hands to this listener, so a rising frame count with a flat gesture count proves the
     * link, the SDK parser and our listener are all healthy and only the gesture bits are dead
     * - which is what a ToF firmware older than v1.2.2 does.
     */
    @SuppressWarnings("deprecation")
    private final TofIncomingFrameListener frameListener = new TofIncomingFrameListener() {
        @Override public void onTofIncomingFrame(java.util.ArrayList frame) { }

        @Override public void onTofIncomingFrame(TofFrameData frame) {
            tofFrameCount.incrementAndGet();
            if (frame != null) {
                depthRecognizer.onFrame(frame.medianRange,
                        android.os.SystemClock.elapsedRealtime());
            }
        }
    };

    private final TofGestureEventListener gestureListener = new TofGestureEventListener() {
        @Override public void onTofGestureEvent(TofGestureEvent event) {
            gestureController.onRawEvent(event, android.os.SystemClock.elapsedRealtime());
        }
    };

    private final TofDevicesAttachListener attachListener = new TofDevicesAttachListener() {
        @Override public void onTofDevicesAttached(boolean attached) {
            Log.i(TAG, "onTofDevicesAttached=" + attached);
            tofAttachedReported = attached;
            if (!attached) {
                handler.post(() -> listener.onTofRuntimeState(LayerState.FAILED, null, true));
            }
        }
    };

    private void startTof(UsbEntry tof) {
        if (tof == null) {
            listener.onTofManagerState(LayerState.FAILED, "沒有可用的 ToF USB 裝置");
            return;
        }
        buildTofManager();
    }

    /**
     * Constructing {@link TofManager} is also what re-arms JJSDK's device-discovery poll, so this
     * doubles as the recovery action when the module is present and granted but never came up.
     * Listeners are registered before {@code open()} because the poll fires 300 ms after the
     * constructor returns and the attach callback must not find a null listener.
     */
    private void buildTofManager() {
        releaseTof();
        try {
            TofManager manager = new TofManager(context);
            tofManager = manager;
            manager.setTofDevicesAttachListener(attachListener);
            manager.setTofGestureListener(gestureListener);
            manager.setTofFrameListener(frameListener);
            // isDeviceSupportToF() is inverted in JJSDK 1.3.3 (it returns true while no ToF has
            // been found) and discovery is asynchronous, so it must not gate anything here.
            manager.open();
            tofManagerBuiltAt = android.os.SystemClock.elapsedRealtime();
            listener.onTofManagerState(LayerState.OK, tofRebuilds == 0
                    ? "已建立並 open()" : "已重建並 open()（第 " + tofRebuilds + " 次）");
            listener.onTofRuntimeState(LayerState.WAITING, null, true);
        } catch (Throwable error) {
            Log.e(TAG, "啟動 JJSDK ToF 失敗", error);
            listener.onTofManagerState(LayerState.FAILED, describe(error));
            listener.onError("啟動 JJSDK ToF 失敗：" + describe(error));
            releaseTof();
        }
    }

    private void releaseTof() {
        TofManager manager = tofManager;
        tofManager = null;
        tofAttachedReported = false;
        if (manager == null) return;
        try {
            manager.setTofGestureListener(null);
            manager.setTofDevicesAttachListener(null);
            manager.setTofFrameListener(null);
        } catch (Throwable error) { Log.w(TAG, "解除 ToF listener 失敗", error); }
        try { manager.close(); }
        catch (Throwable error) { Log.w(TAG, "關閉 ToF 失敗", error); }
        try { manager.release(); }
        catch (Throwable error) { Log.w(TAG, "釋放 ToF 失敗", error); }
    }

    // ---------------------------------------------------------------- watchdog

    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            if (!started) return;
            listener.onFrameCount(frameCount.get());
            pollTof();
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS);
        }
    };

    private void pollTof() {
        TofManager manager = tofManager;
        if (manager == null) return;
        boolean ready;
        String firmware;
        try {
            ready = manager.getTofState();
            firmware = manager.getTofFwVersion();
        } catch (Throwable error) {
            Log.w(TAG, "讀取 ToF 狀態失敗", error);
            return;
        }
        listener.onTofRuntimeState(ready ? LayerState.OK : LayerState.WAITING, firmware, true);
        if (ready) return;
        long waited = android.os.SystemClock.elapsedRealtime() - tofManagerBuiltAt;
        if (waited <= TOF_REBUILD_AFTER_MS || tofRebuilds >= MAX_TOF_REBUILDS) return;
        if (!tofAttachedReported && !hasGrantedTof()) return;
        tofRebuilds++;
        Log.w(TAG, "ToF 已授權但未就緒，重建 TofManager 以重新觸發 SDK 裝置探索（第 "
                + tofRebuilds + " 次）");
        buildTofManager();
    }

    private boolean hasGrantedTof() {
        UsbEntry tof = firstTof(enumerate());
        return tof != null && usbManager != null && usbManager.hasPermission(tof.device);
    }

    long gestureCount() {
        return gestureController.gestureCount();
    }

    long tofFrameCount() {
        return tofFrameCount.get();
    }

    /** Latest depth frame for the on-screen grid; returns the number of lit zones. */
    int copyDepthSnapshot(float[] out) {
        return depthRecognizer.copySnapshot(out);
    }

    float handRow() { return depthRecognizer.snapshotRow(); }

    float handColumn() { return depthRecognizer.snapshotColumn(); }

    float handRangeMm() { return depthRecognizer.snapshotRangeMm(); }

    /** 0 to 1 while a hold-to-select is building. */
    float dwellProgress() { return depthRecognizer.dwellProgress(); }

    /** Runtime axis correction; which way the module is mounted is only knowable on a unit. */
    void setColumnIncreasesRight(boolean value) {
        depthRecognizer.setColumnIncreasesRight(value);
    }

    void setRowIncreasesDown(boolean value) {
        depthRecognizer.setRowIncreasesDown(value);
    }

    boolean isColumnIncreasesRight() { return depthRecognizer.isColumnIncreasesRight(); }

    boolean isRowIncreasesDown() { return depthRecognizer.isRowIncreasesDown(); }

    /**
     * Reproduces JJSDK's own firmware gate so the panel can say whether the module will emit
     * gestures at all. The SDK folds the version string into an integer - each dot-separated
     * field is {@code previous * 16 + field} - and only arms the gesture path at 290, which is
     * exactly "1.2.2". A field that is not a number leaves the running value in place, so a
     * build suffix such as "1.1.2.jg7hc" inflates the total past the threshold even though the
     * firmware is older than 1.2.2; the gate then passes while the frames still carry no
     * gesture bits. Report the numeric part separately so that case is visible instead of
     * looking like a bug in this app.
     */
    static boolean firmwareSupportsGestures(String firmware) {
        if (firmware == null) return false;
        // Mirrors the SDK exactly, including its single reused variable: when a field does not
        // parse, the running total is folded into itself rather than the last parsed number.
        // Both are plain Java ints there, so overflow behaves identically here.
        int value = 0;
        for (String part : firmware.trim().split("\\.")) {
            int previous = value;
            try { value = Integer.parseInt(part); } catch (NumberFormatException ignored) { }
            value = previous * 16 + value;
        }
        return value >= 290;
    }

    /** True only for the numeric prefix, ignoring any build suffix - the honest answer. */
    static boolean firmwareNumericallyAtLeast122(String firmware) {
        if (firmware == null) return false;
        String[] parts = firmware.trim().split("\\.");
        int[] v = new int[]{0, 0, 0};
        for (int i = 0; i < 3; i++) {
            if (i >= parts.length) break;
            try { v[i] = Integer.parseInt(parts[i]); } catch (NumberFormatException ignored) { break; }
        }
        return v[0] * 10000 + v[1] * 100 + v[2] >= 1 * 10000 + 2 * 100 + 2;
    }

    long rawGestureEventCount() {
        return gestureController.rawEventCount();
    }

    static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    static String label(LayerState state, String okText, String failText, String waitText) {
        switch (state) {
            case OK: return okText;
            case FAILED: return failText;
            case WAITING: return waitText;
            default: return "—";
        }
    }

    static String formatCount(long value) {
        return String.format(Locale.TAIWAN, "%,d", value);
    }
}
