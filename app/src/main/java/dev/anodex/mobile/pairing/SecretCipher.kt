package dev.anodex.mobile.pairing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts the paired secret at rest, under a key the app can use but never read.
 *
 * The key is generated inside the Android Keystore and marked non-exportable, so it lives in the
 * TEE or StrongBox where the hardware supports it. Even with the app's data directory in hand —
 * a rooted phone, an ADB backup, a stolen device image — the ciphertext is not decryptable off the
 * device it was written on.
 *
 * This deliberately does not use `androidx.security:security-crypto`. That library wraps the same
 * primitives, has been effectively unmaintained since Jetpack Security stalled, and would add a
 * dependency to save about thirty lines of platform API.
 *
 * The paired secret is the only thing that goes through here. It authenticates this phone to a
 * desktop that will run commands and write files on the user's behalf, which makes it the most
 * sensitive value the app will ever hold.
 */
internal class SecretCipher(private val keyAlias: String = DEFAULT_ALIAS) {

    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())

        // GCM's IV must never repeat under the same key. Letting the provider generate it is the
        // supported way to guarantee that; supplying our own is the classic way to get it wrong.
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        return encode(iv) + SEPARATOR + encode(ciphertext)
    }

    /**
     * @return the plaintext, or null if it cannot be recovered.
     *
     * Null is a real outcome rather than an error to swallow: the Keystore key is dropped when the
     * user adds or removes a screen lock, and the app is then holding a secret it can never read
     * again. The caller's job is to treat that as "no longer paired" and send the user back through
     * pairing — which is honest, recoverable, and takes a few seconds.
     */
    fun decrypt(encoded: String): String? = try {
        val parts = encoded.split(SEPARATOR)
        if (parts.size != 2) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                loadOrCreateKey(),
                GCMParameterSpec(TAG_LENGTH_BITS, decode(parts[0])),
            )
            String(cipher.doFinal(decode(parts[1])), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        // Includes AEADBadTagException (tampering, or a rotated key) and KeyPermanentlyInvalidated.
        null
    }

    /** Drops the key, making every existing ciphertext permanently unreadable. Used on unpair. */
    fun forget() {
        runCatching { keyStore().deleteEntry(keyAlias) }
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun loadOrCreateKey(): SecretKey {
        val existing = keyStore().getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // Deliberately NOT setUserAuthenticationRequired(true). The app must be able to
                // reconnect the moment it is opened, including from a notification tap, and a
                // biometric prompt in front of every reconnect would make the phone useless for
                // the job it exists to do. The secret is confined to hardware either way.
                .build(),
        )
        return generator.generateKey()
    }

    private fun encode(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String) = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "anodex.paired-secret.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SIZE_BITS = 256
        const val TAG_LENGTH_BITS = 128
        const val SEPARATOR = ":"
    }
}
