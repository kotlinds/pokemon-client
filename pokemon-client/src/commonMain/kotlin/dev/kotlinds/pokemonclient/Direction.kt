package dev.kotlinds.pokemonclient

/** A compass direction on the map: x grows east, y grows south. */
enum class Direction(val dx: Int, val dy: Int) {
    NORTH(0, -1), SOUTH(0, 1), WEST(-1, 0), EAST(1, 0);

    companion object {
        fun parse(value: String?): Direction? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}
