package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.games.hgss.HgssWorldRom
import dev.kotlinds.pokemonclient.world.FieldMoveAccess
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.RouteFailure
import dev.kotlinds.pokemonclient.world.RouteOptions
import dev.kotlinds.pokemonclient.world.WorldRouter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A Strength boulder is announced as the way (the go_to hint, `requires_field_moves: [strength]`) only when the
 * [dev.kotlinds.pokemonclient.world.PushPlanner] proves its pushes open it, on the real Victory Road (HeartGold ROM,
 * skipped without `POKEMON_ROM`; 1F 124, 2F 178, 3F 179). Race Claude vs Codex: both agents were told "a boulder
 * blocks the way at 9,25 (use it from 9,26 facing north)" on 2F, whose only push (north, against the wall at 9,23)
 * closes the corridor for good; the real pushes are 1F (43,52) before the ladder (19,7) and 2F (50,28) before the
 * ladder (56,21). The end-to-end routing of Victory Road is tested by the routing tests; here, the hint side.
 */
class StrengthWayProofTest {

    private val world get() = HgssWorldRom.require()

    /** The diagnosis on [zone] from [from] to [goal] with the boulders where the map places them, without Strength. */
    private fun diagnose(zone: Int, from: Pair<Int, Int>, goal: Pair<Int, Int>, pushBound: Int = dev.kotlinds.pokemonclient.world.PushPlanner.DEFAULT_MAX_STATES): Pathfinder.Result.Failed {
        val area = world.areaOf(zone)!!
        val pathfinder = Pathfinder(area, WorldRouter.staticOverlay(area), pushBound)
        val start = Node(from.first, from.second, pathfinder.levelAt(from.first, from.second, 2 * dev.kotlinds.pokemonclient.world.FIELD_HEIGHT_UNITS))
        return assertIs<Pathfinder.Result.Failed>(pathfinder.route(start, RouteOptions(acceptOneWay = true), setOf(goal)) { it.x == goal.first && it.y == goal.second })
    }

    @Test
    fun `the real Victory Road pushes are still announced`() {
        // 1F: from the entrance to the ladder up, the boulder at 43,52.
        val first = assertIs<RouteFailure.NeedsFieldMove>(diagnose(124, 46 to 58, 19 to 7).failure)
        assertEquals(FieldMoveKind.STRENGTH, first.move)
        assertEquals(43 to 52, first.x to first.y)
        // 2F below the hole: the boulder at 50,28 before the ladder at 56,21.
        val second = diagnose(178, 57 to 42, 56 to 21)
        val push = assertIs<RouteFailure.NeedsFieldMove>(second.failure)
        assertEquals(Triple(FieldMoveKind.STRENGTH, 50, 28), Triple(push.move, push.x, push.y))
        assertEquals(listOf(FieldMoveKind.STRENGTH), second.blockers.fieldMoves)
        assertTrue(second.blockers.stuckBoulders.isEmpty())
    }

    @Test
    fun `the dead-end boulder west of 2F is not a way`() {
        // From 1F's ladder (7,30) to the ladder at 7,18: only through 3F, never by pushing 9,25 north.
        val failed = diagnose(178, 7 to 30, 7 to 18)
        assertEquals(RouteFailure.Unreachable, failed.failure)
        assertTrue(failed.blockers.fieldMoves.isEmpty(), failed.toString())
        assertEquals(listOf(9 to 25), failed.blockers.stuckBoulders)
    }

