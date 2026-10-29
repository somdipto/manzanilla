package com.agentdeck.hotel

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Installed only on a fresh disposable emulator. No production application class references:
 * this candidate test APK must also run against the previously published application.
 */
class UpgradeDataFixture : Instrumentation() {
    private var args: Bundle? = null

    override fun onCreate(arguments: Bundle?) {
        args = arguments
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val case = args?.getString("case").takeIf {
            it in setOf("seed", "verify", "bundled", "reception", "reception-missing-script")
        } ?: "unknown"
        try {
            check(args?.getString("isolated") == "1")
            check(Build.HARDWARE in setOf("ranchu", "goldfish"))
            check(targetContext.packageName == TARGET && context.packageName == "$TARGET.test")
            val distribution = args?.getString("distribution")
                ?.takeIf { it in setOf("personal-test", "hotel") } ?: error("Missing distribution")
            val buildConfig = targetContext.classLoader.loadClass("com.agentdeck.hotel.BuildConfig")
            val declared = try { buildConfig.getField("DISTRIBUTION").get(null) as String }
                catch (_: NoSuchFieldException) { check(case == "seed"); "personal-test" }
            check(declared == distribution)
            @Suppress("DEPRECATION")
            val manifest = targetContext.packageManager.getApplicationInfo(TARGET, PackageManager.GET_META_DATA)
                .metaData?.getString("dev.orange.hotel.DISTRIBUTION")
            check(manifest == distribution || (case == "seed" && manifest == null && distribution == "personal-test"))
            if (case == "reception" || case == "reception-missing-script") {
                verifyReception(case == "reception-missing-script")
                result.putString("stream", if (case == "reception")
                    "PASS case=reception ui_ready=true bridge=true mic_off=true distribution=$distribution\n"
                    else "PASS case=reception-missing-script ui_rejected=true\n")
                finish(-1, result)
                return
            }
            val marker = args?.getString("marker") ?: error("Missing fixture marker")
            check(Regex("[0-9a-fA-F]{40}").matches(marker))
            val fixture = targetContext.getSharedPreferences("upgrade-data-fixture", Context.MODE_PRIVATE)
            val deck = targetContext.getSharedPreferences("deck", Context.MODE_PRIVATE)
            val file = File(targetContext.filesDir, "upgrade-data-fixture.bin")
            val alias = "manzanilla-upgrade-fixture-$marker"
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            // Invoke each installed APK's actual credential implementation. This
            // fixture also runs on the previous release, so do not link candidate
            // production classes into the test APK. Only synthetic data is used.
            val secretClass = targetContext.classLoader.loadClass("com.agentdeck.hotel.SecretStore")
            val voiceStore = secretClass.getConstructor(Context::class.java, String::class.java)
                .newInstance(targetContext, "fish")
            val voiceMarker = "fixture-only-voice-$marker"

            when (case) {
                "seed" -> {
                    check(!fixture.contains("marker") && !file.exists() && !store.containsAlias(alias))
                    // Refuse to overwrite existing application preferences, even on an emulator.
                    check(PREFERENCES.keys.none(deck::contains))
                    check(secretClass.getMethod("status").invoke(voiceStore).toString() == "ABSENT")
                    check(secretClass.getMethod("save", String::class.java).invoke(voiceStore, voiceMarker) == true)
                    check(secretClass.getMethod("load").invoke(voiceStore) == voiceMarker)
                    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                    generator.init(KeyGenParameterSpec.Builder(alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, generator.generateKey())
                    val encrypted = cipher.doFinal(marker.toByteArray(Charsets.UTF_8))
                    file.outputStream().use { output ->
                        output.write(marker.toByteArray(Charsets.UTF_8))
                        output.fd.sync()
                    }
                    check(deck.edit().apply { PREFERENCES.forEach { (name, value) -> putString(name, value) } }.commit())
                    check(fixture.edit().putString("marker", marker)
                        .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                        .putString("encrypted", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit())
                }
                "verify" -> {
                    check(fixture.getString("marker", null) == marker)
                    check(PREFERENCES.all { (name, value) -> deck.getString(name, null) == value })
                    check(file.readText(Charsets.UTF_8) == marker)
                    val key = store.getKey(alias, null)
                    check(key != null)
                    val iv = Base64.decode(fixture.getString("iv", null) ?: error("Missing fixture IV"), Base64.NO_WRAP)
                    val encrypted = Base64.decode(fixture.getString("encrypted", null) ?: error("Missing encrypted fixture"), Base64.NO_WRAP)
                    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                    check(String(cipher.doFinal(encrypted), Charsets.UTF_8) == marker)
                    check(secretClass.getMethod("load").invoke(voiceStore) == voiceMarker)
                    check(secretClass.getMethod("status").invoke(voiceStore).toString() == "READY")
                }
                "bundled" -> {
                    // Inspect only the actual installed field; never print or hash its value.
                    // Keep the synthetic saved key, including in managed-only hotel builds.
                    check(secretClass.getMethod("load").invoke(voiceStore) == voiceMarker)
                    val bundled = buildConfig.getField("FISH_API_KEY").get(null) as String
                    check(if (distribution == "personal-test") bundled.isNotBlank() else bundled.isEmpty())
                    val statusClass = targetContext.classLoader.loadClass("com.agentdeck.hotel.SecretStore\$Status")
                    val absent = statusClass.enumConstants.single { it.toString() == "ABSENT" }
                    val policy = targetContext.classLoader.loadClass("com.agentdeck.hotel.AudioPolicy")
                    val gate = policy.getMethod("blockReason", Boolean::class.javaPrimitiveType,
                        statusClass, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                        String::class.java).invoke(policy.getField("INSTANCE").get(null),
                            false, absent, bundled.isNotBlank(), false, distribution) as String
                    check(if (distribution == "personal-test") gate.isEmpty() else gate.isNotEmpty())
                    if (distribution == "hotel") {
                        val ready = statusClass.enumConstants.single { it.toString() == "READY" }
                        val savedGate = policy.getMethod("blockReason", Boolean::class.javaPrimitiveType,
                            statusClass, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
                            String::class.java).invoke(policy.getField("INSTANCE").get(null),
                                false, ready, false, true, distribution) as String
                        check(savedGate.isNotEmpty())
                    }
                }
                else -> error("Unknown fixture case")
            }
            val evidence = if (case == "bundled") {
                if (distribution == "personal-test") "bundled_key=true direct_ready=true"
                else "bundled_key=false managed_required=true"
            } else "preferences=true private_file=true encrypted_marker=true voice_credential=true"
            result.putString("stream", "PASS case=$case $evidence distribution=$distribution\n")
            finish(-1, result)
        } catch (error: Throwable) {
            // Only a fixture source location is safe; never device values or exception text.
            val line = error.stackTrace.firstOrNull { it.fileName == "UpgradeDataFixture.kt" }?.lineNumber ?: 0
            result.putString("stream", "FAIL case=$case upgrade data fixture did not complete at=UpgradeDataFixture.kt:$line\n")
            finish(0, result)
        }
    }

    /** No candidate production class linkage: use the installed target's activity and page. */
    private fun verifyReception(missingScript: Boolean) {
        val activityClass = targetContext.classLoader.loadClass("com.agentdeck.hotel.HotelActivity")
        val activity = startActivitySync(Intent().setClassName(TARGET, activityClass.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        fun <T> main(block: () -> T): T {
            val completed = CountDownLatch(1)
            val value = AtomicReference<Result<T>>()
            check(Handler(Looper.getMainLooper()).post {
                value.set(runCatching(block))
                completed.countDown()
            })
            val remaining = deadline - System.nanoTime()
            check(remaining > 0 && completed.await(remaining, TimeUnit.NANOSECONDS))
            return value.get().getOrThrow()
        }
        fun field(owner: Any, name: String): Any? =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        val page = main { field(activity, "reception") as ViewGroup }
        fun webView(view: View): WebView? {
            if (view is WebView) return view
            if (view is ViewGroup) for (index in 0 until view.childCount) {
                webView(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        val browser = main { webView(page) ?: error("Missing installed WebView") }
        fun evaluate(script: String): String {
            val remaining = deadline - System.nanoTime()
            check(remaining > 0)
            val completed = CountDownLatch(1)
            var value = ""
            main { browser.evaluateJavascript(script) { value = it; completed.countDown() } }
            check(completed.await((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS))
            return value
        }
        fun await(predicate: () -> Boolean) {
            while (!predicate()) {
                check(System.nanoTime() < deadline)
                Thread.sleep(100)
            }
        }
        fun nativeOff(): Boolean = main {
            val owner = field(activity, "owner") ?: error("Missing native owner")
            field(activity, "audioState") == "off" &&
                owner.javaClass.getMethod("getCurrent").invoke(owner) == null &&
                field(activity, "pendingPermission") == null
        }
        fun renderedHome(): Boolean = evaluate("""
            typeof window.manzania === 'object' && typeof window.manzania.receive === 'function' &&
            !!window.MANZANILLA_UI && document.documentElement.dataset.page === 'idle' &&
            document.documentElement.dataset.captureActive === 'false' &&
            !!document.querySelector('.welcome h1') &&
            document.querySelector('.welcome h1').textContent.trim().length > 0 &&
            !!document.querySelector('[data-action=translation]') &&
            document.getElementById('privacy').textContent.trim() ===
              window.MANZANILLA_UI.messages['Mic off'][document.documentElement.lang] &&
            !document.querySelector('.mic-live')
        """.trimIndent()) == "true"
        fun shown(): Boolean = main {
            activity.hasWindowFocus() && browser.isShown && browser.width > 0 && browser.height > 0 &&
                page.childCount == 1 && field(page, "ready") == true
        }
        try {
            await { renderedHome() && shown() && nativeOff() }
            if (missingScript) {
                main {
                    val realClient = browser.webViewClient
                    browser.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            if (request?.url?.path == "/reception.js") return WebResourceResponse(
                                "application/javascript", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                            return realClient.shouldInterceptRequest(view, request)
                        }
                        override fun onPageFinished(view: WebView?, url: String?) = realClient.onPageFinished(view, url)
                    }
                    browser.reload()
                }
                await { main { browser.visibility != View.VISIBLE && page.childCount > 1 } }
                check(!renderedHome() && !shown() && nativeOff())
                return
            }
            val deck = targetContext.getSharedPreferences("deck", Context.MODE_PRIVATE)
            check(deck.getString("reception_guest_language", null) == PREFERENCES["reception_guest_language"])
            check(deck.getString("reception_staff_language", null) == PREFERENCES["reception_staff_language"])
            evaluate("document.querySelector('[data-action=translation]').click()")
            await { evaluate("document.documentElement.dataset.page === 'translation_languages'") == "true" }
            // The real packaged click must cross addJavascriptInterface and persist in native.
            evaluate("document.querySelector('[data-language-side=language]').value = 'en'; document.querySelector('[data-language-side=language]').dispatchEvent(new Event('change', {bubbles:true}))")
            await { deck.getString("reception_guest_language", null) == "en" &&
                evaluate("document.documentElement.lang === 'en'") == "true" }
            check(nativeOff())
            evaluate("document.querySelector('[data-language-side=language]').value = 'fr'; document.querySelector('[data-language-side=language]').dispatchEvent(new Event('change', {bubbles:true}))")
            await { deck.getString("reception_guest_language", null) == "fr" }
            // Exercise the installed Activity's Android Back path, then its native-to-JS event.
            main {
                @Suppress("DEPRECATION")
                activity.onBackPressed()
            }
            await { renderedHome() && shown() && nativeOff() }
            check(PREFERENCES.all { (name, value) -> deck.getString(name, null) == value })
            val screenshot = uiAutomation.takeScreenshot() ?: error("Missing UI screenshot")
            try {
                val directory = context.getExternalFilesDir(null) ?: error("Missing private evidence directory")
                File(directory, "signed-reception.png").outputStream().use {
                    check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { screenshot.recycle() }
        } finally {
            main { activity.finish() }
        }
    }

    private companion object {
        const val TARGET = "dev.orange.hotel"
        val PREFERENCES = mapOf("reception_appearance" to "light",
            "reception_guest_language" to "fr", "reception_staff_language" to "de")
    }
}
