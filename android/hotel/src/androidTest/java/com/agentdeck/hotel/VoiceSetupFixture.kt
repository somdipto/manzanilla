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
        val credentials = TranslationCredentials(context)
        check(store.status() == SecretStore.Status.ABSENT)
        check(credentials.load() == null && credentials.savedFishKey() == null)
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
        fun keyField() = appNodes().firstOrNull {
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
        fun inputLengths(setup: KeyEntryActivity): List<Int> {
            var lengths = emptyList<Int>()
            test.runOnMainSync {
                @Suppress("UNCHECKED_CAST")
                val inputs = fields.get(setup) as List<EditText>
                check(inputs.size == 3) { "Personal setup must own Fish, Gemini and voice ID fields only" }
                check(inputs.take(2).all { it.transformationMethod != null })
                check(inputs[2].transformationMethod == null)
                lengths = inputs.map { it.text.length }
            }
            return lengths
        }
        fun personalSetupReady(setup: KeyEntryActivity, stage: String) {
            await(stage) {
                check(windowRoot("com.android.settings") == null) { "Personal translation setup requested a device credential" }
                var resumed = false
                test.runOnMainSync {
                    @Suppress("UNCHECKED_CAST")
                    val input = (fields.get(setup) as List<EditText>).firstOrNull()
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
            inputLengths(setup)
        }
        fun enterKey(provider: String, value: String) {
            val language = context.getSharedPreferences("deck", 0).getString("reception_staff_language", "es").orEmpty()
            val description = UiText.text(context, language, "New {provider} key, masked", mapOf("provider" to provider))
            val field = findSetupNode("visible editable $provider key field") {
                it.isEditable && it.isPassword && it.contentDescription?.toString() == description
            }
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            })) { "$provider key field did not accept synthetic input" }
        }
        fun enterVoice(value: String) {
            val field = findSetupNode("visible voice ID field") { it.isEditable && !it.isPassword }
            check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            })) { "Voice ID field did not accept synthetic input" }
        }
        fun savedConfiguration(fish: String, gemini: String, voice: String) {
            val saved = checkNotNull(credentials.load()) { "Complete translation configuration was not saved" }
            check(saved.fishKey == fish && saved.translationKey == gemini && saved.voiceId == voice) {
                "Saved translation configuration mixed or lost fields"
            }
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
            check(credentials.load() == null && credentials.savedFishKey() == null)
            check(!TranslationSettings(context).directFishSelected) { "Cancel changed the voice route" }
            await("setup retry button") { js("!!document.querySelector('[data-action=setup-translation]')") == "true" }
            val setup = openSetup("personal setup retry launch") {
                js("document.querySelector('[data-action=setup-translation]').click()")
            }
            personalSetupReady(setup, "personal key field on retry")
            val syntheticKey = "fixture-only-not-a-provider-key"
            val syntheticGemini = "fixture-only-not-a-gemini-key"
            val syntheticVoice = "fixture-only-not-a-provider-voice"
            enterVoice("")
            enterKey("Fish Audio", syntheticKey)
            click("Save and return")
            test.waitForIdleSync()
            check(!setup.isFinishing && !setup.isDestroyed)
            check(credentials.load() == null && store.status() == SecretStore.Status.ABSENT)
            check(!TranslationSettings(context).directFishSelected)
            enterKey("Gemini", syntheticGemini)
            click("Save and return")
            test.waitForIdleSync()
            check(!setup.isFinishing && !setup.isDestroyed)
            check(credentials.load() == null && store.status() == SecretStore.Status.ABSENT)
            enterVoice(syntheticVoice)
            click("Save and return")
            await("saved setup closes") { setup.isFinishing || setup.isDestroyed }
            savedConfiguration(syntheticKey, syntheticGemini, syntheticVoice)
            check(store.status() == SecretStore.Status.ABSENT) { "Atomic save unexpectedly wrote the legacy single-key store" }
            readyTranslation()

            // The translation gear must reopen this same personal setup even
            // after a key exists, without redirecting to Staff authentication.
            val reopened = openSetup("saved-key personal setup launch") {
                js("document.getElementById('settings').click()")
            }
            personalSetupReady(reopened, "saved-key personal setup")

            check(inputLengths(reopened) == listOf(0, 0, syntheticVoice.length))
            val unsavedKey = "fixture-unsaved-key-must-not-survive-recreation"
            val unsavedGemini = "fixture-unsaved-gemini-must-not-survive-recreation"
            enterKey("Fish Audio", unsavedKey)
            enterKey("Gemini", unsavedGemini)
            check(inputLengths(reopened).take(2) == listOf(unsavedKey.length, unsavedGemini.length)) {
                "Synthetic input did not reach the current setup Activity"
            }
            // Recreate the actual Activity to exercise the same saved-instance
            // and resume path as a configuration change, without bypassing auth.
            val recreated = openSetup("personal setup recreation") {
                test.runOnMainSync { reopened.recreate() }
            }
            check(recreated !== reopened)
            await("previous setup destroyed after recreation") { reopened.isDestroyed }
            personalSetupReady(recreated, "recreated personal setup")
            check(inputLengths(recreated) == listOf(0, 0, syntheticVoice.length)) {
                "Recreation retained unsaved keys or lost the saved voice"
            }
            savedConfiguration(syntheticKey, syntheticGemini, syntheticVoice)
            // An incomplete replacement must not partially update either saved key.
            enterKey("Fish Audio", unsavedKey)
            enterKey("Gemini", unsavedGemini)
            enterVoice("")
            click("Save and return")
            test.waitForIdleSync()
            check(!recreated.isFinishing && !recreated.isDestroyed)
            savedConfiguration(syntheticKey, syntheticGemini, syntheticVoice)
            // Blank secret fields reuse the encrypted atomic bundle. This must
            // also override a previously selected hotel voice route.
            enterKey("Fish Audio", "")
            enterKey("Gemini", "")
            enterVoice(syntheticVoice)
            check(TranslationSettings(context).selectHotelConnection())
            check(!TranslationSettings(context).directFishSelected)
            click("Save and return")
            await("re-enrolled setup closes") { recreated.isFinishing || recreated.isDestroyed }
            savedConfiguration(syntheticKey, syntheticGemini, syntheticVoice)
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
            savedConfiguration(syntheticKey, syntheticGemini, syntheticVoice)
            check(TranslationSettings(context).directFishSelected)
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
            val closing = activity
            if (closing != null) {
                test.runOnMainSync { closing.finish() }
                test.waitForIdleSync()
                val destroyedBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
                var destroyed = false
                while (!destroyed) {
                    test.runOnMainSync { destroyed = closing.isDestroyed }
                    check(destroyed || System.nanoTime() < destroyedBy) {
                        var state = ""
                        test.runOnMainSync {
                            state = "finishing=${closing.isFinishing} destroyed=${closing.isDestroyed} focus=${closing.hasWindowFocus()}"
                        }
                        test.sendStatus(0, android.os.Bundle().apply { putString("stream", "HOTEL_CLEANUP_DESTROY $state") })
                        "Cleanup Activity destruction did not settle"
                    }
                    if (!destroyed) Thread.sleep(20)
                }
            }
            shared.set(null, previousAdmission)
            meta.edit().remove("outstanding_session").remove("pending_end_url")
                .remove("pending_end_credential_hash").commit()
        }
    }

    /** Owns only synthetic data and PIN 2468 on a fresh, initially lock-free hotel AVD. */
    fun verifyHotel(test: Instrumentation) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(BuildConfig.DEBUG && BuildConfig.DISTRIBUTION == "hotel")
        val context = test.targetContext
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        check(!keyguard.isDeviceSecure) { "Hotel setup fixture requires an initially lock-free disposable AVD" }
        val personal = SecretStore(context, "fish")
        val connection = HotelConnection(context)
        val credentials = TranslationCredentials(context)
        val meta = context.getSharedPreferences("fishmeta", 0)
        check(personal.status() == SecretStore.Status.ABSENT && connection.load() == null)
        check(credentials.load() == null && credentials.savedFishKey() == null)
        check(!meta.contains("outstanding_session"))
        check(context.getSharedPreferences("deck", 0).edit()
            .putString("reception_guest_language", "en").putString("reception_staff_language", "en").commit())
        val personalKey = "fixture-retained-personal-key-not-a-provider-credential"
        val translationKey = "fixture-retained-translation-key-not-a-provider-credential"
        val voiceId = "fixture-retained-voice-id"
        check(personal.save(personalKey))
        check(credentials.load() == null && credentials.savedFishKey() == personalKey) {
            "A legacy Fish key must remain reusable without masquerading as complete translation setup"
        }
        check(TranslationSettings(context).selectDirectFish())
        fun retainedConfiguration() {
            val saved = checkNotNull(credentials.load())
            check(saved.fishKey == personalKey && saved.translationKey == translationKey && saved.voiceId == voiceId)
            check(personal.load() == personalKey && TranslationSettings(context).directFishSelected)
        }
        fun reportStage(value: String) {
            test.sendStatus(0, Bundle().apply { putString("stream", "HOTEL_SETUP_STAGE $value\n") })
        }
        fun await(stage: String, snapshot: () -> String = { "" }, state: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!state()) {
                if (System.nanoTime() >= deadline) {
                    reportStage("timeout=$stage ${snapshot()}")
                    error("Hotel setup timed out: $stage")
                }
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
        reportStage("enter=owned cleanup")
        verifyRestoredCleanup(test, personalKey)
        reportStage("passed=owned cleanup")
        // Exercise the real new-start boundary with a saved legacy personal selection.
        // Keep the combined provider configuration absent until after this attempt:
        // even a regressed hotel gate cannot construct a paid native pipeline.
        // Interceptors also block historical/managed provider access.
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
            synchronized(events) { events.any { it.optString("phase") == "error" } } &&
                fish.admissionIssue() == null
        }
        check(keyReads.get() == 0 && requests.get() == 0) { "Hotel new start consulted a personal credential or provider" }
        check(credentials.load() == null && personal.load() == personalKey)
        check(credentials.save(personalKey, translationKey, voiceId))
        retainedConfiguration()
        test.uiAutomation.serviceInfo = test.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        // Observe framework lifecycle events before launching. Protected setup may
        // legitimately replace its parent; never drive a destroyed captured view.
        val application = context.applicationContext as android.app.Application
        val identities = java.util.IdentityHashMap<android.app.Activity, String>()
        val hotelStages = LinkedHashMap<HotelActivity, String>()
        val lifecycleEvents = java.util.ArrayDeque<String>()
        val lifecycle = object : android.app.Application.ActivityLifecycleCallbacks {
            fun record(activity: android.app.Activity, stage: String) {
                if (activity !is HotelActivity && activity !is KeyEntryActivity) return
                val identity = identities.getOrPut(activity) {
                    "${if (activity is HotelActivity) "reception" else "setup"}#${identities.size + 1}"
                }
                if (activity is HotelActivity && stage != "saved") hotelStages[activity] = stage
                lifecycleEvents.addLast("$identity:$stage" +
                    "(task=${activity.taskId},changing=${activity.isChangingConfigurations},finishing=${activity.isFinishing})")
                if (lifecycleEvents.size > 12) lifecycleEvents.removeFirst()
            }
            override fun onActivityCreated(activity: android.app.Activity, state: Bundle?) =
                record(activity, if (state == null) "created" else "created-restored")
            override fun onActivityStarted(activity: android.app.Activity) = record(activity, "started")
            override fun onActivityResumed(activity: android.app.Activity) = record(activity, "resumed")
            override fun onActivityPaused(activity: android.app.Activity) = record(activity, "paused")
            override fun onActivityStopped(activity: android.app.Activity) = record(activity, "stopped")
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: Bundle) = record(activity, "saved")
            override fun onActivityDestroyed(activity: android.app.Activity) = record(activity, "destroyed")
        }
        var activity: HotelActivity? = null
        var browser: WebView? = null
        var receptionTaskId = -1
        val reception = HotelActivity::class.java.getDeclaredField("reception").apply { isAccessible = true }
        val authenticated = KeyEntryActivity::class.java.getDeclaredField("authenticated").apply { isAccessible = true }
        val foreground = KeyEntryActivity::class.java.getDeclaredField("foreground").apply { isAccessible = true }
        val fields = KeyEntryActivity::class.java.getDeclaredField("fields").apply { isAccessible = true }
        val openSetups = ArrayList<KeyEntryActivity>()
        val receptionForeground = HotelActivity::class.java.getDeclaredField("foreground").apply { isAccessible = true }
        fun receptionState(): String {
            var state = ""
            test.runOnMainSync {
                val current = activity
                state = "parent=${identities[current] ?: "none"}" +
                    " parent_foreground=${current?.let { receptionForeground.getBoolean(it) }}" +
                    " parent_focus=${current?.hasWindowFocus()} parent_finishing=${current?.isFinishing}" +
                    " parent_destroyed=${current?.isDestroyed} browser_attached=${browser?.isAttachedToWindow}" +
                    " browser_shown=${browser?.isShown} setups=${openSetups.size}" +
                    " setups_destroyed=${openSetups.count { it.isDestroyed }}" +
                    " lifecycle=${lifecycleEvents.joinToString(";")}"
            }
            return state
        }
        fun awaitReception(stage: String) {
            await("reception resumed: $stage", ::receptionState) {
                var resumed = false
                test.runOnMainSync {
                    val previous = activity
                    val current = hotelStages.filter { (candidate, stage) ->
                        stage == "resumed" && candidate.taskId == receptionTaskId
                    }.keys.singleOrNull()
                    if (current != null && openSetups.all { it.isDestroyed } &&
                        (previous == null || current === previous || (previous.isDestroyed && !previous.isFinishing)) &&
                        !current.isFinishing && !current.isDestroyed &&
                        receptionForeground.getBoolean(current) && current.hasWindowFocus()) {
                        val view = reception.get(current) as? ReceptionView
                        val currentBrowser = view?.getChildAt(0) as? WebView
                        if (currentBrowser != null && currentBrowser.isAttachedToWindow && currentBrowser.isShown) {
                            if (current !== activity) {
                                reportStage("parent_selected=${identities[current]} previous=${identities[activity] ?: "none"}" +
                                    " lifecycle=${lifecycleEvents.joinToString(";")}")
                            }
                            activity = current
                            browser = currentBrowser
                            resumed = true
                        }
                    }
                }
                resumed
            }
        }
        fun inputs(setup: KeyEntryActivity): List<EditText> {
            @Suppress("UNCHECKED_CAST")
            return fields.get(setup) as List<EditText>
        }
        fun js(stage: String, script: String): String {
            awaitReception(stage)
            val ready = CountDownLatch(1)
            var value = ""
            test.runOnMainSync { checkNotNull(browser).evaluateJavascript(script) { value = it; ready.countDown() } }
            if (!ready.await(15, TimeUnit.SECONDS)) {
                reportStage("callback_timeout=$stage ${receptionState()}")
                error("Hotel setup WebView callback timed out: $stage")
            }
            return value
        }
        fun awaitPage(stage: String) {
            await("hotel packaged page: $stage", ::receptionState) {
                js(stage, """(function() {
                    var home = document.querySelector('[data-action=translation]');
                    var bounds = home && home.getBoundingClientRect();
                    return !!(window.manzania && typeof window.manzania.receive === 'function' &&
                        window.ManzaniaNative && home && bounds.width > 0 && bounds.height > 0);
                })()""") == "true"
            }
        }
        fun open(stage: String, action: () -> Unit): KeyEntryActivity {
            awaitPage(stage)
            reportStage("opening=$stage")
            val monitor = test.addMonitor(KeyEntryActivity::class.java.name, null, false)
            return try {
                action()
                val setup = monitor.waitForActivityWithTimeout(15_000) as? KeyEntryActivity
                if (setup == null) {
                    reportStage("open_timeout=$stage ${receptionState()}")
                    error("Hotel setup did not open: $stage")
                }
                setup.also(openSetups::add)
            } finally { test.removeMonitor(monitor) }
        }
        fun postSetup(route: String) {
            // Opening setup pauses this WebView. Its JS result callback may then
            // be suspended; the ActivityMonitor above observes the real outcome.
            test.runOnMainSync {
                checkNotNull(browser).evaluateJavascript("window.ManzaniaNative.postMessage(JSON.stringify({action:'$route'}))", null)
            }
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
        fun setupState(setup: KeyEntryActivity): String {
            var state = ""
            test.runOnMainSync {
                state = "setup_foreground=${foreground.getBoolean(setup)} setup_focus=${setup.hasWindowFocus()}" +
                    " setup_finishing=${setup.isFinishing} setup_destroyed=${setup.isDestroyed}" +
                    " setup_attached=${setup.window.decorView.isAttachedToWindow}" +
                    " setup_shown=${setup.window.decorView.isShown}"
            }
            return state
        }
        fun locked(setup: KeyEntryActivity) {
            await("locked setup has no fields", { setupState(setup) }) {
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
            await("real Android credential prompt", { setupState(setup) }) {
                test.uiAutomation.windows.mapNotNull { it.root }.any { root ->
                    root.packageName?.toString() in setOf("com.android.settings", "com.android.systemui") &&
                        descendants(root).any { it.isEditable && it.isVisibleToUser }
                }
            }
            shell("input text 2468")
            shell("input keyevent 66")
            await("authenticated enrollment form", { setupState(setup) }) {
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
        test.runOnMainSync { application.registerActivityLifecycleCallbacks(lifecycle) }
        try {
            reportStage("enter=hotel activity launch")
            val launched = test.startActivitySync(Intent(context, HotelActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            test.runOnMainSync { receptionTaskId = launched.taskId }
            awaitPage("initial page")
            // Both guest bridge entry points and an explicit voice_only extra must fail closed without a lock.
            for (route in listOf("settings", "voice_setup", "intent")) {
                val setup = open("no-lock $route") {
                    if (route == "intent") context.startActivity(Intent(context, KeyEntryActivity::class.java)
                        .putExtra("voice_only", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    else postSetup(route)
                }
                locked(setup)
                await("no-lock hotel setup refusal", { setupState(setup) }) {
                    var refused = false
                    test.runOnMainSync {
                        val visible = nodes(setup.window.decorView).filter { it.isShown }
                        val close = visible.filterIsInstance<android.widget.Button>().singleOrNull()
                        refused = !keyguard.isDeviceSecure && foreground.getBoolean(setup) &&
                            !authenticated.getBoolean(setup) && inputs(setup).isEmpty() &&
                            close?.isEnabled == true &&
                            visible.filterIsInstance<android.widget.TextView>()
                                .count { it !is android.widget.Button && it.text.isNotBlank() } >= 2
                    }
                    refused
                }
                click(setup, "Close")
                awaitPage("after no-lock $route closes")
            }
            shell("locksettings set-pin 2468")
            syntheticPin = true
            await("synthetic device credential enrolled") { keyguard.isDeviceSecure }
            val setup = open("protected voice setup") { postSetup("voice_setup") }
            authenticate(setup)
            test.runOnMainSync {
                val update = HotelActivity::class.java.getDeclaredMethod("onUpdate", String::class.java).apply { isAccessible = true }
                update.invoke(activity, "failed:Could not check for updates (metadata, HTTP 403). Tap to retry.")
            }
            await("protected updater failure visible") {
                var shown = false
                test.runOnMainSync {
                    shown = nodes(setup.window.decorView).filterIsInstance<android.widget.TextView>()
                        .any { it.text.contains("HTTP 403") }
                }
                shown
            }
            test.runOnMainSync {
                val update = HotelActivity::class.java.getDeclaredMethod("onUpdate", String::class.java).apply { isAccessible = true }
                update.invoke(activity, "confirm:${UpdateStatusText.MANUAL_INSTALL}")
            }
            await("protected official recovery instructions visible") {
                var shown = false
                test.runOnMainSync {
                    shown = nodes(setup.window.decorView).filterIsInstance<android.widget.TextView>()
                        .any { it.text.contains(UpdateStatusText.OFFICIAL_URL) && !it.text.contains("{url}") }
                }
                shown
            }
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
            await("background clears authorization and credentials", { setupState(setup) }) {
                var result = false
                test.runOnMainSync { result = !authenticated.getBoolean(setup) && inputs(setup).isEmpty() }
                result
            }
            reportStage("enter=protected setup foreground return")
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
            awaitPage("after protected maintenance")
            retainedConfiguration()
            // A hotel guest bridge command cannot override that protected decision.
            js("guest maintenance denied", "window.ManzaniaNative.postMessage(JSON.stringify({action:'appearance',value:'dark'}))")
            test.waitForIdleSync()
            check(context.getSharedPreferences("deck", 0).getString("reception_appearance", "") == "light")
            val finalSetup = open("final protected settings") { postSetup("settings") }
            authenticate(finalSetup)
            click(finalSetup, "Done")
            awaitPage("after final setup closes")
            test.sendStatus(0, Bundle().apply {
                putString("stream", "HOTEL_SETUP protected=true direct_blocked=true personal_key_retained=true maintenance=true\n")
            })
        } finally {
            test.runOnMainSync {
                openSetups.forEach { if (!it.isDestroyed) it.finish() }
                hotelStages.keys.forEach { if (!it.isDestroyed) it.finish() }
                application.unregisterActivityLifecycleCallbacks(lifecycle)
            }
            meta.edit().remove("outstanding_session").remove("pending_end_url").remove("pending_end_credential_hash").commit()
            if (syntheticPin) shell("locksettings clear --old 2468")
        }
    }
}
