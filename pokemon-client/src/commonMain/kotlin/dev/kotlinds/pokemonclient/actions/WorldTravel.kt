package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.world.FieldMoves

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.kind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.RouteFailure
import dev.kotlinds.pokemonclient.world.WorldLinks
import dev.kotlinds.pokemonclient.world.WorldRouter
import dev.kotlinds.pokemonclient.world.WorldSource
import dev.kotlinds.pokemonclient.world.RouteOptions
import dev.kotlinds.pokemonclient.world.ZoneLink
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.state.normalizeName

/**
 * `go_to` across the world: resolves where to go (a tile, an object, `exit:<direction>`, a map's name, a tile on
 * another map, `frontier`), then walks there with [MovePlans.walkTo], going through warps, holes and map edges when
 * the destination isn't reachable on the current map ([WorldRouter]). After each warp or fall the route is planned
 * again from where the player really arrived, with the live state of the new map.
 *
 * Targets:
 * - `x`/`y` (current map), or `x`/`y` with `map` (a map's name or `map:<id>`: another floor, a neighbouring map);
 * - `person:N`, `item:N` (item ball), `warp:N`, `hole:N`, `sign:N`, `hidden_item:N`: things of the current map;
 * - `exit:north|south|east|west`: the edge of the current map towards a neighbouring map (overworld), the walk ends
 *   on the first tile of the neighbour;
 * - a map's name ("Route 26", "Victory Road 2F") or `map:<id>`: the walk ends on entering that map;
 * - `frontier`: when the agent doesn't know where to go, the nearest reachable tile of this map next to a way out
 *   (an edge towards a neighbouring map, a warp, a hole) other than those within [FRONTIER_SKIP] tiles of the player
 *   (the way they came in). The walk stops before taking it, so the agent sees what is there.
 *
 * When the application hides where the ways out lead ([ActionSettings.hideDestinations]), only the targets of the
 * current map are accepted (its warps, holes and exits are the way to explore) and walks never plan through another
 * map ([hidden]).
 */
internal object WorldTravel {

    /** A destination: [target], in the coordinates of the area of [zone]. */
    data class Goal(val zone: Int, val target: MovePlans.Target)

    fun goTo(action: GameAction.GoTo, context: PlanContext): ActionOutcome {
        val field = context.state().field ?: return ActionOutcome.Failed(ActionError.UnexpectedScreen("the overworld", context.state().screen.kind))
        val world = context.game.world ?: return noMap(field)
        val goal = when (val resolved = resolve(context, field, world, action)) {
            is Resolved.Found -> resolved.goal
            is Resolved.Failed -> return resolved.outcome
        }
        // The walk to another map, planned once from here: when there is none, the error may suggest a flight. A long
        // walk is never refused for a shorter flight: walking is the agent's choice (training on the way...).
        // Destinations hidden: every goal is on this map, nothing is planned across maps.
        val onFoot = otherMap(world, field, goal)?.takeIf { !context.settings.hideDestinations }?.let { (area, goalArea) -> worldRoute(context, world, field, area, goalArea, goal, action.options) }?.tiles
        // A long trip (minutes of surfing) says how far it has got: tiles walked of the planned route, current map.
        val meter = TravelMeter("go_to ${describeGoal(context, field, goal)}", context.scope::report)
        val trip = context.navigator.watching(meter::observe) { travel(context, world, goal, action.options, meter) }
        return finish(context, trip, goal).withNotes(trip.notes).withMovement(context, field, trip.taken).withFly(context, field, goal, onFoot)
    }

    /** The player's area and [goal]'s when the goal is on another map (both areas known), else null. */
    private fun otherMap(world: WorldSource, field: FieldState, goal: Goal): Pair<Area, Area>? {
        if (goal.zone == field.mapId) return null
        val area = world.areaOf(field.mapId) ?: return null
        return world.areaOf(goal.zone)?.let { area to it }
    }

    /**
     * No way on foot to another map (a field move missing, a long detour): when a visited fly destination lands
     * there or near, the error's hint says so ([FlyAdvisor]).
     */
    private fun ActionOutcome.withFly(context: PlanContext, start: FieldState, goal: Goal, onFoot: Int?): ActionOutcome {
        val error = (this as? ActionOutcome.Failed)?.error as? ActionError.Unavailable ?: return this
        // A fly suggestion names where the destination is: never while destinations are hidden.
        if (context.settings.hideDestinations) return this
        if (error.reason != UnavailableReason.NO_PATH || onFoot != null || goal.zone == start.mapId) return this
        val suggestion = FlyAdvisor(context.game).suggest(context.state(), goal.zone, onFoot = null) ?: return this
        val fly = "or fly: ${suggestion.landing}"
        return ActionOutcome.Failed(error.copy(hint = error.hint?.let { "$it; $fly" } ?: fly))
    }

    /**
     * The destination for a person: the map's name when going to another map without a tile ("Seafoam Islands 1F"),
     * else the target as resolved ("person:3", "exit:north", "12,5 on Route 20").
     */
    private fun describeGoal(context: PlanContext, start: FieldState, goal: Goal): String =
        if (goal.target.x == null && goal.zone != start.mapId) context.game.mapName(goal.zone).toString() else goal.target.id

