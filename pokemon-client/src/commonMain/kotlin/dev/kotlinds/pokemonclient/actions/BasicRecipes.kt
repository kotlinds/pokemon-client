package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.console.TouchPoint
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.IncomingCall
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.PartyPurpose
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource
import dev.kotlinds.pokemonclient.state.kind
import dev.kotlinds.pokemonclient.state.sameAs

/**
 * The recipes of the raw controls and of the generic screen actions: `press`, `touch`, `wait`, `drag`,
 * `advance_dialogue`, `choose` and `enter_text`. The first family of the chain of [RecipeBase] (see there why a chain
 * of classes); the families above it and a game's own recipes may override any of these.
 */
abstract class BasicRecipes internal constructor() : RecipeBase() {

    /** One self-checking tap, then wait for the game to react. */
    override fun press(action: GameAction.Press, context: PlanContext): ActionOutcome {
        val before = context.state().screen
        val tap = context.scope.tap(action.button)
        context.navigator.awaitChange(before, maxFrames = PRESS_CHANGE_FRAMES)
        context.navigator.settle(maxFrames = REACTION_FRAMES)
        return if (tap.registered) ActionOutcome.Done() else ActionOutcome.Done("the game didn't read the button (it may be busy)")
    }

    override fun touch(action: GameAction.Touch, context: PlanContext): ActionOutcome {
        context.scope.touch(action.point)
        context.navigator.settle(maxFrames = REACTION_FRAMES)
        return ActionOutcome.Done()
    }

    /** Runs [GameAction.Wait.frames] frames, or until the game expects input again. */
    override fun wait(action: GameAction.Wait, context: PlanContext): ActionOutcome {
        val frames = action.frames
        when {
            action.untilChange -> {
                val before = context.state().screen
                val start = context.scope.frame
                context.navigator.awaitChange(before, maxFrames = frames ?: MAX_WAIT_FRAMES)
                if (context.state().screen.sameAs(before)) return ActionOutcome.Done("nothing changed in ${context.scope.frame - start} frames")
            }
            frames != null -> context.scope.step(frames)
            else -> context.navigator.settle(maxFrames = MAX_WAIT_FRAMES)
        }
        return ActionOutcome.Done()
    }

    /** Holds the stylus from [GameAction.Drag.from] to [GameAction.Drag.to], one small move per frame, then lifts it. */
    override fun drag(action: GameAction.Drag, context: PlanContext): ActionOutcome {
        val before = context.state().screen
        val (from, to, frames) = action
        for (i in 0..frames) {
            val point = TouchPoint(from.x + (to.x - from.x) * i / frames, from.y + (to.y - from.y) * i / frames)
            context.scope.step(1, InputFrame(touch = point))
        }
        context.scope.step(DRAG_HOLD_FRAMES, InputFrame(touch = to))
        context.scope.step(DRAG_RELEASE_FRAMES)
        context.navigator.awaitChange(before, maxFrames = DRAG_REACTION_FRAMES)
        val after = context.navigator.settle(maxFrames = DRAG_REACTION_FRAMES).screen
        return ActionOutcome.Done(
            when {
                !after.sameAs(before) -> if (after.kind == before.kind) "the screen changed (still ${after.kind})" else "now: ${after.kind}"
                after is Screen.Unknown -> "dragged; this screen isn't decoded: a screenshot shows what moved"
                else -> "the screen didn't change"
            },
        )
    }

