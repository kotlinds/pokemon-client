package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction

/** Where one moving platform is: its pivot tile and how many quarter turns (clockwise) it has made from its rest pose. */
data class PlatformPose(val x: Int, val y: Int, val rotation: Int)

/**
 * What stepping on a mechanism's trigger tile does: the mechanism afterwards ([state]) and where the player stands
 * then ([playerX], [playerY]: a platform carries the player, a cart takes them to its arrival station). [moved] is
 * false when nothing moved (a platform blocked by a wall, the floor or another platform).
 */
data class MechanismRide<S>(val state: S, val playerX: Int, val playerY: Int, val moved: Boolean)

/**
 * A switch the player presses with A ([target], `sign:N`), standing on ([x], [y]) and facing [facing]: the mechanism
 * is in [state] afterwards (a lever setting the cart routes).
 */
data class MechanismPress<S>(val x: Int, val y: Int, val facing: Direction, val target: String, val state: S)

/**
 * A movement puzzle whose state changes where the player can go, with the game's rules: [MechanismPlanner] searches
 * routes operating it. [S] is the puzzle's state (comparable by value): where the platforms are (the Blackthorn Gym,
 * `HgssBlackthornGym`), where the carts wait and how the levers are set (the Azalea Gym, `HgssAzaleaGym`).
 */
interface PuzzleMechanics<S> {
    /** What [ride] and [presses] name a ride after when the agent operates it ([RouteFailure] diagnosis). */
    val mechanism: PuzzleMechanism

    /** The state now, read from the game. */
    val state: S

    /** Tiles the player can stand on in [state] whatever the ROM says (platforms over the lava); none by default. */
    fun walkTiles(state: S): Set<Pair<Int, Int>> = emptySet()

    /** Stepping on ([x], [y]) in [state]: the ride, or null when that tile does nothing then. */
    fun ride(state: S, x: Int, y: Int): MechanismRide<S>?

    /** The switches that can be pressed in [state], and what each one makes of it; none by default. */
    fun presses(state: S): List<MechanismPress<S>> = emptyList()

    /**
     * Every tile that may start a ride in some state (cart stations): the plain routes' teleports there ([Overlay.teleports],
     * read for the state of now) are left to [ride], which knows the state of each step of a plan. None by default.
     */
    val triggerTiles: Set<Pair<Int, Int>> get() = emptySet()

    /** The tiles that would start a ride now (a platform trigger that can move, a station with its cart). */
    fun movingTriggers(candidates: Collection<Pair<Int, Int>>): Set<Pair<Int, Int>> =
        (candidates + walkTiles(state) + triggerTiles).filter { (x, y) -> ride(state, x, y)?.moved == true }.toSet()

    /** True when a ride from ([x], [y]) starts from the state now. */
    fun ridesNow(x: Int, y: Int): Boolean = ride(state, x, y) != null
}

/**
 * Press a switch facing [direction] from [to] (the player doesn't move): A on [target] (`sign:N`), part of a route
 * planned by [MechanismPlanner]. The walker turns (checked) and presses A, then checks the puzzle changed.
 */
data class SwitchEdge(
    override val to: Node,
    override val direction: Direction,
    val target: String,
    override val cost: Int = PRESS_COST,
) : Edge {
    override val tiles: List<Node> get() = emptyList()

    companion object {
        /** Turning to a lever and pressing it (its animation) takes a second or two: worth a few steps. */
        const val PRESS_COST = 6
    }
}

/**
 * Plans routes operating [PuzzleMechanics]: the routes' [dijkstra] over (player [Heading], mechanism state), each state
 * getting its own [Pathfinder] where its tiles are walkable ([Overlay.openTiles]). Stepping on a trigger tile is an
 * [Edge.Teleport] (step onto the trigger, the mechanism carries the player to [Edge.Teleport.to]); pressing a switch is
 * a [SwitchEdge]. The walker re-reads the position after each one and plans again when the game disagrees.
 *
 * The rules are the plain routes' ones: the same cost of a turn ([RouteOptions.turnPenalty]; none after a ride, which
 * leaves the player facing wherever it does), the same ledge rule ([boundedLedgeRule], the way back checked with the
 * mechanism where the route leaves it) and the same warnings ([Pathfinder.describe]). A trigger that is the destination
 * is stepped on like a plain tile (the ride that follows ends the walk there, like any destination that moves the
 * player).
 */
