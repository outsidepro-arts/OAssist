package ru.outsideproarts.oassist.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectPositionsTest {

    private fun node(
        index: Int,
        centerY: Int,
        clickable: Boolean = false,
        label: String? = null,
        hasActions: Boolean = false,
    ): UiNode =
        UiNode(
            index = index,
            className = "View",
            text = label,
            contentDescription = null,
            clickable = clickable,
            editable = false,
            scrollable = false,
            bounds = UiRect(0, centerY - 10, 100, centerY + 10),
            hasActions = hasActions,
        )

    @Test
    fun underLimit_returnsAllInOrder() {
        val nodes = List(5) { node(it, it * 10) }
        assertEquals(listOf(0, 1, 2, 3, 4), selectPositions(nodes, 5))
    }

    @Test
    fun zeroLimit_returnsEmpty() {
        val nodes = List(5) { node(it, it * 10) }
        assertTrue(selectPositions(nodes, 0).isEmpty())
    }

    @Test
    fun overLimit_withoutScreen_coversBottomByScore() {
        val nodes = List(20) { i ->
            node(i, centerY = 1900 - i, clickable = i >= 17)
        }
        val shown = selectPositions(nodes, 5, screenHeight = 0)
        assertEquals(5, shown.size)
        assertTrue(shown.containsAll(listOf(17, 18, 19)))
    }

    @Test
    fun overLimit_withScreen_coversAllThirds() {
        val nodes = List(30) { i ->
            val y = when {
                i < 15 -> 100
                i < 25 -> 1000
                else -> 2300
            }
            node(i, centerY = y)
        }
        val shown = selectPositions(nodes, 6, screenHeight = 2400)
        assertEquals(6, shown.size)
        assertTrue(shown.any { it < 15 })
        assertTrue(shown.any { it in 15..24 })
        assertTrue(shown.any { it >= 25 })
    }

    @Test
    fun overLimit_withScreen_prefersActionableWithinBucket() {
        val nodes = List(10) { i ->
            node(i, centerY = 2300, clickable = i >= 8)
        } + List(2) { i -> node(10 + i, centerY = 100) }
        val shown = selectPositions(nodes, 3, screenHeight = 2400)
        assertEquals(3, shown.size)
        assertTrue(shown.contains(8))
        assertTrue(shown.any { it >= 10 })
    }

    @Test
    fun overLimit_prefersHasActions_likeActionable() {
        val nodes = List(10) { i ->
            node(i, centerY = 2300, hasActions = i >= 8)
        } + List(2) { i -> node(10 + i, centerY = 100) }
        val shown = selectPositions(nodes, 3, screenHeight = 2400)
        assertEquals(3, shown.size)
        assertTrue(shown.contains(8))
    }
}
