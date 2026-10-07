package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.runtime.ActionInterruptedException
import dev.kotlinds.pokemonclient.runtime.ActionScope
import dev.kotlinds.pokemonclient.runtime.Interruption
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.GameState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.reflect.KClass

/**
 * An action type, its spec (what agents see: name, parameters, description, the same for every game) and its
 * [availability]. How it is done is not here: it is the game's recipe ([dev.kotlinds.pokemonclient.PokemonGame.recipes]).
 *
 * Built only in this module (the common actions, [CommonActions.definitions]); the constructor requires the
 * availability, so no action can exist without one.
 */
class ActionDefinition<A : GameAction> internal constructor(
    val type: KClass<A>,
    /**
     * When the action can run, and with which parameter values: the one method of the game's recipes for this action
     * (`Recipes::<action>Availability`, the common rule unless the game overrides it). The single entry point: the
     * listing ([ActionRegistry.available], [ActionRegistry.unavailable], [ActionRegistry.enumerate]) and the execution
     * ([ActionRegistry.execute]) both read it, on the same game's recipes, so an action is never listed but refused, or
     * accepted but not listed (other than an explicit [Availability.Available.listed] = false).
     */
    internal val availability: (Recipes, GameState) -> Availability,
    val spec: ActionSpec<A>,
)

/** An action usable now, as listed to agents. */
data class AvailableAction(val name: String, val description: String, val choices: Map<String, List<Choice>>)

/** An action listed but not usable now, with why. */
data class UnavailableAction(val name: String, val reason: UnavailableReason, val detail: String, val hint: String?)

/**
 * The single source of truth of the actions: every consumer (the MCP server, our LLM loop, Jev, the pure-buttons
 * mode) gets its JSON schema, the list of what is possible now, and executes through here. Execution always
 * checks availability first (the same method of the game's recipes the listing reads, [ActionDefinition.availability]),
 * then runs the game's recipe ([dev.kotlinds.pokemonclient.PokemonGame.recipes]), and turns interruptions into typed
 * errors.
 *
 * It holds the contract only (the specs), the same for every game; built only by [ActionRegistry.of] (the
 * constructor is private), never from a hand-made list.
 *
 * @param definitions the common actions ([CommonActions.definitions]).
 */
class ActionRegistry private constructor(private val definitions: List<ActionDefinition<*>>) {

    private val byName = definitions.associateBy { it.spec.name }

    /**
     * Actions of [mode], usable now in [game] (its recipes' availability, the same [execute] checks), with their valid
     * parameter values.
     */
    fun available(state: GameState, mode: ActionMode, game: PokemonGame): List<AvailableAction> = definitions
        .filter { mode in it.spec.modes }
        .mapNotNull { def ->
            (listedAvailability(def, state, game.recipes) as? Availability.Available)?.takeIf { it.listed }?.let { AvailableAction(def.spec.name, def.spec.description, it.choices) }
        }

    /** Actions of [mode] shown but not usable now in [game], with the reason (e.g. "nobody knows Fly"). */
    fun unavailable(state: GameState, mode: ActionMode, game: PokemonGame): List<UnavailableAction> = definitions
        .filter { mode in it.spec.modes }
        .mapNotNull { def ->
            (listedAvailability(def, state, game.recipes) as? Availability.Unavailable)?.let { UnavailableAction(def.spec.name, it.reason, it.detail, it.hint) }
        }

    /** Every concrete action worth offering now in [game], by canonical key (for models that pick from a list). */
    fun enumerate(state: GameState, mode: ActionMode, game: PokemonGame): Map<String, GameAction> = definitions
        .filter { mode in it.spec.modes && (listedAvailability(it, state, game.recipes) as? Availability.Available)?.listed == true }
        .flatMap { def -> def.spec.enumerate(state).ifEmpty { fieldReady(state)?.let { def.spec.enumerate(it) }.orEmpty() } }
        .associateBy { it.key }

