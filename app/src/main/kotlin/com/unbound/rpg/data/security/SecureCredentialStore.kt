package com.unbound.rpg.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the player's OpenAI key lives, and everywhere it does not (§4).
 *
 * The raw key is encrypted with an AES-256-GCM key that is generated inside the Android Keystore
 * and is **not extractable** — the app can ask the Keystore to decrypt, but can never read the
 * encryption key itself, so the ciphertext on disk is useless to anything that copies the file off
 * the device.
 *
 * The credential never touches Room, DataStore, SharedPreferences, a save export, a log line, or
 * `BuildConfig`. There is exactly one path in this codebase that returns the plaintext
 * ([readKey]), and exactly one caller of it: the code that builds the Authorization header.
 *
 * The ciphertext file is excluded from cloud backup and device transfer by
 * `res/xml/data_extraction_rules.xml`, so the key does not silently travel to a new device.
 */
class SecureCredentialStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)

    fun hasKey(): Boolean = file.exists() && file.length() > IV_LENGTH

    /**
     * The only accessor that returns plaintext. Callers must use it and discard it — never store
     * the result in a field, a log, or any object that could be serialized.
     */
    fun readKey(): String? {
        if (!hasKey()) return null
        return try {
            val blob = file.readBytes()
            val iv = blob.copyOfRange(0, IV_LENGTH)
            val cipherText = blob.copyOfRange(IV_LENGTH, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Exception) {
            // A failure here means the Keystore entry is gone (app data cleared, device restored,
            // biometrics reset). The credential is unrecoverable; clear it so the UI prompts
            // cleanly for a new one rather than failing every request with a decryption error.
            clear()
            null
        }
    }

    fun storeKey(rawKey: String) {
        require(rawKey.isNotBlank()) { "The key cannot be empty." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val cipherText = cipher.doFinal(rawKey.trim().toByteArray(Charsets.UTF_8))
        // Written atomically so an interrupted write cannot leave a half-file that decrypts to
        // garbage and gets silently cleared on next launch.
        val tmp = File(appContext.filesDir, "$FILE_NAME.tmp")
        tmp.writeBytes(cipher.iv + cipherText)
        if (!tmp.renameTo(file)) {
            file.writeBytes(tmp.readBytes())
            tmp.delete()
        }
        file.setReadable(false, false)
        file.setReadable(true, true)
    }

    fun clear() {
        if (file.exists()) {
            // Overwrite before unlinking so the ciphertext is not left recoverable in free blocks.
            runCatching { file.writeBytes(ByteArray(file.length().toInt())) }
            file.delete()
        }
    }

    /** What the settings screen shows. Never the whole key (§82). */
    fun maskedKey(): String? {
        val key = readKey() ?: return null
        return mask(key)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // Deliberately not requiring user authentication: the game must be able to take a
                // turn without a biometric prompt every time. The threat model here is a copied
                // file or a shared backup, not an unlocked device in the owner's hands.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "unbound.credential.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val FILE_NAME = "credential.bin"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128

        /** Shows enough to recognise which key is stored, and not enough to use it. */
        fun mask(key: String): String {
            val trimmed = key.trim()
            if (trimmed.length <= 10) return "•".repeat(trimmed.length.coerceAtLeast(8))
            val head = trimmed.take(if (trimmed.startsWith("sk-")) 6 else 3)
            return head + "•".repeat(16) + trimmed.takeLast(4)
        }
    }
}
