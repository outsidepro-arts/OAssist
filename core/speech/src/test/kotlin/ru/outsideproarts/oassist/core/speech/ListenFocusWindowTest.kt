package ru.outsideproarts.oassist.core.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class ListenFocusWindowTest {

    private var requests = 0
    private var abandons = 0
    private val window = ListenFocusWindow(
        requestFocus = { requests += 1 },
        abandonFocus = { abandons += 1 },
    )

    @Test
    fun openTwice_requestsOnce() {
        window.open()
        window.open()

        assertEquals(1, requests)
        assertEquals(0, abandons)
    }

    @Test
    fun closeTwice_abandonsOnce() {
        window.open()

        window.close()
        window.close()

        assertEquals(1, abandons)
    }

    @Test
    fun reopenAfterClose_requestsAgain() {
        window.open()
        window.close()

        window.open()

        assertEquals(2, requests)
    }

    @Test
    fun closeWithoutOpen_isNoOp() {
        window.close()

        assertEquals(0, abandons)
    }
}
