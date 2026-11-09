package com.physicalactivitygate.exercise

import org.junit.Assert.assertEquals
import org.junit.Test

class ExerciseDetectorTest {
    @Test fun countsCompleteSquat() {
        val d = SquatDetector()
        d.update(170f, 0); d.update(130f, 600); d.update(100f, 900); d.update(130f, 1200); d.update(170f, 1500)
        assertEquals(1, d.count)
    }
    @Test fun ignoresPartialSquat() {
        val d = SquatDetector()
        d.update(170f, 0); d.update(140f, 600); d.update(170f, 1200)
        assertEquals(0, d.count)
    }
    @Test fun countsCompletePushUp() {
        val d = PushUpDetector()
        d.update(170f, 0); d.update(110f, 600); d.update(80f, 900); d.update(110f, 1200); d.update(170f, 1500)
        assertEquals(1, d.count)
    }
    @Test fun countsCompleteJumpingJack() {
        val d = JumpingJackDetector()
        d.update(false, false, 0); d.update(true, true, 100); d.update(false, false, 800)
        assertEquals(1, d.count)
    }
    @Test fun plankCountsElapsedSeconds() {
        val d = PlankDetector()
        d.update(true, 0); d.update(true, 3100)
        assertEquals(3, d.seconds)
    }

    @Test fun rejectsTooFastSquat() {
    val d = SquatDetector()
    d.update(170f, 0); d.update(100f, 100); d.update(130f, 200); d.update(170f, 300)
    assertEquals(0, d.count)
}

    @Test fun rejectsStalledJumpingJack() {
    val d = JumpingJackDetector()
    d.update(true, true, 0); d.update(false, false, 5_000)
    assertEquals(0, d.count)
}
}
