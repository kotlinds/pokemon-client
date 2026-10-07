package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.kind

/**
 * Why an action didn't happen or stopped: always explicit, never a silent no-op. Agents get the [code] and the
 * details, so they can decide what to do next.
 */
sealed interface ActionError {
    /** Machine-readable error code (stable, used in agent APIs). */
    val code: String

    /** One human-readable sentence. */
    val message: String

    /** The action can't be done in the current situation ([reason]), with a [hint] on how to get there. */
    data class Unavailable(val reason: UnavailableReason, val detail: String, val hint: String? = null) : ActionError {
        override val code = "NOT_AVAILABLE"
        override val message get() = detail + (hint?.let { ". $it" } ?: "")
    }

    /** A parameter is invalid; [allowed] lists valid values when there are few. */
    data class InvalidParameter(val parameter: String, val value: String, val allowed: List<String> = emptyList()) : ActionError {
        override val code = "INVALID_PARAM"
        override val message get() = "Invalid $parameter `$value`" + if (allowed.isEmpty()) "" else ". Allowed: ${allowed.joinToString()}"
    }

    /**
     * The action was given a parameter it doesn't have ([name]): refused rather than ignored (e.g. `count` for
     * `tiles`), naming the parameter to use instead. [suggested]: the action's parameters the call didn't give, the
     * required ones first (one of them is almost always what was meant), each with what it is; [valid]: every
     * parameter of the action. One name per parameter: no alias is accepted, the message says the right one.
     */
    data class UnknownParameter(
        val action: String,
        val name: String,
        val suggested: List<Pair<String, String>>,
        val valid: List<String>,
        /** Where the key was, when not at the action's top level: an element of an array parameter (`operations[0]`). */
        val within: String? = null,
    ) : ActionError {
        override val code = "INVALID_PARAM"
        override val message get() = (if (within == null) "Unknown parameter `$name` for $action: " else "Unknown key `$name` in $within of $action: ") + when {
            suggested.size == 1 -> suggested.single().let { (n, d) -> "use `$n` (${d.trimEnd('.')})" }
            suggested.isNotEmpty() -> "use one of " + suggested.joinToString { (n, d) -> "`$n` (${d.trimEnd('.')})" }
            valid.isEmpty() -> "$action takes no parameter"
            else -> "its parameters are ${valid.joinToString { "`$it`" }}"
        }
    }

    /**
     * The game shows something else ([actual]) than the screen the action works on ([expected]). The message names the
     * screen by its [kind] ("battle_command", "move_select:battle"), never by the decoded object (whose text holds
     * Kotlin internals: lambdas of its topology...).
     */
    data class UnexpectedScreen(val expected: String, val actual: Screen) : ActionError {
        override val code = "UNEXPECTED_SCREEN"
        override val message get() = "Expected $expected, but the screen is ${actual.kind}"
    }

    /** The entry to choose isn't on the screen. */
    data class NotOnScreen(val target: String, val screen: String, val entries: List<String>) : ActionError {
        override val code = "NOT_ON_SCREEN"
        override val message get() = "No $target on $screen (entries: ${entries.joinToString()})"
    }

    /** The entry is shown but the game refuses it (fainted Pokémon, no PP...). */
    data class NotSelectable(val target: String, val label: String) : ActionError {
        override val code = "NOT_SELECTABLE"
        override val message get() = if (target == label) "$label can't be chosen now" else "$label can't be chosen for $target"
    }

    /** The move to forget is an HM: the game never lets an HM be forgotten (only a Move Deleter can). */
    data class HmCannotForget(val move: String) : ActionError {
        override val code = "HM_CANNOT_FORGET"
        override val message get() = "$move is an HM move: it can't be forgotten. Choose another move to forget, or keep the old moves (no `forget`)"
    }

    /**
     * [pokemon] already knows four moves and the action didn't say which one to forget: refused before any menu opens
     * (NOTES: `teach` looped on "Should a move be deleted?" then left the yes/no on screen). [forgettable]: the moves it
     * can forget (`move:<id> <name>`; HM moves can't be).
     */
    data class ForgetNeeded(val pokemon: String, val forgettable: List<String>) : ActionError {
        override val code = "INVALID_PARAM"
        override val message get() = "$pokemon already knows four moves: give `forget`, the move to forget" +
            if (forgettable.isEmpty()) " (it only knows HM moves, which can't be forgotten)" else ": ${forgettable.joinToString()}"
    }

