package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.runtime.ActionScope
import dev.kotlinds.pokemonclient.state.GameState

/**
 * What a recipe works with: the [scope] it presses in, the [game] it plays, the [navigator] reading that game, and the
 * [settings] of the application.
 *
 * Built by the library only (the constructor is `internal`: [ActionRegistry.execute] makes one per action, [with] a
 * variant of it), always with the navigator of its own [game] (`Navigator(scope, game)`, never given apart): a game,
 * written in this library or in its own project, only receives a context in its overrides, never makes one. So a
 * context's [state] is always decoded by its [game], and the recipes it runs are always that game's ([recipes]).
 */
class PlanContext private constructor(
    val scope: ActionScope,
    val game: PokemonGame,
    /** Reads [game] through [scope] ([Navigator.state]); one per context and its [with] variants (its warp watch). */
    val navigator: Navigator,
    /** What the application allows the recipes to do by themselves ([ActionSettings]). */
    val settings: ActionSettings,
) {
    /** A context playing [game] through [scope], read by a new navigator of that same game. */
    internal constructor(scope: ActionScope, game: PokemonGame, settings: ActionSettings = ActionSettings()) :
        this(scope, game, Navigator(scope, game), settings)

    /**
     * The recipes of [game] ([PokemonGame.recipes]): the only recipes this context runs. The entries of the recipes
     * ([RecipeBase.perform], [RecipeBase.closeToOverworld], [RecipeBase.activateKeyItem]: the registry, the walking
     * engine) run them through here, and the availability of every action is read on them; never given apart from
     * the game, so no context can play another game's recipes. Inside the chain of recipes, a recipe calls the others
     * on itself (virtual calls: itself is this object, the only way in).
     */
    val recipes: Recipes get() = game.recipes

    /** The current state (decoded from this frame). */
    fun state(): GameState = navigator.state()

    /**
     * The same context with other [settings] (a step run with some freedom taken away, e.g. no puzzle solving): same
     * game, same navigator (the warps it noted and the watchers it tells are the action's).
     */
    fun with(settings: ActionSettings): PlanContext = PlanContext(scope, game, navigator, settings)
}

/** The result of an action: done (with an optional detail), or a typed error; plus the state after it. */
sealed interface ActionOutcome {
    /**
     * Done, with an optional [detail]. [stopsChain]: the step was carried out but what it led to makes the steps
     * after it pointless (a `run` that couldn't escape: the battle goes on, while the next steps were meant for after
     * it); a chain stops there with this reason ([ChainRunner]).
     */
    data class Done(val detail: String? = null, val stopsChain: ChainStop? = null) : ActionOutcome
    data class Failed(val error: ActionError) : ActionOutcome
}

/**
 * Ends a chain of steps in an action's outcome: [then] runs with the value of a [Step.Done]; a [Step.Failed] becomes
 * [ActionOutcome.Failed] with its error ([then] never runs). How a recipe (a game's own included) turns its steps into
 * what the action answers: `openParty(context).then { ActionOutcome.Done() }`.
 */
inline fun <T> Step<T>.then(then: (T) -> ActionOutcome): ActionOutcome = when (this) {
    is Step.Done -> then(value)
    is Step.Failed -> ActionOutcome.Failed(error)
}
