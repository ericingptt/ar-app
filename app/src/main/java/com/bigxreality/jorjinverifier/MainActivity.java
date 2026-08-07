package com.bigxreality.jorjinverifier;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
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
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        requestPermissionOrStart();
    }

    @Override protected void onStop() {
        foreground = false;
        stopHardware();
        super.onStop();
    }

    @Override protected void onDestroy() {
        foreground = false;
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
        resourcesStarted = true;
        final int currentGeneration = ++generation;
        errorText.setVisibility(View.GONE);
        startCamera(currentGeneration);
        startTof();
        mainHandler.removeCallbacks(frameStatusUpdater);
        mainHandler.post(frameStatusUpdater);
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

    private void showError(String message) {
        postIfActive(() -> {
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
