package ru.outsideproarts.oassist.core.device

import android.os.Binder
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalCoroutinesApi::class)
class PrivServiceClientDeadlineTest {
    @Test fun blockedBinderReturnsTimeoutAndKeepsParcelsUntilLateReply() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = CountDownLatch(1)
        val calls = AtomicInteger()
        val target = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                calls.incrementAndGet()
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                // Both parcels must remain usable after the client has stopped waiting.
                data.enforceInterface(PrivUserService.DESCRIPTOR)
                reply!!.writeInt(data.readInt())
                return true
            }
        }
        val client = PrivServiceClient(InstrumentationRegistry.getInstrumentation().targetContext)
        PrivServiceClient::class.java.getDeclaredField("binder").apply {
            isAccessible = true
            set(client, target)
        }
        try {
            val first = async {
                client.transact(1, 200, { it.writeInt(7) }, {
                    assertEquals(7, it.readInt())
                    reads.countDown()
                    "late"
                })
            }
            runCurrent()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            advanceTimeBy(200)
            runCurrent()
            val failure = first.await().exceptionOrNull()
            assertTrue(failure is TimeoutCancellationException)
            assertEquals(1L, reads.count)
            val queued = async { client.transact(1, 50, { fail("Expired request was sent") }, { 0 }) }
            runCurrent()
            advanceTimeBy(50)
            runCurrent()
            assertTrue(queued.await().exceptionOrNull() is TimeoutCancellationException)
            assertEquals(1, calls.get())
            release.countDown()
            assertTrue(reads.await(5, TimeUnit.SECONDS))
            assertTrue(first.await().isFailure)
            assertEquals(1, calls.get())
        } finally {
            release.countDown()
            client.close()
        }
    }
}
