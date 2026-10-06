package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.runtime.InputProbe
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.EventFlags
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.PuzzleKind
import dev.kotlinds.pokemonclient.state.PuzzleState
import dev.kotlinds.pokemonclient.state.PuzzleTeleport
import dev.kotlinds.pokemonclient.state.PuzzleTile
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TeleportKind
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.PersonTemplate
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.Warp
import dev.kotlinds.pokemonclient.world.WarpTrigger
import dev.kotlinds.pokemonclient.world.WorldSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The reachability of the targets of the agent's view ([ReachSurvey], [Reachability]): nothing for what a walk
 * reaches, else what the way needs, computed on maps drawn in ASCII.
 */
class ReachabilityTest {

    /** A game whose world is [areas] (by zone); its state is given to the survey directly. */
    private class MapGame(val areas: Map<Int, Area>) : PokemonGame {
        override val name = "Maps"
        override val inputProbe = InputProbe { emptySet() }
        override val world = object : WorldSource {
            override fun areaOf(zoneId: Int) = areas[zoneId]
            override val zoneCount get() = 10
        }
        override fun state(memory: Memory): GameState = error("the survey is given its state")
    }

    /**
     * An area drawn in ASCII: '.' floor, '#' wall, '~' water, 'v' a ledge jumped south, 'D' a door with collision,
     * 'N' a warp tile entered (a gatehouse entrance), 'H' floor at height 24, 'l' at height 16, 'o' at height 0 (the
     * others flat).
     */
    private fun area(zone: Int, rows: List<String>, warps: List<Warp> = emptyList(), people: List<PersonTemplate> = emptyList()): Area {
        val width = rows.maxOf { it.length }
        val tiles = Array<TileInfo?>(width * rows.size) { i ->
            when (rows[i / width].getOrElse(i % width) { '#' }) {
                '#' -> TileInfo(true, TileKind.Wall)
                '~' -> TileInfo(false, TileKind.Water(surfable = true, fishable = true))
                'v' -> TileInfo(false, TileKind.Ledge(Direction.SOUTH))
                'D' -> TileInfo(true, TileKind.Door)
                'N' -> TileInfo(false, TileKind.Door)
                'H' -> TileInfo(false, TileKind.Floor, listOf(24))
                'l' -> TileInfo(false, TileKind.Floor, listOf(16))
                'o' -> TileInfo(false, TileKind.Floor, listOf(0))
                else -> TileInfo(false, TileKind.Floor)
            }
        }
        return Area(zone, "Map $zone", 0, 0, width, rows.size, tiles, warps = warps, people = people)
    }

    private fun person(n: Int, x: Int, y: Int, label: String = "man", height: Int? = null, kind: FieldObjectKind = FieldObjectKind.PERSON) =
        FieldObject("person:$n", label, kind, x, y, Direction.SOUTH, height = height)

    private fun state(zone: Int, x: Int, y: Int, objects: List<FieldObject> = emptyList(), height: Int = 0, flags: EventFlags? = null, puzzle: PuzzleState? = null) =
        GameState(
            0, Screen.Overworld(null, Awaiting.INPUT), null, emptyList(), null, null,
            FieldState(zone, MapName(zone, map = "Map $zone"), x, y, height, Direction.SOUTH, MovementMode.WALK, moving = false, objects = objects, puzzle = puzzle),
            eventFlags = flags,
        )

    private fun survey(game: MapGame, state: GameState, settings: ActionSettings = ActionSettings()) = ReachSurvey(game, state, settings)

    @Test
    fun whatAWalkReachesGetsNothing() {
        val room = area(1, listOf("....", "...."), warps = listOf(Warp(1, 0, 3, 1, 2, 0)))
        val s = survey(MapGame(mapOf(1 to room)), state(1, 0, 0, listOf(person(1, 2, 0))))
        assertEquals(Reachability.DIRECT, s.of("warp:0"))
        assertEquals(Reachability.DIRECT, s.of("person:1"))
        assertEquals("", s.of("warp:0").suffix())
    }

