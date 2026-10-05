package dev.kotlinds.pokemonclient.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/** A long action's reports ([ActionProgress]) keep the clock going and say how far it has got. */
class ProgressClockTest {

    @Test
    fun aReportIsProgressAndBecomesTheNote() {
        val time = TestTimeSource()
        val clock = ProgressClock(time)
        clock.progressed("Go! PILOSWINE!")
        time += 25.seconds
        // Surfing for 25 s with no text: each tile reported keeps the call (and a chain's IDLE limit) alive.
        clock.report(ActionProgress("go_to Seafoam Islands 1F", 120, 480, ProgressUnit.TILES, "Route 20"))
        assertEquals(Duration.ZERO, clock.idle)
        assertEquals(2, clock.ticks)
        assertEquals("go_to Seafoam Islands 1F: 120/480 tiles, Route 20", clock.note)
        // A step with nothing to say keeps the note, but the note stays dated (calls started later don't repeat it).
        clock.progressed()
        assertEquals(2, clock.noteTick)
    }

    @Test
    fun aProgressWithoutTotalOrPlaceStillReads() {
        assertEquals("go_to frontier: 3 tiles", ActionProgress("go_to frontier", 3, null, ProgressUnit.TILES, null).text)
    }
}
