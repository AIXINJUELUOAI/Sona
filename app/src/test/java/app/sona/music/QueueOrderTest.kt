package app.sona.music

import org.junit.Assert.*
import org.junit.Test

class QueueOrderTest {
    @Test fun displayedShuffleOrderAndSelectionFollowThePlayer() {
        // The player will visit items 2 -> 0 -> 3 -> 1, then stop.
        val next = mapOf(2 to 0, 0 to 3, 3 to 1, 1 to -1)
        val shuffled = queueOrder(4, 2) { next.getValue(it) }
        assertEquals(listOf(2, 0, 3, 1), shuffled)
        assertEquals(1, shuffled.indexOf(0)) // current item 0 is highlighted on row 1
        assertEquals(3, shuffled[2]) // clicking row 2 plays media item 3
        assertEquals(next.getValue(shuffled[1]), shuffled[2])
        assertEquals(listOf(0, 1, 2, 3), queueOrder(4, 0) { if (it == 3) -1 else it + 1 })
    }

    @Test fun emptyAndIncompleteTimelinesDoNotLoseOrDuplicateQueueEntries() {
        assertTrue(queueOrder(0, -1) { error("empty timeline") }.isEmpty())
        assertEquals(listOf(0), queueOrder(1, 0) { -1 })
        assertEquals(listOf(0, 1, 2), queueOrder(3, 2) { 2 })
        assertEquals(listOf(0, 1, 2), queueOrder(3, -1) { -1 })
    }
}
