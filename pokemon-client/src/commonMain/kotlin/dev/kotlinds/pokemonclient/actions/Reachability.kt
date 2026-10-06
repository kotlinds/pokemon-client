package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Blockers
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.FieldMoves
import dev.kotlinds.pokemonclient.world.NeedsMechanism
import dev.kotlinds.pokemonclient.world.Node
import dev.kotlinds.pokemonclient.world.Pathfinder
import dev.kotlinds.pokemonclient.world.PersonTemplate
import dev.kotlinds.pokemonclient.world.RouteFailure
import dev.kotlinds.pokemonclient.world.StepWeights
import dev.kotlinds.pokemonclient.world.WorldLinks
import dev.kotlinds.pokemonclient.world.ZoneLink

/**
 * What reaching one target of the current map (an exit, a person, an item, a sign...) needs besides walking, as the
 * agent reads it next to the target in its view and in the go_to / interact errors: the same words from the same
 * diagnosis ([Pathfinder.diagnose]), so the view and the errors never disagree. Every field is about the current map
 * only (it never tells where an exit leads, destinations hidden or not). Nothing set ([DIRECT]): a walk reaches it.
 */
data class Reachability(
    /** No way on this map (walls, another height level): another warp must be taken first. People never count here. */
    val requiresIntermediateWarp: Boolean = false,
    /** The field moves the way needs and the party can't use by itself (each once, in the order met). */
    val requiresFieldMoves: List<FieldMoveKind> = emptyList(),
    /** The person (or object) standing in the only way: talking to them may move them. */
    val blockedByPerson: FieldObject? = null,
    /** A puzzle in the only way: a closed shutter, or a mechanism left to the agent (a lift, a boulder to push). */
    val blockedByPuzzle: String? = null,
    /**
     * No way back: reached only by jumping down ledges, or (exits) taking it leaves no way back through the warp the
     * player arrives on ([WorldLinks.noWayBack]: a hole, an arrival-only warp, an arrival someone stands on).
     */
    val oneWay: Boolean = false,
) {
    /** True when nothing is needed: a walk reaches it (nothing is added to the target's line). */
    val direct: Boolean get() = this == DIRECT

    /**
     * The set fields as the agent reads them, snake_case like the rest of the MCP (`accept_one_way`...):
     * `requires_intermediate_warp`, `requires_field_moves: [surf, strength]`, `blocked_by_person: person:3 (gym guide)`,
     * `blocked_by_puzzle: ...`, `one_way`. Empty when [direct].
     */
    fun fields(): List<String> = buildList {
        if (requiresIntermediateWarp) add(REQUIRES_INTERMEDIATE_WARP)
        if (requiresFieldMoves.isNotEmpty()) add("$REQUIRES_FIELD_MOVES: [${requiresFieldMoves.joinToString { it.name.lowercase() }}]")
        blockedByPerson?.let { add("$BLOCKED_BY_PERSON: ${MovePlans.objectTargetId(it)} (${it.label})") }
        blockedByPuzzle?.let { add("$BLOCKED_BY_PUZZLE: $it") }
        if (oneWay) add(ONE_WAY)
    }

    /** [fields] on one line, " [field; field]" to append to a target's line, or "" when [direct]. */
    fun suffix(): String = if (direct) "" else " [" + fields().joinToString("; ") + "]"

    companion object {
        val DIRECT = Reachability()

        const val REQUIRES_INTERMEDIATE_WARP = "requires_intermediate_warp"
        const val REQUIRES_FIELD_MOVES = "requires_field_moves"
        const val BLOCKED_BY_PERSON = "blocked_by_person"
        const val BLOCKED_BY_PUZZLE = "blocked_by_puzzle"
        const val ONE_WAY = "one_way"

        /**
         * The reachability a failed route tells ([failure] and [blockers] of [Pathfinder.diagnose]): the one mapping
         * of the view and the errors. Null for a failure that says nothing about this map (unknown start or target).
         */
        fun of(failure: RouteFailure, blockers: Blockers, field: FieldState?): Reachability? {
            fun objectAt(at: Pair<Int, Int>?) = at?.let { (x, y) -> field?.objects?.firstOrNull { it.x == x && it.y == y } }
            return when (failure) {
                RouteFailure.StartUnknown, RouteFailure.TargetUnknown, is RouteFailure.LongDetour -> null
                RouteFailure.OnlyOneWay -> Reachability(oneWay = true)
                RouteFailure.DifferentLevel, RouteFailure.Unreachable -> Reachability(requiresIntermediateWarp = true)
                is NeedsMechanism -> Reachability(blockedByPuzzle = "${failure.mechanism.name.lowercase()} at ${failure.x},${failure.y} (left to you, see field.puzzle)")
                is RouteFailure.NeedsFieldMove, is RouteFailure.BlockedByPerson, is RouteFailure.BlockedByBarrier -> {
                    val moves = blockers.fieldMoves.ifEmpty { listOfNotNull((failure as? RouteFailure.NeedsFieldMove)?.move) }
                    val person = blockers.person ?: (failure as? RouteFailure.BlockedByPerson)?.let { it.x to it.y }
                    val barrier = blockers.barrier ?: (failure as? RouteFailure.BlockedByBarrier)?.let { it.x to it.y }
                    Reachability(
                        requiresFieldMoves = moves,
                        blockedByPerson = objectAt(person),
                        blockedByPuzzle = barrier?.let { b ->
                            val id = field?.puzzle?.barriers?.firstOrNull { g -> g.tiles.any { it.x == b.first && it.y == b.second } }?.id
                            "closed shutter${id?.let { " $it" } ?: ""} at ${b.first},${b.second} (see field.puzzle)"
                        },
                    )
                }
            }
        }
    }
}

