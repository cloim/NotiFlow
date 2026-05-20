package com.vibe.notiflow.worker

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vibe.notiflow.domain.model.ActionSpec
import com.vibe.notiflow.domain.model.NotificationEvent
import com.vibe.notiflow.domain.retry.RetryScheduler
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

class WorkManagerRetryScheduler(context: Context) : RetryScheduler {
    private val wm = WorkManager.getInstance(context)

    override fun scheduleWebhookRetry(ruleId: Long, action: ActionSpec, event: NotificationEvent) {
        val req = OneTimeWorkRequestBuilder<WebhookRetryWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(
                workDataOf(
                    WebhookRetryWorker.KEY_RULE_ID to ruleId,
                    WebhookRetryWorker.KEY_ACTION_JSON to Json.encodeToString(action),
                    WebhookRetryWorker.KEY_EVENT_JSON to Json.encodeToString(event)
                )
            )
            .build()

        wm.enqueueUniqueWork("retry-${ruleId}-${event.postedAt}", ExistingWorkPolicy.REPLACE, req)
    }
}
