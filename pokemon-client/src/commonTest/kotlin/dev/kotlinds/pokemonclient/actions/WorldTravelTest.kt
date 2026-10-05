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
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.Trigger
import dev.kotlinds.pokemonclient.world.TriggerWarp
import dev.kotlinds.pokemonclient.world.Warp
import dev.kotlinds.pokemonclient.world.WorldSource
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A simulated dungeon of several floors (one area per zone, or one area shared by outdoor zones), like the real
 * game: holding a direction for [STEP_FRAMES] frames moves the player one tile; stepping on a door or a hole, or
 * pressing the exit direction on a ladder, moves the player to the other floor only after a transition of
 * [transitionFrames] frames (a fade), during which the player doesn't move; stepping on an active scene trigger opens
 * a dialogue.
 */
private class FloorsGame(
    val areas: Map<Int, Area>,
    var zone: Int,
    var x: Int,
    var y: Int,
    val transitionFrames: Int = 60,
    val people: Map<Int, List<FieldObject>> = emptyMap(),
    /** A scene that pushes the player back one tile west (no message), like the S.S. Aqua's B1F guard. */
    val pushBack: Boolean = false,
) : PokemonGame {
    var facing = Direction.SOUTH
    var scene = false

    /** Frames left of the push-back scene (the player can't move meanwhile); how many times it ran. */
    private var busy = 0
    var pushes = 0
    private var heldFor = 0
    private var held: Set<Button> = emptySet()
    private var pending: Triple<Int, Int, Int>? = null
    private var pendingIn = 0
    val zonesVisited = mutableListOf(zone)

    override val name = "Floors"
    override val world = object : WorldSource {
        override fun areaOf(zoneId: Int) = areas[zoneId]
        override val zoneCount get() = maxOf(10, areas.keys.max() + 1)
    }
    override fun zoneName(id: Int) = "Floor $id"
    override fun scriptVariable(memory: Memory, id: Int) = 0
    override val inputProbe = InputProbe { held }
    override fun state(memory: Memory): GameState {
        val field = FieldState(zone, "Floor $zone", x, y, 0, facing, MovementMode.WALK, moving = false, objects = people[zone].orEmpty())
        val screen = if (scene) Screen.Dialogue(TextSource.FIELD, null, "A scene!", Awaiting.INPUT)
        else Screen.Overworld(null, if (busy > 0) Awaiting.ANIMATION else Awaiting.INPUT)
        return GameState(0, screen, null, emptyList(), null, null, field)
    }

    private fun area() = areas.getValue(zone)

    private fun arrive(toZone: Int, warp: Int) {
        val target = areas.getValue(toZone).warps.first { it.zone == toZone && it.id == warp }
        schedule(toZone, target.x, target.y)
    }

    private fun schedule(toZone: Int, toX: Int, toY: Int) {
        pending = Triple(toZone, toX, toY)
        pendingIn = transitionFrames
    }

    val console = object : ConsolePort {
        override val platform = Platform.NINTENDO_DS
        override var frame = 0L
        override val revision get() = frame
        override fun step(frames: Int, input: InputFrame) = repeat(frames) {
            frame++
            held = input.buttons
            pending?.let { (z, tx, ty) ->
                if (--pendingIn <= 0) {
                    zone = z; x = tx; y = ty; pending = null
                    zonesVisited += z
                }
                return@repeat
            }
            if (busy > 0) {
                if (--busy == 0) x -= 1
                return@repeat
            }
            val direction = DIRECTIONS.entries.firstOrNull { it.key in input.buttons }?.value
            if (direction == null || scene) {
                heldFor = 0
                return@repeat
            }
            facing = direction
            if (++heldFor < STEP_FRAMES) return@repeat
            heldFor = 0
            val here = area()
            // A ladder / exit mat: pressing its direction takes it.
            here.warps.firstOrNull { it.zone == zone && it.x == x && it.y == y && it.exitDirection == direction }?.let {
                arrive(it.targetZone, it.targetWarp)
                return@repeat
            }
            val nx = x + direction.dx
            val ny = y + direction.dy
            val tile = here.tile(nx, ny) ?: return@repeat
            val free = (!tile.blocked || tile.kind == TileKind.Door) && people[zone].orEmpty().none { it.x == nx && it.y == ny }
            if (!free) return@repeat
            x = nx
            y = ny
            here.warps.firstOrNull { it.zone == zone && it.x == x && it.y == y && it.exitDirection == null }?.let { arrive(it.targetZone, it.targetWarp) }
            here.triggerWarps.firstOrNull { it.zone == zone && it.x == x && it.y == y }?.let { schedule(it.targetZone, it.toX, it.toY) }
            if (here.triggers.any { it.zone == zone && it.x == x && it.y == y } && here.triggerWarps.none { it.x == x && it.y == y }) {
                if (pushBack) { busy = PUSH_FRAMES; pushes++ } else scene = true
            }
            here.zoneAt(x, y)?.let { if (it != zone) { zone = it; zonesVisited += it } }
        }
        override fun memorySize(region: MemoryRegion) = 16
        override fun read(region: MemoryRegion, offset: Int, length: Int, into: ByteArray) = Unit
        override fun framebuffer(): Frame? = null
        override fun saveState() = ByteArray(0)
        override fun loadState(state: ByteArray) = true
    }

    fun context(onProgress: (dev.kotlinds.pokemonclient.runtime.ActionProgress) -> Unit = {}) =
        PlanContext(ActionScope(console, inputProbe, onProgress = onProgress), this)

    companion object {
        const val STEP_FRAMES = 4
        const val PUSH_FRAMES = 40
        val DIRECTIONS = mapOf(Button.UP to Direction.NORTH, Button.DOWN to Direction.SOUTH, Button.LEFT to Direction.WEST, Button.RIGHT to Direction.EAST)
    }
}

