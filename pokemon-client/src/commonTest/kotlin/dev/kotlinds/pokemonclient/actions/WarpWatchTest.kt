package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Screen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [WarpWatch] on the readings of a real warp (bench, map-randomized HeartGold: Olivine City warp:3 → Lake of Rage):
 * the step onto the warp, a frame in control, the fade, the new map read at 0,0 for one frame, the arrival tile, the
 * walk out of it, the control back; then a second warp taken from there is another one.
 */
class WarpWatchTest {

    private fun state(map: Int, x: Int, y: Int, awaiting: Awaiting = Awaiting.ANIMATION, moving: Boolean = false, frame: Long = 0, screen: Screen? = null) = GameState(
        frame, screen ?: Screen.Overworld(null, awaiting), null, emptyList(), null, null,
        FieldState(map, MapName(map, map = "Map $map"), x, y, 0, null, MovementMode.WALK, moving = moving),
    )

    private val fade = Screen.Animation(AnimationKind.TRANSITION)

    /**
     * A Rock Climb up a wall of 4 tiles read every 10 frames (a wait during the field move's animation): the player
     * moves 5 tiles between two readings on the same map, without the game's transition: no warp (bench: the climb's
     * cut-in fades before its message, the message and the climb itself come after).
     */
    @Test
    fun aLongClimbReadEveryFewFramesIsNoWarp() {
        val watch = WarpWatch { null }
        watch.observe(state(464, 13, 3, Awaiting.INPUT, frame = 100))
        val mark = watch.mark()
        watch.observe(state(464, 13, 3, frame = 110, screen = fade))
        watch.observe(state(464, 13, 3, frame = 120, screen = Screen.Dialogue(dev.kotlinds.pokemonclient.state.TextSource.FIELD, null, "TYPHLOSION used Rock Climb!", Awaiting.INPUT)))
        watch.observe(state(464, 13, 3, frame = 130))
        watch.observe(state(464, 13, 8, frame = 140))
        watch.observe(state(464, 13, 8, Awaiting.INPUT, frame = 150))
        assertNull(watch.since(mark))
    }

    /** A warp pad to the same map read every 10 frames: its fade comes before the jump, it is a warp. */
    @Test
    fun aJumpAfterTheGamesTransitionIsAWarp() {
        val watch = WarpWatch { null }
        watch.observe(state(410, 18, 22, Awaiting.INPUT, frame = 100))
        val mark = watch.mark()
        watch.observe(state(410, 18, 22, frame = 110, screen = fade))
        watch.observe(state(410, 28, 25, frame = 120))
        watch.observe(state(410, 28, 25, Awaiting.INPUT, frame = 130))
        assertEquals(28 to 25, watch.since(mark)!!.let { it.to.x to it.to.y })
    }

    /** A second warp before the control came back (its own fade after the first arrival) is told, not merged. */
    @Test
    fun aSecondWarpBeforeTheControlIsBackIsChained() {
        val watch = WarpWatch { null }
        watch.observe(state(77, 301, 263, Awaiting.INPUT, frame = 1))
        val mark = watch.mark()
        watch.observe(state(88, 0, 0, frame = 2))
        watch.observe(state(88, 536, 89, frame = 3))
        watch.observe(state(88, 536, 89, frame = 4, screen = fade))
        watch.observe(state(99, 5, 5, frame = 5))
        watch.observe(state(99, 5, 5, Awaiting.INPUT, frame = 6))
        val first = watch.since(mark)!!
        assertEquals(88 to (536 to 89), first.to.mapId to (first.to.x to first.to.y))
        assertEquals(99, first.next!!.to.mapId)
        assertEquals(99, first.last.to.mapId)
        assertEquals(1, watch.mark() - mark, "one movement, one warp chain")
    }

    @Test
    fun theJumpsOfOneWarpAreOneWarpUntilTheControlIsBack() {
        val watch = WarpWatch { null }
        watch.observe(state(77, 301, 264, Awaiting.INPUT))
        val mark = watch.mark()
        watch.observe(state(77, 301, 263, moving = true))
        watch.observe(state(77, 301, 263, Awaiting.INPUT))
        assertNull(watch.since(mark))
        watch.observe(state(88, 0, 0))
        watch.observe(state(88, 536, 89))
        watch.observe(state(88, 536, 90, moving = true))
        watch.observe(state(88, 536, 90, Awaiting.INPUT))
        val first = watch.since(mark)!!
        assertEquals(Triple(77, 301, 263), Triple(first.from.mapId, first.from.x, first.from.y))
        assertEquals(Triple(88, 536, 90), Triple(first.to.mapId, first.to.x, first.to.y))
        // Back in control: the next jump is another warp, after a new mark.
        val again = watch.mark()
        watch.observe(state(88, 536, 89, moving = true))
        assertNull(watch.since(again))
        watch.observe(state(77, 301, 263))
        assertEquals(77, watch.since(again)!!.to.mapId)
        // The first mark still tells the first warp.
        assertEquals(88, watch.since(mark)!!.to.mapId)
    }
}