    /**
     * One step of a batch (several item uses in one action) failed: the steps before it are done ([done]), the ones
     * after it were not tried. [code] is the failed step's own code.
     */
    data class BatchStepFailed(val step: Int, val stepKey: String, val done: List<String>, val cause: ActionError) : ActionError {
        override val code get() = cause.code
        override val message get() = "Step ${step + 1} ($stepKey) failed: ${cause.message}" +
            if (done.isEmpty()) "" else ". Done before it: ${done.joinToString("; ")}"
    }

    /** The cursor could not be brought onto the target (the verification rule refused to confirm). */
    data class VerificationFailed(val step: String, val expected: String, val actual: String, val attempts: Int) : ActionError {
        override val code = "VERIFICATION_FAILED"
        override val message get() = "Couldn't select $step: expected $expected, the cursor is on $actual (after $attempts correction(s))"
    }

    /** No sequence of D-pad presses reaches the target from where the cursor is. */
    data class Unreachable(val step: String, val target: String) : ActionError {
        override val code = "UNREACHABLE"
        override val message get() = "$target can't be reached with the D-pad for $step"
    }

    /** Something happened before the action could finish (a battle started, the human took over...). */
    data class Interrupted(val by: InterruptionCause, val performed: String) : ActionError {
        override val code = "INTERRUPTED"
        override val message get() = "Interrupted by ${by.name.lowercase().replace('_', ' ')} after: $performed"
    }

    /**
     * The game ignored the inputs meant to pass [screen] ([tried], in order: nothing changed after any of them),
     * [attempts] times in a row.
     */
    data class InputIgnored(val screen: String, val tried: List<String>, val attempts: Int) : ActionError {
        override val code = "INPUT_IGNORED"
        override val message get() = "The game ignored ${tried.joinToString(", then ")} on $screen ($attempts attempt(s)): nothing changed"
    }

    /**
     * The game stopped on a wireless communication error before the saved game ([ContinueReason.COMMUNICATION_ERROR]
     * on the main menu): A only restarts it at the title screen, where the same error comes back.
     */
    data object CommunicationError : ActionError {
        override val code = "COMMUNICATION_ERROR"
        override val message = "The game stopped on a wireless communication error on the main menu (an emulator core without " +
            "wireless, like melonDS, does this with a save): A restarts it at the title screen, the saved game can't be continued on this core"
    }

    /** There is no saved game to continue: the game went straight to a new game's intro (no main menu, no CONTINUE). */
    data object NoSavedGame : ActionError {
        override val code = "NO_SAVED_GAME"
        override val message = "There is no saved game: the game started a new game's intro instead of showing CONTINUE"
    }

    /** The game didn't reach the expected point in time. */
    data class Timeout(val detail: String) : ActionError {
        override val code = "TIMEOUT"
        override val message get() = detail
    }

    /** The state changed since the agent last looked: the action was refused, see the new state. */
    data class StaleState(val expectedVersion: Long, val actualVersion: Long) : ActionError {
        override val code = "STALE_STATE"
        override val message get() = "Something happened since your last look (state $expectedVersion → $actualVersion): read the new state first"
    }

    /** A human is playing right now. */
    data object HumanDriving : ActionError {
        override val code = "HUMAN_DRIVING"
        override val message = "A human is playing right now: wait until they stop"
    }

    /** The person watching paused the game: nothing can be done until they resume it (the state can still be read). */
    data object PausedByHuman : ActionError {
        override val code = "PAUSED"
        override val message = "The game is paused by the person watching: wait until they resume it"
    }

    /** This game doesn't support the action yet. */
    data class Unsupported(val action: String) : ActionError {
        override val code = "UNSUPPORTED"
        override val message get() = "$action isn't supported for this game yet"
    }
}

/** Why an action is unavailable (typed, so agents can react without parsing text). */
enum class UnavailableReason {
    WRONG_SCREEN,