/**
 * The reachability of every target of the player's map, computed once for a view of [state] ([AgentView] builds one
 * per state it describes): one search from the player over this map ([Pathfinder.reachable], without and with
 * ledges), then a diagnosis ([Pathfinder.diagnose], the very one of the go_to errors) only for the targets the search
 * didn't reach. Routes stay on the current map ([MovePlans.overlay] `onThisMap`), like go_to while destinations are
 * hidden: what is reported is about this map only.
 *
 * Nothing is reported (every target [Reachability.DIRECT]) where the walks plan what a plain search doesn't model: a
 * map with moving platforms ([dev.kotlinds.pokemonclient.state.PuzzleState.mechanics]) or a mechanism the planner
 * doesn't know ([dev.kotlinds.pokemonclient.state.PuzzleState.unmodeled]).
 */
internal class ReachSurvey(private val game: PokemonGame, private val state: GameState, private val settings: ActionSettings) {
    private val field: FieldState? = state.field
    private val area: Area? = field?.let { game.world?.areaOf(it.mapId) }

    /** False where no search is made (no map, moving platforms, an unmodeled mechanism). */
    private val surveyed: Boolean = field != null && area != null && field.puzzle?.mechanics == null && field.puzzle?.unmodeled == null

    private val access = FieldMoves.of(state, game::fieldMoveRule)
    private val options = field?.let { MovePlans.routeOptions(it, MoveOptions(), FieldMoves.usable(access), StepWeights.NONE) }

    /**
     * The scene triggers active now, as the game read them with the walks' own rule ([Area.sceneTriggerTiles]:
     * placeholders and silent triggers walked like floor), so the survey and go_to agree.
     */
    private val triggers: Set<Pair<Int, Int>> = field?.activeTriggers.orEmpty()

    private val pathfinder: Pathfinder? =
        if (!surveyed) null else Pathfinder(area!!, MovePlans.overlay(area, field!!, emptySet(), triggers, settings.solvePuzzles, onThisMap = true))
    private val start: Node? = field?.let { pathfinder?.nodeOf(it) }

    /** The overlay where puzzles are solved, to name the mechanism a way needs while they are left to the agent. */
    private val solving by lazy { MovePlans.overlay(area, field!!, emptySet(), triggers, solve = true, onThisMap = true) }

    /** Tiles any target may enter (warps, holes, the next map's first tiles): entered, never walked through. */
    private val enterable: Set<Pair<Int, Int>> by lazy {
        val f = field ?: return@lazy emptySet()
        val a = area ?: return@lazy emptySet()
        (a.warps.filter { it.zone == f.mapId }.map { it.x to it.y } + a.triggerWarps.filter { it.zone == f.mapId }.map { it.x to it.y } +
            WorldLinks.connections(a, f.mapId).flatMap { c -> c.tiles.map { (x, y) -> x + c.direction.dx to y + c.direction.dy } }).toSet()
    }

    /** The one search of the view: every node walked to from the player, without ledges, then with them. */
    private val walked: Set<Node> by lazy { flood(allowJumps = false) }
    private val jumped: Set<Node> by lazy { flood(allowJumps = true) }

    /**
     * Only for the targets [walked] and [jumped] miss: the places a diagnosis can reach at all, crossing what it
     * crosses ([crossed]), or climbing any height ([anyHeight]). A target out of both is [RouteFailure.Unreachable]
     * without a diagnosis of its own (each would search the whole map again): the very answer [Pathfinder.diagnose]
     * gives it.
     */
    private val crossed: Set<Pair<Int, Int>> by lazy { flood(allowJumps = true, crossing = true).map { it.x to it.y }.toSet() }
    private val anyHeight: Set<Pair<Int, Int>> by lazy {
        flood(allowJumps = true, options = options?.copy(maxClimb = Int.MAX_VALUE / 2)).map { it.x to it.y }.toSet()
    }