    @Test
    fun waterToCrossNamesSurfAndAPersonOnTheWayToo() {
        // The item ball lies across the water; a man stands in the only way to the shore.
        val map = area(1, listOf("#####", "..~..", "#####"))
        val ball = person(3, 4, 1, "Poke Ball", kind = FieldObjectKind.ITEM_BALL)
        val alone = survey(MapGame(mapOf(1 to map)), state(1, 0, 1, listOf(ball))).of("item:3")
        assertEquals(listOf(FieldMoveKind.SURF), alone.requiresFieldMoves)
        assertEquals("[requires_field_moves: [surf]]", alone.suffix().trim())
        val guarded = survey(MapGame(mapOf(1 to map)), state(1, 0, 1, listOf(ball, person(1, 1, 1, "worker")))).of("item:3")
        assertEquals(listOf(FieldMoveKind.SURF), guarded.requiresFieldMoves)
        assertEquals("person:1", guarded.blockedByPerson?.id)
        assertTrue("blocked_by_person: person:1 (worker)" in guarded.suffix(), guarded.suffix())
    }

    @Test
    fun aPersonInTheOnlyWayIsNamedAndAWallNeedsAnotherWarp() {
        val map = area(1, listOf("###.##", "......", "###.##", "###.#."), warps = listOf(Warp(1, 0, 5, 1, 2, 0), Warp(1, 1, 5, 3, 2, 0)))
        val s = survey(MapGame(mapOf(1 to map)), state(1, 0, 1, listOf(person(1, 2, 1, "guard"))))
        assertEquals("person:1", s.of("warp:0").blockedByPerson?.id)
        assertTrue(s.of("warp:0").requiresFieldMoves.isEmpty())
        // warp:1 is walled off (a person never counts as an intermediate warp).
        assertEquals(Reachability(requiresIntermediateWarp = true), s.of("warp:1"))
    }

    @Test
    fun onlyOverALedgeWithoutAWayBackIsOneWay() {
        // Down the ledge to the warp; no way back up.
        val down = area(1, listOf("...", "vvv", "...."), warps = listOf(Warp(1, 0, 3, 2, 2, 0)))
        assertEquals(Reachability(oneWay = true), survey(MapGame(mapOf(1 to down)), state(1, 0, 0)).of("warp:0"))
        // A way around back up: the ledge is a shortcut only.
        val around = area(1, listOf("....", "vvv.", "...."), warps = listOf(Warp(1, 0, 0, 2, 2, 0)))
        assertEquals(Reachability.DIRECT, survey(MapGame(mapOf(1 to around)), state(1, 0, 0)).of("warp:0"))
    }

    /**
     * Two workers side by side in a passage two tiles wide (Cliff Edge Gate, NOTES-run-map-randomizer): from the slope
     * on the west (height 24), the tiles next to them are at another height than theirs (16), where the game answers
     * no A. Neither "stands in the way" of the other: both are on another level from here (reached by another warp);
     * from the east (their height), both are reached.
     */
    @Test
    fun twoPeopleSideBySideNeverBlameEachOther() {
        val gate = area(1, listOf("HHHlll", "HHHlll"))
        val workers = listOf(person(0, 3, 0, "worker", height = 2), person(1, 3, 1, "worker", height = 2))
        val west = survey(MapGame(mapOf(1 to gate)), state(1, 0, 0, workers, height = 3))
        for (id in listOf("person:0", "person:1")) {
            assertEquals(Reachability(requiresIntermediateWarp = true), west.of(id), id)
        }
        val east = survey(MapGame(mapOf(1 to gate)), state(1, 5, 0, workers, height = 2))
        assertEquals(Reachability.DIRECT, east.of("person:0"))
        assertEquals(Reachability.DIRECT, east.of("person:1"))
    }

    /** The Violet Gym before Sprout Tower: the guide stands on the lift's center, the only way up to Falkner. */
    @Test
    fun aPersonOnTheLiftIsWhatBlocksNotAnotherHeightLevel() {
        val rows = listOf("lllll", "#####", "ooooo")
        // Upper floor (row 0) at height 16; the lift at (2,2) takes the player there from the lower floor (row 2).
        val gym = area(1, rows)
        val lift = PuzzleState(PuzzleKind.LIFT, "rule", teleports = listOf(PuzzleTeleport("lift:up", TeleportKind.LIFT, listOf(PuzzleTile(2, 2)), PuzzleTile(2, 0), fromHeight = 0, toHeight = 2)))
        val falkner = person(0, 4, 0, "Falkner", height = 2)
        val free = survey(MapGame(mapOf(1 to gym)), state(1, 0, 2, listOf(falkner), puzzle = lift))
        assertEquals(Reachability.DIRECT, free.of("person:0"))
        val guide = person(4, 2, 2, "gym guide")
        val blocked = survey(MapGame(mapOf(1 to gym)), state(1, 0, 2, listOf(falkner, guide), puzzle = lift)).of("person:0")
        assertEquals("person:4", blocked.blockedByPerson?.id, blocked.toString())
        assertTrue(!blocked.requiresIntermediateWarp)
    }

