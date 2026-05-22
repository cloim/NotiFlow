package com.vibe.notiflow.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureStore(context: Context) {
    private val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "notiflow_secure",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun putSecret(alias: String, secret: String) { prefs.edit().putString(alias, secret).apply() }
    fun getSecret(alias: String): String? = prefs.getString(alias, null)
    fun removeSecret(alias: String) { prefs.edit().remove(alias).apply() }
}
