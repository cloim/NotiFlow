package com.vibe.notiflow.domain.repo

import com.vibe.notiflow.data.local.ExecutionLogEntity
import com.vibe.notiflow.data.local.LogDao
import com.vibe.notiflow.data.local.RuleDao
import com.vibe.notiflow.data.local.RuleEntity
import com.vibe.notiflow.domain.model.ActionSpec
import com.vibe.notiflow.domain.model.ConditionExpression
import com.vibe.notiflow.domain.model.ExecutionLog
import com.vibe.notiflow.domain.model.FilterOperator
import com.vibe.notiflow.domain.model.FilterSpec
import com.vibe.notiflow.domain.model.Rule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

private const val CONDITION_EXPRESSION_FILTER_TYPE = "condition.expression.v1"

class RuleRepository(private val ruleDao: RuleDao, private val logDao: LogDao) {
    fun observeRules(): Flow<List<Rule>> = ruleDao.observeRules().map { it.map(RuleEntity::toDomain) }
    fun observeLogs(): Flow<List<ExecutionLog>> = logDao.observeLogs().map { it.map(ExecutionLogEntity::toDomain) }
    suspend fun getAllRules(): List<Rule> = ruleDao.getAllRules().map { it.toDomain() }
    suspend fun getRuleById(id: Long): Rule? = ruleDao.getById(id)?.toDomain()
    suspend fun getRecentLogs(limit: Int = 200): List<ExecutionLog> =
        logDao.getRecentLogs(limit).map { it.toDomain() }

    suspend fun getEnabledRulesForPackage(packageName: String): List<Rule> =
        ruleDao.getEnabledRules().map { it.toDomain() }
            .filter { it.targetPackages.isEmpty() || it.targetPackages.contains(packageName) }

    suspend fun upsertRule(rule: Rule): Long = ruleDao.upsert(rule.toEntity())
    suspend fun upsertRules(rules: List<Rule>): List<Long> = ruleDao.upsertAll(rules.map { it.toEntity() })
    suspend fun updateEnabled(id: Long, enabled: Boolean) = ruleDao.updateEnabled(id, enabled)
    suspend fun deleteRule(id: Long) = ruleDao.deleteById(id)
    suspend fun insertLog(log: ExecutionLog) = logDao.insert(log.toEntity())
}

private fun RuleEntity.toDomain(): Rule {
    val persistedFilters = Json.decodeFromString<List<FilterSpec>>(filtersJson)
    val expression = persistedFilters
        .firstOrNull { it.type == CONDITION_EXPRESSION_FILTER_TYPE }
        ?.config
        ?.let { config ->
            runCatching { Json.decodeFromJsonElement<ConditionExpression>(config) }.getOrNull()
        }

    return Rule(
        id = id,
        name = name,
        enabled = enabled,
        priority = priority,
        targetPackages = Json.decodeFromString(targetPackagesJson),
        filters = persistedFilters.filterNot { it.type == CONDITION_EXPRESSION_FILTER_TYPE },
        filterOperator = runCatching { FilterOperator.valueOf(filterOperator) }.getOrDefault(FilterOperator.AND),
        conditionExpression = expression,
        actions = Json.decodeFromString<List<ActionSpec>>(actionsJson)
    )
}

private fun Rule.toEntity() = RuleEntity(
    id = id,
    name = name,
    enabled = enabled,
    priority = priority,
    targetPackagesJson = Json.encodeToString(targetPackages),
    filtersJson = Json.encodeToString(
        buildList {
            addAll(filters.filterNot { it.type == CONDITION_EXPRESSION_FILTER_TYPE })
            conditionExpression?.let { expression ->
                add(
                    FilterSpec(
                        type = CONDITION_EXPRESSION_FILTER_TYPE,
                        config = Json.encodeToJsonElement(expression).jsonObject
                    )
                )
            }
        }
    ),
    filterOperator = filterOperator.name,
    actionsJson = Json.encodeToString(actions),
    createdAt = System.currentTimeMillis()
)

private fun ExecutionLogEntity.toDomain() = ExecutionLog(id, ruleId, matched, result, message, executedAt, eventPackage, eventTitle)
private fun ExecutionLog.toEntity() = ExecutionLogEntity(id, ruleId, matched, result, message, executedAt, eventPackage, eventTitle)
