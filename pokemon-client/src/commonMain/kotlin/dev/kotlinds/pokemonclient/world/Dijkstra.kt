package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction

/**
 * One move of a [dijkstra] search: the state [to] it reaches, what it costs, and what the route keeps of it ([label]:
 * the [Edge] walked, the [ZoneLink] taken...). [gameSteps]: the steps the game's step counter counts for it
 * ([Edge.gameSteps]: what wears a Repel off, see [repelDijkstra]); summed along the path found ([SearchPath.gameSteps]).
 */
internal class SearchMove<S, out L>(val to: S, val cost: Int, val label: L, val gameSteps: Int = 0)

/**
 * A route found by [dijkstra]: every move from the start in order ([steps]: the state reached and its label), and the
 * steps the game counts along it ([SearchMove.gameSteps]).
 */
internal class SearchPath<S, L>(val steps: List<Pair<S, L>>, val end: S, val cost: Int, val gameSteps: Int = 0) {
    /** The labels of the moves, in order (the edges of a [Route]). */
    val labels: List<L> get() = steps.map { it.second }

    /** The states reached, in order (the start excluded). */
    val states: List<S> get() = steps.map { it.first }
}

/** How a [dijkstra] search ended. */
internal sealed interface SearchResult<out S, out P, out L> {
    /** A goal was reached: the cheapest [path] to it. */
    data class Found<S, L>(val path: SearchPath<S, L>) : SearchResult<S, Nothing, L>

    /** Every state within the bounds was explored without reaching a goal: [costs] gives each place's cheapest cost. */
    data class Exhausted<P>(val costs: Map<P, Int>) : SearchResult<Nothing, P, Nothing>

    /** More places than the bound allows were explored first. */
    data object OverBound : SearchResult<Nothing, Nothing, Nothing>
}

/**
 * The one Dijkstra of the route planning ([Pathfinder], [WorldRouter], [PushPlanner], [MechanismPlanner]): the cheapest
 * way from [start] to a state where [isGoal] holds, over the states [moves] produces.
 *
 * A state is a [place] (where the player is: a node, an area and node, a node with the objects' positions) plus what
 * else makes the next moves cost differently, typically the direction the player arrived in (see [Heading]): a change
 * of direction costs [turnCost] of the state's place on top of the move ([turn]), which [moves] receives. Most headings
 * are cut ([settledDominates]): one reached for at least one turn more than the cheapest heading of its place can't lead
 * anywhere cheaper. With no turn cost (the default), each place is explored once: the plain Dijkstra.
 *
 * - The start's own place is never a goal (a route has at least one move).
 * - [maxPlaces] bounds the distinct places explored ([SearchResult.OverBound] beyond: the puzzle searches give up);
 *   counting places rather than headings keeps the reach of a search the same with or without turns.
 * - Moves costing more than [maxCost] in total are left out ([Pathfinder.reachable]).
 *
 * No A* heuristic: ledges (2 tiles for 1) and teleports make the distance to the goal overestimate the cost, and the
 * searches are small enough without one.
 *
 * A resource the moves use up ([used], 0 by default: none) makes it a multi-criteria search: a state that has used
 * less of it is never worse later (its moves cost no more: the Repel's steps left, [repelDijkstra]), so a state is cut
 * only by a state of its place explored for at least [turnCost] less that used no more ([settledDominates]: the
 * Pareto labels (cost, used) of each place, very few in practice). Exact as long as costs are non-negative and the
 * moves of a state that used less cost no more.
 */
