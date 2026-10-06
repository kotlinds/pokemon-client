package dev.kotlinds.pokemonclient.view

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.actions.Reachability
import dev.kotlinds.pokemonclient.console.Platform
import dev.kotlinds.pokemonclient.state.ExaminableKind
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.TeleportKind
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.FIELD_HEIGHT_UNITS
import dev.kotlinds.pokemonclient.world.MapConnection
import dev.kotlinds.pokemonclient.world.SignKind
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.WarpTrigger
import dev.kotlinds.pokemonclient.world.WorldLinks
import dev.kotlinds.pokemonclient.world.WorldSource
import dev.kotlinds.pokemonclient.world.ZoneLink
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.kotlinds.pokemonclient.state.MapName

/**
 * The text map of the player's surroundings, the same for every game: built from the ROM's [Area] (tiles, warps,
 * signs) and the live [FieldState] (position, people). Everything listed has the id actions take (`warp:N`, `hole:N`,
 * `exit:<direction>`, `person:N`, `item:N`, `sign:N`, `hidden_item:N`, `examine:N`) and both absolute coordinates (for `go_to`) and
 * relative ones ("3 west, 2 north"). Exits name the destination map and the arrival tile, when [world] is given,
 * unless destinations are hidden (then every exit leads to [UNKNOWN_DESTINATION]).
 */
object MapView {

