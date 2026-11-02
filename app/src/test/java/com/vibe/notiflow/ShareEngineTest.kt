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
import org.junit.Assert.assertTrue
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
        assertEquals("웹훅 실행 성공", engine.process(event).shareMessage())
        engine.process(event.copy(eventId = "second"))
        assertTrue(engine.process(event.copy(eventId = "second")).duplicate)
        assertEquals(listOf("first", "second"), executed)
        assertEquals(listOf(1L, 1L), logs.map { it.ruleId })
    }

    @Test fun noEnabledRulesStillProducesAVisibleShareResult() = runTest {
        val repository = mockk<RuleRepository>()
        val logs = mutableListOf<ExecutionLog>()
        coEvery { repository.getEnabledRulesForPackage("share") } returns emptyList()
        coEvery { repository.insertLog(capture(logs)) } returns Unit
        val engine = RuleEngine(repository, FilterRegistry(emptyList()), ActionRegistry(emptyList()), mockk())
        val result = engine.process(NotificationEvent("share", "파일", null, postedAt = 1, source = "share", eventId = "no-rule"))
        assertEquals("NO_MATCH", logs.single().result)
        assertEquals("공유 실패: 실행할 웹훅 규칙이 없어요", result.shareMessage())
    }

    @Test fun shareFeedbackReflectsAllActualWebhookActionResults() = runTest {
        for (outcomes in listOf(listOf(true), listOf(false), listOf(true, false))) {
            val pending = outcomes.toMutableList()
            val repository = mockk<RuleRepository>()
            val action = object : EventAction {
                override val type = "webhook.post"
                override suspend fun execute(event: NotificationEvent, config: JsonObject) = ActionResult(pending.removeAt(0), "result")
            }
            val rule = Rule(1, "공유", true, 100, emptyList(),
                listOf(FilterSpec("source.equals", buildJsonObject { put("value", "share") })),
                actions = outcomes.map { ActionSpec("webhook.post", buildJsonObject {}) })
            coEvery { repository.getEnabledRulesForPackage("share") } returns listOf(rule)
            coEvery { repository.insertLog(any()) } returns Unit
            val engine = RuleEngine(repository, FilterRegistry(listOf(EventSourceFilter())),
                ActionRegistry(listOf(action)), mockk(relaxed = true))
            val result = engine.process(NotificationEvent("share", "링크", "https://example.com/", postedAt = 1, source = "share"))
            assertEquals(outcomes.count { it }, result.successfulActions)
            assertEquals(outcomes.count { !it }, result.failedActions)
            when {
                outcomes.all { it } -> assertEquals("웹훅 실행 성공", result.shareMessage())
                outcomes.none { it } -> assertTrue(result.shareMessage().startsWith("웹훅 실행 실패"))
                else -> assertEquals("웹훅 일부 실패: 성공 1건, 실패 1건", result.shareMessage())
            }
        }
    }
}
