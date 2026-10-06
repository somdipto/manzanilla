package com.agentdeck.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.webkit.*
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.ArrayDeque

/** Local UI only. The WebView has no microphone, credential, file or network access. */
@SuppressLint("SetJavaScriptEnabled")
class ReceptionView(context: Context, private val action: (JSONObject) -> Unit) : FrameLayout(context) {
    private val browser = WebView(context)
    private val waiting = ArrayDeque<JSONObject>()
    private var ready = false
    private var disposed = false
    private var shown = false
    private val host = "manzania-reception.invalid"

    init {
        setBackgroundColor(Color.rgb(237, 241, 237))
        visibility = View.GONE
        addView(browser, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        browser.setBackgroundColor(Color.TRANSPARENT)
        browser.settings.apply {
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
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(false)
        }
        browser.addJavascriptInterface(object {
            @JavascriptInterface fun postMessage(raw: String) {
                if (raw.length > 8192) return
                val message = try { JSONObject(raw) } catch (_: Exception) { return }
                if (message.optString("action") !in setOf("start", "stop", "info", "exit", "speak", "set_key", "save_key", "clear_key", "turn", "silence", "bell", "night", "activity", "confirm", "message", "operator", "checkin", "taxi", "question")) return
                post { if (!disposed && shown) action(message) }
            }
        }, "ManzaniaNative")
        browser.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse {
                val uri = request?.url
                val path = uri?.path?.removePrefix("/") ?: ""
                val permitted = uri?.scheme == "https" && uri.host == host && uri.query == null &&
                    request?.method == "GET" && path in setOf("index.html", "reception.js", "reception.css", "scene.js")
                if (permitted) {
                    val mime = when { path.endsWith(".js") -> "application/javascript"; path.endsWith(".css") -> "text/css"; else -> "text/html" }
                    try {
                        return WebResourceResponse(mime, "UTF-8", 200, "OK", mapOf(
                            "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
                            "Content-Security-Policy" to "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'none'; object-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'"
                        ), context.assets.open("reception/$path"))
                    } catch (_: Exception) { }
                }
                return WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(ByteArray(0)))
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                if (url != "https://$host/index.html" || disposed) return
                ready = true
                while (waiting.isNotEmpty()) deliver(waiting.removeFirst())
                if (!shown) browser.onPause()
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) showFallback()
            }
            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                ready = false
                post { showFallback(); action(JSONObject().put("action", "stop")) }
                return true
            }
        }
        browser.loadUrl("https://$host/index.html")
    }

    fun setShown(value: Boolean) {
        if (disposed || value == shown) return
        shown = value
        visibility = if (value) View.VISIBLE else View.GONE
        if (value) { browser.onResume(); push(JSONObject().put("type", "visibility").put("visible", true)) }
        else { push(JSONObject().put("type", "visibility").put("visible", false)); browser.onPause() }
    }

    fun push(message: JSONObject) {
        if (disposed) return
        if (!message.has("type") && message.has("t")) message.put("type", message.optString("t"))
        if (!ready) {
            if (message.optString("type") == "energy") return
            if (waiting.size >= 32) waiting.removeFirst()
            waiting.addLast(message)
        } else deliver(message)
    }

    private fun deliver(message: JSONObject) {
        browser.evaluateJavascript("window.manzania && window.manzania.receive(${message});", null)
    }

    private fun showFallback() {
        if (disposed) return
        browser.visibility = View.GONE
        if (childCount > 1) return
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 24, 32, 24)
            addView(TextView(context).apply {
                text = "Reception could not load.\nReturn to the launcher and open Reception again."
                textSize = 20f
                setTextColor(Color.rgb(31, 49, 44))
                gravity = Gravity.CENTER
            })
            addView(Button(context).apply { text = "Back to launcher"; setOnClickListener { action(JSONObject().put("action", "exit")) } })
        }
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        browser.removeJavascriptInterface("ManzaniaNative")
        browser.stopLoading(); browser.onPause(); browser.destroy()
        waiting.clear(); removeAllViews()
    }
}
