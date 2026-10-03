package app.zcomic.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Credentials(context: Context) {
    private val prefs = context.getSharedPreferences("account", Context.MODE_PRIVATE)
    private val alias = "zcomic-account"
    private val lock = Any()
    private var generation = 0L

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    fun save(email: String, password: String) {
        val version = synchronized(lock) { generation }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal("$email\n$password".toByteArray(Charsets.UTF_8))
        synchronized(lock) {
            if (version != generation) return
            prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("data", Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
        }
    }

    fun read(): Pair<String, String>? = try {
        val iv = Base64.decode(prefs.getString("iv", null) ?: return null, Base64.NO_WRAP)
        val data = Base64.decode(prefs.getString("data", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        }
        val plain = cipher.doFinal(data).toString(Charsets.UTF_8).split('\n', limit = 2)
        plain[0] to plain[1]
    } catch (_: Exception) { null }

    fun clear() = synchronized(lock) {
        generation++
        prefs.edit().clear().apply()
    }
}