    /** Parses a wire action `{"type": "...", ...}` into a typed action, or a typed error. */
    fun parse(json: JsonObject, mode: ActionMode): Result<GameAction> {
        val type = json["type"]?.jsonPrimitive?.contentOrNull
            ?: return Result.failure(ActionException(ActionError.InvalidParameter("type", "missing", byName.keys.toList())))
        val def = byName[type]?.takeIf { mode in it.spec.modes }
            ?: return Result.failure(ActionException(ActionError.InvalidParameter("type", type, definitions.filter { mode in it.spec.modes }.map { it.spec.name })))
        // A misspelled parameter would otherwise be ignored silently (e.g. `count` for `tiles`): refuse it, saying
        // which parameter to use (NOTES: `option` given twice to open_menu / choose, which take `entry`).
        json.keys.firstOrNull { it != "type" && def.spec.parameters.none { p -> p.name == it } }?.let { unknown ->
            return Result.failure(ActionException(unknownParameter(def.spec.name, def.spec.parameters, unknown, json.keys)))
        }
        // The same check inside the objects of an array parameter (`pc` operations, `use_item` / `buy` items): NOTES
        // "Invalid op `missing`" never said the key is `op`.
        for (p in def.spec.parameters.filter { it.fields.isNotEmpty() }) {
            val elements = json[p.name] as? kotlinx.serialization.json.JsonArray ?: continue
            elements.forEachIndexed { index, element ->
                val obj = element as? JsonObject ?: return@forEachIndexed
                obj.keys.firstOrNull { key -> p.fields.none { it.name == key } }?.let { unknown ->
                    return Result.failure(ActionException(unknownParameter(def.spec.name, p.fields, unknown, obj.keys, within = "${p.name}[$index]")))
                }
            }
        }
        return runCatching { def.spec.parse(json) }.recoverCatching { error ->
            throw (error as? ActionException) ?: ActionException(ActionError.InvalidParameter(type, json.toString()))
        }
    }

    /**
     * The error for parameter [unknown] of [spec]: the parameters not [given] are suggested, the required ones alone
     * when some are missing (`option` for open_menu → `entry`), else every optional one left.
     */
    private fun unknownParameter(action: String, parameters: List<Parameter>, unknown: String, given: Set<String>, within: String? = null): ActionError.UnknownParameter {
        val left = parameters.filter { it.name !in given }
        val suggested = left.filter { it.required }.ifEmpty { left }
        return ActionError.UnknownParameter(action, unknown, suggested.map { it.name to it.description }, parameters.map { it.name }, within)
    }

