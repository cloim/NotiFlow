package com.vibe.notiflow.share

import android.content.Context
import android.widget.Toast
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.vibe.notiflow.di.ServiceLocator
import com.vibe.notiflow.domain.model.NotificationEvent
import com.vibe.notiflow.domain.model.ExecutionLog
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

class ShareWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val name = inputData.getString("eventFile") ?: return Result.failure()
        if (!Regex("[a-f0-9-]+\\.json").matches(name)) return Result.failure()
        val file = File(applicationContext.filesDir, "share-inbox/$name")
        val owner = File(applicationContext.filesDir, "share-inbox/${name.removeSuffix(".json")}.owner")
        if (!file.exists()) return Result.success()
        if (FirebaseAuth.getInstance().currentUser?.uid != inputData.getString("ownerUid") ||
            !owner.exists() || owner.readText() != inputData.getString("ownerUid")) {
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, "공유 실패: NotiFlow 로그인 상태를 확인해 주세요", Toast.LENGTH_LONG).show()
            }
            return Result.failure() // Retain the inbox file until the next authenticated launch.
        }
        return try {
            val event = Json.decodeFromString<NotificationEvent>(file.readText())
            val summary = ServiceLocator.ruleEngine.process(event)
            file.delete()
            owner.delete()
            if (!summary.duplicate) withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, summary.shareMessage(), Toast.LENGTH_LONG).show()
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            runCatching {
                ServiceLocator.ruleRepository.insertLog(ExecutionLog(ruleId = 0, matched = false,
                    result = "FAILED", message = "공유 처리 중 오류가 발생했어요. 원본 앱에서 다시 공유해 주세요",
                    executedAt = System.currentTimeMillis(), eventPackage = "share", eventTitle = "공유 처리 실패"))
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, "공유 처리 실패: 실행 기록을 확인해 주세요", Toast.LENGTH_LONG).show()
            }
            Result.failure()
        }
    }
}
