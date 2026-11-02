package com.vibe.notiflow

import com.vibe.notiflow.domain.action.ActionRegistry
import com.vibe.notiflow.domain.action.EventAction
import com.vibe.notiflow.domain.engine.RuleEngine
import com.vibe.notiflow.domain.filter.*
import com.vibe.notiflow.domain.model.*
import com.vibe.notiflow.domain.repo.RuleRepository
import com.vibe.notiflow.domain.retry.RetryScheduler
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareEngineTest {
    @Test fun sharesUseTheirOwnSourceGateAndExplicitRepeatsExecuteIndependently() = runTest {
        val repository = mockk<RuleRepository>()
        val logs = mutableListOf<ExecutionLog>()
        val value: (String) -> JsonObject = { v -> buildJsonObject { put("value", v) } }
        val shareRule = Rule(1, "공유 이미지", true, 100, emptyList(),
            listOf(FilterSpec("source.equals", value("share"))),
            conditionExpression = ConditionExpression(listOf(ConditionExpressionRow("mime.matches", "image/*"))),
            actions = listOf(ActionSpec("webhook.post", buildJsonObject {})))
        val notificationRule = shareRule.copy(id = 2, filters = listOf(FilterSpec("source.equals", value("notification"))),
            conditionExpression = ConditionExpression(listOf(ConditionExpressionRow("text.contains", "공유"))))
        coEvery { repository.getEnabledRulesForPackage("share") } returns listOf(notificationRule, shareRule)
        coEvery { repository.insertLog(capture(logs)) } returns Unit
        val executed = mutableListOf<String?>()
        val action = object : EventAction {
            override val type = "webhook.post"
            override suspend fun execute(event: NotificationEvent, config: JsonObject): ActionResult {
                executed.add(event.eventId)
                return ActionResult(true, "ok")
            }
        }
        val engine = RuleEngine(repository,
            FilterRegistry(listOf(EventSourceFilter(), MimeTypeFilter(), TextContainsFilter())),
            ActionRegistry(listOf(action)), mockk<RetryScheduler>(relaxed = true))
        val event = NotificationEvent("share", "사진", "공유", postedAt = 1,
            source = "share", mimeType = "image/png", eventId = "first")
        engine.process(event)
        engine.process(event.copy(eventId = "second"))
        assertEquals(listOf("first", "second"), executed)
        assertEquals(listOf(1L, 1L), logs.map { it.ruleId })
    }

    @Test fun noEnabledRulesStillProducesAVisibleShareResult() = runTest {
        val repository = mockk<RuleRepository>()
        val logs = mutableListOf<ExecutionLog>()
        coEvery { repository.getEnabledRulesForPackage("share") } returns emptyList()
        coEvery { repository.insertLog(capture(logs)) } returns Unit
        val engine = RuleEngine(repository, FilterRegistry(emptyList()), ActionRegistry(emptyList()), mockk())
        engine.process(NotificationEvent("share", "파일", null, postedAt = 1, source = "share", eventId = "no-rule"))
        assertEquals("NO_MATCH", logs.single().result)
    }
}
