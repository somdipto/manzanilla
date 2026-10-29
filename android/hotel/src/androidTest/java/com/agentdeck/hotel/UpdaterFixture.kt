package com.agentdeck.hotel

import android.app.Instrumentation
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.ComponentName
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.UUID
import java.security.MessageDigest

/** Disposable-emulator installer checks. Callback cases never commit a session.
 * The separately requested QA consent case installs only a staged, same-signer,
 * key-free debug candidate through the actual Updater and Android consent UI.
 */
internal object UpdaterFixture {
    fun verify(instrumentation: Instrumentation) {
        requireQaBuild(instrumentation)
        val base = instrumentation.targetContext
        val root = File(base.cacheDir, "updater-fixture-${UUID.randomUUID()}").apply { mkdirs() }
        val prefix = root.name
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?): File = File(root, "downloads").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$prefix-$name", mode)
            override fun startActivity(intent: Intent) { error("Isolated fixture must never open installer or settings UI") }
        }
        val statuses = ArrayList<String>()
        val updater = Updater(context, statuses::add, canInstall = { false })
        val ownership = Updater::class.java.getDeclaredField("ownership").apply { isAccessible = true }.get(updater) as UpdateOwnership
        val phase = Updater::class.java.getDeclaredField("phase").apply { isAccessible = true }
        val request = UpdateRequest("v99.0.0", "https://fixture.invalid/releases/download/v99.0.0/app.apk",
            base.packageName, "99.0.0", 9999, UpdateRequest.hash("fixture-only".toByteArray()), BuildConfig.DISTRIBUTION)
        val before = base.packageManager.getPackageInfo(base.packageName, 0)
        val settings = context.getSharedPreferences("deck", Context.MODE_PRIVATE)
        val prefs = context.getSharedPreferences("upd", Context.MODE_PRIVATE)
        val sentinel = File(context.filesDir, "reception-preferences.fixture").apply { writeText("appearance=light;guest=fr;staff=de") }
        val externalSentinel = File(context.getExternalFilesDir(null), "reception-history.fixture").apply { writeText("fixture-history-kept") }
        val apk = File(context.filesDir, "update-${request.key}.apk").apply { writeText("fixture-cache-not-an-APK") }
        val downloaded = File(context.getExternalFilesDir(null), "update-${request.key}.apk").apply { writeText("fixture-cache-copy") }
        try {
            check(settings.edit().putString("reception_appearance", "light").putString("reception_guest_language", "fr").commit())
            instrumentation.runOnMainSync {
                check(ownership.begin(request) != null)
                phase.set(updater, "ready")
                repeat(3) { updater.tryInstallIfIdle() }
                check(!updater.isInstalling()) { "busy reception reserved an installation" }
                check(phase.get(updater) == "ready") { "busy reception started validation or installer UI" }
                check(statuses.size == 1 && statuses.single().startsWith("waiting:")) { "idle polling repeated UI/status work" }
                updater.cancel()
                check(ownership.active == null && !updater.isInstalling())
            }
            check(!apk.exists() && !downloaded.exists()) { "cancel did not remove only its cached APK copies" }
            check(sentinel.readText() == "appearance=light;guest=fr;staff=de" && externalSentinel.readText() == "fixture-history-kept")
            check(settings.getString("reception_appearance", null) == "light" && settings.getString("reception_guest_language", null) == "fr")

            val obsolete = File(context.filesDir, "update-obsolete.apk").apply { writeText("obsolete-cache") }
            val part = File(context.filesDir, "update-obsolete.part").apply { writeText("interrupted-cache") }
            val cleanup = Updater::class.java.getDeclaredMethod("discardObsoleteCache").apply { isAccessible = true }
            cleanup.invoke(updater)
            check(!obsolete.exists() && !part.exists() && sentinel.isFile && externalSentinel.isFile)
            check(settings.getString("reception_appearance", null) == "light")

            // A declined/dispatched update restored by a new activity stays manual.
            check(prefs.edit().putString("installAttempted", request.key).commit())
            val restoredStatuses = ArrayList<String>()
            val restored = Updater(context, restoredStatuses::add, canInstall = { true })
            val restoredOwnership = Updater::class.java.getDeclaredField("ownership").apply { isAccessible = true }.get(restored) as UpdateOwnership
            val restore = Updater::class.java.getDeclaredMethod("restoreInstallAttempt", UpdateRequest::class.java).apply { isAccessible = true }
            instrumentation.runOnMainSync {
                check(restoredOwnership.begin(request) != null)
                restore.invoke(restored, request)
                phase.set(restored, "ready")
                repeat(3) { restored.tryInstallIfIdle() }
                check(!restored.isInstalling() && phase.get(restored) == "ready")
                check(restoredStatuses.size == 1 && restoredStatuses.single().startsWith("confirm:"))
                restored.cancel()
            }

            val factory = Updater::class.java.getDeclaredMethod("installParameters", String::class.java).apply { isAccessible = true }
            val params = factory.invoke(updater, base.packageName) as PackageInstaller.SessionParams
            val installer = base.packageManager.packageInstaller
            val id = installer.createSession(params)
            try {
                val info = installer.getSessionInfo(id) ?: error("Android did not retain the staged fixture session")
                check(info.appPackageName == base.packageName) { "update changed package identity" }
                if (Build.VERSION.SDK_INT >= 27) check(info.mode == PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                if (Build.VERSION.SDK_INT >= 31) check(info.requireUserAction == PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                // Replay a delayed Android consent callback after reception went idle-off.
                // The real listener must release admission and retain a manual action,
                // without launching installer UI over a different foreground app.
                instrumentation.runOnMainSync {
                    val attempt = ownership.begin(request)!!
                    val policy = Updater::class.java.getDeclaredField("installPolicy").apply { isAccessible = true }.get(updater) as UpdateInstallPolicy
                    check(policy.reserve(idle = true, permitted = true) == UpdateInstallPolicy.Decision.INSTALL)
                    phase.set(updater, "installing")
                    val session = Updater::class.java.getDeclaredField("installSessionId").apply { isAccessible = true }
                    session.setInt(updater, id)
                    Updater::class.java.getDeclaredMethod("listen", UpdateAttempt::class.java).apply { isAccessible = true }.invoke(updater, attempt)
                    val listener = Updater::class.java.getDeclaredField("receiver").apply { isAccessible = true }.get(updater) as BroadcastReceiver
                    val pending = Intent("dev.orange.hotel.UPDATE_RESULT")
                        .setData(Updater::class.java.getDeclaredField("callbackUri").apply { isAccessible = true }.get(updater) as Uri)
                        .putExtra(PackageInstaller.EXTRA_SESSION_ID, id)
                        .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
                        .putExtra(Intent.EXTRA_INTENT, Intent(Settings.ACTION_SETTINGS))
                    listener.onReceive(context, pending)
                    check(phase.get(updater) == "confirming" && !updater.isInstalling() && ownership.active == attempt)
                    listener.onReceive(context, pending)
                    updater.onForeground()
                    check(phase.get(updater) == "confirming" && !updater.isInstalling())
                    Updater::class.java.getDeclaredMethod("stopListening").apply { isAccessible = true }.invoke(updater)
                    session.setInt(updater, -1)
                }
                // Deliberately no openWrite/commit: this cannot replace the running application.
            } finally { installer.abandonSession(id) }

            val after = base.packageManager.getPackageInfo(base.packageName, PackageManager.GET_PERMISSIONS)
            check(after.firstInstallTime == before.firstInstallTime && after.versionName == before.versionName)
            check(File(after.applicationInfo!!.sourceDir).isFile) { "cache cleanup removed the installed package" }
            check(after.requestedPermissions.orEmpty().contains("android.permission.UPDATE_PACKAGES_WITHOUT_USER_ACTION"))
            check(sentinel.readText() == "appearance=light;guest=fr;staff=de")
            instrumentation.sendStatus(0, Bundle().apply {
                putString("stream", "updater_scope=isolated idle=deferred cache_cleanup=user_data_retained installer=same_package_full_install_uncommitted_and_abandoned\n")
            })
        } finally {
            instrumentation.runOnMainSync { updater.cancel() }
            prefs.edit().clear().commit()
            settings.edit().clear().commit()
            root.deleteRecursively()
        }
    }

    fun verifyCallbacks(test: Instrumentation) {
        requireQaBuild(test)
        val base = test.targetContext
        val root = File(base.cacheDir, "installer-callbacks-${UUID.randomUUID()}").apply { mkdirs() }
        var launches = 0
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?): File = File(root, "downloads").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("${root.name}-$name", mode)
            override fun startActivity(intent: Intent) { launches++ }
        }
        val statuses = ArrayList<String>()
        val updater = Updater(context, statuses::add, canInstall = { true })
        fun field(name: String) = Updater::class.java.getDeclaredField(name).apply { isAccessible = true }
        val ownership = field("ownership").get(updater) as UpdateOwnership
        val policy = field("installPolicy").get(updater) as UpdateInstallPolicy
        val phase = field("phase")
        val session = field("installSessionId")
        val capability = field("callbackUri")
        val pendingField = field("callbackIntent")
        val receiver = field("receiver")
        val listen = Updater::class.java.getDeclaredMethod("listen", UpdateAttempt::class.java).apply { isAccessible = true }
        val stop = Updater::class.java.getDeclaredMethod("stopListening").apply { isAccessible = true }
        val abandon = Updater::class.java.getDeclaredMethod("abandonInstall").apply { isAccessible = true }
        val installer = base.packageManager.packageInstaller
        val request = UpdateRequest("v99.0.0", "https://fixture.invalid/releases/download/v99.0.0/app.apk",
            base.packageName, "99.0.0", 9999, UpdateRequest.hash("callback-fixture".toByteArray()), BuildConfig.DISTRIBUTION)
        val cached = File(context.filesDir, "update-${request.key}.apk").apply { writeText("verified-download-sentinel") }
        var id = -1
        lateinit var attempt: UpdateAttempt
        lateinit var uri: Uri
        lateinit var listener: BroadcastReceiver
        lateinit var pending: PendingIntent
        fun stage() = test.runOnMainSync {
            stop.invoke(updater)
            abandon.invoke(updater)
            ownership.cancel()
            policy.reset()
            id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(base.packageName)
            })
            attempt = ownership.begin(request)!!
            check(policy.reserve(idle = true, permitted = true) == UpdateInstallPolicy.Decision.INSTALL)
            session.setInt(updater, id)
            phase.set(updater, "installing")
            listen.invoke(updater, attempt)
            uri = capability.get(updater) as Uri
            check(uri.scheme == "manzanilla-update" && uri.host == "callback" &&
                uri.path.orEmpty().matches(Regex("/[0-9a-f]{64}"))) { "Missing per-attempt callback capability" }
            listener = receiver.get(updater) as BroadcastReceiver
            // Mirror the real sender identity so terminal/removal cancellation is observable.
            val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            pending = PendingIntent.getBroadcast(context, id,
                Intent("dev.orange.hotel.UPDATE_RESULT").setPackage(base.packageName).setData(uri),
                mutable or PendingIntent.FLAG_UPDATE_CURRENT)
            pendingField.set(updater, pending)
        }
        fun result(data: Uri? = uri, sessionId: Int = id, status: Int = PackageInstaller.STATUS_SUCCESS) =
            Intent("dev.orange.hotel.UPDATE_RESULT").setData(data)
                .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                .putExtra(PackageInstaller.EXTRA_STATUS, status)
        fun unchanged(deliver: () -> Unit) {
            val count = statuses.size
            val current = attempt
            val currentUri = uri
            deliver()
            test.waitForIdleSync()
            test.runOnMainSync {
                check(phase.get(updater) == "installing" && updater.isInstalling() &&
                    ownership.active == current && session.getInt(updater) == id &&
                    capability.get(updater) == currentUri && statuses.size == count && launches == 0) {
                    "Unauthenticated callback changed updater state or launched an activity"
                }
            }
        }
        fun cancelled(sender: PendingIntent) {
            var cancelled = false
            try { sender.send() } catch (_: PendingIntent.CanceledException) { cancelled = true }
            check(cancelled) { "Retired callback PendingIntent remained usable" }
        }
        fun shellGuess(data: String?) {
            // Only public action/session and deliberately guessed values cross shell UID.
            // An authentic capability, including a retired one, NEVER enters this command.
            val dataArg = data?.let { " -d '$it'" }.orEmpty()
            shell(test, "am broadcast -a dev.orange.hotel.UPDATE_RESULT -p ${base.packageName}$dataArg " +
                "--ei android.content.pm.extra.SESSION_ID $id --ei android.content.pm.extra.STATUS 0")
        }
        try {
            stage()
            unchanged { shellGuess(null) }
            val guessed = "manzanilla-update://callback/" + "0".repeat(64)
            check(uri.toString() != guessed) { "Regenerate the fixture attempt" }
            unchanged { shellGuess(guessed) }
            // Trusted in-process injections separately exercise checks beyond the IntentFilter.
            unchanged { test.runOnMainSync { listener.onReceive(context, result(sessionId = id + 1)) } }
            unchanged { test.runOnMainSync { listener.onReceive(context, result(sessionId = -1)) } }
            unchanged { test.runOnMainSync { listener.onReceive(context, result().apply { removeExtra(PackageInstaller.EXTRA_SESSION_ID) }) } }
            unchanged { test.runOnMainSync { listener.onReceive(context, result().setAction("fixture.WRONG_ACTION")) } }
            unchanged {
                test.runOnMainSync {
                    phase.set(updater, "ready")
                    listener.onReceive(context, result())
                    check(phase.get(updater) == "ready") { "Callback accepted outside the installing/confirming phase" }
                    phase.set(updater, "installing")
                }
            }
            unchanged {
                test.runOnMainSync {
                    session.setInt(updater, -1)
                    listener.onReceive(context, result(sessionId = -1))
                    check(session.getInt(updater) == -1) { "Callback accepted without a live install session" }
                    session.setInt(updater, id)
                }
            }
            unchanged { test.runOnMainSync { listener.onReceive(context, result(data = null)) } }
            val oldUri = uri
            val oldListener = listener
            val oldPending = pending
            test.runOnMainSync { stop.invoke(updater) }
            check(capability.get(updater) == null && pendingField.get(updater) == null)
            cancelled(oldPending)
            stage()
            check(uri != oldUri) { "Retry reused its callback capability" }
            unchanged { shellGuess(guessed) } // Replay an earlier shell guess against the new attempt.
            unchanged { test.runOnMainSync { listener.onReceive(context, result(data = oldUri)) } }
            unchanged { test.runOnMainSync { oldListener.onReceive(context, result(data = oldUri)) } }

            // Possession of the real capability is trusted fixture injection, NOT an external guess.
            // A nested explicit app activity must still never be launched by the receiver.
            val appActivity = Intent().setComponent(ComponentName(base.packageName, HotelActivity::class.java.name))
            val appInfo = base.packageManager.getActivityInfo(appActivity.component!!, 0)
            check(appInfo.applicationInfo.flags and
                (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0)
            val rejectedListener = listener
            val rejectedResult = result(status = PackageInstaller.STATUS_PENDING_USER_ACTION).putExtra(Intent.EXTRA_INTENT, appActivity)
            val rejectedSender = pending
            test.runOnMainSync {
                listener.onReceive(context, rejectedResult)
                check(launches == 0 && phase.get(updater) == "ready" && !updater.isInstalling() &&
                    capability.get(updater) == null && pendingField.get(updater) == null &&
                    cached.isFile && statuses.last().startsWith("confirm:")) { "Untrusted confirmation target was not rejected safely" }
            }
            cancelled(rejectedSender)
            val count = statuses.size
            test.runOnMainSync {
                rejectedListener.onReceive(context, rejectedResult)
                check(launches == 0 && phase.get(updater) == "ready" && statuses.size == count)
            }

            // Even an authenticated success cannot assert that the candidate was installed.
            stage()
            val success = result()
            val terminalListener = listener
            val terminalSender = pending
            test.runOnMainSync {
                listener.onReceive(context, success)
                check(phase.get(updater) != "installed" && !updater.isInstalling() && launches == 0 &&
                    capability.get(updater) == null && cached.isFile) { "Callback success bypassed installed-version verification" }
            }
            cancelled(terminalSender)
            val terminalPhase = phase.get(updater)
            val terminalCount = statuses.size
            test.runOnMainSync {
                terminalListener.onReceive(context, success)
                check(phase.get(updater) == terminalPhase && statuses.size == terminalCount && launches == 0)
            }
            test.sendStatus(0, Bundle().apply {
                putString("stream", "installer_callbacks=shell_uid_guesses_and_trusted_injection capability_output=none activity_launches=0\n")
            })
        } finally {
            test.runOnMainSync {
                stop.invoke(updater)
                abandon.invoke(updater)
                phase.set(updater, "ready")
                updater.cancel()
            }
            context.getSharedPreferences("upd", Context.MODE_PRIVATE).edit().clear().commit()
            root.deleteRecursively()
        }
    }

    /** Process replacement may end this case; the host must verify the new
     * package/code/signer and run installer-consent-retained afterwards.
     */
    fun verifyConsent(test: Instrumentation) {
        requireQaBuild(test)
        val base = test.targetContext
        test.uiAutomation.serviceInfo = test.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val candidate = File(base.filesDir, "updater-qa-candidate.apk")
        check(candidate.isFile) { "Host must stage the QA candidate in private app files" }
        val archive = base.packageManager.getPackageArchiveInfo(candidate.path, PackageManager.GET_META_DATA) ?: error("Invalid QA candidate")
        val installed = base.packageManager.getPackageInfo(base.packageName, 0)
        fun code(info: android.content.pm.PackageInfo): Long =
            if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        check(archive.packageName == base.packageName && code(archive) > code(installed)) { "QA candidate must be an increasing-code in-place update" }
        val digest = MessageDigest.getInstance("SHA-256")
        candidate.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val name = archive.versionName ?: error("Missing QA version")
        val request = UpdateRequest("v$name", "https://fixture.invalid/releases/download/v$name/app.apk",
            base.packageName, name, code(archive), hash,
            UpdateDistribution.fromManifest(archive.applicationInfo?.metaData?.get(UpdateDistribution.METADATA), historical = false))
        val marker = "qa-only-${UUID.randomUUID()}"
        val prefs = base.getSharedPreferences("updater-consent-fixture", Context.MODE_PRIVATE)
        val deck = base.getSharedPreferences("deck", Context.MODE_PRIVATE)
        val secret = SecretStore(base, "fish")
        check(!prefs.contains("marker") && !deck.contains("reception_guest_language") &&
            secret.status() == SecretStore.Status.ABSENT) { "Consent fixture requires a fresh disposable emulator app" }
        check(secret.save("fixture-only-voice-$marker"))
        check(deck.edit().putString("reception_guest_language", "fr").commit())
        check(prefs.edit().putString("marker", marker).putLong("expected_code", code(archive))
            .putString("expected_name", name).putLong("first_install_time", installed.firstInstallTime).commit())
        File(base.filesDir, "updater-qa-retained.bin").writeText(marker)
        candidate.copyTo(File(base.filesDir, "update-${request.key}.apk"), overwrite = false)
        val statuses = ArrayList<String>()
        val updater = Updater(base, statuses::add, canInstall = { true })
        val ownership = Updater::class.java.getDeclaredField("ownership").apply { isAccessible = true }.get(updater) as UpdateOwnership
        val phase = Updater::class.java.getDeclaredField("phase").apply { isAccessible = true }
        // Do not bypass production validation, session commit or callback handling.
        test.sendStatus(0, Bundle().apply { putString("stream", "qa_dispatch=actual_updater\n") })
        test.runOnMainSync {
            check(ownership.begin(request) != null)
            phase.set(updater, "ready")
            updater.installOffered()
        }
        val deadline = SystemClock.elapsedRealtime() + 90000
        var clicked = false
        while (SystemClock.elapsedRealtime() < deadline) {
            test.waitForIdleSync()
            val root = test.uiAutomation.rootInActiveWindow
            if (root != null) {
                try {
                    val packageName = root.packageName?.toString()
                    val info = packageName?.let { base.packageManager.getApplicationInfo(it, 0) }
                    val system = info != null && info.enabled && info.flags and
                        (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    if (!clicked && system) clicked = clickConsent(test, root)
                } finally { @Suppress("DEPRECATION") root.recycle() }
            }
            val current = base.packageManager.getPackageInfo(base.packageName, 0)
            if (code(current) == request.versionCode && current.versionName == request.versionName) {
                check(clicked) { "Candidate installed without the required genuine consent observation" }
                return // Some systems retain instrumentation; the host still verifies exact bytes.
            }
            val state = phase.get(updater)
            check(state != "ready" && state != "idle") { "QA install returned to manual/failed state before replacement" }
            SystemClock.sleep(200)
        }
        error(if (clicked) "Consent clicked but package replacement did not terminate the old process"
            else "No trusted English-language Android Install/Update consent button appeared")
    }

    private fun clickConsent(test: Instrumentation, node: AccessibilityNodeInfo): Boolean {
        val id = node.viewIdResourceName?.substringAfterLast('/')
        val text = node.text?.toString()?.trim()?.lowercase()
        if (node.isEnabled && node.isClickable && (id == "button1" || id == "ok_button") && (text == "install" || text == "update")) {
            test.sendStatus(0, Bundle().apply { putString("stream", "qa_consent=trusted_system_positive_button\n") })
            check(node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "System consent button rejected click" }
            return true
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try { if (clickConsent(test, child)) return true }
            finally { @Suppress("DEPRECATION") child.recycle() }
        }
        return false
    }

    fun verifyConsentRetained(test: Instrumentation) {
        requireQaBuild(test)
        val base = test.targetContext
        val prefs = base.getSharedPreferences("updater-consent-fixture", Context.MODE_PRIVATE)
        val marker = prefs.getString("marker", null) ?: error("Missing QA retention seed")
        val installed = base.packageManager.getPackageInfo(base.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else @Suppress("DEPRECATION") installed.versionCode.toLong()
        check(code == prefs.getLong("expected_code", -1) && installed.versionName == prefs.getString("expected_name", null) &&
            installed.firstInstallTime == prefs.getLong("first_install_time", -1)) { "Installed QA candidate identity/continuity mismatch" }
        check(base.getSharedPreferences("deck", Context.MODE_PRIVATE).getString("reception_guest_language", null) == "fr" &&
            File(base.filesDir, "updater-qa-retained.bin").readText() == marker &&
            SecretStore(base, "fish").load() == "fixture-only-voice-$marker") { "QA update did not retain data and encrypted voice credential" }
        test.sendStatus(0, Bundle().apply { putString("stream", "qa_retained=preferences_file_encrypted_key installed_candidate=true\n") })
    }

    private fun requireQaBuild(test: Instrumentation) {
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") { "Use a disposable Android emulator only" }
        val config = test.targetContext.classLoader.loadClass("com.agentdeck.hotel.BuildConfig")
        check(config.getField("DEBUG").getBoolean(null) && (config.getField("FISH_API_KEY").get(null) as String).isBlank()) {
            "Use actual installed key-free QA debug APKs"
        }
    }

    private fun shell(test: Instrumentation, command: String) {
        test.uiAutomation.executeShellCommand(command).use { descriptor ->
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val buffer = ByteArray(1024)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= 8192) { "Unexpected shell diagnostic size" }
                }
            }
        }
    }
}
