package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.runtime.ActionProgress
import dev.kotlinds.pokemonclient.runtime.ProgressUnit
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import kotlin.math.abs

/**
 * Counts the tiles of a long walk ([WorldTravel]) for its progress ([ActionProgress]): fed with every state the walk
 * decodes ([observe], see [Navigator.watching]), told the length of the route each time it is planned ([plan]), and
 * reporting after each change to [report] (the app decides how often to show it). The tiles themselves are counted by
 * a [TileCounter].
 */
internal class TravelMeter(
    /** What walks, for a person ("go_to Seafoam Islands 1F"). */
    private val action: String,
    private val report: (ActionProgress) -> Unit,
) {
    private val counter = TileCounter()

    /** Tiles walked so far. */
    val done: Int get() = counter.tiles

    /** Tiles in all as last planned (done when planned + what was left then), null before a route is known. */
    private var total: Int? = null

    /** A route of [remaining] tiles was planned from here (null: no route known, the total is unknown). */
    fun plan(remaining: Int?) {
        total = remaining?.let { done + it }
        emit()
    }

    /** A state decoded during the walk: counts the tiles moved since the previous one. */
    fun observe(state: GameState) {
        if (counter.observe(state) > 0) emit()
    }

    private fun emit() {
        report(ActionProgress(action, done, total?.coerceAtLeast(done), ProgressUnit.TILES, counter.mapName))
    }
}

/**
 * Counts the tiles the player really moved from the positions read along a walk ([observe] every decoded state):
 * walking, running, cycling and surfing alike, whatever the plan said.
 *
 * A change of position counts its tiles when it stays on the map and is one or two tiles long (a step, a ledge
 * jump); any other move (a warp, a hole, a teleport pad, the step into the next zone of the overworld) counts as one,
 * like taking a link in the planned route. States without a field (a battle) are skipped.
 */
internal class TileCounter {
    /** Tiles moved so far. */
    var tiles: Int = 0
        private set

    private var last: FieldState? = null

    /** The map the player was last seen on, null before any field was read. */
    val mapName: String? get() = last?.mapName?.toString()

    /** Counts the tiles moved since the previous state with a field; returns how many this one added. */
    fun observe(state: GameState): Int {
        val field = state.field ?: return 0
        val previous = last
        last = field
        if (previous == null) return 0
        val distance = abs(field.x - previous.x) + abs(field.y - previous.y)
        val moved = when {
            // Another map: a warp, a fall, or the step into the next zone of the overworld.
            field.mapId != previous.mapId -> 1
            distance <= MAX_STRIDE -> distance
            // Moved on the same map farther than a stride: a teleport pad, a slide...
            else -> 1
        }
        tiles += moved
        return moved
    }

    private companion object {
        /** The longest move that is still walking (a ledge jump crosses two tiles); beyond it the player was moved. */
        const val MAX_STRIDE = 2
    }
}
