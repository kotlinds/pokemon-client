package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.runtime.InputProbe
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The side a PC is used from is the game's rule ([PokemonGame.pcFacing]), not the common layer's (review impl13 B9):
 * Gen 4 answers only when faced from the south (src/field/field_control.c); a game that doesn't say is used from any side.
 */
class PcSideTest {

    /** A 3×3 floor with a PC at 1,1, the player at 0,0, the game's rule [facing]. */
    private fun game(facing: Direction?) = object : PokemonGame {
        override val name = "PC"
        override val inputProbe = InputProbe { emptySet() }
        override val recipes = Recipes()
        override val world = ScriptedUi.world(3, 3, mapOf((1 to 1) to TileInfo(true, TileKind.Pc)))
        override val pcFacing = facing
        override fun state(memory: Memory) =
            GameState(0, Screen.Overworld(awaiting = Awaiting.INPUT), null, emptyList(), null, null, ScriptedUi.field(0, 0, Direction.SOUTH))
    }

    private fun sides(game: PokemonGame): Set<Pair<Int, Int>> {
        val state = game.state(dev.kotlinds.pokemonclient.ZeroMemory)
        val target = MovePlans.resolve(game, state, ActionSettings(), MovePlans.PC, null, null)!!
        return listOf(0 to 1, 2 to 1, 1 to 0, 1 to 2).filter { (x, y) -> target.isGoal!!(Node(x, y)) }.toSet()
    }

    @Test
    fun `a Gen 4 PC is used from the south, a game that doesn't say from any side`() {
        assertEquals(setOf(1 to 2), sides(game(Direction.NORTH)))
        assertEquals(setOf(0 to 1, 2 to 1, 1 to 0, 1 to 2), sides(game(null)))
        assertEquals(Direction.NORTH, dev.kotlinds.pokemonclient.games.hgss.HgssGame(dev.kotlinds.pokemonclient.games.hgss.HgssVersion.HEARTGOLD_US).pcFacing)
    }

    @Test
    fun `go_to onto a PC says the side the game uses it from`() {
        fun said(game: PokemonGame) = MovePlans.obstacleTarget(game, game.state(dev.kotlinds.pokemonclient.ZeroMemory).field!!, ActionSettings(), 1, 1)!!.detail
        assertTrue("a PC (use it from the tile south of it: interact pc)" in said(game(Direction.NORTH)), said(game(Direction.NORTH)))
        assertTrue("a PC (interact pc)" in said(game(null)), said(game(null)))
    }
}
