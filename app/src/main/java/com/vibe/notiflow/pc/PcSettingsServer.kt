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
    .field { display:grid; gap:6px; }
    .field-lbl { color:var(--muted); font-size:12px; font-weight:800; text-transform:uppercase; letter-spacing:.4px; }
    .field-row { display:grid; grid-template-columns:1fr 1fr; gap:10px; }
    .pkg-picker-btn { width:100%; display:flex; flex-direction:column; align-items:flex-start; justify-content:center; gap:2px; min-height:58px; text-align:left; color:var(--text); background:var(--bg); border:1px solid var(--line); }
    .pkg-picker-label { font-size:14px; font-weight:800; }
    .pkg-picker-package { color:var(--muted); font-size:12px; font-family:ui-monospace,SFMono-Regular,Consolas,monospace; overflow-wrap:anywhere; }
    .placeholder { color:var(--muted); }
    .conds-box { display:grid; gap:10px; }
    .cond-help { color:var(--muted); font-size:12px; line-height:1.45; }
    .add-group-btn { justify-self:start; }
    .group-card { display:grid; gap:10px; border:1px solid var(--line); border-radius:8px; padding:10px; background:color-mix(in srgb,var(--muted) 7%,transparent); }
    .group-head { display:flex; align-items:center; justify-content:space-between; gap:10px; }
    .group-title { font-weight:800; }
    .group-del { color:var(--bad); background:color-mix(in srgb,var(--bad) 13%,transparent); }
    .group-conds { display:grid; gap:8px; }
    .cond-item { border:1px solid var(--line); border-radius:8px; padding:8px; background:var(--card); }
    .cond-row { display:grid; grid-template-columns:150px minmax(0,1fr) 34px 34px; gap:8px; align-items:center; }
    .cond-add, .cond-del { width:34px; height:34px; padding:0; display:grid; place-items:center; }
    .cond-del { color:var(--bad); background:color-mix(in srgb,var(--bad) 13%,transparent); }
    .group-op-row { display:flex; align-items:center; justify-content:space-between; gap:10px; }
    .group-op-row.between { padding-top:4px; border-top:1px dashed var(--line); }
    .cond-link-lbl { color:var(--muted); font-size:11px; font-weight:800; text-transform:uppercase; letter-spacing:.4px; }
    .seg { display:flex; overflow:hidden; border:1px solid var(--line); border-radius:8px; background:var(--bg); }
    .seg-opt { min-width:58px; color:var(--muted); background:transparent; border-radius:0; }
    .seg-opt.active { color:#fff; background:var(--accent); }
    .expr-preview, .diff-box { border:1px dashed var(--line); border-radius:8px; padding:10px; background:var(--bg); }
    .expr-preview-lbl, .diff-lbl { display:block; margin-bottom:6px; color:var(--muted); font-size:11px; font-weight:800; text-transform:uppercase; letter-spacing:.4px; }
    .expr-preview-code { color:var(--text); font-family:ui-monospace,SFMono-Regular,Consolas,monospace; font-size:12px; white-space:pre-wrap; overflow-wrap:anywhere; }
    .diff-line { color:var(--muted); font-size:12px; line-height:1.7; overflow-wrap:anywhere; }
    .token-check, .picker-toggle { display:flex; align-items:center; gap:8px; color:var(--muted); font-size:13px; font-weight:600; }
    .token-check input, .picker-toggle input { width:16px; height:16px; accent-color:var(--accent); }
    .picker-backdrop { position:fixed; inset:0; background:#0000008c; z-index:10; display:none; }
    .picker-backdrop.open { display:block; }
    .picker-dialog { position:fixed; left:50%; top:50%; width:min(560px,calc(100vw - 28px)); max-height:min(760px,calc(100vh - 32px)); transform:translate(-50%,-50%); z-index:11; display:none; flex-direction:column; background:var(--card); border:1px solid var(--line); border-radius:10px; overflow:hidden; box-shadow:0 18px 60px #00000066; }
    .picker-dialog.open { display:flex; }
    .picker-head, .picker-foot { display:flex; align-items:center; justify-content:space-between; gap:10px; padding:12px 14px; border-bottom:1px solid var(--line); }
    .picker-foot { border-top:1px solid var(--line); border-bottom:0; }
    .picker-body { display:grid; gap:10px; min-height:0; padding:14px; }
    .picker-list { min-height:240px; max-height:420px; overflow:auto; border:1px solid var(--line); border-radius:8px; background:var(--bg); }
    .picker-item { width:100%; display:flex; flex-direction:column; align-items:flex-start; gap:2px; padding:11px 12px; color:var(--text); background:transparent; border-bottom:1px solid var(--line); border-radius:0; text-align:left; }
    .picker-item:last-child { border-bottom:0; }
    .picker-item-label { font-weight:800; }
    .picker-item-package { color:var(--muted); font-size:12px; font-family:ui-monospace,SFMono-Regular,Consolas,monospace; }
    .picker-empty { padding:28px 12px; color:var(--muted); text-align:center; }
    @media (max-width:640px) { .field-row, .cond-row { grid-template-columns:1fr; } .cond-add, .cond-del { width:100%; } }
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
      <div id="ruleFormHost"></div>
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

  <div id="pickerBackdrop" class="picker-backdrop" onclick="closePackagePicker()"></div>
  <div id="pickerDialog" class="picker-dialog" role="dialog" aria-modal="true">
    <div class="picker-head">
      <h2 style="margin:0">대상 앱 선택</h2>
      <button class="ghost" onclick="closePackagePicker()">닫기</button>
    </div>
    <div class="picker-body">
      <label class="picker-toggle">
        <input id="includeSystemApps" type="checkbox" onchange="setIncludeSystemApps(this.checked)" />
        시스템 앱 포함
      </label>
      <input id="picker-query" placeholder="앱 이름 또는 패키지 검색" oninput="setPickerQuery(this.value)" />
      <div id="pickerCount" class="rule-meta"></div>
      <div id="pickerList" class="picker-list"></div>
    </div>
    <div class="picker-foot">
      <span class="rule-meta">설치된 앱 목록은 대상 앱 선택에만 사용됩니다.</span>
      <button class="ghost" onclick="closePackagePicker()">취소</button>
    </div>
  </div>

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
    let installedApps = [];
    let includeSystemApps = false;
    let pickerQuery = "";
    let form = emptyForm();
    let baseline = null;
    let nextId = 1;

    const webhookOf = (rule) => (rule.actions || []).find((action) => action.type === "webhook.post")?.config || {};
    const rowsOf = (rule) => rule.conditionExpression?.rows || (rule.conditions || []).map((condition, idx, arr) => ({
      type: condition.type,
      value: condition.value,
      operator: idx < arr.length - 1 ? (rule.conditionOperator || "AND") : null
    }));
    const normLogicOp = (value, fallback) => {
      const op = String(value || fallback || "AND").toUpperCase();
      return op === "OR" ? "OR" : "AND";
    };
    const condId = () => "cond-" + nextId++;
    const groupId = () => "group-" + nextId++;
    const blankCond = () => ({ id: condId(), type: "text.contains", value: "" });
    const blankGroup = (intraOp, nextGroupOp) => ({
      id: groupId(),
      intraOp: normLogicOp(intraOp, "AND"),
      nextGroupOp: nextGroupOp == null ? null : normLogicOp(nextGroupOp, "AND"),
      conditions: [blankCond()]
    });
    function emptyForm() {
      return {
        id: null,
        name: "",
        packageName: "",
        groups: [blankGroup()],
        webhookUrl: "",
        webhookMethod: "POST",
        headersRaw: "",
        payloadTemplate: "",
        token: "",
        removeToken: false,
        hasTokenRef: false,
        enabled: true,
        priority: 100
      };
    }
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
      loadApps(includeSystemApps);
      document.getElementById("status").textContent = "연결됨";
    }

    async function loadApps(nextIncludeSystem) {
      try {
        const data = await api("/api/apps?includeSystem=" + String(Boolean(nextIncludeSystem)));
        installedApps = data.apps || [];
        renderPackagePicker();
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
      form = emptyForm();
      baseline = null;
      renderRuleForm();
    }

    function editRule(id) {
      const rule = rules.find((item) => item.id === id);
      if (!rule) return;
      const webhook = webhookOf(rule);
      form = {
        id: Number(rule.id),
        name: String(rule.name || ""),
        packageName: String((rule.targetPackages || [])[0] || ""),
        groups: toEditableGroups(rule),
        webhookUrl: String(webhook.url || ""),
        webhookMethod: String(webhook.method || "POST"),
        headersRaw: headersText(webhook.headers),
        payloadTemplate: String(webhook.payloadTemplate || ""),
        token: "",
        removeToken: false,
        hasTokenRef: Boolean(webhook.hasTokenRef || webhook.tokenRef),
        enabled: Boolean(rule.enabled),
        priority: Number(rule.priority || 100)
      };
      baseline = ruleToNorm(rule);
      renderRuleForm();
      window.scrollTo({ top: 0, behavior: "smooth" });
    }

    function renderRuleForm() {
      const isEdit = form.id !== null;
      document.getElementById("formTitle").textContent = isEdit ? "규칙 #" + form.id + " 수정" : "새 규칙";
      const selectedApp = installedApps.find((app) => app.packageName === form.packageName);
      const selectedAppLabel = selectedApp ? selectedApp.appLabel : "";
      document.getElementById("ruleFormHost").innerHTML =
        '<form class="form" onsubmit="saveRule(event)">' +
          '<div class="field"><label class="field-lbl">대상 앱</label>' +
            '<button type="button" class="pkg-picker-btn" onclick="openPackagePicker()">' +
              '<span class="pkg-picker-label ' + (selectedAppLabel ? "" : "placeholder") + '">' + escapeHtml(selectedAppLabel || "설치된 앱 목록에서 선택") + '</span>' +
              '<span class="pkg-picker-package ' + (form.packageName ? "" : "placeholder") + '">' + escapeHtml(form.packageName || "클릭해서 찾아보기") + '</span>' +
            '</button></div>' +
          '<div class="field"><label class="field-lbl">규칙 이름 <span class="placeholder">(선택)</span></label>' +
            '<input placeholder="비워두면 자동 생성" value="' + escapeHtml(form.name) + '" oninput="setField(\'name\', this.value, false)" /></div>' +
          '<div class="field"><label class="field-lbl">조건</label><div class="conds-box">' +
            '<div class="cond-help">그룹을 추가한 뒤 각 그룹 안에 조건을 넣으세요. 괄호는 자동으로 생성됩니다.</div>' +
            '<button type="button" class="add-group-btn" onclick="addGroup()">+ 그룹 추가</button>' +
            renderGroups() +
            '<div class="expr-preview"><span class="expr-preview-lbl">조건식</span><code id="exprPreview" class="expr-preview-code"></code></div>' +
          '</div></div>' +
          '<div class="field"><label class="field-lbl">Webhook URL</label>' +
            '<input placeholder="https://hooks.example.com/..." value="' + escapeHtml(form.webhookUrl) + '" oninput="setField(\'webhookUrl\', this.value, false)" /></div>' +
          '<div class="field-row">' +
            '<div class="field"><label class="field-lbl">메서드</label><select onchange="setField(\'webhookMethod\', this.value, false)">' +
              optionHtml("POST", form.webhookMethod) + optionHtml("PUT", form.webhookMethod) + optionHtml("PATCH", form.webhookMethod) +
            '</select></div>' +
            '<div class="field"><label class="field-lbl">우선순위</label><input type="number" value="' + escapeHtml(String(form.priority)) + '" oninput="setField(\'priority\', Number(this.value || 100), false)" /></div>' +
          '</div>' +
          '<div class="field-row">' +
            '<div class="field"><label class="field-lbl">활성화</label><select onchange="setField(\'enabled\', this.value === \'true\', false)">' +
              '<option value="true" ' + (form.enabled ? "selected" : "") + '>활성</option>' +
              '<option value="false" ' + (!form.enabled ? "selected" : "") + '>일시 정지</option>' +
            '</select></div>' +
          '</div>' +
          '<div class="field"><label class="field-lbl">헤더 <span class="placeholder">(한 줄에 Key: Value)</span></label>' +
            '<textarea rows="3" placeholder="X-App: NotiFlow&#10;Authorization: Bearer ..." oninput="setField(\'headersRaw\', this.value, false)">' + escapeHtml(form.headersRaw) + '</textarea></div>' +
          '<div class="field"><label class="field-lbl">페이로드 템플릿 <span class="placeholder">(선택)</span></label>' +
            '<textarea rows="3" placeholder="{&quot;title&quot;:{{title}},&quot;text&quot;:{{text}}}" oninput="setField(\'payloadTemplate\', this.value, false)">' + escapeHtml(form.payloadTemplate) + '</textarea></div>' +
          '<div class="field"><label class="field-lbl">토큰 ' + (isEdit ? "(비워두면 기존 값 유지)" : "(선택)") + '</label>' +
            '<input type="password" placeholder="Bearer 비밀 토큰" value="' + escapeHtml(form.token) + '" oninput="setField(\'token\', this.value, false)" />' +
            (isEdit && form.hasTokenRef ? '<label class="token-check"><input type="checkbox" ' + (form.removeToken ? "checked" : "") + ' onchange="setField(\'removeToken\', this.checked, false)" /> 저장된 토큰 삭제</label>' : '') +
          '</div>' +
          '<div id="diffHost"></div>' +
          '<div class="actions"><button type="submit">' + (isEdit ? "변경사항 저장" : "규칙 만들기") + '</button><button type="button" class="ghost" onclick="resetForm()">초기화</button></div>' +
        '</form>';
      renderExpressionPreview();
      renderDiffBox();
    }

    function renderGroups() {
      return form.groups.map((group, groupIdx) => {
        const canDeleteGroup = form.groups.length > 1;
        const isLastGroup = groupIdx === form.groups.length - 1;
        return '<div class="group-card">' +
          '<div class="group-head"><div class="group-title">그룹 ' + (groupIdx + 1) + '</div>' +
          '<button type="button" class="group-del" onclick="removeGroup(\'' + group.id + '\')" ' + (canDeleteGroup ? "" : "disabled") + '>삭제</button></div>' +
          '<div class="group-conds">' + group.conditions.map((cond, condIdx) =>
            '<div class="cond-item"><div class="cond-row">' +
              '<select onchange="setGroupCond(\'' + group.id + '\',\'' + cond.id + '\',\'type\',this.value)">' +
                optionHtml("text.contains", cond.type, "본문 포함") +
                optionHtml("title.contains", cond.type, "제목 포함") +
                optionHtml("text.regex", cond.type, "본문 정규식") +
              '</select>' +
              '<input placeholder="값" value="' + escapeHtml(cond.value) + '" oninput="setGroupCond(\'' + group.id + '\',\'' + cond.id + '\',\'value\',this.value)" />' +
              '<button type="button" class="cond-add" onclick="addCondInGroup(\'' + group.id + '\',' + condIdx + ')" title="이 그룹에 조건 추가">+</button>' +
              '<button type="button" class="cond-del" onclick="removeCondInGroup(\'' + group.id + '\',\'' + cond.id + '\')" title="조건 삭제">×</button>' +
            '</div></div>'
          ).join("") + '</div>' +
          (group.conditions.length > 1 ? operatorRow("그룹 내부", group.intraOp, "setGroupField('" + group.id + "','intraOp',") : "") +
          (!isLastGroup ? operatorRow("다음 그룹", group.nextGroupOp || "AND", "setGroupField('" + group.id + "','nextGroupOp',", " between") : "") +
        '</div>';
      }).join("");
    }

    function operatorRow(label, selected, callPrefix, extraClass) {
      return '<div class="group-op-row' + (extraClass || "") + '"><span class="cond-link-lbl">' + label + '</span><div class="seg">' +
        ["AND", "OR"].map((op) => '<button type="button" class="seg-opt ' + (normLogicOp(selected) === op ? "active" : "") + '" onclick="' + callPrefix + '\'' + op + '\')">' + op + '</button>').join("") +
      '</div></div>';
    }

    function optionHtml(value, selected, label) {
      return '<option value="' + escapeHtml(value) + '" ' + (String(selected) === value ? "selected" : "") + '>' + escapeHtml(label || value) + '</option>';
    }

    function setField(name, value, rerender) {
      form[name] = value;
      if (rerender) renderRuleForm();
      else renderDiffBox();
    }

    function setGroupField(groupIdValue, field, value) {
      form.groups = form.groups.map((group) => group.id === groupIdValue ? { ...group, [field]: normLogicOp(value) } : group);
      renderRuleForm();
    }

    function setGroupCond(groupIdValue, condIdValue, field, value) {
      form.groups = form.groups.map((group) => group.id !== groupIdValue ? group : {
        ...group,
        conditions: group.conditions.map((cond) => cond.id === condIdValue ? { ...cond, [field]: value } : cond)
      });
      renderExpressionPreview();
      renderDiffBox();
    }

    function addGroup() {
      const next = form.groups.slice();
      if (next.length) next[next.length - 1] = { ...next[next.length - 1], nextGroupOp: normLogicOp(next[next.length - 1].nextGroupOp, "OR") };
      next.push(blankGroup("AND", null));
      form.groups = next;
      renderRuleForm();
    }

    function removeGroup(groupIdValue) {
      const next = form.groups.filter((group) => group.id !== groupIdValue);
      form.groups = next.length ? next : [blankGroup()];
      form.groups[form.groups.length - 1].nextGroupOp = null;
      renderRuleForm();
    }

    function addCondInGroup(groupIdValue, afterIdx) {
      form.groups = form.groups.map((group) => {
        if (group.id !== groupIdValue) return group;
        const nextConds = group.conditions.slice();
        nextConds.splice(afterIdx + 1, 0, blankCond());
        return { ...group, conditions: nextConds };
      });
      renderRuleForm();
    }

    function removeCondInGroup(groupIdValue, condIdValue) {
      form.groups = form.groups.map((group) => {
        if (group.id !== groupIdValue) return group;
        const nextConds = group.conditions.filter((cond) => cond.id !== condIdValue);
        return { ...group, conditions: nextConds.length ? nextConds : [blankCond()] };
      });
      renderRuleForm();
    }

    function expressionRowsForSubmit(groups) {
      const rows = [];
      for (let groupIdx = 0; groupIdx < groups.length; groupIdx++) {
        const group = groups[groupIdx];
        const conditions = group.conditions.map((cond) => ({
          type: String(cond.type || "text.contains"),
          value: String(cond.value || "").trim()
        })).filter((cond) => cond.value);
        if (!conditions.length) return { ok: false, error: "조건 값을 입력하세요." };
        for (let condIdx = 0; condIdx < conditions.length; condIdx++) {
          const isLastCond = condIdx === conditions.length - 1;
          const isLastGroup = groupIdx === groups.length - 1;
          const row = {
            type: conditions[condIdx].type,
            value: conditions[condIdx].value,
            openParen: condIdx === 0 && conditions.length > 1 ? 1 : 0,
            closeParen: isLastCond && conditions.length > 1 ? 1 : 0
          };
          if (!isLastCond) row.operator = normLogicOp(group.intraOp, "AND");
          else if (!isLastGroup) row.operator = normLogicOp(group.nextGroupOp, "AND");
          rows.push(row);
        }
      }
      return { ok: true, rows };
    }

    function renderExpressionPreview() {
      const target = document.getElementById("exprPreview");
      if (!target) return;
      const result = expressionRowsForSubmit(form.groups);
      target.textContent = result.ok ? normConds(result.rows) : result.error;
    }

    function normConds(rows) {
      return (rows || []).map((row) => {
        const open = "(".repeat(Number(row.openParen || 0));
        const close = ")".repeat(Number(row.closeParen || 0));
        const op = row.operator ? " " + row.operator : "";
        return open + row.type + ":" + row.value + close + op;
      }).join(" ");
    }

    function toEditableGroups(rule) {
      const rows = rowsOf(rule);
      if (!rows.length) return [blankGroup()];
      const groups = [];
      let current = null;
      let currentHasParen = false;
      let currentIntraOp = "AND";
      rows.forEach((row, idx) => {
        if (!current) {
          current = blankGroup();
          current.conditions = [];
          currentHasParen = Number(row.openParen || 0) > 0;
          currentIntraOp = "AND";
        }
        if (current.conditions.length > 0) currentIntraOp = normLogicOp(rows[idx - 1]?.operator, "AND");
        current.conditions.push({ id: condId(), type: String(row.type || "text.contains"), value: String(row.value || "") });
        const isLast = idx === rows.length - 1;
        const next = rows[idx + 1];
        const closesGroup = isLast || Number(row.closeParen || 0) > 0 || (!currentHasParen && rows.length > 1) || Number(next?.openParen || 0) > 0;
        if (closesGroup) {
          current.intraOp = currentIntraOp;
          current.nextGroupOp = isLast ? null : normLogicOp(row.operator, "AND");
          groups.push(current);
          current = null;
        }
      });
      return groups.length ? groups : [blankGroup()];
    }

    function ruleToNorm(rule) {
      const webhook = webhookOf(rule);
      return {
        packageName: String((rule.targetPackages || [])[0] || ""),
        name: String(rule.name || ""),
        conditions: normConds(rowsOf(rule)),
        webhookUrl: String(webhook.url || ""),
        webhookMethod: String(webhook.method || "POST"),
        headersRaw: headersText(webhook.headers),
        payloadTemplate: String(webhook.payloadTemplate || ""),
        enabled: Boolean(rule.enabled),
        priority: Number(rule.priority || 100)
      };
    }

    function formToNorm() {
      const result = expressionRowsForSubmit(form.groups);
      return {
        packageName: form.packageName,
        name: form.name,
        conditions: result.ok ? normConds(result.rows) : "",
        webhookUrl: form.webhookUrl,
        webhookMethod: form.webhookMethod,
        headersRaw: form.headersRaw,
        payloadTemplate: form.payloadTemplate,
        enabled: form.enabled,
        priority: Number(form.priority || 100)
      };
    }

    function renderDiffBox() {
      const host = document.getElementById("diffHost");
      if (!host || !baseline || form.id === null) return;
      const current = formToNorm();
      const labels = {
        packageName: "대상 앱",
        name: "규칙 이름",
        conditions: "조건",
        webhookUrl: "Webhook URL",
        webhookMethod: "메서드",
        headersRaw: "헤더",
        payloadTemplate: "페이로드",
        enabled: "활성화",
        priority: "우선순위"
      };
      const diffs = Object.keys(labels).filter((key) => String(baseline[key] ?? "") !== String(current[key] ?? ""));
      host.innerHTML = diffs.length ? '<div class="diff-box"><div class="diff-lbl">변경사항</div>' + diffs.map((key) =>
        '<div class="diff-line"><strong>' + labels[key] + '</strong>: ' + escapeHtml(String(baseline[key] ?? "")) + ' → ' + escapeHtml(String(current[key] ?? "")) + '</div>'
      ).join("") + '</div>' : "";
    }

    function openPackagePicker() {
      pickerQuery = "";
      document.getElementById("picker-query").value = "";
      document.getElementById("includeSystemApps").checked = includeSystemApps;
      document.getElementById("pickerBackdrop").classList.add("open");
      document.getElementById("pickerDialog").classList.add("open");
      loadApps(includeSystemApps);
    }

    function closePackagePicker() {
      document.getElementById("pickerBackdrop").classList.remove("open");
      document.getElementById("pickerDialog").classList.remove("open");
    }

    function setPickerQuery(value) {
      pickerQuery = value;
      renderPackagePicker();
    }

    async function setIncludeSystemApps(checked) {
      includeSystemApps = checked;
      await loadApps(includeSystemApps);
    }

    function renderPackagePicker() {
      const list = document.getElementById("pickerList");
      const count = document.getElementById("pickerCount");
      if (!list || !count) return;
      const query = pickerQuery.trim().toLowerCase();
      const filtered = query ? installedApps.filter((app) =>
        String(app.appLabel || "").toLowerCase().includes(query) ||
        String(app.packageName || "").toLowerCase().includes(query)
      ) : installedApps;
      count.textContent = filtered.length + "개 / 전체 " + installedApps.length + "개";
      list.innerHTML = filtered.length ? filtered.map((app) =>
        '<button type="button" class="picker-item" onclick="selectPackage(\'' + escapeJs(app.packageName) + '\')">' +
          '<span class="picker-item-label">' + escapeHtml(app.appLabel || app.packageName) + '</span>' +
          '<span class="picker-item-package">' + escapeHtml(app.packageName) + '</span>' +
        '</button>'
      ).join("") : '<div class="picker-empty">설치된 앱을 찾을 수 없습니다.</div>';
    }

    function selectPackage(packageName) {
      form.packageName = packageName;
      closePackagePicker();
      renderRuleForm();
    }

    async function saveRule(event) {
      event.preventDefault();
      const id = form.id;
      const rowsResult = expressionRowsForSubmit(form.groups);
      if (!rowsResult.ok) { alert(rowsResult.error); return; }
      const token = String(form.token || "").trim();
      const webhook = {
        url: String(form.webhookUrl || "").trim(),
        method: form.webhookMethod
      };
      const headers = parseHeaders(form.headersRaw);
      if (Object.keys(headers).length) webhook.headers = headers;
      const payloadTemplate = String(form.payloadTemplate || "").trim();
      if (payloadTemplate) webhook.payloadTemplate = payloadTemplate;
      if (id === null && token) webhook.token = token;
      if (id !== null) {
        if (form.removeToken) webhook.token = "";
        else if (token) webhook.token = token;
      }
      const validConds = rowsResult.rows.map((row) => ({ type: row.type, value: row.value }));
      const payloadRows = rowsResult.rows.map((row, idx, arr) => ({
        type: row.type,
        value: row.value,
        openParen: row.openParen,
        closeParen: row.closeParen,
        ...(idx < arr.length - 1 ? { operator: row.operator } : {})
      }));
      const legacyOperator = rowsResult.rows.length > 1 ? normLogicOp(rowsResult.rows[0].operator, "AND") : "AND";

      const body = {
        name: String(form.name || "").trim(),
        packageName: String(form.packageName || "").trim(),
        enabled: Boolean(form.enabled),
        priority: Number(form.priority || 100),
        conditionOperator: legacyOperator,
        conditions: validConds,
        conditionExpression: { rows: payloadRows },
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
    function escapeJs(value) {
      return String(value ?? "").replace(/\\/g, "\\\\").replace(/'/g, "\\'");
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
