package com.bigxreality.jorjinverifier;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;

import com.jorjin.jjsdk.camera.CameraManager;
import com.jorjin.jjsdk.tof.TofDevicesAttachListener;
import com.jorjin.jjsdk.tof.TofGestureEvent;
import com.jorjin.jjsdk.tof.TofFrameData;
import com.jorjin.jjsdk.tof.TofGestureEventListener;
import com.jorjin.jjsdk.tof.TofIncomingFrameListener;
import com.jorjin.jjsdk.tof.TofManager;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Lifecycle-safe JJSDK camera and ToF hardware verification screen. */
public final class MainActivity extends Activity
        implements TofGestureEventListener, TofDevicesAttachListener {
    private static final String TAG = "JorjinVerifier";
    private static final int CAMERA_PERMISSION_REQUEST = 1001;
    private static final long STATUS_INTERVAL_MS = 500L;
    private static final long GESTURE_DEBOUNCE_MS = 300L;
    /** Own action; the SDK's internal com.jorjin.jjsdk.USB_PERMISSION flow never reaches us. */
    private static final String ACTION_USB_PERMISSION =
            "com.bigxreality.jorjinverifier.USB_PERMISSION";
    private static final int GESTURE_HISTORY_SIZE = 6;
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm:ss", Locale.TAIWAN);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong frameCount = new AtomicLong();
    private final Deque<String> recentGestures = new ArrayDeque<>();
    private final AtomicLong tofFrames = new AtomicLong();
    /** Devices the user actively refused, so a denial cannot loop the permission dialog. */
    private final Set<String> refusedDevices = new HashSet<>();
    /** Written from the ToF callback thread, read on the main thread by the status ticker. */
    private volatile float nearestRange = -1f;
    private SurfaceView cameraSurface;
    private TextView cameraStatus;
    private TextView tofStatus;
    private TextView resolutionStatus;
    private TextView frameStatus;
    private TextView gestureText;
    private TextView gestureHistory;
    private TextView tofDataStatus;
    private TextView usbStatus;
    private TextView errorText;
    private CameraManager cameraManager;
    private TofManager tofManager;
    private boolean foreground;
    private boolean resourcesStarted;
    private int generation;
    private long lastGestureTime;
    private int lastGesture = Integer.MIN_VALUE;
    private UsbManager usbManager;
    private boolean usbReceiverRegistered;
    private boolean awaitingUsbPermission;

    /**
     * The JJSDK opens the glasses over libusb/UVC through usbfs, so Android's CAMERA runtime
     * permission alone grants it nothing: without a USB device grant for this process,
     * UsbManager.openDevice returns null and not a single frame is ever delivered.
     */
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            awaitingUsbPermission = false;
            // An immutable PendingIntent may arrive without the system's fill-in extras, so
            // re-resolve the device and ask the manager rather than trusting EXTRA_DEVICE.
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null) device = findGlassesDevice();
            if (device != null && !usbManager.hasPermission(device)) {
                // Remember the refusal, otherwise startHardware would offer the same device
                // again and again and the dialog would loop.
                refusedDevices.add(device.getDeviceName());
                showError("有 USB 裝置未取得授權，ToF 可能無法運作；按「重新連接」可再要求一次。");
            }
            describeUsbDevices();
            startHardware();
        }
    };

    private final Runnable frameStatusUpdater = new Runnable() {
        @Override public void run() {
            if (!foreground || !resourcesStarted) return;
            frameStatus.setText(String.format(Locale.TAIWAN, "影像幀：%,d", frameCount.get()));
            long tof = tofFrames.get();
            tofDataStatus.setText(tof == 0
                    ? "ToF 影格：0（感測器沒有送出任何資料）"
                    : String.format(Locale.TAIWAN, "ToF 影格：%,d　最近距離：%s", tof,
                            nearestRange < 0f ? "—" : String.format(Locale.TAIWAN, "%.3f", nearestRange)));
            mainHandler.postDelayed(this, STATUS_INTERVAL_MS);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        cameraSurface = findViewById(R.id.cameraSurface);
        cameraStatus = findViewById(R.id.cameraStatus);
        tofStatus = findViewById(R.id.tofStatus);
        resolutionStatus = findViewById(R.id.resolutionStatus);
        frameStatus = findViewById(R.id.frameStatus);
        gestureText = findViewById(R.id.gestureText);
        gestureHistory = findViewById(R.id.gestureHistory);
        tofDataStatus = findViewById(R.id.tofDataStatus);
        usbStatus = findViewById(R.id.usbStatus);
        errorText = findViewById(R.id.errorText);
        findViewById(R.id.retryButton).setOnClickListener(view -> restartHardware());
        findViewById(R.id.cibarButton).setOnClickListener(
                view -> startActivity(new Intent(this, CibarActivity.class)));
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        installCrashReporter();
    }

    /**
     * The SDK drives capture and rendering on threads it owns (startCamera spawns one, the
     * renderer adds HandlerThreads), so an exception there tears down the process before any
     * try/catch around startCamera can see it - the failure vanishes with nothing on screen.
     * Surface it instead, and keep the process alive when the dying thread is not the main one
     * so the message can actually be read on the device.
     */
    private void installCrashReporter() {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            Log.e(TAG, "未捕捉例外，執行緒：" + thread.getName(), error);
            final String detail = describeCrash(thread, error);
            persistCrash(detail);
            if (thread != Looper.getMainLooper().getThread()) {
                mainHandler.post(() -> {
                    cameraStatus.setText("RGB 鏡頭：SDK 執行緒發生例外");
                    errorText.setText(detail);
                    errorText.setVisibility(View.VISIBLE);
                });
                return;
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    private static String describeCrash(Thread thread, Throwable error) {
        StringBuilder text = new StringBuilder()
                .append("執行緒 ").append(thread.getName()).append('\n')
                .append(error.getClass().getName());
        if (error.getMessage() != null) text.append(": ").append(error.getMessage());
        StackTraceElement[] frames = error.getStackTrace();
        for (int i = 0; i < frames.length && i < 6; i++) {
            text.append("\n  at ").append(frames[i]);
        }
        Throwable cause = error.getCause();
        if (cause != null) {
            text.append("\n由 ").append(cause.getClass().getName());
            if (cause.getMessage() != null) text.append(": ").append(cause.getMessage());
        }
        return text.toString();
    }

    /** Also written to disk so the trace survives a process death: adb pull, or the Files app. */
    private void persistCrash(String detail) {
        java.io.File target = new java.io.File(getExternalFilesDir(null), "crash.txt");
        try (java.io.Writer writer = new java.io.FileWriter(target, true)) {
            writer.write(detail);
            writer.write("\n\n");
        } catch (Throwable ignored) {
            Log.w(TAG, "無法寫入 crash.txt");
        }
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        registerUsbReceiver();
        requestPermissionOrStart();
    }

    @Override protected void onStop() {
        foreground = false;
        awaitingUsbPermission = false;
        unregisterUsbReceiver();
        stopHardware();
        super.onStop();
    }

    private void registerUsbReceiver() {
        if (usbReceiverRegistered) return;
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbPermissionReceiver, filter);
        }
        usbReceiverRegistered = true;
    }

    private void unregisterUsbReceiver() {
        if (!usbReceiverRegistered) return;
        usbReceiverRegistered = false;
        try { unregisterReceiver(usbPermissionReceiver); }
        catch (Throwable error) { Log.w(TAG, "解除 USB 廣播接收器失敗", error); }
    }

    @Override protected void onDestroy() {
        foreground = false;
        unregisterUsbReceiver();
        stopHardware();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void requestPermissionOrStart() {
        if (!foreground) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startHardware();
        } else {
            cameraStatus.setText("RGB 鏡頭：等待相機權限");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST || !foreground) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startHardware();
        } else {
            cameraStatus.setText("RGB 鏡頭：啟動失敗");
            showError("未取得相機權限；請在系統設定允許後按「重新連接」。");
        }
    }

    private void restartHardware() {
        stopHardware();
        awaitingUsbPermission = false;
        refusedDevices.clear();
        frameCount.set(0);
        tofFrames.set(0);
        nearestRange = -1f;
        lastGesture = Integer.MIN_VALUE;
        lastGestureTime = 0;
        errorText.setVisibility(View.GONE);
        cameraStatus.setText("RGB 鏡頭：等待連接");
        tofStatus.setText("ToF：等待偵測");
        resolutionStatus.setText("解析度：—");
        frameStatus.setText("影像幀：0");
        gestureText.setText("尚未偵測");
        requestPermissionOrStart();
    }

    private void startHardware() {
        if (!foreground || resourcesStarted) return;
        describeUsbDevices();
        if (usbManager.getDeviceList().isEmpty()) {
            cameraStatus.setText("RGB 鏡頭：未偵測到眼鏡");
            showError("未偵測到 USB 裝置；請確認 USB-C 線具資料傳輸能力、眼鏡已接妥且供電充足。");
            return;
        }
        // Every attached device needs a grant, not just the UVC one. TofManager opens its own
        // UsbDevice by vendor/product id, and on glasses that enumerate the sensor separately
        // from the camera a video-only grant leaves openDevice returning null for ToF - the
        // camera streams while the sensor stays silent.
        UsbDevice pending = nextDeviceNeedingPermission();
        if (pending != null) {
            requestUsbPermission(pending);
            return;
        }
        resourcesStarted = true;
        final int currentGeneration = ++generation;
        errorText.setVisibility(View.GONE);
        startCamera(currentGeneration);
        startTof();
        mainHandler.removeCallbacks(frameStatusUpdater);
        mainHandler.post(frameStatusUpdater);
    }

    private UsbDevice findGlassesDevice() {
        return GlassesUsb.findDevice(usbManager);
    }

    private UsbDevice nextDeviceNeedingPermission() {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (!usbManager.hasPermission(device) && !refusedDevices.contains(device.getDeviceName())) {
                return device;
            }
        }
        return null;
    }

    /** Shows every attached device with its grant state - the fastest way to see a missing one. */
    private void describeUsbDevices() {
        StringBuilder text = new StringBuilder("USB：");
        java.util.Collection<UsbDevice> devices = usbManager.getDeviceList().values();
        if (devices.isEmpty()) {
            usbStatus.setText("USB：未偵測到裝置");
            return;
        }
        text.append(devices.size()).append(" 裝置");
        for (UsbDevice device : devices) {
            text.append(String.format(Locale.TAIWAN, "\n  %04X/%04X %s %s",
                    device.getVendorId(), device.getProductId(),
                    usbManager.hasPermission(device) ? "已授權" : "未授權",
                    device.getProductName() == null ? "" : device.getProductName()));
        }
        usbStatus.setText(text.toString());
    }

    private void requestUsbPermission(UsbDevice device) {
        if (awaitingUsbPermission) return;
        awaitingUsbPermission = true;
        cameraStatus.setText("RGB 鏡頭：等待眼鏡 USB 授權");
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        usbManager.requestPermission(device, PendingIntent.getBroadcast(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE));
    }

    private void startCamera(final int currentGeneration) {
        try {
            cameraStatus.setText("RGB 鏡頭：啟動中");
            CameraManager manager = new CameraManager(getApplicationContext());
            cameraManager = manager;
            String[] resolutions = manager.getResolutionList();
            if (resolutions == null || resolutions.length == 0) {
                throw new IllegalStateException("SDK 未回傳任何可用解析度");
            }
            manager.setResolutionIndex(0);
            resolutionStatus.setText("解析度：" + resolutions[0]);
            manager.addSurfaceHolder(cameraSurface.getHolder());
            manager.setCameraFrameListener((buffer, width, height, format) -> {
                if (foreground && resourcesStarted && generation == currentGeneration) {
                    frameCount.incrementAndGet();
                }
            });
            manager.startCamera(CameraManager.COLOR_FORMAT_RGBA);
            cameraStatus.setText("RGB 鏡頭：已啟動");
        } catch (Throwable error) {
            reportFailure("啟動 JJSDK RGB 鏡頭", error);
            cameraStatus.setText("RGB 鏡頭：啟動失敗");
            releaseCamera();
        }
    }

    private void startTof() {
        try {
            tofStatus.setText("ToF：等待偵測");
            TofManager manager = new TofManager(getApplicationContext());
            tofManager = manager;
            manager.setTofDevicesAttachListener(this);
            manager.setTofGestureListener(this);
            // Raw telemetry separates the two failures that look identical on screen: a sensor
            // that never opened produces no frames at all, whereas a sensor that streams while
            // recognising nothing points at the gesture firmware instead of at the connection.
            manager.setTofFrameListener(new TofIncomingFrameListener() {
                @Override public void onTofIncomingFrame(java.util.ArrayList frames) {
                    tofFrames.incrementAndGet();
                }

                @Override public void onTofIncomingFrame(TofFrameData frame) {
                    tofFrames.incrementAndGet();
                    if (frame != null && frame.medianRange != null && frame.medianRange.length > 0) {
                        float nearest = Float.MAX_VALUE;
                        for (float range : frame.medianRange) {
                            if (range > 0f && range < nearest) nearest = range;
                        }
                        if (nearest != Float.MAX_VALUE) nearestRange = nearest;
                    }
                }
            });
            // isDeviceSupportToF is NOT a support check: its bytecode returns true exactly when
            // the SDK's internal UsbDevice field is null. Gating on it made the UI announce
            // "connected" on a condition that literally means "no device reference", so report
            // the raw values and let the frame counter be the evidence instead.
            manager.open();
            String firmware = manager.getTofFwVersion();
            boolean hasFirmware = firmware != null && !firmware.trim().isEmpty();
            tofStatus.setText(String.format(Locale.TAIWAN,
                    "ToF：韌體 %s（state=%b, deviceNull=%b）",
                    hasFirmware ? firmware : "無回應",
                    manager.getTofState(), manager.isDeviceSupportToF()));
            if (!hasFirmware) {
                showError("ToF 序列埠沒有回應韌體版本，感測器很可能未被 SDK 找到。"
                        + "請試著先開啟本 APP 再插上眼鏡，並確認此型號配有 ToF。");
            }
        } catch (Throwable error) {
            reportFailure("啟動 JJSDK ToF", error);
            tofStatus.setText("ToF：不支援或啟動失敗");
            releaseTof();
        }
    }

    @Override public void onTofDevicesAttached(boolean attached) {
        postIfActive(() -> {
            tofStatus.setText(attached ? "ToF：已連接，等待手勢" : "ToF：已中斷");
            if (!attached) showError("ToF 已中斷；請檢查 USB-C 與眼鏡供電後重新連接。");
        });
    }

    @Override public void onTofGestureEvent(TofGestureEvent event) {
        if (event == null || event.getAction() != TofGestureEvent.ACTION_RECEIVED) return;
        long now = SystemClock.elapsedRealtime();
        int gesture = event.getGesture();
        synchronized (this) {
            if (gesture == lastGesture && now - lastGestureTime < GESTURE_DEBOUNCE_MS) return;
            lastGesture = gesture;
            lastGestureTime = now;
        }
        String label = GestureLabels.from(gesture);
        final int reported = gesture;
        final long eventTime = System.currentTimeMillis();
        postIfActive(() -> {
            gestureText.setText(label);
            tofStatus.setText("ToF：手勢辨識正常");
            // Formatted here rather than on the SDK's callback thread: SimpleDateFormat is not
            // thread safe, and postIfActive already confines this to the main thread.
            recentGestures.addFirst(String.format(Locale.TAIWAN, "%s  %s (%d)",
                    TIME_FORMAT.format(new Date(eventTime)), label, reported));
            while (recentGestures.size() > GESTURE_HISTORY_SIZE) recentGestures.removeLast();
            gestureHistory.setText(TextUtils.join("\n", recentGestures));
        });
    }

    private void postIfActive(Runnable action) {
        mainHandler.post(() -> {
            if (foreground && resourcesStarted && !isFinishing() && !isDestroyed()) action.run();
        });
    }

    /**
     * Not gated on resourcesStarted: the failures worth reporting (no device, USB permission
     * refused) all happen before the hardware ever starts, and postIfActive would swallow them.
     */
    private void showError(String message) {
        mainHandler.post(() -> {
            if (!foreground || isFinishing() || isDestroyed()) return;
            errorText.setText(message);
            errorText.setVisibility(View.VISIBLE);
        });
    }

    private void reportFailure(String operation, Throwable error) {
        Log.e(TAG, operation + "失敗", error);
        String message = error.getMessage();
        showError(operation + "失敗：" + (message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message));
    }

    private void stopHardware() {
        resourcesStarted = false;
        generation++;
        mainHandler.removeCallbacksAndMessages(null);
        releaseCamera();
        releaseTof();
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

    private void releaseTof() {
        TofManager manager = tofManager;
        tofManager = null;
        if (manager == null) return;
        try {
            manager.setTofGestureListener(null);
            manager.setTofDevicesAttachListener(null);
        } catch (Throwable error) { Log.w(TAG, "解除 ToF listener 失敗", error); }
        try { manager.close(); }
        catch (Throwable error) { Log.w(TAG, "關閉 ToF 失敗", error); }
        try { manager.release(); }
        catch (Throwable error) { Log.w(TAG, "釋放 ToF 失敗", error); }
    }
}
