package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldNotice
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.kind
import dev.kotlinds.pokemonclient.world.WorldSource
import kotlin.math.abs

/**
 * The few rules every movement recipe shares (walks, single steps, field moves, pushes, teleports, `interact`), kept
 * in one place so they can't drift apart: when the player is in control, when the game took the control away, how to
 * wait for the player to stand still, how to face a direction (verified, like every other press), and what a warp is
 * ([isWarp], [WarpWatch], [awaitOutcome]).
 *
 * The warp rule: a movement stops at the first warp. Every state the recipes read goes through the [WarpWatch] of the
 * [Navigator], which notes the first jump of the player to another place; the holds let go as soon as the game is busy
 * without the player moving (a door opening, a fade starting), never press again once a warp was seen, and the answer
 * says which warp was taken and where the player is now. Holding on across a warp is what took the shuffled warps
 * twice (the "invisible double warp": arrived in front of another warp taken the same way, the held direction took it
 * back at once, and the walk saw the same map and tile as before).
 *
 * Public API: a game written in its own project uses these rules in its overrides (a step of its own that waits for
 * the player, an availability saying "walking freely": [inControl], [takenOver], [awaitStill], [face],
 * [closeNotice]), so its recipes wait, face and stop exactly like the common ones. The warp watch itself ([warpMark],
 * [awaitOutcome], what a warp is: [isWarp], [inTransition]), the refused-step check of the `step` recipe
 * ([takenAfterRefusal]) and the engine's frame counts stay internal to the walking engine: they work on the warps the
 * [Navigator] noted from every state it decoded (its internal [WarpWatch]), engine state a recipe never handles. A
 * game's recipe that moves the player calls the movement recipes on itself (`goTo(...)`, `step(...)`, protected
 * methods of its own chain, overrides included), which watch the warps and the refusals.
 */
object FieldControl {

    /**
     * True when the player walks freely on the overworld and the game waits for input: no battle, no message, no
     * menu, no scene. The common condition of the field actions (whether maps are known or not: see
     * [ActionConditions.canWalk]).
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
     * Steps without input until the player has stood still, with the control ([inControl]), for [stillFrames] frames in
     * a row (a step, a bike's last tile, a slide over), at most [maxFrames]. With [stopOn], returns as soon as the game
     * takes the control away during that kind of motion ([Still.TakenOver]). The one "wait until still" of the walks:
     * the game may still act on an input read a few frames ago, so a move is over only once the player stays put for a
     * few frames. Frames where the game is busy without the player moving (a door opening, the fade of a warp that
     * starts the frame after the step ends) don't count as still: the move isn't over.
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
            still = if (state.field?.moving == false && inControl(state)) still + 1 else 0
            if (still >= stillFrames) return Still.Settled(state)
        }
        return Still.TimedOut(state)
    }

    /**
     * True when the player went from [before] to [after] (two readings in a row) through a warp, a hole or a warp pad,
     * not by moving on the map, by what the game did between them:
     * - onto another area (the next zone of the same overworld is walked into): always a warp (without maps, any
     *   other map);
     * - farther than [MAX_STRIDE] tiles on the same area (two shuffled outdoor maps of one region, a pad to the same
     *   map) when the game showed a map transition in between ([transitionSeen]: its fade,
     *   [dev.kotlinds.pokemonclient.state.AnimationKind.TRANSITION]), or when the readings are [frames] ≤
     *   [CONSECUTIVE_FRAMES] apart (nothing the player does on the map moves them that far in so few frames).
     *
     * Distance alone isn't a warp: waits that read the game every few frames ([Navigator.advanceUntil] during a field
     * move's animation, a ride) see a Rock Climb, a waterfall or a cart cover several tiles between two readings,
     * with no transition.
     */
    internal fun isWarp(world: WorldSource?, before: FieldState, after: FieldState, transitionSeen: Boolean, frames: Long): Boolean {
        if (before.mapId != after.mapId) {
            if (world == null) return true
            if (world.areaOf(before.mapId)?.id != world.areaOf(after.mapId)?.id) return true
        }
        if (abs(after.x - before.x) + abs(after.y - before.y) <= MAX_STRIDE) return false
        return transitionSeen || frames <= CONSECUTIVE_FRAMES
    }

