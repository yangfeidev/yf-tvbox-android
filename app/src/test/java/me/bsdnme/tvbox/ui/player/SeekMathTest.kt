package me.bsdnme.tvbox.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekMathTest {
    @Test fun swipeMapsDirectionAndClampsToVideo() {
        assertEquals(140_000, swipeSeekTarget(100_000, 200f, 600, 360_000))
        assertEquals(60_000, swipeSeekTarget(100_000, -200f, 600, 360_000))
        assertEquals(0, swipeSeekTarget(5_000, -600f, 600, 360_000))
        assertEquals(360_000, swipeSeekTarget(350_000, 600f, 600, 360_000))
    }
    @Test fun swipeHasBoundedSensitivityAndHandlesInvalidSize() {
        assertEquals(600_000, swipeSeekTarget(0, 1000f, 1000, 7_200_000))
        assertEquals(30_000, swipeSeekTarget(30_000, 10f, 0, 60_000))
    }
    @Test fun remoteStepsAccelerateAndClamp() {
        assertEquals(40_000, remoteSeekTarget(30_000, 1, 0, 100_000))
        assertEquals(50_000, remoteSeekTarget(30_000, 1, 8, 100_000))
        assertEquals(60_000, remoteSeekTarget(30_000, 1, 20, 100_000))
        assertEquals(0, remoteSeekTarget(10_000, -1, 20, 100_000))
        assertEquals(100_000, remoteSeekTarget(90_000, 1, 20, 100_000))
    }
}
