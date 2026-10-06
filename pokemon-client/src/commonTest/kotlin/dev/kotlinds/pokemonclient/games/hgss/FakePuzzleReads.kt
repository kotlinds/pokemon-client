package dev.kotlinds.pokemonclient.games.hgss

/**
 * The script state [HgssPuzzles] reads, set by hand ([HgssPuzzles.Reads]): script variables ([vars], 0 when unset),
 * flags ([flags]) and the map's `Gymmick` slot ([gymmick]). Shared by the puzzle tests, so every one of them sees
 * the variables it sets (the gym tests used to drop them).
 */
internal class FakePuzzleReads(
    private val vars: Map<Int, Int> = emptyMap(),
    private val flags: Set<Int> = emptySet(),
    private val gymmick: ByteArray? = null,
) : HgssPuzzles.Reads {
    override fun variable(id: Int) = vars[id] ?: 0
    override fun flag(id: Int) = id in flags
    override fun gymmick() = gymmick
}
