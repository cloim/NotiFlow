package com.vibe.notiflow

import com.vibe.notiflow.di.ServiceLocator
import com.vibe.notiflow.domain.engine.ConditionExpressionEvaluator
import com.vibe.notiflow.domain.model.*
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.*
import org.json.JSONArray
import org.json.JSONObject

class RuleInputFactory {
    private fun isValidWebhookUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
    }

    private fun autoRuleName(packageName: String, conditions: List<Pair<String, String>>): String {
        val packageShortName = packageName.substringAfterLast('.')
        val conditionSummary = conditions.joinToString(" + ") { "${it.first}:${it.second}" }
        val summary = if (conditionSummary.length > 42) "${conditionSummary.take(42)}..." else conditionSummary
        return "$packageShortName - $summary"
    }

    private fun jsonValue(value: String): JsonObject = buildJsonObject { put("value", value) }

    private fun normalizeFilterOperator(raw: String): FilterOperator {
        return when (raw.trim().uppercase(Locale.US)) {
            "AND" -> FilterOperator.AND
            "OR" -> FilterOperator.OR
            else -> throw IllegalArgumentException("conditionOperator must be AND/OR")
        }
    }

    private fun validateCondition(type: String, value: String) {
        if (type !in setOf("title.contains", "text.contains", "text.regex", "mime.matches")) {
            throw IllegalArgumentException("unsupported condition type: $type")
        }
        if (value.isBlank()) throw IllegalArgumentException("조건 값을 입력해 주세요")
        if (type == "mime.matches" && !Regex("[a-zA-Z0-9!#$&^_.+*-]+/[a-zA-Z0-9!#$&^_.+*-]+").matches(value)) {
            throw IllegalArgumentException("MIME 타입을 입력해 주세요. 예: image/*")
        }
        if (type == "text.regex" && runCatching { Regex(value) }.isFailure) {
            throw IllegalArgumentException("invalid regex pattern")
        }
    }

    private fun parseNonNegativeInt(
        obj: JSONObject,
        key: String,
        context: String
    ): Int {
        if (!obj.has(key)) return 0
        val raw = obj.opt(key)
        val value = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        } ?: throw IllegalArgumentException("$context.$key must be a non-negative integer")
        if (value < 0) throw IllegalArgumentException("$context.$key must be >= 0")
        return value
    }

    private fun normalizeConditions(input: JSONArray?): List<Pair<String, String>> {
        if (input == null) return emptyList()
        val conditions = mutableListOf<Pair<String, String>>()
        for (index in 0 until input.length()) {
            val condition = input.optJSONObject(index) ?: continue
            val type = condition.optString("type").trim()
            val value = condition.optString("value").trim()
            if (type.isBlank() || value.isBlank()) continue

            validateCondition(type, value)
            conditions += type to value
        }
        return conditions
    }

    private fun normalizeConditionExpression(input: JSONObject?): ConditionExpression? {
        if (input == null) return null
        val rowsArray = input.optJSONArray("rows")
            ?: throw IllegalArgumentException("conditionExpression.rows is required")
        val rows = mutableListOf<ConditionExpressionRow>()
        for (index in 0 until rowsArray.length()) {
            val row = rowsArray.optJSONObject(index)
                ?: throw IllegalArgumentException("conditionExpression.rows[$index] must be an object")
            val type = row.optString("type").trim()
            val value = row.optString("value").trim()
            if (type.isBlank() || value.isBlank()) {
                throw IllegalArgumentException("conditionExpression.rows[$index] requires type/value")
            }
            validateCondition(type, value)

            val operator = if (index < rowsArray.length() - 1) {
                if (!row.has("operator")) {
                    throw IllegalArgumentException("conditionExpression.rows[$index].operator is required")
                }
                normalizeFilterOperator(row.optString("operator"))
            } else if (row.has("operator") && !row.isNull("operator")) {
                normalizeFilterOperator(row.optString("operator"))
            } else {
                null
            }

            rows += ConditionExpressionRow(
                type = type,
                value = value,
                operator = operator,
                openParen = parseNonNegativeInt(row, "openParen", "conditionExpression.rows[$index]"),
                closeParen = parseNonNegativeInt(row, "closeParen", "conditionExpression.rows[$index]")
            )
        }
        if (rows.isEmpty()) return null
        val expression = ConditionExpression(rows = rows)
        ConditionExpressionEvaluator.validationError(expression)?.let { reason ->
            throw IllegalArgumentException("invalid conditionExpression: $reason")
        }
        return expression
    }

    private fun legacyExpression(
        conditions: List<Pair<String, String>>,
        operator: FilterOperator
    ): ConditionExpression {
        val rows = conditions.mapIndexed { index, (type, value) ->
            ConditionExpressionRow(
                type = type,
                value = value,
                operator = if (index < conditions.lastIndex) operator else null
            )
        }
        return ConditionExpression(rows)
    }

    private fun existingConditions(rule: Rule?): List<Pair<String, String>> {
        if (rule == null) return emptyList()
        return rule.filters
            .filter { it.type !in setOf("package.equals", "source.equals") }
            .mapNotNull { filter ->
                filter.config["value"]?.jsonPrimitive?.contentOrNull?.let { value ->
                    filter.type to value
                }
            }
    }

    private fun conditionsFromExpression(expression: ConditionExpression): List<Pair<String, String>> {
        return expression.rows.map { row -> row.type to row.value }
    }

    private fun existingWebhookConfig(rule: Rule?): JsonObject? {
        return rule?.actions?.firstOrNull { it.type == "webhook.post" }?.config
    }

    private fun existingHeaders(config: JsonObject?): Map<String, String> {
        if (config == null) return emptyMap()
        return config["headers"]?.jsonObject?.mapNotNull { (key, value) ->
            value.jsonPrimitive.contentOrNull?.let { key to it }
        }?.toMap().orEmpty()
    }

    fun build(input: JSONObject, existing: Rule?): Rule {
        val existingConfig = existingWebhookConfig(existing)
        val webhook = input.optJSONObject("webhook")

        val source = input.optString("source").ifBlank {
            existing?.filters?.firstOrNull { it.type == "source.equals" }?.config?.get("value")?.jsonPrimitive?.contentOrNull ?: "notification"
        }
        require(source in setOf("notification", "share")) { "입력 유형을 선택해 주세요" }
        val packageName = if (source == "share") "" else input.optString("packageName").trim()

        val webhookUrl = (webhook?.optString("url") ?: input.optString("webhookUrl")).trim()
            .ifBlank { existingConfig?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty() }
        if (webhookUrl.isBlank()) throw IllegalArgumentException("webhook.url is required")
        if (!isValidWebhookUrl(webhookUrl)) throw IllegalArgumentException("webhook.url must be http/https")

        val method = (webhook?.optString("method") ?: input.optString("webhookMethod")).trim()
            .ifBlank { existingConfig?.get("method")?.jsonPrimitive?.contentOrNull.orEmpty() }
            .ifBlank { "POST" }
            .uppercase(Locale.US)
        if (method !in setOf("POST", "PUT", "PATCH")) {
            throw IllegalArgumentException("webhook.method must be POST/PUT/PATCH")
        }

        val headers = if (webhook?.has("headers") == true) {
            val headersObj = webhook.optJSONObject("headers")
            val parsed = linkedMapOf<String, String>()
            headersObj?.keys()?.forEach { key ->
                val value = headersObj.optString(key).trim()
                if (key.trim().isNotBlank() && value.isNotBlank()) {
                    parsed[key.trim()] = value
                }
            }
            parsed
        } else {
            existingHeaders(existingConfig)
        }

        val payloadTemplate = if (webhook?.has("payloadTemplate") == true) {
            webhook.optString("payloadTemplate").trim()
        } else {
            existingConfig?.get("payloadTemplate")?.jsonPrimitive?.contentOrNull.orEmpty()
        }

        val filterOperator = when {
            input.has("conditionOperator") -> normalizeFilterOperator(input.optString("conditionOperator"))
            existing != null -> existing.filterOperator
            else -> FilterOperator.AND
        }

        val inputExpression = if (input.has("conditionExpression")) {
            normalizeConditionExpression(input.optJSONObject("conditionExpression"))
        } else {
            null
        }
        val inputConditions = normalizeConditions(input.optJSONArray("conditions"))
        val conditionExpression = when {
            input.has("conditionExpression") -> inputExpression
            input.has("conditions") -> if (inputConditions.isEmpty()) null else legacyExpression(inputConditions, filterOperator)
            else -> existing?.conditionExpression
        }
        val conditions = conditionExpression?.let(::conditionsFromExpression).orEmpty()

        require(source == "share" || conditions.none { it.first == "mime.matches" }) { "콘텐츠 타입 조건은 공유 규칙에서 사용할 수 있어요" }

        com.vibe.notiflow.domain.action.WebhookPayload.build(payloadTemplate, NotificationEvent("preview", "예시", "예시", postedAt = 1, source = source))
        headers.forEach { (key, value) ->
            require(Regex("[!#$%&' *+.^_`|~0-9A-Za-z-]+".replace(" ", "")).matches(key) && !value.contains('\r') && !value.contains('\n')) { "요청 헤더 형식을 확인해 주세요" }
        }

        val tokenInput = if (webhook?.has("token") == true) webhook.optString("token") else null
        val existingTokenRef = existingConfig?.get("tokenRef")?.jsonPrimitive?.contentOrNull
        val tokenRef = when {
            tokenInput == null -> existingTokenRef
            tokenInput.trim().isBlank() -> null
            else -> {
                val alias = "webhook_${UUID.randomUUID()}"
                ServiceLocator.secureStore.putSecret(alias, tokenInput.trim())
                alias
            }
        }

        val actionConfig = buildJsonObject {
            put("url", webhookUrl)
            put("method", method)
            if (payloadTemplate.isNotBlank()) put("payloadTemplate", payloadTemplate)
            if (headers.isNotEmpty()) {
                put(
                    "headers",
                    buildJsonObject {
                        headers.forEach { (key, value) -> put(key, value) }
                    }
                )
            }
            tokenRef?.let { put("tokenRef", it) }
        }

        val filters = buildList {
            add(FilterSpec(type = "source.equals", config = jsonValue(source)))
            if (packageName.isNotBlank()) add(FilterSpec(type = "package.equals", config = jsonValue(packageName)))
            conditions.forEach { (type, value) ->
                add(FilterSpec(type = type, config = jsonValue(value)))
            }
        }

        val rawName = when {
            input.has("name") -> input.optString("name").trim()
            existing != null -> existing.name
            else -> ""
        }
        val ruleName = rawName.ifBlank { autoRuleName(packageName, conditions) }
        require(ruleName.length <= 120) { "규칙 이름은 120자까지 입력할 수 있어요" }

        val enabled = if (input.has("enabled")) input.optBoolean("enabled") else existing?.enabled ?: true
        val priority = if (input.has("priority")) input.optInt("priority", 100) else existing?.priority ?: 100

        return Rule(
            id = existing?.id ?: 0,
            name = ruleName,
            enabled = enabled,
            priority = priority,
            targetPackages = if (packageName.isBlank()) emptyList() else listOf(packageName),
            filters = filters,
            filterOperator = filterOperator,
            conditionExpression = conditionExpression,
            actions = listOf(ActionSpec("webhook.post", actionConfig))
        )
    }

}
