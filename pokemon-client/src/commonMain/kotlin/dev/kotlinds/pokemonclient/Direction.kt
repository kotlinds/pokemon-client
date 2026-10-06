package dev.kotlinds.pokemonclient

import dev.kotlinds.pokemonclient.console.Button

/** A compass direction on the map: x grows east, y grows south. */
enum class Direction(val dx: Int, val dy: Int) {
    NORTH(0, -1), SOUTH(0, 1), WEST(-1, 0), EAST(1, 0);

    /** The D-pad button that walks (or turns the player) this way: the same on every game. */
    val button: Button
        get() = when (this) {
            NORTH -> Button.UP
            SOUTH -> Button.DOWN
            WEST -> Button.LEFT
            EAST -> Button.RIGHT
        }

    /** The direction back. */
    val opposite: Direction
        get() = when (this) {
            NORTH -> SOUTH
            SOUTH -> NORTH
            WEST -> EAST
            EAST -> WEST
        }

    companion object {
        fun parse(value: String?): Direction? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }

        /**
         * The direction from tile ([fromX], [fromY]) to tile ([toX], [toY]) when they are in line (same column or
         * same row, any distance apart: facing someone across a counter); null when they are the same tile or not in
         * line.
         */
        fun between(fromX: Int, fromY: Int, toX: Int, toY: Int): Direction? = when {
            fromX == toX && toY < fromY -> NORTH
            fromX == toX && toY > fromY -> SOUTH
            fromY == toY && toX < fromX -> WEST
            fromY == toY && toX > fromX -> EAST
            else -> null
        }

        /**
         * The direction of one step from tile ([fromX], [fromY]) to the adjacent tile ([toX], [toY]) (the direction a
         * move took); null when they aren't adjacent (a teleport between them, the same tile).
         */
        fun step(fromX: Int, fromY: Int, toX: Int, toY: Int): Direction? =
            between(fromX, fromY, toX, toY)?.takeIf { kotlin.math.abs(toX - fromX) + kotlin.math.abs(toY - fromY) == 1 }
    }
}
