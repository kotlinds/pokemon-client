package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.Direction

/** A movement-puzzle mechanism a route may operate: what [RouteFailure.NeedsMechanism] names. */
enum class PuzzleMechanism {
    /** A Strength boulder to push (walking into it once Strength is used on the map). */
    STRENGTH_BOULDER,

    /** An ice block to push (sliding into it on the ice). */
    ICE_BLOCK,

    /** A moving platform turned or slid by stepping on one of its trigger tiles (the Blackthorn Gym). */
    MOVING_PLATFORM,

    /** A lift started by stepping on its center (the Violet Gym). */
    LIFT,

    /** A cart ridden from its station to another one by stepping on the station (the Azalea Gym's Spinarak carts). */
    CART_RIDE,

    /** A switch pressed with A that changes the way (the Azalea Gym's levers, which set the carts' routes). */
    SWITCH,
}

/**
 * No route by walking alone, but there is one operating [mechanism] (movement puzzles left to the agent): the first
 * one on that way, at ([x], [y]) (the boulder / ice block, the platform trigger, the lift's tile), operated from
 * [from] going [direction] (walking into the boulder, stepping onto the trigger, the lift or the station; facing the
 * lever). [objectTo]: where a pushed object would stop.
 */
data class NeedsMechanism(
    val mechanism: PuzzleMechanism,
    val x: Int,
    val y: Int,
    val from: Node? = null,
    val direction: Direction? = null,
    val objectTo: Pair<Int, Int>? = null,
    /** For a [PuzzleMechanism.SWITCH]: what to press (`sign:N`). */
    val target: String? = null,
) : RouteFailure
