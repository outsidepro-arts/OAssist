package ru.outsideproarts.oassist.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultNetworkObserverTest {
    private class Backend : DefaultNetworkBackend<String> {
        var snapshot = InitialNetworkState.OFFLINE
        var snapshotError = false
        var registrationFailures = 0
        var unregisterFailure = false
        var snapshots = 0
        var unregisters = 0
        val listeners = mutableListOf<DefaultNetworkEvents<String>>()
        var duringRegistration: ((DefaultNetworkEvents<String>) -> Unit)? = null

        override fun initialState(): InitialNetworkState {
            snapshots++
            if (snapshotError) throw IllegalStateException("unavailable")
            return snapshot
        }

        override fun register(events: DefaultNetworkEvents<String>) {
            listeners.add(events)
            duringRegistration?.invoke(events)
            if (registrationFailures > 0) {
                registrationFailures--
                throw SecurityException("unavailable")
            }
        }

        override fun unregister(events: DefaultNetworkEvents<String>) {
            unregisters++
            if (unregisterFailure) throw SecurityException("unavailable")
        }
    }

    private class Fixture {
        val backend = Backend()
        var online = true
        val transitions = mutableListOf<Boolean>()
        val failures = mutableListOf<String>()
        val observer = DefaultNetworkObserver(
            backend,
            publish = { next ->
                if (next != online) transitions.add(next)
                online = next
            },
            reportFailure = { operation, _ -> failures.add(operation) },
        )
        val current: DefaultNetworkEvents<String> get() = backend.listeners.last()
    }

    @Test
    fun offlineBootstrapRecoversFromCallbackPayloadWithoutRequery() {
        val f = Fixture()
        f.observer.start()
        assertFalse(f.online)
        f.current.available("wifi")
        f.current.capabilities("wifi", hasInternet = true)
        assertFalse(f.online)
        f.current.blocked("wifi", blocked = false)
        assertTrue(f.online)
        assertEquals(1, f.backend.snapshots)
        assertEquals(listOf(false, true), f.transitions)
    }

    @Test
    fun blockedAndUnblockedUpdatesTheSameNetwork() {
        val f = Fixture()
        f.observer.start()
        f.current.available("wifi")
        f.current.capabilities("wifi", true)
        f.current.blocked("wifi", false)
        f.current.blocked("wifi", true)
        assertFalse(f.online)
        f.current.blocked("wifi", false)
        assertTrue(f.online)
        assertEquals(listOf(false, true, false, true), f.transitions)
    }

    @Test
    fun blockedHandoverNeverPublishesTransientOnline() {
        val f = Fixture()
        f.observer.start()
        f.current.available("old")
        f.current.capabilities("old", true)
        f.current.blocked("old", true)
        f.current.available("new")
        f.current.capabilities("new", true)
        assertFalse(f.online)
        f.current.blocked("new", true)
        assertFalse(f.online)
        assertEquals(listOf(false), f.transitions)
        f.current.blocked("new", false)
        assertTrue(f.online)
    }

    @Test
    fun oldNetworkEventsCannotOverrideNewDefault() {
        val f = Fixture()
        f.observer.start()
        f.current.available("old")
        f.current.blocked("old", false)
        f.current.capabilities("old", true)
        f.current.available("new")
        f.current.capabilities("new", true)
        f.current.blocked("new", false)
        f.current.lost("old")
        f.current.capabilities("old", false)
        assertTrue(f.online)
        f.current.lost("new")
        assertFalse(f.online)
    }

    @Test
    fun unknownSnapshotAndSnapshotFailureAllowRequests() {
        val f = Fixture()
        f.backend.snapshot = InitialNetworkState.UNKNOWN
        f.observer.start()
        assertTrue(f.online)
        f.observer.stop()
        f.backend.snapshotError = true
        f.observer.start()
        assertTrue(f.online)
        assertEquals(listOf("snapshot"), f.failures)
    }

    @Test
    fun registrationFailureFailsOpenAndCanRetry() {
        val f = Fixture()
        f.backend.registrationFailures = 1
        assertFalse(f.observer.start())
        val old = f.current
        assertTrue(f.online)
        assertEquals(listOf("registration"), f.failures)
        assertTrue(f.observer.start())
        assertFalse(f.online)
        val current = f.current
        old.available("wifi")
        old.capabilities("wifi", true)
        old.blocked("wifi", false)
        assertFalse(f.online)
        current.available("wifi")
        current.capabilities("wifi", true)
        current.blocked("wifi", false)
        assertTrue(f.online)
        assertEquals(2, f.backend.snapshots)
    }

    @Test
    fun stopRestartIgnoresPreviousRegistrationCallbacks() {
        val f = Fixture()
        f.observer.start()
        val old = f.current
        f.observer.stop()
        assertEquals(1, f.backend.unregisters)
        f.observer.start()
        val current = f.current
        current.available("wifi")
        current.capabilities("wifi", true)
        current.blocked("wifi", false)
        assertTrue(f.online)
        old.lost("wifi")
        old.blocked("wifi", true)
        assertTrue(f.online)
        current.lost("wifi")
        assertFalse(f.online)
    }

    @Test
    fun capabilitiesCanRemoveAndRestoreInternet() {
        val f = Fixture()
        f.observer.start()
        f.current.available("wifi")
        f.current.capabilities("wifi", true)
        f.current.blocked("wifi", false)
        assertTrue(f.online)
        f.current.capabilities("wifi", false)
        assertFalse(f.online)
        f.current.capabilities("wifi", true)
        assertTrue(f.online)
    }

    @Test
    fun failedUnregistrationIsReportedAndOldCallbackRemainsInactive() {
        val f = Fixture()
        f.observer.start()
        val old = f.current
        f.backend.unregisterFailure = true
        f.observer.stop()
        assertEquals(listOf("unregistration"), f.failures)
        f.backend.unregisterFailure = false
        f.observer.start()
        val current = f.current
        current.available("wifi")
        current.capabilities("wifi", true)
        current.blocked("wifi", false)
        old.lost("wifi")
        assertTrue(f.online)
    }
    @Test
    fun callbacksDuringRegistrationAreNotLost() {
        val f = Fixture()
        f.backend.duringRegistration = { events ->
            events.available("wifi")
            events.capabilities("wifi", true)
            events.blocked("wifi", false)
        }
        f.observer.start()
        assertTrue(f.online)
        assertEquals(listOf(false, true), f.transitions)
    }
}
