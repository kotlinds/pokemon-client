package dev.kotlinds.pokemonclient.actions

/**
 * Choices of the application running the agents (not of the agent: no action parameter changes them) that change how
 * the recipes act. Passed to [ActionRegistry.execute]; the defaults are the most helpful behaviour.
 */
data class ActionSettings(
    /**
     * Movement puzzles solved by the walks themselves (`go_to`, `interact`... through [MovePlans.walkTo]): Strength
     * boulders and ice blocks pushed out of the way, the Blackthorn Gym platforms ridden, the Violet Gym lift taken.
     * When false, walks only walk: they never push a boulder or an ice block, never step on a platform trigger or a
     * lift (unless it is the destination itself, which the agent asked for); a way that needs one of them fails with
     * [dev.kotlinds.pokemonclient.world.NeedsMechanism] ([UnavailableReason.PUZZLE_LEFT_TO_AGENT]) naming
     * the mechanism, for the agent to operate itself (`step`, `interact`, `push`). What is already in place (a
     * platform where it is, a lift at the player's floor) is still walked on.
     */
    val solvePuzzles: Boolean = true,
    /**
     * Actions may use what the player hasn't discovered: hidden items as targets (`hidden_item:N`, and listed among
     * the valid targets of an error). Walkthrough knowledge: false unless the agent is allowed a walkthrough.
     */
    val revealHidden: Boolean = true,
    /**
     * Where the ways out of a map lead is hidden: the agent explores and keeps its own notes. The library reads every
     * warp of the ROM, so without this one `go_to "Violet Gym"` crosses the whole warp graph (even a map-randomized
     * one) and the agent never has to explore. When true:
     * - the views list warps, holes and map-edge exits without their destination ([dev.kotlinds.pokemonclient.view.MapView]);
     * - `go_to` (and the walk of `interact`) only routes on the player's map: never through a warp, a hole or onto
     *   another map of the same area, except the exit it was asked to take (`warp:N`, `hole:N`, `exit:<direction>`);
     * - a target on another map (its name, `map:<id>`, x / y with `map`, a tile of a neighbouring map) is refused with
     *   [UnavailableReason.DESTINATIONS_HIDDEN], and no error or hint names another map (detours, blockers, fly
     *   suggestions are left out).
     * Nothing is remembered on the agent's behalf: a warp taken a hundred times is still listed without its
     * destination. Arriving somewhere is seen like in the game (the map's name). Fly is unchanged (the game itself
     * shows the visited towns on its fly map). False (the default): everything is shown and routed, as before.
     */
    val hideDestinations: Boolean = false,
)
