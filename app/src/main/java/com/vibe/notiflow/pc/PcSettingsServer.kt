package com.vibe.notiflow.pc

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PcSettingsServer(
    private val context: Context,
    private val handler: Handler,
    private val preferredPort: Int = 8765
) {
    interface Handler {
        fun appInfo(): String
        fun notificationPermission(): Boolean
        fun listRules(): String
        fun listLogs(limit: Int): String
        fun listInstalledApps(includeSystem: Boolean): String
        fun createRule(inputJson: String): String
        fun updateRule(inputJson: String): String
        fun setRuleEnabled(ruleId: Long, enabled: Boolean): String
        fun deleteRule(ruleId: Long): String
    }

    data class State(
        val running: Boolean,
        val url: String = "",
        val host: String = "",
        val port: Int = 0,
        val token: String = ""
    )

    private val active = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private var state: State = State(running = false)

    @Synchronized
    fun start(configuredToken: String? = null): State {
        if (active.get()) return state

        val socket = runCatching { ServerSocket(preferredPort) }
            .getOrElse { ServerSocket(0) }
        socket.reuseAddress = true

        val host = localIpAddress()
        val token = configuredToken?.trim()?.takeIf { it.isNotBlank() } ?: newToken()
        val port = socket.localPort
        val url = settingsUrl(host, port, token)

        serverSocket = socket
        executor = Executors.newCachedThreadPool()
        state = State(running = true, url = url, host = host, port = port, token = token)
        active.set(true)

        executor?.execute {
            while (active.get()) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                executor?.execute { handleClient(client) }
            }
        }

        return state
    }

    @Synchronized
    fun setToken(token: String): State {
        val nextToken = token.trim().ifBlank { newToken() }
        val current = state
        state = if (current.running) {
            current.copy(
                token = nextToken,
                url = settingsUrl(current.host, current.port, nextToken)
            )
        } else {
            State(running = false, token = nextToken)
        }
        return state
    }

    @Synchronized
    fun stop(): State {
        active.set(false)
        runCatching { serverSocket?.close() }
        executor?.shutdownNow()
        serverSocket = null
        executor = null
        state = State(running = false)
        return state
    }

    fun status(): State = state

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8))
            val request = readRequest(reader) ?: return@use
            val response = runCatching { route(request) }
                .getOrElse { jsonResponse(500, errorJson(it.message ?: "요청을 처리하지 못했습니다.")) }
            writer.write(response)
            writer.flush()
        }
    }

    private fun readRequest(reader: BufferedReader): HttpRequest? {
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null

        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: return null
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val chars = CharArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                val count = reader.read(chars, offset, contentLength - offset)
                if (count <= 0) break
                offset += count
            }
            String(chars, 0, offset)
        } else {
            ""
        }

        val uri = java.net.URI("http://localhost${parts[1]}")
        return HttpRequest(
            method = parts[0].uppercase(),
            path = uri.path.ifBlank { "/" },
            query = parseQuery(uri.rawQuery.orEmpty()),
            headers = headers,
            body = body
        )
    }

    private fun route(request: HttpRequest): String {
        if (request.path == "/" && request.method == "GET") {
            return if (isAuthorized(request)) {
                htmlResponse(adminHtml())
            } else {
                htmlResponse(UNAUTHORIZED_HTML)
            }
        }

        if (!isAuthorized(request)) {
            return jsonResponse(401, errorJson("인증 토큰이 올바르지 않습니다. 앱 설정 화면의 PC 설정 서버 URL로 접속하세요."))
        }

        if (!request.path.startsWith("/api/")) {
            return jsonResponse(404, errorJson("찾을 수 없는 경로입니다."))
        }

        return when {
            request.method == "GET" && request.path == "/api/app-info" ->
                jsonResponse(
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("data", JSONObject(handler.appInfo()))
                        .toString()
                )

            request.method == "GET" && request.path == "/api/permission" ->
                jsonResponse(
                    200,
                    JSONObject()
                        .put("ok", true)
                        .put("data", JSONObject().put("notificationListenerEnabled", handler.notificationPermission()))
                        .toString()
                )

            request.method == "GET" && request.path == "/api/rules" ->
                jsonResponse(200, handler.listRules())

            request.method == "GET" && request.path == "/api/logs" ->
                jsonResponse(200, handler.listLogs(request.query["limit"]?.toIntOrNull() ?: 200))

            request.method == "GET" && request.path == "/api/apps" ->
                jsonResponse(200, handler.listInstalledApps(request.query["includeSystem"] == "true"))

            request.method == "POST" && request.path == "/api/rules" ->
                jsonResponse(200, handler.createRule(request.body))

            request.method == "PUT" && request.path.matches(Regex("/api/rules/\\d+")) -> {
                val ruleId = request.path.substringAfterLast('/').toLong()
                val payload = JSONObject(request.body.ifBlank { "{}" }).put("id", ruleId)
                jsonResponse(200, handler.updateRule(payload.toString()))
            }

            request.method == "POST" && request.path.matches(Regex("/api/rules/\\d+/enabled")) -> {
                val ruleId = request.path.removeSuffix("/enabled").substringAfterLast('/').toLong()
                val enabled = JSONObject(request.body.ifBlank { "{}" }).optBoolean("enabled", true)
                jsonResponse(200, handler.setRuleEnabled(ruleId, enabled))
            }

            request.method == "DELETE" && request.path.matches(Regex("/api/rules/\\d+")) -> {
                val ruleId = request.path.substringAfterLast('/').toLong()
                jsonResponse(200, handler.deleteRule(ruleId))
            }

            else -> jsonResponse(404, errorJson("지원하지 않는 요청입니다."))
        }
    }

    private fun isAuthorized(request: HttpRequest): Boolean {
        val currentToken = state.token
        if (currentToken.isBlank()) return false
        if (request.query["token"] == currentToken) return true
        if (request.headers["x-notiflow-token"] == currentToken) return true
        return request.headers["authorization"] == "Bearer $currentToken"
    }

    private fun adminHtml(): String = ADMIN_HTML.replace("__TOKEN__", state.token)

    private fun settingsUrl(host: String, port: Int, token: String): String =
        "http://$host:$port/?token=$token"

    private fun htmlResponse(body: String): String = httpResponse(
        status = 200,
        contentType = "text/html; charset=utf-8",
        body = body
    )

    private fun jsonResponse(status: Int, body: String): String = httpResponse(
        status = status,
        contentType = "application/json; charset=utf-8",
        body = body
    )

    private fun httpResponse(status: Int, contentType: String, body: String): String {
        val reason = when (status) {
            200 -> "OK"
            401 -> "Unauthorized"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        return buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("\r\n")
            append(body)
        }
    }

    private fun errorJson(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()

    private fun parseQuery(rawQuery: String): Map<String, String> {
        if (rawQuery.isBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx < 0) return@mapNotNull null
            decode(part.substring(0, idx)) to decode(part.substring(idx + 1))
        }.toMap()
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun localIpAddress(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces().toList()
        for (networkInterface in interfaces) {
            val addresses = networkInterface.inetAddresses.toList()
            val address = addresses.filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            if (address != null) return address.hostAddress.orEmpty()
        }
        return "127.0.0.1"
    }

    private fun newToken(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String
    )

    private companion object {
        private val UNAUTHORIZED_HTML = """
<!doctype html>
<html lang="ko">
<head><meta charset="utf-8" /><meta name="viewport" content="width=device-width, initial-scale=1" /><title>NotiFlow PC 설정</title></head>
<body style="margin:0;font:15px system-ui;background:#07090f;color:#eef2ff;display:grid;min-height:100vh;place-items:center">
  <main style="max-width:420px;padding:24px;text-align:center">
    <h1>인증이 필요합니다</h1>
    <p style="color:#94a3b8;line-height:1.6">NotiFlow 앱 설정 화면에 표시된 PC 설정 서버 URL로 접속하세요.</p>
  </main>
</body>
</html>
        """.trimIndent()

        private val ADMIN_HTML = """
<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>NotiFlow PC 설정</title>
  <style>
    :root { color-scheme: light dark; --bg:#f6f8fb; --card:#fff; --line:#dbe3ef; --text:#0f172a; --muted:#64748b; --accent:#4f46e5; --bad:#dc2626; --ok:#047857; }
    @media (prefers-color-scheme: dark) { :root { --bg:#07090f; --card:#111827; --line:#273244; --text:#eef2ff; --muted:#94a3b8; --accent:#818cf8; --bad:#fb7185; --ok:#34d399; } }
    * { box-sizing: border-box; }
    body { margin:0; background:var(--bg); color:var(--text); font:14px/1.5 system-ui,-apple-system,Segoe UI,sans-serif; }
    header { position:sticky; top:0; z-index:2; border-bottom:1px solid var(--line); background:color-mix(in srgb,var(--bg) 90%,transparent); backdrop-filter:blur(16px); }
    .wrap { max-width:1120px; margin:0 auto; padding:18px; }
    h1 { margin:0; font-size:22px; }
    h2 { margin:0 0 10px; font-size:16px; }
    .muted { color:var(--muted); }
    .grid { display:grid; grid-template-columns:minmax(0,1.1fr) minmax(340px,.9fr); gap:16px; align-items:start; }
    .card { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:16px; }
    .row { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:10px 0; border-bottom:1px solid var(--line); }
    .row:last-child { border-bottom:0; }
    button { border:0; border-radius:8px; padding:9px 12px; font-weight:700; cursor:pointer; color:#fff; background:var(--accent); }
    button.ghost { color:var(--text); background:color-mix(in srgb,var(--muted) 14%,transparent); }
    button.bad { background:var(--bad); }
    button.ok { background:var(--ok); }
    input, select, textarea { width:100%; border:1px solid var(--line); border-radius:8px; padding:9px 10px; background:var(--bg); color:var(--text); font:inherit; }
    textarea { min-height:84px; resize:vertical; font-family:ui-monospace,SFMono-Regular,Consolas,monospace; font-size:12px; }
    label { display:grid; gap:5px; color:var(--muted); font-size:12px; font-weight:700; }
    .form { display:grid; gap:10px; }
    .cols { display:grid; grid-template-columns:1fr 1fr; gap:10px; }
    .actions { display:flex; gap:8px; flex-wrap:wrap; }
    .rule-title { font-weight:800; }
    .rule-meta { color:var(--muted); font-size:12px; overflow-wrap:anywhere; }
    .status { margin-top:5px; color:var(--muted); font-size:13px; }
    .pill { display:inline-flex; border-radius:999px; padding:2px 8px; font-size:12px; font-weight:800; background:color-mix(in srgb,var(--accent) 15%,transparent); color:var(--accent); }
    .pill.off { background:color-mix(in srgb,var(--muted) 18%,transparent); color:var(--muted); }
    @media (max-width:860px) { .grid { grid-template-columns:1fr; } }
  </style>
</head>
<body>
  <header>
    <div class="wrap">
      <h1>NotiFlow PC 설정</h1>
      <div class="status" id="status">연결 중...</div>
    </div>
  </header>

  <main class="wrap grid">
    <section class="card">
      <div class="actions" style="justify-content:space-between;margin-bottom:12px">
        <h2>규칙</h2>
        <button class="ghost" onclick="reloadAll()">새로고침</button>
      </div>
      <div id="rules"></div>
    </section>

    <aside class="card">
      <h2 id="formTitle">새 규칙</h2>
      <form class="form" onsubmit="saveRule(event)">
        <input type="hidden" id="ruleId" />
        <label>대상 앱
          <input id="packageName" list="apps" placeholder="com.example.app" required />
          <datalist id="apps"></datalist>
        </label>
        <label>규칙 이름
          <input id="ruleName" placeholder="비워두면 자동 생성" />
        </label>
        <div class="cols">
          <label>활성화
            <select id="enabled"><option value="true">활성</option><option value="false">일시 정지</option></select>
          </label>
          <label>우선순위
            <input id="priority" type="number" value="100" />
          </label>
        </div>
        <label>조건 행 JSON
          <textarea id="conditionRows" required></textarea>
        </label>
        <div class="cols">
          <label>Webhook Method
            <select id="webhookMethod"><option>POST</option><option>PUT</option><option>PATCH</option></select>
          </label>
          <label>Webhook URL
            <input id="webhookUrl" placeholder="https://hooks.example.com/..." required />
          </label>
        </div>
        <label>헤더
          <textarea id="headersRaw" placeholder="X-App: NotiFlow"></textarea>
        </label>
        <label>페이로드 템플릿
          <textarea id="payloadTemplate" placeholder='{"title":{{title}},"text":{{text}}}'></textarea>
        </label>
        <label>토큰
          <input id="tokenValue" type="password" placeholder="수정 시 비워두면 기존 값 유지" />
        </label>
        <div class="actions">
          <button type="submit">저장</button>
          <button type="button" class="ghost" onclick="resetForm()">초기화</button>
        </div>
      </form>
    </aside>

    <section class="card">
      <h2>앱 정보</h2>
      <div id="appInfo" class="muted">불러오는 중...</div>
    </section>

    <section class="card">
      <h2>최근 로그</h2>
      <div id="logs" class="muted">불러오는 중...</div>
    </section>
  </main>

  <script>
    const TOKEN = "__TOKEN__";
    const api = async (path, options) => {
      const res = await fetch(path + (path.includes("?") ? "&" : "?") + "token=" + encodeURIComponent(TOKEN), {
        ...options,
        headers: { "Content-Type": "application/json", "X-NotiFlow-Token": TOKEN, ...(options && options.headers || {}) }
      });
      const json = await res.json();
      if (!json.ok) throw new Error(json.error || "요청 실패");
      return json.data || {};
    };
    let rules = [];

    const webhookOf = (rule) => (rule.actions || []).find((action) => action.type === "webhook.post")?.config || {};
    const rowsOf = (rule) => rule.conditionExpression?.rows || (rule.conditions || []).map((condition, idx, arr) => ({
      type: condition.type,
      value: condition.value,
      operator: idx < arr.length - 1 ? (rule.conditionOperator || "AND") : null
    }));
    const parseHeaders = (raw) => Object.fromEntries(String(raw || "").split(/\r?\n/).map((line) => line.trim()).filter(Boolean).map((line) => {
      const idx = line.indexOf(":");
      if (idx <= 0) throw new Error("헤더 형식이 올바르지 않습니다: " + line);
      return [line.slice(0, idx).trim(), line.slice(idx + 1).trim()];
    }));
    const headersText = (headers) => Object.entries(headers || {}).map(([key, value]) => key + ": " + value).join("\n");

    async function reloadAll() {
      document.getElementById("status").textContent = "동기화 중...";
      const [appInfo, permission, rulesData, logsData] = await Promise.all([
        api("/api/app-info"),
        api("/api/permission"),
        api("/api/rules"),
        api("/api/logs?limit=50")
      ]);
      rules = rulesData.rules || [];
      renderAppInfo(appInfo, permission);
      renderRules();
      renderLogs(logsData.logs || []);
      loadApps();
      document.getElementById("status").textContent = "연결됨";
    }

    async function loadApps() {
      try {
        const data = await api("/api/apps?includeSystem=false");
        document.getElementById("apps").innerHTML = (data.apps || []).map((app) =>
          '<option value="' + escapeHtml(app.packageName) + '">' + escapeHtml(app.appLabel) + '</option>'
        ).join("");
      } catch (error) {
        console.warn(error);
      }
    }

    function renderAppInfo(info, permission) {
      document.getElementById("appInfo").innerHTML =
        "앱: " + escapeHtml(info.appName || "NotiFlow") + "<br>" +
        "버전: " + escapeHtml(info.versionName || "") + " (" + escapeHtml(String(info.versionCode || "")) + ")<br>" +
        "패키지: " + escapeHtml(info.packageName || "") + "<br>" +
        "알림 접근 권한: " + (permission.notificationListenerEnabled ? "허용됨" : "필요");
    }

    function renderRules() {
      const box = document.getElementById("rules");
      if (!rules.length) {
        box.innerHTML = '<div class="muted">아직 규칙이 없습니다.</div>';
        return;
      }
      box.innerHTML = rules.map((rule) => {
        const pkg = (rule.targetPackages || [])[0] || "";
        const rows = rowsOf(rule);
        return '<div class="row">' +
          '<div><div class="rule-title">' + escapeHtml(rule.name || ("규칙 #" + rule.id)) + ' <span class="pill ' + (rule.enabled ? "" : "off") + '">' + (rule.enabled ? "활성" : "정지") + '</span></div>' +
          '<div class="rule-meta">' + escapeHtml(pkg) + ' · 조건 ' + rows.length + '개 · 우선순위 ' + rule.priority + '</div></div>' +
          '<div class="actions">' +
          '<button class="ghost" onclick="editRule(' + rule.id + ')">수정</button>' +
          '<button class="' + (rule.enabled ? "ghost" : "ok") + '" onclick="toggleRule(' + rule.id + ',' + (!rule.enabled) + ')">' + (rule.enabled ? "정지" : "활성") + '</button>' +
          '<button class="bad" onclick="deleteRule(' + rule.id + ')">삭제</button>' +
          '</div></div>';
      }).join("");
    }

    function renderLogs(logs) {
      const box = document.getElementById("logs");
      if (!logs.length) {
        box.textContent = "실행 로그가 없습니다.";
        return;
      }
      box.innerHTML = logs.slice(0, 12).map((log) =>
        '<div class="row"><div><div class="rule-title">' + escapeHtml(log.result || "-") + '</div>' +
        '<div class="rule-meta">#' + log.ruleId + ' · ' + escapeHtml(log.eventPackage || "") + '</div></div>' +
        '<div class="rule-meta">' + new Date(Number(log.executedAt)).toLocaleString() + '</div></div>'
      ).join("");
    }

    function resetForm() {
      document.getElementById("formTitle").textContent = "새 규칙";
      document.getElementById("ruleId").value = "";
      document.getElementById("packageName").value = "";
      document.getElementById("ruleName").value = "";
      document.getElementById("enabled").value = "true";
      document.getElementById("priority").value = "100";
      document.getElementById("conditionRows").value = JSON.stringify([{ type: "text.contains", value: "", operator: null }], null, 2);
      document.getElementById("webhookMethod").value = "POST";
      document.getElementById("webhookUrl").value = "";
      document.getElementById("headersRaw").value = "";
      document.getElementById("payloadTemplate").value = "";
      document.getElementById("tokenValue").value = "";
    }

    function editRule(id) {
      const rule = rules.find((item) => item.id === id);
      if (!rule) return;
      const webhook = webhookOf(rule);
      document.getElementById("formTitle").textContent = "규칙 #" + id + " 수정";
      document.getElementById("ruleId").value = id;
      document.getElementById("packageName").value = (rule.targetPackages || [])[0] || "";
      document.getElementById("ruleName").value = rule.name || "";
      document.getElementById("enabled").value = String(Boolean(rule.enabled));
      document.getElementById("priority").value = String(rule.priority || 100);
      document.getElementById("conditionRows").value = JSON.stringify(rowsOf(rule), null, 2);
      document.getElementById("webhookMethod").value = webhook.method || "POST";
      document.getElementById("webhookUrl").value = webhook.url || "";
      document.getElementById("headersRaw").value = headersText(webhook.headers);
      document.getElementById("payloadTemplate").value = webhook.payloadTemplate || "";
      document.getElementById("tokenValue").value = "";
      window.scrollTo({ top: 0, behavior: "smooth" });
    }

    async function saveRule(event) {
      event.preventDefault();
      const id = document.getElementById("ruleId").value;
      const token = document.getElementById("tokenValue").value.trim();
      const webhook = {
        url: document.getElementById("webhookUrl").value.trim(),
        method: document.getElementById("webhookMethod").value
      };
      const headers = parseHeaders(document.getElementById("headersRaw").value);
      if (Object.keys(headers).length) webhook.headers = headers;
      const payloadTemplate = document.getElementById("payloadTemplate").value.trim();
      if (payloadTemplate) webhook.payloadTemplate = payloadTemplate;
      if (token) webhook.token = token;

      const body = {
        name: document.getElementById("ruleName").value.trim(),
        packageName: document.getElementById("packageName").value.trim(),
        enabled: document.getElementById("enabled").value === "true",
        priority: Number(document.getElementById("priority").value || 100),
        conditionExpression: { rows: JSON.parse(document.getElementById("conditionRows").value) },
        webhook
      };

      if (id) await api("/api/rules/" + id, { method: "PUT", body: JSON.stringify(body) });
      else await api("/api/rules", { method: "POST", body: JSON.stringify(body) });
      resetForm();
      await reloadAll();
    }

    async function toggleRule(id, enabled) {
      await api("/api/rules/" + id + "/enabled", { method: "POST", body: JSON.stringify({ enabled }) });
      await reloadAll();
    }

    async function deleteRule(id) {
      if (!confirm("규칙 #" + id + "을 삭제할까요?")) return;
      await api("/api/rules/" + id, { method: "DELETE" });
      await reloadAll();
    }

    function escapeHtml(value) {
      return String(value ?? "").replace(/[&<>"']/g, (char) => ({ "&":"&amp;", "<":"&lt;", ">":"&gt;", '"':"&quot;", "'":"&#39;" }[char]));
    }

    resetForm();
    reloadAll().catch((error) => {
      document.getElementById("status").textContent = error.message;
    });
  </script>
</body>
</html>
        """.trimIndent()
    }
}
