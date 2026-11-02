package com.vibe.notiflow.domain.action

import com.vibe.notiflow.data.local.SecureStore
import com.vibe.notiflow.domain.model.ActionResult
import com.vibe.notiflow.domain.model.NotificationEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

interface EventAction {
    val type: String
    suspend fun execute(event: NotificationEvent, config: JsonObject): ActionResult
}
class ActionRegistry(actions: List<EventAction>) {
    private val map = actions.associateBy { it.type }
    fun get(type: String) = map[type]
}
class WebhookPostAction(private val secureStore: SecureStore) : EventAction {
    override val type = "webhook.post"
    private val client = OkHttpClient()
    override suspend fun execute(event: NotificationEvent, config: JsonObject): ActionResult {
        return try {
            val url = config["url"]?.jsonPrimitive?.contentOrNull ?: return ActionResult(false, "웹훅 URL을 설정해 주세요")
            val method = config["method"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "POST"
            require(method in setOf("POST", "PUT", "PATCH")) { "POST, PUT 또는 PATCH를 선택해 주세요" }
            val template = config["payloadTemplate"]?.jsonPrimitive?.contentOrNull
            val body = WebhookPayload.build(template, event).toRequestBody("application/json".toMediaType())
            val request = Request.Builder().url(url).method(method, body)
            val headers = config["headers"]?.jsonObject.orEmpty()
            headers.forEach { (key, value) -> request.addHeader(key, value.jsonPrimitive.content) }
            val tokenRef = config["tokenRef"]?.jsonPrimitive?.contentOrNull
            val token = tokenRef?.let { secureStore.getSecret(it) }
            if (!token.isNullOrBlank() && headers.keys.none { it.equals("Authorization", ignoreCase = true) }) {
                request.addHeader("Authorization", "Bearer $token")
            }
            client.newCall(request.build()).execute().use {
                if (it.isSuccessful) ActionResult(true, "웹훅을 전송했어요 (HTTP ${it.code})")
                else ActionResult(false, "서버가 HTTP ${it.code}로 응답했어요", retryable = it.code >= 500 || it.code == 429)
            }
        } catch (error: IOException) {
            ActionResult(false, "네트워크 연결을 확인해 주세요", retryable = true)
        } catch (error: Exception) {
            ActionResult(false, "전송 설정을 확인해 주세요: ${error.message}", retryable = false)
        }
    }
}