    /**
     * Renders the [width] × [height] tiles around the player (the DS screen shows about 15 × 11). [mapName] names the
     * map a way out leads to (the game's [dev.kotlinds.pokemonclient.PokemonGame.mapName]; `map:<id>` without one);
     * [world] gives the arrival tiles of warps (and the other zones' maps).
     * [showHidden]: show the hidden items and the invisible examinables without a cue (never seen by the player:
     * walkthrough knowledge, see [Sightings]); without it, an examinable with a cue is only "something to examine".
     * [hideDestinations] (`ActionSettings.hideDestinations`): warps, holes and map-edge exits are listed with their
     * place on this map but lead to [UNKNOWN_DESTINATION]: neither the map nor the arrival tile (the names of the
     * neighbouring maps would give the geography away too). Everything of the current map stays shown.
     * [reach]: what reaching each listed target (by id) needs ([dev.kotlinds.pokemonclient.actions.ReachSurvey]),
     * appended to its line only when it needs something ([Reachability.suffix]).
     */
    fun render(
        area: Area,
        field: FieldState,
        mapName: (Int) -> MapName = ::MapName,
        width: Int = VIEW_WIDTH,
        height: Int = VIEW_HEIGHT,
        world: WorldSource? = null,
        showHidden: Boolean = true,
        hideDestinations: Boolean = false,
        reach: (id: String) -> Reachability = { Reachability.DIRECT },
    ): JsonObject {
        // The fields told on the lines, explained once below them (only those used).
        val told = linkedSetOf<String>()
        fun said(r: Reachability): String = r.suffix().also { r.fields().forEach { told += it.substringBefore(':') } }
        // Where an exit leads, as the agent may read it: unknown while destinations are hidden.
        fun destination(zone: Int): String = if (hideDestinations) UNKNOWN_DESTINATION else mapName(zone).toString()
        val left = field.x - width / 2
        val top = field.y - height / 2
        val warps = area.warps.filter { it.zone == field.mapId || area.zoneAt(it.x, it.y) == field.mapId }
        val links = WorldLinks.links(world, area, field.mapId)
        val arrivals = links.associate { it.id to it }
        val holes = links.filter { it.kind == ZoneLink.Kind.HOLE }
        val connections = WorldLinks.connections(area, field.mapId)
        val signs = area.signs.filter { it.zone == field.mapId && it.kind == SignKind.SIGN }
        val hiddenItems = if (!showHidden) emptyList()
        else area.signs.filter { it.zone == field.mapId && it.kind == SignKind.HIDDEN_ITEM && "hidden_item:${it.id}" !in field.pickedUp }
        // Invisible things that answer A: like hidden items, known only with a walkthrough, unless the game shows a cue.
        val examinables = field.examinables.filter { showHidden || it.cue }
        fun shown(x: Int, y: Int) = x in left until left + width && y in top until top + height
        val objects = field.objects.filter { shown(it.x, it.y) }
        // The live puzzle: closed gates / shutters, and the tiles that start a teleport, a ride or a lift.
        val closed = field.puzzle?.barriers.orEmpty().filterNot { it.open }.flatMap { b -> b.tiles.map { it.x to it.y } }.toSet()
        val teleports = field.puzzle?.teleports.orEmpty().flatMap { t -> t.from.map { (it.x to it.y) to (if (t.kind == TeleportKind.LIFT) 'L' else 'W') } }.toMap()
        val platformTiles = field.puzzle?.platforms.orEmpty().flatMap { p -> p.tiles.map { it.x to it.y } }.toSet()
        val platformTriggers = field.puzzle?.platforms.orEmpty().flatMap { p -> p.triggers.map { it.tile.x to it.tile.y } }.toSet()
        val void = voidOf(area, field)
        val used = linkedSetOf<Char>()
        val rows = (top until top + height).map { y ->
            val cells = (left until left + width).joinToString(" ") { x ->
                val c = when {
                    x == field.x && y == field.y -> player(field.facing)
                    else -> objects.firstOrNull { it.x == x && it.y == y }?.let(::objectSymbol)
                        ?: warps.firstOrNull { it.x == x && it.y == y }?.let { 'E' }
                        ?: teleports[x to y]
                        ?: 'G'.takeIf { (x to y) in closed }
                        ?: holes.firstOrNull { it.x == x && it.y == y }?.let { 'O' }
                        ?: (x to y).takeIf { it in platformTriggers }?.let { 'X' }
                        ?: (x to y).takeIf { it in platformTiles }?.let { '&' }
                        ?: (x to y).takeIf { it in field.activeTriggers }?.let { 'x' }
                        ?: signs.firstOrNull { it.x == x && it.y == y }?.let { 'S' }
                        ?: hiddenItems.firstOrNull { it.x == x && it.y == y }?.let { '$' }
                        ?: examinables.firstOrNull { it.x == x && it.y == y }?.let { 'e' }
                        ?: tileSymbol(area, void, x, y)
                        ?: ' '
                }
                used += c
                c.toString()
            }
            "${y.toString().padStart(4)} $cells"
        }
        return buildJsonObject {
            put("map", JsonArray(listOf("     " + (left until left + width).joinToString(" ") { (it % 10).toString() }).plus(rows).map(::JsonPrimitive)))
            put("map_origin", "x $left..${left + width - 1}, y $top..${top + height - 1} (columns show x mod 10)")
            put("legend", "a partial view: only the $width×$height tiles around you (see map_origin), the map goes on beyond " +
                "(exits and people_off_screen list what is further) · " + used.mapNotNull { c -> LEGEND[c]?.let { "$c $it" } }.joinToString(" · "))
            levels(area, field, left, top, width, height)?.let { (grid, mine) ->
                put("levels", JsonArray(grid.map(::JsonPrimitive)))
                put("levels_legend", "height level of each walkable tile (you are on level $mine): two neighbouring tiles with the same number are walked between (flat floor, slopes, stairs), " +
                    "different numbers never (a cliff, a walkway above the floor or the void below it: the map draws them alike); numbers go up from the lowest (0). b = a bridge (two levels on the tile), blank = not walkable")
            }
            // No position here: the state's `position` ([StateView.state]: map, x, y, facing, movement, height) is the
            // one the agent reads, with the map's name.
            // Double doors: one doorway in the game, two warps in the data; each keeps its id, the line names the others.
            val doors = WorldLinks.sameDoors(world, area, field.mapId)
            val exits = buildList {
                warps.forEach { w ->
                    val arrival = arrivals["warp:${w.id}"]?.takeIf { it.zone == w.zone && !hideDestinations }
                    val to = " → ${destination(w.targetZone)}" + (arrival?.toX?.let { " (${it},${arrival.toY})" } ?: "")
                    val door = doors[w.id]?.takeIf { w.zone == field.mapId }?.let { others ->
                        " (${if (others.size == 1) "double" else "multiple"} door with ${others.joinToString(" and ") { "warp:$it" }}: the same doorway, test it once)"
                    } ?: ""
                    add(distance(field, w.x, w.y) to "warp:${w.id} at ${w.x},${w.y} (${relative(field, w.x, w.y)})$to$door" +
                        when (val trigger = w.trigger) {
                            is WarpTrigger.Press -> " (step on it, then press ${trigger.direction.name.lowercase()})"
                            // Its tile has no warp behaviour: the other side arrives here, nothing takes it from here.
                            WarpTrigger.Never -> " (an arrival point only: it can't be taken from here)"
                            WarpTrigger.Enter -> ""
                        } + said(reach("warp:${w.id}")))
                }
                holes.forEach { h ->
                    val to = if (hideDestinations) UNKNOWN_DESTINATION else "${mapName(h.targetZone)} (${h.toX},${h.toY})"
                    add(distance(field, h.x, h.y) to "${h.id} at ${h.x},${h.y} (${relative(field, h.x, h.y)}) → $to (a hole: you fall through, one way)" +
                        // Its line says it's one way already.
                        said(reach(h.id).copy(oneWay = false)))
                }
                connections.forEach { c ->
                    val (nx, ny) = c.tiles.minBy { (x, y) -> distance(field, x, y) }
                    add(distance(field, nx, ny) to "${c.id} → ${destination(c.toZone)} along ${span(c)} (nearest ${nx},${ny}: ${relative(field, nx, ny)})" +
                        (if (c.byWater) " (partly over water)" else "") + said(reach(c.id)))
                }
            }
            if (exits.isNotEmpty()) put("exits", JsonArray(exits.sortedBy { it.first }.map { JsonPrimitive(it.second) }))
            outsideNote(area, field, void)?.let { put(OUTSIDE, it) }
            val people = objects.filter { it.kind != FieldObjectKind.FOLLOWER && it.kind != FieldObjectKind.ITEM_BALL }
            if (people.isNotEmpty()) put("people", JsonArray(people.sortedBy { distance(field, it.x, it.y) }.map { o ->
                JsonPrimitive("${o.id} ${o.label} at ${o.x},${o.y} (${relative(field, o.x, o.y)})" + (o.facing?.let { " facing ${it.name.lowercase()}" } ?: "") +
                    (o.role?.let { " [${it.name.lowercase()}]" } ?: "") + said(reach(o.id)))
            }))
            // Everyone else on the map (the game tracks every object of the map, not only those on screen): compact.
            val far = field.objects.filter { !shown(it.x, it.y) && it.kind == FieldObjectKind.PERSON }.sortedBy { distance(field, it.x, it.y) }
            if (far.isNotEmpty()) put("people_off_screen", JsonArray(far.take(MAX_FAR_PEOPLE).map { o ->
                JsonPrimitive("${o.id} ${o.label} at ${o.x},${o.y}" + (o.role?.let { " [${it.name.lowercase()}]" } ?: "") + said(reach(o.id)))
            } + listOfNotNull((far.size - MAX_FAR_PEOPLE).takeIf { it > 0 }?.let { JsonPrimitive("… and $it more (go_to or interact them by id)") })))
            val items = objects.filter { it.kind == FieldObjectKind.ITEM_BALL }
            if (items.isNotEmpty()) put("items", JsonArray(items.sortedBy { distance(field, it.x, it.y) }.map { o ->
                JsonPrimitive("item:${o.id.substringAfter(':')} ${o.label} at ${o.x},${o.y} (${relative(field, o.x, o.y)})" + said(reach("item:${o.id.substringAfter(':')}")))
            }))
            val nearSigns = signs.filter { shown(it.x, it.y) }
            if (nearSigns.isNotEmpty()) put("signs", JsonArray(nearSigns.map { s -> JsonPrimitive("sign:${s.id} at ${s.x},${s.y} (${relative(field, s.x, s.y)})" + said(reach("sign:${s.id}"))) }))
            if (hiddenItems.isNotEmpty()) put("hidden_items", JsonArray(hiddenItems.sortedBy { distance(field, it.x, it.y) }.map { s ->
                JsonPrimitive("hidden_item:${s.id} at ${s.x},${s.y} (${relative(field, s.x, s.y)}): face it and press A (interact)" + said(reach("hidden_item:${s.id}")))
            }))
            if (examinables.isNotEmpty()) put("examinables", JsonArray(examinables.sortedBy { distance(field, it.x, it.y) }.map { e ->
                // Without a walkthrough (a cue only), what it is stays unknown.
                val what = if (showHidden) e.label + (if (e.kind == ExaminableKind.ITEM) " (examining picks it up)" else "") else "something to examine"
                JsonPrimitive("${e.id} $what at ${e.x},${e.y} (${relative(field, e.x, e.y)}): nothing is drawn there and it blocks the tile; face it and press A (interact)" + said(reach(e.id)))
            }))
            if (told.isNotEmpty()) put(REACH_LEGEND, "[...] after a target: what reaching it from where you stand needs, on this map only · " +
                told.mapNotNull { REACH_FIELDS[it]?.let { meaning -> "$it: $meaning" } }.joinToString(" · "))
        }
    }

