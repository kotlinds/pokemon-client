package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.runtime.ActionScope
import dev.kotlinds.pokemonclient.state.GameState

/** What a recipe works with. */
class PlanContext(
    val scope: ActionScope,
    val game: PokemonGame,
    val navigator: Navigator = Navigator(scope, game),
    /** What the application allows the recipes to do by themselves ([ActionSettings]). */
    val settings: ActionSettings = ActionSettings(),
) {
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

    /** The same context with other [settings] (a step run with some freedom taken away, e.g. no puzzle solving). */
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

/** Turns a navigation [Step] failure into an outcome, or runs [then] with its value. */
internal inline fun <T> Step<T>.then(then: (T) -> ActionOutcome): ActionOutcome = when (this) {
    is Step.Done -> then(value)
    is Step.Failed -> ActionOutcome.Failed(error)
}
