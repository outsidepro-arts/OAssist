package ru.outsideproarts.oassist.update

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.outsideproarts.oassist.core.model.update.UpdatePreferences

class UpdateStateTest {
    @Test fun initialReadFailureDoesNotLeaveScheduledCheckWaiting() = runTest {
        val state = MutableStateFlow(UpdateState())
        val waiting = async { state.awaitUpdatePreferences() }
        state.value = UpdateState(phase = UpdatePhase.ERROR, failure = UpdateFailure.SETTINGS)
        assertNull(waiting.await())
    }

    @Test fun retryCanLoadPreferencesAfterReadFailure() = runTest {
        val state = MutableStateFlow(UpdateState(phase = UpdatePhase.ERROR, failure = UpdateFailure.SETTINGS))
        assertNull(state.awaitUpdatePreferences())
        state.value = UpdateState(preferences = UpdatePreferences(automaticChecks = false))
        assertEquals(UpdatePreferences(automaticChecks = false), state.awaitUpdatePreferences())
    }
}
