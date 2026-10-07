package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.ExaminableKind
import dev.kotlinds.pokemonclient.state.FieldExaminable
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.ObstacleKind
import dev.kotlinds.pokemonclient.state.PuzzleKind
import dev.kotlinds.pokemonclient.state.PuzzleState
import dev.kotlinds.pokemonclient.state.PuzzleTeleport
import dev.kotlinds.pokemonclient.state.PuzzleTile
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TeleportKind
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.LiveObject
import dev.kotlinds.pokemonclient.world.NeedsMechanism
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.PuzzleMechanism
import dev.kotlinds.pokemonclient.world.RouteOptions
import dev.kotlinds.pokemonclient.world.Sign
import dev.kotlinds.pokemonclient.world.SignKind
import dev.kotlinds.pokemonclient.world.TeleportLink
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.WorldSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.kotlinds.pokemonclient.state.MapName

/**
 * [ActionSettings.solvePuzzles] off: walks only walk ([PuzzleSolving.walkOnly]) and a way needing a mechanism fails
 * with [NeedsMechanism] ([UnavailableReason.PUZZLE_LEFT_TO_AGENT]); [ActionSettings.revealHidden] off: hidden items
 * aren't targets.
 */
class PuzzleSolvingTest {

    /** `.` floor, `#` wall, `_` ice. */
    private fun area(vararg rows: String, signs: List<Sign> = emptyList()): Area {
        val width = rows.maxOf { it.length }
        return Area(1, "test", 0, 0, width, rows.size, Array(width * rows.size) { i ->
            when (rows[i / width].getOrElse(i % width) { '#' }) {
                '#' -> TileInfo(true, TileKind.Wall)
                '_' -> TileInfo(false, TileKind.Ice)
                else -> TileInfo(false, TileKind.Floor)
            }
        }, signs = signs)
    }

    private fun field(x: Int, y: Int, puzzle: PuzzleState? = null, objects: List<FieldObject> = emptyList()) =
        FieldState(1, MapName(1, map = "test"), x, y, 0, Direction.SOUTH, MovementMode.WALK, moving = false, objects = objects, puzzle = puzzle)

    /** A lift at 2,1 between the two halves of a corridor walled in the middle: the only way east. */
    private val liftRoom = area(
        "#####",
        "..L..",
        "#####",
    )
    private val lift = PuzzleState(
        PuzzleKind.LIFT, "rule",
        teleports = listOf(PuzzleTeleport("lift:up", TeleportKind.LIFT, listOf(PuzzleTile(2, 1)), PuzzleTile(2, 1))),
    )

    @Test
    fun walkOnlyDropsTheLiftAndNeverEntersItsTileUnlessItIsTheDestination() {
        val f = field(0, 1, lift)
        val solving = Overlay(teleports = listOf(TeleportLink(2, 1, 2, 1)))
        val walking = PuzzleSolving.walkOnly(solving, f)
        assertTrue(walking.teleports.isEmpty())
        assertEquals(setOf(2 to 1), walking.forbiddenTiles)
        assertIs<Pathfinder.Result.Failed>(Pathfinder(liftRoom, walking).route(Node(0, 1)) { it.x == 4 && it.y == 1 })
        // Asked for explicitly (the destination): the agent's act, allowed.
        assertIs<Pathfinder.Result.Found>(Pathfinder(liftRoom, walking).route(Node(0, 1), goalTiles = setOf(2 to 1)) { it.x == 2 && it.y == 1 })
        // The mechanism that would open the way: the lift, entered from 1,1 going east.
        val needs = PuzzleSolving.diagnose(liftRoom, f, solving, Node(0, 1), RouteOptions(), emptySet()) { it.x == 4 && it.y == 1 }
        assertEquals(NeedsMechanism(PuzzleMechanism.LIFT, 2, 1, Node(1, 1), Direction.EAST), needs)
        assertTrue("2,1" in PuzzleSolving.hint(needs!!, f))
    }

    @Test
    fun aBoulderInTheOnlyWayIsNamedWithWhereToPushItFrom() {
        // The boulder at 2,1 blocks the corridor; pushed east it leaves the way north through 2,0 open.
        val map = area(
            "##.##",
            ".....",
            "#####",
        )
        val f = field(0, 1, objects = listOf(FieldObject("person:4", "boulder", FieldObjectKind.OBSTACLE, 2, 1, null, obstacle = ObstacleKind.BOULDER)))
        val overlay = Overlay(listOf(LiveObject(2, 1, null, clearedBy = FieldMoveKind.STRENGTH)))
        val options = RouteOptions(fieldMoves = setOf(FieldMoveKind.STRENGTH))
        val needs = PuzzleSolving.diagnose(map, f, overlay, Node(0, 1), options, emptySet()) { it.x == 2 && it.y == 0 }
        assertEquals(NeedsMechanism(PuzzleMechanism.STRENGTH_BOULDER, 2, 1, Node(1, 1), Direction.EAST, 3 to 1), needs)
        val hint = PuzzleSolving.hint(needs!!, f)
        // The agent's one call for it: `push` with the boulder and the direction (it walks to 1,1 itself).
        assertTrue("push with boulder person:4 and direction east" in hint && "1,1" in hint, hint)
    }

    @Test
    fun slidesIntoAnIceBlockAreNotWalkedWhenPushesAreAvoided() {
        // Sliding east on the ice from 0,0 stops against the block at 4,0: that would push it.
        val map = area("_____.")
        val block = LiveObject(4, 0, Direction.SOUTH, iceBlock = true)
        val plain = Overlay(listOf(block))
        assertIs<Pathfinder.Result.Found>(Pathfinder(map, plain).route(Node(0, 0), goalTiles = setOf(3 to 0)) { it.x == 3 && it.y == 0 })
        val walking = PuzzleSolving.walkOnly(plain, field(0, 0))
        assertIs<Pathfinder.Result.Failed>(Pathfinder(map, walking).route(Node(0, 0), goalTiles = setOf(3 to 0)) { it.x == 3 && it.y == 0 })
    }