    @Test
    fun anExitArrivingWhereThereIsNoWayBackIsOneWay() {
        val here = area(1, listOf(".....", "....."), warps = listOf(Warp(1, 0, 0, 0, 2, 0), Warp(1, 1, 2, 0, 3, 0), Warp(1, 2, 4, 0, 4, 0)))
        // Zone 2: the arrival tile has no warp behaviour ([WarpTrigger.Never]): it only receives.
        val arrivalOnly = area(2, listOf("..."), warps = listOf(Warp(2, 0, 1, 0, 1, 0, WarpTrigger.Never)))
        // Zone 3: a gatehouse entrance entered going north, whose only free side is held by a man (flag 5 hides him).
        val held = area(3, listOf("#N#", "#.#", "#.#"), warps = listOf(Warp(3, 0, 1, 0, 1, 1)), people = listOf(PersonTemplate(3, 0, 1, 1, 1, Direction.SOUTH, 0, 0, hiddenByFlag = 5)))
        // Zone 4: a door with collision (faced to go back): fine.
        val door = area(4, listOf("#D#", "..."), warps = listOf(Warp(4, 0, 1, 0, 1, 2)))
        val game = MapGame(mapOf(1 to here, 2 to arrivalOnly, 3 to held, 4 to door))
        val flagClear = EventFlags(ByteArray(1))
        val s = survey(game, state(1, 1, 1, flags = flagClear))
        assertEquals(Reachability(oneWay = true), s.of("warp:0"))
        assertEquals(Reachability(oneWay = true), s.of("warp:1"))
        assertEquals(Reachability.DIRECT, s.of("warp:2"))
        // The man gone (his flag set), or the flags unknown (never one way on a guess): a way back.
        assertEquals(Reachability.DIRECT, survey(game, state(1, 1, 1, flags = EventFlags(byteArrayOf(0x20)))).of("warp:1"))
        assertEquals(Reachability.DIRECT, survey(game, state(1, 1, 1)).of("warp:1"))
    }

    @Test
    fun anExitWhoseArrivalSomeoneStandsOnIsOneWayUnlessAnotherWarpLeadsBack() {
        val here = area(1, listOf("...."), warps = listOf(Warp(1, 0, 0, 0, 2, 0), Warp(1, 1, 3, 0, 3, 0)))
        // A guard on the stairs the player arrives on (a mat taken pressing west).
        val guarded = area(2, listOf("....."), warps = listOf(Warp(2, 0, 0, 0, 1, 0, WarpTrigger.Press(Direction.WEST))), people = listOf(PersonTemplate(2, 0, 1, 0, 0, Direction.EAST, 0, 0, hiddenByFlag = 0)))
        // The same, with another exit back to the first map at the far end.
        val otherWay = area(3, listOf("....D"), warps = listOf(Warp(3, 0, 0, 0, 1, 1, WarpTrigger.Press(Direction.WEST)), Warp(3, 1, 4, 0, 1, 1)), people = listOf(PersonTemplate(3, 0, 1, 0, 0, Direction.EAST, 0, 0, hiddenByFlag = 0)))
        val s = survey(MapGame(mapOf(1 to here, 2 to guarded, 3 to otherWay)), state(1, 1, 0))
        assertEquals(Reachability(oneWay = true), s.of("warp:0"))
        assertEquals(Reachability.DIRECT, s.of("warp:1"))
    }

    @Test
    fun theViewAndTheErrorsSayItTheSameWay() {
        val failure = dev.kotlinds.pokemonclient.world.RouteFailure.NeedsFieldMove(FieldMoveKind.SURF, 2, 1)
        val blockers = dev.kotlinds.pokemonclient.world.Blockers(listOf(FieldMoveKind.SURF, FieldMoveKind.STRENGTH), person = 1 to 1)
        val field = state(1, 0, 1, listOf(person(1, 1, 1, "worker"))).field
        assertEquals(
            " [requires_field_moves: [surf, strength]; blocked_by_person: person:1 (worker)]",
            Reachability.of(failure, blockers, field)?.suffix(),
        )
        assertEquals(" [requires_intermediate_warp]", Reachability.of(dev.kotlinds.pokemonclient.world.RouteFailure.DifferentLevel, blockers, field)?.suffix())
        assertEquals(" [one_way]", Reachability.of(dev.kotlinds.pokemonclient.world.RouteFailure.OnlyOneWay, blockers, field)?.suffix())
    }
}
