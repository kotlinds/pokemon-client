package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.runtime.ActionProgress
import dev.kotlinds.pokemonclient.runtime.ProgressUnit
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.HallOfFameStage
import dev.kotlinds.pokemonclient.state.IntroInputs
import dev.kotlinds.pokemonclient.state.IntroStage
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.StarterStage
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.ViewerApp
import dev.kotlinds.pokemonclient.state.kind
import dev.kotlinds.pokemonclient.state.sameAs

/**
 * The common recipes: how every action is carried out in terms of screens ("open the bag, select the pocket, select
 * the item, USE..."), written once for every game. They never press blindly: every choice goes through the
 * [Navigator], which reads the cursor, moves one tap at a time and confirms only on the target.
 *
 * What a game plays ([dev.kotlinds.pokemonclient.PokemonGame.recipes]: every game gives its own instance, there is no
 * shared one); a generation or a game whose screens really differ extends it and overrides what differs
 * ([dev.kotlinds.pokemonclient.games.gen4.Gen4Recipes], then the game's own). Each action also has its availability
 * method here (`<action>Availability`, the common rule by default, built on [ActionConditions]): the one place that
 * decides when the action can run, read by the listing and the execution alike ([ActionDefinition.availability]). A
 * game overrides one only when the game itself differs, documented on the override (docs/adding-a-game.md,
 * "Overriding a condition"); the contract (names, parameters, ids, errors) never varies.
 *
 * The top of the chain of families ([BasicRecipes], [BattleRecipes], [BagPartyRecipes], [MoveRecipes],
 * [ServiceRecipes], [FieldRecipes]): it holds the recipes of the game as a whole (soft reset, continuing the saved
 * game, the starter, the Hall of Fame).
 */
open class Recipes internal constructor() : FieldRecipes() {

    // region Availability: when each action of this family can run (read by the listing and the execution alike)

    /** `soft_reset`: anywhere but while the game saves by itself; accepted on the intro screens, not offered there. */
    internal open fun softResetAvailability(state: GameState): Availability = when {
        state.screen is Screen.Intro -> Availability.Available(listed = false)
        // The game refuses the reset while it saves by itself (after the Hall of Fame).
        (state.screen as? Screen.Animation)?.kind == AnimationKind.SAVING -> Availability.Hidden
        else -> Availability.Available()
    }

    /** `continue_game`: on the intro movie, the title screen, the main menu or the loading between them. */
    internal open fun continueGameAvailability(state: GameState): Availability =
        if (ActionConditions.beforeTheGame(state)) Availability.Available() else Availability.Hidden

    /** `choose_starter`: on the professor's machine, its starters. */
    internal open fun chooseStarterAvailability(state: GameState): Availability {
        val screen = state.screen as? Screen.StarterChoice ?: return Availability.Hidden
        return Availability.Available(mapOf("starter" to screen.starters.map { Choice("species:${it.id.value}", it.name) }))
    }

    /** `watch_hall_of_fame`: on the registration in the Hall of Fame, or the save after it. */
    internal open fun watchHallOfFameAvailability(state: GameState): Availability =
        if (ActionConditions.hallOfFameOffered(state)) Availability.Available() else Availability.Hidden

    // endregion

    // region System: the console and the game as a whole

    /**
     * Soft reset, then back to the saved game: holds L + R + START + SELECT until the game restarts (it refuses while
     * it saves: `gSystem.softResetDisabled`), then goes through the intro to CONTINUE like [continueGame]. Everything
     * since the last save is lost.
     */
    override fun softReset(action: GameAction.SoftReset, context: PlanContext): ActionOutcome {
        val before = context.state().field
        val restarted = context.scope.stepUntil(RESET_FRAMES, RESET_COMBO) { memory ->
            val state = context.game.state(memory)
            state.field == null && state.screen is Screen.Intro
        }
        context.scope.step(RELEASE_FRAMES)
        if (!restarted) {
            return ActionOutcome.Failed(ActionError.Timeout("the game didn't restart (it refuses while saving): try again in a moment"))
        }
        return toSavedGame(context, before, done = "reset: back to the last save")
    }

