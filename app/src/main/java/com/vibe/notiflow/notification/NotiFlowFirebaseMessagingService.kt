package com.vibe.notiflow.notification

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.vibe.notiflow.di.ServiceLocator
import com.vibe.notiflow.domain.model.ReceivedNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NotiFlowFirebaseMessagingService : FirebaseMessagingService() {
    private val notifier by lazy { NotiFlowPushNotifier(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title ?: message.data["title"]
        val body = message.notification?.body ?: message.data["body"] ?: message.data["message"]

        Log.d("NotiFlow", "FCM message received: from=${message.from}")
        scope.launch {
            runCatching {
                ServiceLocator.ruleRepository.saveReceivedNotification(
                    ReceivedNotification(
                        title = title,
                        body = body,
                        sender = message.from,
                        data = message.data,
                        receivedAt = System.currentTimeMillis()
                    )
                )
            }.onFailure { error ->
                Log.w("NotiFlow", "FCM message save failed", error)
            }
        }
        notifier.show(title, body, message.data)
    }

    override fun onNewToken(token: String) {
        Log.d("NotiFlow", "FCM registration token refreshed")
        scope.launch {
            runCatching {
                PushTokenRegistrar(applicationContext).registerToken(token)
            }.onFailure { error ->
                Log.w("NotiFlow", "FCM registration token update failed", error)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
