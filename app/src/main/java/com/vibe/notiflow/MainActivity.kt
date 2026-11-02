package com.vibe.notiflow

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.widget.FrameLayout
import android.view.ViewGroup
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewAssetLoader
import com.cloimism.notiflow.BuildConfig
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.vibe.notiflow.di.ServiceLocator
import com.vibe.notiflow.domain.model.Rule
import com.vibe.notiflow.share.ShareInbox
import com.vibe.notiflow.update.GitHubReleaseUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val credentials by lazy { CredentialManager.create(this) }
    private val inbox by lazy { ShareInbox(this) }
    private var shareState = JSONObject()
    private var updateState = JSONObject().put("busy", false)
    private var lastUpdateUrl: String? = null
    private var authError: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            setBackgroundColor(Color.rgb(13, 21, 36))
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                    request?.url?.let(loader::shouldInterceptRequest)
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val uri = request?.url ?: return false
                    if (uri.host == "appassets.androidplatform.net") return false
                    if (uri.scheme in setOf("https", "http")) {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                    return true
                }
            }
            addJavascriptInterface(Bridge(), "NotiFlowNative")
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(13, 21, 36))
            addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        setBars(false)
        webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("window.dispatchEvent(new Event('notiflowBack'))", null)
            }
        })
        if (savedInstanceState == null) receiveShare(intent)
        auth.currentUser?.let { inbox.enqueuePending(it.uid) }
    }

    override fun onResume() {
        super.onResume()
        emit("notiflowNotificationAccessChanged", JSONObject().put("enabled", notificationAccessEnabled()))
    }

    private fun notificationAccessEnabled(): Boolean =
        Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.split(':')?.any { it.substringBefore('/') == packageName } == true

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShare(intent)
    }

    private fun receiveShare(incoming: Intent) {
        if (incoming.action !in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        shareState = JSONObject().put("busy", true)
        emit("notiflowShareChanged", shareState)
        lifecycleScope.launch {
            runCatching { withContext(Dispatchers.IO) { inbox.receive(incoming) } }
                .onSuccess { event ->
                    shareState = JSONObject().put("busy", false).put("received", true)
                        .put("title", event.title).put("mimeType", event.mimeType)
                        .put("fileCount", event.attachments.size)
                        .put("waitingForLogin", auth.currentUser == null)
                    auth.currentUser?.let { inbox.enqueuePending(it.uid) }
                }.onFailure {
                    shareState = JSONObject().put("busy", false).put("error", it.message ?: "공유를 다시 시도해 주세요")
                }
            emit("notiflowShareChanged", shareState)
        }
    }

    private fun setBars(light: Boolean) {
        window.statusBarColor = if (light) Color.rgb(13, 21, 36) else Color.rgb(13, 21, 36)
        window.navigationBarColor = if (Build.VERSION.SDK_INT < 26) Color.rgb(13, 21, 36) else window.statusBarColor
        WindowInsetsControllerCompat(window, webView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }

    private fun emit(name: String, payload: JSONObject) {
        if (!::webView.isInitialized) return
        runOnUiThread {
            if (!isDestroyed) webView.evaluateJavascript("window.dispatchEvent(new CustomEvent('$name', {detail: $payload}))", null)
        }
    }

    private fun authState() = JSONObject().apply {
        val user = runCatching { auth.currentUser }.getOrNull()
        put("signedIn", user != null)
        user?.let {
            put("uid", it.uid)
            put("displayName", it.displayName ?: "Google 계정")
            put("email", it.email ?: "")
        }
        authError?.let { put("authError", it) }
    }

    private fun signIn() {
        lifecycleScope.launch {
            authError = null
            try {
                val id = resources.getIdentifier("default_web_client_id", "string", packageName)
                check(id != 0) { "Google 로그인 설정을 확인해 주세요" }
                val request = GetCredentialRequest.Builder().addCredentialOption(
                    GetSignInWithGoogleOption.Builder(getString(id)).build()).build()
                val credential = credentials.getCredential(this@MainActivity, request).credential
                require(credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                auth.signInWithCredential(GoogleAuthProvider.getCredential(google.idToken, null)).await()
                auth.currentUser?.let { inbox.enqueuePending(it.uid) }
                if (shareState.optBoolean("waitingForLogin")) {
                    shareState.put("waitingForLogin", false)
                    emit("notiflowShareChanged", shareState)
                }
            } catch (_: GetCredentialCancellationException) {
                // Choosing later returns to the same login screen.
            } catch (error: Exception) {
                authError = error.message ?: "Google 로그인을 다시 시도해 주세요"
            }
            emit("notiflowAuthChanged", authState())
        }
    }

    private fun response(block: () -> JSONObject): String = runCatching {
        JSONObject().put("ok", true).put("data", block()).toString()
    }.getOrElse { JSONObject().put("ok", false).put("error", it.message ?: "다시 시도해 주세요").toString() }

    private fun signedIn() { check(auth.currentUser != null) { "Google 계정으로 로그인해 주세요" } }

    private fun ruleJson(rule: Rule): JSONObject {
        val result = JSONObject(Json.encodeToString(rule))
        val source = rule.filters.firstOrNull { it.type == "source.equals" }?.config?.get("value")?.jsonPrimitive?.contentOrNull
        result.put("source", source ?: "notification")
        if (rule.conditionExpression == null) {
            val rows = JSONArray()
            rule.filters.filter { it.type !in setOf("package.equals", "source.equals") }.forEach {
                rows.put(JSONObject().put("type", it.type).put("value", it.config["value"]?.jsonPrimitive?.contentOrNull ?: "")
                    .put("operator", rule.filterOperator.name))
            }
            result.put("conditionExpression", JSONObject().put("rows", rows))
        }
        return result
    }

    inner class Bridge {
        @JavascriptInterface fun getAuthState(): String = response { authState() }
        @JavascriptInterface fun getShareState(): String = response { shareState }
        @JavascriptInterface fun getUpdateState(): String = response { updateState }
        @JavascriptInterface fun getAppInfo(): String = JSONObject().put("versionName", BuildConfig.VERSION_NAME).toString()
        @JavascriptInterface fun setSystemBarsTheme(light: Boolean) { runOnUiThread { setBars(light) } }
        @JavascriptInterface fun closeApp() { runOnUiThread { finish() } }
        @JavascriptInterface fun signInWithGoogle(): String = response { runOnUiThread { signIn() }; JSONObject() }
        @JavascriptInterface fun signOutGoogle(): String = response {
            runOnUiThread {
                lifecycleScope.launch {
                    auth.signOut()
                    runCatching { credentials.clearCredentialState(ClearCredentialStateRequest()) }
                    emit("notiflowAuthChanged", authState())
                }
            }
            JSONObject()
        }
        @JavascriptInterface fun listRules(): String = response {
            signedIn()
            JSONObject().put("rules", JSONArray().apply {
                runBlocking(Dispatchers.IO) { ServiceLocator.ruleRepository.getAllRules() }.forEach { put(ruleJson(it)) }
            })
        }
        @JavascriptInterface fun createRule(inputJson: String): String = response {
            signedIn()
            val rule = RuleInputFactory().build(JSONObject(inputJson), null)
            JSONObject().put("ruleId", runBlocking(Dispatchers.IO) { ServiceLocator.ruleRepository.upsertRule(rule) })
        }
        @JavascriptInterface fun updateRule(inputJson: String): String = response {
            signedIn()
            val input = JSONObject(inputJson)
            val existing = runBlocking(Dispatchers.IO) { ServiceLocator.ruleRepository.getRuleById(input.getLong("id")) }
                ?: error("규칙을 다시 불러와 주세요")
            val rule = RuleInputFactory().build(input, existing)
            JSONObject().put("ruleId", runBlocking(Dispatchers.IO) { ServiceLocator.ruleRepository.upsertRule(rule) })
        }
        @JavascriptInterface fun setRuleEnabled(id: Long, enabled: Boolean): String = response {
            signedIn()
            runBlocking(Dispatchers.IO) { ServiceLocator.ruleRepository.updateEnabled(id, enabled) }
            JSONObject()
        }
        @JavascriptInterface fun deleteRule(id: Long): String = response {
            signedIn()
            runBlocking(Dispatchers.IO) { ServiceLocator.ruleRepository.deleteRule(id) }
            JSONObject()
        }
        @JavascriptInterface fun listLogs(limit: Int): String = response {
            signedIn()
            val logs = runBlocking(Dispatchers.IO) { ServiceLocator.ruleRepository.getRecentLogs(limit.coerceIn(1, 500)) }
            JSONObject().put("logs", JSONArray(Json.encodeToString(logs)))
        }
        @JavascriptInterface fun isNotificationListenerEnabled(): Boolean = notificationAccessEnabled()
        @JavascriptInterface fun openNotificationListenerSettings() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }
        @JavascriptInterface fun checkForUpdate(): String = response {
            if (!updateState.optBoolean("busy")) {
                updateState = JSONObject().put("busy", true)
                runOnUiThread {
                    lifecycleScope.launch {
                        updateState = runCatching {
                            val release = withContext(Dispatchers.IO) {
                                OkHttpClient().newCall(Request.Builder().url(GitHubReleaseUpdate.LATEST_RELEASE_API_URL)
                                    .header("Accept", "application/vnd.github+json").build()).execute().use { res ->
                                    if (res.code == 404) return@withContext null
                                    check(res.isSuccessful) { "업데이트 정보를 확인하지 못했어요 (${res.code})" }
                                    res.body?.string() ?: error("업데이트 정보가 비어 있어요")
                                }
                            }
                            if (release == null) JSONObject().put("available", false)
                            else {
                                val candidate = GitHubReleaseUpdate.parseLatestRelease(release, BuildConfig.VERSION_NAME,
                                    BuildConfig.APPLICATION_ID.endsWith(".dev"))
                                lastUpdateUrl = candidate.downloadUrl?.takeIf { it.isNotBlank() } ?: candidate.releaseUrl
                                JSONObject().put("available", candidate.available)
                                    .put("latestVersionName", candidate.latestVersionName)
                                    .put("currentVersionName", BuildConfig.VERSION_NAME)
                                    .put("notes", JSONObject(release).optString("body"))
                            }
                        }.getOrElse { JSONObject().put("error", it.message ?: "잠시 후 다시 확인해 주세요") }
                        updateState.put("busy", false).put("checked", true)
                        emit("notiflowUpdateChanged", updateState)
                    }
                }
            }
            JSONObject().put("started", true)
        }
        @JavascriptInterface fun downloadUpdate(): String = response {
            val url = lastUpdateUrl ?: error("먼저 업데이트를 확인해 주세요")
            val uri = Uri.parse(url)
            require(uri.scheme == "https" && uri.host == "github.com" && uri.path?.startsWith("/cloim/NotiFlow/releases/") == true)
            runOnUiThread { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
            JSONObject()
        }
    }

    override fun onDestroy() {
        webView.removeJavascriptInterface("NotiFlowNative")
        webView.destroy()
        super.onDestroy()
    }
}
