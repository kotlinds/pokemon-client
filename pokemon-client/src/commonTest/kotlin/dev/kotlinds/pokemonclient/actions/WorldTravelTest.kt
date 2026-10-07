package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Blocker
import dev.kotlinds.pokemonclient.state.SceneTrigger
import dev.kotlinds.pokemonclient.state.StoryState
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
import dev.kotlinds.pokemonclient.world.WarpTrigger
import dev.kotlinds.pokemonclient.world.WorldSource
import dev.kotlinds.pokemonclient.world.Elevator
import dev.kotlinds.pokemonclient.world.ElevatorOperator
import dev.kotlinds.pokemonclient.world.ElevatorStop
import dev.kotlinds.pokemonclient.console.Button
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import dev.kotlinds.pokemonclient.state.MapName

/**
 * A simulated dungeon of several floors (one area per zone, or one area shared by outdoor zones), like the real
 * game ([GridGame]): holding a direction for [GridGame.STEP_FRAMES] frames moves the player one tile; stepping on a door or a hole, or
 * pressing the exit direction on a ladder ([WarpTrigger]), moves the player to the other floor only after a transition of
 * [transitionFrames] frames (a fade), during which the player doesn't move; stepping on an active scene trigger opens
 * a dialogue.
 */
private class FloorsGame(
    val areas: Map<Int, Area>,
    var zone: Int,
    x: Int,
    y: Int,
    val transitionFrames: Int = 60,
    val people: Map<Int, List<FieldObject>> = emptyMap(),
    /** A scene that pushes the player back one tile west (no message), like the S.S. Aqua's B1F guard. */
    val pushBack: Boolean = false,
    /** The maps' names ("Floor N" by default). */
    val names: (Int) -> MapName = { MapName(it, map = "Floor $it") },
    /** Warps the game takes on entering their tile but its maps don't show (what the walk can't foresee). */
    val unmapped: List<Warp> = emptyList(),
    /**
     * Where the game really leaves the player arriving through warp (zone, id), when it isn't the warp's tile and the
     * maps don't say so (a ladder's step off it the map data wouldn't tell).
     */
    val arrivals: Map<Pair<Int, Int>, Pair<Int, Int>> = emptyMap(),
    /** The lifts by room ([WorldSource.elevatorOf]); entering one shows a message (read with A), like the game's. */
    val elevators: Map<Int, Elevator> = emptyMap(),
    /** What the state lists as blocking a way ([StoryState.blockers]): none by default (a game without story blockers). */
    val blockers: List<Blocker> = emptyList(),
) : GridGame(x, y) {
    /** Where each lift was sent: (zone, warp) of the floor its way out leads to now. */
    val liftAt = HashMap<Int, Pair<Int, Int>>()
    var scene = false

    /** Frames left of the push-back scene (the player can't move meanwhile); how many times it ran. */
    private var busy = 0
    var pushes = 0
    private var pending: Triple<Int, Int, Int>? = null
    private var pendingIn = 0
    val zonesVisited = mutableListOf(zone)

    override val name = "Floors"
    override val world = object : WorldSource {
        override fun areaOf(zoneId: Int) = areas[zoneId]
        override val zoneCount get() = maxOf(10, areas.keys.max() + 1)
        override fun elevatorOf(zoneId: Int) = elevators[zoneId]
    }
    override fun mapName(id: Int) = names(id)
    override fun scriptVariable(memory: Memory, id: Int) = 0
    override fun state(memory: Memory): GameState {
        val field = FieldState(zone, names(zone), x, y, 0, facing, MovementMode.WALK, moving = false, objects = people[zone].orEmpty())
        val screen = if (scene) Screen.Dialogue(TextSource.FIELD, null, "A scene!", Awaiting.INPUT)
        // The game is busy during a push-back and a warp's transition (its fade), like the real one.
        else Screen.Overworld(null, if (busy > 0 || pending != null) Awaiting.ANIMATION else Awaiting.INPUT)
        return GameState(0, screen, null, emptyList(), null, null, field, story = blockers.takeIf { it.isNotEmpty() }?.let { StoryState(null, it) })
    }

    private fun area() = areas.getValue(zone)

    private fun arrive(toZone: Int, warp: Int) {
        // A lift's way out: to the floor it was sent to.
        if (toZone == DYNAMIC_ZONE) liftAt[zone]?.let { (z, w) -> return arrive(z, w) }
        // Into a lift that goes by itself: it is sent to the other floor (the one the player didn't come from).
        elevators[toZone]?.takeIf { it.operator == ElevatorOperator.Shuttle }?.let { lift ->
            liftAt[toZone] = lift.stops.first { it.zone != zone }.let { it.zone to it.warp }
        }
        val target = areas.getValue(toZone).warps.first { it.zone == toZone && it.id == warp }
        val (ax, ay) = arrivals[toZone to warp] ?: (target.x to target.y)
        schedule(toZone, ax, ay)
    }

    private fun schedule(toZone: Int, toX: Int, toY: Int) {
        pending = Triple(toZone, toX, toY)
        pendingIn = transitionFrames
    }

    override fun busy(): Boolean {
        pending?.let { (z, tx, ty) ->
            if (--pendingIn <= 0) {
                zone = z; x = tx; y = ty; pending = null
                zonesVisited += z
                // A lift tells where it goes: a message, read with A.
                if (z in elevators) scene = true
            }
            return true
        }
        if (scene && inLift() && Button.A in inputProbe.heldButtons(dev.kotlinds.pokemonclient.ZeroMemory)) {
            scene = false
            return true
        }
        if (busy > 0) {
            if (--busy == 0) x -= 1
            return true
        }
        return scene
    }

    override fun step(direction: Direction) {
        val here = area()
        // A ladder / exit mat: pressing its direction takes it.
        here.warps.firstOrNull { it.zone == zone && it.x == x && it.y == y && it.trigger == WarpTrigger.Press(direction) }?.let {
            arrive(it.targetZone, it.targetWarp)
            return
        }
        val nx = x + direction.dx
        val ny = y + direction.dy
        val tile = here.tile(nx, ny) ?: return
        val free = (!tile.blocked || tile.kind == TileKind.Door) && people[zone].orEmpty().none { it.x == nx && it.y == ny }
        if (!free) return
        moveTo(nx, ny)
        (here.warps + unmapped).firstOrNull { it.zone == zone && it.x == x && it.y == y && it.trigger == WarpTrigger.Enter }?.let { arrive(it.targetZone, it.targetWarp) }
        here.triggerWarps.firstOrNull { it.zone == zone && it.x == x && it.y == y }?.let { schedule(it.targetZone, it.toX, it.toY) }
        if (here.triggers.any { it.zone == zone && it.x == x && it.y == y } && here.triggerWarps.none { it.x == x && it.y == y }) {
            if (pushBack) { busy = PUSH_FRAMES; pushes++ } else scene = true
        }
        here.zoneAt(x, y)?.let { if (it != zone) { zone = it; zonesVisited += it } }
    }

    /** True when the message on screen is a lift's (closed with A), not a scene's. */
    private fun inLift() = zone in elevators

    companion object {
        const val PUSH_FRAMES = 40

        /** The zone of a warp leading where a lift was sent ([dev.kotlinds.pokemonclient.games.gen4.Gen4WorldSource.DYNAMIC_ZONE]). */
        const val DYNAMIC_ZONE = 4095
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
    people: List<dev.kotlinds.pokemonclient.world.PersonTemplate> = emptyList(),
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
    return Area(zone, "Floor $zone", 0, 0, width, rows.size, tiles, warps = warps, people = people, triggers = triggers, zones = zoneArray, triggerWarps = holes)
}

class WorldTravelTest {

    /**
     * Two warps taken the same way, back to back (the shuffled warps' "invisible double warp", NOTES-run-map-randomizer):
     * zone 1's warp at (0,1) leads to (0,2) on zone 2, right below zone 2's warp at (0,1) back to zone 1. Walking north
     * into the first one, the walk stops on the first warp and says so; still holding north, it took the second one and
     * came back where it started, "nothing happened".
     */
    private fun doubleWarp() = FloorsGame(
        mapOf(
            1 to floor(1, listOf(".", ".", "."), warps = listOf(Warp(1, 0, 0, 1, 2, 0), Warp(1, 1, 0, 2, 2, 1, WarpTrigger.Never))),
            2 to floor(2, listOf(".", ".", "."), warps = listOf(Warp(2, 0, 0, 2, 1, 1, WarpTrigger.Never), Warp(2, 1, 0, 1, 1, 1))),
        ),
        zone = 1, x = 0, y = 2,
    )

    @Test
    fun aStepStopsAtTheFirstWarpAndSaysWhichOne() {
        val game = doubleWarp()
        val done = assertIs<ActionOutcome.Done>(MovePlans.step.run(GameAction.Step(Direction.NORTH, 3), game.context()))
        assertEquals(listOf(1, 2), game.zonesVisited)
        assertEquals(Triple(2, 0, 2), Triple(game.zone, game.x, game.y))
        assertEquals("took warp:0 at 0,1 (Floor 1) → Floor 2 (0,2)", done.detail)
    }

    @Test
    fun goToAWarpStopsOnTheFarSideOfIt() {
        val game = doubleWarp()
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "warp:0"), game.context()))
        assertEquals(listOf(1, 2), game.zonesVisited)
        assertEquals("took warp:0 at 0,1 (Floor 1) → Floor 2 (0,2)", done.detail)
    }

    /**
     * A warp the walk didn't plan (the maps don't show it) taken on the way: the walk stops there, the answer says it
     * wasn't the destination and where the player is now.
     */
    @Test
    fun aWarpOnTheWayThatWasntTheDestinationStopsTheWalk() {
        val game = FloorsGame(
            mapOf(1 to floor(1, listOf("...")), 2 to floor(2, listOf("..."), warps = listOf(Warp(2, 0, 2, 0, 1, 0, WarpTrigger.Never)))),
            zone = 1, x = 0, y = 0, unmapped = listOf(Warp(1, 0, 1, 0, 2, 0)),
        )
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(2, 0, null), game.context()))
        assertEquals(Triple(2, 2, 0), Triple(game.zone, game.x, game.y))
        assertEquals(listOf(1, 2), game.zonesVisited)
        assertTrue(done.detail!!.startsWith("stopped on the way: took a warp at 1,0 (Floor 1) → Floor 2 (2,0), which wasn't the destination"), done.detail)
    }

    /**
     * Arrived on a warp taken by entering it (a cave mouth taken going north, NOTES: Mt. Silver 1F warp:2, Route 33
     * w0): pressing does nothing in the game, so go_to steps off and back on.
     */
    @Test
    fun aWarpUnderTheFeetIsTakenBySteppingOffAndBackOn() {
        val game = doubleWarp()
        game.x = 0; game.y = 1
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "warp:0"), game.context()))
        assertEquals(2, game.zone, done.detail)
        assertTrue((0 to 2) in game.visited || (0 to 0) in game.visited, game.visited.toString())
        assertTrue(done.detail!!.startsWith("took warp:0"), done.detail)
    }

    /** A warp nothing takes (its tile has no warp behaviour: an arrival point only) is refused before walking. */
    @Test
    fun aWarpNothingTakesIsRefusedAtOnce() {
        val game = doubleWarp()
        game.x = 0; game.y = 0
        val error = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, "warp:1"), game.context())).error)
        assertTrue("can't be taken" in error.detail, error.detail)
        // Not a step taken.
        assertEquals(1, game.visited.size)
        assertEquals(Triple(1, 0, 0), Triple(game.zone, game.x, game.y))
    }

    /**
     * Arrived below an exit mat taken by pressing south (the doormat of a shuffled door): the room is reached across
     * the mat going north, and the mat itself is taken from there with the press south.
     */
    @Test
    fun theDoormatBehindADoorLeadsBackInAndOut() {
        val mat = Warp(1, 0, 1, 1, 2, 0, WarpTrigger.Press(Direction.SOUTH))
        fun game() = FloorsGame(mapOf(1 to floor(1, listOf("...", "#.#", "#.#"), warps = listOf(mat)), 2 to floor(2, listOf("..."), warps = listOf(Warp(2, 0, 0, 0, 1, 0)))), zone = 1, x = 1, y = 2)
        val inside = game()
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(0, 0, null), inside.context()))
        assertEquals(Triple(1, 0, 0), Triple(inside.zone, inside.x, inside.y))
        val out = game()
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "warp:0"), out.context()))
        assertEquals(2, out.zone, done.detail)
    }

    /**
     * Floor 1: the start (0,0) is walled off from the goal (4,0); a ladder at (0,2) leads up to floor 2, which comes
     * back down by a hole at (4,1) landing at (4,1) on floor 1, next to the goal.
     */
    private fun dungeon(transition: Int = 60, names: (Int) -> MapName = { MapName(it, map = "Floor $it") }) = FloorsGame(
        mapOf(
            1 to floor(1, listOf(".#...", ".#...", ".#..."), warps = listOf(Warp(1, 0, 0, 2, 2, 0, WarpTrigger.Press(Direction.SOUTH)))),
            2 to floor(2, listOf(".....", "....."), warps = listOf(Warp(2, 0, 0, 0, 1, 0, WarpTrigger.Press(Direction.NORTH))), holes = listOf(TriggerWarp(2, 0, 4, 1, 1, 4, 1)), triggers = listOf(Trigger(2, 0, 4, 1, 1, 1, 1, 0x4000, 0))),
        ),
        zone = 1, x = 0, y = 0, transitionFrames = transition, names = names,
    )

    @Test
    fun aLongGoToReportsTheTilesWalkedOfThePlannedRouteAndTheCurrentMap() {
        val game = dungeon()
        val reports = mutableListOf<dev.kotlinds.pokemonclient.runtime.ActionProgress>()
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), game.context(onProgress = { reports += it })))
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

    /**
     * A5 / A6: a map is found by every name the state shows for it ([MapName]): its own name, its display form, its
     * place's name — the place as the game shows it (French: "Célestia"), accents or not ([dev.kotlinds.pokemonclient.state.normalizeName]).
     */
    @Test
    fun goToAMapByAnyOfItsShownNamesAccentsIgnored() {
        val names = { id: Int -> if (id == 2) MapName(2, location = "Célestia", map = "Celestic Town") else MapName(id, map = "Floor $id") }
        for (asked in listOf("Celestia", "célestia", "CELESTIA", "Célestia (Celestic Town)", "Celestic Town", "Celestic", "map:2")) {
            val game = dungeon(names = names)
            assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, asked), game.context()), asked)
            assertEquals(2, game.zone, asked)
        }
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
        assertTrue("wasn't the destination" in error.performed && "trigger:0" in error.performed && "start again" in error.performed, error.performed)
        // The state lists no blocker here: the answer never points to a blocked_by it doesn't carry.
        assertFalse("blocked_by" in error.performed, error.performed)
        assertEquals(1, game.pushes)
        assertEquals(1 to 0, game.x to game.y)
    }

    /** The same scene, listed by the state ([StoryState.blockers], sent at every knowledge level): the answer points there. */
    @Test
    fun aSceneListedAsABlockerPointsToBlockedBy() {
        val corridor = floor(1, listOf("....."), triggers = listOf(Trigger(1, 0, 2, 0, 1, 1, 7, 0x4000, 0)))
        val listed = listOf(Blocker("trigger:0", "A guard sends you back.", scene = SceneTrigger(0, 2..2, 0..0, repeats = true)))
        val game = FloorsGame(mapOf(1 to corridor), zone = 1, x = 0, y = 0, pushBack = true, blockers = listed)
        val error = assertIs<ActionError.Interrupted>(assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), game.context())).error)
        assertTrue("see blocked_by (trigger:0)" in error.performed, error.performed)
        assertEquals(1, game.pushes)
    }

    /**
     * Race notes (Goldenrod Underground's "NO ENTRY" barricade, the Mahogany shop): a story person standing on the warp
     * asked for. The game refuses the step; go_to used to walk next to it and time out ("the game refused 0 steps"),
     * four times in 37 minutes. Refused before any step, naming who stands there.
     */
    @Test
    fun aWarpSomeoneStandsOnIsRefusedBeforeMoving() {
        val barricade = FieldObject("person:5", "barricade", dev.kotlinds.pokemonclient.state.FieldObjectKind.PERSON, 4, 0, Direction.SOUTH)
        val game = FloorsGame(
            mapOf(1 to floor(1, listOf(".....", "....."), warps = listOf(Warp(1, 0, 4, 0, 2, 0))), 2 to floor(2, listOf("..."), warps = listOf(Warp(2, 0, 0, 0, 1, 0, WarpTrigger.Never)))),
            zone = 1, x = 0, y = 0, people = mapOf(1 to listOf(barricade)),
        )
        val error = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, "warp:0"), game.context())).error)
        assertEquals(UnavailableReason.NO_PATH, error.reason)
        assertTrue("blocked_by_person: person:5 (barricade)" in error.message, error.message)
        assertEquals(Triple(1, 0, 0), Triple(game.zone, game.x, game.y), "not a step taken")
        assertEquals(listOf(1), game.zonesVisited)
    }

    /** The case that must keep working: the same warp with nobody on it is taken. */
    @Test
    fun aFreeWarpIsTaken() {
        val bystander = FieldObject("person:5", "bystander", dev.kotlinds.pokemonclient.state.FieldObjectKind.PERSON, 4, 1, Direction.SOUTH)
        val game = FloorsGame(
            mapOf(1 to floor(1, listOf(".....", "....."), warps = listOf(Warp(1, 0, 4, 0, 2, 0))), 2 to floor(2, listOf("..."), warps = listOf(Warp(2, 0, 0, 0, 1, 0, WarpTrigger.Never)))),
            zone = 1, x = 0, y = 0, people = mapOf(1 to listOf(bystander)),
        )
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "warp:0"), game.context()))
        assertEquals(listOf(1, 2), game.zonesVisited)
    }

    /**
     * Race notes (Radio Tower 5F): `go_to x:5 y:12`, the wall above the executive standing at 5,13, answered "not
     * connected", and the agent thought him out of reach for 33 minutes. A tile nobody can stand on is refused before
     * any step, naming who stands next to it and how to reach them.
     */
    @Test
    fun aWallTileAsTheDestinationNamesWhoStandsNextToIt() {
        val executive = FieldObject("person:0", "executive", dev.kotlinds.pokemonclient.state.FieldObjectKind.PERSON, 3, 1, Direction.NORTH)
        val game = FloorsGame(mapOf(1 to floor(1, listOf("...#.", "....."))), zone = 1, x = 0, y = 0, people = mapOf(1 to listOf(executive)))
        val error = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(3, 0, null), game.context())).error)
        assertEquals(UnavailableReason.TARGET_IS_OBSTACLE, error.reason)
        assertTrue("3,0 is a wall" in error.message && "person:0 (executive) at 3,1" in error.message && "go_to person:0" in error.message, error.message)
        // Someone's own tile: named too.
        val taken = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(3, 1, null), game.context())).error)
        assertEquals(UnavailableReason.TARGET_IS_OBSTACLE, taken.reason)
        assertTrue("person:0 (executive) at 3,1 is there" in taken.message, taken.message)
        assertEquals(0 to 0, game.x to game.y)
    }

    /** The case that must keep working: a free tile that no way leads to is still "not connected" (not an obstacle). */
    @Test
    fun aFreeTileNoWayLeadsToIsStillNotConnected() {
        val game = FloorsGame(mapOf(1 to floor(1, listOf("..#.."))), zone = 1, x = 0, y = 0)
        val error = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(4, 0, null), game.context())).error)
        assertEquals(UnavailableReason.NO_PATH, error.reason)
        assertTrue("not connected" in error.message, error.message)
        // A free tile that is reached: walked to.
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(1, 0, null), game.context()))
        assertEquals(1 to 0, game.x to game.y)
    }

    /**
     * NOTES-run-map-randomizer (`go_to "19,23"`), race notes (`"9,26"`, `"17,45"`): a tile written in `target` is
     * refused with the parameters to use (x and y), whether destinations are hidden or not; no alias.
     */
    @Test
    fun coordinatesWrittenInTargetAreRefusedWithXAndY() {
        for (settings in listOf(ActionSettings(), ActionSettings(hideDestinations = true))) {
            val game = FloorsGame(mapOf(1 to floor(1, listOf("....."))), zone = 1, x = 0, y = 0)
            val error = assertIs<ActionError.InvalidParameter>(assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, "4,0"), game.context(settings))).error)
            assertEquals("target", error.parameter)
            assertTrue("x and y (x: 4, y: 0)" in error.message, error.message)
            assertEquals(0 to 0, game.x to game.y)
        }
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
        val outdoor = floor(1, listOf("......", "......"), warps = listOf(Warp(2, 0, 4, 1, 3, 0, WarpTrigger.Press(Direction.SOUTH))), zones = listOf("111222", "111222"))
        val gatehouse = floor(3, listOf("..."), warps = listOf(Warp(3, 0, 1, 0, 2, 0, WarpTrigger.Press(Direction.NORTH))))
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
        assertTrue("warp:1 at 5,0" in error.detail && "through 9 warps" in error.detail && "Floor 2" in error.detail && "Floor 9" in error.detail, error.detail)
        assertTrue(Regex("a loop of \\d+ steps") in error.detail, error.detail)
        // The opt-in is named, in the refusal and in its hint.
        assertTrue("on_local_detour: go" in error.detail && "on_local_detour: go" in error.hint.orEmpty(), error.hint)
        assertTrue("fly" in error.hint.orEmpty(), error.hint)
        // Nothing done: the agent decides.
        assertEquals(Triple(1, 1, 0), Triple(game.zone, game.x, game.y))
        assertEquals(listOf(1), game.zonesVisited)
    }

    /** The same loop with on_local_detour go (the agent's opt-in): taken, through floors 2 to 9, then warp:1 itself. */
    @Test
    fun aLoopToATargetOfThisMapIsTakenWhenTheAgentSaysGo() {
        val game = walledFloor()
        val outcome = MovePlans.goTo.run(GameAction.GoTo(null, null, "warp:1", MoveOptions(onLocalDetour = LocalDetour.GO)), game.context())
        val done = assertIs<ActionOutcome.Done>(outcome, "$outcome")
        assertEquals((1..9).toList() + listOf(1, 2), game.zonesVisited, done.detail)
    }

    /**
     * The parameters of the two detours: their wire values, refuse by default, anything else refused with the values
     * allowed (never aliased).
     */
    @Test
    fun theDetourParametersAreParsedFromTheirWireValues() {
        fun parse(vararg values: Pair<String, String>) = CommonActions.goTo.spec.parse(kotlinx.serialization.json.buildJsonObject {
            put("target", kotlinx.serialization.json.JsonPrimitive("Floor 50"))
            values.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
        }) as GameAction.GoTo
        assertEquals(LocalDetour.REFUSE, parse().options.onLocalDetour)
        assertEquals(AvoidDetour.REFUSE, parse().options.onAvoidDetour)
        assertEquals(LocalDetour.GO, parse("on_local_detour" to "go").options.onLocalDetour)
        assertEquals(LocalDetour.REFUSE, parse("on_local_detour" to "refuse").options.onLocalDetour)
        assertEquals(AvoidDetour.SHORT_WAY, parse("on_avoid_detour" to "short_way").options.onAvoidDetour)
        for ((key, allowed) in listOf("on_local_detour" to listOf("refuse", "go"), "on_avoid_detour" to listOf("refuse", "short_way"))) {
            val refused = kotlin.runCatching { parse(key to "yes") }.exceptionOrNull()
            val error = assertIs<ActionError.InvalidParameter>(assertIs<ActionException>(refused).error)
            assertEquals(key, error.parameter)
            assertEquals(allowed, error.allowed)
        }
        // Both described in go_to's parameters, with their values.
        val parameters = CommonActions.goTo.spec.parameters.associateBy { it.name }
        assertEquals(listOf("refuse", "go"), parameters.getValue("on_local_detour").values)
        assertEquals(listOf("refuse", "short_way"), parameters.getValue("on_avoid_detour").values)
    }

    @Test
    fun aFarMapNamedOnPurposeIsStillTaken() {
        val game = walledFloor()
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor 9"), game.context()))
        assertEquals(9, game.zone, done.detail)
        assertEquals((1..9).toList(), game.zonesVisited)
    }

    /**
     * Floors 1..[count] in a row (a warp east to the next one, arriving on its west end): a trip of [count] - 1 warps.
     */
    private fun floorsInARow(count: Int): FloorsGame {
        val floors = (1..count).associateWith { k ->
            floor(k, listOf("..."), warps = listOfNotNull(Warp(k, 0, 0, 0, k - 1, 1).takeIf { k > 1 }, Warp(k, 1, 2, 0, k + 1, 0).takeIf { k < count }))
        }
        return FloorsGame(floors, zone = 1, x = 1, y = 0, transitionFrames = 10)
    }

    /**
     * A long trip is legitimate (Nathan: walking across the region early in the game; Cerulean City to Ecruteak City is
     * 16 warps on the ROM, HgssWorldRoutingTest): 12 warps (Mt. Silver's summit to its Pokémon Center, once the most
     * one go_to took) and 24 (formerly refused as "too far for one go_to") both arrive, then the walk on the last floor.
     */
    @Test
    fun aLongTripArrivesHoweverManyWarpsItTakes() {
        for (count in listOf(13, 25)) {
            val game = floorsInARow(count)
            val outcome = MovePlans.goTo.run(GameAction.GoTo(2, 0, null, map = "Floor $count"), game.context())
            val done = assertIs<ActionOutcome.Done>(outcome, "$count floors: $outcome")
            assertEquals(Triple(count, 2, 0), Triple(game.zone, game.x, game.y), done.detail)
            assertEquals((1..count).toList(), game.zonesVisited)
            // Nothing to say about the way: it is the shortest.
            assertTrue(WorldTravel.BY_LENGTH !in done.detail.orEmpty(), done.detail)
        }
    }

    @Test
    fun aTownCanBeNamedWithOrWithoutTown() {
        assertTrue(MapName.sameMapName("New Bark", "New Bark Town"))
        assertTrue(MapName.sameMapName("Goldenrod City", "goldenrod"))
        assertFalse(MapName.sameMapName("Route 3", "Route 30"))
    }

    // region Trips planned again on arrival: what the trip saw, and never a warp twice

    /**
     * Floor 1 (start 1,0) has two mats down to floor 2's two halves: warp:0 (0,0, west) to the left half, warp:1
     * (4,0, east) to the right one; each half has a door (1,1 and 3,1) to floor 3. Someone the maps don't show
     * ([live], on floor 2 at 1,0) closes the left half's way to its door.
     */
    private fun twoHalves(live: List<FieldObject>) = FloorsGame(
        mapOf(
            1 to floor(1, listOf("....."), warps = listOf(Warp(1, 0, 0, 0, 2, 0, WarpTrigger.Press(Direction.WEST)), Warp(1, 1, 4, 0, 2, 1, WarpTrigger.Press(Direction.EAST)))),
            2 to floor(
                2, listOf("..#..", "#.#.#"),
                warps = listOf(
                    Warp(2, 0, 0, 0, 1, 0, WarpTrigger.Press(Direction.WEST)), Warp(2, 1, 4, 0, 1, 1, WarpTrigger.Press(Direction.EAST)),
                    Warp(2, 2, 1, 1, 3, 0), Warp(2, 3, 3, 1, 3, 0),
                ),
            ),
            3 to floor(3, listOf("..."), warps = listOf(Warp(3, 0, 1, 0, 2, 2, WarpTrigger.Never))),
        ),
        zone = 1, x = 1, y = 0, people = mapOf(2 to live), transitionFrames = 10,
    )

    /**
     * The left half is the shorter way, and found closed on arrival: back up, then down the other mat. Planned again
     * from floor 1, the trip remembers floor 2 as it saw it (the person in the way), so it doesn't go down the left
     * mat again (NOTES race: the Bell Tower, the Burned Tower and Ilex Forest's 13-warp ping-pongs).
     */
    @Test
    fun aTripRemembersWhatItFoundOnAFloorItLeft() {
        val blocker = FieldObject("person:0", "hiker", dev.kotlinds.pokemonclient.state.FieldObjectKind.PERSON, 1, 0, Direction.SOUTH)
        val game = twoHalves(listOf(blocker))
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor 3"), game.context()))
        assertEquals(listOf(1, 2, 1, 2, 3), game.zonesVisited, done.detail)
        // Nobody in the way: straight down the left mat and through its door.
        val free = twoHalves(emptyList())
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor 3"), free.context()))
        assertEquals(listOf(1, 2, 3), free.zonesVisited)
    }

    /**
     * Floor 1: start at 1,0, a hole down at 0,0 (warp:0) to floor 2, where the maps say the player lands at 3,0, next
     * to the way on (the door 5,0 to floor 3); the game leaves them at 1,0, walled off from it, next to the hole back
     * up (0,0, landing at 2,0 on floor 1). Planned from where they are, the way goes back up, then down the hole
     * again: the same warp twice, refused before taking it again (typed, with what was taken), rather than going back
     * and forth until the walk gives up (NOTES race: "still not there after 13 warps", the Bell Tower's ladder).
     */
    @Test
    fun aTripNeverTakesTheSameWarpTwice() {
        fun game(arrivals: Map<Pair<Int, Int>, Pair<Int, Int>>) = FloorsGame(
            mapOf(
                1 to floor(1, listOf("..."), warps = listOf(Warp(1, 0, 0, 0, 2, 0), Warp(1, 1, 2, 0, 2, 1, WarpTrigger.Never))),
                2 to floor(2, listOf("..#..."), warps = listOf(Warp(2, 0, 3, 0, 1, 0, WarpTrigger.Never), Warp(2, 1, 0, 0, 1, 1), Warp(2, 2, 5, 0, 3, 0))),
                3 to floor(3, listOf("..."), warps = listOf(Warp(3, 0, 1, 0, 2, 2, WarpTrigger.Never))),
            ),
            zone = 1, x = 1, y = 0, transitionFrames = 10, arrivals = arrivals,
        )
        val looping = game(mapOf((2 to 0) to (1 to 0)))
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor 3"), looping.context()))
        val error = assertIs<ActionError.Unavailable>(failed.error)
        assertEquals(UnavailableReason.NO_PATH, error.reason)
        assertTrue("takes back warp:0 at 0,0 (Floor 1), already taken on this trip" in error.detail, error.detail)
        assertTrue("already taken on this trip" in error.hint.orEmpty(), error.hint)
        // Down once, back up once: the hole isn't taken a second time.
        assertEquals(listOf(1, 2, 1), looping.zonesVisited)
        // Where the maps say: down the hole, across, through the door.
        val plain = game(emptyMap())
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor 3"), plain.context()))
        assertEquals(listOf(1, 2, 3), plain.zonesVisited)
    }

    /**
     * Floor 1: from 0,1 along a corridor ([width] tiles) to the door at its end (y 2) down to floor 50 ([width] - 1
     * steps as routes count them, [dev.kotlinds.pokemonclient.world.WorldRouter.WorldRoute.tiles]), a trainer (below
     * 3,1, watching north one tile) sees 3,1. Without crossing its sight the only way goes up the door at 0,0 and
     * through [corridors] floors of [chainWidth] tiles to the door at the corridor's end (y 0): 2 + [corridors] ×
     * ([chainWidth] - 1) steps.
     */
    private fun watchedCorridor(corridors: Int, width: Int = 7, chainWidth: Int = 3): FloorsGame {
        val last = corridors + 1
        val end = width - 1
        val first = floor(
            1, listOf("D" + "#".repeat(width - 2) + "D", ".".repeat(width), "###." + "#".repeat(width - 5) + "D"),
            warps = listOf(Warp(1, 0, 0, 0, 2, 0), Warp(1, 1, end, 0, last, 1, WarpTrigger.Never), Warp(1, 2, end, 2, GOAL_FLOOR, 0)),
        )
        val chain = (2..last).associateWith { k ->
            floor(
                k, listOf(".".repeat(chainWidth)),
                warps = listOf(Warp(k, 0, 0, 0, k - 1, 0, WarpTrigger.Never), Warp(k, 1, chainWidth - 1, 0, if (k == last) 1 else k + 1, if (k == last) 1 else 0)),
            )
        }
        val goal = floor(GOAL_FLOOR, listOf("..."), warps = listOf(Warp(GOAL_FLOOR, 0, 1, 0, 1, 2, WarpTrigger.Never)))
        val trainer = FieldObject(
            "person:0", "Youngster Joey", dev.kotlinds.pokemonclient.state.FieldObjectKind.PERSON, 3, 2, Direction.NORTH,
            trainer = dev.kotlinds.pokemonclient.state.FieldTrainer(1, "Youngster", "Joey", defeated = false, sightRange = 1),
        )
        return FloorsGame(mapOf(1 to first, GOAL_FLOOR to goal) + chain, zone = 1, x = 0, y = 1, transitionFrames = 10, people = mapOf(1 to listOf(trainer)))
    }

    /**
     * What a route weighs by itself (a trainer's battle) never sends a trip across a region: when the way around the
     * trainer is a detour (here 14 warps, 50 steps against 6: more than twice, and 30 more), the short way is
     * planned (each walk still avoiding what it can) and the answer says so (NOTES race: `go_to Route 26` from Route
     * 27, refused as 19 warps through Kanto). A way around within reach (2 warps) is still taken.
     */
    @Test
    fun theWalksOwnCostsNeverTurnIntoARefusedDetour() {
        val far = watchedCorridor(corridors = 12, chainWidth = 5)
        val outcome = MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor $GOAL_FLOOR"), far.context())
        val done = assertIs<ActionOutcome.Done>(outcome, "$outcome")
        assertEquals(listOf(1, GOAL_FLOOR), far.zonesVisited, done.detail)
        assertTrue(WorldTravel.BY_LENGTH in done.detail.orEmpty(), done.detail)
        val near = watchedCorridor(corridors = 1)
        val around = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor $GOAL_FLOOR", MoveOptions(avoidTrainers = true)), near.context()))
        assertEquals(listOf(1, 2, 1, GOAL_FLOOR), near.zonesVisited, around.detail)
        assertTrue(WorldTravel.BY_LENGTH !in around.detail.orEmpty(), around.detail)
    }

    /**
     * avoid_trainers is the agent's decision (review impl13 M1): when going round the trainer is a detour go_to
     * refuses while a short way through its sight exists, go_to refuses before moving and offers both (the detour's
     * warps, the short way's warps and the trainers it crosses); the short way only with on_avoid_detour short_way.
     */
    @Test
    fun aDetourAroundWhatTheAgentAvoidsIsItsChoice() {
        val far = watchedCorridor(corridors = 12, chainWidth = 5)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor $GOAL_FLOOR", MoveOptions(avoidTrainers = true)), far.context()))
        val error = assertIs<ActionError.Unavailable>(failed.error)
        assertEquals(UnavailableReason.NO_PATH, error.reason)
        assertTrue("through 14 warps" in error.detail && "(1 warp(s)), which crosses the sight of 1 trainer(s)" in error.detail, error.detail)
        assertTrue("more than twice the short way's" in error.detail, error.detail)
        assertTrue("on_avoid_detour: short_way" in error.hint.orEmpty() && "the way around" in error.hint.orEmpty(), error.hint)
        assertEquals(listOf(1), far.zonesVisited)
        assertEquals(Triple(1, 0, 1), Triple(far.zone, far.x, far.y))

        // Opted in: the short way, said so.
        val opted = watchedCorridor(corridors = 12, chainWidth = 5)
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(
            GameAction.GoTo(null, null, "Floor $GOAL_FLOOR", MoveOptions(avoidTrainers = true, onAvoidDetour = AvoidDetour.SHORT_WAY)), opted.context(),
        ))
        assertEquals(listOf(1, GOAL_FLOOR), opted.zonesVisited, done.detail)
        assertTrue(WorldTravel.SHORT_WAY in done.detail.orEmpty(), done.detail)
        // The parameter's wire values; anything else refused.
        assertEquals(AvoidDetour.SHORT_WAY, (CommonActions.goTo.spec.parse(kotlinx.serialization.json.buildJsonObject {
            put("target", kotlinx.serialization.json.JsonPrimitive("Floor 50")); put("on_avoid_detour", kotlinx.serialization.json.JsonPrimitive("short_way"))
        }) as GameAction.GoTo).options.onAvoidDetour)
    }

    /**
     * The ratio's two bounds, with avoid_trainers: a way around the trainer one and a half times the short way's steps
     * (about 91 against 59: 32 more, but not twice) is no detour; nor is one more than twice as long but only a few
     * steps more (about 16 against 6). Both are taken as asked, nothing said.
     */
    @Test
    fun aWayAroundUnderTheDetourRatioIsTakenSilently() {
        val avoid = MoveOptions(avoidTrainers = true)
        val half = watchedCorridor(corridors = 1, width = 60, chainWidth = 90)
        val outcome = MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor $GOAL_FLOOR", avoid), half.context())
        val done = assertIs<ActionOutcome.Done>(outcome, "$outcome")
        assertEquals(listOf(1, 2, 1, GOAL_FLOOR), half.zonesVisited, done.detail)
        assertTrue(WorldTravel.BY_LENGTH !in done.detail.orEmpty() && WorldTravel.SHORT_WAY !in done.detail.orEmpty(), done.detail)
        val few = watchedCorridor(corridors = 2, chainWidth = 8)
        val around = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor $GOAL_FLOOR", avoid), few.context()))
        assertEquals(listOf(1, 2, 3, 1, GOAL_FLOOR), few.zonesVisited, around.detail)
        assertTrue(WorldTravel.BY_LENGTH !in around.detail.orEmpty() && WorldTravel.SHORT_WAY !in around.detail.orEmpty(), around.detail)
    }

    /**
     * Floor 1 (start 0,0), a door at 2,0 down to floor 2's corridor (0,0 to 4,0), whose far end (4,0) leads to floor 3.
     * Floor 2's map places someone in the corridor (2,0); [live]: who really stands there.
     */
    private fun guardedCorridor(live: List<FieldObject>) = FloorsGame(
        mapOf(
            1 to floor(1, listOf("..."), warps = listOf(Warp(1, 0, 2, 0, 2, 0))),
            2 to floor(
                2, listOf("....."), warps = listOf(Warp(2, 0, 0, 0, 1, 0, WarpTrigger.Never), Warp(2, 1, 4, 0, 3, 0)),
                people = listOf(dev.kotlinds.pokemonclient.world.PersonTemplate(2, 0, 0, 2, 0, Direction.SOUTH, 0, 0, 0)),
            ),
            3 to floor(3, listOf("..."), warps = listOf(Warp(3, 0, 0, 0, 2, 1, WarpTrigger.Never))),
        ),
        zone = 1, x = 0, y = 0, people = mapOf(2 to live), transitionFrames = 10,
    )

    /**
     * Review impl13 H1: the people of a map the player isn't on are a guess (where the map places them; an entry
     * script may move them, the day may hide them). When only they close the way, go_to goes and plans again on
     * arrival with who is really there: through when nobody stands there, stopped there (and told who) when someone
     * does; never "no way" before the first step on that guess alone.
     */
    @Test
    fun peopleOfAnotherMapNeverRefuseAWayBeforeItIsSeen() {
        val free = guardedCorridor(emptyList())
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor 3"), free.context()))
        assertEquals(listOf(1, 2, 3), free.zonesVisited, done.detail)
        val blocker = FieldObject("person:0", "hiker", dev.kotlinds.pokemonclient.state.FieldObjectKind.PERSON, 2, 0, Direction.SOUTH)
        val guarded = guardedCorridor(listOf(blocker))
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor 3"), guarded.context()))
        assertEquals(listOf(1, 2), guarded.zonesVisited)
        assertTrue("person:0" in failed.error.toString(), failed.error.toString())
    }

    /**
     * Floors 1 and 2 (start 0,0 on floor 1), each with a door at 2,0 into a lift (zone 9) whose way out, the mat at
     * 1,1 (pressed south), leads to the floor it was sent to: [operator] sends it.
     */
    private fun lift(operator: ElevatorOperator, inside: Boolean = false) = FloorsGame(
        mapOf(
            1 to floor(1, listOf("..D"), warps = listOf(Warp(1, 0, 2, 0, LIFT, 0))),
            2 to floor(2, listOf("..D"), warps = listOf(Warp(2, 0, 2, 0, LIFT, 0))),
            LIFT to floor(LIFT, listOf("...", "..."), warps = listOf(Warp(LIFT, 0, 1, 1, FloorsGame.DYNAMIC_ZONE, 256, WarpTrigger.Press(Direction.SOUTH)))),
        ),
        zone = if (inside) LIFT else 1, x = if (inside) 1 else 0, y = if (inside) 0 else 0, transitionFrames = 10,
        elevators = mapOf(LIFT to Elevator(LIFT, listOf(0), listOf(ElevatorStop(1, 0), ElevatorStop(2, 0)), operator)),
    )

    /**
     * A lift going by itself to the other floor (the Olivine Lighthouse's, the Radio Tower's: NOTES race, "no way to
     * Cianwood City" from the lighthouse's top, the lift ignored) is a way like stairs: in, its message read, out on
     * the other floor.
     */
    @Test
    fun aLiftGoingByItselfIsRidden() {
        val game = lift(ElevatorOperator.Shuttle)
        val done = assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor 2"), game.context()))
        assertEquals(listOf(1, LIFT, 2), game.zonesVisited, done.detail)
    }

    /**
     * A lift whose floor is chosen (an attendant: the Goldenrod Dept. Store's): go_to doesn't choose it, the answer
     * says who does (NOTES race: Codex tried `interact sign:0`, refused, the attendant being person:0).
     */
    @Test
    fun aLiftWhoseFloorIsChosenSaysWhoChoosesIt() {
        val game = lift(ElevatorOperator.Attendant(0), inside = true)
        val failed = assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor 2"), game.context()))
        val error = assertIs<ActionError.Unavailable>(failed.error)
        assertEquals(UnavailableReason.NO_PATH, error.reason)
        assertTrue("is a lift" in error.detail, error.detail)
        assertEquals("talk to person:0 (interact person:0) and choose the floor, then take warp:0", error.hint)
        assertEquals(listOf(LIFT), game.zonesVisited)
    }

    private companion object {
        /** The floor behind the watched corridor ([watchedCorridor]). */
        const val GOAL_FLOOR = 50

        /** The lift's room ([lift]). */
        const val LIFT = 9
    }

    // endregion

}

