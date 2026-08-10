package com.bigxreality.jorjinverifier;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import com.jorjin.jjsdk.tof.TofDevicesAttachListener;
import com.jorjin.jjsdk.tof.TofGestureEvent;
import com.jorjin.jjsdk.tof.TofGestureEventListener;
import com.jorjin.jjsdk.tof.TofManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Hosts the CIBAR experience in a WebView and drives it with the glasses' ToF gestures.
 *
 * The experience stays online rather than bundled: it is still being built, so pointing at the
 * deployed site keeps the shell from freezing a half-finished copy, and CIBAR needs no changes
 * at all - the selection layer is injected from assets. Touch keeps working throughout, since
 * gesture recognition is not dependable enough to be the only way into a public exhibit.
 */
public final class CibarActivity extends Activity
        implements TofGestureEventListener, TofDevicesAttachListener {
    private static final String TAG = "JorjinCibar";
    private static final String ACTION_USB_PERMISSION =
            "com.bigxreality.jorjinverifier.USB_PERMISSION";
    private static final String DEFAULT_URL = "https://ericingptt.github.io/CIBAR/";
    /** Override for a dev server or a branch preview: adb shell am start ... -e url <url>. */
    private static final String EXTRA_URL = "url";
    private static final long GESTURE_DEBOUNCE_MS = 300L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private TextView statusText;
    private TofManager tofManager;
    private UsbManager usbManager;
    private String bridgeScript;
    private boolean usbReceiverRegistered;
    private boolean awaitingUsbPermission;
    private boolean tofStarted;
    private long lastGestureTime;
    private int lastGesture = Integer.MIN_VALUE;

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            awaitingUsbPermission = false;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device == null) device = GlassesUsb.findDevice(usbManager);
            if (device != null && usbManager.hasPermission(device)) startTof();
            else setStatus("未取得眼鏡 USB 授權，手勢無法使用（觸控仍可操作）");
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_cibar);
        webView = findViewById(R.id.webView);
        statusText = findViewById(R.id.cibarStatus);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        bridgeScript = readAsset("gesture-bridge.js");
        configureWebView();

        String url = getIntent().getStringExtra(EXTRA_URL);
        webView.loadUrl(url == null || url.trim().isEmpty() ? DEFAULT_URL : url);
    }

    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);           // CIBAR persists scenario state here
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                injectBridge();
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                  WebResourceError error) {
                if (request.isForMainFrame()) {
                    setStatus("載入失敗：" + error.getDescription() + "（檢查網路連線）");
                }
            }
        });
    }

    /**
     * Re-injected on every page load; the script itself is idempotent and only rescans when it
     * is already present, so a CIBAR route change cannot end up with two selection layers.
     */
    private void injectBridge() {
        if (bridgeScript == null) return;
        webView.evaluateJavascript(bridgeScript, null);
    }

    private String readAsset(String name) {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(getAssets().open(name), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                text.append(line).append('\n');
            }
            return text.toString();
        } catch (Throwable error) {
            Log.e(TAG, "無法讀取 " + name, error);
            setStatus("手勢橋接程式載入失敗，僅能觸控操作");
            return null;
        }
    }

    @Override protected void onStart() {
        super.onStart();
        registerUsbReceiver();
        UsbDevice glasses = GlassesUsb.findDevice(usbManager);
        if (glasses == null) {
            setStatus("未偵測到眼鏡，僅能觸控操作");
        } else if (!usbManager.hasPermission(glasses)) {
            requestUsbPermission(glasses);
        } else {
            startTof();
        }
    }

    @Override protected void onStop() {
        awaitingUsbPermission = false;
        unregisterUsbReceiver();
        stopTof();
        super.onStop();
    }

    @Override protected void onDestroy() {
        unregisterUsbReceiver();
        stopTof();
        mainHandler.removeCallbacksAndMessages(null);
        webView.destroy();
        super.onDestroy();
    }

    /** Back navigates inside the experience first, so a gesture mishap cannot exit the exhibit. */
    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
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

    private void requestUsbPermission(UsbDevice device) {
        if (awaitingUsbPermission) return;
        awaitingUsbPermission = true;
        setStatus("等待眼鏡 USB 授權…");
        Intent intent = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
        usbManager.requestPermission(device, PendingIntent.getBroadcast(
                this, 0, intent, PendingIntent.FLAG_IMMUTABLE));
    }

    private void startTof() {
        if (tofStarted) return;
        try {
            TofManager manager = new TofManager(getApplicationContext());
            tofManager = manager;
            manager.setTofDevicesAttachListener(this);
            manager.setTofGestureListener(this);
            if (!manager.isDeviceSupportToF()) {
                setStatus("此眼鏡未回報支援 ToF，僅能觸控操作");
                stopTof();
                return;
            }
            manager.open();
            tofStarted = true;
            setStatus("手勢已啟用（韌體需 v1.2.2 以上）");
        } catch (Throwable error) {
            Log.e(TAG, "啟動 ToF 失敗", error);
            setStatus("ToF 啟動失敗，僅能觸控操作");
            stopTof();
        }
    }

    private void stopTof() {
        tofStarted = false;
        TofManager manager = tofManager;
        tofManager = null;
        if (manager == null) return;
        try {
            manager.setTofGestureListener(null);
            manager.setTofDevicesAttachListener(null);
        } catch (Throwable error) { Log.w(TAG, "解除 ToF listener 失敗", error); }
        try { manager.close(); } catch (Throwable error) { Log.w(TAG, "關閉 ToF 失敗", error); }
        try { manager.release(); } catch (Throwable error) { Log.w(TAG, "釋放 ToF 失敗", error); }
    }

    @Override public void onTofDevicesAttached(boolean attached) {
        mainHandler.post(() -> setStatus(attached ? "ToF 已連接" : "ToF 已中斷，僅能觸控操作"));
    }

    @Override public void onTofGestureEvent(TofGestureEvent event) {
        if (event == null || event.getAction() != TofGestureEvent.ACTION_RECEIVED) return;
        long now = SystemClock.elapsedRealtime();
        final int gesture = event.getGesture();
        synchronized (this) {
            if (gesture == lastGesture && now - lastGestureTime < GESTURE_DEBOUNCE_MS) return;
            lastGesture = gesture;
            lastGestureTime = now;
        }
        // Quoted through JSONObject so a label can never break out of the JS string literal.
        final String name = org.json.JSONObject.quote(GestureLabels.from(gesture));
        // Gesture callbacks arrive on an SDK thread; WebView is main-thread only.
        mainHandler.post(() -> webView.evaluateJavascript(
                "window.__jjsdk && window.__jjsdk.onGesture(" + gesture + "," + name + ")",
                null));
    }

    private void setStatus(String message) {
        mainHandler.post(() -> statusText.setText(message));
    }
}
