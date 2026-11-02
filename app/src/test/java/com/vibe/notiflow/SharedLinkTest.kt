package com.vibe.notiflow

import com.vibe.notiflow.domain.action.WebhookPayload
import com.vibe.notiflow.domain.filter.MimeTypeFilter
import com.vibe.notiflow.domain.model.NotificationEvent
import com.vibe.notiflow.domain.model.SharedAttachment
import com.vibe.notiflow.domain.model.SharedContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SharedLinkTest {
    private val link = "https://example.com/article?from=share&next=%22document%22#part-1"
    private val shared = NotificationEvent("share", "공유 링크", link, postedAt = 1, source = "share", mimeType = "text/plain")
    private fun matches(event: NotificationEvent, mime: String) = MimeTypeFilter().matches(event, buildJsonObject { put("value", mime) })

    @Test fun plainMimeBrowserUrlMatchesLinkAndNotText() {
        assertTrue(matches(shared, "text/uri-list"))
        assertFalse(matches(shared, "text/plain"))
        assertTrue(matches(shared, "text/*")) // Existing wildcard rules still include both.
        assertEquals("text/uri-list", SharedContent.mimeType(shared))
        assertEquals(link, SharedContent.url(shared))
    }

    @Test fun ordinaryTextMatchesTextAndNotLink() {
        for (text in listOf("공유한 메모", "https://", "ftp://example.com/file", "https://example.com/a b")) {
            val event = shared.copy(text = text)
            assertTrue(matches(event, "text/plain"))
            assertFalse(matches(event, "text/uri-list"))
            assertNull(SharedContent.url(event))
        }
    }

    @Test fun titleAndUrlOnSeparateLinesPreserveTheExactUrl() {
        val event = shared.copy(text = "문서 제목\n  $link  \n")
        assertEquals(link, SharedContent.url(event))
        val body = JSONObject(WebhookPayload.build("""{"url":{{url}},"source":{{source}},"mime":{{mimeType}}}""", event))
        assertEquals(link, body.getString("url"))
        assertEquals("share", body.getString("source"))
        assertEquals("text/uri-list", body.getString("mime"))
    }

    @Test fun filesAndNotificationTextAreNotReclassifiedAsLinks() {
        val file = shared.copy(attachments = listOf(SharedAttachment("사진.png", "image/png", 3, "YWJj")))
        assertTrue(matches(file, "image/*"))
        assertFalse(matches(file, "text/uri-list"))
        assertNull(SharedContent.url(file))
        assertNull(SharedContent.url(shared.copy(source = "notification")))
        assertTrue(JSONObject(WebhookPayload.build("""{"url":{{url}}}""", file)).isNull("url"))
    }

    @Test fun acceptsBothTheReportedSourceTemplateAndTheLinkUrlTemplateOnSave() {
        for (variable in listOf("source", "url")) {
            val template = "{ \"url\": {{$variable}} }"
            val input = JSONObject().put("name", "공유 링크 수집").put("source", "share")
                .put("webhook", JSONObject().put("method", "POST")
                    .put("url", "https://example.com/api/v1/collections/example/documents/url")
                    .put("payloadTemplate", template))
            val rule = RuleInputFactory().build(input, null)
            assertEquals("POST", rule.actions.single().config["method"].toString().trim('"'))
            val body = JSONObject(WebhookPayload.build(template, shared))
            assertEquals(if (variable == "url") link else "share", body.getString("url"))
        }
    }

    @Test fun multipleVariablesHandleLiteralBracesQuotesAndNewlines() {
        val text = "중괄호 {{url}}, 따옴표 \"와\n줄바꿈"
        val body = JSONObject(WebhookPayload.build("""{"text":{{text}},"extras":{{extras}},"files":{{attachments}},"url":{{url}}}""", shared.copy(text = text)))
        assertEquals(text, body.getString("text"))
        assertTrue(body.isNull("url"))
        assertEquals(0, body.getJSONObject("extras").length())
        assertEquals(0, body.getJSONArray("files").length())
    }
}