/**
 * `go_to` while destinations are hidden ([ActionSettings.hideDestinations]): targets of the current map only (its
 * warps, holes and exits are taken: that is how the agent explores), walks kept on the player's map, and refusals
 * that never name another map or the way there.
 */
class HiddenDestinationsTravelTest {

    private fun hidden(game: FloorsGame) = game.context(ActionSettings(hideDestinations = true))

    /** Floor 1's start (0,0) is walled off from (4,0); a ladder at (0,2) leads to floor 2, whose hole falls next to it. */
    private fun dungeon() = FloorsGame(
        mapOf(
            1 to floor(1, listOf(".#...", ".#...", ".#..."), warps = listOf(Warp(1, 0, 0, 2, 2, 0, WarpTrigger.Press(Direction.SOUTH)))),
            2 to floor(2, listOf(".....", "....."), warps = listOf(Warp(2, 0, 0, 0, 1, 0, WarpTrigger.Press(Direction.NORTH))), holes = listOf(TriggerWarp(2, 0, 4, 1, 1, 4, 1)), triggers = listOf(Trigger(2, 0, 4, 1, 1, 1, 1, 0x4000, 0))),
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
        // The answer tells the warp taken and the arrival, which the game shows anyway (discovery), never more.
        assertEquals("took warp:0 at 0,2 (Floor 1) → Floor 2 (0,0)", done.detail)
    }

    @Test
    fun theMapSelfAndItsTilesStayAllowed() {
        val game = dungeon()
        val here = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Floor 1"), hidden(game))).error)
        assertEquals(UnavailableReason.NO_PATH, here.reason)
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(0, 1, null, map = "map:1"), hidden(game)))
        assertEquals(Triple(1, 0, 1), Triple(game.zone, game.x, game.y))
    }

    /**
     * A town and its buildings share the place's name ("Violet City"): asked from the gym, it names the town outside
     * (another map, refused as hidden), not the gym; asked in the town itself, it is the current map.
     */
    @Test
    fun thePlaceNameFromABuildingIsTheTownOutsideNotHere() {
        val outdoor = floor(1, listOf("......", "......"), zones = listOf("111222", "111222"))
        val gym = floor(3, listOf("...", "..."))
        val names = mapOf(1 to MapName(1, "Violet City", "Violet City"), 2 to MapName(2, "Route 32", "Route 32"), 3 to MapName(3, "Violet City", "Violet Gym"))
        val areas = mapOf(1 to outdoor, 2 to outdoor, 3 to gym)
        val inGym = FloorsGame(areas, zone = 3, x = 1, y = 1, names = { names[it] ?: MapName(it) })
        refusedAsHidden(MovePlans.goTo.run(GameAction.GoTo(null, null, "Violet City"), hidden(inGym)))
        refusedAsHidden(MovePlans.goTo.run(GameAction.GoTo(0, 0, null, map = "Violet City"), hidden(inGym)))
        // The gym's own name and display form are this map.
        for (here in listOf("Violet Gym", "Violet City (Violet Gym)")) {
            val error = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, here), hidden(inGym))).error)
            assertEquals(UnavailableReason.NO_PATH, error.reason, here)
        }
        val inTown = FloorsGame(areas, zone = 1, x = 0, y = 0, names = { names[it] ?: MapName(it) })
        val already = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Violet City"), hidden(inTown))).error)
        assertEquals(UnavailableReason.NO_PATH, already.reason)
        assertEquals(listOf(3), inGym.zonesVisited)
        // Destinations shown: a name in an earlier version's display form ("Route 32 (Route 32)") still leads there.
        val shown = FloorsGame(areas, zone = 1, x = 0, y = 0, names = { names[it] ?: MapName(it) })
        assertIs<ActionOutcome.Done>(MovePlans.goTo.run(GameAction.GoTo(null, null, "Route 32 (Route 32)"), shown.context()))
        assertEquals(2, shown.zone)
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

    /**
     * The go_to error and the view's reachability come from the same diagnosis and say it with the same words: a guard
     * in the only way to warp:0 (destinations hidden: this map only).
     */
    @Test
    fun theGoToErrorSaysWhatTheViewSaysOfTheTarget() {
        val guard = FieldObject("person:1", "guard", dev.kotlinds.pokemonclient.state.FieldObjectKind.PERSON, 2, 0, Direction.WEST)
        val game = FloorsGame(mapOf(1 to floor(1, listOf("....."), warps = listOf(Warp(1, 0, 4, 0, 2, 0)))), zone = 1, x = 0, y = 0, people = mapOf(1 to listOf(guard)))
        val view = ReachSurvey(game, game.state(dev.kotlinds.pokemonclient.ZeroMemory), ActionSettings(hideDestinations = true)).of("warp:0")
        assertEquals(" [blocked_by_person: person:1 (guard)]", view.suffix())
        val error = assertIs<ActionError.Unavailable>(assertIs<ActionOutcome.Failed>(MovePlans.goTo.run(GameAction.GoTo(null, null, "warp:0"), hidden(game))).error)
        assertTrue(error.detail.endsWith(view.suffix()), error.detail)
        assertTrue("person:1 (guard) stands in the only way" in error.hint.orEmpty(), error.hint)
    }

}
