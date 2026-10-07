package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.games.hgss.HgssPuzzles
import dev.kotlinds.pokemonclient.state.PuzzleBoulderHole
import dev.kotlinds.pokemonclient.state.PuzzleKind
import dev.kotlinds.pokemonclient.state.PuzzleState
import dev.kotlinds.pokemonclient.state.PuzzleTile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The "not connected" answer in the Ice Path names the unsolved boulder puzzle of B1F (race Claude vs Codex: Claude
 * got "not connected… or fly: New Bark Town" six times from 1F at 11,58, 7 minutes, the puzzle being on another floor
 * it never read). Real HeartGold maps (skipped without `POKEMON_ROM`); the save's puzzle state set by hand.
 */
class IcePathPuzzleHintTest {

    /** Ice Path B1F's boulders, those of [fallen] already through their holes. */
    private fun boulders(vararg fallen: Int) = mapOf(
        HgssPuzzles.ICE_PATH_B1F to PuzzleState(
            PuzzleKind.BOULDER_HOLES, "rule",
            boulderHoles = listOf(PuzzleTile(11, 10), PuzzleTile(10, 18), PuzzleTile(18, 7), PuzzleTile(19, 19))
                .mapIndexed { i, hole -> PuzzleBoulderHole("person:$i", hole, i in fallen) },
        ),
    )

    private fun goToBlackthorn(saved: Map<Int, PuzzleState>, settings: ActionSettings = ActionSettings()): String {
        val game = RomStanding(ICE_PATH_1F, 11, 58, saved = saved)
        val failed = assertIs<ActionOutcome.Failed>(Recipes.COMMON.goTo(GameAction.GoTo(null, null, "Blackthorn City"), game.context(settings)))
        return assertIs<ActionError.Unavailable>(failed.error).message
    }

    @Test
    fun `an unsolved boulder puzzle on the way is named`() {
        val text = goToBlackthorn(boulders())
        assertTrue("boulder puzzle of Ice Path" in text && "B1F" in text && "person:0 into the hole at 11,10" in text, text)
    }

    @Test
    fun `once every boulder fell the answer doesn't name it`() {
        val text = goToBlackthorn(boulders(0, 1, 2, 3))
        assertFalse("boulder puzzle" in text, text)
    }

    @Test
    fun `while destinations are hidden it isn't named either`() {
        val game = RomStanding(ICE_PATH_1F, 11, 58, saved = boulders())
        val failed = assertIs<ActionOutcome.Failed>(Recipes.COMMON.goTo(GameAction.GoTo(null, null, "warp:1"), game.context(ActionSettings(hideDestinations = true))))
        assertFalse("boulder puzzle" in failed.error.message, failed.error.message)
    }

    @Test
    fun `below the walkthrough level it isn't named`() {
        // Review impl13 M3: that another map's puzzle lifts the way is walkthrough knowledge (the Pokédex level's
        // settings: revealHidden false, see AgentOptions.actionSettings); the walkthrough level names it (above).
        val pokedex = dev.kotlinds.pokemonclient.view.AgentOptions(knowledge = dev.kotlinds.pokemonclient.data.KnowledgeLevel.POKEDEX).actionSettings
        val text = goToBlackthorn(boulders(), pokedex)
        assertFalse("boulder puzzle" in text, text)
        val walkthrough = dev.kotlinds.pokemonclient.view.AgentOptions(knowledge = dev.kotlinds.pokemonclient.data.KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH).actionSettings
        assertTrue("boulder puzzle of Ice Path" in goToBlackthorn(boulders(), walkthrough))
    }

    @Test
    fun `HeartGold keeps the Ice Path boulders in the save`() {
        val saved = HgssPuzzles.saved(dev.kotlinds.pokemonclient.games.hgss.FakePuzzleReads(flags = setOf(0x1EA)))
        assertEquals(listOf(true, false, false, false), saved.getValue(HgssPuzzles.ICE_PATH_B1F).boulderHoles.map { it.fallen })
    }

    private companion object {
        /** `MAP_ICE_PATH_1F`. */
        const val ICE_PATH_1F = 120
    }
}
