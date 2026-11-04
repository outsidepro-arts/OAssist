package ru.outsideproarts.oassist.permission

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.outsideproarts.oassist.core.agent.PermissionRequestBridge
import ru.outsideproarts.oassist.core.agent.PermissionRequestOutcome

@RunWith(AndroidJUnit4::class)
class AppPermissionRequesterTest {
    private class LaunchContext(base: Context) : ContextWrapper(base) {
        lateinit var launched: Intent
        var onLaunch: (String) -> Unit = {}

        override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
            PackageManager.PERMISSION_DENIED

        override fun startActivity(intent: Intent) {
            launched = intent
            onLaunch(requestId)
        }

        val requestId: String get() = checkNotNull(launched.getStringExtra("request_id"))
    }

    @Test
    fun cancellationBeforeActivityStartsRemovesRequest() = runBlocking {
        verifyCancellation(afterFallback = false)
    }

    @Test
    fun cancellationAfterFallbackRemovesRequest() = runBlocking {
        verifyCancellation(afterFallback = true)
    }

    private suspend fun verifyCancellation(afterFallback: Boolean) = kotlinx.coroutines.coroutineScope {
        val context = LaunchContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val requester = AppPermissionRequester(context)
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            requester.request(listOf(Manifest.permission.CAMERA))
        }
        val id = context.requestId
        try {
            assertTrue(context.launched.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            if (afterFallback) {
                withTimeout(15_000) { PermissionRequestBridge.blocked.first { it?.requestId == id } }
            }
            owner.cancelAndJoin()
            assertTrue(owner.isCancelled)
            assertNull(PermissionRequestBridge.blocked.value)
            assertEquals(
                PermissionRequestOutcome.UNAVAILABLE,
                withTimeout(1_000) { PermissionRequestBridge.await(id) },
            )
            PermissionRequestBridge.markStarted(id)
            PermissionRequestBridge.complete(id, PermissionRequestOutcome.GRANTED)
            assertNull(PermissionRequestBridge.blocked.value)

            // A new real requester invocation gets its own result after the old owner ended.
            context.onLaunch = PermissionRequestBridge::markStarted
            val nextOwner = async(start = CoroutineStart.UNDISPATCHED) {
                requester.request(listOf(Manifest.permission.CAMERA))
            }
            PermissionRequestBridge.complete(context.requestId, PermissionRequestOutcome.DENIED)
            assertEquals(PermissionRequestOutcome.DENIED, nextOwner.await())
        } finally {
            owner.cancelAndJoin()
            PermissionRequestBridge.cancel(id)
        }
    }

    @Test
    fun rejectedActivityLaunchRemovesRegisteredRequest() = runBlocking {
        val context = LaunchContext(InstrumentationRegistry.getInstrumentation().targetContext)
        context.onLaunch = { throw SecurityException("Launch rejected") }
        assertEquals(
            PermissionRequestOutcome.UNAVAILABLE,
            AppPermissionRequester(context).request(listOf(Manifest.permission.CAMERA)),
        )
        assertEquals(
            PermissionRequestOutcome.UNAVAILABLE,
            withTimeout(1_000) { PermissionRequestBridge.await(context.requestId) },
        )
        assertNull(PermissionRequestBridge.blocked.value)
    }
}
