package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.runtime.ActionProgress
import dev.kotlinds.pokemonclient.runtime.ActionScope
import dev.kotlinds.pokemonclient.runtime.ProgressUnit
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.HallOfFameStage
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.ViewerApp
import dev.kotlinds.pokemonclient.state.ViewerExit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `watch_hall_of_fame`: like HeartGold's (bench, a League win), each team member is presented for a while with input
 * ignored, then the whole team animates, then waits for A; the game saves, then the credits start. The recipe only
 * presses when the screen waits for it, reports each member, and ends at the credits.
 */
class WatchHallOfFameRecipeTest {

    private val names = listOf("AMPHAROS", "Kenya", "TYPHLOSION")

    private fun viewer(stage: HallOfFameStage, awaiting: Awaiting = Awaiting.ANIMATION) =
        Screen.Viewer(ViewerApp.HALL_OF_FAME_REGISTER, ViewerExit(button = Button.A), awaiting, hallOfFame = stage)

    private val credits = Screen.Animation(AnimationKind.CREDITS, "the credits")
    private val saving = Screen.Animation(AnimationKind.SAVING)

    /**
     * The sequence on a timeline: [PER_MON] frames per member, the whole team animating for [PER_MON] more, then its
     * wait until A ([takesA]: whether A is taken), then [PER_MON] of save before the credits.
     */
    private fun game(takesA: Boolean = true): ScriptedUi {
        val ui = ScriptedUi(viewer(HallOfFameStage.Presenting(1, names.size, names[0])))
        var savedFrom: Long? = null
        ui.game.onFrame = { frame, screen ->
            val waiting = viewer(HallOfFameStage.WholeTeam, Awaiting.INPUT)
            when {
                savedFrom != null -> if (frame - savedFrom!! >= PER_MON) credits else saving
                screen == waiting -> screen
                frame < PER_MON * names.size -> (frame / PER_MON).toInt().let { viewer(HallOfFameStage.Presenting(it + 1, names.size, names[it])) }
                frame < PER_MON * (names.size + 1) -> viewer(HallOfFameStage.WholeTeam)
                else -> waiting
            }
        }
        ui.onA = { screen, _ ->
            if (takesA && screen is Screen.Viewer && screen.awaiting == Awaiting.INPUT) {
                savedFrom = ui.frame
                viewer(HallOfFameStage.Leaving)
            } else screen
        }
        return ui
    }

    private fun run(ui: ScriptedUi, reports: MutableList<ActionProgress> = mutableListOf()): ActionOutcome {
        val scope = ActionScope(ui.game.console, ui.game.inputProbe, onProgress = { reports += it })
        return HallOfFamePlans.watchHallOfFame.run(GameAction.WatchHallOfFame, PlanContext(scope, ui.game))
    }

    @Test
    fun waitsThroughThePresentationPressesAOnceAndEndsAtTheCredits() {
        val ui = game()
        val pressedAt = mutableListOf<Long>()
        val press = ui.game.onPress
        ui.game.onPress = { button, screen -> pressedAt += ui.frame; press(button, screen) }
        val reports = mutableListOf<ActionProgress>()

        val done = assertIs<ActionOutcome.Done>(run(ui, reports))

        assertEquals(listOf(Button.A), ui.game.presses)
        assertTrue(pressedAt.single() >= PER_MON * (names.size + 1), "pressed before the whole team waited: $pressedAt")
        assertEquals(names, reports.map { it.place })
        assertEquals(listOf(1, 2, 3), reports.map { it.done })
        assertTrue(reports.all { it.total == names.size && it.unit == ProgressUnit.POKEMON_PRESENTED })
        assertEquals("watch_hall_of_fame: 2/3 Pokémon presented, Kenya", reports[1].text)
        assertTrue("the credits" in done.detail!!)
        assertEquals(credits, ui.game.screen)
    }

    @Test
    fun startsFromTheSaveToo() {
        val ui = ScriptedUi(saving)
        ui.game.onFrame = { frame, _ -> if (frame >= PER_MON) credits else saving }
        assertIs<ActionOutcome.Done>(run(ui))
        assertTrue(ui.game.presses.isEmpty())
    }

    @Test
    fun anIgnoredAEndsInAnExplicitError() {
        val outcome = assertIs<ActionOutcome.Failed>(run(game(takesA = false)))
        val error = assertIs<ActionError.InputIgnored>(outcome.error)
        assertEquals(4, error.attempts) // the first press, then 3 corrections
    }

    @Test
    fun isRefusedElsewhere() {
        val outcome = assertIs<ActionOutcome.Failed>(run(ScriptedUi(ScriptedUi.OVERWORLD)))
        assertIs<ActionError.UnexpectedScreen>(outcome.error)
    }

    private companion object {
        const val PER_MON = 120L
    }
}
