package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The movement-puzzle actions the agent names explicitly: `push` (a Strength boulder one tile in a direction, on any
 * map; or into its own hole, Ice Path B1F).
 *
 * They are the agent's own act (it chose which boulder and where it goes), so they run whatever
 * [ActionSettings.solvePuzzles] says: that setting only stops the walks from operating mechanisms by themselves.
 * Listed with the common actions ([CommonActions.definitions]); the recipe is [MoveRecipes.push], its availability
 * [MoveRecipes.pushAvailability].
 */
object PuzzleActions {

    private val assisted = setOf(ActionMode.ASSISTED)

    val push = ActionDefinition(GameAction.Push::class, Recipes.Conditions.push, object : ActionSpec<GameAction.Push> {
        override val name = "push"
        override val description = "Push a Strength boulder: one tile towards `direction` (walks to its other side first), or, without a " +
            "direction, into its own hole (puzzle.boulder_holes, Ice Path B1F: it drops to the floor below, where it stops slides on " +
            "the ice; plans every push). Checked before moving: the tile behind the boulder must take it and its other side must be " +
            "reachable, else nothing moves and the error says so. Uses Strength (a Pokémon must know it, with the badge). Works even " +
            "when go_to leaves puzzles to you."
        override val parameters = listOf(
            Parameter("boulder", ParameterType.STRING, "The boulder: person:N (a Strength boulder of the map's objects)."),
            Parameter("direction", ParameterType.STRING, "Where to push it, one tile: north, south, west or east. Leave it out to push a " +
                "boulder of puzzle.boulder_holes into its hole.", required = false, values = Direction.entries.map { it.name.lowercase() }),
        )
        override val modes = assisted
        override fun parse(json: JsonObject): GameAction.Push {
            val boulder = json["boulder"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw ActionException(ActionError.InvalidParameter("boulder", "missing"))
            val raw = json["direction"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val direction = raw?.let { Direction.parse(it) ?: throw ActionException(ActionError.InvalidParameter("direction", it, Direction.entries.map { d -> d.name.lowercase() })) }
            return GameAction.Push(boulder, direction)
        }
    })

    val definitions: List<ActionDefinition<*>> get() = listOf(push)
}
