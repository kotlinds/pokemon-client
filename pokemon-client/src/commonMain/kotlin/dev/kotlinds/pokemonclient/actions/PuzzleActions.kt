package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.world.FieldMoves
import dev.kotlinds.pokemonclient.state.ObstacleKind

import dev.kotlinds.pokemonclient.state.kind
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.world.FieldMoveAccess
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.PushEdge
import dev.kotlinds.pokemonclient.world.PushPlanner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The movement-puzzle actions the agent names explicitly: `push` (a Strength boulder one tile in a direction, on any
 * map; or into its own hole, Ice Path B1F).
 *
 * They are the agent's own act (it chose which boulder and where it goes), so they run whatever
 * [ActionSettings.solvePuzzles] says: that setting only stops the walks from operating mechanisms by themselves.
 * Listed with the common actions ([CommonActions.definitions]).
 */
object PuzzleActions {

    private val assisted = setOf(ActionMode.ASSISTED)

    val push = ActionDefinition(GameAction.Push::class, object : ActionSpec<GameAction.Push> {
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
        override fun availability(state: GameState): Availability {
            if (!MovePlans.canWalk(state, hasWorld = true)) return Availability.Hidden
            val field = state.field ?: return Availability.Hidden
            val holes = field.puzzle?.boulderHoles.orEmpty().filter { !it.fallen }.associateBy { it.boulder }
            val boulders = field.objects.filter { it.obstacle == ObstacleKind.BOULDER }
            if (boulders.isEmpty()) return Availability.Hidden
            return Availability.Available(mapOf("boulder" to boulders.map { b ->
                Choice(b.id, "boulder at ${b.x},${b.y}" + (holes[b.id]?.let { " (its hole: ${it.hole.x},${it.hole.y})" } ?: ""))
            }))
        }
        override fun parse(json: JsonObject): GameAction.Push {
            val boulder = json["boulder"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: throw ActionException(ActionError.InvalidParameter("boulder", "missing"))
            val raw = json["direction"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val direction = raw?.let { Direction.parse(it) ?: throw ActionException(ActionError.InvalidParameter("direction", it, Direction.entries.map { d -> d.name.lowercase() })) }
            return GameAction.Push(boulder, direction)
        }
    }, ActionPlan { action, context -> PushPlans.push(action, context) })

    val definitions: List<ActionDefinition<*>> get() = listOf(push)
}

/**
 * The recipe of `push`: plan with the [PushPlanner] ([PushPlanner.pushOnce] towards a direction, [PushPlanner.pushInto]
 * into its hole), then walk to each push and push, re-planning after each one.
 */
internal object PushPlans {

    fun push(action: GameAction.Push, context: PlanContext): ActionOutcome {
        val done = mutableListOf<String>()
        var strengthUsed = false
        val direction = action.direction
        repeat(MAX_PUSHES) {
            val state = context.navigator.settle()
            val field = state.field
            if (field == null || state.screen !is Screen.Overworld) return interrupted(state, done)
            // Towards a direction: one push, then done (the answer says where it went).
            if (direction != null && done.isNotEmpty()) return ActionOutcome.Done(done.joinToString("; "))
            val hole = field.puzzle?.boulderHoles?.firstOrNull { it.boulder == action.boulder }
            if (direction == null) {
                if (hole == null) {
                    val boulders = field.objects.filter { it.obstacle == ObstacleKind.BOULDER }.map { it.id }
                    return ActionOutcome.Failed(if (action.boulder in boulders) ActionError.InvalidParameter("direction", "missing", Direction.entries.map { it.name.lowercase() })
                    else ActionError.InvalidParameter("boulder", action.boulder, boulders))
                }
                if (hole.fallen) {
                    return ActionOutcome.Done(
                        (if (done.isEmpty()) "${action.boulder} has already fallen through its hole" else "${action.boulder} fell through its hole at ${hole.hole.x},${hole.hole.y} to the floor below") +
                            done.joinToString("") { "; $it" },
                    )
                }
            }
            val boulder = field.objects.firstOrNull { it.id == action.boulder && it.obstacle == ObstacleKind.BOULDER }
                ?: return ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, "${action.boulder} isn't a boulder on this floor"))
            val area = context.game.world?.areaOf(field.mapId)
                ?: return noMap(field)
            val access = FieldMoveWalk.access(context, state)
            strengthMissing(access[FieldMoveKind.STRENGTH])?.let { return ActionOutcome.Failed(it) }
            val options = MovePlans.routeOptions(field, MoveOptions(), FieldMoves.usable(access), MovePlans.stepWeights(context, state, MoveOptions()))
            // The puzzle's live state (shutters, people) as walks see it, every mechanism allowed: this is the agent's act.
            val overlay = MovePlans.overlay(context, field, emptySet(), solve = true)
            val start = Pathfinder(area, overlay).nodeOf(field)
            val planner = PushPlanner(area, overlay)
            val route = (if (direction != null) planner.pushOnce(start, options, boulder.x to boulder.y, direction) else planner.pushInto(start, options, boulder.x to boulder.y))
                ?: return ActionOutcome.Failed(if (direction != null) cantPush(action.boulder, boulder.x, boulder.y, direction, field.x, field.y)
                else ActionError.Unavailable(UnavailableReason.NO_PATH,
                    "no way found to push ${action.boulder} (at ${boulder.x},${boulder.y}) into its hole at ${hole!!.hole.x},${hole.hole.y} from ${field.x},${field.y}" +
                        done.joinToString("") { "; $it" },
                    "something may block it (another boulder, a wall): leave the floor and come back to reset the boulders, or push it one tile at a time (direction)"))
            val index = route.edges.indexOfFirst { it is PushEdge }
            val edge = route.edges[index] as PushEdge
            val standAt = if (index == 0) start else route.edges[index - 1].to
            if (field.x != standAt.x || field.y != standAt.y) {
                // Walk only: no other boulder moved on the way (the plan keeps them where they are).
                val walker = PlanContext(context.scope, context.game, context.navigator, context.settings.copy(solvePuzzles = false))
                val walked = MovePlans.walkTo(walker, MovePlans.Target("${standAt.x},${standAt.y}", standAt.x, standAt.y), MoveOptions())
                if (walked !is MovePlans.Walk.Arrived || walked.through != null) return with(MovePlans) { walked.toOutcome(context) { "" } }.withDone(done)
                if (walked.field.x != standAt.x || walked.field.y != standAt.y || walked.field.mapId != field.mapId) return@repeat
            }
            if (!strengthUsed) {
                when (val used = FieldMoveWalk.activateStrength(context, edge.direction)) {
                    is FieldMoveWalk.Use.Done -> strengthUsed = true
                    is FieldMoveWalk.Use.Stopped -> return interrupted(used.state, done)
                    is FieldMoveWalk.Use.Failed -> return ActionOutcome.Failed(used.error).withDone(done)
                }
            }
            val pushedTo = "pushed ${action.boulder} ${edge.direction.name.lowercase()} from ${edge.objectFrom.first},${edge.objectFrom.second} to ${edge.objectTo.first},${edge.objectTo.second}"
            when (val pushed = FieldMoveWalk.push(context, edge, MoveOptions())) {
                is MovePlans.StepResult.Moved -> done += pushedTo
                is MovePlans.StepResult.Stopped -> {
                    // The last push drops the boulder: the game says so ("The boulder fell down!"), read to its end.
                    val ownHole = hole?.takeIf { !it.fallen }?.hole
                    if (pushed.state.battle != null || ownHole == null || edge.objectTo.first != ownHole.x || edge.objectTo.second != ownHole.y) return interrupted(pushed.state, done)
                    done += "$pushedTo, where it fell through its hole to the floor below"
                    val read = context.navigator.advanceUntil(FALL_MESSAGE_PRESSES) { it.battle != null || it.screen is Screen.Overworld }
                    if (read !is Step.Done || read.value.screen !is Screen.Overworld) return interrupted(context.state(), done)
                }
                is MovePlans.StepResult.Failed -> return ActionOutcome.Failed(pushed.error).withDone(done)
                MovePlans.StepResult.Refused -> return ActionOutcome.Failed(ActionError.Timeout(
                    "the boulder at ${edge.objectFrom.first},${edge.objectFrom.second} didn't move when pushed ${edge.direction.name.lowercase()} from ${standAt.x},${standAt.y}",
                )).withDone(done)
            }
        }
        return ActionOutcome.Failed(ActionError.Timeout("${action.boulder} still hasn't fallen after $MAX_PUSHES pushes" + done.joinToString("") { "; $it" }))
    }

