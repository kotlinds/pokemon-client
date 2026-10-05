package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.runtime.ActionProgress
import dev.kotlinds.pokemonclient.runtime.ProgressUnit
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import kotlin.math.abs

/**
 * Counts the tiles of a long walk ([WorldTravel]) for its progress ([ActionProgress]): fed with every state the walk
 * decodes ([observe], see [Navigator.watching]), told the length of the route each time it is planned ([plan]), and
 * reporting after each change to [report] (the app decides how often to show it).
 *
 * A change of position counts its tiles when it stays on the map and is one or two tiles long (a step, a ledge
 * jump); any other move (a warp, a hole, a teleport pad, the step into the next zone of the overworld) counts as one,
 * like taking a link in the planned route.
 */
internal class TravelMeter(
    /** What walks, for a person ("go_to Seafoam Islands 1F"). */
    private val action: String,
    private val report: (ActionProgress) -> Unit,
) {
    /** Tiles walked so far. */
    var done: Int = 0
        private set

    /** Tiles in all as last planned (done when planned + what was left then), null before a route is known. */
    private var total: Int? = null

    private var last: FieldState? = null

    /** A route of [remaining] tiles was planned from here (null: no route known, the total is unknown). */
    fun plan(remaining: Int?) {
        total = remaining?.let { done + it }
        emit()
    }

    /** A state decoded during the walk: counts the tiles moved since the previous one. */
    fun observe(state: GameState) {
        val field = state.field ?: return
        val previous = last
        last = field
        if (previous == null) return
        val distance = abs(field.x - previous.x) + abs(field.y - previous.y)
        val moved = when {
            // Another map: a warp, a fall, or the step into the next zone of the overworld.
            field.mapId != previous.mapId -> 1
            distance <= MAX_STRIDE -> distance
            // Moved on the same map farther than a stride: a teleport pad, a slide...
            else -> 1
        }
        if (moved == 0) return
        done += moved
        emit()
    }

    private fun emit() {
        report(ActionProgress(action, done, total?.coerceAtLeast(done), ProgressUnit.TILES, last?.mapName))
    }

    private companion object {
        /** The longest move that is still walking (a ledge jump crosses two tiles); beyond it the player was moved. */
        const val MAX_STRIDE = 2
    }
}