    @Test
    fun goToFailsWithPuzzleLeftToAgentAndNeverStepsOnTheLift() {
        val game = PuzzleGame(liftRoom, 0, 1, lift)
        val off = game.context(ActionSettings(solvePuzzles = false))
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(4, 1, null), off))
        val error = assertIs<ActionError.Unavailable>(failed.error)
        assertEquals(UnavailableReason.PUZZLE_LEFT_TO_AGENT, error.reason)
        assertTrue("lift" in error.hint.orEmpty(), error.toString())
        assertFalse((2 to 1) in game.visited)
    }

    /**
     * A puzzle's pad jumps the player across the map like a warp would ([WarpWatch] sees a jump), but it is a ride of
     * the puzzle ([PuzzleSolving.isRide]): `go_to teleport:N` arrives where it leads, it isn't "stopped on the way".
     */
    @Test
    fun aPadAskedForIsArrivedAtNotAWarpOnTheWay() {
        val room = area("......")
        val pad = PuzzleState(PuzzleKind.TELEPORT_PADS, "rule", teleports = listOf(PuzzleTeleport("teleport:0", TeleportKind.PAD, listOf(PuzzleTile(2, 0)), PuzzleTile(5, 0))))
        val game = PuzzleGame(room, 0, 0, pad, pads = mapOf((2 to 0) to (5 to 0)))
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "teleport:0"), game.context()))
        val said = done.detail.orEmpty()
        assertFalse("stopped on the way" in said || "took" in said, said)
        assertEquals(5 to 0, game.x to game.y)
        assertEquals(listOf(0 to 0, 1 to 0, 2 to 0, 5 to 0), game.visited)
    }

    @Test
    fun hiddenItemsAreTargetsOnlyWhenRevealed() {
        val map = area("....", signs = listOf(Sign(1, 3, 3, 0, 8001, SignKind.HIDDEN_ITEM, 801)))
        val game = PuzzleGame(map, 0, 0, null)
        val hidden = game.context(ActionSettings(revealHidden = false))
        assertNull(MovePlans.resolve(hidden, "hidden_item:3", null, null))
        assertNull(MovePlans.resolve(hidden, "sign:3", null, null))
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, "hidden_item:3"), hidden))
        assertFalse("hidden_item:3" in assertIs<ActionError.InvalidParameter>(failed.error).allowed)
        val revealed = game.context()
        assertEquals(3 to 0, MovePlans.resolve(revealed, "hidden_item:3", null, null)?.let { it.x to it.y })
    }

    /** The Cerulean Gym's Machine Part (an invisible object): a target only when revealed, and a wall either way. */
    @Test
    fun invisibleExaminablesAreTargetsOnlyWhenRevealedAndBlockTheirTile() {
        val map = area("....", "....")
        val part = FieldExaminable("examine:8", "Machine Part", ExaminableKind.ITEM, 2, 1)
        val game = PuzzleGame(map, 0, 0, null, listOf(part))
        val hidden = game.context(ActionSettings(revealHidden = false))
        assertNull(MovePlans.resolve(hidden, "examine:8", null, null))
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.interact.run(GameAction.Interact("examine:8"), hidden))
        assertFalse("examine:8" in assertIs<ActionError.InvalidParameter>(failed.error).allowed)
        val revealed = game.context()
        val target = assertIs<MovePlans.Target>(MovePlans.resolve(revealed, "examine:8", null, null))
        assertEquals(2 to 1, target.x to target.y)
        // Examined from a tile next to it, never from the tile itself.
        assertTrue(target.isGoal!!(Node(2, 0)) && !target.isGoal!!(Node(2, 1)) && !target.isGoal!!(Node(0, 0)))
        // A cue makes it known without a walkthrough.
        val cued = PuzzleGame(map, 0, 0, null, listOf(part.copy(cue = true)))
        assertEquals(2 to 1, MovePlans.resolve(cued.context(ActionSettings(revealHidden = false)), "examine:8", null, null)?.let { it.x to it.y })
        // Its tile is a wall: the walk to 3,1 goes round it by the top row.
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(3, 1, null), revealed))
        assertFalse((2 to 1) in game.visited, game.visited.toString())
    }
}

/** A field ([GridGame]) where holding a direction moves the player one tile every few frames (walls block), with a [puzzle]. */
private class PuzzleGame(
    val area: Area,
    x: Int,
    y: Int,
    val puzzle: PuzzleState?,
    val examinables: List<FieldExaminable> = emptyList(),
    /** The puzzle's pads, as the game runs them: stepping onto the key moves the player to the value at once. */
    val pads: Map<Pair<Int, Int>, Pair<Int, Int>> = emptyMap(),
) : GridGame(x, y) {
    override val name = "Puzzle"
    override val world = object : WorldSource {
        override fun areaOf(zoneId: Int) = area
    }
    override fun state(memory: Memory): GameState {
        val field = FieldState(1, MapName(1, map = "test"), x, y, 0, facing, MovementMode.WALK, moving = false, puzzle = puzzle, examinables = examinables)
        return GameState(0, Screen.Overworld(null, Awaiting.INPUT), null, emptyList(), null, null, field)
    }

    override fun step(direction: Direction) {
        if (area.tile(x + direction.dx, y + direction.dy)?.blocked == false) moveTo(x + direction.dx, y + direction.dy)
        pads[x to y]?.let { (toX, toY) -> moveTo(toX, toY) }
    }
}
