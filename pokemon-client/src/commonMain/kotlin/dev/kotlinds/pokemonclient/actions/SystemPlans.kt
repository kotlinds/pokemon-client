package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.runtime.kind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.IntroInputs
import dev.kotlinds.pokemonclient.state.IntroStage
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.sameAs

/** Recipes acting on the console / game as a whole (not a screen of the story). */
internal object SystemPlans {

    /** The soft reset of the DS Pokémon games: L + R + START + SELECT held together (src/main.c main loop). */
    private val RESET_COMBO = InputFrame(setOf(Button.L, Button.R, Button.START, Button.SELECT))

    /** The intro stages [continueGame] goes through (the new-game intro is not one: it starts another game). */
    private val BEFORE_THE_GAME = setOf(IntroStage.LOADING, IntroStage.INTRO_MOVIE, IntroStage.TITLE_SCREEN, IntroStage.MAIN_MENU)

    /** True on the screens [continueGame] starts from: the intro movie, the title screen, the main menu, loading. */
    fun beforeTheGame(state: GameState): Boolean {
        val screen = state.screen
        return state.field == null &&
            (screen is Screen.Intro && screen.stage in BEFORE_THE_GAME || screen is Screen.ListMenu && screen.kind == MenuKind.MAIN_MENU)
    }

    /**
     * Soft reset, then back to the saved game: holds L + R + START + SELECT until the game restarts (it refuses while
     * it saves: `gSystem.softResetDisabled`), then goes through the intro to CONTINUE like [continueGame]. Everything
     * since the last save is lost.
     */
    val softReset = ActionPlan<GameAction.SoftReset> { _, context ->
        val before = context.state().field
        val restarted = context.scope.stepUntil(RESET_FRAMES, RESET_COMBO) { memory ->
            val state = context.game.state(memory)
            state.field == null && state.screen is Screen.Intro
        }
        context.scope.step(RELEASE_FRAMES)
        if (!restarted) {
            return@ActionPlan ActionOutcome.Failed(ActionError.Timeout("the game didn't restart (it refuses while saving): try again in a moment"))
        }
        toSavedGame(context, before, done = "reset: back to the last save")
    }

    /**
     * From the intro movie, the title screen, the main menu (with or without its highlight: reached with buttons or by
     * touch) or the loading between them, into the saved game: passes each intro screen with an input it takes now
     * (read from RAM, [Screen.Intro.goesOnWith]), picks CONTINUE on the main menu by its id and waits until the game
     * runs. Each input must change the screen; one that is ignored is followed by the next input the screen takes,
     * at most [RetryPolicy.maxCorrections] times, then [ActionError.InputIgnored].
     */
    val continueGame = ActionPlan<GameAction.ContinueGame> { _, context ->
        val state = context.state()
        if (!beforeTheGame(state)) {
            return@ActionPlan ActionOutcome.Failed(ActionError.UnexpectedScreen("the intro movie, the title screen or the main menu", state.screen.kind))
        }
        toSavedGame(context, before = null, done = "continued the saved game")
    }

