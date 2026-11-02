package com.vibe.notiflow

import com.vibe.notiflow.data.local.SecureStore
import com.vibe.notiflow.domain.action.WebhookPostAction
import com.vibe.notiflow.domain.model.NotificationEvent
import com.vibe.notiflow.domain.model.SharedAttachment
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebhookDeliveryTest {
    @Test fun sendsShareBytesWithConfiguredMethodAndHeaderToAnHttpReceiver() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            server.start()
            val config = buildJsonObject {
                put("url", server.url("/hook").toString())
                put("method", "PATCH")
                put("headers", buildJsonObject { put("X-Flow", "documents") })
            }
            val result = WebhookPostAction(mockk<SecureStore>()).execute(
                NotificationEvent("share", "공유 파일", "본문", postedAt = 1, source = "share", mimeType = "application/pdf",
                    attachments = listOf(SharedAttachment("문서.pdf", "application/pdf", 3, "YWJj"))), config)
            assertTrue(result.success)
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("documents", request.getHeader("X-Flow"))
            assertEquals("YWJj", JSONObject(request.body.readUtf8()).getJSONArray("attachments").getJSONObject(0).getString("base64"))
        }
    }

    @Test fun serverErrorsRetryWhileInvalidRequestsFailWithoutCrashingTheEngine() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.start()
            val action = WebhookPostAction(mockk<SecureStore>())
            val event = NotificationEvent("share", null, "text", postedAt = 1, source = "share")
            val result = action.execute(event, buildJsonObject { put("url", server.url("/hook").toString()) })
            assertFalse(result.success)
            assertTrue(result.retryable)
            val invalid = action.execute(event, buildJsonObject { put("url", "invalid") })
            assertFalse(invalid.success)
            assertFalse(invalid.retryable)
        }
    }
}