    /** "895,402..405" / "896..899,388": the edge tiles of a connection. */
    private fun span(c: MapConnection): String {
        val xs = c.tiles.map { it.first }
        val ys = c.tiles.map { it.second }
        fun range(v: List<Int>) = if (v.min() == v.max()) "${v.min()}" else "${v.min()}..${v.max()}"
        return "${range(xs)},${range(ys)}"
    }

    /**
     * The height levels of the window's walkable tiles, when it shows more than one: the grid rows (same layout as the
     * map) and the player's level. Null on a view of one level (flat, or whose heights only change along slopes and
     * stairs, or several rooms at about the same height).
     *
     * A level is a set of surfaces walked between: neighbouring surfaces whose heights differ by at most a step
     * ([dev.kotlinds.pokemonclient.world.RouteOptions.DEFAULT_MAX_CLIMB], the pathfinder's rule) belong to the same
     * level, whatever their height (a walkway sloping up from the floor stays one level all along). They are found over
     * the window and a [LEVEL_MARGIN] around it (so that two ends of a walkway joined just off screen are one level),
     * then numbered from the lowest (by mean height): a level shares its number only with levels it doesn't touch at
     * about its height (one floor split by walls), so two neighbouring tiles show different numbers exactly when the
     * game refuses the step between them (a cliff, the void under a walkway, a walkway above the floor), and a raised
     * walkway behind a wall shows apart from the floor. A tile with surfaces on two levels (a bridge) shows `b`.
     */
    private fun levels(area: Area, field: FieldState, left: Int, top: Int, width: Int, height: Int): Pair<List<String>, Int>? {
        fun surfaces(x: Int, y: Int): List<Int> = area.tile(x, y)?.takeIf { !it.blocked && it.kind != TileKind.Wall }?.heights.orEmpty()
        val climb = dev.kotlinds.pokemonclient.world.RouteOptions.DEFAULT_MAX_CLIMB
        val xs = (left - LEVEL_MARGIN) until (left + width + LEVEL_MARGIN)
        val ys = (top - LEVEL_MARGIN) until (top + height + LEVEL_MARGIN)
        // One surface: a tile and the index of one of its heights.
        data class Surface(val x: Int, val y: Int, val i: Int)
        fun neighbours(s: Surface): List<Surface> {
            val h = surfaces(s.x, s.y)[s.i]
            return Direction.entries.flatMap { d ->
                val nx = s.x + d.dx
                val ny = s.y + d.dy
                if (nx !in xs || ny !in ys) emptyList()
                else surfaces(nx, ny).withIndex().filter { (_, other) -> kotlin.math.abs(other - h) <= climb }.map { Surface(nx, ny, it.index) }
            }
        }
        // Connected surfaces (flood fill), with the mean and the range of the heights of each.
        val component = HashMap<Surface, Int>()
        val mean = mutableListOf<Double>()
        val range = mutableListOf<IntRange>()
        for (y in ys) for (x in xs) for (i in surfaces(x, y).indices) {
            val first = Surface(x, y, i)
            if (first in component) continue
            val id = mean.size
            var sum = 0L
            var count = 0
            var low = Int.MAX_VALUE
            var high = Int.MIN_VALUE
            val stack = ArrayDeque(listOf(first))
            component[first] = id
            while (stack.isNotEmpty()) {
                val s = stack.removeLast()
                val h = surfaces(s.x, s.y)[s.i]
                sum += h
                count++
                low = minOf(low, h)
                high = maxOf(high, h)
                for (n in neighbours(s)) if (component.putIfAbsent(n, id) == null) stack += n
            }
            mean += sum.toDouble() / count
            range += low..high
        }
        // Which components touch (neighbouring tiles of two components: a step the game refuses), in the window only.
        val touching = HashMap<Int, MutableSet<Int>>()
        for (y in top until top + height) for (x in left until left + width) for (i in surfaces(x, y).indices) {
            val a = component.getValue(Surface(x, y, i))
            for (d in Direction.entries) for (j in surfaces(x + d.dx, y + d.dy).indices) {
                val b = component[Surface(x + d.dx, y + d.dy, j)] ?: continue
                if (a != b) {
                    touching.getOrPut(a) { mutableSetOf() } += b
                    touching.getOrPut(b) { mutableSetOf() } += a
                }
            }
        }
        // Numbers from the lowest level up (by mean height: a walkway sloping down to the floor is higher than the void
        // under it): the smallest one shared only with levels that don't touch this one and lie at about its height
        // (the same floor split by walls).
        val number = HashMap<Int, Int>()
        fun sameFloor(a: Int, b: Int) = b !in touching[a].orEmpty() && range[a].first <= range[b].last + climb && range[b].first <= range[a].last + climb
        val shown = (top until top + height).flatMap { y -> (left until left + width).flatMap { x -> surfaces(x, y).indices.map { component.getValue(Surface(x, y, it)) } } }.toSet()
        for (c in shown.sortedBy { mean[it] }) {
            number[c] = generateSequence(0) { it + 1 }.first { n -> number.all { (d, m) -> m != n || sameFloor(c, d) } }
        }
        if (number.values.distinct().size < 2 || number.values.max() > 9) return null
        val playerHeight = field.height * FIELD_HEIGHT_UNITS
        val mine = surfaces(field.x, field.y).withIndex().minByOrNull { kotlin.math.abs(it.value - playerHeight) }
            ?.let { number[component[Surface(field.x, field.y, it.index)]] } ?: return null
        val rows = (top until top + height).map { y ->
            "${y.toString().padStart(4)} " + (left until left + width).joinToString(" ") { x ->
                val levels = surfaces(x, y).indices.mapNotNull { number[component[Surface(x, y, it)]] }.distinct()
                when {
                    levels.isEmpty() -> " "
                    levels.size > 1 -> "b"
                    else -> levels.single().toString()
                }
            }
        }
        return rows to mine
    }

