package com.vibe.notiflow.domain.transfer

import com.vibe.notiflow.domain.model.ActionSpec
import com.vibe.notiflow.domain.model.ConditionExpression
import com.vibe.notiflow.domain.model.ConditionExpressionRow
import com.vibe.notiflow.domain.model.FilterOperator
import com.vibe.notiflow.domain.model.FilterSpec
import com.vibe.notiflow.domain.model.Rule
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

object RuleTransfer {
    private const val SCHEMA_VERSION = 1
    private const val APP_NAME = "NotiFlow"

    fun exportRules(
        rules: List<Rule>,
        selectedRuleIds: Set<Long>,
        includeSecrets: Boolean,
        tokenResolver: (String) -> String?,
        nowMillis: () -> Long
    ): JSONObject {
        require(selectedRuleIds.isNotEmpty()) { "ruleIds must not be empty" }

        val ruleIds = rules.map { it.id }.toSet()
        val missingIds = selectedRuleIds.filterNot { it in ruleIds }.sorted()
        require(missingIds.isEmpty()) { "selected rules not found: ${missingIds.joinToString(", ")}" }

        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("app", APP_NAME)
            .put("exportedAt", nowMillis())
            .put("includeSecrets", includeSecrets)
            .put("rules", JSONArray(rules.filter { it.id in selectedRuleIds }.map { rule ->
                exportRule(rule, includeSecrets, tokenResolver)
            }))
    }

    fun importRuleInputs(inputJson: JSONObject): List<JSONObject> {
        val schemaVersion = inputJson.optInt("schemaVersion", -1)
        require(schemaVersion == SCHEMA_VERSION) { "unsupported rule export schema: $schemaVersion" }
        require(inputJson.optString("app") == APP_NAME) { "unsupported rule export app: ${inputJson.optString("app")}" }

        val rules = inputJson.optJSONArray("rules")
        require(rules != null && rules.length() > 0) { "rules must not be empty" }

        return (0 until rules.length()).map { index ->
            JSONObject(rules.getJSONObject(index).toString()).apply { remove("id") }
        }
    }

    private fun exportRule(
        rule: Rule,
        includeSecrets: Boolean,
        tokenResolver: (String) -> String?
    ): JSONObject {
        val expression = rule.conditionExpression ?: legacyExpression(rule.filters, rule.filterOperator)
        return JSONObject()
            .put("id", rule.id)
            .put("name", rule.name)
            .put("packageName", rule.targetPackages.firstOrNull().orEmpty())
            .put("conditionOperator", rule.filterOperator.name)
            .put("conditions", conditionsJson(expression))
            .put("conditionExpression", JSONObject(Json.encodeToString(expression)))
            .put("enabled", rule.enabled)
            .put("priority", rule.priority)
            .put("webhook", webhookJson(rule.actions.firstOrNull { it.type == "webhook.post" }, includeSecrets, tokenResolver))
    }

    private fun legacyExpression(filters: List<FilterSpec>, operator: FilterOperator): ConditionExpression {
        val legacyRows = filters
            .filter { it.type != "package.equals" }
            .mapNotNull { filter ->
                filter.config["value"]?.jsonPrimitive?.contentOrNull?.let { value ->
                    filter.type to value
                }
            }

        return ConditionExpression(
            legacyRows.mapIndexed { index, (type, value) ->
                ConditionExpressionRow(
                    type = type,
                    value = value,
                    operator = if (index < legacyRows.lastIndex) operator else null
                )
            }
        )
    }

    private fun conditionsJson(expression: ConditionExpression): JSONArray {
        return JSONArray(expression.rows.map { row ->
            JSONObject()
                .put("type", row.type)
                .put("value", row.value)
                .apply {
                    row.operator?.let { put("operator", it.name) }
                    if (row.openParen != 0) put("openParen", row.openParen)
                    if (row.closeParen != 0) put("closeParen", row.closeParen)
                }
        })
    }

    private fun webhookJson(
        action: ActionSpec?,
        includeSecrets: Boolean,
        tokenResolver: (String) -> String?
    ): JSONObject {
        val config = action?.config ?: return JSONObject()
        return JSONObject()
            .put("url", config.stringValue("url"))
            .put("method", config.stringValue("method"))
            .apply {
                config["headers"]?.jsonObject?.takeIf { it.isNotEmpty() }?.let { headers ->
                    put("headers", JSONObject(headers.toString()))
                }
                config.stringValue("payloadTemplate").takeIf { it.isNotBlank() }?.let { payloadTemplate ->
                    put("payloadTemplate", payloadTemplate)
                }
                val tokenRef = config.stringValue("tokenRef")
                if (includeSecrets && tokenRef.isNotBlank()) {
                    tokenResolver(tokenRef)?.takeIf { it.isNotBlank() }?.let { token ->
                        put("token", token)
                    }
                }
            }
    }

    private fun JsonObject.stringValue(name: String): String {
        return this[name]?.jsonPrimitive?.contentOrNull.orEmpty()
    }
}
