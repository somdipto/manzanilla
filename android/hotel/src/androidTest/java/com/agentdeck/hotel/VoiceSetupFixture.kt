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
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Disposable emulator, both without a screen lock and with synthetic PIN 2468. No provider calls. */
internal object VoiceSetupFixture {
    fun verify(test: Instrumentation) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(BuildConfig.DISTRIBUTION == "personal-test") { "Personal setup requires a personal-test fixture" }
        val context = test.targetContext
        val store = SecretStore(context, "fish")
        check(BuildConfig.FISH_API_KEY.isEmpty() && BuildConfig.FISH_VOICE_ID.isEmpty() &&
            store.status() == SecretStore.Status.ABSENT)
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
            it.isEditable && it.isPassword
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
        }
        fun enterKey(value: String) {
            val field = findSetupNode("visible editable Fish key field") {
                it.isEditable && it.isPassword
            }
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            })) { "Fish key field did not accept synthetic input" }
        }
        fun click(label: String) {
            val language = context.getSharedPreferences("deck", 0).getString("reception_staff_language", "es").orEmpty()
            val localized = UiText.text(context, language, label)
            val node = findSetupNode("visible setup button: $label") {
                // Material buttons expose their transformed ALL-CAPS text to accessibility.
                it.text?.toString()?.equals(localized, ignoreCase = true) == true && it.isClickable
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
                js("document.getElementById('night-toggle').getAttribute('aria-pressed')==='true'") == "true"
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

                click("Close")
                check(authenticated.get(staff) == false && (fields.get(staff) as List<*>).isEmpty())
            }
            await("Staff setup closes without authorization") { staff.isFinishing || staff.isDestroyed }
            check(store.load() == syntheticKey && TranslationSettings(context).directFishSelected)
        } finally {
            test.runOnMainSync { activity.finish() }
        }
    }

    private fun verifyRestoredCleanup(test: Instrumentation, personalKey: String) {
        val context = test.targetContext
        val personal = SecretStore(context, "fish")
        val meta = context.getSharedPreferences("fishmeta", 0)
        val shared = FishVoice::class.java.getDeclaredField("sharedAdmission").apply { isAccessible = true }
        val previousAdmission = shared.get(null)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        var activity: HotelActivity? = null
        try {
            check(meta.edit().putString("outstanding_session", "fixture-old-direct:900")
                .putString("pending_end_url", "https://api.fish.audio/v1/agent/sessions/fixture-old/end")
                .putString("pending_end_credential_hash", "fixture-wrong-hash").commit())
            shared.set(null, null)
            val current = test.startActivitySync(Intent(context, HotelActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as HotelActivity
            activity = current
            val provider = HotelActivity::class.java.getDeclaredField("fishProvider").apply { isAccessible = true }
                .get(current) as Lazy<*>
            val recovery = provider.value as FishVoice
            val http = FishVoice::class.java.getDeclaredField("http").apply { isAccessible = true }
            http.set(recovery, (http.get(recovery) as okhttp3.OkHttpClient).newBuilder().addInterceptor { chain ->
                check(chain.request().method == "POST" && chain.request().url.encodedPath.endsWith("/fixture-old/end"))
                check(chain.request().header("Authorization") == "Bearer $personalKey")
                requests.incrementAndGet()
                okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(204).message("No Content").body("".toResponseBody(null)).build()
            }.build())
            check(meta.contains("outstanding_session") && requests.get() == 0)
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(personalKey.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            check(meta.edit().putString("pending_end_credential_hash", hash).commit())
            test.runOnMainSync {
                test.callActivityOnPause(current)
                test.callActivityOnResume(current)
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (meta.contains("outstanding_session")) {
                check(System.nanoTime() < deadline) { "Foreground hotel recovery stranded the original direct reservation" }
                Thread.sleep(50)
            }
            val owner = HotelActivity::class.java.getDeclaredField("owner").apply { isAccessible = true }
                .get(current) as SessionOwner
            check(requests.get() == 1 && personal.load() == personalKey && owner.current == null)
            check(HotelConnection(context).load() == null)
        } finally {
            test.runOnMainSync { activity?.finish() }
            test.waitForIdleSync()
            shared.set(null, previousAdmission)
            meta.edit().remove("outstanding_session").remove("pending_end_url")
                .remove("pending_end_credential_hash").commit()
        }
    }

    /** Owns only synthetic data and PIN 2468 on a fresh, initially lock-free hotel AVD. */
    fun verifyHotel(test: Instrumentation) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(BuildConfig.DEBUG && BuildConfig.DISTRIBUTION == "hotel")
        check(BuildConfig.FISH_API_KEY.isEmpty() && BuildConfig.FISH_VOICE_ID.isEmpty())
        val context = test.targetContext
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        check(!keyguard.isDeviceSecure) { "Hotel setup fixture requires an initially lock-free disposable AVD" }
        val personal = SecretStore(context, "fish")
        val connection = HotelConnection(context)
        val meta = context.getSharedPreferences("fishmeta", 0)
        check(personal.status() == SecretStore.Status.ABSENT && connection.load() == null)
        check(!meta.contains("outstanding_session"))
        check(context.getSharedPreferences("deck", 0).edit()
            .putString("reception_guest_language", "en").putString("reception_staff_language", "en").commit())
        val personalKey = "fixture-retained-personal-key-not-a-provider-credential"
        check(personal.save(personalKey) && TranslationSettings(context).selectDirectFish())
        fun await(stage: String, state: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!state()) {
                check(System.nanoTime() < deadline) { "Hotel setup timed out: $stage" }
                Thread.sleep(100)
            }
        }
        fun shell(command: String) {
            test.uiAutomation.executeShellCommand(command).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
            }
        }
        // Recover the original reservation through the real Activity foreground path,
        // even before enrollment is available. No new provider session may be opened.
        verifyRestoredCleanup(test, personalKey)
        // Exercise the real new-start boundary even with a saved personal selection.
        // Interceptors prevent a regression from making any provider request.
        shell("pm grant ${context.packageName} android.permission.RECORD_AUDIO")
        val keyReads = java.util.concurrent.atomic.AtomicInteger()
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val events = java.util.Collections.synchronizedList(ArrayList<org.json.JSONObject>())
        val fish = FishVoice(context, { events.add(it) }) { keyReads.incrementAndGet(); personalKey }
        for (name in listOf("http", "sessionHttp")) {
            val field = FishVoice::class.java.getDeclaredField(name).apply { isAccessible = true }
            val client = field.get(fish) as okhttp3.OkHttpClient
            field.set(fish, client.newBuilder().addInterceptor {
                requests.incrementAndGet()
                throw java.io.IOException("Fixture blocks provider access")
            }.build())
        }
        test.runOnMainSync { fish.start("en", "es", sessionId = "hotel-no-direct", epoch = 901) }
        await("hotel new start denied") {
            synchronized(events) { events.any { it.optString("phase") == "error" &&
                it.optString("text") == UiText.text(context, "en", "Translation needs hotel setup. Please ask staff.") } } &&
                fish.admissionIssue() == null
        }
        check(keyReads.get() == 0 && requests.get() == 0) { "Hotel new start consulted a personal credential or provider" }
        check(personal.load() == personalKey && TranslationSettings(context).directFishSelected)
        test.uiAutomation.serviceInfo = test.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val activity = test.startActivitySync(Intent(context, HotelActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val reception = HotelActivity::class.java.getDeclaredField("reception").apply { isAccessible = true }
            .get(activity) as ReceptionView
        val browser = reception.getChildAt(0) as WebView
        val authenticated = KeyEntryActivity::class.java.getDeclaredField("authenticated").apply { isAccessible = true }
        val foreground = KeyEntryActivity::class.java.getDeclaredField("foreground").apply { isAccessible = true }
        val fields = KeyEntryActivity::class.java.getDeclaredField("fields").apply { isAccessible = true }
        val openSetups = ArrayList<KeyEntryActivity>()
        fun inputs(setup: KeyEntryActivity): List<EditText> {
            @Suppress("UNCHECKED_CAST")
            return fields.get(setup) as List<EditText>
        }
        fun js(script: String): String {
            val ready = CountDownLatch(1)
            var value = ""
            test.runOnMainSync { browser.evaluateJavascript(script) { value = it; ready.countDown() } }
            check(ready.await(15, TimeUnit.SECONDS))
            return value
        }
        fun open(action: () -> Unit): KeyEntryActivity {
            val monitor = test.addMonitor(KeyEntryActivity::class.java.name, null, false)
            return try {
                action()
                (monitor.waitForActivityWithTimeout(15_000) as? KeyEntryActivity
                    ?: error("Hotel setup did not open")).also(openSetups::add)
            } finally { test.removeMonitor(monitor) }
        }
        fun nodes(view: android.view.View): List<android.view.View> {
            val result = arrayListOf(view)
            if (view is android.view.ViewGroup) for (index in 0 until view.childCount)
                result.addAll(nodes(view.getChildAt(index)))
            return result
        }
        fun click(setup: KeyEntryActivity, label: String) {
            test.runOnMainSync {
                val button = nodes(setup.window.decorView).filterIsInstance<android.widget.Button>()
                    .single { it.text.toString().equals(label, ignoreCase = true) }
                check(button.isEnabled && button.performClick())
            }
            test.waitForIdleSync()
        }
        fun locked(setup: KeyEntryActivity) {
            await("locked setup has no fields") {
                var result = false
                test.runOnMainSync { result = !authenticated.getBoolean(setup) && inputs(setup).isEmpty() }
                result
            }
        }
        fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
            val result = arrayListOf(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let { result.addAll(descendants(it)) }
            return result
        }
        fun authenticate(setup: KeyEntryActivity) {
            locked(setup)
            await("real Android credential prompt") {
                test.uiAutomation.windows.mapNotNull { it.root }.any { root ->
                    root.packageName?.toString() in setOf("com.android.settings", "com.android.systemui") &&
                        descendants(root).any { it.isEditable && it.isVisibleToUser }
                }
            }
            shell("input text 2468")
            shell("input keyevent 66")
            await("authenticated enrollment form") {
                var result = false
                test.runOnMainSync {
                    result = authenticated.getBoolean(setup) && foreground.getBoolean(setup) && inputs(setup).size == 2
                }
                result
            }
            test.runOnMainSync {
                check(nodes(setup.window.decorView).filterIsInstance<android.widget.TextView>()
                    .none { it.text.toString() == "Fish Audio" })
                check(inputs(setup).none { it.contentDescription?.toString() == "New Fish Audio key, masked" })
            }
        }
        var syntheticPin = false
        try {
            await("hotel packaged page") { js("!!document.querySelector('[data-action=translation]')") == "true" }
            // Both guest bridge entry points and an explicit voice_only extra must fail closed without a lock.
            for (route in listOf("settings", "voice_setup", "intent")) {
                val setup = open {
                    if (route == "intent") context.startActivity(Intent(context, KeyEntryActivity::class.java)
                        .putExtra("voice_only", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else js("window.ManzaniaNative.postMessage(JSON.stringify({action:'$route'}))")
                }
                locked(setup)
                await("no-lock hotel setup refusal") {
                    var refused = false
                    test.runOnMainSync {
                        refused = nodes(setup.window.decorView).filterIsInstance<android.widget.TextView>()
                            .any { it.text.toString().contains("no device screen-lock credential") }
                    }
                    refused
                }
                click(setup, "Close")
                await("no-lock setup closes") { setup.isFinishing || setup.isDestroyed }
            }
            shell("locksettings set-pin 2468")
            syntheticPin = true
            await("synthetic device credential enrolled") { keyguard.isDeviceSecure }
            val setup = open { js("window.ManzaniaNative.postMessage(JSON.stringify({action:'voice_setup'}))") }
            authenticate(setup)
            // Replacing or clearing enrollment cannot strand the original remote-cleanup credential.
            val originalToken = "fixture-original-device-token-000000000000"
            check(connection.save("https://127.0.0.1:1", originalToken))
            check(meta.edit().putString("outstanding_session", "fixture-owned:902")
                .putString("pending_end_url", "https://127.0.0.1:1/api/device/fish-session/fixture/end")
                .putString("pending_end_credential_hash", "fixture-original-hash").commit())
            val originalMetadata = meta.all.toMap()
            test.runOnMainSync {
                inputs(setup)[0].setText("https://127.0.0.1:2")
                inputs(setup)[1].setText("fixture-replacement-device-token-000000000")
            }
            click(setup, "Save Hotel connection key")
            check(connection.load()?.token == originalToken && meta.all == originalMetadata)
            click(setup, "Remove Hotel connection key")
            test.runOnMainSync {
                val dialog = KeyEntryActivity::class.java.getDeclaredField("dialog").apply { isAccessible = true }
                    .get(setup) as android.app.AlertDialog
                check(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick())
            }
            check(connection.load()?.token == originalToken && meta.all == originalMetadata)
            check(meta.edit().remove("outstanding_session").remove("pending_end_url")
                .remove("pending_end_credential_hash").commit())
            // Diagnostics remains in this authenticated Activity and is discarded on background.
            click(setup, "Voice diagnostics")
            shell("input keyevent 3")
            await("background clears authorization and credentials") {
                var result = false
                test.runOnMainSync { result = !authenticated.getBoolean(setup) && inputs(setup).isEmpty() }
                result
            }
            context.startActivity(Intent(context, KeyEntryActivity::class.java)
                .putExtra("voice_only", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            authenticate(setup)
            test.runOnMainSync { check(inputs(setup)[1].text.isEmpty()) }
            // A protected return drives the existing parent maintenance owner; no new updater is created.
            check(context.getSharedPreferences("deck", 0).edit().putString("reception_appearance", "dark").commit())
            click(setup, "Light appearance")
            await("protected maintenance result applied by parent") {
                context.getSharedPreferences("deck", 0).getString("reception_appearance", "") == "light"
            }
            check(setup.isFinishing || setup.isDestroyed)
            check(personal.load() == personalKey && TranslationSettings(context).directFishSelected)
            // A hotel guest bridge command cannot override that protected decision.
            js("window.ManzaniaNative.postMessage(JSON.stringify({action:'appearance',value:'dark'}))")
            test.waitForIdleSync()
            check(context.getSharedPreferences("deck", 0).getString("reception_appearance", "") == "light")
            val finalSetup = open { js("window.ManzaniaNative.postMessage(JSON.stringify({action:'settings'}))") }
            authenticate(finalSetup)
            click(finalSetup, "Done")
            test.sendStatus(0, Bundle().apply {
                putString("stream", "HOTEL_SETUP protected=true direct_blocked=true personal_key_retained=true maintenance=true\n")
            })
        } finally {
            test.runOnMainSync { openSetups.forEach { if (!it.isDestroyed) it.finish() }; activity.finish() }
            meta.edit().remove("outstanding_session").remove("pending_end_url").remove("pending_end_credential_hash").commit()
            if (syntheticPin) shell("locksettings clear --old 2468")
        }
    }
}