    /** The story hasn't unlocked it yet (the start menu at the very start of a new game). */
    NOT_UNLOCKED_YET,
    NOT_IN_BATTLE,
    IN_BATTLE,
    TRAINER_BATTLE,
    TRAPPED,
    TARGET_FAINTED,
    TARGET_IS_EGG,
    ALREADY_ACTIVE,
    NO_PP,

    /** Disable holds this move: it can't be chosen for a few turns. */
    DISABLED,

    /** Taunt: status moves can't be chosen for a few turns. */
    TAUNTED,

    /** The Pokémon can't learn this TM / HM (UNABLE on the game's party screen). */
    CANNOT_LEARN,

    /** The Pokémon already knows the move (LEARNED on the game's party screen). */
    ALREADY_KNOWN,

    /** Encore: only the encored move can be used for a few turns (FIGHT plays it). */
    ENCORED,

    /** The battle's move list refuses the move this turn (Torment, Imprison, a Choice item...: not selectable). */
    MOVE_REFUSED,
    NO_STOCK,
    UNKNOWN_MOVE,
    UNKNOWN_ITEM,
    UNKNOWN_POKEMON,
    PARTY_FULL,
    HM_CANNOT_FORGET,
    MAIL_BLOCKS_DEPOSIT,
    NEEDS_BADGE,
    NO_POKEMON_KNOWS_MOVE,
    NOT_FLYABLE_HERE,
    NOT_VISITED,
    OTHER_REGION,
    NOT_FACING_WATER,
    NO_PATH,

    /**
     * The tile asked for (`go_to` x / y) can't be stood on: a wall, a counter, a ledge, or someone / something stands
     * there. The error names what is on it or next to it and the target id that walks next to it (`go_to person:0`).
     */
    TARGET_IS_OBSTACLE,
    NOT_ENOUGH_MONEY,

    /** The game refused it: "It won't have any effect". */
    NO_EFFECT,

    /** It would leave the party without a Pokémon able to battle. */
    LAST_POKEMON,

    /** Giving Mail opens the mail editor, which wants a written message (not supported). */
    MAIL_NEEDS_WRITING,

    /** The game refuses to use it where the player stands (the Bicycle indoors or while surfing...). */
    CANNOT_USE_HERE,

    /**
     * The item has no USE in the bag ([dev.kotlinds.pokemonclient.data.ItemInfo.usableFromBag]): a key that works by
     * itself when the player interacts with what it opens (the Basement Key's door): interact with it instead.
     */
    NOT_USABLE_FROM_BAG,
    /**
     * The way needs a movement-puzzle mechanism operated (a boulder or ice block pushed, a platform or lift ridden)
     * and the application left movement puzzles to the agent ([ActionSettings.solvePuzzles] off): do it yourself.
     */
    PUZZLE_LEFT_TO_AGENT,

    /**
     * The game has it, but the library doesn't support it in this game yet (a screen it goes through isn't decoded):
     * the same action as in every game, said unavailable rather than tried blindly.
     */
    NOT_SUPPORTED_BY_GAME,

    /**
     * The target is on another map, while the application hides where the ways out lead
     * ([ActionSettings.hideDestinations]): `go_to` only reaches places of the current map (its warps and exits among
     * them). Walk to an exit, take it and see where it leads.
     */
    DESTINATIONS_HIDDEN,
}

/** What interrupted an action. */
enum class InterruptionCause {
    WILD_BATTLE, TRAINER_SIGHT, PHONE_CALL, SCRIPT, UNKNOWN_SCREEN, HUMAN,

    /** The Repel wore off on the way and the walk stopped there ([RepelEnd.STOP], the default): the agent decides. */
    REPEL_ENDED,
}

/**
 * Why a walk can't be planned on [field]'s map: the game gives no map data for it (no ROM loaded, a zone the world
 * decoder doesn't know). The one wording of every walk, `go_to` and puzzle action.
 */
internal fun noMapDetail(field: dev.kotlinds.pokemonclient.state.FieldState): String = "no map data for ${field.mapName}"

/** The refusal of an action that needs [field]'s map when there is none ([noMapDetail]). */
internal fun noMap(field: dev.kotlinds.pokemonclient.state.FieldState): ActionOutcome.Failed =
    ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, noMapDetail(field)))