    /**
     * True when [state] shows the game's map transition (the fade of a warp, a fall, a map load: the screen is a
     * [dev.kotlinds.pokemonclient.state.AnimationKind.TRANSITION]): what tells a warp from a long move ([isWarp]).
     */
    internal fun inTransition(state: GameState): Boolean =
        (state.screen as? Screen.Animation)?.kind == dev.kotlinds.pokemonclient.state.AnimationKind.TRANSITION

    /** The mark to take before a movement ([WarpWatch.mark]), the player's place read first (the last one known). */
    internal fun warpMark(context: PlanContext): Int {
        context.state()
        return context.navigator.warps.mark()
    }

    /**
     * After a move (a step, a straight walk, a press on a mat): waits until the player stands still with the control
     * for [STILL_FRAMES] frames in a row, or a warp seen since [mark] ([WarpWatch.mark]) is over, at most
     * [WARP_FRAMES]. A warp starts a frame after the step onto it ends, and its fade, the new map's loading and the
     * walk out of the arrival door take a few seconds: deciding before (the walk saw the player standing on the warp,
     * "nothing happened") is what pressed on into a second warp. The warp taken, with where the player stands once
     * the game gives the control back; null when there was none (or a battle, a message, a menu came first).
     */
    internal fun awaitOutcome(context: PlanContext, mark: Int): WarpWatch.Warped? {
        val warps = context.navigator.warps
        // Already standing still with the control for long enough (the move's own wait saw it): no warp is starting.
        if (warps.since(mark) == null && warps.calmFrames >= STILL_FRAMES) return null
        var still = 0
        var waited = 0
        while (waited < WARP_FRAMES && context.navigator.warps.since(mark) == null) {
            val state = context.state()
            // A battle starting (no field), a message, a menu: not a warp, the caller sees what took over.
            if (takenOver(state, Motion.TRANSITION) || state.field == null) break
            still = if (state.field?.moving == false && inControl(state)) still + 1 else 0
            if (still >= STILL_FRAMES) break
            context.scope.step(1)
            waited++
        }
        val warped = context.navigator.warps.since(mark) ?: return null
        // The control back on the new map (its arrival walk-out done), then where the player really is.
        val now = context.navigator.settle()
        return warped.endingAt(now.field ?: warped.last.to)
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

    /**
     * After the game refused a step: the state where it took the control away within [REFUSAL_GRACE_FRAMES] frames
     * (a scene starting as the player stopped, its field screen busy or not left at all; a battle's first frames), or
     * null when the player kept the control all along: the step really was refused (a wall, someone in the way). A
     * refused step and a scene starting look the same on the frame of the refusal.
     */
    internal fun takenAfterRefusal(context: PlanContext): GameState? {
        repeat(REFUSAL_GRACE_FRAMES) {
            context.scope.step(1)
            val state = context.state()
            if (state.field == null || !inControl(state)) return state
        }
        return null
    }

    /** Frames [takenAfterRefusal] watches the control after a refused step (half a second). */
    private const val REFUSAL_GRACE_FRAMES = 32

    /** How [closeNotice] ended. */
    sealed interface Notice {
        /** The game shows no [FieldNotice]: what stopped the move is something else (a scene, a battle...), for the caller. */
        data object None : Notice

        /** [notice] was shown and closed: the player has the control again, standing on [field]. */
        data class Closed(val notice: FieldNotice, val field: FieldState) : Notice

        /** [notice] was closed, then the game took the control away (a battle, a call...): [state]. */
        data class Stopped(val notice: FieldNotice, val state: GameState) : Notice

        /** The message stayed on screen after [MAX_NOTICE_PRESSES] checked presses: the typed reason. */
        data class Failed(val error: ActionError) : Notice
    }

    /**
     * When the game stopped a move to show a message of its own that ends nothing ([FieldNotice]: "REPEL's effect
     * wore off..."), closes it the checked way (the message read from RAM, waiting for A, before each press; at most
     * [MAX_NOTICE_PRESSES] presses) and waits for the control back: the walk goes on from there. Told by the script
     * that prints it ([Screen.Dialogue.notice]), never by its text: any other message (a scene, a trainer) is
     * [Notice.None], left to the caller as the interruption it is. [stopped]: the state that stopped the move; a battle,
     * a trainer's "!" or another message is told apart at once, without waiting on it.
     */
    fun closeNotice(context: PlanContext, stopped: GameState): Notice {
        if (!mayBeNotice(stopped)) return Notice.None
        var notice: FieldNotice? = null
        var presses = 0
        repeat(MAX_NOTICE_POLLS) {
            val state = context.navigator.settle()
            val screen = state.screen
            if (screen is Screen.Dialogue && screen.notice != null) {
                notice = screen.notice
                if (presses == MAX_NOTICE_PRESSES) {
                    return Notice.Failed(ActionError.VerificationFailed("close the message \"${screen.text.replace('\n', ' ')}\"", "the overworld", screen.kind, presses))
                }
                if (screen.awaiting == Awaiting.INPUT) {
                    presses++
                    context.navigator.press(dev.kotlinds.pokemonclient.console.Button.A, screen)
                } else {
                    context.scope.step(NOTICE_WAIT_FRAMES)
                }
                return@repeat
            }
            val shown = notice ?: return Notice.None
            val field = state.field
            if (field != null && inControl(state)) return Notice.Closed(shown, field)
            if (takenOver(state, Motion.WALK)) return Notice.Stopped(shown, state)
            // The box closed, the script ending (the overworld busy for a few frames): wait for the control.
            context.scope.step(NOTICE_WAIT_FRAMES)
        }
        return notice?.let { Notice.Stopped(it, context.state()) } ?: Notice.None
    }

    /**
     * Whether [state], the state that stopped a move, may be a [FieldNotice]: one on screen, or the field busy or
     * unreadable while its box opens. Never a battle, a trainer's "!" or another message.
     */
    private fun mayBeNotice(state: GameState): Boolean {
        if (state.battle != null || state.field?.trainerEncounter == true) return false
        return when (val screen = state.screen) {
            is Screen.Dialogue -> screen.notice != null
            is Screen.Overworld, is Screen.Unknown, is Screen.Animation -> true
            else -> false
        }
    }

    /** Checked presses of A on a [FieldNotice] before giving up (the "3 tries" rule). */
    private const val MAX_NOTICE_PRESSES = 3

    /** Readings of [closeNotice] (each one settles first): the message, its presses, the control coming back. */
    private const val MAX_NOTICE_POLLS = 12

    /** Frames between two readings of [closeNotice] while the message prints or the script ends. */
    private const val NOTICE_WAIT_FRAMES = 8

    /** Frames in a row without moving after which a move is over (a bike may start one more tile by itself). */
    internal const val STILL_FRAMES = 6

    /** The longest move between two readings that is still walking (a ledge jump crosses two tiles). */
    internal const val MAX_STRIDE = 2

    /** Readings this many frames apart or fewer are consecutive ([isWarp]): no move on the map covers 3 tiles in them. */
    internal const val CONSECUTIVE_FRAMES = 2L

    /** Longest wait for a warp to start and end once the player stopped on it (a ladder's climb, a fade, the arrival). */
    internal const val WARP_FRAMES = 300

    /** Longest wait for the player to stand still after a move (a step takes 8 frames running, 16 walking). */
    internal const val SETTLE_FRAMES = 64

    /** Frames after a turning tap before the facing is read again (the turn's animation). */
    private const val TURN_FRAMES = 8

    /** The facing reported when the game's facing couldn't be read (the screen is the overworld: not a screen kind). */
    private const val UNKNOWN_FACING = "unknown"

    /** Taps tried to face a direction before giving up (the "3 tries" rule). */
    private const val MAX_TURN_TRIES = 3
}

/**
 * The warps the player went through, seen from every state the recipes decode ([Navigator.state]): how a walk, a step
 * or a field move learns that the game moved the player through a door, stairs, a hole or a warp pad, so that it
 * stops there ([FieldControl.awaitOutcome]). A movement takes a [mark] before it starts and asks [since] after.
 *
 * What a warp is comes from what the game does ([FieldControl.isWarp]): another area, or a jump with the game's map
 * transition (its fade) in between. Readings during a warp may jump more than once (the new map's tile read as 0,0 for
 * a frame before the arrival): those jumps belong to the same warp, whose [Warped.to] is the last place read. A
 * second warp before the player has the control again (its own transition seen after the first jump) is noted as
 * such ([Warped.next]): the agent is told every warp taken.
 */
internal class WarpWatch(private val world: () -> WorldSource?) {

