package dev.kotlinds.pokemonclient.games.gen4

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.Direction.EAST
import dev.kotlinds.pokemonclient.Direction.NORTH
import dev.kotlinds.pokemonclient.Direction.SOUTH
import dev.kotlinds.pokemonclient.Direction.WEST

/**
 * The movement types of the Gen 4 map objects (the `movement` of a zone's object events), the same numbering in
 * Platinum and HeartGold (pokeplatinum generated/movement_types.txt: `MOVEMENT_TYPE_NONE` = 0...; HGSS's zone events
 * agree: Route 38's Lass Dana, type 13 "look south, west and east", faces south; Bird Keeper Toby, 17 "look east", faces
 * east). Only what routes need: the directions a person turns to on its own.
 */
object Gen4MovementTypes {

    /**
     * The directions an object of [movement] turns to on its own (looking around, turning left and right, walking a
     * pattern): a trainer may face any of them when the player passes. Empty for one that keeps facing one way.
     */
    fun looks(movement: Int): Set<Direction> = when (movement) {
        LOOK_AROUND, WANDER_AROUND, ROTATE_COUNTERCLOCKWISE, ROTATE_CLOCKWISE -> ALL
        in WALK_PATTERNS -> ALL
        WANDER_NORTH_AND_SOUTH, LOOK_NORTH_AND_SOUTH -> setOf(NORTH, SOUTH)
        WANDER_WEST_AND_EAST, LOOK_WEST_AND_EAST -> setOf(WEST, EAST)
        LOOK_NORTH_AND_WEST -> setOf(NORTH, WEST)
        LOOK_NORTH_AND_EAST -> setOf(NORTH, EAST)
        LOOK_SOUTH_AND_WEST -> setOf(SOUTH, WEST)
        LOOK_SOUTH_AND_EAST -> setOf(SOUTH, EAST)
        LOOK_NORTH_SOUTH_AND_WEST -> setOf(NORTH, SOUTH, WEST)
        LOOK_NORTH_SOUTH_AND_EAST -> setOf(NORTH, SOUTH, EAST)
        LOOK_NORTH_WEST_AND_EAST -> setOf(NORTH, WEST, EAST)
        LOOK_SOUTH_WEST_AND_EAST -> setOf(SOUTH, WEST, EAST)
        else -> emptySet()
    }

    private val ALL = Direction.entries.toSet()

    private const val LOOK_AROUND = 2
    private const val WANDER_AROUND = 3
    private const val WANDER_NORTH_AND_SOUTH = 4
    private const val WANDER_WEST_AND_EAST = 5
    private const val LOOK_NORTH_AND_WEST = 6
    private const val LOOK_NORTH_AND_EAST = 7
    private const val LOOK_SOUTH_AND_WEST = 8
    private const val LOOK_SOUTH_AND_EAST = 9
    private const val LOOK_NORTH_SOUTH_AND_WEST = 10
    private const val LOOK_NORTH_SOUTH_AND_EAST = 11
    private const val LOOK_NORTH_WEST_AND_EAST = 12
    private const val LOOK_SOUTH_WEST_AND_EAST = 13
    private const val ROTATE_COUNTERCLOCKWISE = 18
    private const val ROTATE_CLOCKWISE = 19

    /** `MOVEMENT_TYPE_WALK_BACK_AND_FORTH` and the walks in a square (`WALK_NORTH_EAST_WEST_SOUTH`...). */
    private val WALK_PATTERNS = 20..44
    private const val LOOK_NORTH_AND_SOUTH = 45
    private const val LOOK_WEST_AND_EAST = 46
}
