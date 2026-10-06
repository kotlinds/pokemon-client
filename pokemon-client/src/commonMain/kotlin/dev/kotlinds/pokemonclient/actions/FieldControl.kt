package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen

/**
 * The few rules every movement recipe shares (walks, single steps, field moves, pushes, teleports, `interact`), kept
 * in one place so they can't drift apart: when the player is in control, when the game took the control away, how to
 * wait for the player to stand still, and how to face a direction (verified, like every other press).
 */
internal object FieldControl {

    /**
     * True when the player walks freely on the overworld and the game waits for input: no battle, no message, no
     * menu, no scene. The common condition of the field actions (whether maps are known or not: see
     * [MovePlans.canWalk]).
     */
    fun inControl(state: GameState): Boolean =
        state.battle == null && state.screen is Screen.Overworld && state.screen.awaiting == Awaiting.INPUT

    /** What a wait is going through, which decides what counts as the game taking the control away ([takenOver]). */
    enum class Motion {
        /**
         * Walking or turning: anything but the overworld that waits for the player (a battle, a message, a menu, a
         * scene), or a trainer's "!" (the overworld stays on screen, an animation). Screens only animating (the
         * overworld busy for a few frames) are walked through.
         */
        WALK,

        /**
         * Holding a direction before the step has started ([MovePlans.stepOnce]): stricter than [WALK], any screen
         * but the overworld stops the hold, animations and fades included. Before the player moved, such a screen is
         * the game taking over (a door's fade, a scene starting): holding on would carry the press into it (and a
         * warp reached that way would read as a plain step). Once the step started, [WALK] applies (a busy
         * overworld frame is the step's own).
         */
        HOLD,

        /**
         * A transition the walk started on purpose (a warp's fade, a fall, a ride, a boulder's push): only a battle,
         * a message or a menu take the control away; the transition's own screens (a fade, an animation, no field)
         * are waited through.
         */
        TRANSITION,
    }

    /** True when the game took the control away from the player during [motion] (see [Motion]). */
    fun takenOver(state: GameState, motion: Motion): Boolean = when (motion) {
        Motion.WALK -> state.battle != null || state.field == null || state.field.trainerEncounter ||
            (state.screen !is Screen.Overworld && state.screen.awaiting != Awaiting.ANIMATION)
        Motion.HOLD -> state.battle != null || state.field == null || state.field.trainerEncounter || state.screen !is Screen.Overworld
        Motion.TRANSITION -> state.battle != null || state.screen is Screen.Dialogue || state.screen is Screen.Selectable
    }

    /** How [awaitStill] ended; [state] is the last one read. */
    sealed interface Still {
        val state: GameState

        /** The player stood still for the frames asked. */
        data class Settled(override val state: GameState) : Still

        /** The game took the control away first ([takenOver]). */
        data class TakenOver(override val state: GameState) : Still

        /** Still moving when the wait ran out. */
        data class TimedOut(override val state: GameState) : Still
    }

    /**
     * Steps without input until the player has stood still for [stillFrames] frames in a row (a step, a bike's last
     * tile, a slide over), at most [maxFrames]. With [stopOn], returns as soon as the game takes the control away
     * during that kind of motion ([Still.TakenOver]). The one "wait until still" of the walks: the game may still act on
     * an input read a few frames ago, so a move is over only once the player stays put for a few frames.
     */
    fun awaitStill(context: PlanContext, maxFrames: Int = SETTLE_FRAMES, stillFrames: Int = STILL_FRAMES, stopOn: Motion? = null): Still {
        var still = 0
        var waited = 0
        var state = context.state()
        while (waited < maxFrames) {
            context.scope.step(1)
            waited++
            state = context.state()
            if (stopOn != null && takenOver(state, stopOn)) return Still.TakenOver(state)
            still = if (state.field?.moving == true) 0 else still + 1
            if (still >= stillFrames) return Still.Settled(state)
        }
        return Still.TimedOut(state)
    }

    /** How [face] ended. */
    sealed interface Facing {
        /** Facing the direction asked, on the overworld: [field] as read then. */
        data class Faced(val field: FieldState) : Facing

        /** The game took the control away (a trainer saw the player, a message, a battle): [state]. */
        data class Stopped(val state: GameState) : Facing

        /** The player never turned that way: the typed reason. */
        data class Failed(val error: ActionError) : Facing
    }

    /**
     * Turns the player to [direction] without stepping (a short tap only turns a player facing elsewhere), the way the
     * verification rule asks: read the facing from RAM, tap, read it again; at most [MAX_TURN_TRIES] taps, then an
     * explicit error ([ActionError.VerificationFailed], [what] says what the turn was for) instead of pressing A facing
     * something else. Already facing it: nothing pressed.
     */
    fun face(context: PlanContext, direction: Direction, what: String): Facing {
        var taps = 0
        while (true) {
            val state = context.navigator.settle()
            val field = state.field
            // A turn is a hold that hasn't moved the player: the same rule as before a step starts.
            if (field == null || takenOver(state, Motion.HOLD)) return Facing.Stopped(state)
            if (field.facing == direction) return Facing.Faced(field)
            if (taps == MAX_TURN_TRIES) {
                return Facing.Failed(ActionError.VerificationFailed("face ${direction.name.lowercase()} to $what", direction.name.lowercase(),
                    field.facing?.name?.lowercase() ?: UNKNOWN_FACING, taps))
            }
            context.scope.tap(direction.button)
            context.scope.step(TURN_FRAMES)
            taps++
        }
    }

    /** Frames in a row without moving after which a move is over (a bike may start one more tile by itself). */
    const val STILL_FRAMES = 6

    /** Longest wait for the player to stand still after a move (a step takes 8 frames running, 16 walking). */
    const val SETTLE_FRAMES = 64

    /** Frames after a turning tap before the facing is read again (the turn's animation). */
    private const val TURN_FRAMES = 8

    /** The facing reported when the game's facing couldn't be read (the screen is the overworld: not a screen kind). */
    private const val UNKNOWN_FACING = "unknown"

    /** Taps tried to face a direction before giving up (the "3 tries" rule). */
    private const val MAX_TURN_TRIES = 3
}