    /** The symbol of a map object: obstacles by what clears them. */
    private fun objectSymbol(o: dev.kotlinds.pokemonclient.state.FieldObject): Char = when (o.obstacle) {
        dev.kotlinds.pokemonclient.state.ObstacleKind.CUT_TREE -> 'T'
        dev.kotlinds.pokemonclient.state.ObstacleKind.SMASH_ROCK -> 'K'
        dev.kotlinds.pokemonclient.state.ObstacleKind.BOULDER -> 'B'
        dev.kotlinds.pokemonclient.state.ObstacleKind.ICE_BLOCK -> 'I'
        null -> OBJECT.getValue(o.kind)
    }

    /**
     * The terrain of the window [render] shows around [field] (same size, same symbols), without people or puzzle
     * state: each tile's [symbol], and 'E' on the exits (warps) of the player's map; tiles not loaded are left out.
     * What a player has seen of the map from where they stand, e.g. for a memory of the explored tiles.
     */
    fun terrain(area: Area, field: FieldState, width: Int = VIEW_WIDTH, height: Int = VIEW_HEIGHT): Map<Pair<Int, Int>, Char> {
        val left = field.x - width / 2
        val top = field.y - height / 2
        val warps = area.warps.filter { it.zone == field.mapId || area.zoneAt(it.x, it.y) == field.mapId }.map { it.x to it.y }.toSet()
        val void = voidOf(area, field)
        return buildMap {
            for (y in top until top + height) for (x in left until left + width) {
                val c = if ((x to y) in warps) 'E' else tileSymbol(area, void, x, y) ?: continue
                put(x to y, c)
            }
        }
    }