    /**
     * A failure after the player already moved (walked towards a link, took a warp, then found no way on) says so:
     * where they started, where they are now and the warps taken, so the answer never looks like nothing happened.
     * Interruptions already tell their steps.
     */
    private fun ActionOutcome.withMovement(context: PlanContext, start: FieldState, taken: List<Hop>): ActionOutcome {
        if (this !is ActionOutcome.Failed || error is ActionError.Interrupted) return this
        val now = context.state().field ?: return this
        if (now.mapId == start.mapId && now.x == start.x && now.y == start.y) return this
        val where = if (now.mapId == start.mapId) "" else " on ${now.mapName}"
        val moved = "you moved before this was found: from ${start.x},${start.y}" + (if (now.mapId == start.mapId) "" else " on ${start.mapName}") +
            " to ${now.x},${now.y}$where" + (if (taken.isEmpty()) "" else " (${describe(taken)})")
        return when (val e = error) {
            is ActionError.Unavailable -> ActionOutcome.Failed(e.copy(detail = "${e.detail} ($moved)"))
            is ActionError.Timeout -> ActionOutcome.Failed(e.copy(detail = "${e.detail} ($moved)"))
            else -> this
        }
    }

    /**
     * Walks next to [target] of the current map (by its id, see [MovePlans.resolve]), through other floors when the
     * way goes there: the walk of `interact`.
     */
    fun walkNextTo(context: PlanContext, target: MovePlans.Target): Trip {
        val field = context.state().field ?: return Trip(MovePlans.Walk.Interrupted(context.state(), 0), emptyList(), emptySet())
        val world = context.game.world ?: return Trip(MovePlans.walkTo(context, target, MoveOptions()), emptyList(), emptySet())
        val meter = TravelMeter("interact ${target.id}", context.scope::report)
        return context.navigator.watching(meter::observe) { travel(context, world, Goal(field.mapId, target), MoveOptions(), meter) }
    }

    /**
     * How a [travel] ended: the last walk, the links taken before it, the active triggers of its map, and what the
     * agent should know about the way (the bicycle...).
     */
    data class Trip(val walk: MovePlans.Walk, val taken: List<Hop>, val triggers: Set<Pair<Int, Int>>, val notes: List<String> = emptyList())

    /**
     * A link of the trip taken: the planned [link], the warp the walk went through ([MovePlans.Through], the very one a
     * walk tells) and where the player stood once it was over ([arrival]).
     */
    data class Hop(val link: ZoneLink, val through: MovePlans.Through, val arrival: FieldState)

    // region Targets

    private sealed interface Resolved {
        data class Found(val goal: Goal) : Resolved
        data class Failed(val outcome: ActionOutcome) : Resolved
    }

