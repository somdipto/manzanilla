package com.agentdeck.hotel

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
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
            it in setOf("seed", "verify", "provider-credentials", "reception", "reception-missing-script")
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
                "provider-credentials" -> {
                    // Inspect the installed candidate, never print or hash a credential.
                    // An in-place update must still retain the user's encrypted key.
                    check(secretClass.getMethod("load").invoke(voiceStore) == voiceMarker)
                    for (name in listOf("FISH_API_KEY", "GEMINI_API_KEY", "LLM_API_KEY", "TRANSLATION_API_KEY")) {
                        val field = try { buildConfig.getField(name) } catch (_: NoSuchFieldException) { null }
                        if (field != null) check((field.get(null) as String).isEmpty())
                    }
                }
                else -> error("Unknown fixture case")
            }
            val evidence = if (case == "provider-credentials") "provider_keys_absent=true"
                else "preferences=true private_file=true encrypted_marker=true voice_credential=true"
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
        // This is a packaged-UI proof, not provider provisioning. Keep the real
        // installed transport and persistent credential, but fence its resolver
        // so optional chooser preparation cannot contact an external provider.
        fun isolateProvider() = main {
            val provider = field(activity, "fishProvider") ?: error("Missing installed voice provider")
            val voice = checkNotNull(provider.javaClass.getMethod("getValue").apply {
                isAccessible = true
            }.invoke(provider))
            val resolver = voice.javaClass.getDeclaredField("key").apply { isAccessible = true }
            resolver.set(voice, java.lang.reflect.Proxy.newProxyInstance(
                resolver.type.classLoader, arrayOf(resolver.type)
            ) { proxy, method, arguments ->
                when (method.name) {
                    "invoke" -> ""
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    "toString" -> "isolated-empty-provider-resolver"
                    else -> error("Unexpected provider resolver callback")
                }
            })
        }
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
            !!document.querySelector('[data-action=translation]') &&
            !document.querySelector('.mic-live')
        """.trimIndent()) == "true"
        fun shown(): Boolean = main {
            activity.hasWindowFocus() && browser.isShown && browser.width > 0 && browser.height > 0 &&
                page.childCount == 1 && field(page, "ready") == true
        }
        // Home has no entrance animation. Its aurora runs forever, so waiting for
        // all animation to stop would never settle. Inspect the actual localized
        // text/layout, then fence WebView rasterization and the native window draw.
        val homeInk = """
            (function () {
              if (document.documentElement.lang !== 'fr' ||
                  (document.fonts && document.fonts.status !== 'loaded')) return null;
              if (document.getAnimations && document.getAnimations().some(function (animation) {
                return animation.playState === 'running' && animation.effect &&
                  animation.effect.getTiming().iterations !== Infinity;
              })) return null;
              var marks = [];
              var labels = [
                ['.welcome h1', 'Welcome.'],
                ['label[for=guest-locale]', 'Language'],
                ['[data-action=translation] > span:not(.orb)', 'Live translation']
              ];
              for (var i = 0; i < labels.length; i++) {
                var element = document.querySelector(labels[i][0]);
                if (!element || element.textContent !== window.MANZANILLA_UI.messages[labels[i][1]].fr)
                  return null;
                for (var parent = element; parent; parent = parent.parentElement) {
                  var style = getComputedStyle(parent);
                  if (style.display === 'none' || style.visibility !== 'visible' ||
                      Number(style.opacity) < 1) return null;
                }
                var range = document.createRange();
                range.selectNodeContents(element);
                var rects = range.getClientRects();
                if (!rects.length) return null;
                var color = getComputedStyle(element).color.match(/[\d.]+/g).slice(0, 3).map(Number);
                for (var j = 0; j < rects.length; j++) {
                  var rect = rects[j];
                  if (rect.width <= 0 || rect.height <= 0 || rect.left < 0 || rect.top < 0 ||
                      rect.right > innerWidth || rect.bottom > innerHeight) return null;
                  var hit = document.elementFromPoint((rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2);
                  if (hit !== element && !element.contains(hit)) return null;
                  marks.push({rect:[rect.left, rect.top, rect.right, rect.bottom], color:color});
                }
              }
              if (document.querySelector('#guest-locale').value !== 'fr') return null;
              return {width:innerWidth, height:innerHeight, marks:marks};
            })()
        """.trimIndent()
        fun committedDraw() {
            val completed = CountDownLatch(1)
            main {
                browser.postVisualStateCallback(1L, object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (Build.VERSION.SDK_INT >= 29 && browser.isHardwareAccelerated) {
                            browser.viewTreeObserver.registerFrameCommitCallback { completed.countDown() }
                        } else {
                            val observer = browser.viewTreeObserver
                            observer.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
                                override fun onDraw() {
                                    browser.post {
                                        if (observer.isAlive) observer.removeOnDrawListener(this)
                                        completed.countDown()
                                    }
                                }
                            })
                        }
                        browser.invalidate()
                    }
                })
            }
            check(completed.await((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS))
        }
        fun paintedText(screenshot: Bitmap, layout: JSONObject): Boolean {
            val origin = main { IntArray(2).also(browser::getLocationOnScreen) }
            val scale = main { browser.width.toDouble() / layout.getDouble("width") }
            val marks = layout.getJSONArray("marks")
            for (index in 0 until marks.length()) {
                val mark = marks.getJSONObject(index)
                val rect = mark.getJSONArray("rect")
                val color = mark.getJSONArray("color")
                val left = origin[0] + rect.getDouble(0) * scale
                val top = (origin[1] + rect.getDouble(1) * scale).toInt()
                val right = origin[0] + rect.getDouble(2) * scale
                val bottom = (origin[1] + rect.getDouble(3) * scale).toInt()
                if (left < 0 || top < 0 || right > screenshot.width || bottom > screenshot.height) return false
                // Each quarter of each text line needs both glyph ink and its
                // surrounding negative space. Blank/stale layers and solid bars
                // cannot pass merely by being nonempty or the expected color.
                for (part in 0 until 4) {
                    val start = (left + (right - left) * part / 4).toInt()
                    val end = (left + (right - left) * (part + 1) / 4).toInt()
                    var ink = 0
                    var background = 0
                    val pixels = (end - start) * (bottom - top)
                    if (pixels <= 0) return false
                    for (y in top until bottom) for (x in start until end) {
                        val pixel = screenshot.getPixel(x, y)
                        val distance = maxOf(kotlin.math.abs(Color.red(pixel) - color.getInt(0)),
                            kotlin.math.abs(Color.green(pixel) - color.getInt(1)),
                            kotlin.math.abs(Color.blue(pixel) - color.getInt(2)))
                        if (distance <= 48) ink++
                        if (distance >= 96) background++
                    }
                    if (ink < pixels * .01 || background < pixels * .10) return false
                }
            }
            return true
        }
        try {
            isolateProvider()
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
            await { evaluate(homeInk) != "null" }
            val layout = evaluate(homeInk)
            check(layout != "null")
            committedDraw()
            check(PREFERENCES.all { (name, value) -> deck.getString(name, null) == value })
            val screenshot = uiAutomation.takeScreenshot() ?: error("Missing UI screenshot")
            try {
                val directory = targetContext.getExternalFilesDir(null) ?: error("Missing private evidence directory")
                File(directory, "signed-reception.png").outputStream().use {
                    check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                check(renderedHome() && shown() && nativeOff() && evaluate(homeInk) == layout)
                check(paintedText(screenshot, JSONObject(layout)))
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
