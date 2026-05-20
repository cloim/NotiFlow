package com.vibe.notiflow.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vibe.notiflow.di.ServiceLocator
import com.vibe.notiflow.domain.model.ActionSpec
import com.vibe.notiflow.domain.model.ExecutionLog
import com.vibe.notiflow.domain.model.NotificationEvent
import kotlinx.serialization.json.Json

class WebhookRetryWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val ruleId = inputData.getLong(KEY_RULE_ID, -1)
        val action = Json.decodeFromString<ActionSpec>(inputData.getString(KEY_ACTION_JSON) ?: return Result.failure())
        val event = Json.decodeFromString<NotificationEvent>(inputData.getString(KEY_EVENT_JSON) ?: return Result.failure())

        val handler = ServiceLocator.ruleEngine.actions.get(action.type) ?: return Result.failure()
        val result = handler.execute(event, action.config)

        ServiceLocator.ruleRepository.insertLog(
            ExecutionLog(
                ruleId = ruleId,
                matched = true,
                result = if (result.success) "RETRY_SUCCESS" else "RETRY_FAILED",
                message = result.message,
                executedAt = System.currentTimeMillis(),
                eventPackage = event.packageName,
                eventTitle = event.title
            )
        )

        if (result.success) return Result.success()
        return if (result.retryable && runAttemptCount < 3) Result.retry() else Result.failure()
    }

    companion object {
        const val KEY_RULE_ID = "rule_id"
        const val KEY_ACTION_JSON = "action_json"
        const val KEY_EVENT_JSON = "event_json"
    }
}
