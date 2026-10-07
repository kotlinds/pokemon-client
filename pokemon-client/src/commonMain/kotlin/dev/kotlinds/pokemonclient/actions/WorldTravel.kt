package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.world.FieldMoves

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.kind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.ElevatorOperator
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.Overlay
import dev.kotlinds.pokemonclient.world.PushPlanner
import dev.kotlinds.pokemonclient.world.StepWeights
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.Node
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
        val field = context.state().field ?: return ActionOutcome.Failed(ActionError.UnexpectedScreen("the overworld", context.state().screen))
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
        return finish(context, trip, goal).withNotes(trip.notes).withMovement(context, field, trip.taken).withPuzzle(context, trip)
            .withFly(context, field, goal, onFoot)
    }

    /**
     * No way at all (walls, heights) while a movement puzzle the save keeps is unsolved on a map the player can walk
     * to ([dev.kotlinds.pokemonclient.PokemonGame.savedPuzzles]: the Ice Path boulders, whose fall makes the stoppers
     * of the slides below): the routes take the maps as they are now, so the hint names the puzzle (race: "not
     * connected" six times in the Ice Path, the puzzle never read). Not while destinations are hidden (it would tell
     * where the way goes), and only with a walkthrough ([ActionSettings.revealHidden]): that a puzzle of another map
     * lifts the way is what a walkthrough tells, not what the screen shows.
     */
    private fun ActionOutcome.withPuzzle(context: PlanContext, trip: Trip): ActionOutcome {
        val error = (this as? ActionOutcome.Failed)?.error as? ActionError.Unavailable ?: return this
        if (context.settings.hideDestinations || !context.settings.revealHidden || error.reason != UnavailableReason.NO_PATH) return this
        val failure = (trip.walk as? MovePlans.Walk.NoRoute)?.failure
        if (failure != RouteFailure.Unreachable && failure != RouteFailure.DifferentLevel) return this
        val unsolved = context.game.savedPuzzles(context.scope.memory()).filterValues { p -> p.boulderHoles.any { !it.fallen } }
        if (unsolved.isEmpty()) return this
        val world = context.game.world ?: return this
        val field = context.state().field ?: return this
        val area = world.areaOf(field.mapId) ?: return this
        val options = worldRouteOptions(context, field, MoveOptions(acceptOneWay = true))
        val start = Pathfinder(area).nodeOf(field)
        val (zone, puzzle) = unsolved.entries.firstOrNull { (zone, _) ->
            zone == field.mapId || routeAcross(context, world, field, area) { router -> router.route(field.mapId, start, options) { it.zone == zone } } != null
        } ?: return this
        val boulders = puzzle.boulderHoles.filter { !it.fallen }.joinToString(", ") { "${it.boulder} into the hole at ${it.hole.x},${it.hole.y}" }
        val note = "the way may need the boulder puzzle of ${context.game.mapName(zone)}, not solved yet: push its boulders into " +
            "their holes there ($boulders; the push action, see field.puzzle once there)"
        return ActionOutcome.Failed(error.copy(hint = error.hint?.let { "$it; $note" } ?: note))
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
        // A tile written in `target` ("19,23"): the tile parameters are x and y (one form, no alias), said as such
        // rather than "not a place of this map" (NOTES-run-map-randomizer; race: "9,26", "17,45").
        action.target?.takeIf { COORDINATES.matches(it) }?.let { written ->
            val (x, y) = written.split(',').map { it.trim() }
            return Resolved.Failed(ActionOutcome.Failed(ActionError.InvalidParameter("target", written, listOf("a tile is given with x and y (x: $x, y: $y), not in target"))))
        }
        // A tile of this map nobody can stand on (a wall, a counter, someone there): said before any step.
        val sameMap = action.map == null || namesThisMap(context, field, action.map)
        if (sameMap && action.target == null && action.x != null && action.y != null && area.zoneAt(action.x, action.y).let { it == null || it == field.mapId }) {
            MovePlans.obstacleTarget(context.game, field, context.settings, action.x, action.y)?.let { return Resolved.Failed(ActionOutcome.Failed(it)) }
        }
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
            return Resolved.Found(Goal(zone, MovePlans.Target(
                "${action.x},${action.y} on ${context.game.mapName(zone)}", action.x, action.y, warp = warp != null, trigger = warp?.trigger,
                isGoal = warp?.let { MovePlans.atWarpLevel(targetArea, action.x, action.y) },
            )))
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
     *
     * The trip remembers what it found on the way ([Seen]): each map's live state as the player left it (people, item
     * balls, refused steps: planning again from another floor uses it rather than the map data's guess), and the
     * warps it took. A warp planned in the route is taken; taking one of them a second time would go round in circles
     * (the way planned from here leads back where the trip already was and found no way on): refused before taking it
     * ([RouteFailure.Oscillation]; NOTES race: the Bell Tower 3F / 4F ladder taken 13 times, also the Burned Tower and
     * Ilex Forest's gatehouse).
     */
    private fun travel(context: PlanContext, world: WorldSource, goal: Goal, options: MoveOptions, notes: MutableList<String>, meter: TravelMeter?): Trip {
        if (context.settings.hideDestinations) return hiddenTravel(context, world, goal, options, notes)
        val taken = mutableListOf<Hop>()
        val seen = Seen()
        var localFailure: MovePlans.Walk.NoRoute? = null
        // One pass per link taken, and one more for the walk on the destination's map. A long trip is legitimate
        // (Cerulean City to Ecruteak City: 16 warps), and stays safe: progress is reported, battles and scenes stop the
        // walk. The bound only guards against a trip that never ends ([MAX_TRIP_LINKS], far above any real one); the
        // oscillation check below already stops a trip going back and forth.
        repeat(MAX_TRIP_LINKS + 1) {
            // Planned again from where this pass starts: the routes of the last pass are stale.
            seen.forgetRoutes()
            // Into a lift that rides by itself: its messages (where it goes, then that it got there) are read on the way.
            taken.lastOrNull()?.let { rideElevator(context, world, it)?.let { stopped -> return Trip(MovePlans.Walk.Interrupted(stopped, 0), taken, emptySet()) } }
            // Back on the bicycle after each warp (a building gets the player off it), unless the way crosses ice.
            BikeRide.mount(context, options) { crossesIce(context, world, goal, options, seen) }?.let { if (it !in notes) notes += it }
            val state = context.navigator.settle()
            val field = state.field
            if (field == null || state.screen !is Screen.Overworld) return Trip(MovePlans.Walk.Interrupted(state, 0), taken, emptySet())
            val area = world.areaOf(field.mapId)
            val goalArea = world.areaOf(goal.zone)
            if (area == null || goalArea == null) return Trip(MovePlans.Walk.NoRoute(RouteFailure.StartUnknown, noMapDetail(field)), taken, emptySet())
            val triggers = MovePlans.activeTriggers(context, field)
            // The whole route from here, for the progress (and, towards another area, the link to take first).
            val planned = meter?.let { worldRoute(context, world, field, area, goalArea, goal, options, seen = seen).also { route -> it.plan(route?.tiles) } }
            if (area === goalArea) {
                val walked = MovePlans.walkTo(context, goal.target, options)
                if (walked !is MovePlans.Walk.NoRoute || walked.failure == RouteFailure.StartUnknown) return Trip(walked, taken, triggers)
                // Not reachable on this map by walking: maybe through other floors / buildings.
                localFailure = walked
            }
            // The route planned above still holds when nothing was walked since (another area: no local walk).
            var route = plan(context, world, field, area, goalArea, goal, options, planned.takeIf { area !== goalArea }, seen)
            // Before moving: a way the walk can't finish, or a long detour to a target of this very map, is the agent's call.
            // A field move needed on this very map: the local failure knows where to use it from and what the party
            // lacks; keep it over the less precise cross-map one (and over a detour: it opens the short way).
            // So is a walk that found no way left once the game refused steps the map allows (only the walk knows them).
            val precise = localFailure?.takeIf { precise(it.failure) || it.refusals.isNotEmpty() || onGoal(it.failure, goal.target) }
            if (taken.isEmpty() && route != null) {
                // Before the first step, the way planned is weighed against the shortest one ([chooseWay]): a detour
                // only the walk's own costs make is replaced by the shorter way, one the agent's bans make is its call
                // (NOTES race: `go_to Route 26` with avoid_trainers from Route 27 refused as 19 warps through Kanto,
                // past Tohjo Falls' two), and so is a loop across the region to a target of this very map.
                fun planBy(mode: ByLength): WorldRouter.WorldRoute? {
                    val before = seen.byLength
                    seen.byLength = mode
                    return plan(context, world, field, area, goalArea, goal, options, seen = seen).also { seen.byLength = before }
                }
                when (val choice = chooseWay(route, local = goal.zone == field.mapId, options, ::planBy)) {
                    is WayChoice.Go -> {
                        route = choice.route
                        seen.byLength = choice.byLength
                        choice.note?.let { notes += it }
                    }
                    is WayChoice.AvoidanceDetour ->
                        return Trip(precise ?: avoidanceDetour(context, field, area, goal, choice.long, choice.short, options, seen), taken, triggers)
                    is WayChoice.LocalLoop -> return Trip(precise ?: localLoop(context, field, goal, choice.route), taken, triggers)
                }
            }
            val link = route?.links?.firstOrNull()
                // Only Strength boulders in the way: the walk on each map pushes them (its own push planning).
                ?: pushedThrough(context, world, field, area, goalArea, goal, options, seen)
                ?: return Trip(
                    precise
                        ?: elevatorFloor(context, world, field)
                        ?: blocked(context, world, field, area, goalArea, goal, options, seen) ?: localFailure
                        ?: MovePlans.Walk.NoRoute(RouteFailure.Unreachable, "no way to ${goal.target.id} from ${field.x},${field.y} (${field.mapName})"),
                    taken, triggers,
                )
            // A warp this trip already took to the same map: the way leads back and forth between the same maps (a
            // lift's way out leads where the lift was sent: taken again to another floor, it isn't the same way).
            taken.firstOrNull { it.link.zone == link.zone && it.link.x == link.x && it.link.y == link.y && it.arrival.mapId == link.targetZone }?.let { again ->
                return Trip(oscillation(context, field, goal, link, again), taken, triggers)
            }
            // What the player found on this map, kept for the plans made from the next ones.
            seen.remember(area, MovePlans.overlay(context, field, emptySet()))
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
        return Trip(MovePlans.Walk.Stuck("still not there after ${taken.size} warps, the most one go_to takes (a safety bound): ${describe(taken)}"), taken, emptySet())
    }

    /**
     * What a trip found on the maps it went through: each area's live overlay as the player last saw it (the game
     * keeps a map's people where they were when the player comes back within the trip; boulders and item balls too).
     */
    class Seen {
        private val overlays = HashMap<Area, Overlay>()

        /**
         * Set once the way weighed by its soft costs turned out to be a detour ([chooseWay]): the rest of the trip is
         * planned by length (the turns still counted), without the soft costs ([ByLength.WEIGHTS]) or also without the
         * bans the agent asked for ([ByLength.WEIGHTS_AND_BANS], only with [AvoidDetour.SHORT_WAY]), see [travel].
         */
        var byLength: ByLength? = null

        /**
         * The routes planned from where this pass of the trip starts ([worldRoute]): the ice check of the bicycle, the
         * progress and the plan ask for the same one ([forgetRoutes] at each pass, and whenever a map is remembered).
         */
        private val routes = HashMap<RouteKey, WorldRouter.WorldRoute?>()

        /** The route of [key] planned this pass, else [plan]'s, kept. */
        fun route(key: RouteKey, plan: () -> WorldRouter.WorldRoute?): WorldRouter.WorldRoute? =
            if (key in routes) routes[key] else plan().also { routes[key] = it }

        /** Forgets the routes planned so far (the player moved, or a map was seen anew). */
        fun forgetRoutes() = routes.clear()

        /** Keeps [overlay], the live state of [area] read now. */
        fun remember(area: Area, overlay: Overlay) {
            overlays[area] = overlay
            routes.clear()
        }

        /** The live state of [area] as last seen on this trip, or null when the trip hasn't been there. */
        fun overlayOf(area: Area): Overlay? = overlays[area]
    }

    /** How a trip plans by length once the weighed way is a detour ([Seen.byLength], [chooseWay]). */
    enum class ByLength {
        /** Without the soft costs (wild Pokémon, trainers' battles): the bans the agent asked for still hold. */
        WEIGHTS,

        /**
         * Without the bans of avoid_trainers / avoid_tall_grass either: the shortest way, the yardstick of [chooseWay],
         * and the way taken only with [AvoidDetour.SHORT_WAY].
         */
        WEIGHTS_AND_BANS,
    }

    /** What makes two routes of one pass the same ([Seen.route]): where from, how, to what. */
    data class RouteKey(
        val mapId: Int,
        val x: Int,
        val y: Int,
        val height: Int,
        val movement: dev.kotlinds.pokemonclient.state.MovementMode,
        val goal: Goal,
        val options: MoveOptions,
        val crossScenes: Boolean,
        val otherPeople: Boolean,
        val byLength: ByLength?,
    )

    /**
     * What go_to does with the way planned before the first step ([travel]): go along a route (possibly another one,
     * said in a note), or refuse before moving, the agent deciding.
     */
    internal sealed interface WayChoice {
        /** Go along [route]; the rest of the trip planned with [byLength] ([Seen.byLength]); [note] told with the answer. */
        data class Go(val route: WorldRouter.WorldRoute, val byLength: ByLength? = null, val note: String? = null) : WayChoice

        /**
         * Refused ([AvoidDetour.REFUSE]): the way around the bans the agent asked for ([long]) is a detour ([isDetour])
         * next to the shortest way ([short]), which goes through them.
         */
        data class AvoidanceDetour(val long: WorldRouter.WorldRoute, val short: WorldRouter.WorldRoute) : WayChoice

        /** Refused ([LocalDetour.REFUSE]): a target of the player's own map reached only by [route], a loop across the region. */
        data class LocalLoop(val route: WorldRouter.WorldRoute) : WayChoice
    }

    /**
     * Nathan's rule for the way planned before the first step ([requested]: with the agent's bans and the library's
     * soft costs). Its length is compared with the shortest way without any of them ([planBy] with
     * [ByLength.WEIGHTS_AND_BANS]), in steps ([WorldRouter.WorldRoute.tiles]: the estimated tiles walked of the whole
     * route, a warp counting one; not the warps, a gatehouse is two warps and a few steps):
     * - not a detour ([isDetour]): go along it, however long (a long trip is legitimate: progress is reported, battles
     *   and scenes stop the walk);
     * - a detour that only the soft costs make (planned by length with the bans kept, [ByLength.WEIGHTS], it isn't one
     *   any more): take that shorter way, said so ([BY_LENGTH]);
     * - a detour the agent's bans make: refused with both ways ([WayChoice.AvoidanceDetour]), or the shortest way taken
     *   with [AvoidDetour.SHORT_WAY] ([SHORT_WAY]).
     * Then, a target of the player's own map ([local]) reached through more than [LOCAL_DETOUR_WARPS] warps is a loop
     * across the region that no shorter way replaces (the ratio can't see it): refused ([WayChoice.LocalLoop]) unless
     * [LocalDetour.GO].
     */
    internal fun chooseWay(
        requested: WorldRouter.WorldRoute,
        local: Boolean,
        options: MoveOptions,
        planBy: (ByLength) -> WorldRouter.WorldRoute?,
    ): WayChoice {
        val chosen = byRatio(requested, options, planBy)
        if (chosen !is WayChoice.Go) return chosen
        if (local && chosen.route.links.size > LOCAL_DETOUR_WARPS && options.onLocalDetour == LocalDetour.REFUSE) return WayChoice.LocalLoop(chosen.route)
        return chosen
    }

    /** The first part of [chooseWay]: [requested] against the shortest way. */
    private fun byRatio(requested: WorldRouter.WorldRoute, options: MoveOptions, planBy: (ByLength) -> WorldRouter.WorldRoute?): WayChoice {
        // Shorter than the least extra a detour has: nothing to compare (and no second search).
        val steps = requested.tiles ?: return WayChoice.Go(requested)
        if (steps < DETOUR_MIN_EXTRA) return WayChoice.Go(requested)
        val shortest = planBy(ByLength.WEIGHTS_AND_BANS) ?: return WayChoice.Go(requested)
        if (!isDetour(requested, shortest)) return WayChoice.Go(requested)
        val bans = options.avoidTrainers || options.avoidTallGrass
        // The bans kept, the soft costs dropped: without bans, that is the shortest way itself.
        val kept = if (bans) planBy(ByLength.WEIGHTS) else shortest
        if (kept != null && !isDetour(kept, shortest)) return WayChoice.Go(kept, ByLength.WEIGHTS, "$BY_LENGTH (${kept.tiles} steps instead of $steps)")
        if (options.onAvoidDetour == AvoidDetour.SHORT_WAY) {
            return WayChoice.Go(shortest, ByLength.WEIGHTS_AND_BANS, "$SHORT_WAY (${shortest.tiles} steps instead of ${(kept ?: requested).tiles})")
        }
        return WayChoice.AvoidanceDetour(kept ?: requested, shortest)
    }

    /**
     * True when [route] is a detour next to [shortest]: more than [DETOUR_RATIO] times its steps and at least
     * [DETOUR_MIN_EXTRA] steps more (a few steps round a trainer on a short walk are no detour, whatever the ratio).
     */
    internal fun isDetour(route: WorldRouter.WorldRoute, shortest: WorldRouter.WorldRoute): Boolean {
        val long = route.tiles ?: return false
        val short = shortest.tiles ?: return false
        return long > DETOUR_RATIO * short && long - short >= DETOUR_MIN_EXTRA
    }

    /**
     * The refusal of a way whose detour around what the agent asked to avoid ([long]) is more than twice the steps of
     * the short way through it ([short]): the agent decides between the two ([RouteFailure.LongDetour] with its
     * [RouteFailure.ShortWay]: how many trainers' sight and tall grass tiles the short way crosses).
     */
    private fun avoidanceDetour(
        context: PlanContext, field: FieldState, area: Area, goal: Goal, long: WorldRouter.WorldRoute, short: WorldRouter.WorldRoute,
        options: MoveOptions, seen: Seen,
    ): MovePlans.Walk.NoRoute {
        val flags = context.state().eventFlags
        val live = MovePlans.overlay(context, field, emptySet())
        var trainers = 0
        var grass = 0
        short.places.groupBy { it.area }.forEach { (a, places) ->
            val tiles = places.map { it.node.x to it.node.y }.toSet()
            if (options.avoidTrainers) trainers += Pathfinder(a, if (a === area) live else otherOverlay(a, seen, flags)).trainersWatching(tiles)
            if (options.avoidTallGrass) grass += tiles.count { (x, y) -> a.tile(x, y)?.kind == TileKind.TallGrass }
        }
        val way = RouteFailure.ShortWay(short.links.size, trainers.takeIf { options.avoidTrainers }, grass.takeIf { options.avoidTallGrass }, short.tiles)
        val crosses = listOfNotNull(way.trainers?.let { "the sight of $it trainer(s)" }, way.tallGrass?.let { "$it tall grass tile(s)" }).joinToString(" and ")
        val maps = wayMaps(field, long)
        return MovePlans.Walk.NoRoute(
            RouteFailure.LongDetour(long.links.size, maps, way, long.tiles),
            "${describeTarget(goal)}: the way around what you asked to avoid takes ${long.tiles} steps through ${long.links.size} warps " +
                "(${shownNames(context, maps)}), more than twice the short way's ${short.tiles} steps (${way.links} warp(s)), which crosses " +
                "$crosses: neither is taken by itself",
        )
    }

    /**
     * The refusal of taking [link] again ([again]: when it was taken first): the trip would go round in circles. Says
     * when it was taken; the hint, that the way on is closed by something the plan didn't know.
     */
    private fun oscillation(context: PlanContext, field: FieldState, goal: Goal, link: ZoneLink, again: Hop): MovePlans.Walk.NoRoute =
        MovePlans.Walk.NoRoute(
            RouteFailure.Oscillation(link),
            "no way on to ${goal.target.id} from ${field.x},${field.y} (${field.mapName}): the way found from here takes back " +
                "${link.id} at ${link.x},${link.y} (${context.game.mapName(link.zone)}), already taken on this trip (${again.through.describe(again.arrival)}), " +
                "so it would go back and forth between the same maps",
        )

    /**
     * After [hop], the ride of a lift that goes by itself ([ElevatorOperator.Shuttle]: entering its room starts it):
     * its messages (where it goes, that it got there) are read, each one checked on screen (never a blind press), until
     * the player can walk out. Null when there was nothing to read or it ended in the field; else the state it stopped
     * on (another screen than a message).
     */
    private fun rideElevator(context: PlanContext, world: WorldSource, hop: Hop): GameState? {
        if (world.elevatorOf(hop.arrival.mapId)?.operator != ElevatorOperator.Shuttle) return null
        val state = context.navigator.settle()
        if (state.screen is Screen.Overworld) return null
        val ridden = context.navigator.advanceUntil(maxPresses = ELEVATOR_MESSAGES) { it.screen is Screen.Overworld && it.screen.awaiting == Awaiting.INPUT }
        return (ridden as? Step.Failed)?.let { context.state() }
    }

    /**
     * The refusal of a way on that goes through the exit of the lift the player is in when its floor is chosen (an
     * attendant, a panel, a menu: [RouteFailure.ElevatorFloor]), naming who to ask; null elsewhere. NOTES race: Codex
     * in the Goldenrod Dept. Store's lift tried `interact sign:0` (refused, the attendant is person:0, nothing said so).
     */
    private fun elevatorFloor(context: PlanContext, world: WorldSource, field: FieldState): MovePlans.Walk.NoRoute? {
        val elevator = world.elevatorOf(field.mapId)?.takeIf { it.operator != ElevatorOperator.Shuttle } ?: return null
        val exit = elevator.exitWarps.firstOrNull() ?: return null
        val floors = elevator.stops.map { it.zone }.distinct()
        val names = floors.joinToString(", ") { context.game.mapName(it).toString() }
        return MovePlans.Walk.NoRoute(
            RouteFailure.ElevatorFloor(elevator.operator, exit, floors),
            "${field.mapName} is a lift: its way out (warp:$exit) leads to the floor it was sent to ($names), which go_to doesn't choose",
        )
    }

    /**
     * True when the way from where the player stands to [goal] slides on ice on the player's map, up to its first link
     * ([BikeRide.crossesIce] of the walk there): the bicycle doesn't steer on it (NOTES race, Codex in the Ice Path on
     * the bicycle: "the game refused 0 steps", fine once off it). While destinations are hidden ([seen] null, the walk
     * stays on this map), the walk to [goal] itself.
     */
    internal fun crossesIce(context: PlanContext, world: WorldSource, goal: Goal, options: MoveOptions, seen: Seen?): Boolean {
        val field = context.state().field ?: return false
        val area = world.areaOf(field.mapId) ?: return false
        val goalArea = world.areaOf(goal.zone) ?: return false
        val link = if (seen == null) null else (worldRoute(context, world, field, area, goalArea, goal, options, seen = seen) ?: return false).links.firstOrNull()
        val pathfinder = Pathfinder(area, MovePlans.overlay(context, field, emptySet()))
        val goalTiles = if (link != null) setOf(link.x to link.y) else MovePlans.goalTiles(area, goal.target)
        val isGoal: (Node) -> Boolean =
            if (link != null) { node -> node.x == link.x && node.y == link.y } else goal.target.isGoal ?: { node -> (node.x to node.y) in goalTiles }
        val walk = pathfinder.route(pathfinder.nodeOf(field), worldRouteOptions(context, field, options), goalTiles, isGoal = isGoal)
        return walk is Pathfinder.Result.Found && BikeRide.crossesIce(area, walk.route)
    }

    /**
     * The trip while destinations are hidden ([ActionSettings.hideDestinations]): one walk on the player's map (kept on
     * it by [MovePlans.overlay]), never a route through warps or other maps, even to reach a place of this map (the
     * way round would reveal where the warps lead). A warp or hole asked for is taken: that is how the agent explores.
     */
    private fun hiddenTravel(context: PlanContext, world: WorldSource, goal: Goal, options: MoveOptions, notes: MutableList<String>): Trip {
        // Not on the bicycle for a way over ice (the same check as a trip across maps: [crossesIce]).
        BikeRide.mount(context, options) { crossesIce(context, world, goal, options, seen = null) }?.let { if (it !in notes) notes += it }
        val state = context.navigator.settle()
        val field = state.field
        if (field == null || state.screen !is Screen.Overworld) return Trip(MovePlans.Walk.Interrupted(state, 0), emptyList(), emptySet())
        val triggers = MovePlans.activeTriggers(context, field)
        return Trip(MovePlans.walkTo(context, goal.target, options), emptyList(), triggers)
    }

    /**
     * True when [failure] is someone standing on [target]'s own tile (a story person on the warp asked for): no way
     * round through other floors reaches a tile someone stands on, the local diagnosis says who.
     */
    private fun onGoal(failure: RouteFailure, target: MovePlans.Target): Boolean =
        failure is RouteFailure.BlockedByPerson && !target.adjacent && failure.x == target.x && failure.y == target.y

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
        seen: Seen? = null,
    ): WorldRouter.WorldRoute? =
        planned ?: worldRoute(context, world, field, area, goalArea, goal, options, seen = seen)
            // The only way out crosses a scene trigger (Elm's aide by the lab's door): take it, the scene will start
            // on the way like for a target on this map (the walk to the link allows triggers the same way).
            ?: worldRoute(context, world, field, area, goalArea, goal, options, crossScenes = true, seen = seen)
            // The people of the maps not seen yet are a guess (where the map places them, by flags read away from
            // them: an entry script may move them, the day may hide them): they never make "no way" before the first
            // step when the maps' obstacles leave one. Go, and plan again on arrival with what is really there.
            ?: worldRoute(context, world, field, area, goalArea, goal, options, seen = seen, otherPeople = false)
            ?: worldRoute(context, world, field, area, goalArea, goal, options, crossScenes = true, seen = seen, otherPeople = false)

    /**
     * The refusal of [route], the only way to a destination of the player's own map (a warp, a person, a tile of it),
     * a loop through other maps ([WayChoice.LocalLoop]): it looks next door, but rocks or walls are in between, and
     * the way round crosses the region (NOTES: `go_to warp:1` on Route 20, the Seafoam Islands entrance 57 tiles east
     * on a beach walled off by rocks, set off west towards Route 21 for a 17-warp loop through Kanto, the entrance next
     * to the player (warp:0) being the way in the agent wanted). Taken with [LocalDetour.GO].
     */
    private fun localLoop(context: PlanContext, field: FieldState, goal: Goal, route: WorldRouter.WorldRoute): MovePlans.Walk.NoRoute {
        val links = route.links.size
        val maps = wayMaps(field, route)
        return MovePlans.Walk.NoRoute(
            RouteFailure.LongDetour(links, maps, steps = route.tiles),
            "${describeTarget(goal)} isn't reachable from ${field.x},${field.y} on ${field.mapName} by walking or surfing (rocks, walls or " +
                "heights in between); the only way found is a loop of ${route.tiles} steps through $links warps (${shownNames(context, maps)}), " +
                "not taken by itself (on_local_detour: go takes it)",
        )
    }

    /** The target of [goal] for a refusal: its id, and its tile when the id doesn't say it ("warp:1 at 5,0"). */
    private fun describeTarget(goal: Goal): String {
        val target = goal.target
        val at = if (target.x != null && target.y != null && target.id != "${target.x},${target.y}") " at ${target.x},${target.y}" else ""
        return "${target.id}$at"
    }

    /** The maps [route] goes through after the player's, in order (zone ids, each once in a row). */
    private fun wayMaps(field: FieldState, route: WorldRouter.WorldRoute): List<Int> =
        route.places.mapNotNull { it.zone }.fold(mutableListOf()) { acc, z -> if (acc.lastOrNull() != z && z != field.mapId) acc += z; acc }

    /** The names of [maps] for a refusal: both ends of the way (where it sets off, the side the destination is reached from). */
    private fun shownNames(context: PlanContext, maps: List<Int>): String {
        val names = maps.map { context.game.mapName(it).toString() }.fold(mutableListOf<String>()) { acc, n -> if (acc.lastOrNull() != n) acc += n; acc }
        return if (names.size <= MAX_DETOUR_NAMES) names.joinToString(" → ")
        else (names.take(MAX_DETOUR_NAMES / 2) + "…" + names.takeLast(MAX_DETOUR_NAMES / 2)).joinToString(" → ")
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
        seen: Seen? = null,
        walled: Map<Area, Set<Pair<Int, Int>>> = emptyMap(),
        otherPeople: Boolean = true,
    ): WorldRouter.WorldRoute? {
        fun plan(): WorldRouter.WorldRoute? {
            val overlay = MovePlans.overlay(context, field, emptySet()).let { if (crossScenes) it.copy(activeTriggers = emptySet()) else it }
            val flags = context.state().eventFlags
            val router = WorldRouter(world) { _, a -> walledOff(if (a === area) overlay else otherOverlay(a, seen, flags, otherPeople), walled[a]) }
            val start = Pathfinder(area).nodeOf(field)
            val goalTiles = MovePlans.goalTiles(goalArea, goal.target)
            val enterable = if (goal.target.adjacent) emptySet() else goalTiles
            val weighed = worldRouteOptions(context, field, options)
            // By length (Seen.byLength): without the soft costs, and the bans only when the agent asked for it.
            val routeOptions = when (seen?.byLength) {
                null -> weighed
                ByLength.WEIGHTS -> weighed.copy(weights = StepWeights.NONE)
                ByLength.WEIGHTS_AND_BANS -> weighed.copy(weights = StepWeights.NONE, avoidTrainers = false, avoidTallGrass = false)
            }
            return router.route(
                field.mapId, start, routeOptions,
                goalTiles = { a -> if (a === goalArea) enterable else emptySet() }, relaxed = relaxed, ignorePeople = relaxed,
            ) { place -> place.area === goalArea && (goal.target.isGoal?.invoke(place.node) ?: ((place.node.x to place.node.y) in goalTiles)) }
        }
        // The diagnosis' searches (relaxed, walls added) are its own; the plain routes of one pass are planned once.
        if (seen == null || relaxed || walled.isNotEmpty()) return plan()
        return seen.route(RouteKey(field.mapId, field.x, field.y, field.height, field.movement, goal, options, crossScenes, otherPeople, seen.byLength), ::plan)
    }

    /**
     * What a route knows of [area], a map the player isn't on: its live state as this trip last saw it ([Seen]), else
     * what its map data and the save's event [flags] tell ([WorldRouter.knownOverlay]: obstacles, the people there
     * now), or with [people] false only its obstacles ([WorldRouter.staticOverlay]: the guess of the people left out).
     */
    private fun otherOverlay(area: Area, seen: Seen?, flags: dev.kotlinds.pokemonclient.state.EventFlags?, people: Boolean = true): Overlay =
        seen?.overlayOf(area) ?: if (people) WorldRouter.knownOverlay(area, flags) else WorldRouter.staticOverlay(area)

    /**
     * A route across maps from [field] (on [area]) for the hints that look at other maps ([withPuzzle],
     * [FlyHints.nearestFlyable]), with the same view of the other maps as go_to's own routes ([plan]): the people
     * the save places there, and when they close every way, only the maps' obstacles. [route] searches with a router.
     */
    internal fun routeAcross(context: PlanContext, world: WorldSource, field: FieldState, area: Area, route: (WorldRouter) -> WorldRouter.WorldRoute?): WorldRouter.WorldRoute? {
        val overlay = MovePlans.overlay(context, field, emptySet())
        val flags = context.state().eventFlags
        return listOf(true, false).firstNotNullOfOrNull { people -> route(WorldRouter(world) { _, a -> if (a === area) overlay else otherOverlay(a, null, flags, people) }) }
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
     * obstacles ([crossing]), with the map it's on, and what the party can do about it ([FieldMoveWalk.access]: a
     * boulder on another floor while the party has Strength isn't "it needs Strength"). Only Strength boulders whose
     * pushes open nothing on the way: no way at all, said with them. Null when even that finds nothing.
     */
    private fun blocked(context: PlanContext, world: WorldSource, field: FieldState, area: Area, goalArea: Area, goal: Goal, options: MoveOptions, seen: Seen? = null): MovePlans.Walk.NoRoute? {
        if (!options.acceptOneWay && worldRoute(context, world, field, area, goalArea, goal, options.copy(acceptOneWay = true), seen = seen) != null) {
            return MovePlans.Walk.NoRoute(RouteFailure.OnlyOneWay, "no way to ${goal.target.id} from ${field.x},${field.y} without jumping down ledges")
        }
        val crossing = crossing(context, world, field, area, goalArea, goal, options, seen)
        val boulders = MovePlans.boulderNotes(crossing.stuck, crossing.unproven)
        val blocker = crossing.blockers.firstOrNull()
            // Only stuck boulders on the way: no way at all, said with them (the agent shouldn't try to push them).
            ?: return if (crossing.stuck.isEmpty()) null
            else MovePlans.Walk.NoRoute(RouteFailure.Unreachable, "no way to ${goal.target.id} from ${field.x},${field.y}$boulders")
        val place = blocker.place
        val route = blocker.route
        val where = if (place.area === area) "" else " on ${place.zone?.let { context.game.mapName(it) } ?: "another floor"}"
        val via = if (route.links.isEmpty()) "" else " (the way: " +
            route.links.joinToString(", ") { it.id + " → " + context.game.mapName(it.targetZone) } + ")"
        return MovePlans.Walk.NoRoute(
            blocker.failure, "no way to ${goal.target.id} from ${field.x},${field.y}: blocked at ${place.node.x},${place.node.y}$where$via$boulders",
            access = FieldMoveWalk.access(context, context.state()),
        )
    }

    /** An obstacle ([failure], at [place]) of [route], the way to a goal that crosses obstacles. */
    private class Blocker(val route: WorldRouter.WorldRoute, val place: WorldRouter.Place, val failure: RouteFailure)

    /**
     * The way to a goal that crosses obstacles: its obstacles in order ([blockers], each with its route and map), the
     * Strength boulders left out of it ([stuck], "x,y on map") because pushing them opens nothing, and those kept
     * although the proof gave up at its bound ([unproven]: maybe a way, not proven).
     */
    private class Crossing(val blockers: List<Blocker>, val stuck: List<String>, val unproven: List<String> = emptyList())

    /**
     * The route to [goal] that crosses obstacles (field moves, people) and its obstacles, the blockers of the other
     * maps being those the plan knew (people where the save places them, what the trip saw). A Strength boulder only
     * counts as a way when pushing it opens one ([pushOpens], the one proof [PushPlanner.opensWay]), on every floor of
     * the route: one whose pushes open nothing (the dead-end boulder at 9,25 west of Victory Road 2F) becomes a wall
     * and the crossing is searched again without it, at most [MAX_STUCK_BOULDERS] times. A proof that gave up at its
     * bound proves nothing either way: the boulder stays a (not proven) Strength way, said so.
     */
    private fun crossing(context: PlanContext, world: WorldSource, field: FieldState, area: Area, goalArea: Area, goal: Goal, options: MoveOptions, seen: Seen?): Crossing {
        val routeOptions = worldRouteOptions(context, field, options)
        val overlay = MovePlans.overlay(context, field, emptySet())
        val flags = context.state().eventFlags
        val walled = HashMap<Area, Set<Pair<Int, Int>>>()
        val stuck = mutableListOf<String>()
        attempts@ for (attempt in 0..MAX_STUCK_BOULDERS) {
            // Scene triggers don't block the way (a scene starts, then the walk goes on): crossing them keeps the search
            // from inventing a detour over water around a trigger (NOTES-run P6: "needs Surf" next to Violet's bridge).
            val route = worldRoute(context, world, field, area, goalArea, goal, options, relaxed = true, crossScenes = true, seen = seen, walled = walled) ?: break
            val overlays = HashMap<Area, Overlay>()
            val found = mutableListOf<Blocker>()
            val unproven = mutableListOf<String>()
            val proofs = HashMap<Int, PushPlanner.PushProof>()
            for ((index, place) in route.places.withIndex()) {
                val placeOverlay = overlays.getOrPut(place.area) {
                    walledOff(if (place.area === area) overlay else otherOverlay(place.area, seen, flags), walled[place.area])
                }
                val failure = Pathfinder(place.area, placeOverlay).blockerAt(place.node.x, place.node.y, routeOptions) ?: continue
                val label = "${place.node.x},${place.node.y}" + if (place.area === area) "" else " on ${place.zone?.let { context.game.mapName(it) } ?: "another floor"}"
                // One proof per stretch of the route on a floor (its boulders are pushed by the same plan).
                if (failure is RouteFailure.NeedsFieldMove && failure.move == FieldMoveKind.STRENGTH) {
                    when (proofs.getOrPut(stretch(route, index).first) { pushOpens(route, stretch(route, index), placeOverlay, routeOptions) }) {
                        PushPlanner.PushProof.NoPlan -> {
                            walled[place.area] = walled[place.area].orEmpty() + (place.node.x to place.node.y)
                            stuck += label
                            continue@attempts
                        }
                        PushPlanner.PushProof.OverBound -> unproven += label
                        is PushPlanner.PushProof.Opens -> Unit
                    }
                }
                found += Blocker(route, place, failure)
            }
            return Crossing(found, stuck, unproven)
        }
        return Crossing(emptyList(), stuck)
    }

    /**
     * The link to walk to next when only Strength boulders close the way across maps, the walks solve the movement
     * puzzles ([ActionSettings.solvePuzzles]) and the party can use Strength: the routes across maps see boulders where
     * they stand, the walk on each map plans the pushes ([MovePlans.walkTo]: the boulders of this map on the way to the
     * link, the next map's once there). The boulders are those of the [crossing], each proven to open the way
     * ([PushPlanner.opensWay]); a dead-end one is a wall there, so the link taken never leads to it. Victory Road (NOTES
     * race, checked end to end on the bench): the 1F boulder at 43,52 before the ladder at 19,7, and on 2F the one at
     * 50,28 before the ladder at 56,21; without this, go_to "Indigo Plateau" from the entrance answered "a boulder
     * blocks the way at 43,52: it needs Strength" with Strength at hand. Null otherwise (the error says what blocks).
     */
    private fun pushedThrough(context: PlanContext, world: WorldSource, field: FieldState, area: Area, goalArea: Area, goal: Goal, options: MoveOptions, seen: Seen?): ZoneLink? {
        if (!context.settings.solvePuzzles) return null
        if (FieldMoveKind.STRENGTH !in FieldMoves.usable(FieldMoveWalk.access(context, context.state()))) return null
        val blockers = crossing(context, world, field, area, goalArea, goal, options, seen).blockers
        if (blockers.isEmpty() || blockers.any { (it.failure as? RouteFailure.NeedsFieldMove)?.move != FieldMoveKind.STRENGTH }) return null
        return blockers.first().route.links.firstOrNull()?.takeIf { world.areaOf(it.zone) === area }
    }

    /** [overlay] with [tiles] (stuck Strength boulders) never entered. */
    private fun walledOff(overlay: Overlay, tiles: Set<Pair<Int, Int>>?): Overlay =
        if (tiles.isNullOrEmpty()) overlay else overlay.copy(forbiddenTiles = overlay.forbiddenTiles + tiles)

    /** The indices of [route]'s places on the same area as its place [index], without leaving it (a stretch on one floor). */
    private fun stretch(route: WorldRouter.WorldRoute, index: Int): IntRange {
        val places = route.places
        val area = places[index].area
        var first = index
        while (first > 0 && places[first - 1].area === area) first--
        var last = index
        while (last < places.size - 1 && places[last + 1].area === area) last++
        return first..last
    }

    /**
     * Whether pushing boulders really opens [stretch] of [route] (a part on one area whose Strength boulders the
     * crossing walks through): the [PushPlanner] proof ([PushPlanner.opensWay]) from where the route enters that area
     * to where it leaves it, with the other field moves that part needs assumed usable, moving only the boulders the
     * stretch crosses (the others stay where they are).
     */
    private fun pushOpens(route: WorldRouter.WorldRoute, stretch: IntRange, overlay: Overlay, options: RouteOptions): PushPlanner.PushProof {
        val places = route.places
        val area = places[stretch.first].area
        val pathfinder = Pathfinder(area, overlay)
        val blockers = stretch.map { i -> places[i].node to pathfinder.blockerAt(places[i].node.x, places[i].node.y, options) as? RouteFailure.NeedsFieldMove }
        val moves = blockers.mapNotNull { it.second?.move }.toSet()
        val crossed = blockers.filter { it.second?.move == FieldMoveKind.STRENGTH }.map { it.first.x to it.first.y }.toSet()
        val end = places[stretch.last].node
        return PushPlanner(area, overlay).opensWay(places[stretch.first].node, options, moves, setOf(end.x to end.y), moving = crossed) { it.x == end.x && it.y == end.y }
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

    /** A tile written as `x,y` in `target`: refused, x and y take it. */
    private val COORDINATES = Regex("""\s*-?\d+\s*,\s*-?\d+\s*""")

    /** `frontier` skips the ways out this close to the player (the one they just came through). */
    private const val FRONTIER_SKIP = 2

    /** The note of a trip that dropped the agent's bans on its explicit opt-in ([AvoidDetour.SHORT_WAY]), see [chooseWay]. */
    internal const val SHORT_WAY = "the way around what avoid_trainers / avoid_tall_grass avoid is more than twice the shortest way: " +
        "took the short way as on_avoid_detour short_way asks, avoiding them on each map where it could (those in the way may stop you)"

    /** The note of a trip planned by length without the soft costs (the agent's bans kept), see [chooseWay]. */
    internal const val BY_LENGTH = "the way around the wild Pokémon's areas and the trainers' sight (what a walk weighs by itself) " +
        "is more than twice the shortest way: took the shorter way, still avoiding what you asked to avoid on each map"

    private const val RODE = BikeRide.RODE
    private const val NO_CYCLING = BikeRide.NO_CYCLING

    /** An ambiguous name lists at most this many maps. */
    private const val MAX_SUGGESTED_NAMES = 12

    /** A lift's ride shows at most this many messages (where it goes, the ride, the arrival). */
    private const val ELEVATOR_MESSAGES = 6

    /**
     * At most this many warps / falls per `go_to`: only a guard against a trip that would never end (each pass plans
     * again from where the player arrived), far above any real trip (Cerulean City to Ecruteak City on foot: 16
     * warps). A long trip is never refused for its length; going back and forth is stopped by the oscillation check.
     */
    private const val MAX_TRIP_LINKS = 100

    /** Stuck boulders [blocked] leaves out at most before giving up (each one is a new crossing search). */
    private const val MAX_STUCK_BOULDERS = 4

    /**
     * A way is a detour ([isDetour]) when it takes more than this many times the steps of the shortest way... Twice:
     * the Route 26 case (19 warps through Kanto instead of the two of Tohjo Falls) is many times longer, while going
     * round a trainer or a patch of grass on the way stays well under it.
     */
    internal const val DETOUR_RATIO = 2

    /**
     * ...and at least this many steps more: on a short way, a few steps round a trainer double it without being a
     * detour worth stopping for (a screen of the overworld is about 30 tiles across).
     */
    internal const val DETOUR_MIN_EXTRA = 30

    /**
     * A destination of the player's own map reached through more warps than this is a loop across the region
     * ([chooseWay], [LocalDetour]). Counted in warps rather than against the straight-line distance: a dungeon's way
     * between two parts of a floor (a ladder down, a corridor, a ladder up) is often several times the straight line
     * while staying within a few warps, whereas a loop across the region crosses towns, routes and their gatehouses
     * (Route 20's beach: 17 warps).
     */
    private const val LOCAL_DETOUR_WARPS = 8

    /** A refused detour names at most this many maps of its way. */
    private const val MAX_DETOUR_NAMES = 6
}
