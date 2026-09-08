package dev.nicotv.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** No plaintext PSK enters shared settings, saved UI state, logs or backups. */
class EncryptedPskStore(context: Context, private val keyProvider: () -> SecretKey = { androidKey() }) {
    private val prefs = context.getSharedPreferences("nicotv_credentials", Context.MODE_PRIVATE)
    fun contains(): Boolean = prefs.contains("psk_v1")
    fun save(secret: String?) {
        if (secret == null) { check(prefs.edit().remove("psk_v1").commit()); return }
        require(secret.isNotEmpty() && secret.length <= 128 && !secret.any { it.isISOControl() }) { "PSKは1〜128文字で入力してください" }
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keyProvider())
            val data = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
            check(prefs.edit().putString("psk_v1", encode(cipher.iv) + ":" + encode(data)).commit())
        } catch (_: Exception) { throw SecretStorageException() }
    }
    fun read(): String? {
        val encoded = prefs.getString("psk_v1", null) ?: return null
        try {
            val parts = encoded.split(':')
            check(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            check(iv.size == 12)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(128, iv))
            return cipher.doFinal(data).toString(Charsets.UTF_8)
        } catch (_: Exception) { throw SecretStorageException() }
    }
    private fun encode(data: ByteArray) = Base64.encodeToString(data, Base64.NO_WRAP)
    companion object {
        private const val ALIAS = "nicotv.bravia.psk.v1"
        private fun androidKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        }
    }
}
class SecretStorageException : IllegalStateException("PSKを読み書きできません。詳細設定で再登録してください")