    /** Presses A through messages, stopping at the first choice, menu or back in control (on a choice already: nothing). */
    override fun advanceDialogue(action: GameAction.AdvanceDialogue, context: PlanContext): ActionOutcome {
        var started = context.state().screen
        if (started is Screen.Selectable) return ActionOutcome.Done("already on a choice: nothing to read")
        // The phone rings while walking: answer it (touch the Pokégear), then read the call like any call.
        val call = (started as? Screen.Overworld)?.incomingCall
        if (call != null) {
            when (val answered = answerCall(context, call)) {
                is Step.Failed -> return ActionOutcome.Failed(answered.error)
                is Step.Done -> started = answered.value
            }
        }
        val startFrame = context.scope.frame
        var outOfTime = false
        return context.navigator.advanceUntil { state ->
            val screen = state.screen
            val done = screen !is Screen.Dialogue && screen !is Screen.PressToContinue && screen !is Screen.Battle && screen !is Screen.Animation ||
                screen is Screen.Battle && screen.awaiting == Awaiting.INPUT
            // A long scene (Cherrygrove's guided tour): hand the turn back in time, so the answer reaches the agent
            // before its call times out (an answer that late is taken as lost and repeated).
            outOfTime = !done && context.scope.frame - startFrame > MAX_ADVANCE_FRAMES
            done || outOfTime
        }.then { end ->
            if (outOfTime) return@then ActionOutcome.Done("still reading after ~${MAX_ADVANCE_FRAMES / 60} s: call advance_dialogue again to read on")
            // A call ends on the Pokégear's contact list (it opened by itself for an incoming call): read, so close it.
            val phoneLeftOpen = (started as? Screen.Dialogue)?.source == TextSource.PHONE &&
                (end.screen as? Screen.ListMenu)?.kind == MenuKind.PHONE_CONTACTS
            if (phoneLeftOpen) {
                closeToOverworld(context)
                ActionOutcome.Done("call ended, Pokégear closed")
            } else ActionOutcome.Done()
        }
    }

