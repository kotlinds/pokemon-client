package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.TeleportKind
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Edge
import dev.kotlinds.pokemonclient.world.NeedsMechanism
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.MechanismPlanner
import dev.kotlinds.pokemonclient.world.SwitchEdge
import dev.kotlinds.pokemonclient.world.PushEdge
import dev.kotlinds.pokemonclient.world.PushPlanner
import dev.kotlinds.pokemonclient.world.PuzzleMechanism
import dev.kotlinds.pokemonclient.world.Route
import dev.kotlinds.pokemonclient.world.RouteOptions

/**
 * The walks when movement puzzles are left to the agent ([ActionSettings.solvePuzzles] off):
 * - [walkOnly] turns the live overlay into one where routes only walk: lifts and cart stations with their cart are no
 *   teleports and their tiles are never entered (unless they are the destination), the moving platforms are floor
 *   where they stand but their triggers that would move them are never entered, levers are never pressed (only
 *   [MechanismPlanner] presses them), and slides never stop against a movable ice block. Strength
 *   boulders are already never walked into by plain routes ([PushPlanner] is what pushes them, and it isn't used);
 * - [diagnose] tells, when no walking route exists, whether one operating a mechanism does, and the first mechanism on
 *   it ([NeedsMechanism]), so the agent knows what to do itself.
 */
internal object PuzzleSolving {

    /** [overlay] (the solving one) with every movement-puzzle mechanism left alone, for [field]'s puzzle. */
    fun walkOnly(overlay: Overlay, field: FieldState): Overlay {
        val puzzle = field.puzzle
        val lifts = liftTiles(field)
        val mechanics = puzzle?.mechanics
        val platformFloor = mechanics?.let { floor(it) }.orEmpty()
        val triggers = movingTriggers(field)
        return overlay.copy(
            teleports = overlay.teleports.filterNot { (it.fromX to it.fromY) in lifts || (it.fromX to it.fromY) in triggers },
            openTiles = overlay.openTiles + (platformFloor - triggers),
            forbiddenTiles = overlay.forbiddenTiles + lifts + triggers,
            avoidPushes = true,
        )
    }

    /**
     * True when stepping on ([x], [y]) operates a mechanism of [field]'s map right now: a puzzle's teleport (a pad, a
     * cart station, a lift: [dev.kotlinds.pokemonclient.state.PuzzleState.teleports]) or a platform trigger.
     */
    fun isMechanism(field: FieldState, x: Int, y: Int): Boolean =
        field.puzzle?.teleports.orEmpty().any { t -> t.from.any { it.x == x && it.y == y } } || (x to y) in movingTriggers(field)

    /**
     * True when [warped], a jump of the player seen by the [WarpWatch], is a ride of the map's puzzle rather than a
     * warp: on the same map, started from a mechanism ([isMechanism]) the player stood on or stepped onto (the tile
     * read last before the jump, or the one they faced). A walk goes on (or arrives) after a ride; it stops at a warp.
     */
    fun isRide(warped: WarpWatch.Warped): Boolean {
        val from = warped.from
        if (warped.to.mapId != from.mapId) return false
        val ahead = from.facing?.let { from.x + it.dx to from.y + it.dy }
        return isMechanism(from, from.x, from.y) || (ahead != null && isMechanism(from, ahead.first, ahead.second))
    }

    /** The tiles that start a lift ([TeleportKind.LIFT]) on [field]'s map. */
    fun liftTiles(field: FieldState): Set<Pair<Int, Int>> =
        field.puzzle?.teleports.orEmpty().filter { it.kind == TeleportKind.LIFT }.flatMap { t -> t.from.map { it.x to it.y } }.toSet()

    /** The trigger tiles that would start a ride right now (a platform that can move, a station with its cart). */
    private fun movingTriggers(field: FieldState): Set<Pair<Int, Int>> {
        val puzzle = field.puzzle ?: return emptySet()
        val mechanics = puzzle.mechanics ?: return emptySet()
        return mechanics.movingTriggers(puzzle.platforms.flatMap { p -> p.triggers.map { it.tile.x to it.tile.y } })
    }

    /** The tiles [mechanics] makes walkable in its state of now. */
    private fun <S> floor(mechanics: dev.kotlinds.pokemonclient.world.PuzzleMechanics<S>): Set<Pair<Int, Int>> = mechanics.walkTiles(mechanics.state)

