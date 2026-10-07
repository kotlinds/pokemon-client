package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.GameState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The story goal and blockers on real HeartGold saves: the agent's run with 8 Johto badges, which lost to the
 * Kimono Girls (bench states `ecruteak*.state`, `pond.state`; `story_bell_tower_gate` walked from `pond.state` to the
 * Bell Tower gate with `go_to warp:2`).
 */
class HgssStoryFixtureTest {

    private fun state(name: String): GameState = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load(name))

    @Test
    fun theReaderReadsTheBadgesAndTheStoryFlags() {
        val story = assertNotNull(HgssReader(HgssFixtures.load("story_ecruteak")).read()?.story)
        assertEquals((0..7).toSet(), story.badges)
        // Done on this save: Sudowoodo, Burned Tower, Radio Tower, Dragon's Den, Elm visited after the 8th badge.
        assertTrue(story.flag(HgssStoryTable.Flags.SUDOWOODO_GONE))
        assertTrue(story.flag(HgssStoryTable.Flags.BEAT_RADIO_TOWER_ROCKETS))
        assertTrue(story.flag(HgssStoryTable.Flags.PASSED_DRAGONS_DEN))
        assertEquals(2, story.variable(HgssStoryTable.Vars.NEW_BARK_EAST_EXIT))
        assertEquals(5, story.variable(HgssStoryTable.Vars.DANCE_THEATER))
        // Not done: the Kimono Girls (Clear Bell), Ho-Oh, the League.
        assertTrue(!story.flag(HgssStoryTable.Flags.BEAT_KIMONO_GIRLS))
        assertTrue(!story.flag(HgssStoryTable.Flags.HO_OH_DONE))
        assertTrue(!story.flag(HgssStoryTable.Flags.GAME_CLEAR))
    }

    @Test
    fun eightBadgesPointToTheKimonoGirls() {
        for (name in listOf("story_ecruteak", "story_ecruteak_city", "story_pond", "story_ecruteak_gym", "story_bike")) {
            val story = assertNotNull(state(name).story, name)
            assertEquals("johto:kimono_girls", story.goal?.id, name)
            assertTrue("Kimono Girls" in story.goal!!.description, name)
        }
    }

    @Test
    fun nothingBlocksInEcruteakAndTheGymPitsAreNotStoryBlockers() {
        for (name in listOf("story_ecruteak", "story_ecruteak_city", "story_pond", "story_ecruteak_gym")) {
            assertEquals(emptyList(), state(name).story?.blockers, name)
        }
    }

    @Test
    fun theBellTowerGateSageIsABlockerUntilHoOh() {
        val state = state("story_bell_tower_gate")
        assertEquals(83, state.field?.mapId)
        val blockers = assertNotNull(state.story).blockers
        assertEquals(listOf("person:0"), blockers.map { it.target })
        assertTrue("Fog Badge" in blockers.single().reason)
        // The sage is a live person of the map.
        assertTrue(state.field!!.objects.any { it.id == "person:0" })
    }

    /**
     * The same real state as the agent reads it (race notes: `blocked_by` was sent only with a walkthrough): the sage
     * is listed at every knowledge level, where he stands; why he blocks (the Fog Badge) only with a walkthrough.
     */
    @Test
    fun theBellTowerGateSageIsToldAtEveryKnowledgeLevel() {
        val state = state("story_bell_tower_gate")
        val game = dev.kotlinds.pokemonclient.actions.FakeGame(state.screen) { state }
        for (level in dev.kotlinds.pokemonclient.data.KnowledgeLevel.entries) {
            val view = dev.kotlinds.pokemonclient.view.AgentView(game).describe(
                state, emptyList(), dev.kotlinds.pokemonclient.view.AgentOptions(knowledge = level), dev.kotlinds.pokemonclient.view.AgentView.Detail.STANDARD,
            )
            val line = assertNotNull(view[dev.kotlinds.pokemonclient.view.AgentView.BLOCKED_BY], "$level").toString()
            assertTrue("person:0" in line && "stands in the way" in line, "$level: $line")
            assertEquals(level.allows(dev.kotlinds.pokemonclient.data.KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH), "Fog Badge" in line, "$level: $line")
        }
    }

    @Test
    fun inKantoEveryGymLeftIsAnOpenGoal() {
        // The user's save on Cinnabar Island (bench `load` of the save, then `go_to "Cinnabar Island"`): Thunder,
        // Rainbow, Soul, Marsh and Volcano. Misty and Brock are both open, not only the first in the table.
        val story = assertNotNull(state("story_kanto_cinnabar").story)
        assertEquals(listOf("kanto:badge_cascade", "kanto:badge_boulder"), story.openGoals.map { it.id })
        assertEquals("kanto:badge_cascade", story.goal?.id)
        val (cerulean, pewter) = story.openGoals.map { it.description }
        assertTrue("Cerulean City" in cerulean && "Pewter City" in pewter, "$cerulean / $pewter")
    }
}
