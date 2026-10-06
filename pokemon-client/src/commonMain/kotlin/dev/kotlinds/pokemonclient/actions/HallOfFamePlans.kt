package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.runtime.ActionProgress
import dev.kotlinds.pokemonclient.runtime.ProgressUnit
import dev.kotlinds.pokemonclient.state.kind
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.HallOfFameStage
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.ViewerApp

/**
 * The sequence after the Champion's defeat, from the screens read in RAM: the registration in the Hall of Fame (each
 * team member presented in turn, then the whole team, which waits for A), the save, then the credits.
 */
internal object HallOfFamePlans {

    /** The screens [watchHallOfFame] starts from: the registration in the Hall of Fame, or the save after it. */
    fun offered(state: GameState): Boolean = when (val screen = state.screen) {
        is Screen.Viewer -> screen.app == ViewerApp.HALL_OF_FAME_REGISTER
        is Screen.Animation -> screen.kind == AnimationKind.SAVING
        else -> false
    }

    /**
     * Waits through the Hall of Fame and gives control back once the credits start (or at "The End", or the title
     * screen, should they already be past): reports each team member presented ("watch_hall_of_fame: 3/6 Pokémon
     * presented, TYPHLOSION"), presses A only when the whole team's screen waits for it (read from RAM: A pressed
     * during the animation is ignored), checks that it was taken (at most [RetryPolicy.maxCorrections] more presses,
     * then [ActionError.InputIgnored]) and waits while the game saves.
     */
    val watchHallOfFame = ActionPlan<GameAction.WatchHallOfFame> { _, context ->
        val first = context.state()
        if (!offered(first)) {
            return@ActionPlan ActionOutcome.Failed(ActionError.UnexpectedScreen("the Hall of Fame or the save after it", first.screen.kind))
        }
        val team = first.party.filter { !it.isEgg }.map { it.displayName }
        val start = context.scope.framesUsed
        var reported: Int? = null
        var presses = 0
        while (context.scope.framesUsed - start < MAX_FRAMES) {
            val state = context.state()
            val screen = state.screen
            when {
                screen is Screen.Viewer && screen.app == ViewerApp.HALL_OF_FAME_REGISTER -> {
                    val stage = screen.hallOfFame
                    if (stage is HallOfFameStage.Presenting && stage.index != reported) {
                        reported = stage.index
                        context.scope.report(ActionProgress(ACTION, stage.index, stage.count, ProgressUnit.POKEMON_PRESENTED, stage.name))
                    }
                    if (stage == HallOfFameStage.WholeTeam && screen.awaiting == Awaiting.INPUT) {
                        if (presses > RetryPolicy().maxCorrections) {
                            return@ActionPlan ActionOutcome.Failed(ActionError.InputIgnored(screen.kind, List(presses) { "press a" }, presses))
                        }
                        presses++
                        context.scope.tap(Button.A)
                        context.navigator.awaitChange(screen, maxFrames = PRESS_FRAMES)
                    } else {
                        context.scope.step(WAIT_FRAMES)
                    }
                }
                screen is Screen.Animation && screen.kind == AnimationKind.CREDITS ->
                    return@ActionPlan ActionOutcome.Done(registered(team) + " " + (screen.hint ?: ""))
                screen is Screen.PressToContinue && screen.reason == ContinueReason.THE_END ->
                    return@ActionPlan ActionOutcome.Done(registered(team) + " The credits are over: " + (screen.text ?: "A restarts the game"))
                screen is Screen.Intro ->
                    return@ActionPlan ActionOutcome.Done(registered(team) + " The game restarted at the title screen: continue_game goes on")
                // Anything else that waits for the player isn't part of the sequence: the agent decides.
                screen.awaiting == Awaiting.INPUT ->
                    return@ActionPlan ActionOutcome.Failed(ActionError.UnexpectedScreen("the Hall of Fame, the save or the credits", screen.kind))
                else -> context.scope.step(WAIT_FRAMES)
            }
        }
        ActionOutcome.Failed(ActionError.Timeout("the Hall of Fame didn't reach the credits in ${MAX_FRAMES / 60} s"))
    }

    private fun registered(team: List<String>) =
        "The team (${team.joinToString()}) is in the Hall of Fame."

    private const val ACTION = "watch_hall_of_fame"

    /**
     * From the first team member presented to the credits: about 9 s per member, the whole team's animation, the save
     * and the fades (about 80 s with six); twice that before giving up.
     */
    private const val MAX_FRAMES = 60 * 180

    /** How often the screen is read while the sequence plays. */
    private const val WAIT_FRAMES = 10

    /** A taken press starts the last flash within a few frames. */
    private const val PRESS_FRAMES = 60
}
