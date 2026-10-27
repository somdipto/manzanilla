package com.agentdeck.hotel

import android.app.Instrumentation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.widget.EditText
import com.agentdeck.ui.ReceptionView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Disposable emulator with the synthetic PIN 2468. No provider or microphone calls. */
internal object VoiceSetupFixture {
    fun verify(test: Instrumentation) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = test.targetContext
        val store = SecretStore(context, "fish")
        check(BuildConfig.FISH_API_KEY.isEmpty() && store.status() == SecretStore.Status.ABSENT)
        check(HotelConnection(context).load() == null)
        test.uiAutomation.serviceInfo = test.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        fun windowRoot(packageName: String) = test.uiAutomation.windows.mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == packageName }
        val activity = test.startActivitySync(Intent(context, HotelActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val reception = HotelActivity::class.java.getDeclaredField("reception").apply { isAccessible = true }
            .get(activity) as ReceptionView
        val browser = reception.getChildAt(0) as WebView
        fun js(script: String): String {
            val ready = CountDownLatch(1)
            var value = ""
            test.runOnMainSync { browser.evaluateJavascript(script) { value = it; ready.countDown() } }
            check(ready.await(15, TimeUnit.SECONDS)) { "Setup WebView callback timed out" }
            return value
        }
        fun await(stage: String, checkState: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!checkState()) {
                check(System.nanoTime() < deadline) { "Setup flow timed out: $stage" }
                Thread.sleep(100)
            }
        }
        fun shell(command: String) {
            test.uiAutomation.executeShellCommand(command).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
        }
        val monitor = test.addMonitor(KeyEntryActivity::class.java.name, null, false)
        try {
            await("initial page") { js("!!document.querySelector('[data-action=translation]')") == "true" }
            js("document.querySelector('[data-action=translation]').click()")
            js("document.querySelector('#language').value='fr'; document.querySelector('#language').dispatchEvent(new Event('change',{bubbles:true}))")
            js("document.querySelector('#staff_language').value='es'; document.querySelector('#staff_language').dispatchEvent(new Event('change',{bubbles:true}))")
            check(js("document.querySelectorAll('.setup-start').length") == "1")
            check(js("!!document.querySelector('[data-action=start]')") == "false")
            js("document.querySelector('[data-action=setup-translation]').click()")
            val cancelled = monitor.waitForActivityWithTimeout(15_000) as? KeyEntryActivity
                ?: error("Translation setup did not open")
            val authenticated = KeyEntryActivity::class.java.getDeclaredField("authenticated").apply { isAccessible = true }
            check(authenticated.get(cancelled) == false) { "Setup bypassed device authentication" }
            await("cancel credential screen") { windowRoot("com.android.settings") != null }
            shell("input keyevent 4")
            // Back first dismisses the PIN keyboard on some Android images.
            Thread.sleep(600)
            if (!cancelled.isFinishing && !cancelled.isDestroyed && windowRoot("com.android.settings") != null)
                shell("input keyevent 4")
            await("cancelled setup closes") { cancelled.isFinishing || cancelled.isDestroyed }
            check(store.status() == SecretStore.Status.ABSENT)
            await("setup retry button") { js("!!document.querySelector('[data-action=setup-translation]')") == "true" }
            // Retry launch: use a FRESH monitor plus the original one. CI run 40577058119
            // showed the original monitor's waitForActivityWithTimeout can return the stale
            // cancelled instance; run 40582626895 showed the same monitor can also miss the
            // retry hit entirely on the slow emulator. KeyEntryDiag proves the retry instance
            // itself launches and authenticates correctly. Poll both monitors for a non-stale
            // instance and fail loudly if neither observes it.
            val retryMonitor = test.addMonitor(KeyEntryActivity::class.java.name, null, true)
            js("document.querySelector('[data-action=setup-translation]').click()")
            var setup: KeyEntryActivity? = null
            await("retry setup activity") {
                val fresh = retryMonitor.waitForActivityWithTimeout(150) as? KeyEntryActivity
                val seen = (fresh ?: monitor.lastActivity) as? KeyEntryActivity
                if (seen != null && seen !== cancelled) setup = seen
                setup != null
            }
            if (setup == null) error("Translation setup retry did not open")
            // Wait for Android's real credential screen before typing its test PIN.
            await("retry credential screen") { windowRoot("com.android.settings") != null }
            var pin: AccessibilityNodeInfo? = null
            await("focused Android PIN field") {
                pin = windowRoot("com.android.settings")
                    ?.findAccessibilityNodeInfosByViewId("com.android.settings:id/password_entry")?.firstOrNull()
                pin?.let { it.isEditable && it.isEnabled && it.isFocused } == true
            }
            // The window can exist before its PIN field gains focus. Set text
            // through Android's editable UI action, then submit normally; the
            // actual system credential verifier must still accept the test PIN.
            check(pin!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "2468")
            })) { "Android PIN field did not accept the test input" }
            shell("input keyevent 66")
            await("PIN accepted") { authenticated.get(setup) == true }
            val fields = KeyEntryActivity::class.java.getDeclaredField("fields").apply { isAccessible = true }
            test.runOnMainSync {
                @Suppress("UNCHECKED_CAST")
                val inputs = fields.get(setup) as List<EditText>
                check(inputs.size == 1) { "Translation setup exposed unrelated hotel fields" }
                inputs.single().setText("fixture-only-not-a-provider-key")
            }
            val save = windowRoot(context.packageName)?.findAccessibilityNodeInfosByText("Save and return")
                ?.firstOrNull() ?: error("Save and return is missing")
            check(save.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            await("saved setup closes") { setup.isFinishing || setup.isDestroyed }
            check(store.load() == "fixture-only-not-a-provider-key")
            await("ready Start button") { js("!!document.querySelector('[data-action=start]')") == "true" }
            check(js("document.querySelector('[data-action=start]').disabled") == "false")
            check(js("document.querySelector('#language').value") == "\"fr\"")
            check(js("document.querySelector('#staff_language').value") == "\"es\"")
            check(js("document.documentElement.dataset.captureActive") != "\"true\"")
            check(js("document.querySelectorAll('.setup-start').length") == "1")
        } finally {
            test.removeMonitor(monitor)
            test.runOnMainSync { activity.finish() }
        }
    }
}