    /**
     * The void around the room of [field]'s map ([Area.voidAround]): the area's own, minus what the live puzzle makes
     * a place (its moving floors, the tiles that start a ride or a lift and where they land).
     */
    internal fun voidOf(area: Area, field: FieldState): (Int, Int) -> Boolean {
        val puzzle = field.puzzle ?: return area::outside
        val places = puzzle.teleports.flatMap { t -> t.from.map { it.x to it.y } + (t.to.x to t.to.y) } +
            puzzle.surfaces.flatMap { s -> s.tiles.map { it.x to it.y } } +
            puzzle.platforms.flatMap { p -> p.tiles.map { it.x to it.y } + p.triggers.map { it.tile.x to it.tile.y } }
        return if (places.isEmpty()) area::outside else area.voidAround(places)
    }

    /** The symbol of the terrain at ([x], [y]): [OUTSIDE_SYMBOL] in the void around a room ([void]), else [symbol]; null when not loaded. */
    private fun tileSymbol(area: Area, void: (Int, Int) -> Boolean, x: Int, y: Int): Char? {
        val tile = area.tile(x, y) ?: return null
        return if (void(x, y)) OUTSIDE_SYMBOL else symbol(tile.kind, tile.blocked)
    }

    /**
     * When the player stands outside the walkable area (in the void around a room, or on a wall tile: where a
     * map-randomized warp leaves them one tile past an exit door, NOTES-run-map-randomizer), what to do: the tile next
     * to them that leads back in, or that none does. Null on a walkable tile.
     */
    internal fun outsideNote(area: Area, field: FieldState, void: (Int, Int) -> Boolean = voidOf(area, field)): String? {
        val here = area.tile(field.x, field.y)
        val onWarp = area.warps.any { it.x == field.x && it.y == field.y }
        val outside = here == null || void(field.x, field.y) ||
            (here.blocked && !onWarp && (here.kind == TileKind.Wall || here.kind == TileKind.Floor || here.kind is TileKind.Unknown))
        if (!outside) return null
        val back = Direction.entries.firstOrNull { d ->
            val x = field.x + d.dx
            val y = field.y + d.dy
            val tile = area.tile(x, y) ?: return@firstOrNull false
            !void(x, y) && (!tile.blocked || area.warps.any { it.x == x && it.y == y }) && tile.kind != TileKind.Wall
        }
        return "you stand outside the walkable area (the game left you past an exit, or you walked off the map): " +
            (back?.let { "step ${it.name.lowercase()} back onto ${field.x + it.dx},${field.y + it.dy}" }
                ?: "no tile next to you leads back in: fly, Teleport, Dig or an Escape Rope get you out (or soft-reset to your last save)")
    }