    /**
     * Review impl13 M2: the proof has three answers. Pushes that open the way (2F's 50,28 before the ladder at 56,21),
     * no plan at all (the dead end of 9,25), and a search that gave up at its bound: nothing proven, the boulder stays a
     * (not proven) Strength way, never "it is not the way".
     */
    @Test
    fun `the push proof tells proven, no plan and over its bound apart`() {
        val area = world.areaOf(178)!!
        val overlay = WorldRouter.staticOverlay(area)
        val options = RouteOptions(acceptOneWay = true)
        fun proof(from: Pair<Int, Int>, to: Pair<Int, Int>, bound: Int, moving: Set<Pair<Int, Int>>? = null) =
            dev.kotlinds.pokemonclient.world.PushPlanner(area, overlay, bound).opensWay(Node(from.first, from.second, Pathfinder(area).levelAt(from.first, from.second, 2 * dev.kotlinds.pokemonclient.world.FIELD_HEIGHT_UNITS)), options, emptyList(), setOf(to), moving) { it.x == to.first && it.y == to.second }
        assertIs<dev.kotlinds.pokemonclient.world.PushPlanner.PushProof.Opens>(proof(57 to 42, 56 to 21, dev.kotlinds.pokemonclient.world.PushPlanner.DEFAULT_MAX_STATES))
        // Only the boulder the way crosses moving: the same proof, a much smaller search.
        assertIs<dev.kotlinds.pokemonclient.world.PushPlanner.PushProof.Opens>(proof(57 to 42, 56 to 21, dev.kotlinds.pokemonclient.world.PushPlanner.DEFAULT_MAX_STATES, moving = setOf(50 to 28)))
        assertEquals(dev.kotlinds.pokemonclient.world.PushPlanner.PushProof.NoPlan, proof(7 to 30, 7 to 18, dev.kotlinds.pokemonclient.world.PushPlanner.DEFAULT_MAX_STATES, moving = setOf(9 to 25)))
        assertEquals(dev.kotlinds.pokemonclient.world.PushPlanner.PushProof.OverBound, proof(57 to 42, 56 to 21, bound = 20))
    }

    @Test
    fun `a boulder whose proof reached its bound stays a Strength way said not proven`() {
        // The dead end of 9,25 with a bound too small to decide: kept, not proven (never "it is not the way").
        val undecided = diagnose(178, 7 to 30, 7 to 18, pushBound = 20)
        assertEquals(FieldMoveKind.STRENGTH, assertIs<RouteFailure.NeedsFieldMove>(undecided.failure).move)
        assertEquals(listOf(9 to 25), undecided.blockers.unprovenBoulders)
        assertTrue(undecided.blockers.stuckBoulders.isEmpty())
        // Decided (the default bound): no plan, a wall.
        assertEquals(listOf(9 to 25), diagnose(178, 7 to 30, 7 to 18).blockers.stuckBoulders)
        val note = MovePlans.boulderNotes(emptyList(), listOf("at 9,25"))
        assertEquals("; whether pushing the boulder at 9,25 opens the way couldn't be worked out (too many boulder positions to try): it may be the way, not proven", note)
        assertFalse("not the way" in note)
    }

    private fun goTo(game: RomStanding, target: String, settings: ActionSettings = ActionSettings()): ActionError.Unavailable {
        val failed = assertIs<ActionOutcome.Failed>(Recipes.COMMON.goTo(GameAction.GoTo(null, null, target), game.context(settings)))
        return assertIs<ActionError.Unavailable>(failed.error)
    }

    @Test
    fun `go_to on 2F never sends the agent to the dead-end boulder`() {
        // Race: from 7,30, go_to warp:7 answered "a boulder blocks the way at 9,25 ... use Strength there by hand".
        val error = goTo(RomStanding(178, 7, 30, height = 2), "warp:7")
        val text = error.message
        assertFalse("use it from 9,26" in text, text)
        assertTrue("9,25 opens no way" in text, text)
    }

    @Test
    fun `go_to from 3F to the exit names the real push, not the dead-end boulder`() {
        // Race: from 3F (49,38), go_to warp:0 answered "blocked at 9,25 on Victory Road 2F" (warp:0 is now refused as arrival-only: warp:5, the same exit): the relaxed crossing of
        // every floor walked through the dead-end boulder. Without Strength in the party, the way is still the push
        // of 50,28 on 2F (after the hole).
        val error = goTo(RomStanding(179, 49, 38, height = 2), "warp:5")
        val text = error.message
        assertFalse("blocked at 9,25" in text, text)
        assertTrue("blocked at 50,28" in text, text)
    }

    /** A party that can push boulders, surf and smash rocks (what Victory Road needs). */
    private val strong = mapOf(
        FieldMoveKind.STRENGTH to FieldMoveAccess.Usable(0, "MACHAMP"),
        FieldMoveKind.ROCK_SMASH to FieldMoveAccess.Usable(0, "MACHAMP"),
        FieldMoveKind.SURF to FieldMoveAccess.Usable(1, "LAPRAS"),
    )

