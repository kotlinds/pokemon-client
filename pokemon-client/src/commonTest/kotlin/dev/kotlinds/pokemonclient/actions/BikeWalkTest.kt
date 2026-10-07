package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.ConsolePort
import dev.kotlinds.pokemonclient.console.Frame
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.MemoryRegion
import dev.kotlinds.pokemonclient.console.Platform
import dev.kotlinds.pokemonclient.runtime.ActionScope
import dev.kotlinds.pokemonclient.runtime.InputProbe
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.world.Node
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A bicycle on a straight road, as measured on the bench (HeartGold, DeSmuME, New Bark Town): holding a direction, the
 * tiles take [TILE_FRAMES] frames each (the first one with the start, then picking up speed); the game reads the next
 * step ahead, so once the direction is let go the bike rides on: not at all after its first tile, one tile after its
 * second, two once it picked up speed.
 */
private class BikeRoad(var x: Int) : PokemonGame {
    /** Every tile the player stood on, in order. */
    val visited = mutableListOf(x)

    private var held: Set<Button> = emptySet()
    private var direction: Direction? = null
    private var tiles = 0
    private var frames = 0
    private var coast = 0

    override val name = "Bike road"
    override val inputProbe = InputProbe { held }
    override val recipes = Recipes()
    override fun state(memory: Memory): GameState {
        val moving = direction != null
        val field = FieldState(1, MapName(1, map = "Road"), x, 0, 0, Direction.EAST, MovementMode.BIKE, moving = moving)
        return GameState(0, Screen.Overworld(null, if (moving) Awaiting.ANIMATION else Awaiting.INPUT), null, emptyList(), null, null, field)
    }

    private fun move(d: Direction) {
        x += d.dx
        visited += x
    }

    val console: ConsolePort = object : ConsolePort {
        override val platform = Platform.NINTENDO_DS
        override var frame = 0L
        override val revision get() = frame
        override fun step(frames: Int, input: InputFrame) = repeat(frames) {
            frame++
            held = input.buttons
            val pressed = Direction.entries.firstOrNull { it.button in input.buttons }
            val going = direction
            if (going == null) {
                if (pressed == null) return@repeat
                direction = pressed
                tiles = 0
                this@BikeRoad.frames = 0
                coast = 0
                return@repeat
            }
            if (pressed == null && coast == 0) {
                coast = when (tiles) {
                    1 -> 0
                    2 -> 1
                    else -> 2
                }
                if (coast == 0) {
                    direction = null
                    return@repeat
                }
            }
            if (++this@BikeRoad.frames < TILE_FRAMES.getOrElse(tiles) { TILE_FRAMES.last() }) return@repeat
            this@BikeRoad.frames = 0
            move(going)
            tiles++
            if (coast > 0 && --coast == 0) direction = null
        }
        override fun memorySize(region: MemoryRegion) = 16
        override fun read(region: MemoryRegion, offset: Int, length: Int, into: ByteArray) = Unit
        override fun framebuffer(): Frame? = null
        override fun saveState() = ByteArray(0)
        override fun loadState(state: ByteArray) = true
    }

    fun context() = PlanContext(ActionScope(console, inputProbe), this)

    companion object {
        val TILE_FRAMES = listOf(7, 12, 8, 6, 4)
    }
}

/**
 * The bicycle's walker lets go where the bike's speed carries it to the last tile ([WalkSegments.bikeLetsGo]): never
 * past the destination, whatever the ride's length (todo "Vélo": rides of 4 tiles or more went one tile past the end
 * and came back, NOTES Codex too).
 */
class BikeWalkTest {

    @Test
    fun aRideNeverGoesPastItsLastTile() {
        for (length in 1..12) {
            val road = BikeRoad(0)
            val tiles = (1..length).map { Node(it, 0) }
            val walked = WalkSegments.walk(road.context(), WalkSegments.line(Direction.EAST, tiles, { _, _ -> false }, 0))
            assertIs<WalkSegments.Result.Reached>(walked, "$length tiles: ${road.visited}")
            assertEquals(length, road.x, "$length tiles: ${road.visited}")
            assertTrue(road.visited.all { it <= length }, "$length tiles: ${road.visited}")
        }
    }
}