    /**
     * Goes from the intro screens to the saved game, every step checked on the screen read from RAM. Ends once the
     * game runs in the field and waits for the player: walking, or any screen of the game (a message shown on
     * arrival). Fails with [ActionError.NoSavedGame] when a new game's intro starts instead (no save), and with
     * [ActionError.CommunicationError] when the game stops on its communication error. [before]: where the player was
     * before a reset, told in the result.
     */
    private fun toSavedGame(context: PlanContext, before: FieldState?, done: String): ActionOutcome {
        val start = context.scope.framesUsed
        val ignored = mutableListOf<String>()
        val maxCorrections = RetryPolicy().maxCorrections
        while (context.scope.framesUsed - start < MAX_FRAMES) {
            val state = context.navigator.settle(maxFrames = ROUND_FRAMES)
            val screen = state.screen
            val field = state.field
            when {
                field != null && screen !is Screen.Intro && screen.awaiting == Awaiting.INPUT -> {
                    val was = before?.let { " (you were at ${it.mapName} ${it.x},${it.y})" } ?: ""
                    val on = if (screen is Screen.Overworld) "" else ", on ${screen.kind}"
                    return ActionOutcome.Done("$done, at ${field.mapName} ${field.x},${field.y}$was$on")
                }
                screen is Screen.ListMenu && screen.kind == MenuKind.MAIN_MENU -> {
                    val chosen = context.navigator.choose(Screen.ListMenu::class, "CONTINUE") { it.id == CONTINUE }
                    if (chosen is Step.Failed) return ActionOutcome.Failed(chosen.error)
                    ignored.clear()
                }
                screen is Screen.Intro && screen.awaiting == Awaiting.INPUT && screen.goesOnWith != null -> {
                    val input = IntroInput.nth(screen.goesOnWith, ignored.size)
                    input.perform(context)
                    if (changes(context, screen)) {
                        ignored.clear()
                    } else {
                        ignored += input.description
                        if (ignored.size > maxCorrections) {
                            return ActionOutcome.Failed(ActionError.InputIgnored(screen.kind, ignored.toList(), ignored.size))
                        }
                    }
                }
                field == null && screen is Screen.PressToContinue && screen.reason == ContinueReason.COMMUNICATION_ERROR ->
                    return ActionOutcome.Failed(ActionError.CommunicationError)
                // Without a save the main menu is skipped: the professor's speech of a new game starts.
                field == null && (screen is Screen.Intro && screen.stage == IntroStage.NEW_GAME_INTRO ||
                    screen is Screen.Dialogue && screen.source == TextSource.INTRO) ->
                    return ActionOutcome.Failed(ActionError.NoSavedGame)
                field == null && (screen is Screen.Selectable || screen is Screen.Dialogue || screen is Screen.PressToContinue) ->
                    return ActionOutcome.Failed(ActionError.UnexpectedScreen("the title screen, main menu or the saved game", screen.kind))
                else -> context.scope.step(WAIT_FRAMES)
            }
        }
        return ActionOutcome.Failed(ActionError.Timeout("the game didn't get to the saved game in ${MAX_FRAMES / 60} s"))
    }

    /** Steps until the screen differs from [before] (another stage, or input no longer read), at most [PASS_FRAMES]. */
    private fun changes(context: PlanContext, before: Screen): Boolean {
        var waited = 0
        while (waited < PASS_FRAMES) {
            context.scope.step(2)
            waited += 2
            if (!context.state().screen.sameAs(before)) return true
        }
        return false
    }

    /** One input that passes an intro screen: a button, or a touch of the bottom screen. */
    private sealed interface IntroInput {
        val description: String
        fun perform(context: PlanContext)

        data class Press(val button: Button) : IntroInput {
            override val description get() = "press ${button.name.lowercase()}"
            override fun perform(context: PlanContext) {
                context.scope.tap(button)
            }
        }

        data class Touch(val point: TouchPoint) : IntroInput {
            override val description get() = "touch ${point.x},${point.y}"
            override fun perform(context: PlanContext) = context.scope.touch(point)
        }

        companion object {
            /** The [n]th input to try (cycling): the screen's buttons in order (A first), then a touch when it takes one. */
            fun nth(inputs: IntroInputs, n: Int): IntroInput {
                val all = inputs.buttons.sortedBy { it.ordinal }.map(::Press) +
                    if (inputs.touchAnywhere) listOf(Touch(BOTTOM_SCREEN_CENTER)) else emptyList()
                return all[n % all.size]
            }
        }
    }

    private const val CONTINUE = "option:continue"

    /** Where "touch anywhere" touches: the middle of the bottom screen (256 x 192). */
    private val BOTTOM_SCREEN_CENTER = TouchPoint(128, 96)

    /** The game reads the keys every frame: it restarts within a few frames of the combo. */
    private const val RESET_FRAMES = 60
    private const val RELEASE_FRAMES = 4

    /** From power-on or a reset to the field: about 20 s of intro, title, main menu and loading (60 frames a second). */
    private const val MAX_FRAMES = 3600

    /** An input taken shows within a few frames (the movie ends, the title flashes); half a second is plenty. */
    private const val PASS_FRAMES = 30
    private const val ROUND_FRAMES = 300
    private const val WAIT_FRAMES = 10
}
