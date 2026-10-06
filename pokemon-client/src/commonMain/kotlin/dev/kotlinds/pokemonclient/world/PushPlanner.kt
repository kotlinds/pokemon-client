package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction

/**
 * Plans routes that move objects out of the way, a small puzzle search over (player position, object positions):
 * - Strength boulders ([LiveObject.clearedBy] = [FieldMoveKind.STRENGTH], when [RouteOptions.fieldMoves] has
 *   Strength): walking into one pushes it one tile, when the tile behind it is free floor;
 * - ice blocks ([LiveObject.iceBlock], the Mahogany Gym): sliding on the ice into one stops the player and slides the
 *   block on (src/unk_0206D494.c): it stops before a wall, a tile that isn't ice or another object; meeting another
 *   ice block, both freeze together and can't be pushed any more.
 *
 * Used when the plain [Pathfinder] finds no route. The search is the routes' [dijkstra] over states (the player's
 * [Heading] and the objects' positions), each object configuration getting its own [Pathfinder]; the rules are the
 * plain routes' ones: the same moves ([Pathfinder.neighbours]), the same cost of a turn ([RouteOptions.turnPenalty]:
 * a push in another direction than the player arrived in is a turn too, the player faces the object first), the same
 * ledge rule ([boundedLedgeRule]: the way back is checked with the objects where the route leaves them) and the same warnings
 * ([Pathfinder.describe]). Pushes are expensive so routes push as little as possible, and the search gives up after
 * [maxStates] places (positions × configurations; null: no plan within the bound).
 */
class PushPlanner(private val area: Area, private val overlay: Overlay, private val maxStates: Int = DEFAULT_MAX_STATES) {

    /**
     * One movable object in a configuration: where it is, and whether it can still move. [fallsInto]: the hole a
     * boulder drops through; [gone] once it did.
     */
    private data class Movable(
        val x: Int,
        val y: Int,
        val boulder: Boolean,
        val frozen: Boolean = false,
        val fallsInto: Pair<Int, Int>? = null,
        val gone: Boolean = false,
    )

    /** Where the player is (and arrived from, see [Heading]) and where the objects are. */
    private data class State(val heading: Heading, val movables: List<Movable>) {
        val node: Node get() = heading.node

        /** The state without the direction: what the search bound counts, and where a turn is cut ([dijkstra]). */
        val place: Pair<Node, List<Movable>> get() = heading.node to movables
    }

    private val movableTemplates = overlay.objects.filter { it.clearedBy == FieldMoveKind.STRENGTH || it.iceBlock }
    private val others = overlay.objects.filter { it.clearedBy != FieldMoveKind.STRENGTH && !it.iceBlock }
    private val warpTiles = area.warps.map { it.x to it.y }.toSet()
    private val pathfinders = HashMap<List<Movable>, Pathfinder>()

    /** True when the overlay has objects this planner can move with [options]. */
    fun hasMovables(options: RouteOptions): Boolean =
        movableTemplates.any { it.iceBlock || FieldMoveKind.STRENGTH in options.fieldMoves }

    /** The cheapest route from [start] to a node satisfying [isGoal], pushing objects on the way; null when none. */
    fun route(start: Node, options: RouteOptions, goalTiles: Set<Pair<Int, Int>> = emptySet(), isGoal: (Node) -> Boolean): Route? =
        search(start, options, goalTiles) { isGoal(it.node) }

    /**
     * The cheapest pushes from [start] that drop the boulder at [boulder] through its own hole ([LiveObject.fallsInto]),
     * every other object staying where it is (pushing another one elsewhere could close the puzzle); null when it isn't
     * a boulder with a hole, Strength isn't in [RouteOptions.fieldMoves], or no plan exists within the bound.
     */
    fun pushInto(start: Node, options: RouteOptions, boulder: Pair<Int, Int>): Route? {
        val chosen = movableTemplates.firstOrNull { it.x == boulder.first && it.y == boulder.second && it.clearedBy == FieldMoveKind.STRENGTH && it.fallsInto != null }
            ?: return null
        // The others become plain obstacles: only the chosen boulder moves.
        val fixed = overlay.objects.map { o -> if (o === chosen || (o.clearedBy != FieldMoveKind.STRENGTH && !o.iceBlock)) o else o.copy(clearedBy = null, iceBlock = false) }
        return PushPlanner(area, overlay.copy(objects = fixed), maxStates).search(start, options, emptySet()) { state -> state.movables.single().gone }
    }

    /** The cheapest plan from [start] to a state where [isDone] holds, under the [boundedLedgeRule]; null when none. */
    private fun search(start: Node, options: RouteOptions, goalTiles: Set<Pair<Int, Int>>, isDone: (State) -> Boolean): Route? {
        val initial = State(Heading(start, null), movableTemplates.map { Movable(it.x, it.y, boulder = it.clearedBy == FieldMoveKind.STRENGTH, fallsInto = it.fallsInto) })
        fun plan(allowJumps: Boolean): SearchResult<State, *, Edge> = dijkstra(
            start = initial,
            place = { it.place },
            isGoal = isDone,
            maxPlaces = maxStates,
            turnCost = { pathfinder(it.movables).turnCostAt(it.node, options) },
        ) { state, turnCost ->
            // Every move ends on its edge's tile (a push too: the player stays, the edge is "to" their own tile).
            moves(state, options, goalTiles, allowJumps).map { (edge, movables) ->
                SearchMove(State(Heading(edge.to, edge.endDirection), movables), edge.cost + turn(state.heading.direction, edge, turnCost), edge)
            }
        }
        // The way back is walked with the objects where the plan leaves them.
        val wayBack = { end: State -> pathfinder(end.movables).hasWayBack(end.node, start, options) }
        return when (val ledges = boundedLedgeRule(options, ::plan, wayBack)) {
            is LedgeChoice.Take -> pathfinder(initial.movables).describe(ledges.edges, ledges.oneWay)
            LedgeChoice.OnlyOneWay, null -> null
        }
    }