    /**
     * Answers a ringing phone: touches [call]'s answer point (the Pokégear button), then waits for the call's first
     * message. A touch the game ignored is tried again (3 times), then fails with an explicit error.
     *
     * A step of `advance_dialogue` a game may override: how a call is picked up is a game's own procedure (the
     * Pokégear of HeartGold / SoulSilver; Platinum has no phone calls).
     */
    internal open fun answerCall(context: PlanContext, call: IncomingCall): Step<Screen> {
        val point = call.answer ?: return Step.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "No way to answer ${call.caller}'s call is known"))
        repeat(ANSWER_TRIES) {
            context.scope.touch(point)
            var waited = 0
            while (waited < ANSWER_FRAMES) {
                context.scope.step(2)
                waited += 2
                val screen = context.state().screen
                if (screen is Screen.Dialogue && screen.source == TextSource.PHONE) return Step.Done(screen)
                // The caller hung up before the touch (calls ring about 30 s): nothing to answer.
                if (screen is Screen.Overworld && screen.incomingCall == null && screen.awaiting == Awaiting.INPUT) {
                    return Step.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "${call.caller} hung up: no call to answer"))
                }
                if (screen !is Screen.Overworld && screen !is Screen.Animation && screen !is Screen.Unknown && screen !is Screen.Dialogue) {
                    return Step.Failed(ActionError.UnexpectedScreen("${call.caller}'s call", screen))
                }
            }
        }
        return Step.Failed(ActionError.VerificationFailed("answer ${call.caller}'s call", expected = "the call's first message", actual = context.state().screen.kind, attempts = ANSWER_TRIES))
    }

    /** Selects and confirms an entry of the menu on screen, by its stable id. */
    override fun choose(action: GameAction.Choose, context: PlanContext): ActionOutcome {
        val screen = context.state().screen as? Screen.Selectable
            ?: return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "No menu is open"))
        return context.navigator.choose(Screen.Selectable::class, action.entry) { it.id == action.entry }.then { after ->
            // Battle party grid (switch, replacement after a K.O.): picking a Pokémon only opens SHIFT / SUMMARY...;
            // sending it in is what the choice means, so SHIFT is confirmed too.
            val grid = screen as? Screen.PartyGrid
            val menu = after.screen as? Screen.ContextMenu
            if (grid != null && (grid.purpose == PartyPurpose.BATTLE_SWITCH || grid.purpose == PartyPurpose.BATTLE_REPLACE_FAINTED) &&
                action.entry.startsWith("mon:") && menu?.entries?.any { it.id == SHIFT } == true
            ) {
                context.navigator.choose(Screen.ContextMenu::class, "SHIFT") { it.id == SHIFT }.then { ActionOutcome.Done("sent in (SHIFT)") }
            } else {
                ActionOutcome.Done()
            }
        }
    }

    /**
     * Types on the naming keyboard (nicknames, box names...): erase what is there, type each character (switching
     * keyboard page when the character is on another one), check the typed text read from RAM after every key, then
     * OK. Keys are found by the character they type (`key:<char>`), page buttons by `page:<name>`.
     */
    override fun enterText(action: GameAction.EnterText, context: PlanContext): ActionOutcome {
        val start = context.navigator.settle()
        val keyboard = start.screen as? Screen.Keyboard
            ?: return ActionOutcome.Failed(ActionError.UnexpectedScreen("the naming keyboard", start.screen))
        if (action.text.length > keyboard.maxLength) {
            return ActionOutcome.Failed(ActionError.InvalidParameter("text", action.text, listOf("at most ${keyboard.maxLength} characters")))
        }
        val erased = erase(context)
        if (erased is Step.Failed) return ActionOutcome.Failed(erased.error)
        for ((i, char) in action.text.withIndex()) {
            val typed = type(context, char, expected = action.text.take(i + 1))
            if (typed is Step.Failed) return ActionOutcome.Failed(typed.error)
        }
        return context.navigator.choose(Screen.Keyboard::class, "OK") { it.id == "option:ok" }.then { ActionOutcome.Done("typed ${action.text}") }
    }

    /** Presses B (delete) until the typed text is empty, checking that each press removed one character. */
    private fun erase(context: PlanContext): Step<GameState> {
        repeat(MAX_KEYS) {
            val state = context.navigator.settle()
            val keyboard = state.screen as? Screen.Keyboard ?: return Step.Failed(ActionError.UnexpectedScreen("the naming keyboard", state.screen))
            if (keyboard.buffer.isEmpty()) return Step.Done(state)
            context.scope.tap(Button.B)
            context.navigator.awaitChange(keyboard, maxFrames = KEY_FRAMES)
        }
        return Step.Failed(ActionError.Timeout("couldn't erase the current text"))
    }

    /** Types [char], switching page first when needed, and checks the text is then [expected]. */
    private fun type(context: PlanContext, char: Char, expected: String): Step<GameState> {
        val key = "key:$char"
        repeat(PAGES) {
            val state = context.navigator.settle()
            val keyboard = state.screen as? Screen.Keyboard ?: return Step.Failed(ActionError.UnexpectedScreen("the naming keyboard", state.screen))
            if (keyboard.entries.any { it.id == key && it.selectable }) {
                return context.navigator.choose(Screen.Keyboard::class, "'$char'") { it.id == key }.andThen {
                    val after = context.navigator.settle().screen as? Screen.Keyboard
                    if (after?.buffer == expected) Step.Done(context.state())
                    else Step.Failed(ActionError.VerificationFailed("typing '$char'", expected, after?.buffer ?: "no keyboard", 0))
                }
            }
            // Not on this page: go to the next one (upper → lower → others).
            val next = PAGE_ORDER[(PAGE_ORDER.indexOf(keyboard.page) + 1) % PAGE_ORDER.size]
            val switched = context.navigator.choose(Screen.Keyboard::class, "page $next") { it.id == "page:$next" }
            if (switched is Step.Failed) return switched
            context.navigator.advanceUntil(PAGE_WAITS) { (it.screen as? Screen.Keyboard)?.page == next }
        }
        return Step.Failed(ActionError.NotOnScreen("'$char'", "the keyboard", PAGE_ORDER))
    }

    private companion object {
        const val REACTION_FRAMES = 300

        /** A press usually shows its effect within a few frames; opening an app takes up to a second. */
        const val PRESS_CHANGE_FRAMES = 60
        const val MAX_WAIT_FRAMES = 1800

        const val DRAG_HOLD_FRAMES = 4
        const val DRAG_RELEASE_FRAMES = 4
        const val DRAG_REACTION_FRAMES = 120

        const val ANSWER_TRIES = 3

        /** Opening the Pokégear and dialing take about two seconds. */
        const val ANSWER_FRAMES = 240

        const val SHIFT = "option:shift"

        /** About 30 s of a scene per `advance_dialogue` (the MCP repeats answers sent later than 50 s). */
        const val MAX_ADVANCE_FRAMES = 1800

        val PAGE_ORDER = listOf("upper", "lower", "others")
        const val PAGES = 3
        const val MAX_KEYS = 16
        const val KEY_FRAMES = 30
        const val PAGE_WAITS = 20
    }
}