/**
 * An area drawn in ASCII: '.' floor, '#' wall, 'D' a door, 'L' a ladder (taken by pressing north on it), 'O' a hole,
 * 'T' a scene trigger. [zones] gives the zone of each tile for shared areas ('1', '2'... as drawn), else [zone].
 */
private fun floor(
    zone: Int,
    rows: List<String>,
    warps: List<Warp> = emptyList(),
    holes: List<TriggerWarp> = emptyList(),
    triggers: List<Trigger> = emptyList(),
    zones: List<String>? = null,
): Area {
    val width = rows.maxOf { it.length }
    val tiles = Array<TileInfo?>(width * rows.size) { i ->
        when (rows[i / width].getOrElse(i % width) { '#' }) {
            '#' -> TileInfo(true, TileKind.Wall)
            'D' -> TileInfo(true, TileKind.Door)
            else -> TileInfo(false, TileKind.Floor)
        }
    }
    val zoneArray = zones?.let { z -> IntArray(width * rows.size) { i -> z[i / width][i % width].digitToInt() } }
        ?: IntArray(width * rows.size) { zone }
    return Area(zone, "Floor $zone", 0, 0, width, rows.size, tiles, warps = warps, triggers = triggers, zones = zoneArray, triggerWarps = holes)
}

class WorldTravelTest {

    /**
     * Floor 1: the start (0,0) is walled off from the goal (4,0); a ladder at (0,2) leads up to floor 2, which comes
     * back down by a hole at (4,1) landing at (4,1) on floor 1, next to the goal.
     */
    private fun dungeon(transition: Int = 60) = FloorsGame(
        mapOf(
            1 to floor(1, listOf(".#...", ".#...", ".#..."), warps = listOf(Warp(1, 0, 0, 2, 2, 0, Direction.SOUTH))),
            2 to floor(2, listOf(".....", "....."), warps = listOf(Warp(2, 0, 0, 0, 1, 0, Direction.NORTH)), holes = listOf(TriggerWarp(2, 0, 4, 1, 1, 4, 1)), triggers = listOf(Trigger(2, 0, 4, 1, 1, 1, 1, 0x4000, 0))),
        ),
        zone = 1, x = 0, y = 0, transitionFrames = transition,
    )