    private fun flood(allowJumps: Boolean, crossing: Boolean = false, options: dev.kotlinds.pokemonclient.world.RouteOptions? = this.options): Set<Node> {
        val p = pathfinder ?: return emptySet()
        val s = start ?: return emptySet()
        return p.reachable(s, options!!, maxCost = Int.MAX_VALUE, allowJumps = allowJumps, allowTriggers = true, goalTiles = enterable, crossing = crossing).keys + s
    }

    private val byId = HashMap<String, Reachability>()

    /**
     * What reaching the target [id] of this map needs (`warp:N`, `hole:N`, `exit:<direction>`, `person:N`, `item:N`,
     * `sign:N`, `hidden_item:N`, `examine:N`), resolved like go_to and interact resolve it; for a way out, whether
     * taking it leaves no way back ([ofExit]). [Reachability.DIRECT] when unknown, or for an object of another map
     * (`person:N@zone`: this survey stays on the player's map). Computed once per id.
     */
    fun of(id: String): Reachability = byId.getOrPut(id) {
        val f = field ?: return@getOrPut Reachability.DIRECT
        val a = area ?: return@getOrPut Reachability.DIRECT
        if (!surveyed || '@' in id) return@getOrPut Reachability.DIRECT
        if (id.startsWith("exit:")) {
            val chosen = WorldLinks.connections(a, f.mapId).filter { it.id == id }
            return@getOrPut if (chosen.isEmpty()) Reachability.DIRECT else of(WorldTravel.exitTarget(id, chosen, onThisMap = true))
        }
        val target = MovePlans.resolve(game, state, settings, id, null, null) ?: return@getOrPut Reachability.DIRECT
        val link = WorldLinks.links(game.world, a, f.mapId).firstOrNull { it.id == id }
        if (link != null) ofExit(link, target) else of(target)
    }

    /** What reaching [target] (resolved like go_to / interact do, [MovePlans.resolve]) needs; [Reachability.DIRECT] when unknown. */
    fun of(target: MovePlans.Target): Reachability {
        val p = pathfinder ?: return Reachability.DIRECT
        val a = area ?: return Reachability.DIRECT
        val s = start ?: return Reachability.DIRECT
        val goals = MovePlans.goalTiles(a, target)
        val isGoal: (Node) -> Boolean = target.isGoal ?: { node -> (node.x to node.y) in goals }
        // Standing on it (a warp: go_to presses it, or steps off and on) or next to it already.
        if (isGoal(s) || (s.x to s.y) in goals) return Reachability.DIRECT
        val reached = { nodes: Set<Node> -> nodes.firstOrNull { it != s && (it.x to it.y) in goals && isGoal(it) } }
        if (reached(walked) != null) return Reachability.DIRECT
        // Only over ledges: fine while the player can walk back (ledges are a shortcut then), one way otherwise.
        reached(jumped)?.let { end -> return if (p.hasWayBack(end, s, options!!, triggers = true)) Reachability.DIRECT else Reachability(oneWay = true) }
        val enter = if (target.adjacent) emptySet() else goals
        val beside = MovePlans.beside(a, target)
        // Out of reach of any diagnosis (next to it at another height is told by the diagnosis itself).
        if (goals.none { it in crossed } && (beside == null || jumped.none { (it.x to it.y) in beside.tiles })) {
            return Reachability.of(if (goals.any { it in anyHeight }) RouteFailure.DifferentLevel else RouteFailure.Unreachable, Blockers(), field)!!
        }
        val failed = p.diagnose(s, options!!, enter, beside, isGoal)
        // Movement puzzles left to the agent: the mechanism the way needs, like go_to tells it (MovePlans.walkTo).
        val mechanism = if (settings.solvePuzzles) null
        else PuzzleSolving.diagnose(a, field!!, solving, s, options, enter, isGoal)
        return Reachability.of(mechanism ?: failed.failure, failed.blockers, field) ?: Reachability.DIRECT
    }

    /**
     * What reaching the way out [link] needs ([of]), and whether taking it leaves no way back ([WorldLinks.noWayBack]:
     * people of the destination counted where the save's event flags say they are, [GameState.eventFlags]).
     */
    fun ofExit(link: ZoneLink, target: MovePlans.Target): Reachability {
        val reach = of(target)
        val world = game.world ?: return reach
        val o = options ?: return reach
        return if (!reach.oneWay && WorldLinks.noWayBack(world, link, o, ::present)) reach.copy(oneWay = true) else reach
    }

    /**
     * True when the person [t] of another map is there now: always without an event flag, else while its flag is
     * clear; unknown flags (a game that doesn't read them) count it as absent, so nothing is called one way on a guess.
     */
    private fun present(t: PersonTemplate): Boolean {
        if (t.hiddenByFlag == 0) return true
        return state.eventFlags?.get(t.hiddenByFlag) == false
    }
}
