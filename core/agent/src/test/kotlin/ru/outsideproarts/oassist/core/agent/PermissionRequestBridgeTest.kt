package ru.outsideproarts.oassist.core.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PermissionRequestBridgeTest {

    @Test
    fun `owner cancellation removes request before and after fallback timeout`() = runTest {
        for (elapsed in listOf(0L, 200L)) {
            val id = PermissionRequestBridge.register(listOf("android.permission.CAMERA"))
            val owner = async { PermissionRequestBridge.await(id, 100, 10_000) }
            runCurrent()
            advanceTimeBy(elapsed)
            runCurrent()
            owner.cancelAndJoin()
            assertTrue(owner.isCancelled)
            assertNull(PermissionRequestBridge.blocked.value)
            val repeat = async { PermissionRequestBridge.await(id, 100, 10_000) }
            runCurrent()
            try {
                assertTrue("Removed request must return immediately", repeat.isCompleted)
                assertEquals(PermissionRequestOutcome.UNAVAILABLE, repeat.await())
            } finally {
                repeat.cancelAndJoin()
                PermissionRequestBridge.cancel(id)
            }
        }
    }

    @Test
    fun `old owner cleanup and late callbacks preserve new fallback`() = runTest {
        val oldId = PermissionRequestBridge.register(listOf("android.permission.CAMERA"))
        val oldOwner = async { PermissionRequestBridge.await(oldId, 100, 10_000) }
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        val newId = PermissionRequestBridge.register(listOf("android.permission.RECORD_AUDIO"))
        val newOwner = async { PermissionRequestBridge.await(newId, 100, 10_000) }
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        try {
            assertEquals(newId, PermissionRequestBridge.blocked.value?.requestId)
            oldOwner.cancelAndJoin()
            PermissionRequestBridge.markStarted(oldId)
            PermissionRequestBridge.complete(oldId, PermissionRequestOutcome.GRANTED)
            PermissionRequestBridge.cancel(oldId)
            assertEquals(newId, PermissionRequestBridge.blocked.value?.requestId)
            assertTrue(newOwner.isActive)
            PermissionRequestBridge.complete(newId, PermissionRequestOutcome.DENIED)
            assertEquals(PermissionRequestOutcome.DENIED, newOwner.await())
            assertNull(PermissionRequestBridge.blocked.value)
            assertEquals(PermissionRequestOutcome.UNAVAILABLE, PermissionRequestBridge.await(oldId))
        } finally {
            oldOwner.cancelAndJoin()
            newOwner.cancelAndJoin()
            PermissionRequestBridge.cancel(oldId)
            PermissionRequestBridge.cancel(newId)
        }
    }

    @Test
    fun `ответ активности завершает ожидание результатом`() = runTest {
        val id = PermissionRequestBridge.register(listOf("android.permission.CALL_PHONE"))
        val deferred = async {
            PermissionRequestBridge.await(id, startTimeoutMs = 100, userTimeoutMs = 10_000)
        }
        runCurrent()
        PermissionRequestBridge.markStarted(id)
        PermissionRequestBridge.complete(id, PermissionRequestOutcome.GRANTED)
        assertEquals(PermissionRequestOutcome.GRANTED, deferred.await())
        assertNull(PermissionRequestBridge.blocked.value)
    }

    @Test
    fun `заблокированный старт публикует blocked, тап по фолбэку завершает запрос`() = runTest {
        val id = PermissionRequestBridge.register(listOf("android.permission.SEND_SMS"))
        val deferred = async {
            PermissionRequestBridge.await(id, startTimeoutMs = 100, userTimeoutMs = 10_000)
        }
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        val blocked = PermissionRequestBridge.blocked.value
        assertEquals(id, blocked?.requestId)
        assertEquals(listOf("android.permission.SEND_SMS"), blocked?.permissions)
        assertTrue(deferred.isActive)

        // Тап по действию «Выдать» в уведомлении: ConsentActivity стартует и отвечает.
        PermissionRequestBridge.markStarted(id)
        assertNull(PermissionRequestBridge.blocked.value)
        PermissionRequestBridge.complete(id, PermissionRequestOutcome.DENIED)
        assertEquals(PermissionRequestOutcome.DENIED, deferred.await())
    }

    @Test
    fun `без ответа по таймауту запрос снимается и возвращается UNAVAILABLE`() = runTest {
        val id = PermissionRequestBridge.register(listOf("android.permission.CAMERA"))
        val outcome = PermissionRequestBridge.await(id, startTimeoutMs = 50, userTimeoutMs = 500)
        assertEquals(PermissionRequestOutcome.UNAVAILABLE, outcome)
        assertNull(PermissionRequestBridge.blocked.value)
    }

    @Test
    fun `метка старта до таймаута не публикует blocked`() = runTest {
        val id = PermissionRequestBridge.register(listOf("android.permission.READ_CONTACTS"))
        val deferred = async {
            PermissionRequestBridge.await(id, startTimeoutMs = 1_000, userTimeoutMs = 10_000)
        }
        runCurrent()
        PermissionRequestBridge.markStarted(id)
        advanceTimeBy(2_000)
        runCurrent()
        assertNull(PermissionRequestBridge.blocked.value)
        PermissionRequestBridge.complete(id, PermissionRequestOutcome.DENIED_FOREVER)
        assertEquals(PermissionRequestOutcome.DENIED_FOREVER, deferred.await())
    }

    @Test
    fun `cancel завершает ожидание UNAVAILABLE и чистит blocked`() = runTest {
        val id = PermissionRequestBridge.register(listOf("android.permission.CAMERA"))
        val deferred = async {
            PermissionRequestBridge.await(id, startTimeoutMs = 100, userTimeoutMs = 10_000)
        }
        runCurrent()
        advanceTimeBy(200)
        runCurrent()
        assertEquals(id, PermissionRequestBridge.blocked.value?.requestId)

        PermissionRequestBridge.cancel(id)
        assertEquals(PermissionRequestOutcome.UNAVAILABLE, deferred.await())
        assertNull(PermissionRequestBridge.blocked.value)
    }

    @Test
    fun `неизвестный id — сразу UNAVAILABLE`() = runTest {
        val outcome = PermissionRequestBridge.await(
            "missing-id",
            startTimeoutMs = 10,
            userTimeoutMs = 10,
        )
        assertEquals(PermissionRequestOutcome.UNAVAILABLE, outcome)
        assertNull(PermissionRequestBridge.blocked.value)
    }

    @Test
    fun `complete после cancel — no-op без падений`() = runTest {
        val id = PermissionRequestBridge.register(listOf("android.permission.CAMERA"))
        PermissionRequestBridge.cancel(id)
        PermissionRequestBridge.complete(id, PermissionRequestOutcome.GRANTED)
        assertNull(PermissionRequestBridge.blocked.value)
    }
}
