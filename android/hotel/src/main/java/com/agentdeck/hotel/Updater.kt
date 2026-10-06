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
 * Self-update from the public GitHub Releases feed. Checks for a release whose tag number (v<N>) is
 * higher than this build's versionCode, downloads the APK and hands it to Android's PackageInstaller.
 * Android decides whether a confirmation tap is needed (not needed for a device owner).
 */
class Updater(private val context: Context, private val onStatus: (String) -> Unit) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var busy = false
    private val action = "dev.orange.hotel.UPDATE_RESULT"

    private fun currentCode(): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** Semantic version compare, a.b.c. A tag that is not numeric never counts as newer. */
    private fun newer(tag: String, current: String): Boolean {
        fun parts(v: String): List<Int>? { val p = v.trim().split("."); if (p.isEmpty() || p.any { it.toIntOrNull() == null }) return null; return p.map { it.toInt() } }
        val a = parts(tag) ?: return false; val b = parts(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return x > y }
        return false
    }

    fun check() {
        if (BuildConfig.DEBUG) return
        if (busy) return
        val prefs = context.getSharedPreferences("upd", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last", 0L) < 6 * 3600_000L) return
        prefs.edit().putLong("last", now).apply()
        busy = true
        Thread {
            try {
                val feed = Request.Builder().url("https://api.github.com/repos/somdipto/manzanilla/releases/latest")
                    .header("Accept", "application/vnd.github+json").build()
                val body = http.newCall(feed).execute().use { if (!it.isSuccessful) return@Thread; it.body?.string() ?: return@Thread }
                val rel = JSONObject(body)
                val latest = rel.optString("tag_name").removePrefix("v")
                if (!newer(latest, BuildConfig.VERSION_NAME)) { post("Up to date (${BuildConfig.VERSION_NAME})"); return@Thread }
                if (prefs.getString("offered", "") == latest && now - prefs.getLong("offeredAt", 0L) < 24 * 3600_000L) return@Thread
                prefs.edit().putString("offered", latest).putLong("offeredAt", now).apply()
                val assets = rel.optJSONArray("assets") ?: return@Thread
                var url = ""
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name").endsWith(".apk")) url = a.optString("browser_download_url")
                }
                if (url.isEmpty()) return@Thread
                post("Downloading update $latest")
                val file = File(context.cacheDir, "update.apk")
                http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    if (!r.isSuccessful) return@Thread
                    r.body!!.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
                }
                install(file)
            } catch (_: Exception) {
            } finally { busy = false }
        }.start()
    }

    private fun post(text: String) { ui.post { onStatus(text) } }

    private fun install(file: File) {
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
                    } else { post("Update result $status"); try { c.unregisterReceiver(this) } catch (_: Exception) {} }
                }
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, IntentFilter(action))
            val pi = PendingIntent.getBroadcast(context, id, Intent(action).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            s.commit(pi.intentSender)
        }
    }
}
