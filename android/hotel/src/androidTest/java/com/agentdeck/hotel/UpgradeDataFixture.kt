package com.agentdeck.hotel

import android.app.Instrumentation
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

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
        val case = args?.getString("case").takeIf { it == "seed" || it == "verify" } ?: "unknown"
        try {
            check(args?.getString("isolated") == "1")
            check(Build.HARDWARE in setOf("ranchu", "goldfish"))
            check(targetContext.packageName == TARGET && context.packageName == "$TARGET.test")
            val marker = args?.getString("marker") ?: error("Missing fixture marker")
            check(Regex("[0-9a-fA-F]{40}").matches(marker))
            val fixture = targetContext.getSharedPreferences("upgrade-data-fixture", Context.MODE_PRIVATE)
            val deck = targetContext.getSharedPreferences("deck", Context.MODE_PRIVATE)
            val file = File(targetContext.filesDir, "upgrade-data-fixture.bin")
            val alias = "manzanilla-upgrade-fixture-$marker"
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

            when (case) {
                "seed" -> {
                    check(!fixture.contains("marker") && !file.exists() && !store.containsAlias(alias))
                    // Refuse to overwrite existing application preferences, even on an emulator.
                    check(PREFERENCES.keys.none(deck::contains))
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
                }
                else -> error("Unknown fixture case")
            }
            result.putString("stream", "PASS case=$case preferences=true private_file=true encrypted_marker=true\n")
            finish(-1, result)
        } catch (_: Throwable) {
            // Never print values from device storage, credentials, or arbitrary exception text.
            result.putString("stream", "FAIL case=$case upgrade data fixture did not complete\n")
            finish(0, result)
        }
    }

    private companion object {
        const val TARGET = "dev.orange.hotel"
        val PREFERENCES = mapOf("reception_appearance" to "light",
            "reception_guest_language" to "fr", "reception_staff_language" to "de")
    }
}
