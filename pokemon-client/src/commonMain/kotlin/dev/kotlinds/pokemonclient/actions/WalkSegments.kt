package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Edge
import dev.kotlinds.pokemonclient.world.Node

/**
 * Smooth straight-line walking: consecutive steps in the same direction are walked as one segment, the direction
 * held all along like a player does, instead of stopping on every tile.
 *
 * The position is read on every frame: each new tile must be the next one of the segment (anything else stops the
 * segment at once and the walk re-plans), a screen other than the overworld (a battle, a trainer, a message) stops
 * it, and no progress for a while means the game refused the step. The direction is let go one tile before the end
 * (when the player starts the last tile, or the one before on a bike, whose input the game reads ahead), then the
 * walker waits for the player to stand still and checks the final tile. The game busy while the player doesn't move
 * (a door opening, a warp's fade) lets go at once, and after a warp ([WarpWatch]) the direction is never held again:
 * the segment ends there ([Result.Elsewhere]), see [FieldControl].
 *
 * The pace changes on the way without stopping: the game decides running or walking at the start of each step (B held
 * then or not), so B is pressed or let go for each tile as the player starts the one before ([Segment.runs]); the
 * direction stays held (a stop at the edge of the grass would cost a full stop and start).
 */
internal object WalkSegments {

    /**
     * A straight run of plain steps in [direction], [tiles] in order (the last one is the segment's end), each run (B
     * held) or walked onto as [runs] says (one per tile, [MovePlans.runOnto]: a walk lets go of B onto the tiles where
     * wild Pokémon appear, once a Repel no longer keeps them away).
     */
    data class Segment(val direction: Direction, val tiles: List<Node>, val runs: List<Boolean> = tiles.map { false }) {
        init {
            require(runs.size == tiles.size) { "one pace per tile" }
        }
    }

    /** How a segment ended. */
    sealed interface Result {
        /** On the segment's last tile. */
        data class Reached(val field: FieldState) : Result

        /** Somewhere else than planned (pushed, slid, another map): the walk re-plans from [field]. */
        data class Elsewhere(val field: FieldState) : Result

        /** The game refused the step leaving [from] (an invisible wall, a person who moved in the way). */
        data class Refused(val from: Node) : Result

        /** Stopped by the game (a battle, a trainer spotting the player, a phone call, a script); [at] is the tile the player was stepping onto (what started a scene, a battle...). */
        data class Stopped(val state: GameState, val walked: Int = 0, val at: Node? = null) : Result
    }

    /**
     * The segment starting at [edges]`[start]`: the following [Edge.Step]s in the same direction that don't enter a
     * warp tile of [area] (a door is a single, longer step). Null when that edge isn't a plain step. [taken]: the steps
     * the game counted before [edges]`[start]` ([dev.kotlinds.pokemonclient.world.Route.stepsBefore]), for [runOnto].
     */
    fun segmentAt(edges: List<Edge>, start: Int, area: Area, runOnto: RunOnto, taken: Int): Segment? {
        val first = edges[start] as? Edge.Step ?: return null
        if (enters(area, first)) return null
        val tiles = mutableListOf(first.to)
        var i = start + 1
        while (i < edges.size) {
            val next = edges[i] as? Edge.Step ?: break
            if (next.direction != first.direction || enters(area, next)) break
            tiles += next.to
            i++
        }
        return line(first.direction, tiles, runOnto, taken)
    }

    /**
     * The straight line [tiles] in [direction], each tile run onto or walked onto as [runOnto] says, the first one
     * after [taken] steps of the walk (each tile of the line one more: plain steps).
     */
    fun line(direction: Direction, tiles: List<Node>, runOnto: RunOnto, taken: Int): Segment =
        Segment(direction, tiles, tiles.mapIndexed { i, tile -> runOnto.runs(tile, taken + i) })

    private fun enters(area: Area, edge: Edge) = area.warps.any { it.x == edge.to.x && it.y == edge.to.y }

