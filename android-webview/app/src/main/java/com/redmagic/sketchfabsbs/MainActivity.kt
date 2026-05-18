package com.redmagic.sketchfabsbs

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.Os
import android.util.Log
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.leia.sdk.FaceTrackingRuntime
import com.leia.sdk.LeiaSDK
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sbsHolder: SbsWebViewHolder
    private lateinit var bridge: CnsdkBridge
    private var injectionScript = ""
    private var viewerMode = false
    private var cnsdkActive = false
    private var viewerReadyGeneration = 0
    private var fpsUnlockGeneration = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        Os.setenv("CNSDK_IN_APP_FORCE_TRACKING_FPS", TARGET_REFRESH_RATE.toInt().toString(), true)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        preferRefreshRate(TARGET_REFRESH_RATE)

        injectionScript = readAsset("sketchfab-sbs-inject.user.js")
        bridge = CnsdkBridge(this)
        sbsHolder = SbsWebViewHolder(this)
        setupWebView(sbsHolder.browserWebView, isViewer = false)
        setupWebView(sbsHolder.viewerWebView, isViewer = true)
        setContentView(sbsHolder)
        hideSystemUi()
        ensureCameraPermission()
        loadInitialUrl(intent)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        bridge.onResume()
        sbsHolder.onResume()
        if (cnsdkActive) {
            enableLeia3D()
        }
    }

    override fun onPause() {
        if (cnsdkActive) {
            disableLeia3D()
        }
        bridge.onPause()
        sbsHolder.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        disableLeia3D()
        bridge.shutdown()
        sbsHolder.destroy()
        super.onDestroy()
    }

    override fun onBackPressed() {
        val browserWebView = sbsHolder.browserWebView
        if (viewerMode) {
            leaveViewerMode()
            return
        }
        if (browserWebView.canGoBack()) {
            browserWebView.goBack()
            return
        }
        super.onBackPressed()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        loadInitialUrl(intent)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView(webView: WebView, isViewer: Boolean) {
        WebView.setWebContentsDebuggingEnabled(true)
        webView.setBackgroundColor(Color.BLACK)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = false
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        }
        webView.addJavascriptInterface(bridge, "CNSDKBridge")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(
                    TAG,
                    "console ${consoleMessage.messageLevel()} ${consoleMessage.sourceId()}:"
                        + "${consoleMessage.lineNumber()} ${consoleMessage.message()}"
                )
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                Log.i(TAG, "page started: $url")
                if (isViewer && isEmbedUrl(url)) {
                    scheduleInjection("started")
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                Log.i(TAG, "page finished: $url")
                if (isViewer && isEmbedUrl(url)) {
                    scheduleInjection("finished")
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (isViewer) return false
                return interceptSketchfabModelUrl(request.url.toString())
            }

            @Deprecated("Deprecated in Android")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (isViewer) return false
                return interceptSketchfabModelUrl(url)
            }
        }
    }

    private fun interceptSketchfabModelUrl(url: String?): Boolean {
        val modelId = extractSketchfabModelId(url) ?: return false
        Log.i(TAG, "intercept model detail: $url -> $modelId")
        loadEmbed(modelId)
        return true
    }

    private fun loadInitialUrl(intent: Intent?) {
        val modelId = extractSketchfabModelId(intent?.dataString)
        if (modelId != null) {
            Log.i(TAG, "launch with model detail intent -> $modelId")
            loadEmbed(modelId)
        } else {
            leaveViewerMode()
            sbsHolder.browserWebView.loadUrl(FEED_URL)
        }
    }

    private fun loadEmbed(modelId: String) {
        enterViewerMode()
        sbsHolder.viewerWebView.loadUrl(buildEmbedUrl(modelId))
    }

    private fun enterViewerMode() {
        viewerReadyGeneration++
        if (cnsdkActive) {
            cnsdkActive = false
            disableLeia3D()
        }
        viewerMode = true
        cnsdkActive = false
        sbsHolder.showViewer2D()
    }

    private fun leaveViewerMode() {
        if (!viewerMode) {
            return
        }
        viewerReadyGeneration++
        viewerMode = false
        if (cnsdkActive) {
            cnsdkActive = false
            disableLeia3D()
        }
        sbsHolder.showBrowser()
        sbsHolder.viewerWebView.stopLoading()
        sbsHolder.viewerWebView.loadUrl("about:blank")
    }

    private fun onViewerReadyFor3D(reason: String) {
        if (!viewerMode || cnsdkActive || !isEmbedUrl(sbsHolder.viewerWebView.url)) {
            return
        }
        Log.i(TAG, "viewer ready for CNSDK 3D: $reason")
        cnsdkActive = true
        sbsHolder.showViewerInterlaced()
        enableLeia3D()
    }

    private fun enableLeia3D() {
        ensureCameraPermission()
        preferRefreshRate(TARGET_REFRESH_RATE)
        sbsHolder.setSdkFrameRate(TARGET_REFRESH_RATE)
        val targetFps = TARGET_REFRESH_RATE.toInt()
        Log.i(
            TAG,
            listOf(
                bridge.enable3D(),
                VendorFrameTuning.acquirePerformanceLock(this),
                VendorFrameTuning.setFrameRateByApp(TARGET_REFRESH_RATE),
                VendorFrameTuning.setMindSyncFps(packageName, targetFps),
                VendorFrameTuning.setMindSyncAppFps(packageName, targetFps)
            ).joinToString("; ")
        )
        scheduleFpsUnlockRetries(targetFps, ++fpsUnlockGeneration)
    }

    private fun disableLeia3D() {
        fpsUnlockGeneration++
        VendorFrameTuning.releasePerformanceLock()
        Log.i(TAG, bridge.disable3D())
    }

    private fun scheduleFpsUnlockRetries(targetFps: Int, generation: Int) {
        FPS_UNLOCK_RETRY_DELAYS_MS.forEachIndexed { index, delayMs ->
            handler.postDelayed({
                if (!cnsdkActive || generation != fpsUnlockGeneration || isFinishing || isDestroyed) {
                    return@postDelayed
                }
                val result = VendorFrameTuning.requestSurfaceFpsUnlock(packageName, targetFps)
                Log.i(TAG, "fps unlock ${index + 1}/${FPS_UNLOCK_RETRY_DELAYS_MS.size}: $result")
            }, delayMs)
        }
    }

    private fun scheduleInjection(reason: String) {
        for (i in 0 until 40) {
            val attempt = i + 1
            handler.postDelayed({ injectScript(reason, attempt) }, i * 500L)
        }
    }

    private fun injectScript(reason: String, attempt: Int) {
        val webView = sbsHolder.viewerWebView
        if (!isEmbedUrl(webView.url) || injectionScript.isEmpty()) {
            return
        }
        val wrapped =
            "(function(){try{" +
                "var compact=function(already){var s=window.__skfbSbs||{};" +
                "return JSON.stringify({already:already,scriptVersion:s.scriptVersion," +
                "patchedModules:s.patchedModules||[],runtimePatches:s.runtimePatches||[]," +
                "blockedEvents:(s.blockedEvents||[]).length,forcedOrbit:s.forcedOrbit||0," +
                "webpackPushSeen:s.webpackPushSeen||0,readyState:document.readyState," +
                "osdHidden:!!s.osdHidden," +
                "lastOrbitAttempt:s.lastOrbitAttempt||null,lastOrbitLoadedProbe:s.lastOrbitLoadedProbe||null," +
                "forcedOrbitTriggeredBy:s.forcedOrbitTriggeredBy||null,halfSbsMode:s.halfSbsMode||null," +
                "normalPipelineStereo:s.normalPipelineStereo||null});};" +
                "if(window.__skfbSbs&&window.__skfbSbs.scriptVersion==='${SCRIPT_VERSION}'){" +
                installViewerReadyProbeScript() + "\n" +
                "return compact(true);}" +
                "\n$injectionScript\n" +
                installViewerReadyProbeScript() + "\n" +
                "return compact(false);" +
                "}catch(e){return 'ERROR:'+((e&&e.stack)||e);}})();"
        webView.evaluateJavascript(wrapped) { value ->
            Log.i(TAG, "inject $reason #$attempt: $value")
        }
    }

    private fun installViewerReadyProbeScript(): String {
        return """
            ;(function(){
              if (window.__skfbLeiaReadyProbeVersion === '$SCRIPT_VERSION' && window.__skfbLeiaReadyProbeInstalled) return;
              var done = false;
              var startedAt = Date.now();
              function getViewerMain() {
                return document.querySelector('main[aria-label="sketchfab-viewer"],main.viewer');
              }
              function viewerSnapshot(viewer) {
                return {
                  source: 'main-model-loaded-probe',
                  reason: 'main-model-loaded-class',
                  elapsedMs: Date.now() - startedAt,
                  readyState: document.readyState,
                  viewerClass: viewer ? String(viewer.className || '') : '',
                  viewerAriaLabel: viewer ? String(viewer.getAttribute('aria-label') || '') : '',
                  canvasCount: document.querySelectorAll('canvas').length
                };
              }
              function notify(viewer) {
                if (done) return;
                done = true;
                if (window.CNSDKBridge && window.CNSDKBridge.viewerReadyFor3D) {
                  window.CNSDKBridge.viewerReadyFor3D(JSON.stringify(viewerSnapshot(viewer)));
                }
              }
              function tick(){
                if (done) return;
                var viewer = getViewerMain();
                if (viewer && /\bmodel-loaded\b/.test(String(viewer.className || ''))) {
                  notify(viewer);
                }
              }
              function install() {
                if (!document.documentElement) {
                  setTimeout(install, 50);
                  return;
                }
                window.__skfbLeiaReadyProbeVersion = '$SCRIPT_VERSION';
                window.__skfbLeiaReadyProbeInstalled = true;
                var observer = new MutationObserver(tick);
                observer.observe(document.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] });
                tick();
              }
              install();
            })();
        """.trimIndent()
    }

    private fun extractSketchfabModelId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.US) ?: return null
        if (host != "sketchfab.com" && host != "www.sketchfab.com") return null
        val path = uri.path ?: return null
        if (path.startsWith("/3d-models/")) {
            return MODEL_ID_AT_END.matcher(path).takeIf { it.find() }?.group(1)
        }
        val direct = DIRECT_MODEL_PATH.matcher(path)
        return if (direct.matches()) direct.group(1) else null
    }

    private fun isEmbedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.US) ?: return false
        return (host == "sketchfab.com" || host == "www.sketchfab.com")
            && EMBED_PATH.matcher(uri.path ?: "").matches()
    }

    private fun buildEmbedUrl(modelId: String): String {
        return "https://sketchfab.com/models/$modelId/embed?$EMBED_QUERY"
    }

    private fun readAsset(name: String): String {
        return try {
            assets.open(name).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                }
                output.toString(StandardCharsets.UTF_8.name())
            }
        } catch (error: Throwable) {
            Log.e(TAG, "failed to read asset: $name", error)
            ""
        }
    }

    private fun ensureCameraPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
        }
    }

    private fun preferRefreshRate(targetFps: Float) {
        val mode = windowManager.defaultDisplay.supportedModes.minByOrNull {
            kotlin.math.abs(it.refreshRate - targetFps)
        }
        window.attributes = window.attributes.apply {
            preferredRefreshRate = targetFps
            if (mode != null) {
                preferredDisplayModeId = mode.modeId
            }
        }
        Log.i(TAG, "Preferred display mode=${mode?.modeId}, refresh=${mode?.refreshRate}, target=$targetFps")
    }

    private fun hideSystemUi() {
        window.setDecorFitsSystemWindows(false)
        window.decorView.windowInsetsController?.apply {
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsets.Type.systemBars())
        }
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    class CnsdkBridge(private val activity: Activity) {
        private val context: Context = activity.applicationContext
        private var sdk: LeiaSDK? = null

        @JavascriptInterface
        fun enable3D(): String {
            return try {
                Log.i(TAG, "enable3D requested at ${SystemClock.elapsedRealtime()}ms")
                val instance = getSdk()
                instance.enableBacklight(true)
                instance.setFaceTrackingRuntime(FaceTrackingRuntime.InApp)
                instance.setFaceTrackingCaptureLux(false)
                instance.enable3D(true)
                instance.enableFaceTracking(true)
                instance.startFaceTracking(true)
                "CNSDK 3D enabled, face tracking ON"
            } catch (error: Throwable) {
                Log.e(TAG, "enable3D failed", error)
                "CNSDK enable failed: ${error.message ?: error.javaClass.simpleName}"
            }
        }

        @JavascriptInterface
        fun disable3D(): String {
            return try {
                sdk?.apply {
                    startFaceTracking(false)
                    enableFaceTracking(false)
                    enable3D(false)
                    enableBacklight(false)
                    enableNoFaceMode(false)
                }
                "CNSDK 3D disabled"
            } catch (error: Throwable) {
                Log.e(TAG, "disable3D failed", error)
                "CNSDK disable failed: ${error.message ?: error.javaClass.simpleName}"
            }
        }

        @JavascriptInterface
        fun status(): String {
            val instance = sdk ?: LeiaSDK.getInstance()
            return if (instance == null) {
                "CNSDK not initialized"
            } else {
                "CNSDK initialized, 3D=${instance.is3DEnabled}, backlight=${instance.backlight}"
            }
        }

        @JavascriptInterface
        fun viewerReadyFor3D(reason: String) {
            activity.runOnUiThread {
                (activity as? MainActivity)?.onViewerReadyFor3D(reason)
            }
        }

        fun onResume() {
            sdk?.onResume()
        }

        fun onPause() {
            sdk?.onPause()
        }

        fun shutdown() {
            disable3D()
            try {
                LeiaSDK.shutdownSDK()
            } catch (error: Throwable) {
                Log.w(TAG, "shutdownSDK failed", error)
            }
            sdk = null
        }

        private fun getSdk(): LeiaSDK {
            sdk?.let { return it }
            LeiaSDK.getInstance()?.let {
                sdk = it
                return it
            }
            val initArgs = LeiaSDK.InitArgs().apply {
                platform.context = context.applicationContext
                platform.activity = activity
                enableFaceTracking = true
                startFaceTracking = false
                faceTrackingRuntime = FaceTrackingRuntime.InApp
                faceTrackingPreferredFps = TARGET_REFRESH_RATE.toInt()
                requiresFaceTrackingPermissionCheck = false
            }
            return LeiaSDK.createSDK(initArgs).also { sdk = it }
        }
    }

    companion object {
        private const val TAG = "SketchfabLeia3D"
        private const val FEED_URL = "https://sketchfab.com/3d-models/staffpicks"
        private const val SCRIPT_VERSION = "2026-05-18-normal-pipeline-stereo-dpr1"
        private const val EMBED_QUERY =
            "autostart=1&internal=1&tracking=0&ui_ar=0&ui_infos=0&ui_snapshots=1" +
                "&ui_stop=0&ui_theatre=1&ui_watermark=0&cardboard=1&vr_stereo=1" +
                "&navigation=orbit&vr_ar=1"
        private const val CAMERA_REQUEST_CODE = 42
        private const val TARGET_REFRESH_RATE = 144f
        private val FPS_UNLOCK_RETRY_DELAYS_MS = longArrayOf(1200L, 2200L, 3400L, 4800L)
        private val MODEL_ID_AT_END = Pattern.compile("([0-9a-fA-F]{32})(?:/)?$")
        private val DIRECT_MODEL_PATH = Pattern.compile("^/models/([0-9a-fA-F]{32})/?$")
        private val EMBED_PATH = Pattern.compile("^/models/[0-9a-fA-F]{32}/embed/?$")
    }
}
