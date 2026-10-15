package com.agentdeck.hotel

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
 import android.util.Log
 import android.app.DownloadManager
 import android.app.Notification
 import android.app.NotificationChannel
 import android.app.NotificationManager
 import android.os.Environment
 import okhttp3.OkHttpClient
 import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-update from the public GitHub Releases feed.
 * The APK downloads in the background. A tap installs that local file and does not start the download again.
 */
class Updater(context: Context, private val onStatus: (String) -> Unit) {
    private val app = context.applicationContext
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    private val ui = Handler(Looper.getMainLooper())
    private val checking = AtomicBoolean(false)
    private val action = "dev.orange.hotel.UPDATE_RESULT"

    @Volatile private var phase = "idle"
    @Volatile private var pendingUrl = ""
    @Volatile private var pendingVersion = ""
    @Volatile private var installWhenReady = false
    private val poll = object : Runnable {
        override fun run() { if (pollDownload()) ui.postDelayed(this, 1000) }
    }
     private var receiver: BroadcastReceiver? = null

    private fun versionCode(): Long {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun newer(tag: String) = ReleaseVersion.offer(tag, BuildConfig.VERSION_NAME, versionCode())
    private fun post(text: String) { ui.post { onStatus(text) } }
    private fun prefs() = app.getSharedPreferences("upd", Context.MODE_PRIVATE)
    private fun apkFile(version: String) = File(app.filesDir, "update-${version.filter { it.isLetterOrDigit() || it == '.' }}.apk")

    private fun validApk(file: File): Boolean {
        if (!file.isFile || file.length() < 1_000_000L) return false
        return file.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }
    }

    fun check() {
        if (BuildConfig.DEBUG) return
        val prefs = prefs()
        val cached = prefs.getString("pendingVersion", "").orEmpty()
        val cachedUrl = prefs.getString("pendingUrl", "").orEmpty()
        if (cached.isNotBlank() && cachedUrl.isNotBlank() && newer(cached)) {
            pendingVersion = cached
            pendingUrl = cachedUrl
            when (phase) {
                "downloading", "installing" -> return
                "confirming" -> {
                    post("confirm:The update is already downloaded. Tap once more if the Android prompt closed.")
                    return
                }
                 else -> when {
                     validApk(apkFile(cached)) -> { phase = "ready"; post("offer:$cached") }
                     downloadId() >= 0L -> watch()
                     else -> startDownload()
                 }
            }
        }
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last", 0L) < 10 * 60_000L) return
        if (!checking.compareAndSet(false, true)) return
        prefs.edit().putLong("last", now).apply()
        Thread {
            try { refresh() } catch (e: Exception) { Log.w("UPD", "release check failed", e) }
            finally { checking.set(false) }
        }.start()
    }

