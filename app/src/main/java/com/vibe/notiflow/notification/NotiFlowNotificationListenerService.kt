package com.vibe.notiflow.notification

import com.google.firebase.auth.FirebaseAuth
import android.app.Notification
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

        if (FirebaseAuth.getInstance().currentUser == null) return
        if (!SourceNotificationPolicy.shouldProcessForRules(
                sourcePackageName = sbn.packageName,
                ownPackageName = packageName,
                isOngoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0,
                isGroupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
            )
        ) {
            Log.d("NotiFlow", "Skipping source notification: pkg=${sbn.packageName}")
            return
        }

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

}
