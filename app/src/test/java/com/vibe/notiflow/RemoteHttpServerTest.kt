package com.vibe.notiflow

import com.vibe.notiflow.domain.model.ExecutionLog
import com.vibe.notiflow.domain.model.Rule
import com.vibe.notiflow.domain.repo.RuleRepository
import com.vibe.notiflow.remote.RemoteHttpServer
import com.vibe.notiflow.remote.TailscaleAddress
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class RemoteHttpServerTest {
    private lateinit var server: RemoteHttpServer
    private val client = OkHttpClient()
    private val code = "test-session-code"
    private var ownerSignedIn = true
    private val stored = linkedMapOf<Long, Rule>()

    @Before fun start() {
        val repository = mockk<RuleRepository>()
        coEvery { repository.getAllRules() } answers { stored.values.toList() }
        coEvery { repository.getRuleById(any()) } answers { stored[firstArg()] }
        coEvery { repository.upsertRule(any()) } answers {
            val rule = firstArg<Rule>()
            val id = if (rule.id == 0L) 1L else rule.id
            stored[id] = rule.copy(id = id)
            id
        }
        coEvery { repository.updateEnabled(any(), any()) } answers {
            val id = firstArg<Long>()
            stored[id] = stored.getValue(id).copy(enabled = secondArg())
        }
        coEvery { repository.deleteRule(any()) } answers { stored.remove(firstArg<Long>()); Unit }
        coEvery { repository.getRecentLogs(any()) } returns listOf(
            ExecutionLog(1, 1, true, "SUCCESS", "전송 완료", 100, "share", "문서.pdf")
        )
        val management = RuleManagement(repository)
        server = RemoteHttpServer("127.0.0.1", 0, code, { ownerSignedIn },
            asset = { if (it == "index.html") "<html><head></head><body></body></html>".toByteArray() else null },
            execute = { method, args ->
                if (method == "getAuthState") JSONObject().put("signedIn", true)
                else runBlocking { management.execute(method, args) }
            })
        server.start(2000, true)
    }

    @After fun stop() { server.stop() }
    private fun url(path: String = "/api/rpc") = "http://127.0.0.1:${server.listeningPort}$path"
    private fun rpc(method: String, args: JSONArray = JSONArray(), auth: String? = code, origin: String? = null): Pair<Int, JSONObject> {
        val body = JSONObject().put("method", method).put("args", args).toString()
        val request = Request.Builder().url(url()).post(body.toRequestBody("application/json".toMediaType()))
        auth?.let { request.header("Authorization", "Bearer $it") }
        origin?.let { request.header("Origin", it) }
        return client.newCall(request.build()).execute().use { it.code to JSONObject(it.body!!.string()) }
    }

    @Test fun requiresSessionAndRejectsStaleOwnerAndCrossOrigin() {
        assertEquals(401, rpc("listRules", auth = null).first)
        assertEquals(401, rpc("listRules", auth = "incorrect").first)
        assertEquals(403, rpc("listRules", origin = "https://example.com").first)
        assertEquals(200, rpc("listRules", origin = "http://127.0.0.1:${server.listeningPort}").first)
        assertEquals(400, rpc("startServer").first)
        ownerSignedIn = false
        assertEquals(401, rpc("listRules").first)
        assertEquals(401, rpc("createRule", JSONArray().put("{}")).first)
        assertTrue(stored.isEmpty())
    }

    @Test fun remoteCreateEditToggleDeleteAndLogsUseTheSharedRuleManagement() {
        val input = JSONObject("""{"name":"원격 PDF","source":"share","webhook":{"url":"https://example.com/hook"},"conditionExpression":{"rows":[{"type":"mime.matches","value":"application/pdf"}]}}""")
        assertEquals(200, rpc("createRule", JSONArray().put(input.toString())).first)
        val rule = rpc("listRules").second.getJSONObject("data").getJSONArray("rules").getJSONObject(0)
        assertEquals("share", rule.getString("source"))
        assertEquals("application/pdf", rule.getJSONObject("conditionExpression").getJSONArray("rows").getJSONObject(0).getString("value"))
        input.put("id", 1).put("name", "원격 PDF 수정")
        assertEquals(200, rpc("updateRule", JSONArray().put(input.toString())).first)
        assertEquals("원격 PDF 수정", stored.getValue(1).name)
        assertEquals(200, rpc("setRuleEnabled", JSONArray().put(1).put(false)).first)
        assertFalse(stored.getValue(1).enabled)
        val logs = rpc("listLogs", JSONArray().put(200)).second.getJSONObject("data").getJSONArray("logs")
        assertEquals("문서.pdf", logs.getJSONObject(0).getString("eventTitle"))
        assertEquals(200, rpc("deleteRule", JSONArray().put(1)).first)
        assertTrue(stored.isEmpty())
    }

    @Test fun refusesInvalidRulesWithoutWritingAndRejectsUnknownOrOversizeRequests() {
        val invalid = """{"name":"잘못된 규칙","source":"share","webhook":{"url":"file:///private"}}"""
        assertEquals(400, rpc("createRule", JSONArray().put(invalid)).first)
        assertTrue(stored.isEmpty())
        val body = " ".repeat(262145).toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(url()).header("Authorization", "Bearer $code").post(body).build()).execute().use {
            assertEquals(413, it.code)
        }
        client.newCall(Request.Builder().url(url()).header("Host", "example.com").get().build()).execute().use {
            assertEquals(403, it.code)
        }
    }

    @Test fun servesOnlyBundledAssetsWithoutLeakingTheAccessCode() {
        client.newCall(Request.Builder().url(url("/")).build()).execute().use {
            val html = it.body!!.string()
            assertEquals(200, it.code)
            assertTrue(html.contains("notiflow-remote"))
            assertFalse(html.contains(code))
            assertEquals("no-store", it.header("Cache-Control"))
            assertEquals("DENY", it.header("X-Frame-Options"))
        }
        for (path in listOf("/local.properties", "/assets/%2e%2e/private", "/api/logs")) {
            client.newCall(Request.Builder().url(url(path)).build()).execute().use { assertEquals(404, it.code) }
        }
    }

    @Test fun aRestartInvalidatesThePreviousAccessCode() {
        server.stop()
        server = RemoteHttpServer("127.0.0.1", 0, "new-test-session", { true }, { null }, { _, _ -> JSONObject() })
        server.start(2000, true)
        assertEquals(401, rpc("listRules").first)
        assertEquals(200, rpc("listRules", auth = "new-test-session").first)
    }

    @Test fun tailnetRangeExcludesLanAndCarrierAddressesOutsideTheAllocation() {
        assertTrue(TailscaleAddress.isTailnetIpv4(byteArrayOf(100, 64, 0, 1)))
        assertTrue(TailscaleAddress.isTailnetIpv4(byteArrayOf(100, 127, -1, -1)))
        for (ip in listOf(byteArrayOf(100, 63, 0, 1), byteArrayOf(100, -128, 0, 1), byteArrayOf(10, 0, 0, 1))) {
            assertFalse(TailscaleAddress.isTailnetIpv4(ip))
        }
    }
}
