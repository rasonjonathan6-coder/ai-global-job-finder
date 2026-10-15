package com.aiglobal.jobfinder.data.prefs

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Symmetric encryption for the credentials the user saves (Adzuna, Jooble, NVIDIA).
 *
 * The app already keeps its settings in DataStore, which is private to the app but stored
 * as plain bytes on disk. Wrapping just the secret values with a key that lives in the
 * Android Keystore means a device backup, a `adb backup` dump or a root-level file read
 * yields ciphertext rather than a live API key, while every other preference stays readable.
 */
interface SecretCipher {

    /** Encrypts [plaintext], returning a self-describing, storable string. */
    fun encrypt(plaintext: String): String

    /**
     * Reverses [encrypt]. A value that was never encrypted (an older install's plaintext
     * credential) is returned unchanged, so upgrading the app never loses a saved key.
     */
    fun decrypt(ciphertext: String): String

    /** False only for the test double; the production cipher is always true. */
    val isSecure: Boolean
}

/**
 * Keystore-backed AES-256-GCM. The key is generated once and never leaves the Keystore;
 * only the ciphertext and its IV are written to DataStore.
 */
class AndroidKeystoreSecretCipher(
    private val alias: String = DEFAULT_ALIAS,
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) },
) : SecretCipher {

    override val isSecure: Boolean = true

    override fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(iv, Base64.NO_WRAP) + SEPARATOR +
            Base64.encodeToString(body, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String): String {
        if (ciphertext.isEmpty()) return ""
        // A value with no prefix predates encryption; hand it back so the user keeps the
        // credential they already saved and it is re-encrypted on the next write.
        if (!ciphertext.startsWith(PREFIX)) return ciphertext
        val payload = ciphertext.removePrefix(PREFIX)
        val split = payload.indexOf(SEPARATOR)
        if (split <= 0) return ""
        return runCatching {
            val iv = Base64.decode(payload.substring(0, split), Base64.NO_WRAP)
            val body = Base64.decode(payload.substring(split + 1), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        }.getOrDefault("")
    }

    private fun secretKey(): SecretKey {
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val PREFIX = "enc1:"
        private const val SEPARATOR = ":"
        const val DEFAULT_ALIAS = "aigjf_credentials_v1"
    }
}

/**
 * Identity cipher used only by tests, so the credential round-trip can be exercised on the
 * JVM where the Android Keystore is not available. It is never installed in production.
 */
object PassthroughSecretCipher : SecretCipher {
    override val isSecure: Boolean = false
    override fun encrypt(plaintext: String): String = plaintext
    override fun decrypt(ciphertext: String): String = ciphertext
}
