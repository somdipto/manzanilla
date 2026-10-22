package com.agentdeck.hotel

import android.app.Instrumentation
import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.widget.FrameLayout
import com.agentdeck.ui.ReceptionView
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises the shipped assets and real addJavascriptInterface boundary, with no audio provider. */
internal object ReceptionWebViewFixture {
    /** A real system permission dialog, with empty credentials so granting cannot call Fish. */
    fun verifyMicrophonePermission(instrumentation: Instrumentation, outcome: String = "grant") {
        val context = instrumentation.targetContext
        check(BuildConfig.FISH_API_KEY.isEmpty() && !SecretStore(context, "fish").has()
            && HotelConnection(context).load() == null) { "Use an isolated, unconfigured key-free APK" }
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            "Revoke RECORD_AUDIO on the disposable emulator before this fixture"
        }
        val activity = instrumentation.startActivitySync(Intent(context, HotelActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val ownerField = HotelActivity::class.java.getDeclaredField("owner").apply { isAccessible = true }
        val permissionField = HotelActivity::class.java.getDeclaredField("pendingPermission").apply { isAccessible = true }
        val page = HotelActivity::class.java.getDeclaredField("reception").apply { isAccessible = true }.get(activity) as ReceptionView
        lateinit var browser: WebView
        val forward = HotelActivity::class.java.getDeclaredMethod("forward", JSONObject::class.java).apply { isAccessible = true }
        val keyReads = AtomicInteger()
        // Observe that granting reaches the real Fish start, while ensuring that it has no credential.
        instrumentation.runOnMainSync {
            browser = page.getChildAt(0) as WebView
            HotelActivity::class.java.getDeclaredField("fishProvider").apply { isAccessible = true }.set(activity, lazy {
                FishVoice(context, { event -> activity.runOnUiThread { forward.invoke(activity, event) } }) {
                    keyReads.incrementAndGet()
                    ""
                }
            })
        }
        fun evaluate(script: String) = evaluate(instrumentation, browser, script)
        fun permissionButton(allow: Boolean = true): AccessibilityNodeInfo? {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
            for (pkg in listOf("com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.packageinstaller")) {
                for (id in if (allow) listOf("permission_allow_foreground_only_button", "permission_allow_button") else listOf("permission_deny_button")) {
                    val node = root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").firstOrNull()
                    if (node != null) return node
                }
            }
            return null
        }
        try {
            val loadedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (evaluate("typeof window.manzania === 'object'") != "true") {
                check(System.nanoTime() < loadedDeadline) { "Reception did not initialize" }
                Thread.sleep(100)
            }
            instrumentation.runOnMainSync {
                page.push(JSONObject().put("type", "reception_info").put("audio_available", true).put("local_audio_state", "off"))
                page.push(JSONObject().put("type", "language_preferences").put("language", "fr").put("staff_language", "en"))
            }
            evaluate("document.querySelector('[data-action=translation]').click()")
            check(evaluate("document.querySelector('[data-action=start]').disabled") == "false")
            evaluate("document.querySelector('[data-action=start]').click()")
            var button: AccessibilityNodeInfo? = null
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (button == null && System.nanoTime() < deadline) {
                button = permissionButton()
                if (button == null) Thread.sleep(100)
            }
            check(button != null) { "System microphone permission dialog did not appear" }
            var preserved = false
            instrumentation.runOnMainSync {
                preserved = permissionField.get(activity) != null && (ownerField.get(activity) as SessionOwner).current?.request?.mode == "translation"
            }
            check(preserved) { "Opening microphone permission discarded the pending translation session" }
            check(evaluate("document.documentElement.dataset.page") == "\"active\"") { "Permission dialog detached the JS conversation" }
            check(evaluate("document.documentElement.dataset.captureActive") == "\"true\"")
            if (outcome == "background") {
                check(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
            } else {
                check((if (outcome == "deny") permissionButton(false) else button)?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    "Cannot tap system permission $outcome"
                }
            }
            val resultDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (outcome == "grant" && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                check(System.nanoTime() < resultDeadline) { "Microphone grant did not complete" }
                Thread.sleep(100)
            }
            var released = false
            while (!released) {
                instrumentation.runOnMainSync {
                    released = permissionField.get(activity) == null && (ownerField.get(activity) as SessionOwner).current == null
                }
                // The real Fish start reaches its missing-credential gate and releases the session.
                check(System.nanoTime() < resultDeadline) { "Credential-free permission start stayed active" }
                if (!released) Thread.sleep(100)
            }
            check(keyReads.get() == if (outcome == "grant") 1 else 0) { "Permission $outcome did not start exactly once or started when denied/backgrounded" }
            if (outcome != "background") {
                check(evaluate("document.documentElement.dataset.captureActive") == "\"false\"") { "Permission result left JS capture active" }
                val expected = if (outcome == "deny") "Microphone permission is required" else "configure or recover"
                check(evaluate("document.body.innerText.indexOf('$expected') >= 0") == "true") { "Permission result lost its error detail" }
            }
        } finally {
            if (permissionButton() != null) instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun evaluate(instrumentation: Instrumentation, browser: WebView, script: String): String {
        val completed = CountDownLatch(1)
        var value = ""
        instrumentation.runOnMainSync { browser.evaluateJavascript(script) { value = it; completed.countDown() } }
        check(completed.await(15, TimeUnit.SECONDS)) { "WebView JavaScript callback timed out" }
        return value
    }

    fun verify(instrumentation: Instrumentation) {
        check(BuildConfig.FISH_API_KEY.isEmpty()) { "Use a key-free fixture build" }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, HotelActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val messages = LinkedBlockingQueue<JSONObject>()
        lateinit var page: ReceptionView
        lateinit var browser: WebView
        var unavailable = false
        instrumentation.runOnMainSync {
            page = ReceptionView(activity, { messages.offer(it) }) { unavailable = true }
            browser = page.getChildAt(0) as WebView
            activity.addContentView(page, FrameLayout.LayoutParams(-1, -1))
            // Exercise native events queued before page initialization, not a hand-written JS bridge.
            page.push(JSONObject().put("type", "reception_info").put("audio_available", true)
                .put("local_audio_state", "off").put("hotel_name", "Bridge fixture"))
            page.push(JSONObject().put("type", "language_preferences").put("language", "fr").put("staff_language", "en"))
            page.setShown(true)
        }
        fun evaluate(script: String): String {
            val completed = CountDownLatch(1)
            var value = ""
            instrumentation.runOnMainSync { browser.evaluateJavascript(script) { value = it; completed.countDown() } }
            check(completed.await(10, TimeUnit.SECONDS)) { "WebView JavaScript callback timed out" }
            return value
        }
        fun expectAction(action: String): JSONObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline) {
                val message = messages.poll(250, TimeUnit.MILLISECONDS) ?: continue
                if (message.optString("action") == action) return message
            }
            error("Shipped WebView did not deliver native action: $action; page=${evaluate("document.body.innerText")}")
        }
        try {
            expectAction("info")
            check(!unavailable) { "Shipped assets failed to load" }
            // Initial info is sent while JS initializes; onPageFinished then flushes the native queue.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (evaluate("document.getElementById('hotel-label').textContent") != "\"Bridge fixture\"") {
                check(System.nanoTime() < deadline) { "Queued native startup events did not reach the page" }
                Thread.sleep(100)
            }
            check(evaluate("!!document.querySelector('[data-action=appearance]')") == "true")
            evaluate("document.querySelector('[data-action=appearance]').click()")
            check(expectAction("appearance").optString("value") in listOf("light", "dark"))
            evaluate("document.querySelector('[data-action=translation]').click()")
            check(evaluate("document.querySelector('[data-language-side=language]').value") == "\"fr\"")
            check(evaluate("document.querySelector('[data-language-side=staff_language]').value") == "\"en\"")
            check(evaluate("document.querySelector('[data-action=start]').disabled") == "false") { "Start stays disabled after native capability/pair" }
            evaluate("document.querySelector('[data-action=start]').click()")
            val start = expectAction("start")
            check(start.optString("mode") == "translation")
            check(start.optString("language") == "fr" && start.optString("staff_language") == "en")
            check(UUID.fromString(start.getString("session_id")).toString() == start.getString("session_id"))
            check(evaluate("document.documentElement.dataset.page") == "\"active\"")
            val notice = FishStartupFailure.describe(FishStartupFailure.Stage.CAPTION_CREATE,
                FishStartupFailure.http(FishStartupFailure.Stage.CAPTION_CREATE, 402), false)
            instrumentation.runOnMainSync {
                page.push(JSONObject().put("type", "audio_state").put("state", "off")
                    .put("session_id", start.getString("session_id")).put("generation", 71))
                page.push(JSONObject().put("type", "reception_event").put("phase", "error").put("text", notice.text)
                    .put("session_id", start.getString("session_id")).put("generation", 71))
            }
            check(evaluate("document.body.innerText.indexOf('${notice.reference}') >= 0") == "true") {
                "startup failure reference was lost at the native/WebView boundary"
            }
            check(evaluate("document.documentElement.dataset.captureActive") == "\"false\"")
            // JavaScript completion precedes WebView/compositor painting. Wait for
            // the current DOM state and two display frames before capturing evidence.
            val painted = CountDownLatch(1)
            instrumentation.runOnMainSync {
                browser.postVisualStateCallback(71L, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        browser.postOnAnimation { browser.postOnAnimation { painted.countDown() } }
                    }
                })
            }
            check(painted.await(15, TimeUnit.SECONDS)) { "WebView did not paint the diagnostic frame before screenshot" }
            instrumentation.uiAutomation.takeScreenshot()?.let { screenshot ->
                val image = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "startup-reference-fixture.png")
                image.outputStream().use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
        } finally {
            instrumentation.runOnMainSync { page.dispose(); activity.finish() }
        }
    }
}
