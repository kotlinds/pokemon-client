package dev.kotlinds.pokemonclient.runtime

/**
 * How far a long action has got, reported while it runs ([ActionScope.report]): a `go_to` across several maps can
 * take minutes with nothing else to show (no text, no menu), so it tells what it has done ("go_to Seafoam Islands 1F:
 * 120/480 tiles, Route 20"). The app turns it into progress for whoever waits on the action (a remote agent's progress
 * notifications, see [ProgressClock.report]).
 */
data class ActionProgress(
    /** What runs, for a person ("go_to Seafoam Islands 1F"). */
    val action: String,
    /** How much of it is done, in [unit]s. */
    val done: Int,
    /** How much there is in all as planned now (it may change when the action plans again), or null when unknown. */
    val total: Int?,
    val unit: ProgressUnit,
    /** Where the player is now (the map's name), when known. */
    val place: String?,
) {
    /** One line: "go_to Seafoam Islands 1F: 120/480 tiles, Route 20". */
    val text: String
        get() = buildString {
            append(action).append(": ").append(done)
            total?.let { append('/').append(it) }
            append(' ').append(unit.label)
            place?.let { append(", ").append(it) }
        }
}

/** What an [ActionProgress] counts. */
enum class ProgressUnit(val label: String) {
    /** Tiles walked (or surfed, cycled): a warp taken counts as one. */
    TILES("tiles"),
}
