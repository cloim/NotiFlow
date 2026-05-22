package com.vibe.notiflow

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.cloimism.notiflow.BuildConfig
import com.vibe.notiflow.di.ServiceLocator
import com.vibe.notiflow.domain.engine.ConditionExpressionEvaluator
import com.vibe.notiflow.domain.model.ActionSpec
import com.vibe.notiflow.domain.model.ConditionExpression
import com.vibe.notiflow.domain.model.ConditionExpressionRow
import com.vibe.notiflow.domain.model.FilterOperator
import com.vibe.notiflow.domain.model.FilterSpec
import com.vibe.notiflow.domain.model.Rule
import com.vibe.notiflow.pc.PcSettingsServer
import com.vibe.notiflow.update.GitHubReleaseUpdate
import com.vibe.notiflow.update.UpdateCandidate
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import androidx.webkit.WebViewAssetLoader
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private val updateHttpClient = OkHttpClient()
    private val bridge = NotiFlowBridge()
    private var pcSettingsServer: PcSettingsServer? = null
    private val pcSettingsPrefs by lazy {
        getSharedPreferences("pc_settings", Context.MODE_PRIVATE)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSystemBarsTheme(isLight = false)

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.cacheMode = if (BuildConfig.DEBUG) {
                WebSettings.LOAD_NO_CACHE
            } else {
                WebSettings.LOAD_DEFAULT
            }
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            if (BuildConfig.DEBUG) {
                clearCache(true)
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val uri = request?.url ?: return null
                    return assetLoader.shouldInterceptRequest(uri)
                }
            }
            webChromeClient = WebChromeClient()
            addJavascriptInterface(bridge, "NotiFlowNative")
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        setContentView(webView)
        loadConfiguredWebApp()
    }

    override fun onDestroy() {
        pcSettingsServer?.stop()
        webView.removeJavascriptInterface("NotiFlowNative")
        webView.destroy()
        super.onDestroy()
    }

    private fun loadConfiguredWebApp() {
        val configuredUrl = BuildConfig.WEB_APP_URL.trim()
        if (configuredUrl.isNotEmpty()) {
            webView.loadUrl(configuredUrl)
            return
        }

        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
    }

    @Suppress("DEPRECATION")
    private fun setSystemBarsTheme(isLight: Boolean) {
        val background = if (isLight) Color.rgb(246, 248, 251) else Color.rgb(7, 9, 15)
        window.statusBarColor = background
        window.navigationBarColor = background
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = isLight
            isAppearanceLightNavigationBars = isLight
        }
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val cn = ComponentName(
            packageName,
            "com.vibe.notiflow.notification.NotiFlowNotificationListenerService"
        )
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat?.contains(cn.flattenToString()) == true
    }

    private fun isValidWebhookUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return (uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()
    }

    private fun autoRuleName(packageName: String, conditions: List<Pair<String, String>>): String {
        val packageShortName = packageName.substringAfterLast('.')
        val conditionSummary = conditions.joinToString(" + ") { "${it.first}:${it.second}" }
        val summary = if (conditionSummary.length > 42) "${conditionSummary.take(42)}..." else conditionSummary
        return "$packageShortName - $summary"
    }

    private fun jsonValue(value: String): JsonObject = buildJsonObject { put("value", value) }

    private fun okResponse(payload: JSONObject = JSONObject()): String {
        return JSONObject().apply {
            put("ok", true)
            put("data", payload)
        }.toString()
    }

    private fun errorResponse(message: String): String {
        return JSONObject().apply {
            put("ok", false)
            put("error", message)
        }.toString()
    }

    private fun normalizeFilterOperator(raw: String): FilterOperator {
        return when (raw.trim().uppercase(Locale.US)) {
            "AND" -> FilterOperator.AND
            "OR" -> FilterOperator.OR
            else -> throw IllegalArgumentException("conditionOperator must be AND/OR")
        }
    }

    private fun validateCondition(type: String, value: String) {
        if (type !in setOf("title.contains", "text.contains", "text.regex")) {
            throw IllegalArgumentException("unsupported condition type: $type")
        }
        if (type == "text.regex" && runCatching { Regex(value) }.isFailure) {
            throw IllegalArgumentException("invalid regex pattern")
        }
    }

    private fun parseNonNegativeInt(
        obj: JSONObject,
        key: String,
        context: String
    ): Int {
        if (!obj.has(key)) return 0
        val raw = obj.opt(key)
        val value = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        } ?: throw IllegalArgumentException("$context.$key must be a non-negative integer")
        if (value < 0) throw IllegalArgumentException("$context.$key must be >= 0")
        return value
    }

    private fun normalizeConditions(input: JSONArray?): List<Pair<String, String>> {
        if (input == null) return emptyList()
        val conditions = mutableListOf<Pair<String, String>>()
        for (index in 0 until input.length()) {
            val condition = input.optJSONObject(index) ?: continue
            val type = condition.optString("type").trim()
            val value = condition.optString("value").trim()
            if (type.isBlank() || value.isBlank()) continue

            validateCondition(type, value)
            conditions += type to value
        }
        return conditions
    }

    private fun normalizeConditionExpression(input: JSONObject?): ConditionExpression? {
        if (input == null) return null
        val rowsArray = input.optJSONArray("rows")
            ?: throw IllegalArgumentException("conditionExpression.rows is required")
        val rows = mutableListOf<ConditionExpressionRow>()
        for (index in 0 until rowsArray.length()) {
            val row = rowsArray.optJSONObject(index)
                ?: throw IllegalArgumentException("conditionExpression.rows[$index] must be an object")
            val type = row.optString("type").trim()
            val value = row.optString("value").trim()
            if (type.isBlank() || value.isBlank()) {
                throw IllegalArgumentException("conditionExpression.rows[$index] requires type/value")
            }
            validateCondition(type, value)

            val operator = if (index < rowsArray.length() - 1) {
                if (!row.has("operator")) {
                    throw IllegalArgumentException("conditionExpression.rows[$index].operator is required")
                }
                normalizeFilterOperator(row.optString("operator"))
            } else if (row.has("operator") && !row.isNull("operator")) {
                normalizeFilterOperator(row.optString("operator"))
            } else {
                null
            }

            rows += ConditionExpressionRow(
                type = type,
                value = value,
                operator = operator,
                openParen = parseNonNegativeInt(row, "openParen", "conditionExpression.rows[$index]"),
                closeParen = parseNonNegativeInt(row, "closeParen", "conditionExpression.rows[$index]")
            )
        }
        val expression = ConditionExpression(rows = rows)
        ConditionExpressionEvaluator.validationError(expression)?.let { reason ->
            throw IllegalArgumentException("invalid conditionExpression: $reason")
        }
        return expression
    }

    private fun legacyExpression(
        conditions: List<Pair<String, String>>,
        operator: FilterOperator
    ): ConditionExpression {
        val rows = conditions.mapIndexed { index, (type, value) ->
            ConditionExpressionRow(
                type = type,
                value = value,
                operator = if (index < conditions.lastIndex) operator else null
            )
        }
        return ConditionExpression(rows)
    }

    private fun existingConditions(rule: Rule?): List<Pair<String, String>> {
        if (rule == null) return emptyList()
        return rule.filters
            .filter { it.type != "package.equals" }
            .mapNotNull { filter ->
                filter.config["value"]?.jsonPrimitive?.contentOrNull?.let { value ->
                    filter.type to value
                }
            }
    }

    private fun conditionsFromExpression(expression: ConditionExpression): List<Pair<String, String>> {
        return expression.rows.map { row -> row.type to row.value }
    }

    private fun existingWebhookConfig(rule: Rule?): JsonObject? {
        return rule?.actions?.firstOrNull { it.type == "webhook.post" }?.config
    }

    private fun existingHeaders(config: JsonObject?): Map<String, String> {
        if (config == null) return emptyMap()
        return config["headers"]?.jsonObject?.mapNotNull { (key, value) ->
            value.jsonPrimitive.contentOrNull?.let { key to it }
        }?.toMap().orEmpty()
    }

    private fun buildRuleFromInput(input: JSONObject, existing: Rule?): Rule {
        val existingConfig = existingWebhookConfig(existing)
        val webhook = input.optJSONObject("webhook")

        val packageName = input.optString("packageName").trim()
            .ifBlank { existing?.targetPackages?.firstOrNull().orEmpty() }
        if (packageName.isBlank()) throw IllegalArgumentException("packageName is required")

        val webhookUrl = (webhook?.optString("url") ?: input.optString("webhookUrl")).trim()
            .ifBlank { existingConfig?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty() }
        if (webhookUrl.isBlank()) throw IllegalArgumentException("webhook.url is required")
        if (!isValidWebhookUrl(webhookUrl)) throw IllegalArgumentException("webhook.url must be http/https")

        val method = (webhook?.optString("method") ?: input.optString("webhookMethod")).trim()
            .ifBlank { existingConfig?.get("method")?.jsonPrimitive?.contentOrNull.orEmpty() }
            .ifBlank { "POST" }
            .uppercase(Locale.US)
        if (method !in setOf("POST", "PUT", "PATCH")) {
            throw IllegalArgumentException("webhook.method must be POST/PUT/PATCH")
        }

        val headers = if (webhook?.has("headers") == true) {
            val headersObj = webhook.optJSONObject("headers")
            val parsed = linkedMapOf<String, String>()
            headersObj?.keys()?.forEach { key ->
                val value = headersObj.optString(key).trim()
                if (key.trim().isNotBlank() && value.isNotBlank()) {
                    parsed[key.trim()] = value
                }
            }
            parsed
        } else {
            existingHeaders(existingConfig)
        }

        val payloadTemplate = if (webhook?.has("payloadTemplate") == true) {
            webhook.optString("payloadTemplate").trim()
        } else {
            existingConfig?.get("payloadTemplate")?.jsonPrimitive?.contentOrNull.orEmpty()
        }

        val filterOperator = when {
            input.has("conditionOperator") -> normalizeFilterOperator(input.optString("conditionOperator"))
            existing != null -> existing.filterOperator
            else -> FilterOperator.AND
        }

        val inputExpression = if (input.has("conditionExpression")) {
            normalizeConditionExpression(input.optJSONObject("conditionExpression"))
        } else {
            null
        }
        val inputConditions = normalizeConditions(input.optJSONArray("conditions"))
        val conditionExpression = when {
            inputExpression != null -> inputExpression
            inputConditions.isNotEmpty() -> legacyExpression(inputConditions, filterOperator)
            existing?.conditionExpression != null -> existing.conditionExpression
            else -> {
                val fallback = existingConditions(existing)
                if (fallback.isEmpty()) null else legacyExpression(fallback, existing?.filterOperator ?: filterOperator)
            }
        } ?: throw IllegalArgumentException("at least one condition is required")
        val conditions = conditionsFromExpression(conditionExpression)

        val tokenInput = if (webhook?.has("token") == true) webhook.optString("token") else null
        val existingTokenRef = existingConfig?.get("tokenRef")?.jsonPrimitive?.contentOrNull
        val tokenRef = when {
            tokenInput == null -> existingTokenRef
            tokenInput.trim().isBlank() -> null
            else -> {
                val alias = "webhook_${UUID.randomUUID()}"
                ServiceLocator.secureStore.putSecret(alias, tokenInput.trim())
                alias
            }
        }

        val actionConfig = buildJsonObject {
            put("url", webhookUrl)
            put("method", method)
            if (payloadTemplate.isNotBlank()) put("payloadTemplate", payloadTemplate)
            if (headers.isNotEmpty()) {
                put(
                    "headers",
                    buildJsonObject {
                        headers.forEach { (key, value) -> put(key, value) }
                    }
                )
            }
            tokenRef?.let { put("tokenRef", it) }
        }

        val filters = buildList {
            add(FilterSpec(type = "package.equals", config = jsonValue(packageName)))
            conditions.forEach { (type, value) ->
                add(FilterSpec(type = type, config = jsonValue(value)))
            }
        }

        val rawName = when {
            input.has("name") -> input.optString("name").trim()
            existing != null -> existing.name
            else -> ""
        }
        val ruleName = rawName.ifBlank { autoRuleName(packageName, conditions) }

        val enabled = if (input.has("enabled")) input.optBoolean("enabled") else existing?.enabled ?: true
        val priority = if (input.has("priority")) input.optInt("priority", 100) else existing?.priority ?: 100

        return Rule(
            id = existing?.id ?: 0,
            name = ruleName,
            enabled = enabled,
            priority = priority,
            targetPackages = listOf(packageName),
            filters = filters,
            filterOperator = filterOperator,
            conditionExpression = conditionExpression,
            actions = listOf(ActionSpec("webhook.post", actionConfig))
        )
    }

    private suspend fun fetchLatestReleaseJson(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(GitHubReleaseUpdate.LATEST_RELEASE_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "NotiFlow/${BuildConfig.VERSION_NAME}")
            .build()

        updateHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("GitHub 릴리즈 확인 실패 (${response.code})")
            }
            response.body?.string() ?: throw IllegalStateException("GitHub 응답이 비어 있습니다.")
        }
    }

    private fun updateCandidateJson(candidate: UpdateCandidate): JSONObject {
        return JSONObject().apply {
            put("available", candidate.available)
            put("latestTag", candidate.latestTag)
            put("latestVersionName", candidate.latestVersionName)
            put("currentVersionName", BuildConfig.VERSION_NAME)
            put("currentVersionCode", BuildConfig.VERSION_CODE)
            put("releaseUrl", candidate.releaseUrl)
            put("assetName", candidate.assetName)
            put("downloadUrl", candidate.downloadUrl)
            put("assetSize", candidate.assetSize)
            put("flavor", if (isDevBuild()) "dev" else "prod")
        }
    }

    private fun isDevBuild(): Boolean = BuildConfig.APPLICATION_ID.endsWith(".dev")

    private fun canRequestApkInstall(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            packageManager.canRequestPackageInstalls()
    }

    private fun openUnknownAppInstallSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runOnUiThread {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private suspend fun downloadUpdateApk(downloadUrl: String, assetName: String): File =
        withContext(Dispatchers.IO) {
            val uri = URI(downloadUrl)
            require(uri.scheme == "https") { "APK 다운로드 URL은 HTTPS여야 합니다." }

            val safeAssetName = assetName
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "NotiFlow-update.apk" }
            val updateDir = File(cacheDir, "updates").apply { mkdirs() }
            val targetFile = File(updateDir, safeAssetName)

            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", "NotiFlow/${BuildConfig.VERSION_NAME}")
                .build()

            updateHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("APK 다운로드 실패 (${response.code})")
                }
                val body = response.body ?: throw IllegalStateException("APK 다운로드 응답이 비어 있습니다.")
                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            targetFile
        }

    private fun openApkInstaller(apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun pcSettingsServerHandler(): PcSettingsServer.Handler =
        object : PcSettingsServer.Handler {
            override fun appInfo(): String = bridge.getAppInfo()
            override fun notificationPermission(): Boolean = isNotificationListenerEnabled()
            override fun listRules(): String = bridge.listRules()
            override fun listLogs(limit: Int): String = bridge.listLogs(limit)
            override fun listInstalledApps(includeSystem: Boolean): String =
                bridge.listInstalledApps(includeSystem)
            override fun createRule(inputJson: String): String = bridge.createRule(inputJson)
            override fun updateRule(inputJson: String): String = bridge.updateRule(inputJson)
            override fun setRuleEnabled(ruleId: Long, enabled: Boolean): String =
                bridge.setRuleEnabled(ruleId, enabled)
            override fun deleteRule(ruleId: Long): String = bridge.deleteRule(ruleId)
        }

    private fun pcSettingsServerStateJson(state: PcSettingsServer.State): JSONObject =
        JSONObject().apply {
            put("running", state.running)
            put("url", state.url)
            put("host", state.host)
            put("port", state.port)
            put("token", state.token)
            put("configuredToken", getConfiguredPcSettingsToken())
        }

    private fun getPcSettingsServer(): PcSettingsServer =
        pcSettingsServer ?: PcSettingsServer(applicationContext, pcSettingsServerHandler()).also {
            pcSettingsServer = it
        }

    private fun getConfiguredPcSettingsToken(): String =
        pcSettingsPrefs.getString(PREF_PC_SETTINGS_TOKEN, "").orEmpty()

    private fun validatePcSettingsToken(token: String) {
        if (!Regex("^[A-Za-z0-9._~-]{6,64}$").matches(token)) {
            throw IllegalArgumentException("토큰은 영문, 숫자, . _ ~ - 조합으로 6~64자여야 합니다.")
        }
    }

    private fun savePcSettingsToken(token: String) {
        validatePcSettingsToken(token)
        pcSettingsPrefs.edit().putString(PREF_PC_SETTINGS_TOKEN, token).apply()
        pcSettingsServer?.setToken(token)
    }

    inner class NotiFlowBridge {
        @JavascriptInterface
        fun getAppInfo(): String {
            return JSONObject().apply {
                put("appName", "NotiFlow")
                put("versionName", BuildConfig.VERSION_NAME)
                put("versionCode", BuildConfig.VERSION_CODE)
                put("packageName", packageName)
                put("platform", "android")
            }.toString()
        }

        @JavascriptInterface
        fun setSystemBarsTheme(isLight: Boolean) {
            runOnUiThread {
                this@MainActivity.setSystemBarsTheme(isLight)
            }
        }

        @JavascriptInterface
        fun getPcSettingsServerStatus(): String {
            return okResponse(pcSettingsServerStateJson(pcSettingsServer?.status() ?: PcSettingsServer.State(false)))
        }

        @JavascriptInterface
        fun startPcSettingsServer(token: String): String {
            return runCatching {
                val trimmedToken = token.trim()
                if (trimmedToken.isNotBlank()) savePcSettingsToken(trimmedToken)
                okResponse(pcSettingsServerStateJson(getPcSettingsServer().start(getConfiguredPcSettingsToken())))
            }.getOrElse { errorResponse(it.message ?: "PC 설정 서버를 시작하지 못했습니다.") }
        }

        @JavascriptInterface
        fun stopPcSettingsServer(): String {
            return runCatching {
                okResponse(pcSettingsServerStateJson(pcSettingsServer?.stop() ?: PcSettingsServer.State(false)))
            }.getOrElse { errorResponse(it.message ?: "PC 설정 서버를 중지하지 못했습니다.") }
        }

        @JavascriptInterface
        fun setPcSettingsServerToken(token: String): String {
            return runCatching {
                savePcSettingsToken(token.trim())
                okResponse(pcSettingsServerStateJson(pcSettingsServer?.status() ?: PcSettingsServer.State(false)))
            }.getOrElse { errorResponse(it.message ?: "PC 설정 서버 토큰을 저장하지 못했습니다.") }
        }

        @JavascriptInterface
        fun checkForUpdate(): String {
            return runCatching {
                val releaseJson = runBlocking { fetchLatestReleaseJson() }
                val candidate = GitHubReleaseUpdate.parseLatestRelease(
                    json = releaseJson,
                    currentVersionName = BuildConfig.VERSION_NAME,
                    isDevBuild = isDevBuild()
                )
                okResponse(updateCandidateJson(candidate))
            }.getOrElse { errorResponse(it.message ?: "업데이트를 확인하지 못했습니다.") }
        }

        @JavascriptInterface
        fun installUpdate(downloadUrl: String, assetName: String): String {
            return runCatching {
                if (!canRequestApkInstall()) {
                    openUnknownAppInstallSettings()
                    return errorResponse("APK 설치 권한을 허용한 뒤 다시 시도하세요.")
                }

                val apkFile = runBlocking {
                    downloadUpdateApk(downloadUrl = downloadUrl, assetName = assetName)
                }
                runOnUiThread { openApkInstaller(apkFile) }
                okResponse(
                    JSONObject().apply {
                        put("assetName", apkFile.name)
                        put("bytes", apkFile.length())
                    }
                )
            }.getOrElse { errorResponse(it.message ?: "업데이트를 설치하지 못했습니다.") }
        }

        @JavascriptInterface
        fun openNotificationListenerSettings() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        @JavascriptInterface
        fun isNotificationListenerEnabled(): Boolean {
            return this@MainActivity.isNotificationListenerEnabled()
        }

        @JavascriptInterface
        fun listInstalledApps(includeSystem: Boolean): String {
            return runCatching {
                val apps = packageManager.getInstalledApplications(0)
                    .asSequence()
                    .filter { info ->
                        val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                            (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                        includeSystem || !isSystem
                    }
                    .map { info ->
                        val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                            (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                        val label = runCatching {
                            packageManager.getApplicationLabel(info).toString().trim()
                        }.getOrDefault("")
                        JSONObject().apply {
                            put("appLabel", label.ifBlank { info.packageName })
                            put("packageName", info.packageName)
                            put("isSystem", isSystem)
                        }
                    }
                    .sortedWith(compareBy<JSONObject> { it.optString("appLabel").lowercase(Locale.ROOT) }
                        .thenBy { it.optString("packageName").lowercase(Locale.ROOT) })
                    .toList()

                val items = JSONArray().apply { apps.forEach { put(it) } }
                okResponse(JSONObject().put("apps", items))
            }.getOrElse { errorResponse(it.message ?: "failed to list installed apps") }
        }

        @JavascriptInterface
        fun listInstalledApps(): String = listInstalledApps(includeSystem = true)

        @JavascriptInterface
        fun listRules(): String {
            return runCatching {
                val rules = runBlocking { ServiceLocator.ruleRepository.getAllRules() }
                val items = JSONArray().apply {
                    rules.forEach { rule ->
                        val expression = rule.conditionExpression ?: run {
                            val legacyConditions = existingConditions(rule)
                            if (legacyConditions.isEmpty()) null else legacyExpression(legacyConditions, rule.filterOperator)
                        }
                        val conditions = expression?.let(::conditionsFromExpression).orEmpty()
                        put(
                            JSONObject().apply {
                                put("id", rule.id)
                                put("name", rule.name)
                                put("enabled", rule.enabled)
                                put("priority", rule.priority)
                                put("targetPackages", JSONArray(rule.targetPackages))
                                put("filterOperator", rule.filterOperator.name)
                                put("conditionOperator", rule.filterOperator.name)
                                put(
                                    "conditions",
                                    JSONArray().apply {
                                        conditions.forEach { (type, value) ->
                                            put(
                                                JSONObject().apply {
                                                    put("type", type)
                                                    put("value", value)
                                                }
                                            )
                                        }
                                    }
                                )
                                expression?.let { put("conditionExpression", JSONObject(Json.encodeToString(it))) }
                                put(
                                    "filters",
                                    JSONArray().apply {
                                        rule.filters.forEach { filter ->
                                            put(
                                                JSONObject().apply {
                                                    put("type", filter.type)
                                                    put("config", JSONObject(filter.config.toString()))
                                                }
                                            )
                                        }
                                    }
                                )
                                put(
                                    "actions",
                                    JSONArray().apply {
                                        rule.actions.forEach { action ->
                                            put(
                                                JSONObject().apply {
                                                    put("type", action.type)
                                                    put("config", JSONObject(action.config.toString()))
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
                okResponse(JSONObject().put("rules", items))
            }.getOrElse { errorResponse(it.message ?: "failed to load rules") }
        }

        @JavascriptInterface
        fun listLogs(limit: Int): String {
            return runCatching {
                val safeLimit = limit.coerceIn(1, 500)
                val logs = runBlocking { ServiceLocator.ruleRepository.getRecentLogs(safeLimit) }
                val items = JSONArray().apply {
                    logs.forEach { log ->
                        put(
                            JSONObject().apply {
                                put("id", log.id)
                                put("ruleId", log.ruleId)
                                put("matched", log.matched)
                                put("result", log.result)
                                put("message", log.message)
                                put("executedAt", log.executedAt)
                                put("eventPackage", log.eventPackage)
                                put("eventTitle", log.eventTitle)
                            }
                        )
                    }
                }
                okResponse(JSONObject().put("logs", items))
            }.getOrElse { errorResponse(it.message ?: "failed to load logs") }
        }

        @JavascriptInterface
        fun setRuleEnabled(ruleId: Long, enabled: Boolean): String {
            return runCatching {
                runBlocking { ServiceLocator.ruleRepository.updateEnabled(ruleId, enabled) }
                okResponse(
                    JSONObject().apply {
                        put("ruleId", ruleId)
                        put("enabled", enabled)
                    }
                )
            }.getOrElse { errorResponse(it.message ?: "failed to update rule") }
        }

        @JavascriptInterface
        fun createRule(inputJson: String): String {
            return runCatching {
                val input = JSONObject(inputJson)
                val rule = buildRuleFromInput(input, existing = null)
                val ruleId = runBlocking { ServiceLocator.ruleRepository.upsertRule(rule) }
                okResponse(JSONObject().put("ruleId", ruleId))
            }.getOrElse { errorResponse(it.message ?: "failed to create rule") }
        }

        @JavascriptInterface
        fun updateRule(inputJson: String): String {
            return runCatching {
                val input = JSONObject(inputJson)
                val ruleId = input.optLong("id", 0)
                if (ruleId <= 0) return errorResponse("id is required")

                val existing = runBlocking { ServiceLocator.ruleRepository.getRuleById(ruleId) }
                    ?: return errorResponse("rule not found")

                val nextRule = buildRuleFromInput(input, existing).copy(id = ruleId)
                val updatedId = runBlocking { ServiceLocator.ruleRepository.upsertRule(nextRule) }
                okResponse(JSONObject().put("ruleId", updatedId))
            }.getOrElse { errorResponse(it.message ?: "failed to update rule") }
        }

        @JavascriptInterface
        fun deleteRule(ruleId: Long): String {
            return runCatching {
                if (ruleId <= 0) return errorResponse("ruleId is required")
                runBlocking { ServiceLocator.ruleRepository.deleteRule(ruleId) }
                okResponse(JSONObject().put("ruleId", ruleId))
            }.getOrElse { errorResponse(it.message ?: "failed to delete rule") }
        }
    }

    private companion object {
        private const val PREF_PC_SETTINGS_TOKEN = "pc_settings_token"
    }
}
