package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.PuzzleKind
import dev.kotlinds.pokemonclient.state.PuzzleState
import dev.kotlinds.pokemonclient.state.PuzzleStepAside
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.StepAsideMove
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.WorldSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Trainers who step one tile aside once beaten (the Cinnabar Gym, [PuzzleState.stepAside]): `go_to` / `interact`
 * walk to the side from which that step keeps the way open (randomizer run: detours after a trainer stepped into the
 * corridor). A simulated room ([GridGame]):
 * ```
 * #######
 * #.....#   y1
 * #.#T#.#   y2: the trainer at 3,2, talked to from 3,1 (north) or 3,3 (south)
 * #.....#   y3
 * ###.###   y4: the only way down
 * #.....#   y5
 * #######
 * ```
 * The trainer steps away from the player along a north-south line: talked to from 3,1 it steps south onto 3,3 and
 * cuts the way down; from 3,3 it steps north onto 3,1, which the ring goes round.
 */
class StepAsideTest {

    private class Room(x: Int, y: Int, val stepper: PuzzleStepAside?) : GridGame(x, y) {
        val rows = listOf("#######", "#.....#", "#.#.#.#", "#.....#", "###.###", "#.....#", "#######")
        val area: Area = Area(1, "room", 0, 0, 7, rows.size, Array(7 * rows.size) { i ->
            if (rows[i / 7][i % 7] == '.') TileInfo(false, TileKind.Floor, listOf(0)) else TileInfo(true, TileKind.Wall)
        })
        override val name = "Room"
        override val world = object : WorldSource {
            override fun areaOf(zoneId: Int) = area.takeIf { zoneId == 1 }
            override val zoneCount get() = 2
        }
        override fun mapName(id: Int) = MapName(id, map = "Room")
        override fun scriptVariable(memory: Memory, id: Int) = 0
        override fun step(direction: Direction) {
            val nx = x + direction.dx
            val ny = y + direction.dy
            if (area.tile(nx, ny)?.blocked == false && !(nx == 3 && ny == 2)) moveTo(nx, ny)
        }
        override fun state(memory: Memory): GameState {
            val trainer = FieldObject("person:1", "trainer", FieldObjectKind.PERSON, 3, 2, Direction.SOUTH)
            val puzzle = stepper?.let { PuzzleState(PuzzleKind.TRAINERS_STEP_ASIDE, "rule", stepAside = listOf(it)) }
            val field = FieldState(1, mapName(1), x, y, 0, facing, MovementMode.WALK, moving = false, objects = listOf(trainer), puzzle = puzzle)
            return GameState(0, Screen.Overworld(null, Awaiting.INPUT), null, emptyList(), null, null, field)
        }
    }

    /** Steps away from the player when talked to from the south (facing north), south otherwise. */
    private fun away(beaten: Boolean = false) = PuzzleStepAside("person:1", beaten, Direction.entries.map { StepAsideMove(it, if (it == Direction.NORTH) Direction.NORTH else Direction.SOUTH) })

    private fun goTo(game: Room): Pair<Int, Int> {
        assertIs<ActionOutcome.Done>(Recipes.COMMON.goTo(GameAction.GoTo(null, null, "person:1"), game.context()))
        return game.x to game.y
    }

    @Test
    fun theTrainerIsTalkedToFromTheSideThatKeepsTheWayOpen() {
        // From 1,1 the north side (3,1) is the nearest; the south one (3,3) keeps the way down open.
        assertEquals(3 to 3, goTo(Room(1, 1, away())))
    }

    @Test
    fun otherwiseTheNearestSideAsBefore() {
        // No step aside known, or the trainer is already beaten: the nearest side.
        assertEquals(3 to 1, goTo(Room(1, 1, null)))
        assertEquals(3 to 1, goTo(Room(1, 1, away(beaten = true))))
        // A step that closes the way whatever the side: the nearest one too (it can't be helped).
        val always = PuzzleStepAside("person:1", false, Direction.entries.map { StepAsideMove(it, Direction.SOUTH) })
        assertEquals(3 to 1, goTo(Room(1, 1, always)))
    }
}
