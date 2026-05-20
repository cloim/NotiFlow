package com.vibe.notiflow.notification

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.vibe.notiflow.di.ServiceLocator
import com.vibe.notiflow.domain.model.NotificationEvent
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

        val event = NotificationEvent(
            packageName = sbn.packageName,
            title = extras?.getCharSequence("android.title")?.toString(),
            text = extras?.getCharSequence("android.text")?.toString(),
            extras = mapOf("key" to sbn.key, "category" to (n.category ?: "")),
            postedAt = sbn.postTime
        )

        Log.d("NotiFlow", "Processing event: pkg=${event.packageName} title=${event.title} text=${event.text}")
        scope.launch { ServiceLocator.ruleEngine.process(event) }
    }
}
