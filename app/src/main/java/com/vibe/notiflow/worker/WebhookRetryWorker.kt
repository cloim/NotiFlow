package com.vibe.notiflow.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.vibe.notiflow.di.ServiceLocator
import com.vibe.notiflow.domain.model.ActionSpec
import com.vibe.notiflow.domain.model.ExecutionLog
import com.vibe.notiflow.domain.model.NotificationEvent
import java.io.File
import kotlinx.serialization.json.Json
import org.json.JSONObject

class WebhookRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val name = inputData.getString("payloadFile") ?: return Result.failure()
        if (!Regex("[a-f0-9-]+\\.json").matches(name)) return Result.failure()
        val file = File(applicationContext.filesDir, "webhook-retries/$name")
        if (!file.exists()) return Result.failure()
        val payload = JSONObject(file.readText())
        val ruleId = inputData.getLong(KEY_RULE_ID, -1)
        val rule = ServiceLocator.ruleRepository.getRuleById(ruleId)
        if (rule?.enabled != true || FirebaseAuth.getInstance().currentUser?.uid != payload.getString("ownerUid")) {
            file.delete()
            return Result.success()
        }
        val action = Json.decodeFromString<ActionSpec>(payload.getString("action"))
        val event = Json.decodeFromString<NotificationEvent>(payload.getString("event"))
        val handler = ServiceLocator.ruleEngine.actions.get(action.type) ?: return Result.failure()
        val result = handler.execute(event, action.config)
        ServiceLocator.ruleRepository.insertLog(ExecutionLog(ruleId = ruleId, matched = true,
            result = if (result.success) "RETRY_SUCCESS" else "RETRY_FAILED", message = result.message,
            executedAt = System.currentTimeMillis(), eventPackage = event.packageName, eventTitle = event.title))
        if (!result.success && result.retryable && runAttemptCount < 3) return Result.retry()
        file.delete()
        return if (result.success) Result.success() else Result.failure()
    }
    companion object { const val KEY_RULE_ID = "rule_id" }
}
