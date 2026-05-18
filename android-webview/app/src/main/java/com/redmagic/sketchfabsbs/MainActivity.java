package com.redmagic.sketchfabsbs;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String TAG = "SketchfabSbs";
    private static final String SCRIPT_VERSION = "2026-05-18-safe-webpack-require";
    private static final String TARGET_URL =
            "https://sketchfab.com/models/b2359160a4f54e76b5ae427a55d9594d/embed"
                    + "?cardboard=1&vr_ar=1&vr_stereo=1&autostart=1&internal=1"
                    + "&tracking=0&navigation=orbit&ui_ar=0&ui_infos=0"
                    + "&ui_snapshots=1&ui_stop=0&ui_theatre=1&ui_watermark=0";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WebView webView;
    private String injectionScript;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        injectionScript = readAsset("sketchfab-sbs-inject.user.js");
        setupWebView();
        hideSystemUi();
        webView.loadUrl(TARGET_URL);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebView.setWebContentsDebuggingEnabled(true);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setContentView(webView, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d(TAG, "console " + consoleMessage.messageLevel()
                        + " " + consoleMessage.sourceId()
                        + ":" + consoleMessage.lineNumber()
                        + " " + consoleMessage.message());
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Log.i(TAG, "page started: " + url);
                scheduleInjection("started");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.i(TAG, "page finished: " + url);
                scheduleInjection("finished");
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }
        });
    }

    private void scheduleInjection(String reason) {
        for (int i = 0; i < 40; i++) {
            final int attempt = i + 1;
            handler.postDelayed(() -> injectScript(reason, attempt), i * 500L);
        }
    }

    private void injectScript(String reason, int attempt) {
        if (webView == null || injectionScript == null || injectionScript.length() == 0) {
            return;
        }
        String wrapped =
                "(function(){try{"
                        + "var compact=function(already){var s=window.__skfbSbs||{};"
                        + "return JSON.stringify({already:already,scriptVersion:s.scriptVersion,"
                        + "patchedModules:s.patchedModules||[],runtimePatches:s.runtimePatches||[],"
                        + "blockedEvents:(s.blockedEvents||[]).length,forcedOrbit:s.forcedOrbit||0,"
                        + "webpackPushSeen:s.webpackPushSeen||0,readyState:document.readyState,"
                        + "lastOrbitAttempt:s.lastOrbitAttempt||null});};"
                        + "if(window.__skfbSbs&&window.__skfbSbs.scriptVersion==='"
                        + SCRIPT_VERSION
                        + "'){return compact(true);}"
                        + "\n" + injectionScript + "\n"
                        + "return compact(false);"
                        + "}catch(e){return 'ERROR:'+((e&&e.stack)||e);}})();";
        webView.evaluateJavascript(wrapped, value ->
                Log.i(TAG, "inject " + reason + " #" + attempt + ": " + value)
        );
    }

    private String readAsset(String name) {
        try (InputStream input = getAssets().open(name)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            Log.e(TAG, "failed to read asset: " + name, e);
            return "";
        }
    }

    private void hideSystemUi() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }
}