    /**
     * From the intro movie, the title screen, the main menu (with or without its highlight: reached with buttons or by
     * touch) or the loading between them, into the saved game: passes each intro screen with an input it takes now
     * (read from RAM, [Screen.Intro.goesOnWith]), picks CONTINUE on the main menu by its id and waits until the game
     * runs. Each input must change the screen; one that is ignored is followed by the next input the screen takes,
     * at most [RetryPolicy.maxCorrections] times, then [ActionError.InputIgnored].
     */
    override fun continueGame(action: GameAction.ContinueGame, context: PlanContext): ActionOutcome {
        val state = context.state()
        if (!ActionConditions.beforeTheGame(state)) {
            return ActionOutcome.Failed(ActionError.UnexpectedScreen("the intro movie, the title screen or the main menu", state.screen))
        }
        return toSavedGame(context, before = null, done = "continued the saved game")
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
        while (context.scope.framesUsed - start < MAX_START_FRAMES) {
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
                    return ActionOutcome.Failed(ActionError.UnexpectedScreen("the title screen, main menu or the saved game", screen))
                else -> context.scope.step(INTRO_WAIT_FRAMES)
            }
        }
        return ActionOutcome.Failed(ActionError.Timeout("the game didn't get to the saved game in ${MAX_START_FRAMES / 60} s"))
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

    // endregion

    // region Story

    /**
     * The choice of a starter on the professor's machine ([Screen.StarterChoice]): turns the machine until the wanted
     * ball is in front (LEFT / RIGHT, each turn checked on the ball in front), looks at it (A), picks it (A: the
     * professor asks to confirm) and confirms (A), then waits for the machine to close. A confirmation asked for
     * another ball is declined first (B). Every press is read back on the machine's state; after a few presses that
     * change nothing, an explicit error.
     */
    override fun chooseStarter(action: GameAction.ChooseStarter, context: PlanContext): ActionOutcome {
        val first = context.navigator.settle().screen as? Screen.StarterChoice
            ?: return ActionOutcome.Failed(ActionError.UnexpectedScreen("the starter machine", context.state().screen))
        val target = first.starters.indexOfFirst { matchesRef(action.starter, "species", it.id.value, it.name) }
        if (target < 0) {
            return ActionOutcome.Failed(ActionError.InvalidParameter("starter", action.starter, first.starters.map { "species:${it.id.value}" }))
        }
        val name = first.starters[target].name
        var unchanged = 0
        repeat(STARTER_MAX_PRESSES) {
            val screen = context.navigator.settle().screen as? Screen.StarterChoice
                ?: return ActionOutcome.Done("took $name")
            val button = when {
                screen.stage == StarterStage.CONFIRMING && screen.front == target -> Button.A
                screen.stage == StarterStage.CONFIRMING -> Button.B
                // RIGHT turns the machine 0 -> 2 -> 1, LEFT the other way (checked on the ball in front anyway).
                screen.front != target -> if ((target - screen.front + 3) % 3 == 1) Button.LEFT else Button.RIGHT
                else -> Button.A
            }
            context.scope.tap(button)
            context.navigator.awaitChange(screen, maxFrames = STARTER_CHANGE_FRAMES)
            val after = context.state().screen
            if (after is Screen.StarterChoice && after.front == screen.front && after.stage == screen.stage) {
                if (++unchanged >= STARTER_MAX_UNCHANGED) {
                    return ActionOutcome.Failed(ActionError.VerificationFailed("the $name ball", "a change after $button", "${screen.starters[screen.front].name} (${screen.stage.name.lowercase()})", unchanged))
                }
            } else unchanged = 0
        }
        return ActionOutcome.Failed(ActionError.Timeout("the machine is still open after $STARTER_MAX_PRESSES presses"))
    }

