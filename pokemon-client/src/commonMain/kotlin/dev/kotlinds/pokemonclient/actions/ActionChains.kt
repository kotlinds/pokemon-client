package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.Screen

/**
 * Rules for running a chain of actions (an agent's action and its `then` steps) as one sequence, the way a player
 * would: without closing a menu only to reopen it, and without stopping on a step that has become pointless.
 */
object ActionChains {

    /**
     * Merges consecutive steps that work in the same menu into one session when out of battle, instead of closing the
     * menu and reopening it for each one:
     * - `use_item` steps into one bag session ([GameAction.UseItem.batch]); in battle every item use takes a turn, so
     *   they stay separate;
     * - `deposit`, `withdraw` and `pc` steps into one `pc` session ([GameAction.Pc]): the PC is booted once and its
     *   box screen kept between operations of the same mode.
     */
    fun coalesce(actions: List<GameAction>, inBattle: Boolean): List<GameAction> {
        if (inBattle) return actions
        val merged = mutableListOf<GameAction>()
        for (action in actions) {
            val previous = merged.lastOrNull()
            val pcBefore = previous?.let(::pcOperations)
            val pcNow = pcOperations(action)
            when {
                action is GameAction.UseItem && previous is GameAction.UseItem ->
                    merged[merged.lastIndex] = previous.copy(batch = previous.batch + action.uses)
                pcBefore != null && pcNow != null -> merged[merged.lastIndex] = GameAction.Pc(pcBefore + pcNow)
                else -> merged += action
            }
        }
        return merged
    }

    /** The PC operations [action] stands for (a `deposit`, a `withdraw`, a `pc` session), null for any other action. */
    private fun pcOperations(action: GameAction): List<PcOperation>? = when (action) {
        is GameAction.Deposit -> listOf(PcOperation.Deposit(action.mon))
        is GameAction.Withdraw -> listOf(PcOperation.Withdraw(action.mon))
        is GameAction.Pc -> action.operations
        else -> null
    }

    /**
     * True when a failed step of a chain was only a spare `advance_dialogue`: the dialogue ended sooner than the
     * agent expected and the player can walk again. There is nothing left to read, so the chain goes on (e.g. with
     * `save_game`) instead of stopping.
     */
    fun isSpareAdvance(action: GameAction, error: ActionError, after: GameState): Boolean =
        action is GameAction.AdvanceDialogue &&
            error is ActionError.Unavailable &&
            after.field != null &&
            after.screen.let { it is Screen.Overworld && it.awaiting == Awaiting.INPUT }
}
