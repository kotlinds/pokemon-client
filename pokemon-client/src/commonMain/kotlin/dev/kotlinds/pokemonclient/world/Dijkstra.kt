package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction

/**
 * One move of a [dijkstra] search: the state [to] it reaches, what it costs, and what the route keeps of it ([label]:
 * the [Edge] walked, the [ZoneLink] taken...).
 */
internal class SearchMove<S, out L>(val to: S, val cost: Int, val label: L)

/** A route found by [dijkstra]: every move from the start in order ([steps]: the state reached and its label). */
internal class SearchPath<S, L>(val steps: List<Pair<S, L>>, val end: S, val cost: Int) {
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
 * are cut ([turnDominated]): one reached for at least one turn more than the cheapest heading of its place can't lead
 * anywhere cheaper. With no turn cost (the default), each place is explored once: the plain Dijkstra.
 *
 * - The start's own place is never a goal (a route has at least one move).
 * - [maxPlaces] bounds the distinct places explored ([SearchResult.OverBound] beyond: the puzzle searches give up);
 *   counting places rather than headings keeps the reach of a search the same with or without turns.
 * - Moves costing more than [maxCost] in total are left out ([Pathfinder.reachable]).
 *
 * No A* heuristic: ledges (2 tiles for 1) and teleports make the distance to the goal overestimate the cost, and the
 * searches are small enough without one.
 */
internal fun <S, P, L> dijkstra(
    start: S,
    place: (S) -> P,
    isGoal: (S) -> Boolean = { false },
    turnCost: (S) -> Int = { 0 },
    maxPlaces: Int = Int.MAX_VALUE,
    maxCost: Int = Int.MAX_VALUE,
    moves: (state: S, turnCost: Int) -> Iterable<SearchMove<S, L>>,
): SearchResult<S, P, L> {
    val dist = HashMap<S, Int>()
    val previous = HashMap<S, Pair<S, L>>()
    val settled = HashMap<P, Int>()
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
        if (turnDominated(settled, at, d, turn)) continue
        if (at != startPlace && isGoal(state)) return SearchResult.Found(path(previous, start, state, d))
        if (firstVisit && ++explored > maxPlaces) return SearchResult.OverBound
        for (move in moves(state, turn)) {
            val next = d + move.cost
            if (next > maxCost) continue
            if (next < (dist[move.to] ?: Int.MAX_VALUE)) {
                dist[move.to] = next
                previous[move.to] = state to move.label
                queue.add(move.to to next)
            }
        }
    }
    return SearchResult.Exhausted(settled)
}

/** The found path of a search, or null whatever else happened (no goal reachable, over the bound). */
internal val <S, P, L> SearchResult<S, P, L>.found: SearchPath<S, L>? get() = (this as? SearchResult.Found)?.path

/** The moves from [start] to [end], read back from the [previous] links of the search. */
private fun <S, L> path(previous: Map<S, Pair<S, L>>, start: S, end: S, cost: Int): SearchPath<S, L> {
    val steps = ArrayDeque<Pair<S, L>>()
    var at = end
    while (at != start) {
        val (from, label) = previous.getValue(at)
        steps.addFirst(at to label)
        at = from
    }
    return SearchPath(steps.toList(), end, cost)
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
 * True when [place], reached for [cost], needn't be explored: another state of it was already explored for at least
 * [turnCost] less (the cheapest one is explored first, and from it every move costs at most one turn more, so nothing
 * is cheaper from this one). Records [cost] as the place's cheapest otherwise. With no turn cost, every place is
 * explored once, like a plain Dijkstra.
 */
private fun <P> turnDominated(settled: MutableMap<P, Int>, place: P, cost: Int, turnCost: Int): Boolean {
    val best = settled[place] ?: run {
        settled[place] = cost
        return false
    }
    return cost >= best + turnCost
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
