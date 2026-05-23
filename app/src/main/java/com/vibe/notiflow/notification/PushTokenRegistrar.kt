package com.vibe.notiflow.notification

import android.content.Context
import com.cloimism.notiflow.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import java.security.MessageDigest
import kotlinx.coroutines.tasks.await

class PushTokenRegistrar(context: Context) {
    private val appContext = context.applicationContext
    private val auth = FirebaseAuth.getInstance()
    private val messaging = FirebaseMessaging.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    suspend fun registerCurrentToken(): Boolean {
        auth.currentUser ?: return false
        val token = messaging.token.await()
        return registerToken(token)
    }

    suspend fun registerToken(token: String): Boolean {
        val user = auth.currentUser ?: return false
        if (token.isBlank()) return false

        val uid = user.uid
        val userRef = firestore.collection(USERS_COLLECTION).document(uid)
        val now = FieldValue.serverTimestamp()

        userRef.set(
            mapOf(
                "uid" to uid,
                "email" to user.email,
                "displayName" to user.displayName,
                "photoUrl" to user.photoUrl?.toString(),
                "platform" to PLATFORM_ANDROID,
                "appPackage" to appContext.packageName,
                "appVersionName" to BuildConfig.VERSION_NAME,
                "appVersionCode" to BuildConfig.VERSION_CODE,
                "updatedAt" to now
            ),
            SetOptions.merge()
        ).await()

        userRef.collection(DEVICES_COLLECTION).document(tokenHash(token)).set(
            mapOf(
                "fcmToken" to token,
                "tokenHash" to tokenHash(token),
                "platform" to PLATFORM_ANDROID,
                "appPackage" to appContext.packageName,
                "appVersionName" to BuildConfig.VERSION_NAME,
                "appVersionCode" to BuildConfig.VERSION_CODE,
                "deviceSdk" to android.os.Build.VERSION.SDK_INT,
                "updatedAt" to now
            ),
            SetOptions.merge()
        ).await()

        return true
    }

    suspend fun unregisterCurrentToken(): Boolean {
        val user = auth.currentUser ?: return false
        val token = runCatching { messaging.token.await() }.getOrNull() ?: return false
        if (token.isBlank()) return false

        firestore.collection(USERS_COLLECTION)
            .document(user.uid)
            .collection(DEVICES_COLLECTION)
            .document(tokenHash(token))
            .delete()
            .await()

        return true
    }

    private fun tokenHash(token: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private companion object {
        private const val USERS_COLLECTION = "users"
        private const val DEVICES_COLLECTION = "devices"
        private const val PLATFORM_ANDROID = "android"
    }
}
