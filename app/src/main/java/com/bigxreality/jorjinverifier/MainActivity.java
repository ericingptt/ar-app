package com.bigxreality.jorjinverifier;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;

import java.util.Locale;

import com.bigxreality.jorjinverifier.JorjinHardwareManager.LayerState;

/**
 * Diagnostics screen for the glasses' RGB camera and native ToF gestures.
 *
 * <p>All hardware handling lives in {@link JorjinHardwareManager}; this class only maps its
 * per-layer reports onto the panel, so a stuck verification can be attributed to a specific
 * layer (USB enumeration, USB grant, SDK manager, ToF runtime state, gesture delivery) instead of
 * a single opaque "ToF connected" line.
 */
public final class MainActivity extends Activity implements JorjinHardwareManager.Listener {
    private static final String TAG = "JorjinVerifier";
    private static final int CAMERA_PERMISSION_REQUEST = 1001;
    private static final String PREF_COLUMN_RIGHT = "columnIncreasesRight";
    private static final String PREF_ROW_DOWN = "rowIncreasesDown";
    /** How often the depth grid is redrawn; frames arrive far faster than the eye needs. */
    private static final long HEATMAP_INTERVAL_MS = 100L;

    private static final String GESTURE_GUIDE =
            "手在 ToF 前方 2–45 cm：\n"
            + "• 左右揮 → LEFT / RIGHT\n"
            + "• 上下揮 → UP / DOWN\n"
            + "• 手掌靠近 → PUSH\n"
            + "• 手掌拉遠 → PULL\n"
            + "• 停住不動 → HALT\n"
            + "• 只要手進入範圍 → PRESENCE\n"
            + "揮完要把手移開，手勢才會判定。";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SurfaceView cameraSurface;
    private TextView cameraStatus;
    private TextView resolutionStatus;
    private TextView tofUsbStatus;
    private TextView tofPermissionStatus;
    private TextView tofManagerStatus;
    private TextView tofStateStatus;
    private TextView tofFirmwareStatus;
    private TextView gestureListenerStatus;
    private TextView gestureText;
    private TextView gestureCount;
    private TextView errorText;
    private TextView usbInventory;
    private TextView tofLive;
    private TextView gestureGuide;
    private TofHeatmapView tofHeatmap;
    private android.widget.Button flipHorizontal;
    private android.widget.Button flipVertical;
    private final float[] depthSnapshot = new float[TofGestureRecognizer.ZONES];
    private android.content.SharedPreferences prefs;
    private JorjinHardwareManager hardware;
    private String resolution = "—";
    private long frames;
    private boolean foreground;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        cameraSurface = findViewById(R.id.cameraSurface);
        cameraStatus = findViewById(R.id.cameraStatus);
        resolutionStatus = findViewById(R.id.resolutionStatus);
        tofUsbStatus = findViewById(R.id.tofUsbStatus);
        tofPermissionStatus = findViewById(R.id.tofPermissionStatus);
        tofManagerStatus = findViewById(R.id.tofManagerStatus);
        tofStateStatus = findViewById(R.id.tofStateStatus);
        tofFirmwareStatus = findViewById(R.id.tofFirmwareStatus);
        gestureListenerStatus = findViewById(R.id.gestureListenerStatus);
        gestureText = findViewById(R.id.gestureText);
        gestureCount = findViewById(R.id.gestureCount);
        errorText = findViewById(R.id.errorText);
        usbInventory = findViewById(R.id.usbInventory);
        tofLive = findViewById(R.id.tofLive);
        gestureGuide = findViewById(R.id.gestureGuide);
        tofHeatmap = findViewById(R.id.tofHeatmap);
        flipHorizontal = findViewById(R.id.flipHorizontal);
        flipVertical = findViewById(R.id.flipVertical);
        gestureGuide.setText(GESTURE_GUIDE);
        prefs = getSharedPreferences("gesture", MODE_PRIVATE);
        findViewById(R.id.openCibarButton).setOnClickListener(view ->
                startActivity(new android.content.Intent(this, CibarActivity.class)));
        findViewById(R.id.retryButton).setOnClickListener(view -> {
            errorText.setVisibility(View.GONE);
            hardware.restart();
        });
        hardware = new JorjinHardwareManager(this, this);
        hardware.setSurfaceHolder(cameraSurface.getHolder());
        // Restored across launches: once a tester has corrected the axes for their unit, they
        // should never have to do it again.
        hardware.setColumnIncreasesRight(prefs.getBoolean(PREF_COLUMN_RIGHT, true));
        hardware.setRowIncreasesDown(prefs.getBoolean(PREF_ROW_DOWN, true));
        flipHorizontal.setOnClickListener(view -> {
            boolean value = !hardware.isColumnIncreasesRight();
            hardware.setColumnIncreasesRight(value);
            prefs.edit().putBoolean(PREF_COLUMN_RIGHT, value).apply();
            renderFlipLabels();
        });
        flipVertical.setOnClickListener(view -> {
            boolean value = !hardware.isRowIncreasesDown();
            hardware.setRowIncreasesDown(value);
            prefs.edit().putBoolean(PREF_ROW_DOWN, value).apply();
            renderFlipLabels();
        });
        renderFlipLabels();
        installCrashReporter();
    }

    /**
     * The SDK drives capture and rendering on threads it owns, so an exception there tears down
     * the process before any try/catch around startCamera can see it - the failure vanishes with
     * nothing on screen.  Surface it instead, and keep the process alive when the dying thread is
     * not the main one so the message can actually be read on the device.
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

    /** Redraws the depth grid and the live hand readout independently of the 1 s watchdog. */
    private final Runnable heatmapTicker = new Runnable() {
        @Override public void run() {
            if (!foreground) return;
            int active = hardware.copyDepthSnapshot(depthSnapshot);
            float row = hardware.handRow();
            float column = hardware.handColumn();
            tofHeatmap.setFrame(depthSnapshot, row, column);
            if (active >= TofGestureRecognizer.MIN_ACTIVE_ZONES) {
                tofLive.setText(String.format(Locale.TAIWAN,
                        "手部：偵測中　距離 %.0f mm　亮區 %d", hardware.handRangeMm(), active));
            } else {
                tofLive.setText("手部：未偵測（把手放到 ToF 前方 2–45 cm）");
            }
            mainHandler.postDelayed(this, HEATMAP_INTERVAL_MS);
        }
    };

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        requestPermissionOrStart();
        mainHandler.removeCallbacks(heatmapTicker);
        mainHandler.post(heatmapTicker);
    }

    @Override protected void onStop() {
        foreground = false;
        mainHandler.removeCallbacks(heatmapTicker);
        hardware.stop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        foreground = false;
        hardware.stop();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void requestPermissionOrStart() {
        if (!foreground) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            hardware.start();
        } else {
            cameraStatus.setText("RGB Camera：等待相機權限");
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST || !foreground) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            hardware.start();
        } else {
            cameraStatus.setText("RGB Camera：Failed（未取得相機權限）");
            onError("未取得相機權限；請在系統設定允許後按「重新連接」。");
        }
    }

    // ---------------------------------------------------------------- Listener

    @Override public void onCameraState(LayerState state, String detail) {
        post(() -> cameraStatus.setText("RGB Camera："
                + JorjinHardwareManager.label(state, "Connected", "Failed", "等待中")
                + suffix(detail)));
    }

    @Override public void onCameraResolution(String value) {
        post(() -> {
            resolution = value;
            renderResolution();
        });
    }

    @Override public void onFrameCount(long value) {
        post(() -> {
            frames = value;
            renderResolution();
            renderGestureCount();
        });
    }

    /**
     * Shows the ToF depth-frame count next to the gesture count. Frames rising while gestures
     * stay at zero is the on-screen proof that the USB link, the SDK parser and the listener
     * are all working and the module simply is not reporting gestures.
     */
    private void renderGestureCount() {
        gestureCount.setText("手勢次數：" + JorjinHardwareManager.formatCount(hardware.gestureCount())
                + "（原始事件 " + JorjinHardwareManager.formatCount(hardware.rawGestureEventCount())
                + "，ToF 資料幀 " + JorjinHardwareManager.formatCount(hardware.tofFrameCount())
                + "）");
    }

    private void renderFlipLabels() {
        flipHorizontal.setText(hardware.isColumnIncreasesRight() ? "左右：正常" : "左右：已對調");
        flipVertical.setText(hardware.isRowIncreasesDown() ? "上下：正常" : "上下：已對調");
    }

    private void renderResolution() {
        resolutionStatus.setText("解析度：" + resolution + "　影像幀："
                + JorjinHardwareManager.formatCount(frames));
    }

    @Override public void onTofUsbState(LayerState detected, LayerState permission, String detail) {
        post(() -> {
            tofUsbStatus.setText("ToF USB："
                    + JorjinHardwareManager.label(detected, "Detected", "Not detected", "偵測中")
                    + suffix(detail));
            tofPermissionStatus.setText("ToF USB Permission："
                    + JorjinHardwareManager.label(permission, "Granted", "Missing", "Missing"));
        });
    }

    @Override public void onTofManagerState(LayerState state, String detail) {
        post(() -> tofManagerStatus.setText("ToF Manager："
                + JorjinHardwareManager.label(state, "Opened", "Failed", "等待中")
                + suffix(detail)));
    }

    @Override public void onTofRuntimeState(LayerState state, String firmware,
                                            boolean listenerRegistered) {
        post(() -> {
            tofStateStatus.setText("ToF State："
                    + JorjinHardwareManager.label(state, "Ready", "已中斷", "Waiting"));
            if (firmware == null || firmware.trim().isEmpty()) {
                tofFirmwareStatus.setText("ToF Firmware：—（手勢需 v1.2.2 以上）");
            } else if (JorjinHardwareManager.firmwareNumericallyAtLeast122(firmware)) {
                tofFirmwareStatus.setText("ToF Firmware：" + firmware + "（支援手勢）");
            } else {
                // The module answers, streams depth frames and reports Ready, but firmware
                // older than v1.2.2 has no gesture engine behind it, so the gesture bits in
                // every frame stay zero and no event can ever be delivered. Say so here
                // rather than letting the screen sit on "尚未偵測" looking like an app bug.
                tofFirmwareStatus.setText("ToF Firmware：" + firmware
                        + " ⚠ 低於 v1.2.2，韌體不會輸出手勢");
            }
            gestureListenerStatus.setText("Gesture Listener："
                    + (listenerRegistered ? "Registered" : "Not registered"));
        });
    }

    @Override public void onGesture(String label, int gesture, String source, long count) {
        post(() -> {
            gestureText.setText("最後手勢：" + label + "（" + source + "）");
            renderGestureCount();
        });
    }

    @Override public void onError(String message) {
        post(() -> {
            errorText.setText(message);
            errorText.setVisibility(View.VISIBLE);
        });
    }

    @Override public void onUsbInventory(String inventory) {
        post(() -> usbInventory.setText(inventory));
    }

    private static String suffix(String detail) {
        return detail == null || detail.trim().isEmpty() ? "" : "（" + detail + "）";
    }

    private void post(Runnable action) {
        mainHandler.post(() -> {
            if (!isFinishing() && !isDestroyed()) action.run();
        });
    }
}
