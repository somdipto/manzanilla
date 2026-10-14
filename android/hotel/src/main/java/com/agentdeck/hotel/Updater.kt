package com.agentdeck.hotel

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Self-update from the public GitHub Releases feed. A release tag newer than versionName
 * shows an offer; the user taps it, then Android's installer confirms. Debug builds do not check.
 */
class Updater(private val context: Context, private val onStatus: (String) -> Unit) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
    private val action = "dev.orange.hotel.UPDATE_RESULT"

    private fun versionCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }
    private fun newer(tag: String) = ReleaseVersion.offer(tag, BuildConfig.VERSION_NAME, versionCode())
    @Volatile private var pendingUrl = ""
    @Volatile private var pendingVersion = ""

    fun check() {
        if (BuildConfig.DEBUG) return
        if (busy) return
        val prefs = context.getSharedPreferences("upd", Context.MODE_PRIVATE)
        val cached = prefs.getString("pendingVersion", "").orEmpty()
        val cachedUrl = prefs.getString("pendingUrl", "").orEmpty()
        if (cached.isNotBlank() && cachedUrl.isNotBlank() && newer(cached)) {
            pendingVersion = cached
            pendingUrl = cachedUrl
            post("offer:$cached")
        }
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last", 0L) < 10 * 60_000L) return
        prefs.edit().putLong("last", now).apply()
        busy = true
        Thread {
            try {
                val feed = Request.Builder().url(BuildConfig.FEED_URL)
                    .header("Accept", "application/vnd.github+json").build()
                val body = http.newCall(feed).execute().use { if (!it.isSuccessful) return@Thread; it.body?.string() ?: return@Thread }
                val rel = JSONObject(body)
                val latest = rel.optString("tag_name").removePrefix("v")
                if (!newer(latest)) {
                    prefs.edit().remove("pendingVersion").remove("pendingUrl").apply()
                    return@Thread
                }
                val assets = rel.optJSONArray("assets") ?: return@Thread
                var url = ""
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
                }
                if (url.isEmpty()) return@Thread
                pendingVersion = latest
                pendingUrl = url
                prefs.edit().putString("pendingVersion", latest).putString("pendingUrl", url).apply()
                post("offer:$latest")
            } catch (_: Exception) {
            } finally { busy = false }
        }.start()
    }

    fun installOffered() {
        val url = pendingUrl
        val latest = pendingVersion
        if (url.isBlank() || latest.isBlank() || busy) return
        busy = true
        post("Downloading update $latest")
        Thread {
            try {
                val file = File(context.cacheDir, "update.apk")
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) { post("Update download failed"); return@Thread }
                    r.body!!.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                }
                post("Installing update $latest")
                install(file) { ok ->
                    if (ok) context.getSharedPreferences("upd", Context.MODE_PRIVATE).edit().remove("pendingVersion").remove("pendingUrl").apply()
                    post(if (ok) "Update installed" else "Update needs a confirmation tap")
                }
            } catch (_: Exception) {
                post("Update download failed")
            } finally { busy = false }
        }.start()
    }

    private fun post(text: String) { ui.post { onStatus(text) } }

    private fun install(file: File, done: (Boolean) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            file.inputStream().use { input -> s.openWrite("update", 0, file.length()).use { out -> input.copyTo(out); s.fsync(out) } }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); if (confirm != null) c.startActivity(confirm)
                    } else { done(status == PackageInstaller.STATUS_SUCCESS); post("Update result $status"); try { c.unregisterReceiver(this) } catch (_: Exception) {} }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, IntentFilter(action))
            val pi = PendingIntent.getBroadcast(context, id, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(pi.intentSender)
        }
    }
}