    /**
     * Walks [segment] holding its direction (with B onto the tiles it runs onto: [Segment.runs]), as described on [WalkSegments].
     * Entering one of [scenes] (tiles of active scene triggers) stops it there: the scene starts as the step ends
     * ([Result.Stopped] with that tile).
     */
    fun walk(context: PlanContext, segment: Segment, scenes: Set<Pair<Int, Int>> = emptySet()): Result {
        val mark = FieldControl.warpMark(context)
        var remaining = segment.tiles
        var round = 0
        while (remaining.isNotEmpty() && round++ < MAX_ROUNDS) {
            val start = context.state().field ?: return Result.Stopped(context.state())
            val done = segment.tiles.size - remaining.size
            when (val held = hold(context, segment.direction, remaining, segment.runs.drop(done), start, scenes)) {
                is Held.Stopped -> return Result.Stopped(held.state, done + held.walked, held.at)
                is Held.Refused -> return Result.Refused(held.from)
                is Held.Released -> Unit
            }
            // Let go: wait for the player to stand still (a bike may still take one more tile), then check.
            val end = when (val still = FieldControl.awaitStill(context, stopOn = FieldControl.Motion.WALK)) {
                is FieldControl.Still.TakenOver -> return Result.Stopped(still.state)
                is FieldControl.Still.Settled, is FieldControl.Still.TimedOut -> still.state.field ?: return Result.Stopped(still.state)
            }
            // Through a warp (even one that came back to this very tile): never hold again, the walk decides.
            if (context.navigator.warps.since(mark) != null) return Result.Elsewhere(end)
            val at = remaining.indexOfFirst { it.x == end.x && it.y == end.y }
            when {
                at == remaining.lastIndex -> return Result.Reached(end)
                // Stopped short (released early on a bike): hold again for the rest.
                at >= 0 -> remaining = remaining.drop(at + 1)
                end.x == start.x && end.y == start.y -> continue
                else -> return Result.Elsewhere(end)
            }
        }
        val field = context.state().field ?: return Result.Stopped(context.state())
        return Result.Elsewhere(field)
    }

    private sealed interface Held {
        data object Released : Held
        data class Refused(val from: Node) : Held
        data class Stopped(val state: GameState, val walked: Int, val at: Node?) : Held
    }

    /**
     * Holds the direction until the release point of [tiles], checking each new position; B is held for the step onto
     * each tile that [runs] says (set as the step before starts: the game reads it when the next step begins).
     */
    private fun hold(context: PlanContext, direction: Direction, tiles: List<Node>, runs: List<Boolean>, start: FieldState, scenes: Set<Pair<Int, Int>>): Held {
        fun input(index: Int) = InputFrame(buildSet {
            add(direction.button)
            if (runs.getOrElse(index) { false }) add(Button.B)
        })
        var input = input(0)
        val releaseAt = if (start.movement == MovementMode.BIKE && tiles.size >= 2) tiles.size - 2 else tiles.size - 1
        var next = 0
        var lastX = start.x
        var lastY = start.y
        var idle = 0
        var busy = 0
        while (true) {
            context.scope.step(1, input)
            val state = context.state()
            val field = state.field
            // A trainer's "!" keeps the overworld on screen (an animation) and ignores the held direction: without this,
            // the walk would read it as a refused step (an invisible wall) and plan again.
            if (field == null || FieldControl.takenOver(state, FieldControl.Motion.WALK)) {
                val walked = next + if (tiles.getOrNull(next)?.let { it.x == field?.x && it.y == field.y } == true) 1 else 0
                return Held.Stopped(state, walked, tiles.getOrNull(next))
            }
            if (field.x != lastX || field.y != lastY) {
                lastX = field.x
                lastY = field.y
                idle = 0
                val expected = tiles.getOrNull(next)
                // Anything but the next tile: let go, the caller sees where the player ends.
                if (expected == null || expected.x != field.x || expected.y != field.y) return Held.Released
                // A scene trigger: let go, its script runs once this step ends (and may move the player back).
                if ((field.x to field.y) in scenes) return Held.Stopped(state, next + 1, expected)
                if (next >= releaseAt) return Held.Released
                next++
                // The step onto the next tile starts when this one ends: its pace from now on.
                input = input(next)
                continue
            }
            // The game busy while the player stands (a door opening, a warp's fade starting): let go now, holding on
            // would carry the press into what comes next (the warp on the other side).
            busy = if (!field.moving && state.screen.awaiting != Awaiting.INPUT) busy + 1 else 0
            if (busy >= BUSY_FRAMES) return Held.Released
            idle = if (field.moving) 0 else idle + 1
            if (idle >= REFUSED_FRAMES) {
                context.scope.step(1)
                val from = if (next == 0) Node(start.x, start.y) else tiles[next - 1]
                return Held.Refused(from)
            }
        }
    }

    /** No new tile for this long while holding (and not moving): the game refuses the step (turning included). */
    private const val REFUSED_FRAMES = 24
    private const val MAX_ROUNDS = 8

    /** Frames in a row of the game busy while the player stands still after which the hold lets go. */
    private const val BUSY_FRAMES = 2
}

/**
 * Whether a walk holds B onto a tile ([MovePlans.runOnto]): [runs] for the move onto [Node] made after the game counted
 * `taken` steps of the walk ([dev.kotlinds.pokemonclient.world.Edge.gameSteps]), so that a Repel wearing off on the way
 * is followed tile by tile, like the route was planned.
 */
internal fun interface RunOnto {
    fun runs(node: Node, taken: Int): Boolean
}
