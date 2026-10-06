package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.view.MapView
import dev.kotlinds.pokemonclient.world.LiveObject
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.RouteOptions
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The sloped walkways of Whirl Islands B2F (map 243) on a real save (bench, DeSmuME, 16-badge save; skipped without
 * `POKEMON_ROM`): `pz_whirl_b2f_bottom`, the player at the foot of the walkways (31,44, height 0), next to warp:5.
 *
 * NOTES (map randomizer run, 2:37): `go_to warp:4` from there (31,31, 24 field units up the walkways) timed out three
 * times ("the game refused 6 steps"): between the walkways, rows of tiles no BDHC plate covers were taken for floor at
 * any height, so the routes cut through the void; the game sees them at height 0 and refuses the step from the walkway.
 * Live after the fix: `go_to warp:4` walked the 54 tiles up the walkways and took the warp.
 */
class HgssWalkwaysFixtureTest {

    private val field get() = assertNotNull(HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load("pz_whirl_b2f_bottom")).field)

    @Test
    fun `the void between the walkways is at height 0`() {
        val area = assertNotNull(HgssWorldRom.require().areaOf(WHIRL_B2F))
        // Row 33: the walkway (30..31) at 288, then unblocked floor no plate covers; row 32 slopes up to 336.
        assertEquals(listOf(288), area.tile(30, 33)!!.heights)
        assertEquals(listOf(0), area.tile(33, 33)!!.heights)
        assertTrue(!area.tile(33, 33)!!.blocked)
        assertEquals(listOf(296), area.tile(33, 32)!!.heights)
        assertEquals(listOf(288), area.tile(31, 31)!!.heights)
    }

    @Test
    fun `go_to warp 4 climbs the walkways without stepping into the void`() {
        val world = HgssWorldRom.require()
        val area = assertNotNull(world.areaOf(WHIRL_B2F))
        val start = field
        assertEquals(WHIRL_B2F to (31 to 44), start.mapId to (start.x to start.y))
        val pathfinder = Pathfinder(area, Overlay(objects = start.objects.map { LiveObject(it.x, it.y, it.facing, isFollower = it.kind == dev.kotlinds.pokemonclient.state.FieldObjectKind.FOLLOWER) }))
        val found = assertIs<Pathfinder.Result.Found>(pathfinder.route(pathfinder.nodeOf(start), RouteOptions(), setOf(31 to 31)) { it.x == 31 && it.y == 31 })
        var height = 0
        for (node in found.route.edges.flatMap { it.tiles }) {
            val next = area.tile(node.x, node.y)!!.heights[node.level]
            assertTrue(kotlin.math.abs(next - height) <= RouteOptions.DEFAULT_MAX_CLIMB, "a climb from $height to $next onto ${node.x},${node.y}")
            height = next
        }
        assertEquals(288, height)
        // Up the whole spiral: 54 tiles live.
        assertTrue(found.route.edges.size >= 50, "${found.route.edges.size} moves")
    }

    @Test
    fun `the levels show the void apart from the walkways`() {
        val world = HgssWorldRom.require()
        val view = MapView.render(assertNotNull(world.areaOf(WHIRL_B2F)), field, world = world)
        val levels = assertNotNull(view["levels"]).jsonArray.map { it.jsonPrimitive.content }
        // y 39: the void (x 24..35) under the walkway that ends at 36..37; y 44: the floor the player stands on.
        assertEquals("  39 0 0 0 0 0 0 0 0 0 0 0 0 1 1  ", levels[0])
        assertTrue(levels[5].startsWith("  44 1 1 1"), levels[5])
        assertTrue("you are on level 1" in view["levels_legend"]!!.jsonPrimitive.content)
    }

    private companion object {
        /** `MAP_WHIRL_ISLANDS_B2F` (D40R0104). */
        const val WHIRL_B2F = 243
    }
}
