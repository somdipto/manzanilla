package com.agentdeck.hotel

import android.app.Instrumentation
import android.app.KeyguardManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import com.agentdeck.ui.ReceptionView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Disposable emulator, both without a screen lock and with synthetic PIN 2468. No provider calls. */
internal object VoiceSetupFixture {
    fun verify(test: Instrumentation) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = test.targetContext
        val store = SecretStore(context, "fish")
        check(BuildConfig.FISH_API_KEY.isEmpty() && store.status() == SecretStore.Status.ABSENT)
        check(HotelConnection(context).load() == null)
        check(!TranslationSettings(context).directFishSelected)
        val secureDevice = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
        test.sendStatus(0, Bundle().apply { putString("stream", "VOICE_SETUP deviceSecure=$secureDevice\n") })
        test.uiAutomation.serviceInfo = test.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        fun windowRoot(packageName: String) = test.uiAutomation.windows.mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == packageName }
        fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
            val result = arrayListOf(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { result.addAll(descendants(it)) }
            return result
        }
        fun appNodes() = windowRoot(context.packageName)?.let(::descendants).orEmpty()
        fun keyField() = appNodes().singleOrNull {
            it.isEditable && it.contentDescription?.toString() == "New Fish Audio key, masked"
        }
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
        fun findSetupNode(stage: String, matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
            var found: AccessibilityNodeInfo? = null
            var direction = AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            await(stage) {
                // Android can omit off-screen ScrollView descendants entirely.
                // Re-read the real tree after every scroll until the target is
                // visible; one scroll does not cover this landscape form.
                val nodes = appNodes()
                found = nodes.firstOrNull(matches)
                val target = found
                if (target != null && target.isEnabled && target.isVisibleToUser) {
                    true
                } else {
                    val shown = target?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id) == true
                    if (!shown) {
                        fun scrollable() = nodes.firstOrNull { node ->
                            node.isScrollable && node.actionList.any { it.id == direction }
                        }
                        var scroller = scrollable()
                        if (scroller == null) {
                            direction = if (direction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            scroller = scrollable()
                        }
                        scroller?.performAction(direction)
                    }
                    test.waitForIdleSync()
                    false
                }
            }
            return checkNotNull(found) { "Setup control disappeared: $stage" }
        }
        val authenticated = KeyEntryActivity::class.java.getDeclaredField("authenticated").apply { isAccessible = true }
        fun personalSetupReady(setup: KeyEntryActivity, stage: String) {
            await(stage) {
                check(windowRoot("com.android.settings") == null) { "Personal translation setup requested a device credential" }
                keyField()?.let { it.isEnabled && it.isVisibleToUser } == true
            }
            check(authenticated.get(setup) == false) { "Personal translation setup granted Staff authorization" }
            check(appNodes().count { it.isEditable } == 1) { "Translation setup exposed unrelated hotel fields" }
            check(appNodes().none { it.text?.toString() == "Hotel service" })
        }
        fun enterKey(value: String) {
            val field = findSetupNode("visible editable Fish key field") {
                it.isEditable && it.contentDescription?.toString() == "New Fish Audio key, masked"
            }
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            })) { "Fish key field did not accept synthetic input" }
        }
        fun click(label: String) {
            val node = findSetupNode("visible setup button: $label") {
                // Material buttons expose their transformed ALL-CAPS text to accessibility.
                it.text?.toString()?.equals(label, ignoreCase = true) == true && it.isClickable
            }
            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Setup button did not respond: $label" }
        }
        fun readyTranslation() {
            await("ready Start button") { js("!!document.querySelector('[data-action=start]')") == "true" }
            check(js("document.querySelector('[data-action=start]').disabled") == "false")
            check(js("document.querySelector('#language').value") == "\"fr\"")
            check(js("document.querySelector('#staff_language').value") == "\"es\"")
            check(js("document.documentElement.dataset.captureActive") != "\"true\"")
            check(js("document.querySelectorAll('.setup-start').length") == "1")
            val owner = HotelActivity::class.java.getDeclaredField("owner").apply { isAccessible = true }
                .get(activity) as SessionOwner
            check(owner.current == null) { "Saving setup started a voice session" }
            check(TranslationSettings(context).directFishSelected) { "Direct Fish selection was not persisted" }
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
            personalSetupReady(cancelled, "personal key field before cancel")
            click("Cancel")
            await("cancelled setup closes") { cancelled.isFinishing || cancelled.isDestroyed }
            check(store.status() == SecretStore.Status.ABSENT)
            check(!TranslationSettings(context).directFishSelected) { "Cancel changed the voice route" }
            await("setup retry button") { js("!!document.querySelector('[data-action=setup-translation]')") == "true" }
            js("document.querySelector('[data-action=setup-translation]').click()")
            val setup = monitor.waitForActivityWithTimeout(15_000) as? KeyEntryActivity
                ?: error("Translation setup retry did not open")
            personalSetupReady(setup, "personal key field on retry")
            val syntheticKey = "fixture-only-not-a-provider-key"
            enterKey(syntheticKey)
            click("Save and return")
            await("saved setup closes") { setup.isFinishing || setup.isDestroyed }
            check(store.load() == syntheticKey)
            readyTranslation()

            // The translation gear must reopen this same personal setup even
            // after a key exists, without redirecting to Staff authentication.
            js("document.getElementById('settings').click()")
            val reopened = monitor.waitForActivityWithTimeout(15_000) as? KeyEntryActivity
                ?: error("Saved translation setup did not reopen")
            personalSetupReady(reopened, "saved-key personal setup")
            findSetupNode("visible saved-key status") { it.text?.toString()?.contains("a key is securely saved") == true }
            enterKey("fixture-unsaved-key-must-not-survive-recreation")
            // Recreate the actual Activity to exercise the same saved-instance
            // and resume path as a configuration change, without bypassing auth.
            test.runOnMainSync { reopened.recreate() }
            val recreated = monitor.waitForActivityWithTimeout(15_000) as? KeyEntryActivity
                ?: error("Personal setup did not recreate")
            check(recreated !== reopened)
            personalSetupReady(recreated, "recreated personal setup")
            check(store.load() == syntheticKey) { "Recreation changed the saved key" }
            // An empty Save must stay on setup: the unsaved input was discarded.
            click("Save and return")
            test.waitForIdleSync()
            check(!recreated.isFinishing && !recreated.isDestroyed) { "Unsaved key text survived recreation" }
            // Reuse the encrypted key without asking for a replacement. This
            // must also override a previously selected hotel voice route.
            check(TranslationSettings(context).selectHotelConnection())
            check(!TranslationSettings(context).directFishSelected)
            click("Use saved key")
            await("re-enrolled setup closes") { recreated.isFinishing || recreated.isDestroyed }
            check(store.load() == syntheticKey)
            readyTranslation()

            // Hotel assistance retains its separate Staff credential gate.
            js("document.querySelector('[data-action=back]').click()")
            await("home after translation") { js("!!document.querySelector('[data-action=translation]')") == "true" }
            js("document.getElementById('night-toggle').click()")
            await("hotel assistance Staff setup") {
                js("document.getElementById('night-toggle').getAttribute('aria-pressed')==='true' && document.getElementById('settings').getAttribute('aria-label')==='Staff setup'") == "true"
            }
            js("document.getElementById('settings').click()")
            val staff = monitor.waitForActivityWithTimeout(15_000) as? KeyEntryActivity
                ?: error("Staff setup did not open")
            if (secureDevice) {
                await("Staff credential confirmation") { windowRoot("com.android.settings") != null }
                check(authenticated.get(staff) == false && keyField() == null)
                shell("input keyevent 4")
                Thread.sleep(600)
                if (!staff.isFinishing && !staff.isDestroyed && windowRoot("com.android.settings") != null)
                    shell("input keyevent 4")
            } else {
                await("Staff lock enrollment requirement") {
                    appNodes().any { it.text?.toString()?.contains("no device screen-lock credential") == true }
                }
                check(authenticated.get(staff) == false && keyField() == null)
                click("Close")
            }
            await("Staff setup closes without authorization") { staff.isFinishing || staff.isDestroyed }
            check(store.load() == syntheticKey && TranslationSettings(context).directFishSelected)
        } finally {
            test.removeMonitor(monitor)
            test.runOnMainSync { activity.finish() }
        }
    }
}
