package com.gist.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Key-value store for sensitive secrets (API keys, OAuth tokens).
 * Uses Tink AEAD with a master key backed by Android Keystore (§8.1).
 * Falls back gracefully to in-memory/prefs encoding during unit tests when Keystore is unavailable.
 */
interface SecureStore {
    fun getSecret(key: String): String?
    fun putSecret(key: String, value: String?)
    fun clearAll()
}

@Singleton
class TinkSecureStore @Inject constructor(
    @ApplicationContext private val context: Context
) : SecureStore {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
    }

    private val fallbackMap = ConcurrentHashMap<String, String>()

    private val aead: Aead? by lazy {
        try {
            AeadConfig.register()
            val keysetHandle = AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, PREFS_FILE)
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
            keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "Android Keystore unavailable, using fallback storage", e)
            null
        }
    }

    override fun getSecret(key: String): String? {
        val cipher = aead
        if (cipher == null) {
            return fallbackMap[key] ?: prefs.getString("plain_$key", null)
        }
        val encoded = prefs.getString(key, null) ?: return null
        return try {
            val ciphertext = Base64.decode(encoded, Base64.NO_WRAP)
            val plaintext = cipher.decrypt(ciphertext, key.toByteArray(Charsets.UTF_8))
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt secret for $key", e)
            null
        }
    }

    override fun putSecret(key: String, value: String?) {
        if (value.isNullOrBlank()) {
            fallbackMap.remove(key)
            prefs.edit().remove(key).remove("plain_$key").apply()
            return
        }
        val cipher = aead
        if (cipher == null) {
            fallbackMap[key] = value
            prefs.edit().putString("plain_$key", value).apply()
            return
        }
        try {
            val ciphertext = cipher.encrypt(
                value.toByteArray(Charsets.UTF_8),
                key.toByteArray(Charsets.UTF_8)
            )
            val encoded = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            prefs.edit().putString(key, encoded).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt secret for $key", e)
        }
    }

    override fun clearAll() {
        fallbackMap.clear()
        prefs.edit().clear().apply()
    }

    companion object {
        private const val TAG = "TinkSecureStore"
        const val PREFS_FILE = "gist_secure_prefs"
        private const val KEYSET_NAME = "gist_master_keyset"
        private const val MASTER_KEY_URI = "android-keystore://gist_master_key"
    }
}
