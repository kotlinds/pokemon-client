package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.EventFlags

/**
 * Routes across zones: floors of a dungeon, buildings and the outdoor area, linked by warps and holes
 * ([WorldLinks]). One Dijkstra over (area, [Node]) pairs: inside an area the moves are the [Pathfinder]'s; stepping
 * onto a warp (or pressing its direction on an exit mat, [WarpTrigger]) or onto a hole jumps to the arrival tile of the other zone.
 * Changes of direction cost [RouteOptions.turnPenalty] like in the [Pathfinder] (the search state also holds the
 * direction the player arrived in, with the same cut of the headings that can't be cheaper), and the moves their soft
 * costs ([RouteOptions.weights]: wild encounters by zone, trainers' sight where the overlay shows trainers; a Repel
 * wearing off on the way counted step by step, [repelDijkstra]).
 *
 * The live state is only known for the player's zone ([overlayFor] gives the people, refused steps and active
 * triggers there); other zones use the static maps with their obstacle objects where the map places them
 * ([staticOverlay]: boulders and rocks are back in place whenever the player enters a map). The walker follows the first link of the route, then plans again
 * from where it really arrived, so the static guess for the other zones is corrected on the way.
 */
class WorldRouter(
    private val world: WorldSource,
    /** The live overlay of a zone (people, triggers...), [Overlay] with nothing for the zones not shown. */
    private val overlayFor: (zone: Int, area: Area) -> Overlay = { _, area -> staticOverlay(area) },
) {
    /** A position in the world: an area (identity) and a node on it. */
    data class Place(val area: Area, val node: Node) {
        val zone: Int? get() = area.zoneAt(node.x, node.y)
    }

    /** A route found: the links taken in order (empty when the goal is on the start area), and its cost. */
    data class WorldRoute(val links: List<ZoneLink>, val end: Place, val cost: Int, val places: List<Place> = emptyList()) {
        /** True when it takes a hole: no way back the same way. */
        val oneWay: Boolean get() = links.any { it.oneWay }

        /** How many moves it takes (tiles walked, a link taken counting as one), or null when [places] isn't known. */
        val tiles: Int? get() = if (places.isEmpty()) null else places.size - 1
    }

    /** An area as the search sees it: its [pathfinder], its [links] by tile (a lift's way out leads to several floors), its goal tiles. */
    private class AreaInfo(val pathfinder: Pathfinder, val links: Map<Pair<Int, Int>, List<ZoneLink>>, val goalTiles: Set<Pair<Int, Int>>)

    /**
     * The cheapest route from [start] (in zone [startZone]) to a place where [isGoal] holds. [goalTiles] lists the
     * tiles of an area that may be entered as destinations (warps, triggers, blocked targets): they are entered only
     * as goals. Ledges are jumped only with [RouteOptions.acceptOneWay]; holes always (they are one way: see
     * [WorldRoute.oneWay]). Null when no route exists within [maxNodes] explored nodes. With [relaxed] and
     * [ignorePeople], field-move obstacles and people are crossed (at a high cost), to tell what blocks a route.
     */
    fun route(
        startZone: Int,
        start: Node,
        options: RouteOptions,
        goalTiles: (Area) -> Set<Pair<Int, Int>> = { emptySet() },
        maxNodes: Int = MAX_NODES,
        relaxed: Boolean = false,
        ignorePeople: Boolean = false,
        isGoal: (Place) -> Boolean,
    ): WorldRoute? {
        val startArea = world.areaOf(startZone) ?: return null
        val infos = HashMap<Area, AreaInfo>()
        fun info(area: Area, zone: Int): AreaInfo = infos.getOrPut(area) {
            val zones = if (area.zoneBounds.isEmpty()) setOf(zone) else area.zoneBounds.keys
            // Pads to the same map are teleports of the area's pathfinder (see [staticOverlay]), not links.
            val pads = zones.flatMap { z -> WorldLinks.sameZoneTeleports(area, z).map { it.fromX to it.fromY } }.toSet()
            // The first warp of a tile is the one the game takes (later warps on it are never taken: [WarpTrigger.Never]);
            // a lift's way out keeps one link per floor it goes to ([WorldLinks.elevatorLinks], all of that same warp).
            val links = zones.flatMap { WorldLinks.links(world, area, it) + WorldLinks.elevatorLinks(world, area, it) }
                .filterNot { (it.x to it.y) in pads }.groupBy { it.x to it.y }
                .mapValues { (_, tile) -> tile.filter { it.id == tile.first().id } }
            AreaInfo(Pathfinder(area, overlayFor(zone, area)), links, goalTiles(area))
        }
        // Soft costs (and the Repel's steps) are left out while looking for what blocks a route.
        val soft = !relaxed && !ignorePeople
        val result = repelDijkstra(
            start = State(Place(startArea, start), null),
            options = if (soft) options else options.withoutRepelWear(),
            // The same node in two areas is two places; the bound counts places, not headings: the same reach as a
            // search without turns.
            place = { it.place },
            isGoal = { isGoal(it.place) },
            maxPlaces = maxNodes,
            // A turn costs more where wild Pokémon appear (see Pathfinder.turnCostAt).
            turnCost = { state, now -> info(state.place.area, state.place.zone ?: startZone).pathfinder.turnCostAt(state.place.node, now, soft) },
        ) { state, now, turnCost ->
            val place = state.place
            val here = info(place.area, place.zone ?: startZone)
            buildList {
                fun relax(next: Place, cost: Int, via: ZoneLink?, direction: Direction?, gameSteps: Int) = add(SearchMove(State(next, direction), cost, via, gameSteps))
                // A warp or a hole is taken before the game counts the step onto it ([Edge.gameSteps]): none counted.
                fun take(link: ZoneLink, cost: Int) {
                    val toX = link.toX ?: return
                    val toY = link.toY ?: return
                    val area = world.areaOf(link.targetZone) ?: return
                    if (area.tile(toX, toY) == null) return
                    // Where the player really stands once arrived: off the ladder when the game moves them so (the
                    // Bell Tower's 3F / 4F ladder: planned from the hole's tile, the way back down looked like a short cut).
                    val step = link.arrivalStep?.takeIf { d -> area.tile(toX + d.dx, toY + d.dy)?.blocked == false }
                    val at = if (step == null) Node(toX, toY) else Node(toX + step.dx, toY + step.dy)
                    // Arrived through a warp or a fall: facing whichever way the game leaves the player, no turn counted.
                    relax(Place(area, at), cost + LINK_COST, link, null, gameSteps = 0)
                }
                // Pressing the direction of the exit mat the player stands on.
                here.links[place.node.x to place.node.y].orEmpty().filter { it.trigger is WarpTrigger.Press }.forEach { take(it, 0) }
                val enterable = here.goalTiles + here.links.keys
                for (edge in here.pathfinder.neighbours(place.node, now, enterable, allowJumps = options.acceptOneWay, relaxed = relaxed, ignorePeople = ignorePeople)) {
                    val to = edge.to
                    val next = Place(place.area, to)
                    val links = here.links[to.x to to.y].orEmpty()
                    val cost = edge.cost + turn(state.direction, edge, turnCost)
                    when {
                        links.isEmpty() -> if ((to.x to to.y) !in here.goalTiles || isGoal(next)) relax(next, cost, null, edge.endDirection, edge.gameSteps)
                        // Stepping on a door, a ladder down or a hole takes it at once (unless it's the destination), at
                        // the warp's own level ([WorldLinks.warpLevel]: a bridge over a door doesn't take it).
                        links.first().trigger == WarpTrigger.Enter -> if (isGoal(next) || !atWarpLevel(place.area, to)) relax(next, cost, null, edge.endDirection, edge.gameSteps) else links.forEach { take(it, cost) }
                        // An exit mat is floor until its direction is pressed (the Pathfinder never leaves it that way);
                        // a warp nothing takes is floor.
                        else -> relax(next, cost, null, edge.endDirection, edge.gameSteps)
                    }
                }
            }
        }
        val path = result.found ?: return null
        return WorldRoute(path.labels.filterNotNull(), path.end.place, path.cost, path.states.map { it.place })
    }

    /** True when [node] (a warp's tile of [area]) is at the level its warp is taken at ([WorldLinks.warpLevel]). */
    private fun atWarpLevel(area: Area, node: Node): Boolean = WorldLinks.warpLevel(area, node.x, node.y)?.let { it == node.level } ?: true

    /** A search state: a place, and the direction the player arrived in (null when unknown), see [Heading]. */
    private data class State(val place: Place, val direction: Direction?)

    companion object {
        /**
         * What is known of [area] without seeing it: its obstacles (boulders, rocks, trees) where its events place
         * them, and its warp pads ([Area.scriptWarps]: stepping on the trigger takes the player to the pad's
         * destination, assumed active; warps to the same map, [WorldLinks.sameZoneTeleports]).
         */
        fun staticOverlay(area: Area): Overlay = Overlay(
            objects = area.people.filter { it.obstacle != null }.map { LiveObject(it.x, it.y, it.facing, clearedBy = it.obstacle) },
            teleports = area.scriptWarps.flatMap { pad ->
                val trigger = area.triggers.firstOrNull { it.zone == pad.zone && it.id == pad.trigger } ?: return@flatMap emptyList()
                trigger.tiles.map { (x, y) -> TeleportLink(x, y, pad.x, pad.y) }
            } + sameZoneTeleports(area),
        )

        /**
         * What is known of [area] from its map data and the save, without seeing it: the [staticOverlay] (obstacles,
         * pads) and the people its events place there now ([knownPeople]). For the zones the player isn't on: a person
         * or an item ball standing in a corridor of another floor closes it in the plan too, so a trip never walks
         * there to find it closed and turn back (NOTES race: Bell Tower 4F's item ball at 22,18, a route planned
         * through it from 3F went up and down the ladder until the walk gave up), and a trainer of another map is
         * avoided like one of this map.
         */
        fun knownOverlay(area: Area, flags: EventFlags?): Overlay =
            staticOverlay(area).let { o -> o.copy(objects = o.objects + knownPeople(area, flags)) }

        /**
         * The people of [area] (of the zones [zones] keeps) standing where its events place them, as the save's event
         * [flags] say they are there now ([PersonTemplate.presentWith]; unknown flags: absent), trainers watching as far
         * as [PersonTemplate.sightWith] says. Obstacles are the [staticOverlay]'s; people who walk around
         * ([PersonTemplate.wanders]) or whom a script of their map moves ([PersonTemplate.scriptMoved]: the Cinnabar Gym's
         * beaten trainers, put out of the corridors on every entry) are left out: where the map places them isn't where
         * they stand.
         */
        fun knownPeople(area: Area, flags: EventFlags?, zones: (Int) -> Boolean = { true }): List<LiveObject> =
            area.people.filter { it.obstacle == null && !it.wanders && !it.scriptMoved && zones(it.zone) && it.presentWith(flags) }
                .map { LiveObject(it.x, it.y, it.facing, sightRange = it.sightWith(flags), looks = it.looks) }

        /** The warps of every zone of [area] that lead to the same zone ([WorldLinks.sameZoneTeleports]). */
        fun sameZoneTeleports(area: Area): List<TeleportLink> =
            (if (area.zoneBounds.isEmpty()) area.warps.map { it.zone }.distinct() else area.zoneBounds.keys.toList())
                .flatMap { WorldLinks.sameZoneTeleports(area, it) }

        /** A warp or a fall takes a few seconds (fade): worth about this many steps. */
        const val LINK_COST = 10

        /** Bound of the search (the outdoor area is large: about a million tiles). */
        const val MAX_NODES = 400_000
    }
}