    /**
     * The link go_to sets off for (the walk to it is what fails: [RomStanding] never moves), to the Indigo Plateau with
     * Strength, the movement puzzles solved by the walks: only boulders in the way, so the trip walks to the next link
     * and pushes there ([WorldTravel], the boulders of every floor proven by [dev.kotlinds.pokemonclient.world.PushPlanner.opensWay]).
     */
    private fun firstLink(zone: Int, x: Int, y: Int, height: Int): String {
        val error = goTo(RomStanding(zone, x, y, height = height, fieldMoves = strong), "Indigo Plateau")
        return assertNotNull(Regex("""no way to (warp:\d+) from""").find(error.detail)?.groupValues?.get(1), error.message)
    }

    @Test
    fun `go_to across floors with Strength never sets off for the dead-end boulder`() {
        // 2F from 1F's ladder (7,30): the ladder at 7,18 (warp:7) is only reached by pushing 9,25 into the wall; the
        // way is 3F's ladder at 51,38 (warp:4), then the hole back to 2F below the boulder at 50,28. The boulders the
        // link is chosen by are those of the crossing whose pushes are proven on every floor (the same crossing as the
        // error of `go_to from 3F to the exit names the real push`, which fails without the proof).
        assertEquals("warp:4", firstLink(178, 7, 30, height = 2))
    }

    @Test
    fun `go_to across floors with Strength still sets off for the real pushes`() {
        // 1F: the ladder at 19,7 (warp:1) behind the boulder at 43,52; 2F below the hole: the ladder at 56,22 (warp:5)
        // behind the boulder at 50,28.
        assertEquals("warp:1", firstLink(124, 46, 58, height = 8))
        assertEquals("warp:5", firstLink(178, 57, 42, height = 2))
    }

    @Test
    fun `the boulder of another floor is told with what the party can do`() {
        // Walks not solving the puzzles: the way across floors is blocked at 2F's boulder. The party has Strength: the
        // hint says who can use it there, never "it needs Strength" (WorldTravel.blocked passes the party's access).
        val walkOnly = ActionSettings(solvePuzzles = false)
        val usable = goTo(RomStanding(179, 49, 38, height = 2, fieldMoves = strong), "warp:5", walkOnly)
        assertTrue("blocked at 50,28" in usable.detail, usable.message)
        assertEquals("a boulder blocks the way at 50,28: use Strength there by hand (MACHAMP knows it)", usable.hint)
        // Nobody knows it: said so.
        val nobody = goTo(RomStanding(179, 49, 38, height = 2, fieldMoves = mapOf(FieldMoveKind.STRENGTH to FieldMoveAccess.NoPokemon)), "warp:5", walkOnly)
        assertEquals("a boulder blocks the way at 50,28: no Pokémon of the party knows Strength", nobody.hint)
    }

    @Test
    fun `the view tells requires_field_moves strength only where the push opens the way`() {
        fun survey(zone: Int, x: Int, y: Int): ReachSurvey {
            val game = RomStanding(zone, x, y, height = 2)
            return ReachSurvey(game, game.context().state(), ActionSettings())
        }
        val strength = Reachability(requiresFieldMoves = listOf(FieldMoveKind.STRENGTH))
        // 2F from 1F's ladder: the ladder at 7,18 is behind the dead-end boulder, another floor first.
        assertEquals(Reachability(requiresIntermediateWarp = true), survey(178, 7, 30).of("warp:7"))
        // 2F below the hole: the ladder at 56,21 behind the boulder at 50,28; 1F: the ladder behind 43,52.
        assertEquals(strength, survey(178, 57, 42).of("warp:5"))
        assertEquals(strength, survey(124, 46, 58).of("warp:1"))
    }

    /**
     * Review impl13 B6: the view's reachability (every get_state) diagnoses each unreached target, proving the
     * Strength pushes of its way; the proofs move only the boulders the way crosses, so a whole Victory Road view stays
     * fast. Every way out of each floor, from where the race stood, well within a get_state's budget.
     */
    @Test
    fun `the reachability of a whole Victory Road view stays fast`() {
        val stands = listOf(Triple(124, 46 to 58, 8), Triple(178, 7 to 30, 2), Triple(178, 57 to 42, 2), Triple(179, 49 to 38, 2))
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        for ((zone, at, height) in stands) {
            val game = RomStanding(zone, at.first, at.second, height = height)
            val survey = ReachSurvey(game, game.context().state(), ActionSettings())
            val area = world.areaOf(zone)!!
            area.warps.filter { it.zone == zone }.forEach { survey.of("warp:${it.id}") }
        }
        val took = started.elapsedNow()
        println("Victory Road views: $took")
        assertTrue(took < kotlin.time.Duration.parse("10s"), "took $took")
    }
}