    private fun refresh() {
        val feed = Request.Builder().url(BuildConfig.FEED_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Manzanilla")
            .build()
        val body = http.newCall(feed).execute().use { if (!it.isSuccessful) return; it.body?.string() ?: return }
        val rel = JSONObject(body)
        val latest = rel.optString("tag_name").removePrefix("v")
        val prefs = prefs()
        if (!newer(latest)) {
            prefs.edit().remove("pendingVersion").remove("pendingUrl").apply()
            if (phase == "ready") phase = "idle"
            return
        }
        val assets = rel.optJSONArray("assets") ?: return
        var url = ""
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.optString("name").endsWith(".apk")) url = asset.optString("browser_download_url")
        }
        if (url.isEmpty()) return
        pendingVersion = latest
        pendingUrl = url
        prefs.edit().putString("pendingVersion", latest).putString("pendingUrl", url).apply()
        if (phase == "downloading" || phase == "installing" || phase == "confirming") return
        if (validApk(apkFile(latest))) {
            phase = "ready"
            post("offer:$latest")
        } else startDownload()
    }

    fun installOffered() {
        val version = pendingVersion
        if (version.isBlank() || pendingUrl.isBlank()) return
        val file = apkFile(version)
        if (phase == "installing") return
        if (validApk(file)) beginInstall(file) else {
            installWhenReady = true
            if (phase != "downloading") startDownload()
        }
    }

    private fun downloadId() = prefs().getLong("downloadId", -1L)

    private fun downloads() = app.getSystemService(DownloadManager::class.java)

    private fun startDownload() {
        val version = pendingVersion
        val url = pendingUrl
        if (version.isBlank() || url.isBlank()) return
        if (downloadId() >= 0L) { watch(); return }
        val name = "update-${version.filter { it.isLetterOrDigit() || it == '.' }}.apk"
        app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.listFiles()?.forEach { if (it.name == name) it.delete() }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Manzanilla $version")
            .setDescription("Downloading update")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, name)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .addRequestHeader("User-Agent", "Manzanilla")
        val id = try { downloads().enqueue(request) } catch (e: Exception) {
            Log.w("UPD", "enqueue failed", e)
            post("failed:The download could not start.")
            return
        }
        prefs().edit().putLong("downloadId", id).apply()
        watch()
    }

    private fun watch() {
        phase = "downloading"
        post("progress:0")
        ui.removeCallbacks(poll)
        ui.post(poll)
    }

    private fun pollDownload(): Boolean {
        val id = downloadId()
        if (id < 0L) return false
        val cursor = downloads().query(DownloadManager.Query().setFilterById(id)) ?: return false
        cursor.use { c ->
            if (!c.moveToFirst()) {
                prefs().edit().remove("downloadId").apply()
                phase = "idle"
                post("failed:The download was removed.")
                return false
            }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val got = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val pct = if (total > 0) (got * 100 / total).toInt().coerceIn(0, 99) else -1
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> { saveDownload(id); return false }
                DownloadManager.STATUS_FAILED -> {
                    val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                    prefs().edit().remove("downloadId").apply()
                    phase = "idle"
                    note(-1, 0, 0, done = true)
                    post("failed:The download stopped ($reason). Open the app to retry.")
                    return false
                }
                else -> {
                    if (pct >= 0) post("progress:$pct") else post("progress:m${got / 1048576}")
                    note(pct, got, total, done = false)
                    return true
                }
            }
        }
    }

    private fun note(pct: Int, got: Long, total: Long, done: Boolean) {
        val nm = app.getSystemService(NotificationManager::class.java)
        if (done) { nm.cancel(7); return }
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (nm.getNotificationChannel("upd") == null) nm.createNotificationChannel(NotificationChannel("upd", "Updates", NotificationManager.IMPORTANCE_LOW))
        val text = if (pct >= 0) "$pct%" else "${got / 1048576} MB"
        val n = Notification.Builder(app, "upd")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Manzanilla update")
            .setContentText(text)
            .setProgress(100, pct.coerceAtLeast(0), total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(7, n)
    }

    private fun saveDownload(id: Long) {
        ui.removeCallbacks(poll)
        Thread {
            val version = pendingVersion.ifBlank { prefs().getString("pendingVersion", "").orEmpty() }
            val dest = apkFile(version)
            try {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(downloads().openDownloadedFile(id)).use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                }
            } catch (e: Exception) {
                Log.w("UPD", "save failed", e)
                phase = "idle"
                post("failed:The update file could not be saved.")
                return@Thread
            }
            if (!validApk(dest)) {
                dest.delete()
                prefs().edit().remove("downloadId").apply()
                phase = "idle"
                post("failed:The download was not an installable update.")
                return@Thread
            }
            prefs().edit().remove("downloadId").apply()
            note(-1, 0, 0, done = true)
            phase = "ready"
            val installNow = installWhenReady
            installWhenReady = false
            if (installNow) ui.post { beginInstall(dest) } else post("offer:$version")
        }.start()
    }

    private fun beginInstall(file: File) {
        if (!validApk(file)) {
            phase = "idle"
            file.delete()
            startDownload()
            return
        }
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            phase = "confirming"
            val settings = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { app.startActivity(settings) } catch (_: Exception) {}
            post("confirm:Allow Manzanilla to install updates, then tap once more. The file is already downloaded.")
            return
        }
        phase = "installing"
        post("progress:Installing update…")
        ui.post {
            listen()
            Thread {
                try { writeAndCommit(file) }
                catch (e: Exception) {
                    Log.w("UPD", "session install failed", e)
                    ui.post { openSystemInstaller(file) }
                }
            }.start()
        }
    }

    private fun writeAndCommit(file: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("update", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val pending = PendingIntent.getBroadcast(
                app, id,
                Intent(action).setPackage(app.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            session.commit(pending.intentSender)
        }
    }

    private fun listen() {
        receiver?.let { try { app.unregisterReceiver(it) } catch (_: Exception) {} }
        val next = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        phase = "confirming"
                        val confirm = confirmIntent(intent)
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try { c.startActivity(confirm) }
                            catch (_: Exception) { openSystemInstaller(apkFile(pendingVersion)) }
                        } else openSystemInstaller(apkFile(pendingVersion))
                        post("confirm:Confirm the Android install prompt. The file is already downloaded.")
                    }
                    PackageInstaller.STATUS_SUCCESS -> {
                        phase = "idle"
                        prefs().edit().remove("pendingVersion").remove("pendingUrl").apply()
                        apkFile(pendingVersion).delete()
                        post("installed")
                        stopListening()
                    }
                    else -> {
                        Log.w("UPD", "install status ${intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)} ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                        phase = "ready"
                        openSystemInstaller(apkFile(pendingVersion))
                        stopListening()
                    }
                }
            }
        }
        receiver = next
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(next, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(next, filter)
    }

    private fun stopListening() {
        receiver?.let { try { app.unregisterReceiver(it) } catch (_: Exception) {} }
        receiver = null
    }

    private fun confirmIntent(intent: Intent): Intent? {
        return if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
    }

    private fun openSystemInstaller(file: File) {
        if (!validApk(file)) {
            phase = "idle"
            post("failed:The update file is missing. Open the app again to download it.")
            return
        }
        phase = "confirming"
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(UpdateApkProvider.uri(file), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            app.startActivity(view)
            post("confirm:Confirm the Android install prompt. The file is already downloaded.")
        } catch (e: Exception) {
            Log.w("UPD", "system installer did not open", e)
            phase = "ready"
            post("failed:Could not open the Android installer. Tap to try again.")
        }
    }
}
