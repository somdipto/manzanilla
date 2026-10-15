package com.agentdeck.hotel

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.android.apksig.ApkVerifier
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One pinned, persisted release per attempt. Feed refreshes never rename an in-flight APK. */
class Updater(context: Context, private val onStatus: (String) -> Unit) {
    private val app = context.applicationContext
    private val http = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS).followRedirects(true).build()
    private val ui = Handler(Looper.getMainLooper())
    private val checking = AtomicBoolean(false)
    private val ownership = UpdateOwnership()
    private val action = "dev.orange.hotel.UPDATE_RESULT"
    private var phase = "idle"
    private var restored = false
    private var installWhenReady = false
    private var receiver: BroadcastReceiver? = null
    @Volatile private var installSessionId = -1
    private val poll = object : Runnable {
        override fun run() { if (pollDownload()) ui.postDelayed(this, 1000) }
    }

    private fun prefs() = app.getSharedPreferences("upd", Context.MODE_PRIVATE)
    private fun downloads() = app.getSystemService(DownloadManager::class.java)
    private fun apkFile(request: UpdateRequest) = File(app.filesDir, "update-${request.key}.apk")
    private fun externalFile(request: UpdateRequest) = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?.let { File(it, "update-${request.key}.apk") }
    private fun onUi(block: () -> Unit) { if (Looper.myLooper() == Looper.getMainLooper()) block() else ui.post { block() } }
    private fun status(attempt: UpdateAttempt, text: String) { if (ownership.owns(attempt)) onStatus(text) }

    @Suppress("DEPRECATION")
    private fun code(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    @Suppress("DEPRECATION")
    private fun installed(): ApkIdentity {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = app.packageManager.getPackageInfo(app.packageName, flags)
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return ApkIdentity(info.packageName, info.versionName.orEmpty(), code(info),
            signatures?.map { UpdateRequest.hash(it.toByteArray()) }?.toSet() ?: emptySet())
    }

    /** apksig verifies the entire signed archive, not just ZIP magic or untrusted manifest certificates. */
    private fun validate(file: File, request: UpdateRequest) {
        require(file.isFile && file.length() > 0) { "The update file is missing." }
        val verification = ApkVerifier.Builder(file).setMinCheckedPlatformVersion(26).build().verify()
        require(verification.isVerified) { "The APK signature or signed contents are invalid." }
        val info = app.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: error("The download is not an Android package.")
        val apk = ApkIdentity(info.packageName, info.versionName.orEmpty(), code(info),
            verification.signerCertificates.map { UpdateRequest.hash(it.encoded) }.toSet())
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        request.rejects(apk, installed(), hash)?.let { error(it) }
    }

    private fun eligible(request: UpdateRequest): Boolean = installed().let {
        request.packageName == it.packageName && ReleaseVersion.offer(request.tag, it.versionName, it.versionCode, request.versionCode)
    }

    private fun persist(attempt: UpdateAttempt) {
        if (ownership.owns(attempt)) prefs().edit().putString("request", attempt.request.json().toString())
            .putLong("downloadId", ownership.downloadId).commit()
    }

    /** Only updater-owned artifacts/preferences are migrated; app settings, keys and data are untouched. */
    private fun discardObsoleteCache() {
        val id = prefs().getLong("downloadId", -1L)
        if (id >= 0) downloads().remove(id)
        prefs().edit().remove("request").remove("pendingVersion").remove("pendingUrl").remove("downloadId").commit()
        listOfNotNull(app.filesDir, app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)).forEach { dir ->
            dir.listFiles()?.filter { it.name.startsWith("update-") && (it.name.endsWith(".apk") || it.name.endsWith(".part")) }
                ?.forEach { it.delete() }
        }
    }

    fun check() = onUi {
        if (BuildConfig.DEBUG) return@onUi
        if (!restored) {
            restored = true
            app.filesDir.listFiles()?.filter { it.name.startsWith("update-") && it.name.endsWith(".part") }
                ?.forEach { it.delete() }
            val saved = prefs().getString("request", null)
            val request = try { saved?.let { UpdateRequest.fromJson(JSONObject(it)) } } catch (_: Exception) { null }
            if (request != null && eligible(request)) {
                val attempt = ownership.begin(request)!!
                val id = prefs().getLong("downloadId", -1L)
                if (id >= 0) ownership.attach(attempt, id)
            } else discardObsoleteCache()
        }
        ownership.active?.let { attempt ->
            when (phase) {
                "downloading", "validating", "installing" -> Unit
                "confirming" -> status(attempt, "confirm:The update is already downloaded. Tap once more if the Android prompt closed.")
                "ready" -> verifyAndOffer(attempt)
                else -> resume(attempt)
            }
            return@onUi
        }
        val now = System.currentTimeMillis()
        if (now - prefs().getLong("last", 0L) < 10 * 60_000L || !checking.compareAndSet(false, true)) return@onUi
        prefs().edit().putLong("last", now).apply()
        val refreshGeneration = ownership.generation
        Thread {
            try {
                val request = releaseRequest()
                ui.post {
                    if (request != null && ownership.generation == refreshGeneration && ownership.active == null && eligible(request)) {
                        ownership.begin(request)?.let { persist(it); resume(it) }
                    }
                }
            } catch (e: Exception) { Log.w("UPD", "Release metadata unavailable", e) }
            finally { checking.set(false) }
        }.start()
    }

    private fun getJson(url: String): JSONObject {
        require(Uri.parse(url).scheme == "https") { "Update metadata requires HTTPS." }
        val request = Request.Builder().url(url).header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Manzanilla").build()
        return http.newCall(request).execute().use {
            check(it.isSuccessful) { "Release metadata HTTP ${it.code}" }
            JSONObject(it.body?.string() ?: error("Empty release metadata"))
        }
    }

    private fun releaseRequest(): UpdateRequest? {
        val release = getJson(BuildConfig.FEED_URL)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.getString("tag_name")
        if (!ReleaseVersion.valid(tag) || !tag.startsWith("v")) return null
        val assets = release.getJSONArray("assets")
        val entries = (0 until assets.length()).map { assets.getJSONObject(it) }
        val manifest = entries.singleOrNull { it.optString("name") == "release-metadata.json" } ?: return null
        val request = UpdateRequest.fromJson(getJson(manifest.getString("browser_download_url")))
        require(request.tag == tag) { "Release tag/metadata mismatch" }
        val asset = entries.singleOrNull { it.optString("browser_download_url") == request.url && it.optString("name").endsWith(".apk") }
            ?: error("Release APK/metadata mismatch")
        require(Uri.parse(request.url).path.orEmpty().contains("/releases/download/$tag/")) { "APK URL is not pinned to its release" }
        val apiDigest = asset.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
        if (request.sha256 != null && apiDigest != null) require(request.sha256 == apiDigest) { "Release digests disagree" }
        return request.copy(sha256 = apiDigest ?: request.sha256)
    }

    private fun resume(attempt: UpdateAttempt) {
        if (!ownership.owns(attempt)) return
        when {
            apkFile(attempt.request).isFile -> verifyAndOffer(attempt)
            ownership.downloadId >= 0 -> watch(attempt)
            else -> startDownload(attempt)
        }
    }

    fun installOffered() = onUi {
        val attempt = ownership.active ?: run { check(); return@onUi }
        if (phase == "installing") return@onUi
        installWhenReady = true
        if (phase != "downloading" && phase != "validating") resume(attempt)
    }

    /** Cancels only this updater's immutable attempt, including its DownloadManager job. */
    fun cancel() = onUi {
        if (phase == "installing") return@onUi // A committed Android installation is owned by the OS.
        val attempt = ownership.active ?: run { ownership.cancel(); return@onUi }
        discard(attempt)
    }

    private fun discard(attempt: UpdateAttempt) {
        val id = ownership.downloadId
        if (!ownership.invalidate(attempt)) return
        if (id >= 0) downloads().remove(id)
        ui.removeCallbacks(poll)
        stopListening()
        abandonInstall()
        apkFile(attempt.request).delete()
        externalFile(attempt.request)?.delete()
        prefs().edit().remove("request").remove("downloadId").remove("last").commit()
        installWhenReady = false
        phase = "idle"
        note(done = true)
    }

    private fun startDownload(attempt: UpdateAttempt) {
        if (!ownership.owns(attempt)) return
        if (ownership.downloadId >= 0) { watch(attempt); return }
        externalFile(attempt.request)?.delete()
        val request = DownloadManager.Request(Uri.parse(attempt.request.url))
            .setTitle("Manzanilla ${attempt.request.versionName}").setDescription("Downloading update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, "update-${attempt.request.key}.apk")
            .setAllowedOverMetered(true).setAllowedOverRoaming(true).addRequestHeader("User-Agent", "Manzanilla")
        try {
            val id = downloads().enqueue(request)
            ownership.attach(attempt, id)
            persist(attempt)
            watch(attempt)
        } catch (e: Exception) { fail(attempt, "The download could not start.", e) }
    }

    private fun watch(attempt: UpdateAttempt) {
        phase = "downloading"
        status(attempt, "progress:0")
        ui.removeCallbacks(poll)
        ui.post(poll)
    }

    private fun pollDownload(): Boolean {
        val attempt = ownership.active ?: return false
        val id = ownership.downloadId
        if (id < 0) return false
        try {
            val cursor = downloads().query(DownloadManager.Query().setFilterById(id)) ?: error("Download unavailable")
            cursor.use { c ->
                check(c.moveToFirst()) { "The download was removed." }
                val url = c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_URI))
                check(ownership.ownsDownload(attempt, id, url)) { "The download does not belong to this release." }
                val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                    DownloadManager.STATUS_SUCCESSFUL -> { saveDownload(attempt, id); return false }
                    DownloadManager.STATUS_FAILED -> error("The download stopped. Tap to check for an update.")
                    else -> {
                        val pct = if (total > 0) (got * 100 / total).toInt().coerceIn(0, 99) else -1
                        status(attempt, if (pct >= 0) "progress:$pct" else "progress:m${got / 1048576}")
                        note(pct, got, total)
                    }
                }
            }
        } catch (e: Exception) {
            fail(attempt, e.message ?: "The download could not be read.", e)
            return false
        }
        return true
    }

    private fun saveDownload(attempt: UpdateAttempt, id: Long) {
        phase = "validating"
        val part = File(app.filesDir, "update-${attempt.request.key}-${attempt.revision}.part")
        Thread {
            try {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(downloads().openDownloadedFile(id)).use { input ->
                    part.outputStream().use { input.copyTo(it) }
                }
                validate(part, attempt.request)
                ui.post {
                    if (!ownership.ownsDownload(attempt, id, attempt.request.url)) { part.delete(); return@post }
                    val dest = apkFile(attempt.request)
                    if (!part.renameTo(dest)) { part.delete(); fail(attempt, "The update file could not be saved."); return@post }
                    downloads().remove(id)
                    ownership.detach(attempt)
                    persist(attempt)
                    ready(attempt)
                }
            } catch (e: Exception) {
                part.delete()
                ui.post {
                    if (!ownership.owns(attempt)) return@post
                    fail(attempt, e.message ?: "The update could not be verified.", e)
                }
            }
        }.start()
    }

    private fun verifyAndOffer(attempt: UpdateAttempt) {
        phase = "validating"
        val file = apkFile(attempt.request)
        Thread {
            try { validate(file, attempt.request); ui.post { if (ownership.owns(attempt)) ready(attempt) } }
            catch (e: Exception) { ui.post {
                if (ownership.owns(attempt)) fail(attempt, e.message ?: "The cached update is invalid.", e)
            } }
        }.start()
    }

    private fun ready(attempt: UpdateAttempt) {
        if (ownership.downloadId >= 0) {
            downloads().remove(ownership.downloadId)
            ownership.detach(attempt)
            persist(attempt)
        }
        note(done = true)
        phase = "ready"
        if (installWhenReady) { installWhenReady = false; beginInstall(attempt) }
        else status(attempt, "offer:${attempt.request.versionName}")
    }

    private fun fail(attempt: UpdateAttempt, text: String, error: Exception? = null) {
        if (!ownership.owns(attempt)) return
        if (error != null) Log.w("UPD", text, error)
        discard(attempt)
        onStatus("failed:$text")
    }

    private fun beginInstall(attempt: UpdateAttempt) {
        if (!ownership.owns(attempt)) return
        abandonInstall()
        if (!app.packageManager.canRequestPackageInstalls()) {
            phase = "confirming"
            try { app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (e: Exception) { fail(attempt, "Android install permission settings could not open.", e); return }
            status(attempt, "confirm:Allow Manzanilla to install updates, then tap once more. The file is already downloaded.")
            return
        }
        phase = "installing"
        status(attempt, "progress:Installing update…")
        listen(attempt)
        Thread {
            try {
                val file = apkFile(attempt.request)
                validate(file, attempt.request) // Revalidate immediately before handing bytes to Android.
                writeAndCommit(file, attempt)
            } catch (e: Exception) { ui.post {
                if (ownership.owns(attempt)) {
                    Log.w("UPD", "PackageInstaller session failed", e)
                    stopListening()
                    abandonInstall()
                    openSystemInstaller(attempt)
                }
            } }
        }.start()
    }

    private fun openSystemInstaller(attempt: UpdateAttempt) {
        phase = "validating"
        val file = apkFile(attempt.request)
        Thread {
            try {
                validate(file, attempt.request)
                ui.post {
                    if (!ownership.owns(attempt)) return@post
                    try {
                        app.startActivity(Intent(Intent.ACTION_VIEW)
                            .setDataAndType(UpdateApkProvider.uri(file), "application/vnd.android.package-archive")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        phase = "confirming"
                        status(attempt, "confirm:Confirm the Android install prompt. The file is already downloaded.")
                    } catch (e: Exception) { fail(attempt, "The Android installer could not open.", e) }
                }
            } catch (e: Exception) { ui.post { fail(attempt, e.message ?: "The update is invalid.", e) } }
        }.start()
    }

    private fun writeAndCommit(file: File, attempt: UpdateAttempt) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(attempt.request.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        val id = installer.createSession(params)
        installSessionId = id
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input -> session.openWrite("update.apk", 0, file.length()).use { out -> input.copyTo(out); session.fsync(out) } }
                val pending = PendingIntent.getBroadcast(app, id, Intent(action).setPackage(app.packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) { installer.abandonSession(id); throw e }
    }

    // Pre-33 receivers have no export flag; the filter carries an app-private action and setPackage().
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun listen(attempt: UpdateAttempt) {
        stopListening()
        val next = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!ownership.owns(attempt) || intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != installSessionId) return
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        phase = "confirming"
                        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                            else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        try {
                            check(confirm != null) { "Android returned no installation prompt." }
                            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) { fail(attempt, "The Android installer could not open.", e) }
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        phase = "installed"
                        stopListening()
                    }
                    else -> {
                        phase = "ready"
                        status(attempt, "failed:Android did not install the update. Tap to retry or cancel.")
                        stopListening()
                    }
                }
            }
        }
        receiver = next
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(next, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(next, IntentFilter(action))
    }

    private fun stopListening() {
        receiver?.let { try { app.unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null
    }

    private fun abandonInstall() {
        val id = installSessionId
        installSessionId = -1
        if (id >= 0) try { app.packageManager.packageInstaller.abandonSession(id) } catch (_: Exception) {}
    }

    private fun note(pct: Int = 0, got: Long = 0, total: Long = 0, done: Boolean = false) {
        val manager = app.getSystemService(NotificationManager::class.java)
        if (done) { manager.cancel(7); return }
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (manager.getNotificationChannel("upd") == null) manager.createNotificationChannel(NotificationChannel("upd", "Updates", NotificationManager.IMPORTANCE_LOW))
        manager.notify(7, Notification.Builder(app, "upd").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Manzanilla update").setContentText(if (pct >= 0) "$pct%" else "${got / 1048576} MB")
            .setProgress(100, pct.coerceAtLeast(0), total <= 0).setOngoing(true).setOnlyAlertOnce(true).build())
    }
}