    /** The symbol of the player facing [facing] (south when unknown). */
    fun player(facing: Direction?): Char = PLAYER.getValue(facing ?: Direction.SOUTH)

    /** The meaning of a map symbol, as the [render] legend says it, or null for an unknown one. */
    fun legend(symbol: Char): String? = LEGEND[symbol]

    /** The symbol of a tile kind. */
    fun symbol(kind: TileKind, blocked: Boolean): Char = when (kind) {
        TileKind.Floor, TileKind.Sand, TileKind.Cave, is TileKind.Railing -> if (blocked) '#' else '.'
        is TileKind.Bridge -> ':'
        TileKind.TallGrass -> '"'
        TileKind.Wall -> '#'
        is TileKind.Ledge -> when (kind.jump) {
            Direction.NORTH -> '^'
            Direction.SOUTH -> 'v'
            Direction.WEST -> '<'
            Direction.EAST -> '>'
        }
        is TileKind.Water -> if (kind.fishable) '~' else ';'
        TileKind.Waterfall -> '|'
        TileKind.Whirlpool -> '@'
        TileKind.Ice -> '_'
        TileKind.Lava -> '!'
        TileKind.Ladder -> 'H'
        TileKind.Counter -> '='
        TileKind.Pc -> 'C'
        TileKind.Door -> 'E'
        is TileKind.Spinner -> '*'
        TileKind.SpinnerStop -> '+'
        is TileKind.RockClimb -> '%'
        is TileKind.Unknown -> if (blocked) '#' else '?'
    }

    private fun distance(field: FieldState, x: Int, y: Int) = kotlin.math.abs(x - field.x) + kotlin.math.abs(y - field.y)

    /** The map's tiles in the void around a room ([Area.outside]): drawn as floor by the map data, but no place. */
    const val OUTSIDE_SYMBOL = ','

    /** The key of the explanation of the reachability fields shown ([Reachability.fields]). */
    const val REACH_LEGEND = "reach_legend"

    /** What each reachability field means, for the agent. */
    private val REACH_FIELDS = mapOf(
        Reachability.REQUIRES_INTERMEDIATE_WARP to "no way on this map from here (walls, another height level): take another exit first",
        Reachability.REQUIRES_FIELD_MOVES to "field moves the way needs that your party can't use yet",
        Reachability.BLOCKED_BY_PERSON to "who stands in the only way: talk to them (some step aside), or they leave with the story",
        Reachability.BLOCKED_BY_PUZZLE to "a puzzle closes the way (see field.puzzle)",
        Reachability.ONE_WAY to "no way back once there (ledges down; for an exit, nothing takes you back from where it leads)",
    )

    /** The key of [outsideNote] in the view. */
    const val OUTSIDE = "outside_map"

    /** Where an exit leads while destinations are hidden (`ActionSettings.hideDestinations`). */
    const val UNKNOWN_DESTINATION = "unknown"