    private fun resolve(context: PlanContext, field: FieldState, world: WorldSource, action: GameAction.GoTo): Resolved {
        val area = world.areaOf(field.mapId) ?: return Resolved.Failed(noMap(field))
        if (context.settings.hideDestinations) return hidden(context, field, area, action)
        val target = action.target
        val near = nearZones(world, area, field)
        if (action.map != null) {
            val zones = zonesNamed(context, world, action.map, near)
            if (zones.isEmpty()) return Resolved.Failed(ActionOutcome.Failed(ActionError.InvalidParameter("map", action.map, knownMaps(context, world, area, field))))
            ambiguous(context, world, area, "map", action.map, zones)?.let { return it }
            // Prefer the zone of that name closest to here: the current area, then a map linked to it.
            val zone = zones.firstOrNull { world.areaOf(it) === area } ?: zones.first()
            if (action.x == null || action.y == null) return Resolved.Found(Goal(zone, zoneTarget(MapName.idForm(zone), world, zone)))
            val targetArea = world.areaOf(zone) ?: return Resolved.Failed(noMap(field))
            val warp = targetArea.warps.firstOrNull { it.zone == zone && it.x == action.x && it.y == action.y }
            return Resolved.Found(Goal(zone, MovePlans.Target("${action.x},${action.y} on ${context.game.mapName(zone)}", action.x, action.y, warp = warp != null, trigger = warp?.trigger)))
        }
        if (target == null) {
            return MovePlans.resolve(context, null, action.x, action.y)?.let { Resolved.Found(Goal(field.mapId, it)) }
                ?: Resolved.Failed(MovePlans.unknownTarget(context, null))
        }
        if (target == FRONTIER) return frontier(world, area, field)
        if (target.startsWith("exit:")) return exit(context, area, field, target)
        MovePlans.resolve(context, target, null, null)?.let { return Resolved.Found(Goal(field.mapId, it)) }
        val zones = zonesNamed(context, world, target, near)
        if (zones.isNotEmpty()) {
            ambiguous(context, world, area, "target", target, zones)?.let { return it }
            val zone = zones.firstOrNull { world.areaOf(it) === area } ?: zones.first()
            if (zone == field.mapId) return Resolved.Failed(ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, "You are already on ${field.mapName}")))
            return Resolved.Found(Goal(zone, zoneTarget(target, world, zone)))
        }
        val exits = WorldLinks.connections(area, field.mapId).map { it.id }.distinct() + FRONTIER
        // A name that matched no map: the maps around here are what it most likely meant.
        val maps = if (':' in target) emptyList() else knownMaps(context, world, area, field)
        return Resolved.Failed(MovePlans.unknownTarget(context, target, exits + maps))
    }

    /**
     * [resolve] while destinations are hidden ([ActionSettings.hideDestinations]): a thing of the current map (person,
     * warp, hole, sign, tile, `exit:<direction>`, `frontier`), or a refusal that names no other map. Whatever names
     * another map (its name, `map:<id>`, x / y with `map`, a tile of a neighbouring map) gets the same
     * [UnavailableReason.DESTINATIONS_HIDDEN]: the answer never tells whether that map exists, is near, or how to get
     * there. A name the target doesn't resolve to on this map is refused the same way (no list of nearby maps).
     */
    private fun hidden(context: PlanContext, field: FieldState, area: Area, action: GameAction.GoTo): Resolved {
        val target = action.target
        if (action.map != null) {
            if (!namesThisMap(context, field, action.map)) return Resolved.Failed(hiddenElsewhere(field, action.map))
            if (action.x == null || action.y == null) return Resolved.Failed(alreadyHere(field))
            return local(context, field, area, null, action.x, action.y, "${action.x},${action.y}")
        }
        if (target == null) return local(context, field, area, null, action.x, action.y, "${action.x},${action.y}")
        if (target == FRONTIER) return frontier(context.game.world ?: return Resolved.Failed(noMap(field)), area, field)
        if (target.startsWith("exit:")) return exit(context, area, field, target)
        MovePlans.resolve(context, target, null, null)?.let { return Resolved.Found(Goal(field.mapId, it)) }
        if (namesThisMap(context, field, target)) return Resolved.Failed(alreadyHere(field))
        // An id of this map's kinds that resolved to nothing (person:99): the usual list of valid ids. Anything else
        // (a map's name, map:<id>) is another map's.
        if (':' in target && !target.startsWith("map:")) {
            return Resolved.Failed(MovePlans.unknownTarget(context, target, WorldLinks.connections(area, field.mapId).map { it.id }.distinct() + FRONTIER))
        }
        return Resolved.Failed(hiddenElsewhere(field, target))
    }

    /** A tile ([x], [y]) of the current map; refused when it is known to be on another map of the area. */
    private fun local(context: PlanContext, field: FieldState, area: Area, target: String?, x: Int?, y: Int?, asked: String): Resolved {
        val zone = if (x != null && y != null) area.zoneAt(x, y) else null
        if (zone != null && zone != field.mapId) return Resolved.Failed(hiddenElsewhere(field, asked))
        return MovePlans.resolve(context, target, x, y)?.let { Resolved.Found(Goal(field.mapId, it)) }
            ?: Resolved.Failed(MovePlans.unknownTarget(context, target))
    }

    /**
     * True when [name] is the current map: its own name ([MapName.isNamed], `map:<its id>` too), or its place's name
     * ([MapName.placeIs]) only when the place names this map as [zonesNamed] resolves it (the place's outdoor map):
     * a town's buildings share its place, and "Violet City" asked from the Violet Gym is the town outside, another
     * map (refused like any other while destinations are hidden), not "already here".
     */
    private fun namesThisMap(context: PlanContext, field: FieldState, name: String): Boolean {
        if (field.mapName.isNamed(name)) return true
        if (!field.mapName.placeIs(name)) return false
        val world = context.game.world ?: return false
        return field.mapId in zonesNamed(context, world, name)
    }

    private fun alreadyHere(field: FieldState) =
        ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, "You are already on ${field.mapName}"))

    /** The refusal of a target off the current map while destinations are hidden: the same words whatever it is. */
    internal fun hiddenElsewhere(field: FieldState, asked: String) = ActionOutcome.Failed(ActionError.Unavailable(
        UnavailableReason.DESTINATIONS_HIDDEN,
        "`$asked` isn't a place of the map you are on (${field.mapName}), and destinations are hidden: go_to only reaches " +
            "places of this map",
        "walk to one of its exits (go_to warp:N, hole:N, exit:<direction> or frontier) and take it to find out where it " +
            "leads; read signs, listen to people and keep your own notes",
    ))

    /** Entering zone [zone]: any tile of it (its area is shared with other zones on the overworld). */
    private fun zoneTarget(id: String, world: WorldSource, zone: Int): MovePlans.Target {
        val area = world.areaOf(zone)
        return MovePlans.Target(id, null, null, isGoal = { node -> area?.zoneAt(node.x, node.y) == zone })
    }

    /** `exit:<direction>`: the tiles of the neighbouring maps one step past this map's edge that way. */
    private fun exit(context: PlanContext, area: Area, field: FieldState, target: String): Resolved {
        val connections = WorldLinks.connections(area, field.mapId)
        val direction = Direction.parse(target.substringAfter(':'))
        val chosen = connections.filter { it.direction == direction }
        if (direction == null || chosen.isEmpty()) {
            // Destinations hidden: the exits by id only (where they lead is for the agent to find out).
            val allowed = if (context.settings.hideDestinations) connections.map { it.id }.distinct()
            else connections.map { "${it.id} (${context.game.mapName(it.toZone)})" }.distinct()
            val detail = if (connections.isEmpty()) "${field.mapName} has no edge leading to another map: use its warps (see exits)" else "No exit that way"
            return Resolved.Failed(ActionOutcome.Failed(if (connections.isEmpty()) ActionError.Unavailable(UnavailableReason.NO_PATH, detail) else ActionError.InvalidParameter("target", target, allowed)))
        }
        return Resolved.Found(Goal(field.mapId, exitTarget(target, chosen, onThisMap = context.settings.hideDestinations)))
    }

    /**
     * The target `exit:<direction>` ([id]) through the map edges [chosen]: the neighbour's first tiles one step past
     * them. [onThisMap]: walks kept on the player's map (destinations hidden, the view's reachability) may still step
     * onto those tiles: the exit asked for.
     */
    internal fun exitTarget(id: String, chosen: List<dev.kotlinds.pokemonclient.world.MapConnection>, onThisMap: Boolean): MovePlans.Target {
        val beyond = chosen.flatMap { c -> c.tiles.map { (x, y) -> x + c.direction.dx to y + c.direction.dy } }.toSet()
        return MovePlans.Target(id, null, null, isGoal = { node -> (node.x to node.y) in beyond }, enter = if (onThisMap) beyond else emptySet())
    }

    /** `frontier`: the nearest reachable tile next to a way out of this map, away from where the player stands. */
    private fun frontier(world: WorldSource, area: Area, field: FieldState): Resolved {
        val zone = field.mapId
        val edgeTiles = WorldLinks.connections(area, zone).flatMap { it.tiles }
        val nearLinks = WorldLinks.links(world, area, zone).flatMap { l -> Direction.entries.map { l.x + it.dx to l.y + it.dy } }
            .filter { (x, y) -> area.zoneAt(x, y) == zone && area.tile(x, y)?.blocked == false }
        val candidates = (edgeTiles + nearLinks).filter { (x, y) -> kotlin.math.abs(x - field.x) + kotlin.math.abs(y - field.y) > FRONTIER_SKIP }.toSet()
        if (candidates.isEmpty()) {
            return Resolved.Failed(ActionOutcome.Failed(ActionError.Unavailable(UnavailableReason.NO_PATH, "No other way out of ${field.mapName} than the one here")))
        }
        return Resolved.Found(Goal(zone, MovePlans.Target(FRONTIER, null, null, isGoal = { node -> (node.x to node.y) in candidates })))
    }

    /**
     * Zones named [name] (the one [MapName] of every game). In order, the first that finds any:
     * - `map:<id>`, or the map's own name or display form ([MapName.isNamed]; case, punctuation and accents ignored,
     *   apostrophes too: "Elm's" = "Elms");
     * - the place's name ([MapName.placeIs]: "Violet City", "Bourg Geon" in French): the place's outdoor map (on an
     *   area of several zones), else every map of that place;
     * - the looser variants an agent writes of a map's own name ([looseMatch]): without the town ("Elm's Lab" for
     *   "New Bark Elms Lab 1F") or without the floor of a ground floor ("1F"), else its words in order with some left
     *   out ([wordsMatch]: "Seafoam Gym" for "Seafoam Islands Cinnabar Gym"). Among several loose matches, those
     *   around the player (this area, the maps its exits lead to) win.
     * Still several: all of them, which [resolve] reports as ambiguous.
     */
    private fun zonesNamed(context: PlanContext, world: WorldSource, name: String, near: Set<Int> = emptySet()): List<Int> {
        MapName.parseIdForm(name)?.let { id -> return listOfNotNull(id.takeIf { world.areaOf(it) != null }) }
        if (normalizeName(name).isEmpty()) return emptyList()
        val names = (0 until world.zoneCount).map { context.game.mapName(it) }
        // A possessive is written either way: "Elm's Lab" is "Elms Lab", "Diglett's Cave" is "Diglett Cave".
        val queries = listOf(name, POSSESSIVE.replace(name, "")).distinct()
        val exact = names.filter { n -> queries.any(n::isNamed) }
        if (exact.isNotEmpty()) return exact.map { it.id }
        val place = names.filter { n -> queries.any(n::placeIs) }
        if (place.isNotEmpty()) return place.filter { n -> (world.areaOf(n.id)?.zoneBounds?.size ?: 0) > 1 }.ifEmpty { place }.map { it.id }
        // The map's own name identifies it (the place is shared by a town and its buildings).
        val own = names.mapNotNull { n -> (n.map ?: n.location)?.let { n.id to it } }
        val loose = own.filter { (_, n) -> queries.any { looseMatch(n, it) } }.map { it.first }
            // Still nothing: the words of the name in order, some left out ("Seafoam Gym" for "Seafoam Islands Cinnabar Gym").
            .ifEmpty { own.filter { (_, n) -> queries.any { wordsMatch(n, it) } }.map { it.first } }
        val nearby = loose.filter { it in near }
        return nearby.ifEmpty { loose }
    }

    /**
     * True when every word of [query] (two at least) is a whole word of map [name], in the same order, other words of
     * the name left out: "Seafoam Gym" → "Seafoam Islands Cinnabar Gym" (Blaine's gym, moved into the Seafoam Islands:
     * every other gym is "<Town> Gym", NOTES: refused as an unknown map). Several maps answering (the floors of a store) are
     * reported as ambiguous by [resolve]. Town and city words are optional like in [MapName.sameMapName] ("Viridian City Gym" → "Viridian Gym").
     */
    internal fun wordsMatch(name: String, query: String): Boolean {
        val wanted = words(query).filterNot { it in TOWN_WORDS }
        if (wanted.size < MIN_ORDERED_WORDS) return false
        val have = words(name)
        var at = 0
        for (word in wanted) {
            while (at < have.size && have[at] != word) at++
            if (at == have.size) return false
            at++
        }
        return true
    }

    /** The words of a map name, normalized ("Elm's" = "elms", [normalizeName]). */
    private fun words(value: String): List<String> = value.split(' ', '-', '.', '_').map { normalizeName(it) }.filter { it.isNotEmpty() }

    /** Words a name may add or leave out ("New Bark Town" is the map "New Bark"). */
    private val TOWN_WORDS = setOf("town", "city")

    /** [wordsMatch] needs this many words: a single word is the suffix rule of [looseMatch]. */
    private const val MIN_ORDERED_WORDS = 2

    /**
     * True when [query] names map [name] loosely: [name] (or [name] without its ground-floor "1F") ends with [query],
     * a whole word or more of it ("Elms Lab" → "New Bark Elms Lab 1F", "Dept Store" → "Goldenrod Dept Store 1F").
     */
    internal fun looseMatch(name: String, query: String): Boolean {
        val q = MapName.key(query)
        if (q.length < MIN_LOOSE_NAME) return false
        val words = name.split(' ', '-', '.').filter { it.isNotBlank() }
        val variants = listOfNotNull(words, words.takeIf { it.lastOrNull()?.equals(GROUND_FLOOR, ignoreCase = true) == true }?.dropLast(1))
        // A suffix made of whole words of the name: "Lab" matches "Elms Lab", "ab" doesn't.
        return variants.any { w -> w.indices.any { start -> MapName.key(w.drop(start).joinToString(" ")) == q } }
    }

    /** "'s" / "’s" ending a word. */
    private val POSSESSIVE = Regex("['’]s\\b", RegexOption.IGNORE_CASE)

    /** Ground floors: "Elm's Lab" means its 1F. */
    private const val GROUND_FLOOR = "1F"

    /** Loose names shorter than this (normalized) are too vague to guess from. */
    private const val MIN_LOOSE_NAME = 3

    /**
     * The INVALID_PARAM for a name several maps of different names answer to loosely ("Pokecenter 1F" in every town),
     * listing them; null when they all share one name (the floors of a map spread over areas) or one is on this area.
     */
    private fun ambiguous(context: PlanContext, world: WorldSource, area: Area, parameter: String, value: String, zones: List<Int>): Resolved? {
        if (zones.any { world.areaOf(it) === area }) return null
        val names = zones.map { context.game.mapName(it).toString() }.distinct()
        if (names.size <= 1) return null
        return Resolved.Failed(ActionOutcome.Failed(ActionError.InvalidParameter(parameter, value, names.take(MAX_SUGGESTED_NAMES))))
    }

    /** The zones of [area] and those this map's exits lead to: where a loose name most likely points. */
    private fun nearZones(world: WorldSource, area: Area, field: FieldState): Set<Int> =
        (area.zoneBounds.keys + field.mapId + WorldLinks.connections(area, field.mapId).map { it.toZone } +
            WorldLinks.links(world, area, field.mapId).map { it.targetZone }).toSet()

    /** Maps worth suggesting: the neighbours and the destinations of this map's warps and holes. */
    private fun knownMaps(context: PlanContext, world: WorldSource, area: Area, field: FieldState): List<String> =
        (WorldLinks.connections(area, field.mapId).map { it.toZone } + WorldLinks.links(world, area, field.mapId).map { it.targetZone })
            .distinct().map { context.game.mapName(it).toString() }

    // endregion

    // region Walking

    /** Walks to [goal]: on this area directly, or through the links the [WorldRouter] finds, one at a time. */
    private fun travel(context: PlanContext, world: WorldSource, goal: Goal, options: MoveOptions, meter: TravelMeter? = null): Trip {
        val notes = mutableListOf<String>()
        val trip = travel(context, world, goal, options, notes, meter)
        // Got off at a building that doesn't allow cycling after riding there: nothing worth saying.
        val said = if (RODE in notes) notes.filterNot { it.startsWith(NO_CYCLING) } else notes
        // An interruption tells the tiles of the whole trip (every hop, through warps), not only of its last walk.
        val walk = trip.walk.let { if (it is MovePlans.Walk.Interrupted && meter != null) it.copy(steps = meter.done) else it }
        return trip.copy(walk = walk, notes = said + trip.notes)
    }

    /**
     * The trip itself (see [WorldTravel]); [meter], when given, is told the length of the route planned at each hop
     * (from where the player really is), so the progress always has a total.
     */
    private fun travel(context: PlanContext, world: WorldSource, goal: Goal, options: MoveOptions, notes: MutableList<String>, meter: TravelMeter?): Trip {
        if (context.settings.hideDestinations) return hiddenTravel(context, goal, options, notes)
        val taken = mutableListOf<Hop>()
        var localFailure: MovePlans.Walk.NoRoute? = null
        // One pass per link taken, and one more for the walk on the destination's map: a route of [MAX_HOPS] links
        // (accepted by [detour]) takes MAX_HOPS + 1 passes (Mt. Silver's summit to its Pokémon Center: 12 warps).
        repeat(MAX_HOPS + 1) {
            // Back on the bicycle after each warp (a building gets the player off it).
            BikeRide.mount(context, options)?.let { if (it !in notes) notes += it }
            val state = context.navigator.settle()
            val field = state.field
            if (field == null || state.screen !is Screen.Overworld) return Trip(MovePlans.Walk.Interrupted(state, 0), taken, emptySet())
            val area = world.areaOf(field.mapId)
            val goalArea = world.areaOf(goal.zone)
            if (area == null || goalArea == null) return Trip(MovePlans.Walk.NoRoute(RouteFailure.StartUnknown, noMapDetail(field)), taken, emptySet())
            val triggers = MovePlans.activeTriggers(context, field)
            // The whole route from here, for the progress (and, towards another area, the link to take first).
            val planned = meter?.let { worldRoute(context, world, field, area, goalArea, goal, options).also { route -> it.plan(route?.tiles) } }
            if (area === goalArea) {
                val walked = MovePlans.walkTo(context, goal.target, options)
                if (walked !is MovePlans.Walk.NoRoute || walked.failure == RouteFailure.StartUnknown) return Trip(walked, taken, triggers)
                // Not reachable on this map by walking: maybe through other floors / buildings.
                localFailure = walked
            }
            // The route planned above still holds when nothing was walked since (another area: no local walk).
            val route = plan(context, world, field, area, goalArea, goal, options, planned.takeIf { area !== goalArea })
            // Before moving: a way the walk can't finish, or a long detour to a target of this very map, is the agent's call.
            // A field move needed on this very map: the local failure knows where to use it from and what the party
            // lacks; keep it over the less precise cross-map one (and over a detour: it opens the short way).
            // So is a walk that found no way left once the game refused steps the map allows (only the walk knows them).
            val precise = localFailure?.takeIf { precise(it.failure) || it.refusals.isNotEmpty() }
            if (taken.isEmpty()) route?.let { detour(context, field, goal, it) }?.let { return Trip(precise ?: it, taken, triggers) }
            val link = route?.links?.firstOrNull()
                ?: return Trip(
                    precise
                        ?: blocked(context, world, field, area, goalArea, goal, options) ?: localFailure
                        ?: MovePlans.Walk.NoRoute(RouteFailure.Unreachable, "no way to ${goal.target.id} from ${field.x},${field.y} (${field.mapName})"),
                    taken, triggers,
                )
            val walked = MovePlans.walkTo(context, MovePlans.Target(link.id, link.x, link.y, warp = true, trigger = link.trigger), options)
            if (walked !is MovePlans.Walk.Arrived) return Trip(walked, taken, triggers)
            // A walk ends at the first warp ([FieldControl.awaitOutcome]): none means the link didn't fire.
            val through = walked.through
                ?: return Trip(MovePlans.Walk.Stuck("${link.id} at ${link.x},${link.y} didn't take the player anywhere"), taken, triggers)
            // Another warp than the link planned (one under the player's feet, a hole on the way): the trip stops there,
            // the answer says where the player is ([finish]).
            if (through.x != link.x || through.y != link.y) return Trip(walked, taken, triggers)
            taken += Hop(link, through, walked.field)
            // What the walk to the link did besides walking (a tree cut on the way): told with the final answer.
            walked.notes.forEach { if (it !in notes) notes += it }
            localFailure = null
        }
        return Trip(MovePlans.Walk.Stuck("still not there after ${taken.size} warps: ${describe(taken)}"), taken, emptySet())
    }

    /**
     * The trip while destinations are hidden ([ActionSettings.hideDestinations]): one walk on the player's map (kept on
     * it by [MovePlans.overlay]), never a route through warps or other maps, even to reach a place of this map (the
     * way round would reveal where the warps lead). A warp or hole asked for is taken: that is how the agent explores.
     */
    private fun hiddenTravel(context: PlanContext, goal: Goal, options: MoveOptions, notes: MutableList<String>): Trip {
        BikeRide.mount(context, options)?.let { if (it !in notes) notes += it }
        val state = context.navigator.settle()
        val field = state.field
        if (field == null || state.screen !is Screen.Overworld) return Trip(MovePlans.Walk.Interrupted(state, 0), emptyList(), emptySet())
        val triggers = MovePlans.activeTriggers(context, field)
        return Trip(MovePlans.walkTo(context, goal.target, options), emptyList(), triggers)
    }

    /** True for the local failures that say exactly what closes the way on this map (a field move, a shutter, a level, a mechanism). */
    private fun precise(failure: RouteFailure): Boolean =
        failure is RouteFailure.NeedsFieldMove || failure is RouteFailure.BlockedByBarrier || failure == RouteFailure.DifferentLevel ||
            failure is dev.kotlinds.pokemonclient.world.NeedsMechanism

    /**
     * The cheapest route from the player to [goal] across zones (its first link is taken next), or null when there is
     * none; [planned] when given (already planned from this very spot, for the progress).
     */
    private fun plan(
        context: PlanContext,
        world: WorldSource,
        field: FieldState,
        area: Area,
        goalArea: Area,
        goal: Goal,
        options: MoveOptions,
        planned: WorldRouter.WorldRoute? = null,
    ): WorldRouter.WorldRoute? =
        planned ?: worldRoute(context, world, field, area, goalArea, goal, options)
            // The only way out crosses a scene trigger (Elm's aide by the lab's door): take it, the scene will start
            // on the way like for a target on this map (the walk to the link allows triggers the same way).
            ?: worldRoute(context, world, field, area, goalArea, goal, options, crossScenes = true)

    /**
     * The refusal of [route] before taking it, or null when it is fine:
     * - more warps than one `go_to` takes ([MAX_HOPS]): the walk would stop half way, somewhere the agent never chose;
     * - a destination on the player's own map (a warp, a person, a tile of it) reached only through more than
     *   [MAX_LOCAL_DETOUR] warps: it looks next door, but rocks or walls are in between, and the way round crosses the
     *   region (NOTES: `go_to warp:1` on Route 20, the Seafoam Islands entrance 57 tiles east on a beach walled off by
     *   rocks, set off west towards Route 21 for a 17-warp loop through Kanto, the entrance next to the player
     *   (warp:0) being the way in the agent wanted).
     * A map named on purpose, however far, is taken (within [MAX_HOPS]).
     */
    private fun detour(context: PlanContext, field: FieldState, goal: Goal, route: WorldRouter.WorldRoute): MovePlans.Walk.NoRoute? {
        val links = route.links.size
        val local = goal.zone == field.mapId
        if (links <= MAX_HOPS && !(local && links > MAX_LOCAL_DETOUR)) return null
        val maps = route.places.mapNotNull { it.zone }.fold(mutableListOf<Int>()) { acc, z -> if (acc.lastOrNull() != z && z != field.mapId) acc += z; acc }
        val names = maps.map { context.game.mapName(it).toString() }.fold(mutableListOf<String>()) { acc, n -> if (acc.lastOrNull() != n) acc += n; acc }
        // Both ends of the way: where it sets off, and the side the destination is reached from.
        val shown = if (names.size <= MAX_DETOUR_NAMES) names.joinToString(" → ")
        else (names.take(MAX_DETOUR_NAMES / 2) + "…" + names.takeLast(MAX_DETOUR_NAMES / 2)).joinToString(" → ")
        val why = if (local) "isn't reachable from ${field.x},${field.y} on ${field.mapName} by walking or surfing (rocks, walls or heights in between); the only way found"
        else "is too far for one go_to: the way"
        val target = goal.target
        val at = if (target.x != null && target.y != null && target.id != "${target.x},${target.y}") " at ${target.x},${target.y}" else ""
        return MovePlans.Walk.NoRoute(RouteFailure.LongDetour(links, maps), "${target.id}$at $why goes through $links warps ($shown), not taken by itself")
    }

    private fun worldRoute(
        context: PlanContext,
        world: WorldSource,
        field: FieldState,
        area: Area,
        goalArea: Area,
        goal: Goal,
        options: MoveOptions,
        relaxed: Boolean = false,
        crossScenes: Boolean = false,
    ): WorldRouter.WorldRoute? {
        val overlay = MovePlans.overlay(context, field, emptySet()).let { if (crossScenes) it.copy(activeTriggers = emptySet()) else it }
        val router = WorldRouter(world) { _, a -> if (a === area) overlay else WorldRouter.staticOverlay(a) }
        val start = Pathfinder(area).nodeOf(field)
        val goalTiles = MovePlans.goalTiles(goalArea, goal.target)
        val enterable = if (goal.target.adjacent) emptySet() else goalTiles
        return router.route(
            field.mapId, start, worldRouteOptions(context, field, options),
            goalTiles = { a -> if (a === goalArea) enterable else emptySet() }, relaxed = relaxed, ignorePeople = relaxed,
        ) { place -> place.area === goalArea && (goal.target.isGoal?.invoke(place.node) ?: ((place.node.x to place.node.y) in goalTiles)) }
    }

    /**
     * The route options across zones: the same field moves as a walk on one map ([FieldMoveWalk.access]: Cut, Surf...
     * the party can use by itself), so a Cut tree in front of another map's door is cut like one on the way inside a
     * gym (NOTES: "needs Cut" in front of the Vermilion Gym while the walk out cut it).
     */
    internal fun worldRouteOptions(context: PlanContext, field: FieldState, options: MoveOptions): RouteOptions {
        val state = context.state()
        return MovePlans.routeOptions(field, options, FieldMoves.usable(FieldMoveWalk.access(context, state)), MovePlans.stepWeights(context, state, options))
    }

    /**
     * Why there is no route across zones either: the first obstacle (field move, person) of the route that crosses
     * obstacles, with the map it's on. Null when even that finds nothing.
     */
    private fun blocked(context: PlanContext, world: WorldSource, field: FieldState, area: Area, goalArea: Area, goal: Goal, options: MoveOptions): MovePlans.Walk.NoRoute? {
        if (!options.acceptOneWay && worldRoute(context, world, field, area, goalArea, goal, options.copy(acceptOneWay = true)) != null) {
            return MovePlans.Walk.NoRoute(RouteFailure.OnlyOneWay, "no way to ${goal.target.id} from ${field.x},${field.y} without jumping down ledges")
        }
        // Scene triggers don't block the way (a scene starts, then the walk goes on): crossing them keeps the search
        // from inventing a detour over water around a trigger (NOTES-run P6: "needs Surf" next to Violet's bridge).
        val route = worldRoute(context, world, field, area, goalArea, goal, options, relaxed = true, crossScenes = true) ?: return null
        val routeOptions = worldRouteOptions(context, field, options)
        val overlay = MovePlans.overlay(context, field, emptySet())
        for (place in route.places) {
            val pathfinder = if (place.area === area) Pathfinder(area, overlay) else Pathfinder(place.area, WorldRouter.staticOverlay(place.area))
            val failure = pathfinder.blockerAt(place.node.x, place.node.y, routeOptions) ?: continue
            val zone = place.zone
            val where = if (place.area === area) "" else " on ${zone?.let { context.game.mapName(it) } ?: "another floor"}"
            val via = if (route.links.isEmpty()) "" else " (the way: " +
                route.links.joinToString(", ") { it.id + " → " + context.game.mapName(it.targetZone) } + ")"
            return MovePlans.Walk.NoRoute(failure, "no way to ${goal.target.id} from ${field.x},${field.y}: blocked at ${place.node.x},${place.node.y}$where$via")
        }
        return null
    }

    /** The outcome of the last walk, with the warps taken and the scene a trigger started. */
    private fun finish(context: PlanContext, trip: Trip, goal: Goal): ActionOutcome {
        val walked = trip.walk
        val taken = trip.taken
        val triggers = trip.triggers
        val via = if (taken.isEmpty()) "" else " (${describe(taken)})"
        return when (walked) {
            is MovePlans.Walk.Arrived -> {
                val through = walked.through
                val target = goal.target
                // The walk ends at the first warp: the one asked for ("took warp:3 → ..."), or one on the way.
                val reached = when {
                    through == null -> "arrived at ${walked.field.x},${walked.field.y}" + (if (taken.isEmpty()) "" else " on ${walked.field.mapName}")
                    target.warp == true && through.x == target.x && through.y == target.y -> through.describe(walked.field)
                    else -> MovePlans.stoppedAt(through, walked.field)
                }
                ActionOutcome.Done(reached + via + walked.notes.joinToString("") { "; $it" })
            }
            is MovePlans.Walk.Interrupted -> {
                val at = walked.at
                if (at != null && at in triggers && walked.state.battle == null) {
                    val onGoal = goal.target.x == at.first && goal.target.y == at.second
                    if (onGoal) ActionOutcome.Done("arrived at ${at.first},${at.second}: this started a scene (now: ${walked.state.screen.kind})$via")
                    else ActionOutcome.Failed(ActionError.Interrupted(InterruptionCause.SCRIPT,
                        "${walked.steps} step(s)$via; ${at.first},${at.second} wasn't the destination but the only way to ${goal.target.id} crosses it: " +
                            (MovePlans.sceneNote(context, walked.state.field?.mapId ?: context.state().field?.mapId, at) ?: "a scene trigger there started a scene")))
                } else with(MovePlans) { walked.toOutcome(context) { "" } }.let { outcome ->
                    if (taken.isNotEmpty() && outcome is ActionOutcome.Failed && outcome.error is ActionError.Interrupted) {
                        ActionOutcome.Failed(outcome.error.copy(performed = outcome.error.performed + via))
                    } else outcome
                }
            }
            else -> with(MovePlans) { walked.toOutcome(context) { "" } }
        }
    }

    /** [notes] added to the detail of a success or of an interruption. */
    private fun ActionOutcome.withNotes(notes: List<String>): ActionOutcome {
        if (notes.isEmpty()) return this
        val text = notes.joinToString("") { "; $it" }
        return when (this) {
            is ActionOutcome.Done -> copy(detail = (detail ?: "done") + text)
            is ActionOutcome.Failed -> if (error is ActionError.Interrupted) ActionOutcome.Failed(error.copy(performed = error.performed + text)) else this
        }
    }

    /**
     * The links of a trip for the agent, each in the words of a walk's warp ([MovePlans.Through.describe]: "took warp:4
     * at 12,3 (Route 32) → Union Cave 1F (17,31)"), one way falls said so.
     */
    private fun describe(taken: List<Hop>): String =
        if (taken.isEmpty()) "no warp taken" else taken.joinToString("; ") { h -> h.through.describe(h.arrival) + if (h.link.oneWay) " (fell, one way)" else "" }

    // endregion

    /** The target id of [frontier]. */
    const val FRONTIER = "frontier"

    /** `frontier` skips the ways out this close to the player (the one they just came through). */
    private const val FRONTIER_SKIP = 2

    private const val RODE = BikeRide.RODE
    private const val NO_CYCLING = BikeRide.NO_CYCLING

    /** An ambiguous name lists at most this many maps. */
    private const val MAX_SUGGESTED_NAMES = 12

    /** At most this many warps / falls per `go_to`. */
    private const val MAX_HOPS = 12

    /**
     * A destination of the player's own map reached through more warps than this is a detour across the region
     * ([detour]): a dungeon's way between two parts of a floor (ladders down and up) takes fewer.
     */
    private const val MAX_LOCAL_DETOUR = 8

    /** A refused detour names at most this many maps of its way. */
    private const val MAX_DETOUR_NAMES = 6
}