    /**
     * The first mechanism a route operating them would use from [start] (platform rides, the lift, pushes), when no
     * walking route exists; null when even operating them finds no way (the plain failure stands then).
     */
    fun diagnose(
        area: Area,
        field: FieldState,
        solving: Overlay,
        start: Node,
        options: RouteOptions,
        enterable: Set<Pair<Int, Int>>,
        isGoal: (Node) -> Boolean,
    ): NeedsMechanism? {
        field.puzzle?.mechanics?.let { mechanics ->
            MechanismPlanner(area, solving, mechanics).route(start, options, enterable, isGoal)?.let { route -> first(route, start, field)?.let { return it } }
        }
        (Pathfinder(area, solving).route(start, options, enterable, isGoal = isGoal) as? Pathfinder.Result.Found)?.let { found ->
            first(found.route, start, field)?.let { return it }
        }
        val pushes = PushPlanner(area, solving)
        if (pushes.hasMovables(options)) pushes.route(start, options, enterable, isGoal)?.let { route -> first(route, start, field)?.let { return it } }
        return null
    }

    /** The first mechanism [route] operates (from [start]), or null when it only walks. */
    internal fun first(route: Route, start: Node, field: FieldState): NeedsMechanism? {
        val lifts = liftTiles(field)
        val mechanics = field.puzzle?.mechanics
        var from = start
        for (edge in route.edges) {
            when {
                edge is PushEdge -> return NeedsMechanism(
                    if (edge.needsStrength) PuzzleMechanism.STRENGTH_BOULDER else PuzzleMechanism.ICE_BLOCK,
                    edge.objectFrom.first, edge.objectFrom.second, from, edge.direction, edge.objectTo,
                )
                edge is Edge.Teleport && (edge.via.x to edge.via.y) in lifts ->
                    return NeedsMechanism(PuzzleMechanism.LIFT, edge.via.x, edge.via.y, from, edge.direction)
                edge is Edge.Teleport && mechanics?.ridesNow(edge.via.x, edge.via.y) == true ->
                    return NeedsMechanism(mechanics.mechanism, edge.via.x, edge.via.y, from, edge.direction)
                edge is SwitchEdge -> return NeedsMechanism(PuzzleMechanism.SWITCH, from.x + edge.direction.dx, from.y + edge.direction.dy, from, edge.direction, target = edge.target)
            }
            from = edge.to
        }
        return null
    }

    /** What the agent should do about [failure], with the object's id when there is one on [field]. */
    fun hint(failure: NeedsMechanism, field: FieldState?): String {
        val from = failure.from?.let { "${it.x},${it.y}" }
        val dir = failure.direction?.name?.lowercase()
        val id = field?.objects?.firstOrNull { it.x == failure.x && it.y == failure.y }?.id
        val left = "movement puzzles are left to you (go_to only walks)"
        return when (failure.mechanism) {
            PuzzleMechanism.STRENGTH_BOULDER -> "$left: the way needs the boulder ${id ?: ""} at ${failure.x},${failure.y} pushed $dir" +
                (failure.objectTo?.let { " (to ${it.first},${it.second})" } ?: "") +
                (if (id != null && failure.direction != null) ": " + FieldMoveWalk.pushCall(id, failure.direction, failure.from)
                else ": stand on $from, use Strength on it (interact with it, answer yes), then step $dir")
            PuzzleMechanism.ICE_BLOCK -> "$left: the way needs the ice block ${id ?: ""} at ${failure.x},${failure.y} pushed: " +
                "from $from, step $dir and slide into it" + (failure.objectTo?.let { " (it slides to ${it.first},${it.second})" } ?: "")
            PuzzleMechanism.MOVING_PLATFORM -> "$left: the way needs a platform moved: step on its trigger at ${failure.x},${failure.y} " +
                "(from $from, step $dir; or go_to ${failure.x},${failure.y}), see puzzle.platforms for what each trigger does"
            PuzzleMechanism.LIFT -> "$left: the way goes through the lift at ${failure.x},${failure.y}: step onto it " +
                "(from $from, step $dir; or go_to ${failure.x},${failure.y}) to ride it to the other floor"
            PuzzleMechanism.CART_RIDE -> "$left: the way needs a cart ride: step on the station at ${failure.x},${failure.y} " +
                "(from $from, step $dir; or go_to ${failure.x},${failure.y}), see puzzle.teleports for where each cart goes now"
            PuzzleMechanism.SWITCH -> "$left: the way needs the lever ${failure.target ?: ""} at ${failure.x},${failure.y} pressed: " +
                "interact ${failure.target ?: "with it"} (from $from, facing $dir), see puzzle.switches for what it changes"
        }.replace("  ", " ")
    }
}
