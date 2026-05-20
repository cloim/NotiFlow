package com.vibe.notiflow.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class NotificationEvent(
    val packageName: String,
    val title: String?,
    val text: String?,
    val extras: Map<String, String> = emptyMap(),
    val postedAt: Long
)

@Serializable
enum class FilterOperator { AND, OR }

@Serializable
data class ConditionExpressionRow(
    val type: String,
    val value: String,
    val operator: FilterOperator? = null,
    val openParen: Int = 0,
    val closeParen: Int = 0
)

@Serializable
data class ConditionExpression(
    val rows: List<ConditionExpressionRow>
)

@Serializable
data class Rule(
    val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    val priority: Int,
    val targetPackages: List<String>,
    val filters: List<FilterSpec>,
    val filterOperator: FilterOperator = FilterOperator.AND,
    val conditionExpression: ConditionExpression? = null,
    val actions: List<ActionSpec>
)

@Serializable data class FilterSpec(val type: String, val config: JsonObject)
@Serializable data class ActionSpec(val type: String, val config: JsonObject)

@Serializable
data class ExecutionLog(
    val id: Long = 0,
    val ruleId: Long,
    val matched: Boolean,
    val result: String,
    val message: String,
    val executedAt: Long,
    val eventPackage: String,
    val eventTitle: String?
)

data class ActionResult(val success: Boolean, val message: String, val retryable: Boolean = false)
