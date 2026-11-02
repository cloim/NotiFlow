package com.vibe.notiflow

import com.vibe.notiflow.domain.model.Rule
import com.vibe.notiflow.domain.repo.RuleRepository
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

/** One validation and storage path for the WebView and the remote editor. */
class RuleManagement(private val repository: RuleRepository) {
    suspend fun execute(method: String, args: JSONArray): JSONObject = when (method) {
        "listRules" -> JSONObject().put("rules", JSONArray().apply {
            repository.getAllRules().forEach { put(ruleJson(it)) }
        })
        "createRule", "updateRule" -> {
            val input = JSONObject(args.getString(0))
            val existing = if (method == "updateRule") {
                repository.getRuleById(input.getLong("id")) ?: error("규칙을 다시 불러와 주세요")
            } else null
            val rule = RuleInputFactory().build(input, existing)
            JSONObject().put("ruleId", repository.upsertRule(rule))
        }
        "setRuleEnabled" -> {
            val id = args.getLong(0)
            checkNotNull(repository.getRuleById(id)) { "규칙을 다시 불러와 주세요" }
            repository.updateEnabled(id, args.getBoolean(1))
            JSONObject()
        }
        "deleteRule" -> {
            repository.deleteRule(args.getLong(0))
            JSONObject()
        }
        "listLogs" -> JSONObject().put("logs", JSONArray(Json.encodeToString(
            repository.getRecentLogs(args.optInt(0, 200).coerceIn(1, 500))
        )))
        else -> throw IllegalArgumentException("지원하지 않는 요청입니다")
    }

    private fun ruleJson(rule: Rule): JSONObject {
        val result = JSONObject(Json.encodeToString(rule))
        val source = rule.filters.firstOrNull { it.type == "source.equals" }
            ?.config?.get("value")?.jsonPrimitive?.contentOrNull
        result.put("source", source ?: "notification")
        if (rule.conditionExpression == null) {
            val rows = JSONArray()
            rule.filters.filter { it.type !in setOf("package.equals", "source.equals") }.forEach {
                rows.put(JSONObject().put("type", it.type)
                    .put("value", it.config["value"]?.jsonPrimitive?.contentOrNull ?: "")
                    .put("operator", rule.filterOperator.name))
            }
            result.put("conditionExpression", JSONObject().put("rows", rows))
        }
        return result
    }
}
