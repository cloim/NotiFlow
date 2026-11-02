package com.vibe.notiflow.share

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Transparent transient task: copy URI grants, enqueue work and return to the sending app. */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) { finish(); return }
        lifecycleScope.launch {
            try {
                val uid = FirebaseAuth.getInstance().currentUser?.uid
                if (uid == null) {
                    Toast.makeText(applicationContext, "공유 실패: 먼저 NotiFlow에서 로그인해 주세요", Toast.LENGTH_LONG).show()
                    return@launch
                }
                val inbox = ShareInbox(applicationContext)
                withContext(Dispatchers.IO) {
                    inbox.receive(intent)
                    inbox.enqueuePending(uid)
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // A system-terminated receiver must not show an incorrect success.
            } catch (_: Exception) {
                Toast.makeText(applicationContext, "공유 실패: 원본 앱에서 다시 공유해 주세요", Toast.LENGTH_LONG).show()
            } finally {
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
    }
}
