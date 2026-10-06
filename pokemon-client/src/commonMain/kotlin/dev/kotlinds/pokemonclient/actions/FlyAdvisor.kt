package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.world.StepWeights
import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.state.FlyDestination
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.FieldMoveAccess
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.FieldMoves
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.WorldRouter

/**
 * Whether Fly gets the player much closer to a place than walking: the visited fly destination ([FlyDestination])
 * that lands nearest to it, on foot, compared with the walk from where the player stands. An agent that only knows
 * the towns it visited may not think of it (NOTES: from Pallet Town to Mt. Silver it walked through Viridian City and
 * Route 22, while Victory Road's fly point lands on Route 26, next to the League gate that opens on Route 28).
 *
 * Told with the story goals that name a place, and by `go_to` before a long walk to another map ([WorldTravel]).
 * Distances are tiles walked on the static maps (field moves the party can use, ledges either way): an estimate.
 * Answers are kept per place, map of the player and visited destinations: the routes cost a search of the world each.
 */
class FlyAdvisor(private val game: PokemonGame) {

    /**
     * Flying to [destination] lands about [fromLanding] tiles from the place, against about [onFoot] tiles of walk
     * from the player (null: no walking way found).
     */
    data class Suggestion(val destination: FlyDestination, val fromLanding: Int, val onFoot: Int?) {
        /** "Victory Road (fly:30) lands ~60 tiles from it" / "Mahogany Town (fly:87) lands on it". */
        val landing: String
            get() = "${destination.name} (${destination.id}) lands " + if (fromLanding == 0) "on it" else "~$fromLanding tiles from it"

        /** "nearest fly: Victory Road (fly:30) lands ~60 tiles from it (~310 on foot from here)". */
        val text: String
            get() = "nearest fly: $landing" + (onFoot?.let { " (~$it on foot from here)" } ?: "")
    }

    private data class Key(
        val target: Int,
        val from: Int,
        val destinations: List<String>,
        val fieldMoves: Set<FieldMoveKind>,
        val onFoot: Int?,
        val walkKnown: Boolean,
    )

    private val cache = LinkedHashMap<Key, Suggestion?>()

    /**
     * The fly destination to use for [targetZone], when the party can fly (the game's Fly rule) and flying there then walking
     * is much shorter than walking from here (less than half, a flight counting as [FLIGHT_TILES] tiles), or there is
     * no way on foot. The walk from here is searched.
     */
    fun suggest(state: GameState, targetZone: Int): Suggestion? = suggest(state, targetZone, onFoot = null, walkKnown = false)

    /** The same with the walk from here already known (a `go_to` planned it): [onFoot] tiles, null when there is none. */
    fun suggest(state: GameState, targetZone: Int, onFoot: Int?): Suggestion? = suggest(state, targetZone, onFoot, walkKnown = true)

    private fun suggest(state: GameState, targetZone: Int, onFoot: Int?, walkKnown: Boolean): Suggestion? {
        val field = state.field ?: return null
        // The party can fly: a Pokémon (not an egg) knows Fly and the badge that allows it is owned (the game's rule).
        val access = FieldMoves.of(state, game::fieldMoveRule)
        if (access[FieldMoveKind.FLY] !is FieldMoveAccess.Usable || field.mapId == targetZone) return null
        val world = game.world ?: return null
        val destinations = reachable(state, field.mapId)
        if (destinations.isEmpty()) return null
        val fieldMoves = FieldMoves.usable(access)
        val key = Key(targetZone, field.mapId, destinations.map { it.id }, fieldMoves, onFoot, walkKnown)
        if (key in cache) return cache[key]
        // Distances in tiles, not in expected time: no encounter weights ([StepWeights.NONE]).
        val options = MovePlans.routeOptions(field, MoveOptions(acceptOneWay = true), fieldMoves, StepWeights.NONE)
        val router = WorldRouter(world)
        val landing = nearestLanding(router, targetZone, destinations, options)
        val suggestion = landing?.let { (destination, tiles) ->
            val walk = if (walkKnown) onFoot else world.areaOf(field.mapId)?.let { area ->
                val start = Pathfinder(area).nodeOf(field)
                router.route(field.mapId, start, options) { it.zone == targetZone }?.tiles
            }
            Suggestion(destination, tiles, walk).takeIf { destination.zone != field.mapId && (walk == null || tiles * 2 + FLIGHT_TILES < walk) }
        }
        if (cache.size >= MAX_CACHED) cache.remove(cache.keys.first())
        cache[key] = suggestion
        return suggestion
    }

    /**
     * The visited destination nearest to [targetZone] on foot (searched from the place outwards, ledges either way),
     * with the tiles from where its map starts: 0 when it lands on that very map.
     */
    private fun nearestLanding(
        router: WorldRouter,
        targetZone: Int,
        destinations: List<FlyDestination>,
        options: dev.kotlinds.pokemonclient.world.RouteOptions,
    ): Pair<FlyDestination, Int>? {
        destinations.firstOrNull { it.zone == targetZone }?.let { return it to 0 }
        val area = game.world?.areaOf(targetZone) ?: return null
        val start = entry(area, targetZone) ?: return null
        val byZone = destinations.associateBy { it.zone }
        val route = router.route(targetZone, start, options) { place -> place.zone in byZone } ?: return null
        val destination = route.end.zone?.let { byZone[it] } ?: return null
        return destination to (route.tiles ?: route.cost)
    }

    /** A walkable tile of [zone] in [area], the closest to the middle of the zone. */
    private fun entry(area: Area, zone: Int): Node? {
        val bounds = area.zoneBounds[zone] ?: intArrayOf(area.originX, area.originY, area.originX + area.width - 1, area.originY + area.height - 1)
        val cx = (bounds[0] + bounds[2]) / 2
        val cy = (bounds[1] + bounds[3]) / 2
        var best: Node? = null
        var bestDistance = Int.MAX_VALUE
        val pathfinder = Pathfinder(area)
        for (y in bounds[1]..bounds[3]) for (x in bounds[0]..bounds[2]) {
            val tile = area.tile(x, y) ?: continue
            if (tile.blocked || (tile.kind != TileKind.Floor && tile.kind != TileKind.TallGrass)) continue
            if (area.zoneBounds.isNotEmpty() && area.zoneAt(x, y) != zone) continue
            val distance = (x - cx) * (x - cx) + (y - cy) * (y - cy)
            if (distance < bestDistance) {
                bestDistance = distance
                best = Node(x, y, pathfinder.levelAt(x, y, tile.heights.firstOrNull() ?: 0))
            }
        }
        return best
    }

    /**
     * The visited destinations Fly can take the player to from [zone]: those of its region, those chosen from any
     * region, and every one once a region hub was visited (`fly` goes through it).
     */
    private fun reachable(state: GameState, zone: Int): List<FlyDestination> {
        val visited = state.player?.flyDestinations.orEmpty()
        if (visited.any { it.regionHub }) return visited
        val world = game.world ?: return visited
        val here = world.regionOf(zone)?.id ?: return visited
        return visited.filter { it.fromAnyRegion || world.regionOf(it.zone)?.id?.let { region -> region == here } != false }
    }

    companion object {
        /** A flight (menus, the animation) weighs like this many tiles walked. */
        const val FLIGHT_TILES = 30

        private const val MAX_CACHED = 64
    }
}