    /**
     * The refusal of a push towards [direction] the [PushPlanner] found no plan for: the tile behind the boulder refuses
     * it, or its other side can't be reached. Nothing moved.
     */
    private fun cantPush(id: String, x: Int, y: Int, direction: Direction, fromX: Int, fromY: Int): ActionError {
        val dir = direction.name.lowercase()
        val behind = "${x + direction.dx},${y + direction.dy}"
        val side = "${x - direction.dx},${y - direction.dy}"
        return ActionError.Unavailable(UnavailableReason.NO_PATH,
            "can't push $id (at $x,$y) $dir from $fromX,$fromY: nothing moved",
            "either $behind can't take it (a wall, water, a ledge, a warp, another object, a hole that isn't its own) or $side, " +
                "the tile to push it from, can't be reached without moving other boulders: try another direction or another boulder first")
    }

    /** The typed reason Strength can't be used, or null when it can. */
    private fun strengthMissing(access: FieldMoveAccess?): ActionError? = when (access) {
        is FieldMoveAccess.Usable -> null
        is FieldMoveAccess.NoBadge -> ActionError.Unavailable(UnavailableReason.NEEDS_BADGE, "Strength needs the ${access.badge} Badge")
        FieldMoveAccess.NoPokemon -> ActionError.Unavailable(UnavailableReason.NO_POKEMON_KNOWS_MOVE, "No Pokémon of the party knows Strength")
        FieldMoveAccess.NotSupported -> ActionError.Unavailable(UnavailableReason.NOT_SUPPORTED_BY_GAME, "using Strength isn't supported in this game yet")
        FieldMoveAccess.Unknown, null -> ActionError.Unavailable(UnavailableReason.NO_POKEMON_KNOWS_MOVE, "Strength can't be used in this game")
    }

    private fun interrupted(state: GameState, done: List<String>) = ActionOutcome.Failed(ActionError.Interrupted(
        if (state.battle != null) InterruptionCause.WILD_BATTLE else InterruptionCause.SCRIPT,
        (if (done.isEmpty()) "no push" else done.joinToString("; ")) + " (now: ${state.screen.kind})",
    ))

    private fun ActionOutcome.withDone(done: List<String>): ActionOutcome =
        if (done.isEmpty() || this !is ActionOutcome.Failed || error !is ActionError.Interrupted) this
        else ActionOutcome.Failed(error.copy(performed = error.performed + done.joinToString("") { "; $it" }))

    /** Presses to read the message of the boulder's fall. */
    private const val FALL_MESSAGE_PRESSES = 4

    /** Pushes at most per `push` (the Ice Path boulders need a handful). */
    private const val MAX_PUSHES = 30
}
