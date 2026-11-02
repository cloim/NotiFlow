package com.vibe.notiflow

import com.vibe.notiflow.domain.action.WebhookPayload
import com.vibe.notiflow.domain.filter.MimeTypeFilter
import com.vibe.notiflow.domain.filter.EventSourceFilter
import com.vibe.notiflow.domain.model.NotificationEvent
import com.vibe.notiflow.domain.model.SharedAttachment
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ShareRulesTest {
    private val event = NotificationEvent("share", "문서", "따옴표 \"와\n줄바꿈 {{literal}}", postedAt = 1,
        source = "share", mimeType = "*/*", attachments = listOf(
            SharedAttachment("사진.png", "image/png", 3, "YWJj"),
            SharedAttachment("문서.pdf", "application/pdf", 3, "ZGVm")))

    private fun config(value: String) = buildJsonObject { put("value", value) }

    @Test fun mixedFilesMatchResolvedMimeTypesInsteadOfWildcardIntentType() {
        val filter = MimeTypeFilter()
        assertTrue(filter.matches(event, config("image/*")))
        assertTrue(filter.matches(event, config("application/pdf")))
        assertFalse(filter.matches(event, config("video/*")))
        assertFalse(filter.matches(event.copy(source = "notification"), config("*/*")))
        assertTrue(filter.matches(event.copy(attachments = emptyList(), mimeType = "text/plain; charset=utf-8"), config("text/*")))
    }

    @Test fun sourceSeparatesNotificationsFromShares() {
        assertTrue(EventSourceFilter().matches(event, config("share")))
        assertFalse(EventSourceFilter().matches(event, config("notification")))
    }

    @Test fun payloadIncludesBinaryContentsAndEscapesTextWithoutReplacingItsLiterals() {
        val body = JSONObject(WebhookPayload.build("""{"text":{{text}},"files":{{attachments}},"source":{{source}}}""", event))
        assertEquals(event.text, body.getString("text"))
        assertEquals("YWJj", body.getJSONArray("files").getJSONObject(0).getString("base64"))
        assertEquals("share", body.getString("source"))
        assertEquals("notification", JSONObject(WebhookPayload.build(null, event.copy(source = "notification"))).getString("source"))
    }

    @Test fun rejectsUnknownVariablesAndTrailingJson() {
        assertTrue(runCatching { WebhookPayload.build("""{"data":{{unknown}}}""", event) }.isFailure)
        assertTrue(runCatching { WebhookPayload.build("{}{}", event) }.isFailure)
    }

    @Test fun sourceOnlyRuleAndClearingExistingConditionsAreSupported() {
        val factory = RuleInputFactory()
        val input = JSONObject("""{"name":"모든 공유","source":"share","webhook":{"url":"https://example.com/hook"},"conditionExpression":{"rows":[]}}""")
        val rule = factory.build(input, null)
        assertTrue(rule.targetPackages.isEmpty())
        assertNull(rule.conditionExpression)
        assertEquals("source.equals", rule.filters.single().type)
        val withCondition = JSONObject(input.toString()).put("conditionExpression", JSONObject("""{"rows":[{"type":"mime.matches","value":"image/*"}]}"""))
        val restricted = factory.build(withCondition, null)
        assertEquals("mime.matches", restricted.conditionExpression!!.rows.single().type)
        assertNull(factory.build(input, restricted).conditionExpression)
    }
}
