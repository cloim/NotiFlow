package com.vibe.notiflow.notification

import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.vibe.notiflow.di.ServiceLocator
import com.vibe.notiflow.domain.model.NotificationEvent
import com.vibe.notiflow.domain.model.ReceivedNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NotiFlowNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d("NotiFlow", "NLS onListenerConnected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d("NotiFlow", "NLS onListenerDisconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        Log.d("NotiFlow", "onNotificationPosted: pkg=${sbn.packageName}")
        val n = sbn.notification ?: return
        val extras = n.extras
        val title = extras?.getCharSequence("android.title")?.toString()
        val text = extras?.getCharSequence("android.text")?.toString()

        if (saveOwnFcmNotificationIfNeeded(sbn, title, text)) return

        val event = NotificationEvent(
            packageName = sbn.packageName,
            title = title,
            text = text,
            extras = mapOf("key" to sbn.key, "category" to (n.category ?: "")),
            postedAt = sbn.postTime
        )

        Log.d("NotiFlow", "Processing event: pkg=${event.packageName} title=${event.title} text=${event.text}")
        scope.launch { ServiceLocator.ruleEngine.process(event) }
    }

    private fun saveOwnFcmNotificationIfNeeded(
        sbn: StatusBarNotification,
        title: String?,
        text: String?
    ): Boolean {
        if (sbn.packageName != packageName) return false
        if (sbn.tag?.startsWith("FCM-Notification:") != true) return false

        val data = buildMap {
            put("source", "fcm-auto-display")
            put("notificationKey", sbn.key)
            sbn.tag?.let { put("fcmTag", it) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                sbn.notification.channelId?.let { put("channelId", it) }
            }
        }

        Log.d("NotiFlow", "Persisting own Firebase auto-displayed notification: tag=${sbn.tag}")
        scope.launch {
            runCatching {
                ServiceLocator.ruleRepository.saveReceivedNotification(
                    ReceivedNotification(
                        title = title,
                        body = text,
                        sender = sbn.packageName,
                        data = data,
                        receivedAt = sbn.postTime.takeIf { it > 0 } ?: System.currentTimeMillis()
                    )
                )
            }.onFailure { error ->
                Log.w("NotiFlow", "FCM auto-displayed notification save failed", error)
            }
        }
        return true
    }
}
