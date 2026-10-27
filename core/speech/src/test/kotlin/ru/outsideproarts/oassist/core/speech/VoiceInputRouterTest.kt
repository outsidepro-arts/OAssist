package ru.outsideproarts.oassist.core.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputRouterTest {

    private class FakeBackend(
        var btInputPresent: Boolean = true,
        var permissionGranted: Boolean = true,
        var routeAccepted: Boolean = true,
    ) : VoiceRouteBackend {
        var routeSet = false
        var watching = false
        var onLost: (() -> Unit)? = null
        var routeClearCount = 0
        var loseDuringWatch = false
        var failDuringWatch = false

        override fun hasBluetoothInput(): Boolean = btInputPresent
        override fun hasBluetoothPermission(): Boolean = permissionGranted
        override fun setBluetoothRoute(): Boolean {
            if (!routeAccepted) return false
            routeSet = true
            return true
        }
        override fun clearRoute() {
            routeClearCount++
            routeSet = false
        }
        override fun watchDeviceLoss(onLost: () -> Unit) {
            watching = true
            this.onLost = onLost
            if (failDuringWatch) error("watch failed")
            if (loseDuringWatch) onLost()
        }
        override fun unwatchDeviceLoss() {
            watching = false
            onLost = null
        }
    }

    private class FakeFlag(var requested: Boolean = false) : BtPermissionFlag {
        override fun isRequested(): Boolean = requested
        override fun markRequested() {
            requested = true
        }
    }

    @Test
    fun decide_prefersBluetoothWhenConnectedAndGranted() {
        val router = VoiceInputRouter(FakeBackend(), FakeFlag())

        assertEquals(BtInputDecision.USE_BLUETOOTH, router.decide(enabled = true))
    }

    @Test
    fun decide_usesMicWhenDisabledOrNoHeadset() {
        val router = VoiceInputRouter(FakeBackend(btInputPresent = false), FakeFlag())

        assertEquals(BtInputDecision.USE_MIC, router.decide(enabled = true))
        assertEquals(
            BtInputDecision.USE_MIC,
            VoiceInputRouter(FakeBackend(), FakeFlag()).decide(enabled = false),
        )
    }

    @Test
    fun decide_asksOnceThenFallsBackToMic() {
        val flag = FakeFlag()
        val router = VoiceInputRouter(FakeBackend(permissionGranted = false), flag)

        assertEquals(BtInputDecision.ASK_PERMISSION, router.decide(enabled = true))

        router.markPermissionRequested()
        assertTrue(flag.requested)
        assertEquals(BtInputDecision.USE_MIC, router.decide(enabled = true))
    }

    @Test
    fun acquire_setsRouteAndRelease_clearsIt() {
        val backend = FakeBackend()
        val router = VoiceInputRouter(backend, FakeFlag())

        assertTrue(router.acquire(enabled = true))
        assertTrue(backend.routeSet)
        assertTrue(backend.watching)
        assertEquals(VoiceInputRoute.BLUETOOTH, router.route.value)

        router.release()
        assertFalse(backend.routeSet)
        assertFalse(backend.watching)
        assertEquals(VoiceInputRoute.MICROPHONE, router.route.value)
    }

    @Test
    fun acquire_skipsRouteWhenDecisionIsMic() {
        val backend = FakeBackend(permissionGranted = false)
        val router = VoiceInputRouter(backend, FakeFlag(requested = true))

        assertFalse(router.acquire(enabled = true))
        assertFalse(backend.routeSet)
        assertEquals(VoiceInputRoute.MICROPHONE, router.route.value)
    }

    @Test
    fun acquire_fallsBackToMicWhenSystemRejectsRoute() {
        val backend = FakeBackend(routeAccepted = false)
        val router = VoiceInputRouter(backend, FakeFlag())

        assertFalse(router.acquire(enabled = true))
        assertFalse(backend.routeSet)
        assertEquals(VoiceInputRoute.MICROPHONE, router.route.value)
    }

    @Test
    fun acquire_replacesPreviousRoute() {
        val backend = FakeBackend()
        val router = VoiceInputRouter(backend, FakeFlag())

        assertTrue(router.acquire(enabled = true))
        assertTrue(router.acquire(enabled = true))
        assertTrue(backend.routeSet)

        router.release()
        assertFalse(backend.routeSet)
    }

    @Test
    fun deviceLossMidCapture_fallsBackToMicrophone() {
        val backend = FakeBackend()
        val router = VoiceInputRouter(backend, FakeFlag())

        router.acquire(enabled = true)
        backend.onLost?.invoke()

        assertEquals(VoiceInputRoute.MICROPHONE, router.route.value)
        assertFalse(backend.routeSet)
        assertFalse(backend.watching)
    }

    @Test
    fun staleDeviceLossCallback_isIgnored() {
        val backend = FakeBackend()
        val router = VoiceInputRouter(backend, FakeFlag())

        router.acquire(enabled = true)
        val stale = backend.onLost
        router.release()

        stale?.invoke()
        assertEquals(VoiceInputRoute.MICROPHONE, router.route.value)
    }

    @Test
    fun staleLossFromPreviousCapture_keepsNewBluetoothRoute() {
        val backend = FakeBackend()
        val router = VoiceInputRouter(backend, FakeFlag())

        assertTrue(router.acquire(enabled = true))
        val oldLoss = requireNotNull(backend.onLost)
        router.release()
        assertTrue(router.acquire(enabled = true))
        val currentLoss = requireNotNull(backend.onLost)
        val clearsBeforeStale = backend.routeClearCount

        oldLoss()

        assertEquals(VoiceInputRoute.BLUETOOTH, router.route.value)
        assertTrue(backend.routeSet)
        assertTrue(backend.watching)
        assertEquals(clearsBeforeStale, backend.routeClearCount)

        currentLoss()
        assertEquals(VoiceInputRoute.MICROPHONE, router.route.value)
        assertFalse(backend.routeSet)
        assertFalse(backend.watching)
        assertEquals(clearsBeforeStale + 1, backend.routeClearCount)
    }

    @Test
    fun staleLossAfterRejectedAcquire_doesNotAffectLaterCapture() {
        val backend = FakeBackend()
        val router = VoiceInputRouter(backend, FakeFlag())

        assertTrue(router.acquire(enabled = true))
        val oldLoss = requireNotNull(backend.onLost)
        backend.routeAccepted = false
        assertFalse(router.acquire(enabled = true))
        oldLoss()
        assertEquals(VoiceInputRoute.MICROPHONE, router.route.value)

        backend.routeAccepted = true
        assertTrue(router.acquire(enabled = true))
        val clearsBeforeStale = backend.routeClearCount
        oldLoss()
        assertEquals(VoiceInputRoute.BLUETOOTH, router.route.value)
        assertTrue(backend.routeSet)
        assertEquals(clearsBeforeStale, backend.routeClearCount)
    }

    @Test
    fun immediateLossDuringWatch_reportsMicrophone() {
        val backend = FakeBackend().apply { loseDuringWatch = true }
        val router = VoiceInputRouter(backend, FakeFlag())

        assertFalse(router.acquire(enabled = true))
        assertEquals(VoiceInputRoute.MICROPHONE, router.route.value)
        assertFalse(backend.routeSet)
        assertFalse(backend.watching)
    }

    @Test
    fun failedWatch_releasesRouteBeforeLaterCapture() {
        val backend = FakeBackend().apply { failDuringWatch = true }
        val router = VoiceInputRouter(backend, FakeFlag())

        assertTrue(runCatching { router.acquire(enabled = true) }.exceptionOrNull() is IllegalStateException)
        assertEquals(VoiceInputRoute.MICROPHONE, router.route.value)
        assertFalse(backend.routeSet)
        assertFalse(backend.watching)

        backend.failDuringWatch = false
        assertTrue(router.acquire(enabled = true))
        assertEquals(VoiceInputRoute.BLUETOOTH, router.route.value)
    }

    @Test
    fun release_isIdempotent() {
        val backend = FakeBackend()
        val router = VoiceInputRouter(backend, FakeFlag())

        router.release()
        router.acquire(enabled = true)
        router.release()
        router.release()

        assertFalse(backend.routeSet)
        assertEquals(VoiceInputRoute.MICROPHONE, router.route.value)
    }
}
