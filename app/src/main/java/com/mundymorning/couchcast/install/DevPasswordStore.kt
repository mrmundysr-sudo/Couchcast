package com.mundymorning.couchcast.install

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Optional "remember on this phone". The developer password is encrypted with an
 * Android Keystore AES-GCM key that never leaves the device. Plaintext is never written to disk.
 */
class DevPasswordStore(context: Context) {
    private val prefs = context.getSharedPreferences("couchcast_devpw", Context.MODE_PRIVATE)

    fun has(ip: String) = prefs.contains(ip)

    fun save(ip: String, password: String) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = cipher.iv + cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(ip, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun load(ip: String): String? {
        val raw = prefs.getString(ip, null) ?: return null
        return try {
            val blob = Base64.decode(raw, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_LEN))
            }
            String(cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN), Charsets.UTF_8)
        } catch (_: Exception) {
            clear(ip)
            null
        }
    }

    fun clear(ip: String) = prefs.edit().remove(ip).apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val ALIAS = "couchcast_dev_password"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
    }
}
