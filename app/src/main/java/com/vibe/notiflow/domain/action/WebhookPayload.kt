package com.vibe.notiflow.domain.action

import com.vibe.notiflow.domain.model.NotificationEvent
import com.vibe.notiflow.domain.model.SharedContent
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

object WebhookPayload {
    private val json = Json { encodeDefaults = true }
    fun build(template: String?, event: NotificationEvent): String {
        if (template.isNullOrBlank()) return json.encodeToString(event)
        val values = mapOf(
            "packageName" to JSONObject.quote(event.packageName),
            "title" to (event.title?.let(JSONObject::quote) ?: "null"),
            "text" to (event.text?.let(JSONObject::quote) ?: "null"),
            "postedAt" to event.postedAt.toString(),
            "extras" to JSONObject(event.extras).toString(),
            "source" to JSONObject.quote(event.source),
            "url" to (SharedContent.url(event)?.let(JSONObject::quote) ?: "null"),
            "mimeType" to (SharedContent.mimeType(event)?.let(JSONObject::quote) ?: "null"),
            "attachments" to Json.encodeToString(event.attachments)
        )
        val rendered = Regex("\\{\\{([^}]+)\\}\\}").replace(template) { match ->
            values[match.groupValues[1]] ?: error("지원하는 본문 변수를 선택해 주세요: ${match.value}")
        }
        val tokener = JSONTokener(rendered)
        val value = tokener.nextValue()
        require(value is JSONObject || value is JSONArray) { "JSON 객체 또는 배열을 입력해 주세요" }
        require(tokener.nextClean().code == 0) { "JSON 뒤의 내용을 확인해 주세요" }
        return value.toString()
    }
}