    private fun pathfinder(movables: List<Movable>): Pathfinder = pathfinders.getOrPut(movables) {
        val objects = others + movables.filterNot { it.gone }.map { m ->
            LiveObject(m.x, m.y, null, clearedBy = if (m.boulder) FieldMoveKind.STRENGTH else null, iceBlock = !m.boulder && !m.frozen)
        }
        Pathfinder(area, overlay.copy(objects = objects))
    }

    /** The moves from [state], with where the objects are after each: the plain ones (objects unmoved), and the pushes. */
    private fun moves(state: State, options: RouteOptions, goalTiles: Set<Pair<Int, Int>>, allowJumps: Boolean): List<Pair<Edge, List<Movable>>> {
        val node = state.node
        val result = mutableListOf<Pair<Edge, List<Movable>>>()
        for (edge in pathfinder(state.movables).neighbours(node, options, goalTiles, allowJumps = allowJumps)) {
            result += (icePush(edge, state) ?: (edge to state.movables))
        }
        if (FieldMoveKind.STRENGTH in options.fieldMoves) {
            for (dir in Direction.entries) strengthPush(state, dir)?.let { result += it }
        }
        return result
    }

    /**
     * When [edge] ends sliding on ice against a movable ice block, the block slides on: the same edge as a
     * [PushEdge], with the block's new place.
     */
    private fun icePush(edge: Edge, state: State): Pair<Edge, List<Movable>>? {
        val landing = edge.to
        if (area.tile(landing.x, landing.y)?.kind != TileKind.Ice) return null
        val dir = edge.endDirection ?: edge.direction
        val bx = landing.x + dir.dx
        val by = landing.y + dir.dy
        val index = state.movables.indexOfFirst { it.x == bx && it.y == by && !it.boulder && !it.frozen }
        if (index < 0) return null
        var x = bx
        var y = by
        var joined: Int? = null
        while (true) {
            val nx = x + dir.dx
            val ny = y + dir.dy
            val other = state.movables.indexOfFirst { it.x == nx && it.y == ny && !it.gone }
            if (other >= 0) {
                if (!state.movables[other].boulder) joined = other
                break
            }
            val tile = area.tile(nx, ny) ?: break
            if (tile.blocked || tile.kind != TileKind.Ice || others.any { !it.isFollower && it.x == nx && it.y == ny }) break
            x = nx
            y = ny
        }
        if (x == bx && y == by && joined == null) return null
        val moved = state.movables.mapIndexed { i, m ->
            when (i) {
                index -> m.copy(x = x, y = y, frozen = joined != null)
                joined -> m.copy(frozen = true)
                else -> m
            }
        }
        val push = PushEdge(landing, edge.direction, bx to by, x to y, needsStrength = false, tiles = edge.tiles, cost = edge.cost + PUSH_EXTRA_COST)
        return push to moved
    }

    /** Walking [dir] into a boulder next to the player, when the tile behind it is free: a Strength push. */
    private fun strengthPush(state: State, dir: Direction): Pair<Edge, List<Movable>>? {
        val node = state.node
        val bx = node.x + dir.dx
        val by = node.y + dir.dy
        val index = state.movables.indexOfFirst { it.x == bx && it.y == by && it.boulder && !it.gone }
        if (index < 0) return null
        val boulderTile = area.tile(bx, by) ?: return null
        if (boulderTile.blocked) return null
        val tx = bx + dir.dx
        val ty = by + dir.dy
        val target = area.tile(tx, ty) ?: return null
        val falls = state.movables[index].fallsInto == (tx to ty)
        if (!falls && !boulderCanEnter(target, tx, ty, state)) return null
        if (area.tile(node.x, node.y) == null) return null
        val moved = state.movables.mapIndexed { i, m -> if (i == index) m.copy(x = tx, y = ty, gone = falls) else m }
        // The player stays where they are (the boulder slides away alone): following it is a plain step afterwards.
        return PushEdge(node, dir, bx to by, tx to ty, needsStrength = true) to moved
    }

    /**
     * A boulder moves onto plain free floor only (no wall, water, ledge, warp, active trigger such as another
     * boulder's hole, person or other object).
     */
    private fun boulderCanEnter(tile: TileInfo, x: Int, y: Int, state: State): Boolean {
        if (tile.blocked || (x to y) in warpTiles || (x to y) in overlay.blockedTiles || (x to y) in overlay.activeTriggers) return false
        when (tile.kind) {
            TileKind.Floor, TileKind.Cave, TileKind.Sand, TileKind.TallGrass -> Unit
            else -> return false
        }
        if (state.movables.any { it.x == x && it.y == y && !it.gone }) return false
        return others.none { !it.isFollower && it.x == x && it.y == y }
    }

    companion object {
        /** States expanded at most: beyond, the puzzle is left to the agent (with the obstacle named). */
        const val DEFAULT_MAX_STATES = 60_000

        /** Pushing an ice block costs a little more than the slide itself. */
        private const val PUSH_EXTRA_COST = 2
    }
}
