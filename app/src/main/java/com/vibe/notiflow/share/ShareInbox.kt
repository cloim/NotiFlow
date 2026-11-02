package com.vibe.notiflow.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.vibe.notiflow.domain.model.NotificationEvent
import com.vibe.notiflow.domain.model.SharedAttachment
import java.io.File
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Copy granted content while the receiving activity still owns the URI permission. */
class ShareInbox(private val context: Context) {
    private val directory get() = File(context.filesDir, "share-inbox").apply { mkdirs() }

    @Suppress("DEPRECATION")
    fun receive(intent: Intent): NotificationEvent {
        require(intent.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE))
        val uris = linkedSetOf<Uri>()
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        } else {
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
        }
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
        }
        require(uris.size <= 20) { "한 번에 파일을 20개까지 공유할 수 있어요" }
        var remaining = MAX_BYTES
        val attachments = uris.map { uri ->
            require(uri.scheme == "content") { "공유 파일은 콘텐츠 제공자를 통해 전달해 주세요" }
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "shared-file"
            val bytes = resolver.openInputStream(uri)?.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    remaining -= count
                    require(remaining >= 0) { "공유 파일의 합계 크기는 10MB까지 지원해요" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("공유 파일을 읽을 수 없어요. 원본 앱에서 다시 공유해 주세요")
            SharedAttachment(name, resolver.getType(uri) ?: intent.type ?: "application/octet-stream",
                bytes.size.toLong(), Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
            ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        require(attachments.isNotEmpty() || !text.isNullOrBlank()) { "공유할 내용을 찾지 못했어요" }
        require((text?.length ?: 0) <= 200_000) { "공유 텍스트는 20만 자까지 지원해요" }
        val event = NotificationEvent(
            packageName = "share",
            title = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: attachments.firstOrNull()?.name ?: "공유 콘텐츠",
            text = text,
            postedAt = System.currentTimeMillis(),
            source = "share",
            mimeType = intent.type ?: attachments.firstOrNull()?.mimeType ?: "text/plain",
            attachments = attachments,
            eventId = UUID.randomUUID().toString()
        )
        val temporary = File(directory, "${event.eventId}.tmp")
        temporary.writeText(Json.encodeToString(event))
        check(temporary.renameTo(File(directory, "${event.eventId}.json"))) { "공유 콘텐츠를 다시 보내 주세요" }
        return event
    }

    @Synchronized
    fun enqueuePending(ownerUid: String) {
        directory.listFiles()?.filter { it.extension == "json" }?.forEach { file ->
            val owner = File(directory, "${file.nameWithoutExtension}.owner")
            if (owner.exists() && owner.readText() != ownerUid) return@forEach
            if (!owner.exists()) owner.writeText(ownerUid)
            val work = OneTimeWorkRequestBuilder<ShareWorker>()
                .setInputData(workDataOf("eventFile" to file.name, "ownerUid" to ownerUid))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("share-${file.name}", ExistingWorkPolicy.KEEP, work)
        }
    }

    companion object { const val MAX_BYTES = 10 * 1024 * 1024 }
}