internal fun <S, P, L> dijkstra(
    start: S,
    place: (S) -> P,
    isGoal: (S) -> Boolean = { false },
    turnCost: (S) -> Int = { 0 },
    maxPlaces: Int = Int.MAX_VALUE,
    maxCost: Int = Int.MAX_VALUE,
    used: (S) -> Int = { 0 },
    moves: (state: S, turnCost: Int) -> Iterable<SearchMove<S, L>>,
): SearchResult<S, P, L> {
    val dist = HashMap<S, Int>()
    val previous = HashMap<S, Link<S, L>>()
    val settled = HashMap<P, ArrayList<Label>>()
    val queue = PriorityQueue<Pair<S, Int>> { a, b -> a.second - b.second }
    val startPlace = place(start)
    dist[start] = 0
    queue.add(start to 0)
    var explored = 0
    while (queue.isNotEmpty()) {
        val (state, d) = queue.poll()
        if (d > (dist[state] ?: Int.MAX_VALUE)) continue
        val at = place(state)
        val firstVisit = at !in settled
        val turn = turnCost(state)
        if (settledDominates(settled, at, d, used(state), turn)) continue
        if (at != startPlace && isGoal(state)) return SearchResult.Found(path(previous, start, state, d))
        if (firstVisit && ++explored > maxPlaces) return SearchResult.OverBound
        for (move in moves(state, turn)) {
            val next = d + move.cost
            if (next > maxCost) continue
            if (next < (dist[move.to] ?: Int.MAX_VALUE)) {
                dist[move.to] = next
                previous[move.to] = Link(state, move.label, move.gameSteps)
                queue.add(move.to to next)
            }
        }
    }
    return SearchResult.Exhausted(settled.mapValues { it.value.first().cost })
}

/** How a state was reached: the state it came [from], the move's [label] and its [gameSteps]. */
private class Link<S, L>(val from: S, val label: L, val gameSteps: Int)

/** A state explored at a place: its [cost], and how much of the resource it had [used] ([dijkstra]). */
private class Label(val cost: Int, val used: Int)

/** The found path of a search, or null whatever else happened (no goal reachable, over the bound). */
internal val <S, P, L> SearchResult<S, P, L>.found: SearchPath<S, L>? get() = (this as? SearchResult.Found)?.path

/**
 * The routes' search ([dijkstra]) with a Repel wearing off on the way ([StepWeights.repel]): the weights of a move
 * depend on how many steps the game counted before it ([StepWeights.after]: the Repel's weights for its first
 * [RepelCover.steps] steps, the plain ones after). The planners ([Pathfinder], [WorldRouter], [PushPlanner],
 * [MechanismPlanner]) give their moves under the route options of the moment ([moves] and [turnCost] receive them),
 * this search tells which ones apply. Without a Repel in [options], the plain [dijkstra].
 *
 * Exact, in two passes:
 * 1. as if the Repel covered every step: no route can cost less than that (a covered move never costs more than the
 *    same move without it), so when the route found takes no more steps than the Repel has left
 *    ([SearchPath.gameSteps]), it is the cheapest: done, one plain search (most trips: a short way, a long Repel);
 * 2. otherwise, the states also carry the Repel's steps left ([Timed], 0 once worn off: every state then weighs the
 *    same), and [dijkstra] keeps per place only the states no other beats on both the cost and the steps left (its
 *    [dijkstra]'s `used`: the steps already counted). A state with more steps left is never worse later (its moves
 *    cost no more), which makes the cut exact.
 *
 * [maxPlaces] bounds the places as in [dijkstra] (a place counted once whatever its steps left).
 */
internal fun <S, P, L> repelDijkstra(
    start: S,
    options: RouteOptions,
    place: (S) -> P,
    isGoal: (S) -> Boolean = { false },
    maxPlaces: Int = Int.MAX_VALUE,
    turnCost: (state: S, options: RouteOptions) -> Int,
    moves: (state: S, options: RouteOptions, turnCost: Int) -> Iterable<SearchMove<S, L>>,
): SearchResult<S, P, L> {
    val repel = options.weights.repel
        ?: return dijkstra(start, place, isGoal, turnCost = { turnCost(it, options) }, maxPlaces = maxPlaces) { state, turn -> moves(state, options, turn) }
    val worn = options.copy(weights = repel.after)
    // The options of a move made with [left] steps of the Repel left: covered while any is left (the game checks the
    // Repel after counting the step, see StepWeights.after).
    fun at(left: Int) = if (left > 0) options else worn
    fun search(counting: Boolean): SearchResult<Timed<S>, P, L> = dijkstra(
        start = Timed(start, repel.steps),
        place = { place(it.state) },
        isGoal = { isGoal(it.state) },
        turnCost = { turnCost(it.state, at(it.left)) },
        maxPlaces = maxPlaces,
        used = { repel.steps - it.left },
    ) { timed, turn ->
        moves(timed.state, at(timed.left), turn).map {
            // The first pass never counts: the Repel covers the whole way (the lower bound).
            val left = if (counting) maxOf(0, timed.left - it.gameSteps) else timed.left
            SearchMove(Timed(it.to, left), it.cost, it.label, it.gameSteps)
        }
    }
    val covered = search(counting = false)
    val lowerBound = covered.found
    val result = if (lowerBound == null || lowerBound.gameSteps <= repel.steps) covered else search(counting = true)
    return when (result) {
        is SearchResult.Found -> SearchResult.Found(result.path.let { p -> SearchPath(p.steps.map { it.first.state to it.second }, p.end.state, p.cost, p.gameSteps) })
        is SearchResult.Exhausted -> result
        SearchResult.OverBound -> SearchResult.OverBound
    }
}

