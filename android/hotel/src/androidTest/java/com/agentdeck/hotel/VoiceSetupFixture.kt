package com.agentdeck.hotel

import android.app.Instrumentation
import android.app.KeyguardManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.widget.EditText
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
        var setupWindowId: Int? = null
        fun windowRoot(packageName: String, windowId: Int? = null) = test.uiAutomation.windows
            .filter { windowId == null || it.id == windowId }.mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == packageName }
        fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
            val result = arrayListOf(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { result.addAll(descendants(it)) }
            return result
        }
        fun appNodes() = windowRoot(context.packageName, setupWindowId)?.let(::descendants).orEmpty()
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
        val foreground = KeyEntryActivity::class.java.getDeclaredField("foreground").apply { isAccessible = true }
        val fields = KeyEntryActivity::class.java.getDeclaredField("fields").apply { isAccessible = true }
        val previousSetups = mutableSetOf<KeyEntryActivity>()
        fun openSetup(stage: String, personal: Boolean = true, open: () -> Unit): KeyEntryActivity {
            // ActivityMonitor matches both onCreate and onResume. Reusing one
            // across launches can return an already destroyed older Activity.
            val monitor = test.addMonitor(KeyEntryActivity::class.java.name, null, false)
            setupWindowId = null
            var candidate: KeyEntryActivity? = null
            try {
                open()
                await(stage) {
                    val observed = monitor.waitForActivityWithTimeout(100) as? KeyEntryActivity
                    if (observed != null && observed !in previousSetups) candidate = observed
                    var ready = false
                    candidate?.let { setup ->
                        test.runOnMainSync {
                            ready = !setup.isDestroyed && !setup.isFinishing && setup !in previousSetups &&
                                (!personal || (foreground.getBoolean(setup) && setup.hasWindowFocus() &&
                                    setup.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE))
                        }
                    }
                    ready
                }
                return checkNotNull(candidate).also { previousSetups.add(it) }
            } finally {
                test.removeMonitor(monitor)
            }
        }
        fun inputLength(setup: KeyEntryActivity): Int {
            var length = -1
            test.runOnMainSync {
                @Suppress("UNCHECKED_CAST")
                val inputs = fields.get(setup) as List<EditText>
                check(inputs.size == 1) { "Personal setup must own exactly one key field" }
                length = inputs.single().text.length
            }
            return length
        }
        fun personalSetupReady(setup: KeyEntryActivity, stage: String) {
            await(stage) {
                check(windowRoot("com.android.settings") == null) { "Personal translation setup requested a device credential" }
                var resumed = false
                test.runOnMainSync {
                    @Suppress("UNCHECKED_CAST")
                    val input = (fields.get(setup) as List<EditText>).singleOrNull()
                    resumed = !setup.isDestroyed && !setup.isFinishing && foreground.getBoolean(setup) &&
                        setup.hasWindowFocus() && input?.isAttachedToWindow == true
                    if (resumed) {
                        val node = input!!.createAccessibilityNodeInfo()
                        setupWindowId = node.windowId
                        node.recycle()
                    }
                }
                resumed && keyField()?.let { it.isEnabled && it.isVisibleToUser } == true
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
        try {
            await("initial page") { js("!!document.querySelector('[data-action=translation]')") == "true" }
            js("document.querySelector('[data-action=translation]').click()")
            js("document.querySelector('#language').value='fr'; document.querySelector('#language').dispatchEvent(new Event('change',{bubbles:true}))")
            js("document.querySelector('#staff_language').value='es'; document.querySelector('#staff_language').dispatchEvent(new Event('change',{bubbles:true}))")
            check(js("document.querySelectorAll('.setup-start').length") == "1")
            check(js("!!document.querySelector('[data-action=start]')") == "false")
            val cancelled = openSetup("personal setup launch") {
                js("document.querySelector('[data-action=setup-translation]').click()")
            }
            personalSetupReady(cancelled, "personal key field before cancel")
            click("Cancel")
            await("cancelled setup closes") { cancelled.isFinishing || cancelled.isDestroyed }
            check(store.status() == SecretStore.Status.ABSENT)
            check(!TranslationSettings(context).directFishSelected) { "Cancel changed the voice route" }
            await("setup retry button") { js("!!document.querySelector('[data-action=setup-translation]')") == "true" }
            val setup = openSetup("personal setup retry launch") {
                js("document.querySelector('[data-action=setup-translation]').click()")
            }
            personalSetupReady(setup, "personal key field on retry")
            val syntheticKey = "fixture-only-not-a-provider-key"
            enterKey(syntheticKey)
            click("Save and return")
            await("saved setup closes") { setup.isFinishing || setup.isDestroyed }
            check(store.load() == syntheticKey)
            readyTranslation()

            // The translation gear must reopen this same personal setup even
            // after a key exists, without redirecting to Staff authentication.
            val reopened = openSetup("saved-key personal setup launch") {
                js("document.getElementById('settings').click()")
            }
            personalSetupReady(reopened, "saved-key personal setup")
            findSetupNode("visible saved-key status") { it.text?.toString()?.contains("a key is securely saved") == true }
            val unsavedKey = "fixture-unsaved-key-must-not-survive-recreation"
            enterKey(unsavedKey)
            check(inputLength(reopened) == unsavedKey.length) { "Synthetic input did not reach the current setup Activity" }
            // Recreate the actual Activity to exercise the same saved-instance
            // and resume path as a configuration change, without bypassing auth.
            val recreated = openSetup("personal setup recreation") {
                test.runOnMainSync { reopened.recreate() }
            }
            check(recreated !== reopened)
            await("previous setup destroyed after recreation") { reopened.isDestroyed }
            personalSetupReady(recreated, "recreated personal setup")
            val recreatedLength = inputLength(recreated)
            check(recreatedLength == 0) { "Recreated Fish key field retained $recreatedLength characters" }
            check(store.load() == syntheticKey) { "Recreation changed the saved key" }
            // An empty Save must stay on setup: the unsaved input was discarded.
            click("Save and return")
            test.waitForIdleSync()
            check(!recreated.isFinishing && !recreated.isDestroyed && inputLength(recreated) == 0) {
                "Empty Save unexpectedly closed or changed the recreated personal setup"
            }
            check(store.load() == syntheticKey) { "Empty Save changed the saved key" }
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
            val staff = openSetup("Staff setup launch", personal = false) {
                js("document.getElementById('settings').click()")
            }
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
            test.runOnMainSync { activity.finish() }
        }
    }
}