    /**
     * A warp: [from] the last reading before the jump (the warp's tile, or the tile in front of a door), [to] after;
     * [next] the warp taken right after it, before the control came back (null: none).
     */
    data class Warped(val from: FieldState, val to: FieldState, val next: Warped? = null) {
        /** The last warp of the chain: where the player ended up. */
        val last: Warped get() = next?.last ?: this

        /** This chain with the player last read at [to] (the arrival of its last warp). */
        fun endingAt(to: FieldState): Warped = if (next == null) copy(to = to) else copy(next = next.endingAt(to))
    }

    private var last: FieldState? = null
    private var lastFrame = 0L

    /** The last warps seen ([KEPT] at most); the first of them is number [count] - size + 1. */
    private val recent = ArrayDeque<Warped>()
    private var count = 0

    /** True from a warp's jump until the player has the control again: further jumps belong to that warp. */
    private var open = false

    /**
     * The game showed a map transition ([FieldControl.inTransition]) since the player last had the control, moved by
     * themselves, read a message or chose in a menu (or since the last warp noted).
     */
    private var transition = false

    /** The frames of the last readings where the player stood still with the control, in a row (null: not now). */
    private var calmFrom: Long? = null
    private var calmTo = 0L

    /**
     * How many frames in a row, up to the last reading, the player has stood still with the control: a move whose
     * own wait saw that ([FieldControl.awaitStill]) needs no other wait for a warp ([FieldControl.awaitOutcome]).
     */
    val calmFrames: Long get() = calmFrom?.let { calmTo - it + 1 } ?: 0

