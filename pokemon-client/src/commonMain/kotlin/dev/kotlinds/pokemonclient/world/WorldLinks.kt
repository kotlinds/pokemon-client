package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction

/**
 * A way from a tile of [zone] to another zone: a door, stairs or ladder ([Kind.WARP]), or a hole the player falls
 * through ([Kind.HOLE], one way). [id] is the target id actions take (`warp:N`, `hole:N`).
 */
data class ZoneLink(
    val kind: Kind,
    val id: String,
    val zone: Int,
    val x: Int,
    val y: Int,
    /** What takes it ([WarpTrigger]: entering the tile, a press on it, nothing); a hole is entered. */
    val trigger: WarpTrigger,
    val targetZone: Int,
    /** Where the player arrives (the destination warp's tile, the hole's landing tile), when known. */
    val toX: Int?,
    val toY: Int?,
) {
    enum class Kind { WARP, HOLE }

    /** True when there is no way back through it (holes). */
    val oneWay: Boolean get() = kind == Kind.HOLE
}

/**
 * An edge of [zone] where the player walks into the neighbouring zone [toZone] of the same area (the Gen 4
 * overworld: routes and towns side by side). [tiles] are the tiles of [zone] from which one step [direction] enters
 * [toZone]; [byWater] when some of them are water (Surf).
 */
data class MapConnection(
    val zone: Int,
    val direction: Direction,
    val toZone: Int,
    val tiles: List<Pair<Int, Int>>,
    val byWater: Boolean,
) {
    /** The target id actions take: `exit:east`. */
    val id: String get() = "exit:${direction.name.lowercase()}"
}

/** The ways out of a zone, computed from the static [Area]s of a [WorldSource]. */
object WorldLinks {

    /** Warps of [zone] (with their arrival tile) and its holes. */
    fun links(world: WorldSource?, area: Area, zone: Int): List<ZoneLink> {
        val warps = area.warps.filter { it.zone == zone }.map { w ->
            val arrival = world?.areaOf(w.targetZone)?.warps?.firstOrNull { it.zone == w.targetZone && it.id == w.targetWarp }
            ZoneLink(ZoneLink.Kind.WARP, "warp:${w.id}", zone, w.x, w.y, w.trigger, w.targetZone, arrival?.x, arrival?.y)
        }
        val holes = area.triggerWarps.filter { it.zone == zone }.map { h ->
            ZoneLink(ZoneLink.Kind.HOLE, "hole:${h.trigger}", zone, h.x, h.y, WarpTrigger.Enter, h.targetZone, h.toX, h.toY)
        }
        return warps + holes
    }

    /**
     * Warps of [zone] leading to [zone] itself (the Saffron Gym's warp pads, Diglett's Cave's ladders between the two
     * halves of its map): stepping on one moves the player elsewhere on the same map, so routes take them as
     * [TeleportLink]s (to the destination warp's tile) rather than as ways out. Only warps taken by entering their
     * tile ([WarpTrigger.Enter]): a teleport fires on entering the tile.
     */
    fun sameZoneTeleports(area: Area, zone: Int): List<TeleportLink> = sameZoneWarps(area, zone).mapNotNull { w ->
        if (w.trigger != WarpTrigger.Enter) return@mapNotNull null
        val arrival = area.warps.firstOrNull { it.zone == zone && it.id == w.targetWarp } ?: return@mapNotNull null
        TeleportLink(w.x, w.y, arrival.x, arrival.y)
    }

    /**
     * True when taking [link] leaves no way back to [link]'s map from where the player arrives (`one_way` of the
     * agent's exits; holes always). The arrival is the destination warp's tile ([ZoneLink.toX], [ZoneLink.toY]):
     * - the warp there takes the player back when it leads to [link]'s map (a dynamic one, an elevator's, too) and can
     *   be used, as its [Warp.trigger] says (the one classification of how warps fire, [WarpTrigger]): a
     *   [WarpTrigger.Press] (a mat, side stairs, a ladder) with a press while standing on it; a [WarpTrigger.Enter] with
     *   collision (a door: the game walks the player out of it on arrival) by facing it, one without (a gatehouse
     *   entrance taken going north, a warp panel, a ladder down) by stepping off it and on again
     *   ([Pathfinder.hasWayBack] from each tile next to it); never when someone stands on it, nor a
     *   [WarpTrigger.Never] (its tile has no warp behaviour: it only receives);
     * - else another warp of that map leading back, reached by walking from the arrival ([Pathfinder.reaches], at
     *   most [BACK_SEARCH] places: beyond, not known, so not called one way): the forest exit doors just above the
     *   floor tile where Viridian Forest is entered.
     * People of the destination stand where its events place them when [present] says they are there: the man waiting
     * for the Power Plant on the only free side of Route 5's underground gate entrance, the Radio Tower 2F guard on the
     * stairs reached from Route 12, an item ball on the Goldenrod Tunnel B1F arrival reached from Route 36
     * (NOTES-run-map-randomizer). Unknown arrivals (no destination warp) aren't called one way. [options]: how the
     * player can move (field moves).
     */
    fun noWayBack(world: WorldSource, link: ZoneLink, options: RouteOptions, present: (PersonTemplate) -> Boolean): Boolean {
        if (link.oneWay) return true
        val toX = link.toX ?: return false
        val toY = link.toY ?: return false
        val area = world.areaOf(link.targetZone) ?: return false
        val arrival = area.warps.firstOrNull { it.zone == link.targetZone && it.x == toX && it.y == toY } ?: return false
        val people = area.people.filter { it.zone == link.targetZone && it.obstacle == null && present(it) }
        val pathfinder = Pathfinder(area, WorldRouter.staticOverlay(area).let { o -> o.copy(objects = o.objects + people.map { LiveObject(it.x, it.y, it.facing) }) })
        val at = Node(toX, toY)
        // A destination unknown to the map data (an elevator's dynamic warp) leads wherever the player came from.
        fun leadsBack(w: Warp) = w.targetZone == link.zone || world.areaOf(w.targetZone) == null
        if (leadsBack(arrival) && arrival.trigger != WarpTrigger.Never && people.none { it.x == toX && it.y == toY }) {
            // Taken again without leaving the tile: a press on it, or a door (collision) faced from where the game
            // walked the player out to.
            val again = arrival.trigger is WarpTrigger.Press || area.tile(toX, toY)?.blocked == true
            if (again || pathfinder.neighbours(at, options, setOf(toX to toY)).any { step -> pathfinder.hasWayBack(step.to, at, options) }) return false
        }
        val others = area.warps.filter { it.zone == link.targetZone && it !== arrival && leadsBack(it) && it.trigger != WarpTrigger.Never }.map { it.x to it.y }.toSet()
        if (others.isEmpty()) return true
        return pathfinder.reaches(at, options, others, BACK_SEARCH) == false
    }

