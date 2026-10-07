package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.actions.ActionSettings
import dev.kotlinds.pokemonclient.actions.Reachability
import dev.kotlinds.pokemonclient.actions.ReachSurvey
import dev.kotlinds.pokemonclient.world.RouteOptions
import dev.kotlinds.pokemonclient.world.WarpTrigger
import dev.kotlinds.pokemonclient.world.WorldLinks
import dev.kotlinds.pokemonclient.world.ZoneLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reachability on a real save (bench, DeSmuME, the map-randomized ROM's 2-badge save, skipped without `POKEMON_ROM`;
 * the maps are the same on the plain ROM, only the warps differ):
 * - `cliff_edge_gate_workers`: Cliff Edge Gate (map 279) at (18,46), arrived from Route 47's side like the agent of
 *   NOTES-run-map-randomizer (`go_to person:0` said "person:1 stands in the only way" and the other way round). The two
 *   Safari Zone workers stand side by side at (30,21) and (30,22) (FLAG_UNK_249 clear); on this side, the tiles next to
 *   them are the slope (heights 24 to 72), where the game answers no A (checked live: A facing person:0 from (29,21)
 *   showed nothing). The save's event flags come with it (FLAG_UNK_258 clear: the Route 5 man still waits for the
 *   Power Plant).
 */
class HgssReachabilityFixtureTest {

    private val game: PokemonGame by lazy {
        val world = HgssWorldRom.require()
        object : PokemonGame by HgssGame(HgssVersion.HEARTGOLD_US) {
            override val world = world
        }
    }

    private val state by lazy { HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load("cliff_edge_gate_workers")) }

    @Test
    fun `two workers side by side are on another level from the slope, never each other's blocker`() {
        val field = assertNotNull(state.field)
        assertEquals(279 to (18 to 46), field.mapId to (field.x to field.y))
        val survey = ReachSurvey(game, state, ActionSettings(hideDestinations = true))
        assertEquals(Reachability(requiresIntermediateWarp = true), survey.of("person:0"))
        assertEquals(Reachability(requiresIntermediateWarp = true), survey.of("person:1"))
        // Behind them, the way to the other exit and to the woman crosses them: they are named there.
        assertTrue(survey.of("warp:0").blockedByPerson?.id in setOf("person:0", "person:1"))
        assertTrue(survey.of("person:2").blockedByPerson?.id in setOf("person:0", "person:1"))
    }

    @Test
    fun `the save's flags tell that the Route 5 man holds the only way back from the underground gate`() {
        val flags = assertNotNull(state.eventFlags)
        assertEquals(false, flags[0x258], "the Power Plant isn't fixed: the man is there")
        val world = HgssWorldRom.require()
        // Out of the Route 5 Underground Path Gate (map 469, warp 0, its exit mat pressed south) onto Route 5's entrance
        // (1306,185): entered going north ([WarpTrigger.Enter]), its only free side (1306,186) held by the man
        // (obj_R05_gsmiddleman1, FLAG_UNK_258).
        val out = WorldLinks.links(world, assertNotNull(world.areaOf(469)), 469).single { it.id == "warp:0" }
        assertEquals(ZoneLink(ZoneLink.Kind.WARP, "warp:0", 469, 5, 9, WarpTrigger.Press(Direction.SOUTH), 13, 1306, 185), out)
        val entrance = assertNotNull(world.areaOf(13)).warps.single { it.zone == 13 && it.x == 1306 && it.y == 185 }
        assertEquals(WarpTrigger.Enter, entrance.trigger)
        assertTrue(WorldLinks.noWayBack(world, out, RouteOptions(), flags))
        // Once he is gone (every flag set: whoever hides behind one is gone), the entrance is stepped off and on again.
        assertFalse(WorldLinks.noWayBack(world, out, RouteOptions(), dev.kotlinds.pokemonclient.state.EventFlags(ByteArray(0x1000) { -1 })))
        // The snapshot is the save's: Elder Li beaten (the Zephyr Badge is owned).
        assertEquals(true, flags[HgssProgress.FLAG_BEAT_SPROUT_ELDER])
    }
}
