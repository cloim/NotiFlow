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
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

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
    private val unknownPlaceholderRegex = Regex("\\{\\{[^}]+\\}\\}")

    override suspend fun execute(event: NotificationEvent, config: JsonObject): ActionResult {
        val url = config["url"]?.jsonPrimitive?.contentOrNull ?: return ActionResult(false, "webhook url missing")
        val method = config["method"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "POST"
        val tokenRef = config["tokenRef"]?.jsonPrimitive?.contentOrNull
        val token = tokenRef?.let { secureStore.getSecret(it) }
        val headers = config["headers"]?.jsonObject?.mapNotNull { (key, value) ->
            value.jsonPrimitive.contentOrNull?.let { key to it }
        }?.toMap().orEmpty()
        val template = config["payloadTemplate"]?.jsonPrimitive?.contentOrNull

        val body = runCatching { buildBody(template, event) }.getOrElse {
            return ActionResult(false, "webhook payload invalid: ${it.message}", retryable = false)
        }
        val requestBuilder = Request.Builder().url(url)
        when (method) {
            "POST" -> requestBuilder.post(body.toRequestBody("application/json".toMediaType()))
            "PUT" -> requestBuilder.put(body.toRequestBody("application/json".toMediaType()))
            "PATCH" -> requestBuilder.patch(body.toRequestBody("application/json".toMediaType()))
            else -> return ActionResult(false, "unsupported method: $method", retryable = false)
        }

        headers.forEach { (key, value) -> requestBuilder.addHeader(key, value) }
        if (!token.isNullOrBlank() && headers.keys.none { it.equals("Authorization", ignoreCase = true) }) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }
        val req = requestBuilder.build()

        return runCatching {
            client.newCall(req).execute().use {
                if (it.isSuccessful) ActionResult(true, "webhook ok")
                else ActionResult(false, "webhook failed: ${it.code}", retryable = it.code >= 500)
            }
        }.getOrElse { ActionResult(false, "webhook exception: ${it.message}", retryable = true) }
    }

    private fun buildBody(template: String?, event: NotificationEvent): String {
        val sourceTemplate = template?.takeIf { it.isNotBlank() } ?: return defaultBody(event)
        var rendered = sourceTemplate
        payloadVariables(event).forEach { (token, value) ->
            rendered = rendered.replace(token, value)
        }
        if (unknownPlaceholderRegex.containsMatchIn(rendered)) {
            error("unknown placeholder")
        }

        val jsonValue = JSONTokener(rendered).nextValue()
        return when (jsonValue) {
            is JSONObject -> jsonValue.toString()
            is JSONArray -> jsonValue.toString()
            else -> error("payload must be object or array")
        }
    }

    private fun payloadVariables(event: NotificationEvent): Map<String, String> = mapOf(
        "{{packageName}}" to JSONObject.quote(event.packageName),
        "{{title}}" to jsonStringOrNull(event.title),
        "{{text}}" to jsonStringOrNull(event.text),
        "{{postedAt}}" to event.postedAt.toString(),
        "{{extras}}" to JSONObject(event.extras).toString()
    )

    private fun jsonStringOrNull(value: String?): String = value?.let { JSONObject.quote(it) } ?: "null"

    private fun defaultBody(event: NotificationEvent): String = JSONObject().apply {
        put("packageName", event.packageName)
        put("title", event.title)
        put("text", event.text)
        put("postedAt", event.postedAt)
        put("extras", JSONObject(event.extras))
    }.toString()
}
