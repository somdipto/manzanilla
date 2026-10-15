package com.agentdeck.hotel

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/** Android-free persistence seam: tests use real files and AES-GCM, including failed writes. */
internal interface LegacyCredential {
    fun read(): String?
    fun remove()
}

internal interface CredentialCipher {
    fun encrypt(value: ByteArray): ByteArray
    fun decrypt(value: ByteArray): ByteArray
}

/** A replacement is published only after its encrypted file has been read and decrypted. */
internal class CredentialStorage(
    private val file: File,
    private val legacy: LegacyCredential,
    private val cipher: CredentialCipher
) {
    enum class Status { ABSENT, READY, ERROR }
    var status = Status.ABSENT
        private set
    var errorMessage: String? = null
        private set

    @Synchronized fun load(): String? = try {
        val value = if (file.exists()) {
            val decrypted = decrypt(file.readText(Charsets.UTF_8))
            // Retry removal if a prior successful migration could not remove its old entry.
            legacy.remove()
            decrypted
        } else {
            val previous = legacy.read()
            when {
                previous == null -> null
                previous.startsWith("ks:") -> decrypt(previous)
                previous.startsWith("pl:") -> {
                    val plaintext = decodeText(Base64.getDecoder().decode(previous.substring(3)))
                    require(plaintext.isNotBlank())
                    publish(plaintext)
                    legacy.remove()
                    plaintext
                }
                else -> throw IllegalStateException("Unsupported credential encoding")
            }
        }
        status = if (value == null) Status.ABSENT else Status.READY
        errorMessage = null
        value
    } catch (_: Exception) {
        fail("Secure credential storage is unavailable or the saved key cannot be read. Unlock the tablet and retry Staff setup. If this persists, replace the key from its original provider; the existing stored entry has not been discarded.")
        null
    }

    @Synchronized fun save(value: String): Boolean = try {
        val trimmed = value.trim()
        require(trimmed.isNotBlank())
        publish(trimmed)
        legacy.remove()
        status = Status.READY
        errorMessage = null
        true
    } catch (_: Exception) {
        fail("The key could not be securely saved. Unlock the tablet and retry. Keep the original provider key; do not clear the saved entry to resolve a storage error.")
        false
    }

    @Synchronized fun clear() {
        try {
            legacy.remove()
            if (file.exists() && !file.delete()) throw IllegalStateException("Credential removal failed")
            status = Status.ABSENT
            errorMessage = null
        } catch (_: Exception) {
            fail("The saved key could not be removed. Unlock the tablet and retry Staff setup.")
        }
    }

    private fun publish(value: String) {
        val encoded = "ks:" + Base64.getEncoder().encodeToString(cipher.encrypt(value.toByteArray(Charsets.UTF_8)))
        check(decrypt(encoded) == value)
        val directory = file.parentFile ?: throw IllegalStateException("Missing private directory")
        if (!directory.isDirectory && !directory.mkdirs()) throw IllegalStateException("Private storage unavailable")
        val candidate = File.createTempFile("credential-", ".pending", directory)
        try {
            FileOutputStream(candidate).use { stream ->
                stream.write(encoded.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            check(decrypt(candidate.readText(Charsets.UTF_8)) == value)
            // No non-atomic fallback: failed staging/read-back cannot replace a working entry.
            Files.move(candidate.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            candidate.delete()
        }
    }

    private fun decrypt(encoded: String): String {
        require(encoded.startsWith("ks:"))
        val value = decodeText(cipher.decrypt(Base64.getDecoder().decode(encoded.substring(3))))
        require(value.isNotBlank())
        return value
    }

    private fun decodeText(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun fail(message: String) {
        status = Status.ERROR
        errorMessage = message
    }
}
