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
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;

import com.jorjin.jjsdk.camera.CameraManager;
import com.jorjin.jjsdk.tof.TofDevicesAttachListener;
import com.jorjin.jjsdk.tof.TofGestureEvent;
import com.jorjin.jjsdk.tof.TofGestureEventListener;
import com.jorjin.jjsdk.tof.TofManager;

import java.util.Locale;
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
    /** USB video class (UVC); the glasses' RGB camera enumerates under it. */
    private static final int UVC_INTERFACE_CLASS = 14;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong frameCount = new AtomicLong();
    private SurfaceView cameraSurface;
    private TextView cameraStatus;
    private TextView tofStatus;
    private TextView resolutionStatus;
    private TextView frameStatus;
    private TextView gestureText;
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
            if (device != null && usbManager.hasPermission(device)) {
                startHardware();
            } else {
                cameraStatus.setText("RGB 鏡頭：未取得眼鏡 USB 授權");
                showError("未取得眼鏡的 USB 授權；請重新接上並允許授權視窗後按「重新連接」。");
            }
        }
    };

    private final Runnable frameStatusUpdater = new Runnable() {
        @Override public void run() {
            if (!foreground || !resourcesStarted) return;
            frameStatus.setText(String.format(Locale.TAIWAN, "影像幀：%,d", frameCount.get()));
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
        errorText = findViewById(R.id.errorText);
        findViewById(R.id.retryButton).setOnClickListener(view -> restartHardware());
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
        frameCount.set(0);
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
        UsbDevice glasses = findGlassesDevice();
        if (glasses == null) {
            cameraStatus.setText("RGB 鏡頭：未偵測到眼鏡");
            showError("未偵測到 USB 裝置；請確認 USB-C 線具資料傳輸能力、眼鏡已接妥且供電充足。");
            return;
        }
        if (!usbManager.hasPermission(glasses)) {
            requestUsbPermission(glasses);
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

    /**
     * Prefers a device exposing a UVC video interface; the glasses enumerate as one. Falls back
     * to the first attached device so a single wrong guess cannot silently block verification.
     */
    private UsbDevice findGlassesDevice() {
        if (usbManager == null) return null;
        UsbDevice fallback = null;
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            if (hasVideoInterface(device)) return device;
            if (fallback == null) fallback = device;
        }
        return fallback;
    }

    private static boolean hasVideoInterface(UsbDevice device) {
        for (int index = 0; index < device.getInterfaceCount(); index++) {
            if (device.getInterface(index).getInterfaceClass() == UVC_INTERFACE_CLASS) return true;
        }
        return false;
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
            if (!manager.isDeviceSupportToF()) {
                tofStatus.setText("ToF：不支援");
                showError("未偵測到 ToF；請確認眼鏡型號、USB 授權、連線與供電。");
                releaseTof();
                return;
            }
            manager.open();
            String firmware = manager.getTofFwVersion();
            tofStatus.setText("ToF：已連接（韌體 "
                    + (firmware == null || firmware.trim().isEmpty() ? "無法確認，手勢需 v1.2.2+" : firmware)
                    + "）");
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
        postIfActive(() -> {
            gestureText.setText(label);
            tofStatus.setText("ToF：手勢辨識正常");
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
