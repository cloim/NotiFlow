package com.vibe.notiflow

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import androidx.webkit.WebViewAssetLoader
import com.cloimism.notiflow.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.vibe.notiflow.di.ServiceLocator
import com.vibe.notiflow.domain.engine.ConditionExpressionEvaluator
import com.vibe.notiflow.domain.model.ActionSpec
import com.vibe.notiflow.domain.model.ConditionExpression
import com.vibe.notiflow.domain.model.ConditionExpressionRow
import com.vibe.notiflow.domain.model.FilterOperator
import com.vibe.notiflow.domain.model.FilterSpec
import com.vibe.notiflow.domain.model.Rule
import com.vibe.notiflow.domain.transfer.RuleTransfer
import com.vibe.notiflow.notification.PushTokenRegistrar
import com.vibe.notiflow.pc.PcSettingsServer
import com.vibe.notiflow.update.GitHubReleaseUpdate
import com.vibe.notiflow.update.UpdateCandidate
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
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
    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val credentialManager by lazy { CredentialManager.create(this) }
    private val pushTokenRegistrar by lazy { PushTokenRegistrar(this) }
    private var lastAuthError: String? = null
    private var pcSettingsServer: PcSettingsServer? = null
    private var pendingFileChooserCallback: ValueCallback<Array<Uri>>? = null
    private var pendingExportFileName: String? = null
    private var pendingExportContent: String? = null
    private val pcSettingsPrefs by lazy {
        getSharedPreferences("pc_settings", Context.MODE_PRIVATE)
    }
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFileChooserCallback ?: return@registerForActivityResult
        pendingFileChooserCallback = null
        val uris = if (result.resultCode == Activity.RESULT_OK) {
            FileChooserParams.parseResult(result.resultCode, result.data)
                ?: result.data?.data?.let { arrayOf(it) }
        } else {
            null
        }
        callback.onReceiveValue(uris)
    }
    private val createJsonDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val content = pendingExportContent
        clearPendingExport()
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null || content == null) return@registerForActivityResult

        runCatching {
            contentResolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                    writer.write(content)
                }
            } ?: error("failed to open output stream")
        }
    }
    private val requestPostNotificationsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setSystemBarsTheme(isLight = false)
        requestPostNotificationsPermissionIfNeeded()

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
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    pendingFileChooserCallback?.onReceiveValue(null)
                    pendingFileChooserCallback = filePathCallback
                    return runCatching {
                        val intent = fileChooserParams?.createIntent()
                            ?: Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "application/json"
                            }
                        fileChooserLauncher.launch(intent)
                        true
                    }.getOrElse {
                        pendingFileChooserCallback = null
                        filePathCallback?.onReceiveValue(null)
                        false
                    }
                }
            }
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

    private fun runOnUiThreadAndWait(block: () -> Unit) {
        if (mainLooper.isCurrentThread) {
            block()
            return
        }

        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        runOnUiThread {
            try {
                block()
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
        failure.get()?.let { throw it }
    }

    private fun safeJsonFileName(fileName: String): String {
        val safeName = Regex("[^A-Za-z0-9._-]").replace(fileName.trim(), "_").trim('_')
        val baseName = if (safeName.isBlank() || safeName == "." || safeName == "..") {
            "notiflow-rules"
        } else {
            safeName
        }
        return ensureJsonFileName(baseName)
    }

    private fun ensureJsonFileName(fileName: String): String {
        return if (fileName.endsWith(".json", ignoreCase = true)) fileName else "$fileName.json"
    }

    private fun clearPendingExport() {
        pendingExportFileName = null
        pendingExportContent = null
    }

    private fun launchJsonSavePicker(fileName: String, content: String) {
        runOnUiThreadAndWait {
            pendingExportFileName = safeJsonFileName(fileName)
            pendingExportContent = content
            try {
                createJsonDocumentLauncher.launch(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/json"
                        putExtra(Intent.EXTRA_TITLE, pendingExportFileName)
                    }
                )
            } catch (error: Throwable) {
                clearPendingExport()
                throw error
            }
        }
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

    private fun requestPostNotificationsPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestPostNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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

    private fun importedTokenRefs(rule: Rule): List<String> {
        return rule.actions
            .filter { it.type == "webhook.post" }
            .mapNotNull { action -> action.config["tokenRef"]?.jsonPrimitive?.contentOrNull }
    }

    private fun rollbackImportedSecrets(aliases: List<String>) {
        aliases.forEach { alias -> ServiceLocator.secureStore.removeSecret(alias) }
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

    private fun authStateJson(): JSONObject {
        val user = firebaseAuth.currentUser
        return JSONObject().apply {
            put("signedIn", user != null)
            user?.let {
                put("uid", it.uid)
                put("email", it.email ?: "")
                put("displayName", it.displayName ?: "")
                put("photoUrl", it.photoUrl?.toString() ?: "")
            }
            lastAuthError?.let { put("authError", it) }
        }
    }

    private fun defaultWebClientId(): String {
        val resId = resources.getIdentifier("default_web_client_id", "string", packageName)
        if (resId == 0) {
            throw IllegalStateException("Firebase Google OAuth client 설정이 없습니다. google-services.json을 갱신하세요.")
        }
        return getString(resId)
    }

    private suspend fun getGoogleCredential(): Credential {
        val googleIdOption = GetSignInWithGoogleOption.Builder(defaultWebClientId())
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return credentialManager.getCredential(this, request).credential
    }

    private suspend fun signInWithGoogleCredential(credential: Credential) {
        if (credential !is CustomCredential || credential.type != TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw IllegalArgumentException("Google 계정 정보를 가져오지 못했습니다.")
        }

        val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
        val firebaseCredential = GoogleAuthProvider.getCredential(googleCredential.idToken, null)
        firebaseAuth.signInWithCredential(firebaseCredential).await()
    }

    private fun startGoogleSignIn() {
        lifecycleScope.launch {
            lastAuthError = null
            runCatching {
                val credential = getGoogleCredential()

                signInWithGoogleCredential(credential)
                runCatching {
                    pushTokenRegistrar.registerCurrentToken()
                }.onFailure { error ->
                    lastAuthError = "로그인은 됐지만 푸시 토큰 등록에 실패했습니다."
                    Log.w("NotiFlow", "Push token registration failed", error)
                }
            }.onFailure { error ->
                lastAuthError = if (error is NoCredentialException) {
                    "Google 계정을 선택할 수 없습니다. 기기에 Google 계정을 추가하고 Google Play 서비스를 업데이트한 뒤 다시 시도하세요."
                } else {
                    error.message ?: "Google 로그인에 실패했습니다."
                }
                Log.w("NotiFlow", "Google sign-in failed", error)
            }
            notifyAuthStateChanged()
        }
    }

    private fun signOutGoogleAccount() {
        lifecycleScope.launch {
            lastAuthError = null
            runCatching {
                pushTokenRegistrar.unregisterCurrentToken()
            }.onFailure { error ->
                Log.w("NotiFlow", "Push token unregister failed", error)
            }
            firebaseAuth.signOut()
            runCatching {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            }.onFailure { error ->
                if (error is ClearCredentialException) {
                    Log.w("NotiFlow", "Credential state clear failed", error)
                } else {
                    throw error
                }
            }
            notifyAuthStateChanged()
        }
    }

    private fun notifyAuthStateChanged() {
        if (!::webView.isInitialized) return
        val payload = authStateJson().toString()
        runOnUiThread {
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('notiflowAuthChanged', { detail: $payload }))",
                null
            )
        }
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
        fun getAuthState(): String {
            return runCatching {
                okResponse(authStateJson())
            }.getOrElse { errorResponse(it.message ?: "로그인 상태를 확인하지 못했습니다.") }
        }

        @JavascriptInterface
        fun signInWithGoogle(): String {
            return runCatching {
                startGoogleSignIn()
                okResponse()
            }.getOrElse { errorResponse(it.message ?: "Google 로그인을 시작하지 못했습니다.") }
        }

        @JavascriptInterface
        fun signOutGoogle(): String {
            return runCatching {
                signOutGoogleAccount()
                okResponse()
            }.getOrElse { errorResponse(it.message ?: "로그아웃하지 못했습니다.") }
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
        fun exportRules(inputJson: String): String {
            return runCatching {
                val input = JSONObject(inputJson)
                val ruleIdsJson = input.optJSONArray("ruleIds") ?: JSONArray()
                val ruleIds = (0 until ruleIdsJson.length()).map { index ->
                    ruleIdsJson.getLong(index)
                }.toSet()
                val includeSecrets = input.optBoolean("includeSecrets", false)
                val rules = runBlocking { ServiceLocator.ruleRepository.getAllRules() }
                val exported = RuleTransfer.exportRules(
                    rules = rules,
                    selectedRuleIds = ruleIds,
                    includeSecrets = includeSecrets,
                    tokenResolver = { alias -> ServiceLocator.secureStore.getSecret(alias) },
                    nowMillis = { System.currentTimeMillis() }
                )

                okResponse(JSONObject().put("export", exported))
            }.getOrElse { errorResponse(it.message ?: "failed to export rules") }
        }

        @JavascriptInterface
        fun importRules(inputJson: String): String {
            return runCatching {
                val createdTokenRefs = mutableListOf<String>()
                val ruleIds = try {
                    val inputs = RuleTransfer.importRuleInputs(JSONObject(inputJson))
                    val rules = inputs.map { input ->
                        buildRuleFromInput(input, existing = null).also { rule ->
                            createdTokenRefs += importedTokenRefs(rule)
                        }
                    }
                    runBlocking { ServiceLocator.ruleRepository.upsertRules(rules) }
                } catch (error: Throwable) {
                    rollbackImportedSecrets(createdTokenRefs)
                    throw error
                }

                okResponse(
                    JSONObject().apply {
                        put("imported", ruleIds.size)
                        put("ruleIds", JSONArray(ruleIds))
                    }
                )
            }.getOrElse { errorResponse(it.message ?: "failed to import rules") }
        }

        @JavascriptInterface
        fun saveJsonFile(fileName: String, content: String): String {
            return runCatching {
                launchJsonSavePicker(fileName, content)
                okResponse(JSONObject().put("fileName", safeJsonFileName(fileName)))
            }.getOrElse { errorResponse(it.message ?: "failed to start JSON save") }
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