    /**
     * Executes [action] with the console leased to [scope]: checks it is available now, runs [game]'s recipe for it
     * ([dev.kotlinds.pokemonclient.PokemonGame.recipes], [RecipeBase.perform]), and reports interruptions (a battle
     * starting, the human taking over...) as typed errors. [settings]: what the application lets the recipes do by
     * themselves (solve movement puzzles, use hidden knowledge).
     */
    fun execute(action: GameAction, scope: ActionScope, game: PokemonGame, settings: ActionSettings = ActionSettings()): ActionOutcome {
        val def = definitions.firstOrNull { it.type.isInstance(action) }
            ?: return ActionOutcome.Failed(ActionError.Unsupported(action.key))
        val context = PlanContext(scope, game, settings = settings)
        when (val availability = availabilityToRun(def, context)) {
            is Availability.Unavailable -> {
                // Fly refused here: name the nearest place where it works (computed only when asked, it routes).
                val hint = if (availability.reason == UnavailableReason.NOT_FLYABLE_HERE) FlyHints.nearestFlyable(context) ?: availability.hint else availability.hint
                return ActionOutcome.Failed(ActionError.Unavailable(availability.reason, availability.detail, hint))
            }
            Availability.Hidden -> return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.WRONG_SCREEN, "${def.spec.name} isn't possible on this screen"))
            is Availability.Available -> Unit
        }
        return try {
            RecipeBase.perform(action, context)
        } catch (interrupted: ActionInterruptedException) {
            val cause = if (interrupted.reason == Interruption.HUMAN) InterruptionCause.HUMAN else InterruptionCause.SCRIPT
            ActionOutcome.Failed(ActionError.Interrupted(cause, action.key))
        } catch (error: ActionException) {
            ActionOutcome.Failed(error.error)
        }
    }

    /**
     * [execute], then lets the game settle (frames only, never a button: [Navigator.settle]) until it expects input:
     * how every host runs an agent's step (the app's sessions, the bench), so what the agent reads next is the
     * screen the action led to. The step never runs much past [STEP_FRAMES] in all (an agent's call must answer
     * before its client gives up): a long action leaves less time to settle, but always at least [MIN_SETTLE_FRAMES]
     * (a menu closing, the next screen fading in).
     */
    fun executeAndSettle(action: GameAction, scope: ActionScope, game: PokemonGame, settings: ActionSettings = ActionSettings()): ActionOutcome {
        val start = scope.framesUsed
        return execute(action, scope, game, settings).also {
            val used = scope.framesUsed - start
            Navigator(scope, game).settle(maxFrames = (STEP_FRAMES - used).coerceIn(MIN_SETTLE_FRAMES.toLong(), STEP_FRAMES.toLong()).toInt())
        }
    }

    /**
     * Whether [def] is listed as usable in [state] by [recipes] (its [ActionDefinition.availability]): usable now, or
     * usable once the field is ready ([fieldReady]). The listing matches what [execute] accepts, since [execute] reads
     * the same method and waits for the game to settle before refusing ([availabilityToRun]): right after a battle the
     * field actions are listed during the fade back already (NOTES: `reorder_party` missing from the actions, then
     * accepted a second later).
     */
    private fun listedAvailability(def: ActionDefinition<*>, state: GameState, recipes: Recipes): Availability {
        val now = def.availability(recipes, state)
        if (now is Availability.Available) return now
        val ready = fieldReady(state) ?: return now
        return def.availability(recipes, ready).takeIf { it is Availability.Available } ?: now
    }

    /**
     * [state] as it will be once the game waits for input again, when that is known without emulating: the overworld
     * still busy by itself (the fade back after a battle, a field animation), out of battle, is the same overworld
     * waiting for input a few frames later. Null for any other screen: what comes next isn't known (a message may
     * open, a menu may change), so only the current state counts there.
     */
    private fun fieldReady(state: GameState): GameState? {
        val screen = state.screen as? Screen.Overworld ?: return null
        if (screen.awaiting == Awaiting.INPUT || state.battle != null) return null
        return state.copy(screen = screen.copy(awaiting = Awaiting.INPUT))
    }

    /**
     * Whether [def] can run now in [context]'s game (its recipes' [ActionDefinition.availability], the method the
     * listing reads): what [execute] checks before running the recipe. Refused on a screen where the game is still
     * busy by itself ([Awaiting] other than INPUT: the fade back to the field after a battle, a script finishing, text
     * printing), it is checked again once the game waits for input ([Navigator.settle], which only lets frames run and
     * never presses a button): the screen the action needs is often only a few frames away (NOTES: `reorder_party`
     * refused on "overworld, awaiting animation" right after BATTLE_WON, accepted when retried a second later). An
     * action available at once runs at once (advance_dialogue while text prints...); one still refused after settling
     * is refused for real.
     */
    internal fun availabilityToRun(def: ActionDefinition<*>, context: PlanContext): Availability {
        val now = context.state()
        val availability = def.availability(context.recipes, now)
        if (availability is Availability.Available || now.screen.awaiting == Awaiting.INPUT) return availability
        return def.availability(context.recipes, context.navigator.settle())
    }

    /** JSON Schema of the wire actions of [mode] (one object per type, discriminated by `type`). */
    fun jsonSchema(mode: ActionMode): JsonObject = buildJsonObject {
        putJsonArray("oneOf") {
            definitions.filter { mode in it.spec.modes }.forEach { def ->
                add(buildJsonObject {
                    put("type", "object")
                    put("description", def.spec.description)
                    putJsonObject("properties") {
                        putJsonObject("type") { put("const", def.spec.name) }
                        def.spec.parameters.forEach { p -> putJsonObject(p.name) { parameterSchema(p) } }
                    }
                    put("required", JsonArray((listOf("type") + def.spec.parameters.filter { it.required }.map { it.name }).map(::JsonPrimitive)))
                })
            }
        }
    }

    /** The JSON schema of parameter [p]; an array of objects with [Parameter.fields] describes each object's keys. */
    private fun kotlinx.serialization.json.JsonObjectBuilder.parameterSchema(p: Parameter) {
        put("type", p.type.name.lowercase())
        if (p.type == ParameterType.ARRAY) putJsonObject("items") {
            put("type", "object")
            if (p.fields.isNotEmpty()) {
                putJsonObject("properties") { p.fields.forEach { f -> putJsonObject(f.name) { parameterSchema(f) } } }
                put("required", JsonArray(p.fields.filter { it.required }.map { JsonPrimitive(it.name) }))
            }
        }
        put("description", p.description)
        if (p.values.isNotEmpty()) put("enum", JsonArray(p.values.map(::JsonPrimitive)))
    }

    companion object {
        /** About 30 s of game (real time): what one step of an agent may take, action and settling together. */
        const val STEP_FRAMES = 1800

        /** Always let the game settle a little after an action (a menu closing, the next screen fading in). */
        const val MIN_SETTLE_FRAMES = 120

        /**
         * Lets a battle still playing out between two steps of a chain settle (frames only, never a button) and reads
         * the state then, at most [STEP_FRAMES]: the `settle` of a [ChainRunner], the same for every host.
         */
        fun settleBetweenSteps(scope: ActionScope, game: PokemonGame): GameState = Navigator(scope, game).settle(maxFrames = STEP_FRAMES)

        /**
         * The registry: the common actions ([CommonActions.definitions]), the same for every game. The one factory of
         * every host (the app's sessions and MCP server, the bench, [dev.kotlinds.pokemonclient.view.AgentView]);
         * which game's recipes decide availability and run is the game given to [available], [unavailable],
         * [enumerate] and [execute].
         */
        fun of(): ActionRegistry = ActionRegistry(CommonActions.definitions)
    }
}

/** Carries a typed [ActionError] out of parsing or a recipe. */
class ActionException(val error: ActionError) : RuntimeException(error.message)
