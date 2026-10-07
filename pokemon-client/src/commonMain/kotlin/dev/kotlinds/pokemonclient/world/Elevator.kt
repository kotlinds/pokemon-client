package dev.kotlinds.pokemonclient.world

/**
 * A lift: the room [zone], whose ways out ([exitWarps]: warps whose destination the map data doesn't give) lead to
 * the floor the lift was sent to, one of [stops] (set by the room's scripts: Gen 4's `SetDynamicWarp`, Platinum's
 * `SetSpecialLocation`). [operator] says how it is sent there.
 */
data class Elevator(val zone: Int, val exitWarps: List<Int>, val stops: List<ElevatorStop>, val operator: ElevatorOperator)

/** A floor a lift goes to: warp [warp] of zone [zone] (the player comes out there). */
data class ElevatorStop(val zone: Int, val warp: Int)

/** How a lift is sent to a floor. */
sealed interface ElevatorOperator {
    /**
     * By itself: entering the room starts the ride to the other floor of a two-floor lift (the Olivine Lighthouse's,
     * the Radio Tower's), then the player walks out there.
     */
    data object Shuttle : ElevatorOperator

    /** Entering the room shows the floors to choose from (a lift of three floors or more without an attendant). */
    data object EntryMenu : ElevatorOperator

    /** Talking to person [person] (`person:N`, the attendant) offers the floors to choose from. */
    data class Attendant(val person: Int) : ElevatorOperator

    /** Reading background event [sign] (`sign:N`, the lift's panel) offers the floors to choose from. */
    data class Panel(val sign: Int) : ElevatorOperator
}

/**
 * What the agent does to send the lift to a floor, in the words of the answers and the view (the action and the
 * target to use): null for a [ElevatorOperator.Shuttle], which goes by itself.
 */
val ElevatorOperator.choice: String?
    get() = when (this) {
        ElevatorOperator.Shuttle -> null
        ElevatorOperator.EntryMenu -> "choose the floor in the menu the lift shows when you come in"
        is ElevatorOperator.Attendant -> "talk to person:$person (interact person:$person) and choose the floor"
        is ElevatorOperator.Panel -> "read sign:$sign (interact sign:$sign) and choose the floor"
    }
