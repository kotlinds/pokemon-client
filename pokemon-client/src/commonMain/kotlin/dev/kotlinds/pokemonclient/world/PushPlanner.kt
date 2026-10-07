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
 * plain routes' ones: the same search ([repelDijkstra]: a Repel wearing off on the way counted step by step), the
 * same moves ([Pathfinder.neighbours]), the same cost of a turn ([RouteOptions.turnPenalty]: a push in another
 * direction than the player arrived in is a turn too, the player faces the object first), the same ledge rule
 * ([boundedLedgeRule]: the way back is checked with the objects where the route leaves them) and the same warnings
 * ([Pathfinder.describe]). Pushes are expensive so routes push as little as possible, and the search gives up after
 * [maxStates] places (positions × configurations: [PushProof.OverBound], no plan within the bound).
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

    /** Whether pushing boulders opens a way ([opensWay]): proven with its plan, no plan at all, or not known. */
    sealed interface PushProof {
        /** The pushes open the way: [route] does it. */
        data class Opens(val route: Route) : PushProof

        /** No plan exists (a wall behind the boulder, a push into a dead end): the boulders are no way. */
        data object NoPlan : PushProof

        /** The search gave up at its bound ([maxStates]) before finding a plan: nothing is proven either way. */
        data object OverBound : PushProof
    }

    /** True when the overlay has objects this planner can move with [options]. */
    fun hasMovables(options: RouteOptions): Boolean =
        movableTemplates.any { it.iceBlock || FieldMoveKind.STRENGTH in options.fieldMoves }

    /** The cheapest route from [start] to a node satisfying [isGoal], pushing objects on the way; null when none. */
    fun route(start: Node, options: RouteOptions, goalTiles: Set<Pair<Int, Int>> = emptySet(), isGoal: (Node) -> Boolean): Route? =
        (proof(start, options, goalTiles) { isGoal(it.node) } as? PushProof.Opens)?.route

    /**
     * The cheapest pushes from [start] that drop the boulder at [boulder] through its own hole ([LiveObject.fallsInto]),
     * every other object staying where it is (pushing another one elsewhere could close the puzzle); null when it isn't
     * a boulder with a hole, Strength isn't in [RouteOptions.fieldMoves], or no plan exists within the bound.
     */
    fun pushInto(start: Node, options: RouteOptions, boulder: Pair<Int, Int>): Route? {
        val chosen = movableTemplates.firstOrNull { it.x == boulder.first && it.y == boulder.second && it.clearedBy == FieldMoveKind.STRENGTH && it.fallsInto != null }
            ?: return null
        return alone(chosen).search(start, options, emptySet()) { state -> state.movables.single().gone }
    }

    /**
     * The walk from [start] to the side of the Strength boulder at [boulder] and its one push towards [direction] (the
     * `push` action with a direction), every other object staying where it is; null when it isn't a boulder, Strength
     * isn't in [RouteOptions.fieldMoves], the tile behind it refuses it (a wall, water, another object, a hole that
     * isn't its own) or the tile to push it from can't be reached. The boulder moves that way only, so the plan pushes
     * it exactly once.
     */
    fun pushOnce(start: Node, options: RouteOptions, boulder: Pair<Int, Int>, direction: Direction): Route? {
        val chosen = movableTemplates.firstOrNull { it.x == boulder.first && it.y == boulder.second && it.clearedBy == FieldMoveKind.STRENGTH }
            ?: return null
        val to = boulder.first + direction.dx to boulder.second + direction.dy
        return alone(chosen).search(start, options, emptySet(), pushes = direction) { state -> state.movables.single().let { it.x to it.y == to } }
    }

    /** A planner where only [chosen] moves: the other movable objects become plain obstacles. */
    private fun alone(chosen: LiveObject): PushPlanner = only { it === chosen }

    /** A planner where only the movable objects [moves] keeps move: the others become plain obstacles. */
    private fun only(moves: (LiveObject) -> Boolean): PushPlanner {
        val fixed = overlay.objects.map { o -> if (moves(o) || (o.clearedBy != FieldMoveKind.STRENGTH && !o.iceBlock)) o else o.copy(clearedBy = null, iceBlock = false) }
        return PushPlanner(area, overlay.copy(objects = fixed), maxStates)
    }

    /**
     * The proof that pushing Strength boulders opens a way from [start] to [isGoal] (what a diagnosis crossing the
     * boulders assumes, [Pathfinder.diagnose]): the cheapest plan with Strength and [moves] (the other field moves the
     * way needs) usable on top of [options], ledges allowed like the diagnosis does. Only the Strength boulders at
     * [moving] move (the ones the way crosses; null: every movable object), the others stay where they are: a much
     * smaller search. [PushProof.NoPlan] when there is none (a wall behind the boulder, a push into a dead end): the
     * boulders are no way; [PushProof.OverBound] when the search gave up at its bound: not proven either way.
     */
    fun opensWay(
        start: Node, options: RouteOptions, moves: Collection<FieldMoveKind>, goalTiles: Set<Pair<Int, Int>>,
        moving: Set<Pair<Int, Int>>? = null, isGoal: (Node) -> Boolean,
    ): PushProof {
        val assumed = options.copy(
            fieldMoves = options.fieldMoves + moves + FieldMoveKind.STRENGTH,
            canSurf = options.canSurf || FieldMoveKind.SURF in moves,
            acceptOneWay = true,
        )
        val planner = if (moving == null) this else only { it.clearedBy == FieldMoveKind.STRENGTH && (it.x to it.y) in moving }
        return planner.proof(start, assumed, goalTiles) { isGoal(it.node) }
    }

    /** [proof]'s plan, or null when there is none or the search gave up at its bound. */
    private fun search(start: Node, options: RouteOptions, goalTiles: Set<Pair<Int, Int>>, pushes: Direction? = null, isDone: (State) -> Boolean): Route? =
        (proof(start, options, goalTiles, pushes, isDone) as? PushProof.Opens)?.route

    /**
     * The cheapest plan from [start] to a state where [isDone] holds, under the [boundedLedgeRule]
     * ([PushProof.NoPlan]: none at all, [PushProof.OverBound]: none within the bound). [pushes]: the only direction
     * Strength boulders may be pushed (null: any).
     */
    private fun proof(start: Node, options: RouteOptions, goalTiles: Set<Pair<Int, Int>>, pushes: Direction? = null, isDone: (State) -> Boolean): PushProof {
        val initial = State(Heading(start, null), movableTemplates.map { Movable(it.x, it.y, boulder = it.clearedBy == FieldMoveKind.STRENGTH, fallsInto = it.fallsInto) })
        fun plan(allowJumps: Boolean): SearchResult<State, *, Edge> = repelDijkstra(
            start = initial,
            options = options,
            place = { it.place },
            isGoal = isDone,
            maxPlaces = maxStates,
            turnCost = { state, now -> pathfinder(state.movables).turnCostAt(state.node, now) },
        ) { state, now, turnCost ->
            // Every move ends on its edge's tile (a push too: the player stays, the edge is "to" their own tile).
            moves(state, now, goalTiles, allowJumps, pushes).map { (edge, movables) ->
                SearchMove(State(Heading(edge.to, edge.endDirection), movables), edge.cost + turn(state.heading.direction, edge, turnCost), edge, edge.gameSteps)
            }
        }
        // The way back is walked with the objects where the plan leaves them.
        val wayBack = { end: State -> pathfinder(end.movables).hasWayBack(end.node, start, options) }
        return when (val bounded = boundedLedgeRule(options, ::plan, wayBack)) {
            BoundedLedgeChoice.OverBound -> PushProof.OverBound
            null -> PushProof.NoPlan
            is BoundedLedgeChoice.Chosen -> when (val ledges = bounded.choice) {
                is LedgeChoice.Take -> PushProof.Opens(pathfinder(initial.movables).describe(ledges.edges, ledges.oneWay))
                LedgeChoice.OnlyOneWay -> PushProof.NoPlan
            }
        }
    }

    private fun pathfinder(movables: List<Movable>): Pathfinder = pathfinders.getOrPut(movables) {
        val objects = others + movables.filterNot { it.gone }.map { m ->
            LiveObject(m.x, m.y, null, clearedBy = if (m.boulder) FieldMoveKind.STRENGTH else null, iceBlock = !m.boulder && !m.frozen)
        }
        Pathfinder(area, overlay.copy(objects = objects))
    }

    /** The moves from [state], with where the objects are after each: the plain ones (objects unmoved), and the pushes. */
    private fun moves(state: State, options: RouteOptions, goalTiles: Set<Pair<Int, Int>>, allowJumps: Boolean, pushes: Direction? = null): List<Pair<Edge, List<Movable>>> {
        val node = state.node
        val result = mutableListOf<Pair<Edge, List<Movable>>>()
        for (edge in pathfinder(state.movables).neighbours(node, options, goalTiles, allowJumps = allowJumps)) {
            result += (icePush(edge, state) ?: (edge to state.movables))
        }
        if (FieldMoveKind.STRENGTH in options.fieldMoves) {
            for (dir in Direction.entries) if (pushes == null || dir == pushes) strengthPush(state, dir)?.let { result += it }
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
