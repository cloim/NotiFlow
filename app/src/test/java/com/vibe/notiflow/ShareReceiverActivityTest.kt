package com.vibe.notiflow

import android.app.Application
import android.content.Intent
import android.os.Looper
import android.util.TypedValue
import androidx.work.Operation
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.vibe.notiflow.domain.model.NotificationEvent
import com.vibe.notiflow.share.ShareReceiverActivity
import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class ShareReceiverActivityTest {
    private val auth = mockk<FirebaseAuth>()
    private val work = mockk<WorkManager>()

    @Before fun prepare() {
        mockkStatic(FirebaseAuth::class)
        mockkObject(WorkManager.Companion)
        every { FirebaseAuth.getInstance() } returns auth
        every { WorkManager.getInstance(any()) } returns work
        every { work.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>()) } returns mockk<Operation>()
    }

    @After fun cleanup() { unmockkAll() }

    @Test fun signedInShareQueuesTheContentWithoutLaunchingTheAppScreen() {
        val user = mockk<FirebaseUser>()
        every { user.uid } returns "share-test-user"
        every { auth.currentUser } returns user
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://example.com/shared-page")
        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        val activity = controller.get()
        runBlocking {
            withTimeout(10000) {
                while (!activity.isFinishing) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
        }
        val transparent = TypedValue()
        assertTrue(activity.theme.resolveAttribute(android.R.attr.windowIsTranslucent, transparent, true))
        assertTrue(transparent.data != 0)
        assertNull(shadowOf(activity).nextStartedActivity)
        verify(exactly = 1) { work.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>()) }
        val files = File(activity.filesDir, "share-inbox").listFiles().orEmpty().filter { it.extension == "json" }
        assertEquals(1, files.size)
        val event = Json.decodeFromString<NotificationEvent>(files.single().readText())
        assertEquals("https://example.com/shared-page", event.text)
        assertEquals("text/uri-list", event.mimeType)
        assertEquals("share", event.source)
        assertNull(ShadowToast.getTextOfLatestToast()) // Success is shown only after the worker executes.
        controller.pause().stop().destroy()
    }

    @Test fun signedOutShareShowsFailureWithoutOpeningGoogleLogin() {
        every { auth.currentUser } returns null
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "https://example.com/shared-page")
        val controller = Robolectric.buildActivity(ShareReceiverActivity::class.java, intent).setup()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
        assertEquals("공유 실패: 먼저 NotiFlow에서 로그인해 주세요", ShadowToast.getTextOfLatestToast())
        verify(exactly = 0) { work.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>()) }
        controller.pause().stop().destroy()
    }

    @Test fun sharesResolveToTheTransparentReceiverAndNotMainActivity() {
        val context = RuntimeEnvironment.getApplication()
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName)
        @Suppress("DEPRECATION")
        val targets = context.packageManager.queryIntentActivities(intent, 0).map { it.activityInfo.name }
        assertTrue(targets.contains(ShareReceiverActivity::class.java.name))
        assertFalse(targets.contains(MainActivity::class.java.name))
    }
}
