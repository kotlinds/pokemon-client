package dev.kotlinds.pokemonclient.actions

import kotlin.reflect.KClass

/**
 * One game's own recipe for an action type, replacing the common one for that game only
 * ([dev.kotlinds.pokemonclient.PokemonGame.actionOverrides], applied by [ActionRegistry.of]).
 *
 * Only *how* the action is done changes: its spec (name, parameters, availability, description: what agents see) stays
 * the common one, so the contract is the same for every game. A game overrides a recipe when its screens make the
 * common one impossible (a menu laid out differently, a step the other games don't have), never to add behaviour.
 * [type] and [plan] are typed together: a recipe can only replace the action type it is written for.
 */
class RecipeOverride<A : GameAction>(val type: KClass<A>, val plan: ActionPlan<A>) {

    /** [definition] (the common one of [type]) with this recipe in place of its own. */
    internal fun replacing(definition: ActionDefinition<*>): ActionDefinition<A> {
        require(definition.type == type) { "a recipe for ${type.simpleName} can't replace ${definition.type.simpleName}'s" }
        // Same KClass checked above: the spec is the one of A.
        @Suppress("UNCHECKED_CAST")
        return ActionDefinition(type, definition.spec as ActionSpec<A>, plan)
    }

    companion object {
        /** The recipe [plan] for action type [A]. */
        inline fun <reified A : GameAction> of(plan: ActionPlan<A>): RecipeOverride<A> = RecipeOverride(A::class, plan)
    }
}
