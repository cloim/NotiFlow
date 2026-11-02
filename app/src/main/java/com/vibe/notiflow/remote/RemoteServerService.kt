package com.vibe.notiflow.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.cloimism.notiflow.R
import com.google.firebase.auth.FirebaseAuth
import com.vibe.notiflow.MainActivity
import com.vibe.notiflow.RuleManagement
import com.vibe.notiflow.di.ServiceLocator
import kotlinx.coroutines.*
import org.json.JSONObject
import java.security.SecureRandom

class RemoteServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var server: RemoteHttpServer? = null
    private val lifecycleLock = Any()
    private var destroyed = false
    private var started = false
    @Volatile private var owner: String? = null
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val authListener = FirebaseAuth.AuthStateListener {
        if (owner != null && it.currentUser?.uid != owner) {
            server?.stop()
            fail("로그아웃되어 서버를 종료했어요")
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        auth.addAuthStateListener(authListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (started) return START_NOT_STICKY
        started = true
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(
                CHANNEL, "원격 편집 서버", NotificationManager.IMPORTANCE_LOW
            ))
            val open = PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).putExtra("openSettings", true),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(this, 1, Intent(this, RemoteServerService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_remote_server).setContentTitle("원격 편집 서버 실행 중")
                .setContentText("Tailscale로 규칙과 실행 기록에 접속할 수 있어요")
                .setContentIntent(open).setOngoing(true)
                .addAction(0, "서버 중지", stop).build()
            ServiceCompat.startForeground(this, 81, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        } catch (_: Exception) {
            fail("서버 알림을 시작하지 못했어요. 앱을 열고 다시 시도해 주세요")
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            try {
                owner = auth.currentUser?.uid ?: error("Google 계정으로 로그인해 주세요")
                val host = TailscaleAddress.find() ?: error("Tailscale 앱에서 연결을 켠 뒤 다시 실행해 주세요")
                val code = ByteArray(18).also { SecureRandom().nextBytes(it) }
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                val rules = RuleManagement(ServiceLocator.ruleRepository)
                val http = RemoteHttpServer(host, PORT, code,
                    authorized = { auth.currentUser?.uid == owner && TailscaleAddress.find() == host },
                    asset = { path -> runCatching { assets.open("web/$path").use { it.readBytes() } }.getOrNull() },
                    execute = { method, args ->
                        if (method == "getAuthState") JSONObject().put("signedIn", true)
                            .put("displayName", auth.currentUser?.displayName ?: "원격 연결")
                            .put("email", auth.currentUser?.email ?: "")
                        else runBlocking { rules.execute(method, args) }
                    })
                server = http
                http.start(10000, true)
                synchronized(lifecycleLock) {
                    if (destroyed || !isActive || auth.currentUser?.uid != owner) { http.stop(); return@launch }
                    synchronized(Companion) {
                        state = JSONObject().put("status", "running").put("url", "http://$host:$PORT/")
                            .put("accessCode", code)
                    }
                }
                while (isActive) {
                    delay(2000)
                    if (TailscaleAddress.find() != host) {
                        fail("Tailscale 연결이 끊겨 서버를 종료했어요. 다시 연결한 뒤 실행해 주세요")
                        stopSelf()
                        break
                    }
                }
            } catch (_: CancellationException) {
                // Explicit stop cancels startup and closes the listener in onDestroy.
            } catch (_: Exception) {
                fail("서버를 열지 못했어요. Tailscale 연결과 포트 8186 사용 여부를 확인해 주세요")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        auth.removeAuthStateListener(authListener)
        synchronized(lifecycleLock) {
            destroyed = true
            scope.cancel()
            server?.stop()
            server = null
            synchronized(Companion) {
                if (state.optString("status") != "error") state = JSONObject().put("status", "stopped")
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val PORT = 8186
        private const val CHANNEL = "remote_editor"
        private const val ACTION_STOP = "com.vibe.notiflow.remote.STOP"
        @Volatile private var state = JSONObject().put("status", "stopped")
        @Synchronized fun snapshot(): JSONObject = JSONObject(state.toString()).put("tailscaleConnected", TailscaleAddress.find() != null)
        @Synchronized fun starting() { state = JSONObject().put("status", "starting") }
        @Synchronized fun fail(message: String) { state = JSONObject().put("status", "error").put("error", message) }
    }
}
