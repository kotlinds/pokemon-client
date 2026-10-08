package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.actions.ActionMode
import dev.kotlinds.pokemonclient.actions.ActionRegistry
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.HallOfFameStage
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.ViewerApp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * After a live League win (HeartGold USA, Lance beaten on the bench): the Hall of Fame, the save, the credits and "The
 * End", read from RAM ([HgssGameClear]). Only the whole team's wait and "The End" take input; `watch_hall_of_fame` is
 * offered on the Hall of Fame and the save, `soft_reset` not while the game saves.
 */
class HgssGameClearFixtureTest {

    private val game = HgssGame(HgssVersion.HEARTGOLD_US)
    private val registry = ActionRegistry.of()

    private fun state(fixture: String): GameState = game.state(HgssFixtures.load(fixture))

    private fun offered(state: GameState) = registry.available(state, ActionMode.ASSISTED, game).map { it.name }

    @Test
    fun eachTeamMemberIsPresentedWithoutTakingInput() {
        val state = state("hof_presenting")
        val screen = assertIs<Screen.Viewer>(state.screen)
        assertEquals(ViewerApp.HALL_OF_FAME_REGISTER, screen.app)
        assertEquals(HallOfFameStage.Presenting(1, 6, "AMPHAROS"), screen.hallOfFame)
        assertEquals(Awaiting.ANIMATION, screen.awaiting)
        assertEquals("presenting 1/6: AMPHAROS", screen.details.last())
        assertTrue("watch_hall_of_fame" in offered(state))
    }

    @Test
    fun theWholeTeamWaitsForAOnlyOnceItsAnimationIsOver() {
        val animating = assertIs<Screen.Viewer>(state("hof_whole_team").screen)
        assertEquals(HallOfFameStage.WholeTeam, animating.hallOfFame)
        assertEquals(Awaiting.ANIMATION, animating.awaiting)

        val waiting = assertIs<Screen.Viewer>(state("hof_whole_team_waits").screen)
        assertEquals(HallOfFameStage.WholeTeam, waiting.hallOfFame)
        assertEquals(Awaiting.INPUT, waiting.awaiting)
    }

    @Test
    fun afterAThePhotoFadesOutWithoutWaiting() {
        assertNotEquals(Awaiting.INPUT, state("hof_leaving").screen.awaiting)
    }

    @Test
    fun theSaveAfterTheHallOfFameIsItsOwnScreen() {
        val state = state("hof_saving")
        val screen = assertIs<Screen.Animation>(state.screen)
        assertEquals(AnimationKind.SAVING, screen.kind)
        val actions = offered(state)
        assertTrue("watch_hall_of_fame" in actions)
        assertTrue("soft_reset" !in actions, "the game refuses a reset while it saves: $actions")
    }

    @Test
    fun theFirstCreditsCantBeSkippedButASoftResetLosesNothing() {
        val state = state("credits_rolling")
        val screen = assertIs<Screen.Animation>(state.screen)
        assertEquals(AnimationKind.CREDITS, screen.kind)
        assertEquals(HgssCutsceneScreens.CREDITS_HINT, screen.hint)
        assertTrue("soft_reset" in offered(state))
        assertTrue("watch_hall_of_fame" !in offered(state))
    }

    @Test
    fun theEndWaitsForAThatRestartsTheGame() {
        val screen = assertIs<Screen.PressToContinue>(state("credits_the_end").screen)
        assertEquals(ContinueReason.THE_END, screen.reason)
    }
}