    @Test
    fun aLongGoToReportsTheTilesWalkedOfThePlannedRouteAndTheCurrentMap() {
        val game = dungeon()
        val reports = mutableListOf<dev.kotlinds.pokemonclient.runtime.ActionProgress>()
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), game.context { reports += it }))
        // Planned from the start (nothing walked yet), with a total.
        val first = reports.first()
        assertEquals(0, first.done)
        assertTrue((first.total ?: 0) > 0, first.toString())
        // Counted tile by tile (a warp or a fall counts as one), never backwards; on the map the player is on.
        assertEquals(reports.map { it.done }.sorted(), reports.map { it.done })
        assertTrue(reports.any { it.place == "Floor 2" })
        val last = reports.last()
        assertEquals("Floor 1", last.place)
        // 2 tiles to the ladder, the ladder, 5 tiles to the hole, the fall, 1 tile to the goal.
        assertEquals(10, last.done)
        assertEquals(last.done, last.total)
        assertEquals("go_to 4,0: 10/10 tiles, Floor 1", last.text)
    }

    @Test
    fun goToGoesThroughALadderAndAHoleWhenTheMapAloneHasNoWay() {
        val game = dungeon()
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), game.context()))
        assertEquals(Triple(1, 4, 0), Triple(game.zone, game.x, game.y), done.detail)
        assertEquals(listOf(1, 2, 1), game.zonesVisited)
        assertTrue("warp:0" in done.detail!! && "hole:0" in done.detail!!, done.detail)
    }

    @Test
    fun aLadderWhoseFadeOutlastsTheStepIsNotATimeout() {
        // The map changes 90 frames after the press: longer than a step's wait (the false TIMEOUT of the notes).
        val game = dungeon(transition = 90)
        game.x = 0; game.y = 1
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "warp:0"), game.context()))
        assertEquals(2, game.zone, done.detail)
    }

    @Test
    fun goToATileOnAnotherFloorByName() {
        val game = dungeon()
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(3, 0, null, map = "Floor 2"), game.context()))
        assertEquals(Triple(2, 3, 0), Triple(game.zone, game.x, game.y))
    }

    @Test
    fun goToAMapByNameStopsOnEnteringIt() {
        val game = dungeon()
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor 2"), game.context()))
        assertEquals(2, game.zone)
    }

    @Test
    fun exitEastCrossesIntoTheNeighbouringZoneOfASharedArea() {
        val outdoor = floor(1, listOf("......", "..#..."), zones = listOf("111222", "111222"))
        val game = FloorsGame(mapOf(1 to outdoor, 2 to outdoor), zone = 1, x = 0, y = 1)
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "exit:east"), game.context()))
        assertEquals(2, game.zone, done.detail)
        assertEquals(3, game.x)
        // By name too, and an unknown direction lists the exits.
        val back = FloorsGame(mapOf(1 to outdoor, 2 to outdoor), zone = 1, x = 0, y = 0)
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor 2"), back.context()))
        assertEquals(2, back.zone)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, "exit:north"), FloorsGame(mapOf(1 to outdoor, 2 to outdoor), 1, 0, 0).context()))
        assertEquals(listOf("exit:east (Floor 2)"), assertIs<ActionError.InvalidParameter>(failed.error).allowed)
    }

    @Test
    fun frontierWalksNextToTheNearestWayOutAwayFromThePlayer() {
        val game = dungeon()
        game.zone = 2; game.x = 0; game.y = 1
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "frontier"), game.context()))
        // The ladder down is next to the player: the hole's surroundings are the frontier; the walk stops before it.
        assertEquals(2, game.zone, done.detail)
        assertTrue(game.x to game.y in setOf(3 to 1, 4 to 0), "${game.x},${game.y}")
    }

    @Test
    fun aSceneTriggerIsWalkedOntoWhenItIsTheDestinationAndTheOutcomeSaysSo() {
        val room = floor(1, listOf("....."), triggers = listOf(Trigger(1, 0, 3, 0, 1, 1, 7, 0x4000, 0)))
        val game = FloorsGame(mapOf(1 to room), zone = 1, x = 0, y = 0)
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(3, 0, null), game.context()))
        assertTrue("started a scene" in done.detail!!, done.detail)
        assertTrue(game.scene)
    }

    @Test
    fun aSceneTriggerInTheOnlyWayIsCrossedAndReported() {
        val corridor = floor(1, listOf("....."), triggers = listOf(Trigger(1, 0, 2, 0, 1, 1, 7, 0x4000, 0)))
        val game = FloorsGame(mapOf(1 to corridor), zone = 1, x = 0, y = 0)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), game.context()))
        val error = assertIs<ActionError.Interrupted>(failed.error)
        assertTrue("scene trigger at 2,0" in error.performed, error.performed)
    }

    /**
     * NOTES.md (Kanto, S.S. Aqua): the B1F guard's trigger pushes the player back on each attempt; go_to went on
     * re-crossing it without saying so. The walk stops at the first crossing and names the trigger.
     */
    @Test
    fun aSceneThatPushesBackStopsTheWalkOnceAndSaysItWasTheOnlyWay() {
        val corridor = floor(1, listOf("....."), triggers = listOf(Trigger(1, 0, 2, 0, 1, 1, 7, 0x4000, 0)))
        val game = FloorsGame(mapOf(1 to corridor), zone = 1, x = 0, y = 0, pushBack = true)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), game.context()))
        val error = assertIs<ActionError.Interrupted>(failed.error)
        assertTrue("wasn't the destination" in error.performed && "trigger:0" in error.performed && "starts again" in error.performed, error.performed)
        assertEquals(1, game.pushes)
        assertEquals(1 to 0, game.x to game.y)
    }

    @Test
    fun aPersonInTheOnlyWayIsNamed() {
        val guard = FieldObject("person:8", "guard", dev.kotlinds.pokemonclient.state.FieldObjectKind.PERSON, 2, 0, Direction.SOUTH)
        val game = FloorsGame(mapOf(1 to floor(1, listOf("....."))), zone = 1, x = 0, y = 0, people = mapOf(1 to listOf(guard)))
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), game.context()))
        val error = assertIs<ActionError.Unavailable>(failed.error)
        assertTrue("person:8" in error.message && "talk to them" in error.message, error.message)
    }

    /**
     * NOTES (Kanto, flying to Pewter City then `go_to` Viridian City): the Route 2 gatehouse's exit mat is on the next
     * zone of the shared outdoor area. Standing on it, the press towards the exit was skipped (the walk compared the
     * map with the zone it set off from), and the trip ended with "didn't take the player anywhere".
     */
    @Test
    fun anExitMatOnTheNextZoneOfTheAreaIsTakenByAPressTowardsTheExit() {
        val outdoor = floor(1, listOf("......", "......"), warps = listOf(Warp(2, 0, 4, 1, 3, 0, Direction.SOUTH)), zones = listOf("111222", "111222"))
        val gatehouse = floor(3, listOf("..."), warps = listOf(Warp(3, 0, 1, 0, 2, 0, Direction.NORTH)))
        val game = FloorsGame(mapOf(1 to outdoor, 2 to outdoor, 3 to gatehouse), zone = 1, x = 0, y = 0)
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor 3"), game.context()))
        assertEquals(listOf(1, 2, 3), game.zonesVisited, done.detail)
    }

    /**
     * A floor walled in two (the player on the left, warp:1 on the right), whose right part is reached only through
     * floors 2 to 9 one after another (9 warps): the Route 20 case of the NOTES, where `go_to warp:1` (the Seafoam
     * Islands entrance on a beach walled off by rocks, "57 east") set off west for a loop through Kanto.
     */
    private fun walledFloor(): FloorsGame {
        val first = floor(1, listOf("..#..."), warps = listOf(Warp(1, 0, 0, 0, 2, 0), Warp(1, 1, 5, 0, 2, 0), Warp(1, 2, 3, 0, 9, 1)))
        val corridors = (2..9).associateWith { k ->
            floor(k, listOf("..."), warps = listOf(Warp(k, 0, 0, 0, k - 1, if (k == 2) 0 else 1), Warp(k, 1, 2, 0, if (k == 9) 1 else k + 1, if (k == 9) 2 else 0)))
        }
        return FloorsGame(mapOf(1 to first) + corridors, zone = 1, x = 1, y = 0, transitionFrames = 10)
    }

    @Test
    fun aTargetOfThisMapReachedOnlyByALongDetourIsRefusedBeforeMoving() {
        val game = walledFloor()
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, "warp:1"), game.context()))
        val error = assertIs<ActionError.Unavailable>(failed.error)
        assertEquals(UnavailableReason.NO_PATH, error.reason)
        assertTrue("warp:1 at 5,0" in error.detail && "9 warps" in error.detail && "Floor 2" in error.detail && "Floor 9" in error.detail, error.detail)
        assertTrue("fly" in error.hint.orEmpty(), error.hint)
        // Nothing done: the agent decides.
        assertEquals(Triple(1, 1, 0), Triple(game.zone, game.x, game.y))
        assertEquals(listOf(1), game.zonesVisited)
    }

    @Test
    fun aFarMapNamedOnPurposeIsStillTaken() {
        val game = walledFloor()
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor 9"), game.context()))
        assertEquals(9, game.zone, done.detail)
        assertEquals((1..9).toList(), game.zonesVisited)
    }

    @Test
    fun aGoToOfAsManyWarpsAsAllowedArrives() {
        // Floors 1..13 in a row (a warp east to the next one, arriving on its west end): 12 warps, the most one go_to
        // takes (Mt. Silver's summit to its Pokémon Center), then the walk on the last floor.
        val floors = (1..13).associateWith { k ->
            floor(k, listOf("..."), warps = listOfNotNull(Warp(k, 0, 0, 0, k - 1, 1).takeIf { k > 1 }, Warp(k, 1, 2, 0, k + 1, 0).takeIf { k < 13 }))
        }
        val game = FloorsGame(floors, zone = 1, x = 1, y = 0, transitionFrames = 10)
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(2, 0, null, map = "Floor 13"), game.context()))
        assertEquals(Triple(13, 2, 0), Triple(game.zone, game.x, game.y), done.detail)
    }

    @Test
    fun aTownCanBeNamedWithOrWithoutTown() {
        assertTrue(WorldTravel.sameMapName("New Bark", "New Bark Town"))
        assertTrue(WorldTravel.sameMapName("Goldenrod City", "goldenrod"))
        assertFalse(WorldTravel.sameMapName("Route 3", "Route 30"))
    }
}

