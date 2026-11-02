package com.vibe.notiflow.remote

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Bound to one VPN address, never a wildcard or LAN interface. */
class RemoteHttpServer(
    host: String,
    port: Int,
    private val accessCode: String,
    private val authorized: () -> Boolean,
    private val asset: (String) -> ByteArray?,
    private val execute: (String, JSONArray) -> JSONObject
) : NanoHTTPD(host, port) {
    override fun serve(session: IHTTPSession): Response {
        val expectedHost = "$hostname:$listeningPort"
        if (session.headers["host"] != expectedHost) return json(Response.Status.FORBIDDEN, "접속 주소를 확인해 주세요")
        val origin = session.headers["origin"]
        if (origin != null && origin != "http://$expectedHost") return json(Response.Status.FORBIDDEN, "접속 주소를 확인해 주세요")
        if (session.uri == "/api/rpc") {
            if (session.method != Method.POST) return json(Response.Status.METHOD_NOT_ALLOWED, "POST 요청이 필요합니다")
            val provided = session.headers["authorization"]?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ") ?: ""
            if (!authorized() || !MessageDigest.isEqual(provided.toByteArray(), accessCode.toByteArray())) {
                return json(Response.Status.UNAUTHORIZED, "접속 코드를 확인하거나 앱에서 서버를 다시 실행해 주세요")
            }
            val length = session.headers["content-length"]?.toLongOrNull()
            if (length == null || length !in 1..MAX_BODY || session.headers.containsKey("transfer-encoding")) {
                return json(Response.Status.PAYLOAD_TOO_LARGE, "요청 본문은 256KB까지 전송할 수 있습니다")
            }
            if (session.headers["content-type"]?.substringBefore(';')?.trim() != "application/json") {
                return json(Response.Status.UNSUPPORTED_MEDIA_TYPE, "JSON 요청이 필요합니다")
            }
            return try {
                val body = HashMap<String, String>()
                session.parseBody(body)
                val input = JSONObject(body["postData"] ?: "")
                val method = input.getString("method")
                require(method in METHODS) { "지원하지 않는 요청입니다" }
                val data = execute(method, input.optJSONArray("args") ?: JSONArray())
                secured(newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8",
                    JSONObject().put("ok", true).put("data", data).toString()))
            } catch (_: org.json.JSONException) {
                json(Response.Status.BAD_REQUEST, "요청 형식을 확인해 주세요")
            } catch (error: IllegalArgumentException) {
                json(Response.Status.BAD_REQUEST, error.message ?: "규칙 내용을 확인해 주세요")
            } catch (_: Exception) {
                json(Response.Status.INTERNAL_ERROR, "처리하지 못했어요. 연결과 규칙을 확인하고 다시 시도해 주세요")
            }
        }
        if (session.method != Method.GET) return json(Response.Status.METHOD_NOT_ALLOWED, "GET 요청이 필요합니다")
        val path = when {
            session.uri in setOf("/", "/index.html") -> "index.html"
            session.uri == "/notiflow-icon.png" -> "notiflow-icon.png"
            Regex("/assets/[a-zA-Z0-9_.-]+\\.(js|css)").matches(session.uri) -> session.uri.removePrefix("/")
            else -> return json(Response.Status.NOT_FOUND, "페이지를 찾지 못했어요")
        }
        var bytes = asset(path) ?: return json(Response.Status.NOT_FOUND, "페이지를 찾지 못했어요")
        val type = when {
            path.endsWith(".js") -> "application/javascript; charset=utf-8"
            path.endsWith(".css") -> "text/css; charset=utf-8"
            path.endsWith(".png") -> "image/png"
            else -> "text/html; charset=utf-8"
        }
        if (path == "index.html") bytes = bytes.toString(Charsets.UTF_8)
            .replace("</head>", "<meta name=\"notiflow-remote\" content=\"true\"></head>").toByteArray()
        return secured(newFixedLengthResponse(Response.Status.OK, type, bytes.inputStream(), bytes.size.toLong()))
    }

    private fun json(status: Response.Status, message: String) = secured(newFixedLengthResponse(
        status, "application/json; charset=utf-8", JSONObject().put("ok", false).put("error", message).toString()
    ))

    private fun secured(response: Response) = response.apply {
        // Rejected requests may still have unread bodies; never reuse that socket.
        closeConnection(true)
        addHeader("Cache-Control", "no-store")
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("X-Frame-Options", "DENY")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
    }

    companion object {
        private const val MAX_BODY = 262144L
        private val METHODS = setOf("getAuthState", "listRules", "createRule", "updateRule", "setRuleEnabled", "deleteRule", "listLogs")
    }
}
