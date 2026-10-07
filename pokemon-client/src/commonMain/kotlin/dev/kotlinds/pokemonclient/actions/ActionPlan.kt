package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.runtime.ActionScope
import dev.kotlinds.pokemonclient.state.GameState

/**
 * The recipe of one action: how it is carried out in terms of screens ("open the bag, select the pocket, select
 * the item, USE..."), written once for every game. It never presses blindly: every choice goes through the
 * [Navigator], which reads the cursor, moves one tap at a time and confirms only on the target.
 *
 * A game whose screens really differ provides its own recipe for that action ([PokemonGame]'s overrides).
 */
fun interface ActionPlan<A : GameAction> {
    fun run(action: A, context: PlanContext): ActionOutcome
}

/** What a recipe works with. */
class PlanContext(
    val scope: ActionScope,
    val game: PokemonGame,
    val navigator: Navigator = Navigator(scope, game),
    /** What the application allows the recipes to do by themselves ([ActionSettings]). */
    val settings: ActionSettings = ActionSettings(),
    /**
     * The actions of [game] (the registry running this recipe, [ActionRegistry.execute]): what [run] plays when a
     * recipe carries out another action as one of its steps. By default the registry of [game] ([ActionRegistry.of]).
     */
    val registry: ActionRegistry = ActionRegistry.of(game),
) {
    /** The current state (decoded from this frame). */
    fun state(): GameState = navigator.state()

    /**
     * Carries out [action] as a step of the current recipe (talking to the nurse for `heal`, to the clerk for `buy`,
     * typing a nickname for `throw_ball`...): with the recipe [registry] plays for its type, so a game's own recipe
     * ([PokemonGame.actionOverrides]) is played there too, never the common one behind its back. No availability
     * check (the calling recipe has checked its own screen): the step's recipe checks its screens like any recipe.
     */
    fun run(action: GameAction): ActionOutcome {
        val recipe = registry.recipeFor(action) ?: return ActionOutcome.Failed(ActionError.Unsupported(action.key))
        return recipe.run(action, this)
    }

    /** The same context with other [settings] (a step run with some freedom taken away, e.g. no puzzle solving). */
    fun with(settings: ActionSettings): PlanContext = PlanContext(scope, game, navigator, settings, registry)
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

/** Turns a navigation [Step] failure into an outcome, or runs [then] with its value. */
internal inline fun <T> Step<T>.then(then: (T) -> ActionOutcome): ActionOutcome = when (this) {
    is Step.Done -> then(value)
    is Step.Failed -> ActionOutcome.Failed(error)
}
