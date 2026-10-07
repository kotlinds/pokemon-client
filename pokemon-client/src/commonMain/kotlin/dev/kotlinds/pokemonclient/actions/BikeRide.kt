package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Edge
import dev.kotlinds.pokemonclient.world.Route
import dev.kotlinds.pokemonclient.world.TileKind

/**
 * The `bike` option of `go_to` / `step`: gets on the bicycle before walking, with the registered button when it's
 * registered (Y / its touch button), else bag → the Bicycle → USE ([PartyBagPlans.activateKeyItem]). Called again
 * after each warp (entering a building gets the player off; leaving it doesn't put them back on).
 *
 * Never an error: where cycling isn't possible (indoors, no bicycle, the game says no) the walk goes on on foot and
 * the note says why.
 */
internal object BikeRide {

    /**
     * Gets on the bicycle when [options] ask for it and the player walks; a note for the agent, or null. Not when
     * [crossesIce] says the way goes over ice (asked only when it would get on): the bicycle doesn't steer there
     * ([getOff]).
     */
    fun mount(context: PlanContext, options: MoveOptions, crossesIce: () -> Boolean = { false }): String? {
        if (!options.bike) return null
        val state = context.navigator.settle()
        val field = state.field ?: return null
        if (field.movement == MovementMode.BIKE || field.movement == MovementMode.SURF) return null
        if (field.bikeAllowed == false) return "$NO_CYCLING ${field.mapName}: walked"
        val item = context.game.bicycleItem ?: return "this game has no bicycle: walked"
        val owned = state.bag.orEmpty().any { pocket -> pocket.items.any { it.item.id.value == item } }
        if (!owned) return "no Bicycle in the bag: walked"
        if (crossesIce()) return ICE_WALKED
        return toggle(context, item, MovementMode.BIKE)
    }

    /**
     * Gets off the bicycle (it's used again: Y when registered, else from the bag), checked on the movement read
     * after: the way crosses ice, where the bicycle doesn't steer (NOTES race, Codex in the Ice Path: on the bicycle
     * go_to ended "the game refused 0 steps", on foot it went). A note for the agent.
     */
    fun getOff(context: PlanContext): String {
        val item = context.game.bicycleItem ?: return "couldn't get off the Bicycle on the ice (this game has no bicycle)"
        return when (val off = toggle(context, item, MovementMode.WALK)) {
            RODE -> GOT_OFF_ICE
            else -> off
        }
    }

    /** Uses the bicycle [item] and tells whether the movement became [wanted] ([RODE]) or why not. */
    private fun toggle(context: PlanContext, item: Int, wanted: MovementMode): String {
        val used = PartyBagPlans.activateKeyItem(context, ItemRef("item:$item"))
        // A refusal is a message ("no cycling here"): read it, back to the field.
        PartyBagPlans.closeToOverworld(context)
        val now = context.navigator.settle().field
        val on = wanted == MovementMode.BIKE
        return when {
            used is Step.Failed -> if (on) "couldn't get on the Bicycle (${used.error.message}): walked" else "couldn't get off the Bicycle (${used.error.message})"
            now?.movement == wanted -> RODE
            on -> "the game didn't let the player ride here: walked"
            else -> "the game didn't let the player get off the Bicycle here"
        }
    }

    /** True when [route] slides on ice (a [Edge.Slide] over an ice tile of [area]). */
    fun crossesIce(area: Area, route: Route): Boolean =
        route.edges.any { edge -> edge is Edge.Slide && edge.tiles.any { area.tile(it.x, it.y)?.kind == TileKind.Ice } }

    /** The note of a trip that stayed on foot because its way crosses ice. */
    const val ICE_WALKED = "walked: the way crosses ice, where the Bicycle doesn't steer"

    /** The note of a walk that got off the bicycle before the ice. */
    const val GOT_OFF_ICE = "got off the Bicycle: the way crosses ice, where it doesn't steer"

    /** The note of a ride. */
    const val RODE = "rode the Bicycle"

    /** The start of the note of a map where cycling isn't allowed. */
    const val NO_CYCLING = "no cycling on"
}
