package com.vibe.notiflow.share

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.vibe.notiflow.di.ServiceLocator
import com.vibe.notiflow.domain.model.NotificationEvent
import java.io.File
import kotlinx.serialization.json.Json

class ShareWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val name = inputData.getString("eventFile") ?: return Result.failure()
        if (!Regex("[a-f0-9-]+\\.json").matches(name)) return Result.failure()
        val file = File(applicationContext.filesDir, "share-inbox/$name")
        val owner = File(applicationContext.filesDir, "share-inbox/${name.removeSuffix(".json")}.owner")
        if (!file.exists()) return Result.success()
        if (FirebaseAuth.getInstance().currentUser?.uid != inputData.getString("ownerUid") ||
            !owner.exists() || owner.readText() != inputData.getString("ownerUid")) {
            return Result.failure() // Retain the inbox file until the next authenticated launch.
        }
        val event = Json.decodeFromString<NotificationEvent>(file.readText())
        ServiceLocator.ruleEngine.process(event)
        file.delete()
        owner.delete()
        return Result.success()
    }
}
