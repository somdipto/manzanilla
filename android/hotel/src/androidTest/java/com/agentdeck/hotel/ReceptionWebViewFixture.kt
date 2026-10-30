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

/** Exercises the shipped assets and real addJavascriptInterface boundary, with no audio provider. */
internal object ReceptionWebViewFixture {
    /** Synthetic readiness is withdrawn before permission resolves: granting cannot call a provider. */
    fun verifyMicrophonePermission(instrumentation: Instrumentation, outcome: String = "grant") {
        val context = instrumentation.targetContext
        check(!SecretStore(context, "fish").has() && !SecretStore(context, "translation_credentials").has()
            && HotelConnection(context).load() == null) { "Use an isolated, unconfigured key-free APK" }
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            "Revoke RECORD_AUDIO on the disposable emulator before this fixture"
        }
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val fixtureStore = SecretStore(context, "translation_credentials")
        check(TranslationCredentials(context).save("fixture-only-fish", "fixture-only-gemini", "fixture-only-voice"))
        val activity = instrumentation.startActivitySync(Intent(context, HotelActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val ownerField = HotelActivity::class.java.getDeclaredField("owner").apply { isAccessible = true }
        val permissionField = HotelActivity::class.java.getDeclaredField("pendingPermission").apply { isAccessible = true }
        val page = HotelActivity::class.java.getDeclaredField("reception").apply { isAccessible = true }.get(activity) as ReceptionView
        lateinit var browser: WebView
        val forward = HotelActivity::class.java.getDeclaredMethod("forward", JSONObject::class.java).apply { isAccessible = true }
        val transportEvents = LinkedBlockingQueue<JSONObject>()
        // Observe the real transport's credential gate after the permission decision.
        instrumentation.runOnMainSync {
            browser = page.getChildAt(0) as WebView
            HotelActivity::class.java.getDeclaredField("fishProvider").apply { isAccessible = true }.set(activity, lazy {
                FishVoice(context, { event ->
                    transportEvents.add(event)
                    activity.runOnUiThread { forward.invoke(activity, event) }
                }) { "" }
            })
        }
        fun evaluate(script: String) = evaluate(instrumentation, browser, script)
        fun permissionButton(allow: Boolean = true): AccessibilityNodeInfo? {
            val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
            if (root.packageName?.toString() == "android" &&
                root.findAccessibilityNodeInfosByViewId("android:id/immersive_cling_title").isNotEmpty()) {
                check(root.findAccessibilityNodeInfosByViewId("android:id/ok").firstOrNull()
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    "Cannot dismiss Android's first-fullscreen tutorial"
                }
                return null
            }
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
                page.push(JSONObject().put("type", "language_preferences").put("language", "en").put("staff_language", "es"))
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
            fixtureStore.clear()
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
            check(transportEvents.any { it.optString("type") == "reception_event" && it.optString("phase") == "error" } ==
                (outcome == "grant")) { "Permission $outcome did not gate the actual transport start" }
            check(transportEvents.none { it.optString("type") == "audio_state" && it.optString("state") == "recording" })
            if (outcome != "background") {
                check(evaluate("document.documentElement.dataset.captureActive") == "\"false\"") { "Permission result left JS capture active" }
                check(evaluate("!!document.querySelector('.notice.error')") == "true") { "Permission outcome lost its error state" }
                check(evaluate("document.querySelector('[data-action=restart],[data-action=retry]').disabled") == "false") { "Permission outcome is not retryable after microphone release" }
            }
        } finally {
            if (permissionButton() != null) instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            instrumentation.runOnMainSync { activity.finish() }
            fixtureStore.clear()
        }
    }

    private fun evaluate(instrumentation: Instrumentation, browser: WebView, script: String): String {
        val completed = CountDownLatch(1)
        var value = ""
        instrumentation.runOnMainSync { browser.evaluateJavascript(script) { value = it; completed.countDown() } }
        check(completed.await(15, TimeUnit.SECONDS)) { "WebView JavaScript callback timed out" }
        return value
    }

    fun verify(instrumentation: Instrumentation, truth: Boolean = false) {
        check(!SecretStore(instrumentation.targetContext, "translation_credentials").has()) { "Use an unconfigured fixture build" }
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, HotelActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val messages = LinkedBlockingQueue<JSONObject>()
        lateinit var page: ReceptionView
        lateinit var browser: WebView
        var unavailable = false
        instrumentation.runOnMainSync {
            // Replace, rather than overlay, the activity's existing WebView. Two
            // simultaneous engines obscure startup timing on software-only AVDs.
            val receptionField = HotelActivity::class.java.getDeclaredField("reception").apply { isAccessible = true }
            (receptionField.get(activity) as ReceptionView).dispose()
            page = ReceptionView(activity, { messages.offer(it) }) { unavailable = true }
            browser = page.getChildAt(0) as WebView
            if (truth) browser.settings.textZoom = 125
            receptionField.set(activity, page)
            activity.setContentView(page)
            // Exercise native events queued before page initialization, not a hand-written JS bridge.
            page.push(JSONObject().put("type", "reception_info").put("audio_available", true)
                .put("local_audio_state", "off").put("hotel_name", "Bridge fixture"))
            page.push(JSONObject().put("type", "language_preferences").put("language", if (truth) "es" else "fr").put("staff_language", "en"))
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
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (System.nanoTime() < deadline) {
                val message = messages.poll(250, TimeUnit.MILLISECONDS) ?: continue
                if (message.optString("action") == action) return message
            }
            error("Shipped WebView did not deliver native action: $action; page=${evaluate("document.body.innerText")}")
        }
        fun utterance(session: String, id: String, language: String, guest: String, staff: String, original: String): JSONObject {
            val known = language == guest || language == staff
            val role = if (!known) JSONObject.NULL else if (language == guest) "guest" else "receptionist"
            return JSONObject().put("type", "translation.utterance").put("record", JSONObject()
                .put("session_id", session).put("utterance_id", id).put("revision", 1)
                .put("role", role).put("source_language", if (known) language else JSONObject.NULL)
                .put("target_language", if (!known) JSONObject.NULL else if (language == guest) staff else guest)
                .put("original", original).put("translation", if (known) "204 · Alex" else "")
                .put("recognition_status", "final").put("translation_status", if (known) "complete" else "needs_direction")
                .put("playback_status", "idle").put("output_generation", 0)
                .put("direction_reason", if (known) "recognized" else "uncertain")
                .put("detected_language", if (known) language else JSONObject.NULL))
        }
        try {
            expectAction("info")
            check(!unavailable) { "Shipped assets failed to load" }
            // Initial info is sent while JS initializes; onPageFinished then flushes the native queue.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (evaluate("document.getElementById('hotel-label').textContent") != "\"Bridge fixture\"") {
                check(System.nanoTime() < deadline) { "Queued native startup events did not reach the page" }
                Thread.sleep(100)
            }
            if (truth) {
                check(evaluate("!!window.MANZANILLA_UI && window.MANZANILLA_UI.languages.length === 52") == "true") {
                    "The real native page did not load the complete offline catalogue"
                }
                check(evaluate("document.documentElement.lang") == "\"es\"") { "Truth fixture must exercise the Spanish interface" }
                check(evaluate("document.querySelector('.welcome h1').textContent") ==
                    JSONObject.quote(UiText.text(activity, "es", "Welcome."))) { "Spanish home did not render translated text" }
                check(evaluate("document.querySelector('[data-action=translation]').textContent.indexOf('Traducci') >= 0") == "true") {
                    "Truth fixture is not visibly Spanish"
                }
                check(messages.none { it.optString("action") == "start" }) { "Restoring a locale opened a session" }
            }
            check(evaluate("!!document.querySelector('[data-action=appearance]')") == "true")
            evaluate("document.querySelector('[data-action=appearance]').click()")
            check(expectAction("appearance").optString("value") in listOf("light", "dark"))
            evaluate("document.querySelector('[data-action=translation]').click()")
            check(evaluate("document.querySelector('[data-language-side=language]').value") == JSONObject.quote(if (truth) "es" else "fr"))
            check(evaluate("document.querySelector('[data-language-side=staff_language]').value") == "\"en\"")
            check(evaluate("document.querySelectorAll('#language option').length") == "28") { "Speech choices must not advertise all interface languages" }
            check(evaluate("document.querySelector('[data-action=start]').disabled") == "false") { "Start stays disabled after native capability/pair" }
            evaluate("document.querySelector('[data-action=start]').click()")
            val start = expectAction("start")
            check(start.optString("mode") == "translation")
            check(start.optString("language") == (if (truth) "es" else "fr") && start.optString("staff_language") == "en")
            check(UUID.fromString(start.getString("session_id")).toString() == start.getString("session_id"))
            check(evaluate("document.documentElement.dataset.page") == "\"active\"")
            if (truth) {
                fun push(event: JSONObject) = instrumentation.runOnMainSync {
                    page.push(event.put("session_id", start.getString("session_id")).put("generation", 1))
                }
                check(evaluate("document.querySelector('.voice-ball').dataset.hearing") == "\"false\"")
                check(evaluate("document.querySelector('.voice-ball').getAttribute('aria-label')") ==
                    JSONObject.quote(UiText.text(activity, "es", "Connecting · microphone off"))) { "Connecting incorrectly claims microphone capture" }
                // Both initial languages came from preferences, not explicit picker
                // choices: this tests the live-session gate, not selected[] flags.
                evaluate("window.initialTruthPair = document.querySelector('.pair-title').textContent")
                instrumentation.runOnMainSync {
                    page.push(JSONObject().put("type", "language_preferences").put("language", "ar").put("staff_language", "es"))
                }
                check(evaluate("window.initialTruthPair === document.querySelector('.pair-title').textContent") == "true") {
                    "Preference restoration changed a live conversation's language pair"
                }
                push(JSONObject().put("type", "reception_event").put("phase", "listening"))
                for ((index, language) in listOf("en", "und", "es", "und").withIndex()) {
                    push(utterance(start.getString("session_id"), "truth-$index", language, "es", "en", "  Alex, 204?  "))
                }
                check(evaluate("JSON.stringify(Array.prototype.map.call(document.querySelectorAll('.caption'),function(n){return n.classList.contains('caption-reception')?'staff':n.classList.contains('caption-guest')?'guest':'neutral'}))") ==
                    "\"[\\\"staff\\\",\\\"neutral\\\",\\\"guest\\\",\\\"neutral\\\"]\"")
                check(evaluate("document.querySelectorAll('.caption-pending .translated').length") == "0")
                evaluate("window.truthFirst = document.querySelector('.caption')")
                for ((audio, label) in listOf("muted" to "Unmute the microphone", "off" to "Microphone off",
                    "unknown" to "Microphone state unknown · ask staff")) {
                    push(JSONObject().put("type", "audio_state").put("state", "recording"))
                    push(JSONObject().put("type", "mic_level").put("level", 0.8)
                        .put("bands", org.json.JSONArray("[0.7,0.5,0.2]")))
                    check(evaluate("document.querySelector('.voice-ball').style.getPropertyValue('--level')") == "\"0.8\"")
                    push(JSONObject().put("type", "audio_state").put("state", audio))
                    fun checkMeter() {
                        check(evaluate("['--level','--b0','--b1','--b2'].every(function(k){return document.querySelector('.voice-ball').style.getPropertyValue(k)==='0'})") == "true")
                        check(evaluate("document.querySelector('.voice-ball').dataset.hearing") == "\"false\"")
                        check(evaluate("document.querySelector('.voice-ball').getAttribute('aria-label')") ==
                            JSONObject.quote(UiText.text(activity, "es", label))) { "Capture state has an incorrect localized accessible label" }
                        check(evaluate("window.truthFirst === document.querySelector('.caption')") == "true")
                    }
                    checkMeter()
                    instrumentation.runOnMainSync { page.push(JSONObject().put("type", "connection").put("connected", true)) }
                    checkMeter()
                    instrumentation.runOnMainSync {
                        page.push(JSONObject().put("type", "mic_level").put("session_id", start.getString("session_id"))
                            .put("generation", 0).put("level", 1))
                    }
                    checkMeter()
                }
                push(JSONObject().put("type", "reception_event").put("phase", "stopped"))
                push(JSONObject().put("type", "audio_state").put("state", "off"))
                check(evaluate("!!document.querySelector('.voice-ball')") == "false")
                check(evaluate("document.querySelector('[data-action=restart]').disabled") == "false")
                check(evaluate("document.querySelector('[data-action=restart]').textContent") ==
                    JSONObject.quote(UiText.text(activity, "es", "Try again"))) { "Stopped retry is not localized" }
                evaluate("document.querySelector('[data-action=back]').click()")
                val beforeSelection = messages.count { it.optString("action") == "start" }
                evaluate("""
                    var guest=document.querySelector('#language'), staff=document.querySelector('#staff_language');
                    guest.value='ar';guest.dispatchEvent(new Event('change',{bubbles:true}));
                    staff=document.querySelector('#staff_language');
                    staff.value='es';staff.dispatchEvent(new Event('change',{bubbles:true}));
                """.trimIndent())
                check(evaluate("document.documentElement.lang") == "\"ar\"")
                check(evaluate("document.documentElement.dir") == "\"rtl\"")
                check(evaluate("document.querySelector('[data-action=back]').textContent.indexOf(" +
                    JSONObject.quote(UiText.text(activity, "ar", "Back")) + ") >= 0") == "true") { "Arabic navigation text did not render" }
                check(messages.count { it.optString("action") == "start" } == beforeSelection) { "Choosing Arabic/Spanish opened a microphone" }
                evaluate("document.querySelector('[data-action=start]').click()")
                val arabic = expectAction("start")
                check(arabic.optString("language") == "ar" && arabic.optString("staff_language") == "es")
                fun arabicEvent(event: JSONObject) = instrumentation.runOnMainSync {
                    page.push(event.put("session_id", arabic.getString("session_id")).put("generation", 1))
                }
                arabicEvent(JSONObject().put("type", "audio_state").put("state", "recording"))
                arabicEvent(JSONObject().put("type", "reception_event").put("phase", "listening"))
                for ((id, language, original) in listOf(Triple("arabic-guest", "ar", "أين الغرفة 204؟"),
                    Triple("spanish-staff", "es", "Su habitación está a la izquierda."))) {
                    arabicEvent(utterance(arabic.getString("session_id"), id, language, "ar", "es", original))
                }
                check(evaluate("document.querySelector('.caption-guest').textContent.indexOf('أين الغرفة') >= 0") == "true")
                check(evaluate("document.querySelector('.caption-reception').textContent.indexOf('Su habitación') >= 0") == "true")
                check(evaluate("""
                    (function(){var g=document.querySelector('.caption-guest').getBoundingClientRect(),
                    s=document.querySelector('.caption-reception').getBoundingClientRect();
                    return g.left+g.width/2<s.left+s.width/2;})()
                """.trimIndent()) == "true") { "RTL swapped the physical guest-left/staff-right arrangement" }
                evaluate("window.truthPair = document.querySelector('.pair-title').textContent")
                instrumentation.runOnMainSync {
                    page.push(JSONObject().put("type", "language_preferences").put("language", "en").put("staff_language", "de"))
                }
                check(evaluate("window.truthPair === document.querySelector('.pair-title').textContent") == "true") {
                    "A native preference replay changed the live language pair"
                }
            }
        } finally {
            instrumentation.runOnMainSync { page.dispose(); activity.finish() }
        }
    }
}
