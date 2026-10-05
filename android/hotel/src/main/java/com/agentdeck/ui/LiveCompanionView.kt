package com.agentdeck.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** Read-only packaged 3D stage: no device/JavaScript bridge or network content. */
@SuppressLint("SetJavaScriptEnabled")
class LiveCompanionView(context: Context, private val onAvailability: (Boolean, Boolean) -> Unit) : WebView(context) {
    private var loaded = false
    private var failed = false
    private var disposed = false
    private var stageReady = false
    private var visibleStage = false
    private var latest = JSONObject().put("visible", false)
    private var lastPayload = ""

    init {
        setBackgroundColor(Color.rgb(250, 251, 252))
        isFocusable = false
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        visibility = View.INVISIBLE
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true
            domStorageEnabled = false
            databaseEnabled = false
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportZoom(false)
        }
        webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView?, title: String?) {
                when (title) {
                    "Orange companion ready" -> {
                        failed = false; stageReady = true
                        visibility = if (visibleStage) View.VISIBLE else View.INVISIBLE
                        onAvailability(true, false)
                    }
                    "Orange companion unavailable" -> {
                        failed = true; stageReady = false; visibility = View.INVISIBLE
                        onAvailability(false, true)
                    }
                }
            }
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                val uri = request?.url
                val file = if (uri?.scheme == "https" && uri.host == "orange-companion.invalid" &&
                    uri.query == null && uri.fragment == null && request?.method == "GET") {
                    when (uri.path) { "/index.html" -> "index.html"; "/companion.js" -> "companion.js"; else -> null }
                } else null
                if (file != null) {
                    try {
                        return WebResourceResponse(if (file.endsWith(".js")) "application/javascript" else "text/html", "UTF-8",
                            200, "OK", mapOf(
                                "Cache-Control" to "no-store",
                                "Content-Security-Policy" to "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; img-src data:; connect-src 'none'; base-uri 'none'; form-action 'none'",
                                "X-Content-Type-Options" to "nosniff"
                            ), context.assets.open("voice-companion/$file"))
                    } catch (_: Exception) { }
                }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != "https://orange-companion.invalid/index.html" || disposed) return
                loaded = true
                publish()
                if (!visibleStage) onPause()
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) { failed = true; onAvailability(false, true) }
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                failed = true; loaded = false; visibility = View.INVISIBLE
                onAvailability(false, true)
                disposeStage()
                return true
            }
        }
        loadUrl("https://orange-companion.invalid/index.html")
        // A failed vendor renderer must expose the native fallback, not mask it
        // behind an empty opaque stage. Voice and End call remain native.
        postDelayed({
            if (!disposed && !stageReady) {
                failed = true; visibility = View.INVISIBLE
                onAvailability(false, true)
            }
        }, 8000L)
    }

    // Decoration cannot steal touches or hardware focus from native controls.
    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    fun updateStage(show: Boolean, phase: String, energy: Float, reduced: Boolean, session: Long, preview: Boolean) {
        if (disposed) return
        val changedVisibility = show != visibleStage
        visibleStage = show
        latest = JSONObject().put("visible", show).put("state", phase.lowercase())
            .put("energy", if (energy.isFinite()) energy.coerceIn(0f, 1f).toDouble() else 0.0)
            .put("reduceMotion", reduced).put("session", session).put("preview", preview)
        if (show && changedVisibility) onResume()
        visibility = if (show && stageReady && !failed) View.VISIBLE else View.INVISIBLE
        publish()
        if (!show && changedVisibility) onPause()
    }

    private fun publish() {
        if (!loaded || failed || disposed) return
        val payload = latest.toString()
        if (payload == lastPayload) return
        lastPayload = payload
        evaluateJavascript("window.OrangeCompanion && window.OrangeCompanion.update($payload);", null)
    }

    fun disposeStage() {
        if (disposed) return
        disposed = true
        (parent as? ViewGroup)?.removeView(this)
        stopLoading(); onPause(); removeAllViews(); destroy()
    }
}
