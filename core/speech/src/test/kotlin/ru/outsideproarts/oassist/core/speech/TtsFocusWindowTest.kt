package ru.outsideproarts.oassist.core.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsFocusWindowTest {

    private var requests = 0
    private var abandons = 0
    private val window = TtsFocusWindow(
        requestFocus = { requests += 1 },
        abandonFocus = { abandons += 1 },
    )

    @Test
    fun firstUtterance_requestsOnce() {
        window.onUtteranceStarted()
        window.onUtteranceStarted()

        assertEquals(1, requests)
        assertEquals(0, abandons)
    }

    @Test
    fun queueDrained_abandonsOnce() {
        window.onUtteranceStarted()
        window.onUtteranceStarted()

        window.onUtteranceFinished()
        assertEquals(0, abandons)

        window.onUtteranceFinished()
        window.onUtteranceFinished()

        assertEquals(1, abandons)
    }

    @Test
    fun newQueueAfterIdle_requestsAgain() {
        window.onUtteranceStarted()
        window.onUtteranceFinished()

        window.onUtteranceStarted()

        assertEquals(2, requests)
    }

    @Test
    fun reset_releasesImmediately() {
        window.onUtteranceStarted()
        window.onUtteranceStarted()

        window.reset()

        assertEquals(1, abandons)

        window.onUtteranceFinished()
        assertEquals(1, abandons)
    }

    @Test
    fun finishWithoutStart_isNoOp() {
        window.onUtteranceFinished()

        assertEquals(0, abandons)
    }
}
