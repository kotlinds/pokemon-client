package dev.kotlinds.pokemonclient.world

/** Where one moving platform is: its pivot tile and how many quarter turns (clockwise) it has made from its rest pose. */
data class PlatformPose(val x: Int, val y: Int, val rotation: Int)

/**
 * What stepping on a platform's trigger tile does: the platforms afterwards ([poses]) and where the player stands
 * then ([playerX], [playerY]: the platform carries the player). [moved] is false when the platform was blocked (by a
 * wall, the floor, another platform) and stayed where it was.
 */
data class PlatformRide(val poses: List<PlatformPose>, val playerX: Int, val playerY: Int, val moved: Boolean)

/**
 * Platforms that the player rides and moves by stepping on their trigger tiles (the Blackthorn Gym's platforms on the
 * lava). The rules are the game's ([dev.kotlinds.pokemonclient.games.hgss.HgssBlackthornGym] for HeartGold / SoulSilver);
 * [PlatformPlanner] searches routes with them.
 */
interface MovingPlatforms {
    /** The platforms now, read from the game. */
    val poses: List<PlatformPose>

    /** Tiles the player can stand on with the platforms at [poses] (on top of the lava). */
    fun walkTiles(poses: List<PlatformPose>): Set<Pair<Int, Int>>

    /** Stepping on ([x], [y]) with the platforms at [poses]: the ride, or null when that tile isn't a trigger. */
    fun ride(poses: List<PlatformPose>, x: Int, y: Int): PlatformRide?
}

/**
 * Plans routes over [MovingPlatforms]: the routes' [dijkstra] over (player [Heading], platform poses), each pose
 * configuration getting its own [Pathfinder] where the platforms' tiles are walkable ([Overlay.openTiles]). Stepping on
 * a trigger tile is an [Edge.Teleport] (step onto the trigger, the platform moves and carries the player to
 * [Edge.Teleport.to]); the walker re-reads the position after each one and plans again when the game disagrees.
 *
 * The rules are the plain routes' ones: the same cost of a turn ([RouteOptions.turnPenalty]; none after a ride, which
 * leaves the player facing wherever it does), the same ledge rule ([boundedLedgeRule], the way back checked with the
 * platforms where the route leaves them) and the same warnings ([Pathfinder.describe]).
 */
class PlatformPlanner(
    private val area: Area,
    private val overlay: Overlay,
    private val platforms: MovingPlatforms,
    private val maxStates: Int = DEFAULT_MAX_STATES,
) {

    /** Where the player is (and arrived from, see [Heading]) and where the platforms are. */
    private data class State(val heading: Heading, val poses: List<PlatformPose>) {
        val node: Node get() = heading.node

        /** The state without the direction: what the search bound counts, and where a turn is cut ([dijkstra]). */
        val place: Pair<Node, List<PlatformPose>> get() = heading.node to poses
    }

    private val pathfinders = HashMap<List<PlatformPose>, Pathfinder>()

    private fun pathfinder(poses: List<PlatformPose>): Pathfinder = pathfinders.getOrPut(poses) {
        Pathfinder(area, overlay.copy(openTiles = overlay.openTiles + platforms.walkTiles(poses)))
    }

    /**
     * The cheapest route from [start] to a node satisfying [isGoal], riding platforms on the way; null when none. Like
     * [Pathfinder.route], active triggers are crossed only when there is no other way (the route then warns).
     */
    fun route(start: Node, options: RouteOptions, goalTiles: Set<Pair<Int, Int>> = emptySet(), isGoal: (Node) -> Boolean): Route? {
        val initial = State(Heading(start, null), platforms.poses)
        fun plan(allowJumps: Boolean, allowTriggers: Boolean): SearchResult<State, *, Edge> = dijkstra(
            start = initial,
            place = { it.place },
            isGoal = { isGoal(it.node) },
            maxPlaces = maxStates,
            turnCost = { pathfinder(it.poses).turnCostAt(it.node, options) },
        ) { state, turnCost ->
            moves(state, options, goalTiles, allowJumps, allowTriggers).map { (edge, next) ->
                SearchMove(next, edge.cost + turn(state.heading.direction, edge, turnCost), edge)
            }
        }
        // The way back is walked with the platforms where the plan leaves them.
        fun ledges(triggers: Boolean): LedgeChoice? =
            boundedLedgeRule(options, { plan(it, triggers) }) { end -> pathfinder(end.poses).hasWayBack(end.node, start, options, triggers) }
        // Active triggers only when there is no other way (as in Pathfinder.route).
        return when (val choice = ledges(triggers = false) ?: ledges(triggers = true)) {
            is LedgeChoice.Take -> pathfinder(initial.poses).describe(choice.edges, choice.oneWay)
            LedgeChoice.OnlyOneWay, null -> null
        }
    }

    /** The plain moves with the platforms where they are; a step onto a trigger tile becomes the ride it starts. */
    private fun moves(state: State, options: RouteOptions, goalTiles: Set<Pair<Int, Int>>, allowJumps: Boolean, allowTriggers: Boolean): List<Pair<Edge, State>> =
        pathfinder(state.poses).neighbours(state.node, options, goalTiles, allowJumps = allowJumps, allowTriggers = allowTriggers).map { edge ->
            val ride = if (edge is Edge.Step) platforms.ride(state.poses, edge.to.x, edge.to.y) else null
            when {
                ride == null || !ride.moved -> edge to State(Heading(edge.to, edge.endDirection), state.poses)
                else -> {
                    val landing = Node(ride.playerX, ride.playerY, edge.to.level)
                    val teleport = Edge.Teleport(landing, edge.direction, edge.to, RIDE_COST)
                    teleport to State(Heading(landing, teleport.endDirection), ride.poses)
                }
            }
        }

    companion object {
        /** States expanded at most (the Blackthorn Gym needs a few thousand). */
        const val DEFAULT_MAX_STATES = 200_000

        /** A ride (the platform turning or sliding with the player on it) takes a couple of seconds. */
        const val RIDE_COST = 8
    }
}
