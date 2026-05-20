package com.vibe.notiflow

import com.vibe.notiflow.domain.action.ActionRegistry
import com.vibe.notiflow.domain.action.EventAction
import com.vibe.notiflow.domain.engine.RuleEngine
import com.vibe.notiflow.domain.filter.FilterRegistry
import com.vibe.notiflow.domain.filter.PackageEqualsFilter
import com.vibe.notiflow.domain.filter.TextContainsFilter
import com.vibe.notiflow.domain.model.ActionResult
import com.vibe.notiflow.domain.model.ActionSpec
import com.vibe.notiflow.domain.model.ExecutionLog
import com.vibe.notiflow.domain.model.FilterOperator
import com.vibe.notiflow.domain.model.FilterSpec
import com.vibe.notiflow.domain.model.NotificationEvent
import com.vibe.notiflow.domain.model.Rule
import com.vibe.notiflow.domain.repo.RuleRepository
import com.vibe.notiflow.domain.retry.RetryScheduler
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineFlowTest {
    @Test
    fun eventMatchingRuleExecutesActionAndStoresSuccessLog() = runTest {
        val repository = mockk<RuleRepository>()
        val retryScheduler = mockk<RetryScheduler>()
        val insertedLogs = mutableListOf<ExecutionLog>()

        coEvery { repository.getEnabledRulesForPackage("com.test") } returns listOf(
            testRule(
                id = 1,
                packageName = "com.test",
                filterType = "text.contains",
                filterValue = "otp"
            )
        )
        coEvery { repository.insertLog(capture(insertedLogs)) } returns Unit
        every { retryScheduler.scheduleWebhookRetry(any(), any(), any()) } just runs

        val action = object : EventAction {
            override val type: String = "webhook.post"
            override suspend fun execute(
                event: NotificationEvent,
                config: kotlinx.serialization.json.JsonObject
            ): ActionResult = ActionResult(success = true, message = "ok")
        }

        val engine = RuleEngine(
            repository = repository,
            filters = FilterRegistry(listOf(PackageEqualsFilter(), TextContainsFilter())),
            actions = ActionRegistry(listOf(action)),
            retryScheduler = retryScheduler
        )

        engine.process(
            NotificationEvent(
                packageName = "com.test",
                title = "Alert",
                text = "otp code 1234",
                postedAt = System.currentTimeMillis()
            )
        )

        coVerify(exactly = 1) { repository.insertLog(any()) }
        assertTrue(insertedLogs.single().matched)
        assertEquals("SUCCESS", insertedLogs.single().result)
        verify(exactly = 0) { retryScheduler.scheduleWebhookRetry(any(), any(), any()) }
    }

    @Test
    fun eventNotMatchingRuleStoresFilterNoMatchLog() = runTest {
        val repository = mockk<RuleRepository>()
        val retryScheduler = mockk<RetryScheduler>()
        val insertedLogs = mutableListOf<ExecutionLog>()
        var actionExecuted = false

        coEvery { repository.getEnabledRulesForPackage("com.test") } returns listOf(
            testRule(
                id = 2,
                packageName = "com.test",
                filterType = "text.contains",
                filterValue = "payment"
            )
        )
        coEvery { repository.insertLog(capture(insertedLogs)) } returns Unit
        every { retryScheduler.scheduleWebhookRetry(any(), any(), any()) } just runs

        val action = object : EventAction {
            override val type: String = "webhook.post"
            override suspend fun execute(
                event: NotificationEvent,
                config: kotlinx.serialization.json.JsonObject
            ): ActionResult {
                actionExecuted = true
                return ActionResult(success = true, message = "ok")
            }
        }

        val engine = RuleEngine(
            repository = repository,
            filters = FilterRegistry(listOf(PackageEqualsFilter(), TextContainsFilter())),
            actions = ActionRegistry(listOf(action)),
            retryScheduler = retryScheduler
        )

        engine.process(
            NotificationEvent(
                packageName = "com.test",
                title = "Alert",
                text = "otp code 1234",
                postedAt = System.currentTimeMillis()
            )
        )

        assertEquals(1, insertedLogs.size)
        assertEquals("FILTER_NO_MATCH", insertedLogs.single().result)
        assertTrue(!insertedLogs.single().matched)
        assertTrue(!actionExecuted)
        verify(exactly = 0) { retryScheduler.scheduleWebhookRetry(any(), any(), any()) }
    }

    @Test
    fun retryableFailureSchedulesRetryAndStoresFailedLog() = runTest {
        val repository = mockk<RuleRepository>()
        val retryScheduler = mockk<RetryScheduler>()
        val insertedLogs = mutableListOf<ExecutionLog>()

        val rule = testRule(
            id = 3,
            packageName = "com.test",
            filterType = "text.contains",
            filterValue = "otp"
        )

        coEvery { repository.getEnabledRulesForPackage("com.test") } returns listOf(rule)
        coEvery { repository.insertLog(capture(insertedLogs)) } returns Unit
        every { retryScheduler.scheduleWebhookRetry(any(), any(), any()) } just runs

        val action = object : EventAction {
            override val type: String = "webhook.post"
            override suspend fun execute(
                event: NotificationEvent,
                config: kotlinx.serialization.json.JsonObject
            ): ActionResult = ActionResult(success = false, message = "temporary fail", retryable = true)
        }

        val engine = RuleEngine(
            repository = repository,
            filters = FilterRegistry(listOf(PackageEqualsFilter(), TextContainsFilter())),
            actions = ActionRegistry(listOf(action)),
            retryScheduler = retryScheduler
        )

        val event = NotificationEvent(
            packageName = "com.test",
            title = "Alert",
            text = "otp code 1234",
            postedAt = System.currentTimeMillis()
        )
        engine.process(event)

        assertEquals(1, insertedLogs.size)
        assertEquals("FAILED", insertedLogs.single().result)
        verify(exactly = 1) { retryScheduler.scheduleWebhookRetry(eq(3L), any(), any()) }
    }

    @Test
    fun legacyGlobalOperatorRegression_usesFilterOperatorWhenExpressionMissing() = runTest {
        val repository = mockk<RuleRepository>()
        val retryScheduler = mockk<RetryScheduler>()
        val insertedLogs = mutableListOf<ExecutionLog>()
        var actionExecuted = false

        val rule = Rule(
            id = 4,
            name = "legacy-rule",
            enabled = true,
            priority = 100,
            targetPackages = listOf("com.test"),
            filters = listOf(
                FilterSpec("package.equals", buildJsonObject { put("value", "com.test") }),
                FilterSpec("text.contains", buildJsonObject { put("value", "otp") }),
                FilterSpec("text.contains", buildJsonObject { put("value", "payment") })
            ),
            filterOperator = FilterOperator.OR,
            actions = listOf(
                ActionSpec(
                    "webhook.post",
                    buildJsonObject {
                        put("url", "https://example.com/webhook")
                        put("method", "POST")
                    }
                )
            )
        )

        coEvery { repository.getEnabledRulesForPackage("com.test") } returns listOf(rule)
        coEvery { repository.insertLog(capture(insertedLogs)) } returns Unit
        every { retryScheduler.scheduleWebhookRetry(any(), any(), any()) } just runs

        val action = object : EventAction {
            override val type: String = "webhook.post"
            override suspend fun execute(
                event: NotificationEvent,
                config: kotlinx.serialization.json.JsonObject
            ): ActionResult {
                actionExecuted = true
                return ActionResult(success = true, message = "ok")
            }
        }

        val engine = RuleEngine(
            repository = repository,
            filters = FilterRegistry(listOf(PackageEqualsFilter(), TextContainsFilter())),
            actions = ActionRegistry(listOf(action)),
            retryScheduler = retryScheduler
        )

        engine.process(
            NotificationEvent(
                packageName = "com.test",
                title = "Legacy",
                text = "payment alert",
                postedAt = System.currentTimeMillis()
            )
        )

        assertEquals(1, insertedLogs.size)
        assertEquals("SUCCESS", insertedLogs.single().result)
        assertTrue(insertedLogs.single().matched)
        assertTrue(actionExecuted)
    }

    private fun testRule(
        id: Long,
        packageName: String,
        filterType: String,
        filterValue: String
    ): Rule {
        return Rule(
            id = id,
            name = "rule-$id",
            enabled = true,
            priority = 100,
            targetPackages = listOf(packageName),
            filters = listOf(
                FilterSpec("package.equals", buildJsonObject { put("value", packageName) }),
                FilterSpec(filterType, buildJsonObject { put("value", filterValue) })
            ),
            filterOperator = FilterOperator.AND,
            actions = listOf(
                ActionSpec(
                    "webhook.post",
                    buildJsonObject {
                        put("url", "https://example.com/webhook")
                        put("method", "POST")
                    }
                )
            )
        )
    }
}