    /** The places [noWayBack] explores at most looking for another warp back (a building, a dungeon floor). */
    const val BACK_SEARCH = 20_000

    /**
     * The warps of [zone] that are one door with others ([Warp.id] → the other warps' ids, in order): side by side
     * (orthogonal neighbours, chained) and leading to the same place (the same destination warp, or destination warps
     * side by side themselves, [world] telling where they are). A double door is two warps in the map data, but one
     * doorway in the game (a map randomizer shuffles them together): an explorer tests it once. Ids stay one per warp.
     */
    fun sameDoors(world: WorldSource?, area: Area, zone: Int): Map<Int, List<Int>> {
        val warps = area.warps.filter { it.zone == zone }
        fun arrival(w: Warp) = world?.areaOf(w.targetZone)?.warps?.firstOrNull { it.zone == w.targetZone && it.id == w.targetWarp }
        fun together(a: Warp, b: Warp): Boolean {
            if (kotlin.math.abs(a.x - b.x) + kotlin.math.abs(a.y - b.y) != 1 || a.targetZone != b.targetZone) return false
            if (a.targetWarp == b.targetWarp) return true
            val ta = arrival(a) ?: return false
            val tb = arrival(b) ?: return false
            return kotlin.math.abs(ta.x - tb.x) + kotlin.math.abs(ta.y - tb.y) == 1
        }
        val groups = HashMap<Int, List<Int>>()
        for (w in warps) {
            if (w.id in groups) continue
            val group = mutableListOf(w)
            var i = 0
            while (i < group.size) {
                val at = group[i++]
                warps.filter { o -> group.none { it.id == o.id } && together(at, o) }.forEach { group += it }
            }
            if (group.size < 2) continue
            val ids = group.map { it.id }.sorted()
            ids.forEach { id -> groups[id] = ids - id }
        }
        return groups
    }

    /** Warps of [zone] whose destination is [zone] itself (see [sameZoneTeleports]). */
    fun sameZoneWarps(area: Area, zone: Int): List<Warp> = area.warps.filter { it.zone == zone && it.targetZone == zone }

    /**
     * The edges of [zone] towards the other zones of [area], grouped by direction and neighbour. Only edges the
     * player can cross: both tiles free (water counts, with [MapConnection.byWater]).
     */
    fun connections(area: Area, zone: Int): List<MapConnection> {
        val bounds = area.zoneBounds[zone] ?: return emptyList()
        val found = LinkedHashMap<Pair<Direction, Int>, MutableList<Pair<Int, Int>>>()
        val water = HashSet<Pair<Direction, Int>>()
        for (y in bounds[1]..bounds[3]) for (x in bounds[0]..bounds[2]) {
            if (area.zoneAt(x, y) != zone) continue
            val here = area.tile(x, y) ?: continue
            if (!crossable(here)) continue
            for (d in Direction.entries) {
                val other = area.zoneAt(x + d.dx, y + d.dy) ?: continue
                if (other == zone) continue
                val next = area.tile(x + d.dx, y + d.dy) ?: continue
                if (!crossable(next)) continue
                found.getOrPut(d to other) { mutableListOf() } += x to y
                if (isWater(here) || isWater(next)) water += d to other
            }
        }
        return found.map { (k, tiles) -> MapConnection(zone, k.first, k.second, tiles, k in water) }
    }

    private fun crossable(tile: TileInfo): Boolean = when (tile.kind) {
        TileKind.Wall, TileKind.Lava, TileKind.Pc, TileKind.Counter -> false
        is TileKind.Water, is TileKind.Bridge, is TileKind.Ledge -> true
        else -> !tile.blocked
    }

    private fun isWater(tile: TileInfo) = tile.kind is TileKind.Water || (tile.kind as? TileKind.Bridge)?.overWater == true
}