    /**
     * The sequence after the Champion's defeat, from the screens read in RAM: the registration in the Hall of Fame
     * (each team member presented in turn, then the whole team, which waits for A), the save, then the credits.
     *
     * Waits through the Hall of Fame and gives control back once the credits start (or at "The End", or the title
     * screen, should they already be past): reports each team member presented ("watch_hall_of_fame: 3/6 Pokémon
     * presented, TYPHLOSION"), presses A only when the whole team's screen waits for it (read from RAM: A pressed
     * during the animation is ignored), checks that it was taken (at most [RetryPolicy.maxCorrections] more presses,
     * then [ActionError.InputIgnored]) and waits while the game saves.
     */
    override fun watchHallOfFame(action: GameAction.WatchHallOfFame, context: PlanContext): ActionOutcome {
        val first = context.state()
        if (!ActionConditions.hallOfFameOffered(first)) {
            return ActionOutcome.Failed(ActionError.UnexpectedScreen("the Hall of Fame or the save after it", first.screen))
        }
        val team = first.party.filter { !it.isEgg }.map { it.displayName }
        val start = context.scope.framesUsed
        var reported: Int? = null
        var presses = 0
        while (context.scope.framesUsed - start < HALL_OF_FAME_MAX_FRAMES) {
            val state = context.state()
            val screen = state.screen
            when {
                screen is Screen.Viewer && screen.app == ViewerApp.HALL_OF_FAME_REGISTER -> {
                    val stage = screen.hallOfFame
                    if (stage is HallOfFameStage.Presenting && stage.index != reported) {
                        reported = stage.index
                        context.scope.report(ActionProgress(HALL_OF_FAME_ACTION, stage.index, stage.count, ProgressUnit.POKEMON_PRESENTED, stage.name))
                    }
                    if (stage == HallOfFameStage.WholeTeam && screen.awaiting == Awaiting.INPUT) {
                        if (presses > RetryPolicy().maxCorrections) {
                            return ActionOutcome.Failed(ActionError.InputIgnored(screen.kind, List(presses) { "press a" }, presses))
                        }
                        presses++
                        context.scope.tap(Button.A)
                        context.navigator.awaitChange(screen, maxFrames = HALL_OF_FAME_PRESS_FRAMES)
                    } else {
                        context.scope.step(HALL_OF_FAME_WAIT_FRAMES)
                    }
                }
                screen is Screen.Animation && screen.kind == AnimationKind.CREDITS ->
                    return ActionOutcome.Done(registered(team) + " " + (screen.hint ?: ""))
                screen is Screen.PressToContinue && screen.reason == ContinueReason.THE_END ->
                    return ActionOutcome.Done(registered(team) + " The credits are over: " + (screen.text ?: "A restarts the game"))
                screen is Screen.Intro ->
                    return ActionOutcome.Done(registered(team) + " The game restarted at the title screen: continue_game goes on")
                // Anything else that waits for the player isn't part of the sequence: the agent decides.
                screen.awaiting == Awaiting.INPUT ->
                    return ActionOutcome.Failed(ActionError.UnexpectedScreen("the Hall of Fame, the save or the credits", screen))
                else -> context.scope.step(HALL_OF_FAME_WAIT_FRAMES)
            }
        }
        return ActionOutcome.Failed(ActionError.Timeout("the Hall of Fame didn't reach the credits in ${HALL_OF_FAME_MAX_FRAMES / 60} s"))
    }

    private fun registered(team: List<String>) =
        "The team (${team.joinToString()}) is in the Hall of Fame."

    // endregion

    private companion object {
        /** The soft reset of the DS Pokémon games: L + R + START + SELECT held together (src/main.c main loop). */
        private val RESET_COMBO = InputFrame(setOf(Button.L, Button.R, Button.START, Button.SELECT))

        private const val CONTINUE = "option:continue"

        /** Where "touch anywhere" touches: the middle of the bottom screen (256 x 192). */
        private val BOTTOM_SCREEN_CENTER = TouchPoint(128, 96)

        /** The game reads the keys every frame: it restarts within a few frames of the combo. */
        private const val RESET_FRAMES = 60
        private const val RELEASE_FRAMES = 4

        /** From power-on or a reset to the field: about 20 s of intro, title, main menu and loading (60 frames a second). */
        private const val MAX_START_FRAMES = 3600

        /** An input taken shows within a few frames (the movie ends, the title flashes); half a second is plenty. */
        private const val PASS_FRAMES = 30
        private const val ROUND_FRAMES = 300
        private const val INTRO_WAIT_FRAMES = 10

        private const val STARTER_MAX_PRESSES = 12
        private const val STARTER_MAX_UNCHANGED = 3
        private const val STARTER_CHANGE_FRAMES = 120

        private const val HALL_OF_FAME_ACTION = "watch_hall_of_fame"

        /**
         * From the first team member presented to the credits: about 9 s per member, the whole team's animation, the
         * save and the fades (about 80 s with six); twice that before giving up.
         */
        private const val HALL_OF_FAME_MAX_FRAMES = 60 * 180

        /** How often the screen is read while the sequence plays. */
        private const val HALL_OF_FAME_WAIT_FRAMES = 10

        /** A taken press starts the last flash within a few frames. */
        private const val HALL_OF_FAME_PRESS_FRAMES = 60
    }
}