class MechanismPlanner<S>(
    private val area: Area,
    private val overlay: Overlay,
    private val mechanics: PuzzleMechanics<S>,
    private val maxStates: Int = DEFAULT_MAX_STATES,
) {

    /** Where the player is (and arrived from, see [Heading]) and the mechanism's state. */
    private data class State<S>(val heading: Heading, val mechanism: S) {
        val node: Node get() = heading.node

        /** The state without the direction: what the search bound counts, and where a turn is cut ([dijkstra]). */
        val place: Pair<Node, S> get() = heading.node to mechanism
    }

    private val pathfinders = HashMap<S, Pathfinder>()

    /** The plain teleports of the overlay, less those the mechanism rides itself (they depend on its state). */
    private val plainTeleports = overlay.teleports.filterNot { (it.fromX to it.fromY) in mechanics.triggerTiles }

    private fun pathfinder(state: S): Pathfinder = pathfinders.getOrPut(state) {
        Pathfinder(area, overlay.copy(teleports = plainTeleports, openTiles = overlay.openTiles + mechanics.walkTiles(state)))
    }

    /**
     * The cheapest route from [start] to a node satisfying [isGoal], operating the mechanism on the way; null when none.
     * Like [Pathfinder.route], active triggers are crossed only when there is no other way (the route then warns).
     */
    fun route(start: Node, options: RouteOptions, goalTiles: Set<Pair<Int, Int>> = emptySet(), isGoal: (Node) -> Boolean): Route? {
        val initial = State(Heading(start, null), mechanics.state)
        fun plan(allowJumps: Boolean, allowTriggers: Boolean): SearchResult<State<S>, *, Edge> = dijkstra(
            start = initial,
            place = { it.place },
            isGoal = { isGoal(it.node) },
            maxPlaces = maxStates,
            turnCost = { pathfinder(it.mechanism).turnCostAt(it.node, options) },
        ) { state, turnCost ->
            moves(state, options, goalTiles, allowJumps, allowTriggers).map { (edge, next) ->
                SearchMove(next, edge.cost + turn(state.heading.direction, edge, turnCost), edge)
            }
        }
        // The way back is walked with the mechanism where the plan leaves it.
        fun ledges(triggers: Boolean): LedgeChoice? =
            boundedLedgeRule(options, { plan(it, triggers) }) { end -> pathfinder(end.mechanism).hasWayBack(end.node, start, options, triggers) }
        // Active triggers only when there is no other way (as in Pathfinder.route).
        return when (val choice = ledges(triggers = false) ?: ledges(triggers = true)) {
            is LedgeChoice.Take -> pathfinder(initial.mechanism).describe(choice.edges, choice.oneWay)
            LedgeChoice.OnlyOneWay, null -> null
        }
    }

    /**
     * The plain moves in the mechanism's state; a step onto a trigger tile becomes the ride it starts, and the switches
     * pressable from this tile are moves too.
     */
    private fun moves(state: State<S>, options: RouteOptions, goalTiles: Set<Pair<Int, Int>>, allowJumps: Boolean, allowTriggers: Boolean): List<Pair<Edge, State<S>>> {
        val node = state.node
        val walks = pathfinder(state.mechanism).neighbours(node, options, goalTiles, allowJumps = allowJumps, allowTriggers = allowTriggers).map { edge ->
            val ride = if (edge is Edge.Step && (edge.to.x to edge.to.y) !in goalTiles) mechanics.ride(state.mechanism, edge.to.x, edge.to.y) else null
            when {
                ride == null || !ride.moved -> edge to State(Heading(edge.to, edge.endDirection), state.mechanism)
                else -> {
                    val landing = Node(ride.playerX, ride.playerY, edge.to.level)
                    val teleport = Edge.Teleport(landing, edge.direction, edge.to, RIDE_COST)
                    teleport to State(Heading(landing, teleport.endDirection), ride.state)
                }
            }
        }
        val presses = mechanics.presses(state.mechanism).filter { it.x == node.x && it.y == node.y }.map { press ->
            val edge = SwitchEdge(node, press.facing, press.target)
            edge to State(Heading(node, press.facing), press.state)
        }
        return walks + presses
    }

    companion object {
        /** States expanded at most (the Blackthorn Gym needs a few thousand). */
        const val DEFAULT_MAX_STATES = 200_000

        /** A ride (a platform turning or sliding with the player on it, a cart crossing the gym) takes a couple of seconds. */
        const val RIDE_COST = 8
    }
}
