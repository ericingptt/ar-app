package com.bigxreality.jorjinverifier;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.jorjin.jjsdk.camera.CameraManager;
import com.jorjin.jjsdk.tof.TofDevicesAttachListener;
import com.jorjin.jjsdk.tof.TofGestureEvent;
import com.jorjin.jjsdk.tof.TofGestureEventListener;
import com.jorjin.jjsdk.tof.TofManager;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public class MainActivity extends Activity
        implements TofGestureEventListener, TofDevicesAttachListener {

    private static final int CAMERA_PERMISSION_REQUEST = 1001;
    private static final long STATUS_INTERVAL_MS = 500L;
    private static final long GESTURE_DEBOUNCE_MS = 300L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong frameCount = new AtomicLong(0L);

    private SurfaceView cameraSurface;
    private TextView cameraStatus;
    private TextView tofStatus;
    private TextView resolutionStatus;
    private TextView frameStatus;
    private TextView gestureText;
    private TextView errorText;

    private CameraManager cameraManager;
    private TofManager tofManager;
    private boolean resourcesStarted;
    private long lastGestureTime;
    private int lastGesture = Integer.MIN_VALUE;

    private final Runnable frameStatusUpdater = new Runnable() {
        @Override
        public void run() {
            frameStatus.setText(String.format(Locale.TAIWAN, "影像幀：%,d", frameCount.get()));
            if (resourcesStarted) {
                mainHandler.postDelayed(this, STATUS_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cameraSurface = findViewById(R.id.cameraSurface);
        cameraStatus = findViewById(R.id.cameraStatus);
        tofStatus = findViewById(R.id.tofStatus);
        resolutionStatus = findViewById(R.id.resolutionStatus);
        frameStatus = findViewById(R.id.frameStatus);
        gestureText = findViewById(R.id.gestureText);
        errorText = findViewById(R.id.errorText);
        Button retryButton = findViewById(R.id.retryButton);

        retryButton.setOnClickListener(view -> restartHardware());
    }

    @Override
    protected void onStart() {
        super.onStart();
        requestPermissionOrStart();
    }

    private void requestPermissionOrStart() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startHardware();
        } else {
            requestPermissions(
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_REQUEST
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != CAMERA_PERMISSION_REQUEST) {
            return;
        }

        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startHardware();
        } else {
            cameraStatus.setText("鏡頭：沒有相機權限");
            showError("請允許相機權限，再按「重新連接」。");
        }
    }

    private void restartHardware() {
        stopHardware();
        errorText.setVisibility(View.GONE);
        frameCount.set(0L);
        gestureText.setText("尚未偵測");
        requestPermissionOrStart();
    }

    private void startHardware() {
        if (resourcesStarted) {
            return;
        }

        resourcesStarted = true;
        errorText.setVisibility(View.GONE);
        startCamera();
        startTof();
        mainHandler.removeCallbacks(frameStatusUpdater);
        mainHandler.post(frameStatusUpdater);
    }

    private void startCamera() {
        try {
            cameraStatus.setText("鏡頭：正在連接…");
            cameraManager = new CameraManager(getApplicationContext());
            cameraManager.addSurfaceHolder(cameraSurface.getHolder());

            String[] resolutions = cameraManager.getResolutionList();
            if (resolutions == null || resolutions.length == 0) {
                throw new IllegalStateException("眼鏡沒有回傳可用解析度");
            }

            cameraManager.setResolutionIndex(0);
            resolutionStatus.setText("解析度：" + resolutions[0]);
            cameraManager.setCameraFrameListener((buffer, width, height, format) -> {
                frameCount.incrementAndGet();
            });
            cameraManager.startCamera(CameraManager.COLOR_FORMAT_RGBA);
            cameraStatus.setText("鏡頭：已啟動");
        } catch (Throwable error) {
            cameraStatus.setText("鏡頭：啟動失敗");
            showError("鏡頭錯誤：" + safeMessage(error));
            releaseCamera();
        }
    }

    private void startTof() {
        try {
            tofStatus.setText("ToF：正在偵測…");
            tofManager = new TofManager(getApplicationContext());
            tofManager.setTofDevicesAttachListener(this);
            tofManager.setTofGestureListener(this);

            if (!tofManager.isDeviceSupportToF()) {
                tofStatus.setText("ToF：未偵測到支援裝置");
                showError("目前連接的眼鏡未偵測到 ToF；請確認型號、USB 連線及供電。 ");
                return;
            }

            tofManager.open();
            tofStatus.setText("ToF：已啟動，等待手勢");
        } catch (Throwable error) {
            tofStatus.setText("ToF：啟動失敗");
            showError("ToF 錯誤：" + safeMessage(error));
            releaseTof();
        }
    }

    @Override
    public void onTofDevicesAttached(boolean attached) {
        runOnUiThread(() -> {
            tofStatus.setText(attached ? "ToF：已連接，等待手勢" : "ToF：裝置已中斷");
            if (!attached) {
                showError("ToF 裝置連線中斷；請檢查 USB-C 與眼鏡供電。");
            }
        });
    }

    @Override
    public void onTofGestureEvent(TofGestureEvent event) {
        if (event == null || event.getAction() != TofGestureEvent.ACTION_RECEIVED) {
            return;
        }

        long now = android.os.SystemClock.elapsedRealtime();
        int gesture = event.getGesture();
        if (gesture == lastGesture && now - lastGestureTime < GESTURE_DEBOUNCE_MS) {
            return;
        }

        lastGesture = gesture;
        lastGestureTime = now;
        String label = gestureLabel(gesture);

        runOnUiThread(() -> {
            gestureText.setText(label);
            tofStatus.setText("ToF：手勢辨識正常");
        });
    }

    private String gestureLabel(int gesture) {
        switch (gesture) {
            case TofGestureEvent.GESTURE_UP:
                return "向上 UP";
            case TofGestureEvent.GESTURE_DOWN:
                return "向下 DOWN";
            case TofGestureEvent.GESTURE_LEFT:
                return "向左 LEFT";
            case TofGestureEvent.GESTURE_RIGHT:
                return "向右 RIGHT";
            case TofGestureEvent.GESTURE_PULL:
                return "拉回 PULL";
            case TofGestureEvent.GESTURE_PUSH:
                return "推進 PUSH";
            case TofGestureEvent.GESTURE_HALT:
                return "停止 HALT";
            case TofGestureEvent.PRESENCE:
                return "接近 PRESENCE";
            default:
                return "未知手勢 " + gesture;
        }
    }

    private void showError(String message) {
        runOnUiThread(() -> {
            errorText.setText(message);
            errorText.setVisibility(View.VISIBLE);
        });
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    @Override
    protected void onStop() {
        stopHardware();
        super.onStop();
    }

    private void stopHardware() {
        resourcesStarted = false;
        mainHandler.removeCallbacks(frameStatusUpdater);
        releaseCamera();
        releaseTof();
    }

    private void releaseCamera() {
        CameraManager manager = cameraManager;
        cameraManager = null;
        if (manager == null) {
            return;
        }

        try {
            manager.setCameraFrameListener(null);
        } catch (Throwable ignored) {
        }
        try {
            manager.stopCamera();
        } catch (Throwable ignored) {
        }
        try {
            manager.release();
        } catch (Throwable ignored) {
        }
    }

    private void releaseTof() {
        TofManager manager = tofManager;
        tofManager = null;
        if (manager == null) {
            return;
        }

        try {
            manager.setTofGestureListener(null);
            manager.setTofDevicesAttachListener(null);
        } catch (Throwable ignored) {
        }
        try {
            manager.close();
        } catch (Throwable ignored) {
        }
        try {
            manager.release();
        } catch (Throwable ignored) {
        }
    }
}
