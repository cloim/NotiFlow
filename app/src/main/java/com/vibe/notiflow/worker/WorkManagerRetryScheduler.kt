package com.vibe.notiflow.worker

import android.content.Context
import androidx.work.*
import com.vibe.notiflow.domain.model.ActionSpec
import com.vibe.notiflow.domain.model.NotificationEvent
import com.vibe.notiflow.domain.retry.RetryScheduler
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import com.google.firebase.auth.FirebaseAuth

class WorkManagerRetryScheduler(private val context: Context) : RetryScheduler {
    override fun scheduleWebhookRetry(ruleId: Long, action: ActionSpec, event: NotificationEvent) {
        val ownerUid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val directory = File(context.filesDir, "webhook-retries").apply { mkdirs() }
        val file = File(directory, "${UUID.randomUUID()}.json")
        file.writeText(JSONObject().put("ownerUid", ownerUid).put("action", Json.encodeToString(action))
            .put("event", Json.encodeToString(event)).toString())
        val request = OneTimeWorkRequestBuilder<WebhookRetryWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(WebhookRetryWorker.KEY_RULE_ID to ruleId, "payloadFile" to file.name))
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
