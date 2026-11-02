package com.vibe.notiflow.domain.engine

import android.util.Log
import com.vibe.notiflow.domain.action.ActionRegistry
import com.vibe.notiflow.domain.filter.FilterRegistry
import com.vibe.notiflow.domain.model.ExecutionLog
import com.vibe.notiflow.domain.model.FilterOperator
import com.vibe.notiflow.domain.model.NotificationEvent
import com.vibe.notiflow.domain.repo.RuleRepository
import com.vibe.notiflow.domain.retry.RetryScheduler
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RuleEngine(
    private val repository: RuleRepository,
    private val filters: FilterRegistry,
    val actions: ActionRegistry,
    private val retryScheduler: RetryScheduler
) {
    private val dedupe = LinkedHashMap<String, Long>()
    private val rateLimit = mutableMapOf<Long, Long>()
    private val mutex = Mutex()
    private fun logDebug(message: String) {
        runCatching { Log.d("NotiFlow", message) }
    }

    suspend fun process(event: NotificationEvent): ProcessingSummary = mutex.withLock {
        val now = System.currentTimeMillis()
        val key = event.eventId ?: "${event.source}:${event.packageName}:${event.title}:${event.text}"
        dedupe[key]?.let { if (now - it < 5_000) { logDebug("Dedupe skip"); return@withLock ProcessingSummary(duplicate = true) } }
        dedupe[key] = now
        if (dedupe.size > 500) dedupe.remove(dedupe.entries.first().key)

        val rules = repository.getEnabledRulesForPackage(event.packageName).sortedByDescending { it.priority }
        var executed = false
        var succeeded = 0
        var failed = 0
        logDebug("RuleEngine.process: pkg=${event.packageName} rules=${rules.size}")
        for (rule in rules) {
            if (event.source != "share" && now - (rateLimit[rule.id] ?: 0L) < 2_000) continue

            val sourceFilter = rule.filters.firstOrNull { it.type == "source.equals" }
            if (sourceFilter == null && event.source != "notification") continue
            if (sourceFilter != null && filters.get("source.equals")?.matches(event, sourceFilter.config) != true) continue
            val packageFilters = rule.filters.filter { it.type == "package.equals" }
            val contentFilters = rule.filters.filter { it.type !in setOf("package.equals", "source.equals") }
            val packageMatch = packageFilters.all { spec -> filters.get(spec.type)?.matches(event, spec.config) == true }
            if (!packageMatch) {
                repository.insertLog(ExecutionLog(ruleId = rule.id, matched = false, result = "FILTER_NO_MATCH", message = "no match", executedAt = now, eventPackage = event.packageName, eventTitle = event.title))
                continue
            }

            val contentResult = if (rule.conditionExpression != null) {
                ConditionExpressionEvaluator.evaluate(rule.conditionExpression) { row ->
                    val handler = filters.get(row.type) ?: return@evaluate false
                    handler.matches(
                        event,
                        buildJsonObject { put("value", row.value) }
                    )
                }
            } else {
                val matched = contentFilters.isEmpty() || when (rule.filterOperator) {
                    FilterOperator.AND -> contentFilters.all { spec -> filters.get(spec.type)?.matches(event, spec.config) == true }
                    FilterOperator.OR -> contentFilters.any { spec -> filters.get(spec.type)?.matches(event, spec.config) == true }
                }
                ConditionExpressionEvaluator.EvaluationResult.Valid(matched)
            }

            when (contentResult) {
                is ConditionExpressionEvaluator.EvaluationResult.Invalid -> {
                    repository.insertLog(
                        ExecutionLog(
                            ruleId = rule.id,
                            matched = false,
                            result = "FILTER_INVALID_EXPRESSION",
                            message = contentResult.reason,
                            executedAt = now,
                            eventPackage = event.packageName,
                            eventTitle = event.title
                        )
                    )
                    continue
                }

                is ConditionExpressionEvaluator.EvaluationResult.Valid -> {
                    if (!contentResult.matched) {
                        repository.insertLog(
                            ExecutionLog(
                                ruleId = rule.id,
                                matched = false,
                                result = "FILTER_NO_MATCH",
                                message = "no match",
                                executedAt = now,
                                eventPackage = event.packageName,
                                eventTitle = event.title
                            )
                        )
                        continue
                    }
                }
            }

            for (action in rule.actions) {
                val handler = actions.get(action.type) ?: continue
                executed = true
                val result = handler.execute(event, action.config)
                if (result.success) succeeded++ else failed++
                repository.insertLog(ExecutionLog(ruleId = rule.id, matched = true, result = if (result.success) "SUCCESS" else "FAILED", message = result.message, executedAt = now, eventPackage = event.packageName, eventTitle = event.title))
                if (!result.success && result.retryable && action.type == "webhook.post") {
                    retryScheduler.scheduleWebhookRetry(rule.id, action, event)
                }
            }

            rateLimit[rule.id] = now
        }
        if (event.source == "share" && !executed) {
            repository.insertLog(ExecutionLog(ruleId = 0, matched = false, result = "NO_MATCH",
                message = "공유 콘텐츠와 일치하는 활성 규칙이 없어요", executedAt = now,
                eventPackage = "share", eventTitle = event.title))
        }
        ProcessingSummary(succeeded, failed)
    }
}