/** A state of [repelDijkstra]: [state], with [left] steps of the Repel left (capped at 0: worn off). */
internal data class Timed<S>(val state: S, val left: Int)

/** The moves from [start] to [end], read back from the [previous] links of the search. */
private fun <S, L> path(previous: Map<S, Link<S, L>>, start: S, end: S, cost: Int): SearchPath<S, L> {
    val steps = ArrayDeque<Pair<S, L>>()
    var gameSteps = 0
    var at = end
    while (at != start) {
        val link = previous.getValue(at)
        steps.addFirst(at to link.label)
        gameSteps += link.gameSteps
        at = link.from
    }
    return SearchPath(steps.toList(), end, cost, gameSteps)
}

/**
 * A state of a turn-aware search: the player on [node], having arrived moving in [direction] (null at the start of
 * the route, or when unknown: after a teleport, a warp). See [RouteOptions.turnCost].
 */
internal data class Heading(val node: Node, val direction: Direction?)

/** What [edge] costs on top of its own cost when the player arrived moving in [direction]: [turnCost] for a turn. */
internal fun turn(direction: Direction?, edge: Edge, turnCost: Int): Int =
    if (turnCost > 0 && direction != null && edge.direction != direction) turnCost else 0

/**
 * True when [place], reached for [cost] having [used] that much of the search's resource, needn't be explored: another
 * state of it was already explored for at least [turnCost] less having used no more (the cheapest ones are explored
 * first, and from them every move costs at most one turn more and no more than from this one, so nothing is cheaper
 * from this one). Records ([cost], [used]) as a label of the place otherwise, when it used less than every label so
 * far (the others are dominated by one of them already).
 *
 * The labels of a place are kept in the order explored: costs rising, [used] falling, so the first one having used no
 * more is the cheapest of them. Without a resource (everything 0), only the first state of a place is recorded and
 * every place is explored once when there are no turns, like a plain Dijkstra.
 */
private fun <P> settledDominates(settled: MutableMap<P, ArrayList<Label>>, place: P, cost: Int, used: Int, turnCost: Int): Boolean {
    val labels = settled[place] ?: run {
        settled[place] = arrayListOf(Label(cost, used))
        return false
    }
    val best = labels.firstOrNull { it.used <= used }
    if (best != null && cost >= best.cost + turnCost) return true
    if (used < labels.last().used) labels += Label(cost, used)
    return false
}

/** A minimal binary-heap priority queue (commonMain has no java.util.PriorityQueue). */
internal class PriorityQueue<T>(private val comparator: Comparator<T>) {
    private val heap = ArrayList<T>()
    fun isNotEmpty() = heap.isNotEmpty()

    fun add(value: T) {
        heap.add(value)
        var i = heap.size - 1
        while (i > 0) {
            val parent = (i - 1) / 2
            if (comparator.compare(heap[i], heap[parent]) >= 0) break
            heap[i] = heap[parent].also { heap[parent] = heap[i] }
            i = parent
        }
    }

    fun poll(): T {
        val top = heap[0]
        val last = heap.removeAt(heap.size - 1)
        if (heap.isNotEmpty()) {
            heap[0] = last
            var i = 0
            while (true) {
                val l = 2 * i + 1
                val r = l + 1
                var smallest = i
                if (l < heap.size && comparator.compare(heap[l], heap[smallest]) < 0) smallest = l
                if (r < heap.size && comparator.compare(heap[r], heap[smallest]) < 0) smallest = r
                if (smallest == i) break
                heap[i] = heap[smallest].also { heap[smallest] = heap[i] }
                i = smallest
            }
        }
        return top
    }
}
