package com.vibe.notiflow.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cloimism.notiflow.R
import com.vibe.notiflow.MainActivity

class NotiFlowPushNotifier(private val context: Context) {
    fun show(title: String?, body: String?, data: Map<String, String>) {
        ensureChannel()
        if (!canPostNotifications()) {
            Log.w("NotiFlow", "FCM notification skipped because POST_NOTIFICATIONS is not granted")
            return
        }

        val resolvedTitle = title.nonBlank()
            ?: data["title"].nonBlank()
            ?: "NotiFlow"
        val resolvedBody = body.nonBlank()
            ?: data["body"].nonBlank()
            ?: data["message"].nonBlank()
            ?: "새 알림을 수신했습니다."

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(resolvedTitle)
            .setContentText(resolvedBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(resolvedBody))
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("NotiFlow", "FCM notification skipped because POST_NOTIFICATIONS was revoked")
            return
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(data), notification)
        }.onFailure { error ->
            if (error is SecurityException) {
                Log.w("NotiFlow", "FCM notification skipped because POST_NOTIFICATIONS was rejected", error)
            } else {
                throw error
            }
        }
    }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "NotiFlow push",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "NotiFlow가 직접 수신한 푸시 알림"
        }
        manager.createNotificationChannel(channel)
    }

    private fun canPostNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun notificationId(data: Map<String, String>): Int =
        data["notificationId"]?.toIntOrNull()
            ?: data["id"]?.hashCode()
            ?: System.currentTimeMillis().toInt()

    private fun String?.nonBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val CHANNEL_ID = "notiflow_push"
    }
}
