package com.bigxreality.jorjinverifier;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import com.bigxreality.jorjinverifier.JorjinHardwareManager.LayerState;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Hosts the CIBAR experience in a WebView and drives its selection with the glasses' gestures.
 *
 * <p>The experience stays online rather than bundled: it is still being built, so pointing at
 * the deployed site keeps the shell from freezing a half-finished copy, and CIBAR itself needs
 * no changes - the selection layer is injected from assets. Touch keeps working throughout,
 * because a public exhibit must not depend solely on gesture recognition.
 *
 * <p>An earlier version of this screen steered the page from head yaw and raw ToF range,
 * because at the time no gesture ever reached the app. That workaround is gone: gestures now
 * arrive through {@link JorjinHardwareManager}, computed from the module's depth frames, and
 * feed the page directly.
 */
public final class CibarActivity extends Activity implements JorjinHardwareManager.Listener {
    private static final String TAG = "JorjinCibar";
    private static final String DEFAULT_URL = "https://ericingptt.github.io/CIBAR/";
    /** Override for a dev server or a branch preview: adb shell am start ... -e url <url>. */
    private static final String EXTRA_URL = "url";
    /**
     * Loads the bundled harness instead of the live site. Validating the selection layer
     * against CIBAR first is the wrong order: it needs the network, its layout moves under us,
     * and a swipe that does nothing gives no way to separate a recogniser problem from a
     * page-structure one. Every screen in the harness states its own expected outcome.
     */
    private static final String EXTRA_LOCAL = "local";
    private static final String TEST_ASSET = "gesture-test.html";
    private static final long DWELL_POLL_MS = 100L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private TextView statusText;
    private JorjinHardwareManager hardware;
    private String bridgeScript;
    private boolean pageReady;
    private String gestureState = "手勢：等待眼鏡";
    private String lastGesture = "—";
    private float lastDwell = -1f;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_cibar);
        webView = findViewById(R.id.webView);
        statusText = findViewById(R.id.cibarStatus);
        bridgeScript = readAsset("gesture-bridge.js");
        configureWebView();
        hardware = new JorjinHardwareManager(this, this);
        // No camera preview on this screen; the ToF module is the only hardware it needs.
        if (getIntent().getBooleanExtra(EXTRA_LOCAL, false)) {
            String page = readAsset(TEST_ASSET);
            if (page == null) {
                render("找不到 " + TEST_ASSET);
                return;
            }
            // Inline rather than file:///android_asset, which needs file access the WebView
            // switches off by default from API 30 on.
            webView.loadDataWithBaseURL("https://localhost/gesture-test/", page,
                    "text/html", "utf-8", null);
            return;
        }
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
                pageReady = true;
                injectBridge();
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                                                  WebResourceError error) {
                if (request.isForMainFrame()) {
                    pageReady = false;
                    render("載入失敗：" + error.getDescription() + "（檢查網路連線）");
                }
            }
        });
    }

    private void injectBridge() {
        if (bridgeScript == null) {
            render("找不到 gesture-bridge.js，只能觸控操作");
            return;
        }
        webView.evaluateJavascript(bridgeScript, null);
        render(null);
    }

    private String readAsset(String name) {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                getAssets().open(name), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) text.append(line).append('\n');
            return text.toString();
        } catch (Throwable error) {
            Log.e(TAG, "讀取 " + name + " 失敗", error);
            return null;
        }
    }

    /**
     * Relays the hold-to-select countdown into the page. Polled rather than pushed: progress is
     * a continuous value the page redraws, not an event, and 10 Hz is smooth enough to read.
     */
    private final Runnable dwellTicker = new Runnable() {
        @Override public void run() {
            float progress = hardware.dwellProgress();
            if (pageReady && Math.abs(progress - lastDwell) > 0.02f) {
                lastDwell = progress;
                webView.evaluateJavascript(
                        "window.__jjsdk && window.__jjsdk.setDwell(" + progress + ")", null);
            }
            mainHandler.postDelayed(this, DWELL_POLL_MS);
        }
    };

    @Override protected void onStart() {
        super.onStart();
        hardware.start();
        mainHandler.removeCallbacks(dwellTicker);
        mainHandler.post(dwellTicker);
    }

    @Override protected void onStop() {
        mainHandler.removeCallbacks(dwellTicker);
        hardware.stop();
        super.onStop();
    }

    @Override protected void onDestroy() {
        hardware.stop();
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** The page owns history, so a swipe-back inside CIBAR must not close the activity. */
    @Override public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    // ---------------------------------------------------------------- gestures

    @Override public void onGesture(String label, int gesture, String source, long count) {
        lastGesture = label + "（" + source + "）";
        render(null);
        if (!pageReady || gesture < 0) return;
        // The bridge decides what each gesture does to the page; the shell only relays.
        String script = "window.__jjsdk && window.__jjsdk.onGesture("
                + gesture + ",'" + GestureLabels.code(gesture) + "')";
        webView.evaluateJavascript(script, null);
    }

    // ---------------------------------------------------------------- status

    /**
     * One line, and only about whether gestures are live - the injected bridge draws its own
     * focus and gesture readout inside the page, where the user is already looking.
     */
    private void render(String error) {
        mainHandler.post(() -> {
            if (isFinishing() || isDestroyed()) return;
            statusText.setText(error != null ? error
                    : gestureState + "　最後手勢：" + lastGesture);
            statusText.setVisibility(View.VISIBLE);
        });
    }

    @Override public void onTofRuntimeState(LayerState state, String firmware,
                                            boolean listenerRegistered) {
        gestureState = state == LayerState.OK ? "手勢：可用" : "手勢：等待 ToF";
        render(null);
    }

    @Override public void onTofUsbState(LayerState detected, LayerState permission,
                                        String detail) {
        if (detected != LayerState.OK) gestureState = "手勢：未偵測到 ToF";
        else if (permission != LayerState.OK) gestureState = "手勢：等待 USB 授權";
        render(null);
    }

    @Override public void onError(String message) {
        render(message);
    }

    // The remaining diagnostics belong to the verification screen, not to the experience.
    @Override public void onCameraState(LayerState state, String detail) { }

    @Override public void onCameraResolution(String resolution) { }

    @Override public void onFrameCount(long frames) { }

    @Override public void onTofManagerState(LayerState state, String detail) { }

    @Override public void onUsbInventory(String inventory) { }
}