    /** Reads [state]: notes a warp when the player jumped since the previous reading ([FieldControl.isWarp]). */
    fun observe(state: GameState) {
        val calm = state.field?.moving == false && FieldControl.inControl(state)
        if (calm) {
            if (calmFrom == null) calmFrom = state.frame
            calmTo = state.frame
        } else {
            calmFrom = null
        }
        if (FieldControl.inTransition(state)) transition = true
        val field = state.field ?: return
        val previous = last ?: field
        val frames = state.frame - lastFrame
        last = field
        lastFrame = state.frame
        val jumped = FieldControl.isWarp(world(), previous, field, transition, frames)
        if (open) {
            // Another jump of the same warp (its arrival read in two steps), unless a transition of its own came first.
            if (jumped && transition) note(previous, field, chained = true)
            else recent[recent.lastIndex] = recent.last().endingAt(field)
        } else if (jumped) {
            note(previous, field, chained = false)
        }
        if (calm) open = false
        // What the player does by themselves, a message, a menu: whatever transition came before isn't a warp's.
        if (calm || field.moving || state.screen is Screen.Dialogue || state.screen is Screen.Selectable) transition = false
    }

    /** Notes a warp from [from] to [to]: a new one, or ([chained]) the next of the warp still under way. */
    private fun note(from: FieldState, to: FieldState, chained: Boolean) {
        transition = false
        open = true
        if (chained) {
            recent[recent.lastIndex] = recent.last().chain(Warped(from, to))
            return
        }
        count++
        recent.addLast(Warped(from, to))
        if (recent.size > KEPT) recent.removeFirst()
    }

    private fun Warped.chain(warp: Warped): Warped = if (next == null) copy(next = warp) else copy(next = next.chain(warp))

    /** The current mark: [since] a mark only tells the warps after it. */
    fun mark(): Int = count

    /** The first warp seen after [mark] (the oldest one kept when more came since), or null when there was none. */
    fun since(mark: Int): Warped? {
        if (count <= mark) return null
        val index = recent.size - (count - mark)
        return recent.getOrNull(index.coerceAtLeast(0))
    }

    private companion object {
        /** Warps remembered: a movement asks right after it ends. */
        const val KEPT = 8
    }
}
