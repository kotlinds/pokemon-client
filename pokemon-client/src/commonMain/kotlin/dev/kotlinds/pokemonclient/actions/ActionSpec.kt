package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.GameState
import kotlinx.serialization.json.JsonObject

/**
 * One action type as agents see it, the same for every game: its wire name, description, parameters, how to parse it
 * from JSON and how to enumerate its concrete instances (for models that pick among a list, like Jev). When it can
 * run is not here: it is the game's recipes' method for it ([ActionDefinition.availability]).
 */
interface ActionSpec<A : GameAction> {
    /** Wire name, e.g. `attack` (the JSON `type`). */
    val name: String

    /** What the action does, for the agent. */
    val description: String

    /** Parameters, for the JSON schema. */
    val parameters: List<Parameter>

    /** Modes where the action is offered. */
    val modes: Set<ActionMode>

    /** Parses the JSON parameters (already validated as an object with this `type`). */
    fun parse(json: JsonObject): A

    /** Concrete instances worth offering now (empty when the action takes free parameters only). */
    fun enumerate(state: GameState): List<A> = emptyList()
}

/** Which actions an agent may use. */
enum class ActionMode {
    /** Raw buttons and touches only, like a human holding the console. */
    PURE,

    /** Every action, carried out by the server (navigation, menus, battles...). */
    ASSISTED,
}

/** One parameter of an action. */
data class Parameter(
    val name: String,
    val type: ParameterType,
    val description: String,
    val required: Boolean = true,
    /** Allowed values when the set is fixed (enums). */
    val values: List<String> = emptyList(),
    /**
     * For an [ParameterType.ARRAY] of objects: the keys of each object (`op`, `pokemon`... of a `pc` operation),
     * checked like the action's own parameters (an unknown key is refused, naming the right one) and described in
     * the JSON schema. Empty: the elements aren't objects with known keys (a list of ids).
     */
    val fields: List<Parameter> = emptyList(),
)

/** JSON types of parameters. [ARRAY] is a list of objects (e.g. several item uses). */
enum class ParameterType { STRING, INTEGER, BOOLEAN, ARRAY }

/**
 * Whether an action is usable now: what the game's recipes say (`<action>Availability`), read by the listing and the
 * execution alike ([ActionRegistry]). Four cases, each listed and executed its own way:
 *
 * | Case | Listed | Executed |
 * |---|---|---|
 * | [Available] | in `actions` (unless [Available.listed] is false) | run |
 * | [Unavailable] | in `unavailable`, with the reason | refused with that reason |
 * | [Hidden] | nowhere | refused as [UnavailableReason.WRONG_SCREEN] |
 * | [NotInThisGame] | nowhere | refused as [UnavailableReason.NOT_SUPPORTED_BY_GAME] |
 *
 * None of the refusals presses anything.
 */
sealed interface Availability {
    /**
     * Usable; [choices] lists valid values per parameter when they depend on the situation (moves, Pokémon...).
     * Not [listed]: accepted (typically chained in a sequence) but not worth offering on this screen (e.g.
     * advance_dialogue on a choice, a no-op; keep_battling while the messages before the question still scroll).
     */
    data class Available(val choices: Map<String, List<Choice>> = emptyMap(), val listed: Boolean = true) : Availability

    /**
     * Visible but not usable now, with the typed reason (e.g. Fly when nobody knows it). Also what the game has but
     * the library doesn't support in it yet ([UnavailableReason.NOT_SUPPORTED_BY_GAME]: Platinum's Fly while its party
     * menu isn't decoded): the agent sees that the action exists and why it can't be used.
     */
    data class Unavailable(val reason: UnavailableReason, val detail: String, val hint: String? = null) : Availability

    /**
     * The action exists in this game but is meaningless in this situation (attack outside a battle): not listed;
     * executed, it is refused as [UnavailableReason.WRONG_SCREEN].
     */
    data object Hidden : Availability

    /**
     * The action doesn't exist in this game at all, whatever the situation (`tune_radio` in Platinum, which has no
     * Pokégear): never listed (neither in `actions` nor in `unavailable`: there is nothing to wait for or unlock);
     * executed, it is refused as [UnavailableReason.NOT_SUPPORTED_BY_GAME] with [detail] (what the game lacks), not as
     * a wrong screen, and at once (no wait for the game to settle, no press). Not for what the game has but the library
     * doesn't support yet: that is [Unavailable] with [UnavailableReason.NOT_SUPPORTED_BY_GAME], listed.
     */
    data class NotInThisGame(val detail: String) : Availability
}

/** A valid value of a parameter, with what it is. */
data class Choice(val value: String, val label: String)
