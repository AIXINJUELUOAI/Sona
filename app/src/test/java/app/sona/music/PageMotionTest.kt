package app.sona.music

import org.junit.Assert.*
import org.junit.Test

class PageMotionTest {
    @Test fun swipedEdgeHasMoreSpaceWithoutClippingTheOppositeEdge() {
        for (width in listOf(320f, 411f, 600f)) {
            for (direction in listOf(-1, 1)) {
                val frame = pageTransform(width, 8f, 1f, direction)
                val leftGap = width * (1f - frame.scale) / 2f + frame.translationX
                val rightGap = width * (1f - frame.scale) / 2f - frame.translationX
                assertTrue(leftGap >= 8f - .0001f && rightGap >= 8f - .0001f)
                if (direction == 1) assertTrue(leftGap > rightGap)
                else assertTrue(rightGap > leftGap)
            }
        }
    }

    @Test fun eitherEdgeContinuesFromItsPreviewAndExitsCompletelyToTheRight() {
        val width = 411f
        for (direction in listOf(-1, 1)) {
            for (progress in listOf(0f, .2f, .7f, 1f)) {
                val released = pageTransform(width, 8f, progress, direction)
                assertEquals(released, pageTransform(width, 8f, progress, direction, 0f))
                var previous = released.translationX
                for (step in 1..100) {
                    val frame = pageTransform(width, 8f, progress, direction, step / 100f)
                    assertTrue(frame.translationX > previous)
                    assertEquals(released.scale, frame.scale, .0001f)
                    previous = frame.translationX
                }
                val left = previous + width * (1f - released.scale) / 2f
                assertTrue("The whole surface must leave the right edge", left >= width - .0001f)
            }
        }
    }

    @Test fun entranceAndCancelledGesturesEndAtFullSizeInTheCenter() {
        assertEquals(411f, pageTransform(411f, 8f, 0f, 1, 1f).translationX, .0001f)
        for (direction in listOf(-1, 1)) {
            assertEquals(PageTransform(1f, 0f), pageTransform(411f, 8f, 0f, direction))
        }
    }
}