    /** People listed off screen at most (the nearest ones). */
    private const val MAX_FAR_PEOPLE = 12

    /** "3 west, 2 north", "here". */
    fun relative(field: FieldState, x: Int, y: Int): String {
        val dx = x - field.x
        val dy = y - field.y
        val parts = listOfNotNull(
            dx.takeIf { it != 0 }?.let { "${kotlin.math.abs(it)} ${if (it > 0) "east" else "west"}" },
            dy.takeIf { it != 0 }?.let { "${kotlin.math.abs(it)} ${if (it > 0) "south" else "north"}" },
        )
        return if (parts.isEmpty()) "here" else parts.joinToString(", ")
    }

    private val PLAYER = mapOf(Direction.NORTH to 'A', Direction.SOUTH to 'V', Direction.WEST to '{', Direction.EAST to '}')
    private val OBJECT = mapOf(
        FieldObjectKind.PERSON to 'P',
        FieldObjectKind.FOLLOWER to 'f',
        FieldObjectKind.ITEM_BALL to 'o',
        FieldObjectKind.OBSTACLE to 'R',
    )
    /** Tiles around the window searched for the [levels] (a walkway leaving the view and coming back is one level). */
    private const val LEVEL_MARGIN = 8

    /** Pixels of a map tile on screen (16 × 16 on the DS games). */
    private const val TILE_PIXELS = 16

    /**
     * Tiles the screen shows on each side of the player, who stands in its middle: the platform's screen size in tiles
     * (16 × 12 on the DS), less the player's own, halved (7 × 5).
     */
    val SCREEN_HALF_WIDTH = (Platform.NINTENDO_DS.screenWidth / TILE_PIXELS - 1) / 2
    val SCREEN_HALF_HEIGHT = (Platform.NINTENDO_DS.screenHeight / TILE_PIXELS - 1) / 2

    /** Width of the map rendered around the player, in tiles: what the screen shows (15 on the DS). */
    val VIEW_WIDTH = 2 * SCREEN_HALF_WIDTH + 1

    /** Height of the map rendered around the player, in tiles (11 on the DS). */
    val VIEW_HEIGHT = 2 * SCREEN_HALF_HEIGHT + 1

    private val LEGEND = mapOf(
        'A' to "you (facing north)", 'V' to "you (facing south)", '{' to "you (facing west)", '}' to "you (facing east)",
        '.' to "floor", '#' to "wall / obstacle", '"' to "tall grass (wild Pokémon)", '^' to "ledge (jump north only)",
        'v' to "ledge (jump south only)", '<' to "ledge (jump west only)", '>' to "ledge (jump east only)", '~' to "water (Surf; a rod fishes when facing it)", ';' to "water where no rod bites (top of a waterfall)",
        '|' to "waterfall", '@' to "whirlpool", '_' to "ice (slides)", '!' to "lava", 'H' to "ladder", '=' to "counter (talk across it)",
        'C' to "PC", 'E' to "exit / door (see exits)", '*' to "arrow tile (pushes)", '+' to "stop tile (ends a push)", '%' to "rocky wall (Rock Climb)", '?' to "unknown floor", 'P' to "person (see people)",
        ':' to "bridge (walk on it; water or a lower floor under it)", 'O' to "hole (you fall to the floor below, see exits)",
        '$' to "hidden item (see hidden_items)", 'e' to "something invisible to examine (see examinables)",
        'W' to "teleport tile: a pad, pit or cart station that moves you (see puzzle.teleports)",
        'L' to "lift: stepping on it takes you to the other floor (see puzzle)", 'G' to "closed gate / shutter (see puzzle)",
        'f' to "your Pokémon (follows you)", 'o' to "item ball (see items)", 'R' to "obstacle", 'S' to "sign (see signs)",
        'T' to "small tree (Cut)", 'K' to "cracked rock (Rock Smash)", 'B' to "boulder (Strength pushes it)",
        'I' to "ice block (slide into it on the ice to push it)",
        '&' to "moving platform over the lava (walkable; see puzzle.platforms)", 'X' to "platform trigger: stepping here turns or slides the platform (see puzzle.platforms)",
        'x' to "trigger: stepping here starts a scene or an event now (see blocked_by / puzzle)",
        OUTSIDE_SYMBOL to "outside the map: the void around this room, where no way of the game leads (never walk in: you may not get back)",
    )
}