/**
 * `go_to` while destinations are hidden ([ActionSettings.hideDestinations]): targets of the current map only (its
 * warps, holes and exits are taken: that is how the agent explores), walks kept on the player's map, and refusals
 * that never name another map or the way there.
 */
class HiddenDestinationsTravelTest {

    private fun hidden(game: FloorsGame) = PlanContext(ActionScope(game.console, game.inputProbe), game, settings = ActionSettings(hideDestinations = true))

    /** Floor 1's start (0,0) is walled off from (4,0); a ladder at (0,2) leads to floor 2, whose hole falls next to it. */
    private fun dungeon() = FloorsGame(
        mapOf(
            1 to floor(1, listOf(".#...", ".#...", ".#..."), warps = listOf(Warp(1, 0, 0, 2, 2, 0, Direction.SOUTH))),
            2 to floor(2, listOf(".....", "....."), warps = listOf(Warp(2, 0, 0, 0, 1, 0, Direction.NORTH)), holes = listOf(TriggerWarp(2, 0, 4, 1, 1, 4, 1)), triggers = listOf(Trigger(2, 0, 4, 1, 1, 1, 1, 0x4000, 0))),
        ),
        zone = 1, x = 0, y = 0, transitionFrames = 10,
    )

    private fun refusedAsHidden(outcome: ActionOutcome): ActionError.Unavailable {
        val error = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(outcome).error)
        assertEquals(UnavailableReason.DESTINATIONS_HIDDEN, error.reason, error.message)
        return error
    }

    @Test
    fun anotherMapByNameByIdOrWithATileIsRefusedWithoutMoving() {
        val game = dungeon()
        val byName = refusedAsHidden(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor 2"), hidden(game)))
        refusedAsHidden(MovePlans.goTo.run(GameAction.GoTo(null, null, "map:2"), hidden(game)))
        refusedAsHidden(MovePlans.goTo.run(GameAction.GoTo(3, 0, null, map = "Floor 2"), hidden(game)))
        // A name of no map at all gets the same answer: the refusal never tells whether a map exists.
        val nowhere = refusedAsHidden(MovePlans.goTo.run(GameAction.GoTo(null, null, "Atlantis"), hidden(game)))
        assertEquals(byName.hint, nowhere.hint)
        assertEquals(listOf(1), game.zonesVisited)
        assertEquals(0 to 0, game.x to game.y)
        // Nothing about the way there: no warp, no hole, no other map's name.
        listOf("warp:0", "hole:0", "ladder").forEach { assertFalse(it in byName.message, byName.message) }
    }

    @Test
    fun aPlaceOfThisMapReachableOnlyThroughOtherFloorsIsNotRoutedThere() {
        val game = dungeon()
        val error = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), hidden(game))).error)
        assertEquals(UnavailableReason.NO_PATH, error.reason)
        assertEquals(listOf(1), game.zonesVisited)
        assertFalse("Floor 2" in error.message || "warp:0" in error.message || "hole:0" in error.message, error.message)
        assertTrue("destinations are hidden" in error.message, error.message)
    }

    @Test
    fun aWarpOfThisMapIsTakenAndTheAnswerOnlyTellsWhereThePlayerStands() {
        val game = dungeon()
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "warp:0"), hidden(game)))
        assertEquals(2, game.zone, done.detail)
        // The arrival is discovery (the game shows it); the target isn't described by where it leads.
        assertFalse("Floor 2" in done.detail.orEmpty(), done.detail)
    }

    @Test
    fun theMapSelfAndItsTilesStayAllowed() {
        val game = dungeon()
        val here = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor 1"), hidden(game))).error)
        assertEquals(UnavailableReason.NO_PATH, here.reason)
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(0, 1, null, map = "map:1"), hidden(game)))
        assertEquals(Triple(1, 0, 1), Triple(game.zone, game.x, game.y))
    }

    @Test
    fun anExitOfASharedAreaIsTakenButANeighbourTileIsRefusedAndExitsAreListedByIdOnly() {
        val outdoor = floor(1, listOf("......", "..#..."), zones = listOf("111222", "111222"))
        val game = FloorsGame(mapOf(1 to outdoor, 2 to outdoor), zone = 1, x = 0, y = 1)
        refusedAsHidden(MovePlans.goTo.run(GameAction.GoTo(5, 0, null), hidden(game)))
        assertEquals(1, game.zone)
        val wrong = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, "exit:north"), hidden(game)))
        assertEquals(listOf("exit:east"), assertIs<ActionError.InvalidParameter>(wrong.error).allowed)
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "exit:east"), hidden(game)))
        assertEquals(2, game.zone, done.detail)
        assertEquals(3, game.x)
    }

    @Test
    fun walksNeverCrossANeighbouringMapOfTheArea() {
        // Zone 1's two halves (0,0) and (2,0) are joined only through zone 2's row below.
        val outdoor = floor(1, listOf(".#.", "..."), zones = listOf("111", "222"))
        val shown = FloorsGame(mapOf(1 to outdoor, 2 to outdoor), zone = 1, x = 0, y = 0)
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(2, 0, null), shown.context()))
        val game = FloorsGame(mapOf(1 to outdoor, 2 to outdoor), zone = 1, x = 0, y = 0)
        val error = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(2, 0, null), hidden(game))).error)
        assertEquals(UnavailableReason.NO_PATH, error.reason)
        assertEquals(listOf(1), game.zonesVisited)
    }
}
