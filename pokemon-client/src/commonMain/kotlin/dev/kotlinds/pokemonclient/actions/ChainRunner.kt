package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.BattleKind
import dev.kotlinds.pokemonclient.state.BattleOutcome
import dev.kotlinds.pokemonclient.state.BattlerRef
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MonId
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Why a chain of actions (an agent's action and its `then` steps) stopped before its end although no step failed.
 * Typed, so an agent can react without parsing text: the steps left are given back as not done.
 */
sealed interface ChainStop {
    /** Machine-readable reason (stable, used in agent APIs). */
    val code: String

    /** One sentence for the agent. */
    val message: String

    /** Nothing changed in the game for [idleSeconds]: the chain is stuck, better look at the state. */
    data class Idle(val idleSeconds: Long) : ChainStop {
        override val code = "IDLE"
        override val message get() = "nothing changed in the game for $idleSeconds s: check the state, then go on"
    }

    /** The call had already run [elapsedSeconds] (the safety cap, whatever the progress). */
    data class TimeCap(val elapsedSeconds: Long) : ChainStop {
        override val code = "TIME_CAP"
        override val message get() = "the call had already run $elapsedSeconds s: check the state, then go on"
    }

    /** The opponent at [position] is another Pokémon than when the chain started (switched by the AI, sent after a K.O.). */
    data class FoeChanged(val position: BattlerRef, val before: String?, val now: String?) : ChainStop {
        override val code = "FOE_CHANGED"
        override val message get() = "the opponent at ${position.wire} changed (${before ?: "nobody"} → ${now ?: "nobody"}): " +
            "the next steps were chosen against the previous one, choose again"
    }

    /** The opponent at [position], there when the chain started, fainted. */
    data class FoeFainted(val position: BattlerRef, val name: String) : ChainStop {
        override val code = "FOE_FAINTED"
        override val message get() = "the opponent's $name (${position.wire}) fainted: choose the next steps against what comes next"
    }

    /**
     * The battle the chain started in is over ([outcome]: its last opponent fainted, the player lost, a capture or a
     * flight) while battle steps were left ([BattleWatch.isBattleStep]): they have nothing to act on and are dropped
     * ([ChainResult.dropped]), while the steps meant for the field (walking, talking to someone, using a Repel) go on.
     */
    data class BattleOver(val outcome: BattleEnd) : ChainStop {
        override val code get() = outcome.code
        override val message get() = outcome.message
    }

    /**
     * A `run` step couldn't escape: the battle goes on against [foe], while the steps after it were meant for after
     * the battle. Either the attempt failed ("Can't escape!": the turn was used, the foe attacked) or the foe traps
     * the player (Arena Trap, Shadow Tag, Mean Look: refused at once, no turn used).
     */
    data class EscapeFailed(val foe: String?) : ChainStop {
        override val code = "ESCAPE_FAILED"
        override val message get() = "couldn't escape${foe?.let { " from $it" } ?: ""}: the battle goes on (see the messages: a failed attempt " +
            "gives the foe a turn, a trapping foe prevents fleeing), the steps left were meant for after it, choose again"
    }

    /** One of the player's Pokémon that was battling when the chain started fainted. */
    data class OwnFainted(val mon: MonId, val name: String) : ChainStop {
        override val code = "OWN_FAINTED"
        override val message get() = "your $name ($mon) fainted: choose what to do next"
    }
}

/** How a battle ended, as far as the state after it tells ([ChainStop.BattleOver]). */
enum class BattleEnd(
    /** Machine-readable reason (stable, used in agent APIs as the chain's stop code). */
    val code: String,
    /** One sentence for the agent. */
    val message: String,
) {
    /** A trainer battle over with a Pokémon of the player still standing: won (a trainer battle has no other way out). */
    WON("BATTLE_WON", "the battle is won (the opponent's last Pokémon fainted): the battle steps left were dropped (nothing to act on), the field steps went on"),

    /** Every Pokémon of the player fainted. */
    LOST("BATTLE_LOST", "all your Pokémon fainted: the battle is lost, the battle steps left were dropped (nothing to act on), the field steps went on"),

    /** Another battle over (wild: the foe fainted, was caught, or someone fled): the messages tell which. */
    OVER("BATTLE_OVER", "the battle is over (the foe fainted, was caught or fled: see the messages): the battle steps left were dropped (nothing to act on), the field steps went on"),
    ;

    companion object {
        /**
         * The end of a battle of [kind] the game decided as [outcome]. A draw (the last foe and the player's last
         * Pokémon fainting together) is a loss: the game runs its battle-lost script for it ([BattleOutcome.DRAW]).
         * A wild battle won is only "over" (the foe fainted), like a capture or a flight.
         */
        fun of(outcome: BattleOutcome, kind: BattleKind): BattleEnd = when (outcome) {
            BattleOutcome.LOST, BattleOutcome.DRAW -> LOST
            BattleOutcome.WON -> if (kind == BattleKind.TRAINER) WON else OVER
            BattleOutcome.CAUGHT, BattleOutcome.PLAYER_FLED, BattleOutcome.FOE_FLED -> OVER
        }
    }
}

/** How long a chain may go on: [idle] without any progress in the game, [total] in all whatever the progress. */
data class ChainLimits(val idle: Duration, val total: Duration)

/**
 * The battle as a chain found it, to stop the chain when what its steps were chosen for is gone: an opponent replaced
 * or fainted, or one of the player's battling Pokémon fainted. A switch the chain itself makes isn't a reason (only
 * faints count on the player's side), but the Pokémon it sends in is watched like the others: `switch` to Ho-Oh then
 * `attack`, Ho-Oh knocked out by the switch turn, stops the chain with OWN_FAINTED. Once the battle is over, or decided
 * while still on screen ([dev.kotlinds.pokemonclient.state.BattleState.outcome]), [ended] tells how, for the battle
 * steps left to be dropped ([isBattleStep]).
 */
class BattleWatch private constructor(
    private val kind: BattleKind,
    private val foes: Map<BattlerRef, BattlerState>,
    private val own: Map<MonId, String>,
    /** The player's Pokémon already down on the field when the chain started (waiting to be replaced): no news. */
    private val downAtStart: Set<MonId>,
) {
    /**
     * The outcome first seen while the battle was still on screen: once it has left the screen the party may tell
     * otherwise (a draw is a loss, but the blackout heals the team before the chain looks again). The first one
     * sticks: a later battle the chain walks into is another one.
     */
    private var decided: BattleOutcome? = null
        set(value) {
            if (field == null) field = value
        }

    /**
     * Why the chain must stop in [now], the battle going on (null: go on, or the battle is over or decided: see
     * [ended]). A decided battle stops nothing: its last foe fainting is a win (or, with the player's last Pokémon, a
     * loss), told as the battle's end, not as FOE_FAINTED / OWN_FAINTED.
     */
    fun check(now: GameState): ChainStop? {
        val battle = now.battle ?: return null
        battle.outcome?.let { decided = it; return null }
        val current = battle.battlers.filter { !it.ref.isPlayerSide }.associateBy { it.ref }
        for (ref in (foes.keys + current.keys).distinct().sortedBy { it.ordinal }) {
            val was = foes[ref]
            val isNow = current[ref]
            when {
                was == null && isNow == null -> Unit
                // A Pokémon fainted earlier in the chain's battle and its spot was empty: someone new came in.
                was == null -> if (isNow!!.hp > 0) return ChainStop.FoeChanged(ref, null, name(isNow))
                isNow == null -> return ChainStop.FoeChanged(ref, name(was), null)
                !same(was, isNow) -> return ChainStop.FoeChanged(ref, name(was), name(isNow))
                isNow.hp == 0 -> return ChainStop.FoeFainted(ref, name(was))
            }
        }
        val partyHp = now.party.associate { it.id to it.hp }
        val battlerHp = battle.battlers.mapNotNull { b -> b.mon?.let { it to b.hp } }.toMap()
        own.forEach { (mon, name) ->
            if ((battlerHp[mon] ?: partyHp[mon]) == 0) return ChainStop.OwnFainted(mon, name)
        }
        // A Pokémon of the player that came in during the chain (its own switch, a replacement after a K.O. it started
        // on) and is down now: the steps after it were chosen for it too.
        battle.battlers.firstOrNull { it.ref.isPlayerSide && it.hp == 0 && it.mon != null && it.mon !in own && it.mon !in downAtStart }
            ?.let { return ChainStop.OwnFainted(it.mon!!, name(it)) }
        return null
    }

    /**
     * The game decided the battle as [outcome], seen frame by frame while the chain's steps ran (null: not seen; the
     * first decision seen since the chain started is its battle's). The chain itself only looks between steps, often
     * once the battle has left the screen: by then a loss can't be told from the state (the blackout heals the team
     * at once).
     */
    fun saw(outcome: BattleOutcome?) {
        if (outcome != null) decided = outcome
    }

    /**
     * How the chain's battle ended, when [now] is out of it or the game already decided it while it is still on
     * screen (null while it goes on).
     */
    fun ended(now: GameState): ChainStop.BattleOver? {
        val battle = now.battle ?: return ChainStop.BattleOver(end(now))
        val outcome = battle.outcome ?: return null
        decided = outcome
        return ChainStop.BattleOver(BattleEnd.of(outcome, kind))
    }

    /** How the battle ended, from the outcome seen while it was on screen, else from the state after it (see [BattleEnd]). */
    private fun end(now: GameState): BattleEnd {
        decided?.let { return BattleEnd.of(it, kind) }
        val team = now.party.filter { !it.isEgg }
        return when {
            team.isNotEmpty() && team.all { it.hp == 0 } -> BattleEnd.LOST
            kind == BattleKind.TRAINER -> BattleEnd.WON
            else -> BattleEnd.OVER
        }
    }

    companion object {
        /** The battle of [state] (null out of battle). Only Pokémon still standing are watched. */
        fun of(state: GameState): BattleWatch? {
            val battle = state.battle ?: return null
            val foes = battle.battlers.filter { !it.ref.isPlayerSide && it.hp > 0 }.associateBy { it.ref }
            val own = battle.battlers.filter { it.ref.isPlayerSide && it.hp > 0 }.mapNotNull { b -> b.mon?.let { it to name(b) } }.toMap()
            val down = battle.battlers.filter { it.ref.isPlayerSide && it.hp == 0 }.mapNotNull { it.mon }.toSet()
            return BattleWatch(battle.kind, foes, own, down).also { watch -> battle.outcome?.let { watch.decided = it } }
        }

        /**
         * Steps that only exist in a battle, pointless once it is over: a move, a switch, keep battling, fleeing, a
         * ball, and an item of the bag's battle pockets (X Attack, Guard Spec., Poké Doll...: the `battle_items` and
         * `balls` pockets of [state]'s bag, by pocket id). Every other step is meant for the field (or for the end of
         * the battle: its messages, learning a move) and goes on, an item usable out of battle too (a Potion, a
         * Repel) included: what it does still makes sense after the battle.
         */
        fun isBattleStep(action: GameAction, state: GameState): Boolean = when (action) {
            is GameAction.Attack, is GameAction.Switch, is GameAction.KeepBattling, is GameAction.Run, is GameAction.ThrowBall -> true
            is GameAction.UseItem -> action.uses.all { use -> pocketOf(use.item, state) in BATTLE_ONLY_POCKETS }
            else -> false
        }

        /** The id of the bag pocket holding [item] (by id or name, like the recipes), null when it isn't in the bag. */
        private fun pocketOf(item: ItemRef, state: GameState): String? =
            state.bag.orEmpty().firstOrNull { pocket -> pocket.items.any { matchesRef(item.raw, "item", it.item.id.value, it.item.name) } }?.name

        /** Bag pockets whose items only work in a battle. */
        private val BATTLE_ONLY_POCKETS = setOf("battle_items", "balls")

        private fun name(b: BattlerState) = b.nickname ?: b.species.name

        /**
         * The same Pokémon: by personality and party slot when the game tells them, else by what is shown (species,
         * level, nickname, max HP). The personality alone isn't enough: a trainer's Pokémon get one computed from the
         * trainer, the species and the level (`src/trainer_data.c`), so a Doduo replacing a fainted Doduo of the same
         * level has the same one; only the party slot it was sent from differs.
         */
        private fun same(a: BattlerState, b: BattlerState): Boolean = when {
            a.partySlot != null && b.partySlot != null && a.partySlot != b.partySlot -> false
            a.personality != null && b.personality != null -> a.personality == b.personality
            else -> a.species.id == b.species.id && a.level == b.level && a.nickname == b.nickname && a.maxHp == b.maxHp
        }
    }
}

/**
 * What a chain did: the steps [performed] (keys) with their [details], the step that [failed], the steps [skipped]
 * and why ([stop]), and the battle steps [dropped] on the way because the battle had ended ([droppedBecause]).
 */
data class ChainResult(
    val performed: List<String>,
    val details: List<String>,
    val failed: Pair<GameAction, ActionError>?,
    /** The steps left when the chain stopped early ([stop]) or failed: none of them was started. */
    val skipped: List<GameAction>,
    /** Why the chain stopped early without a failure (null when it ran to its end or a step failed). */
    val stop: ChainStop?,
    /** Battle steps not run because the battle was over by then, while the chain went on with its field steps. */
    val dropped: List<GameAction> = emptyList(),
    /** How the battle ended, when steps were [dropped]. */
    val droppedBecause: ChainStop.BattleOver? = null,
)

/**
 * Runs a chain of actions as one sequence (see [ActionChains]): item uses merged into one bag session, a spare
 * `advance_dialogue` skipped, and stops:
 * - at the first failed step;
 * - after a step that was done but makes the next ones pointless ([ActionOutcome.Done.stopsChain]: a failed escape);
 * - before a step, when the battle changed under the chain ([BattleWatch]: the foe replaced or fainted, one of the
 *   player's Pokémon fainted); also when a step was refused before doing anything (an attack on the "Will you
 *   switch?" prompt that came up after the previous step was checked) and the battle turns out to have changed: the
 *   step is given back as not done with that reason, not as a failure;
 * - before a step, when the game made no progress for [ChainLimits.idle] ([idle], from the recorder's
 *   `ProgressClock`) or the chain has run [ChainLimits.total] in all: long chains go on while things keep happening.
 *
 * Once the battle the chain started in is over, or decided while its last messages are still on screen (the last foe
 * fainting together with the player's Pokémon: a win, not FOE_FAINTED), its battle steps left
 * ([BattleWatch.isBattleStep]) are dropped ([ChainResult.dropped], with how it ended) and the field steps go on:
 * `attack, attack, interact` ending the battle with the first attack still talks to the trainer behind.
 *
 * A battle is only checked once the game waits for input again: a step may come back while its turn still plays out
 * (the step's own settling has a frame budget: a long recipe leaves little), the foe's attack and the K.O. it causes
 * not in RAM yet. Before checking, [settle] lets the game run by itself (frames only, never a button) until it waits
 * for input, as the next step's recipe would do anyway before acting ([ActionRegistry] settles a busy screen before
 * refusing): the check then sees what that step will find. Raw input steps (press, touch, drag, wait,
 * advance_dialogue) are about timing and act on the busy screen itself: no settling before them.
 *
 * [observe] reads the state, [settle] lets the game settle then reads it (by default only reads: no frame runs),
 * [execute] carries out one step (with its index: the first one is checked against the agent's version), [onStep] is
 * told before each step starts (progress for remote agents), [decided] tells how the game decided the battle as seen
 * frame by frame since the chain started (the recorder's [dev.kotlinds.pokemonclient.state.GameEvent.BattleDecided]):
 * without it a lost battle that left the screen during a step reads as won (the team is healed by the blackout).
 */
class ChainRunner(
    private val observe: suspend () -> GameState,
    private val execute: suspend (action: GameAction, index: Int) -> ActionOutcome,
    private val settle: suspend () -> GameState = observe,
    private val decided: () -> BattleOutcome? = { null },
    private val limits: ChainLimits? = null,
    private val idle: () -> Duration = { Duration.ZERO },
    private val timeSource: TimeSource = TimeSource.Monotonic,
    private val onStep: (index: Int, total: Int, action: GameAction) -> Unit = { _, _, _ -> },
) {
    suspend fun run(requested: List<GameAction>): ChainResult {
        require(requested.isNotEmpty()) { "no action" }
        val start = observe()
        val actions = ActionChains.coalesce(requested, inBattle = start.battle != null)
        val watch = BattleWatch.of(start)
        val started = timeSource.markNow()
        val performed = mutableListOf<String>()
        val details = mutableListOf<String>()
        val dropped = mutableListOf<GameAction>()
        var ended: ChainStop.BattleOver? = null

        fun result(failed: Pair<GameAction, ActionError>? = null, skipped: List<GameAction> = emptyList(), stop: ChainStop? = null) =
            ChainResult(performed, details, failed, skipped, stop, dropped.toList(), ended)

        /** True when [action] is a battle step and the chain's battle is over in [now]: it is dropped. */
        fun drops(action: GameAction, now: GameState): Boolean {
            watch?.saw(decided())
            val over = watch?.ended(now) ?: return false
            if (!BattleWatch.isBattleStep(action, now)) return false
            ended = ended ?: over
            dropped += action
            return true
        }

        for ((index, action) in actions.withIndex()) {
            if (index > 0) {
                val now = observe().let { if (busyBattle(it) && !isRawInput(action)) settle() else it }
                if (drops(action, now)) continue
                stopBefore(watch, now, started)?.let { stop -> return result(skipped = actions.drop(index), stop = stop) }
            }
            onStep(index, actions.size, action)
            when (val outcome = execute(action, index)) {
                is ActionOutcome.Done -> {
                    performed += action.key
                    outcome.detail?.let { details += if (actions.size > 1) "${action.key}: $it" else it }
                    val left = actions.drop(index + 1)
                    outcome.stopsChain?.takeIf { left.isNotEmpty() }?.let { stop -> return result(skipped = left, stop = stop) }
                }
                is ActionOutcome.Failed -> {
                    val after = observe()
                    // A spare advance_dialogue (the dialogue ended sooner than expected): nothing to read, go on.
                    if (index > 0 && ActionChains.isSpareAdvance(action, outcome.error, after)) {
                        details += "${action.key}: skipped, no dialogue left"
                        continue
                    }
                    // Refused before doing anything because the battle moved on after the check: the screen it needs
                    // is gone (a K.O. and "Will you switch?" came up meanwhile, or the battle ended). The step wasn't
                    // started: like a stop before it, not a failure.
                    if (index > 0 && outcome.error is ActionError.Unavailable) {
                        if (drops(action, after)) continue
                        watch?.check(after)?.let { stop -> return result(skipped = actions.drop(index), stop = stop) }
                    }
                    return result(failed = action to outcome.error, skipped = actions.drop(index + 1))
                }
            }
        }
        return result()
    }

    /** The battle still plays out by itself in [state] (a turn, faint messages, the end of the battle). */
    private fun busyBattle(state: GameState): Boolean = state.battle != null && state.screen.awaiting != Awaiting.INPUT

    /** Steps that press, touch or wait themselves: they act on the screen as it is, busy or not. */
    private fun isRawInput(action: GameAction): Boolean = when (action) {
        is GameAction.Press, is GameAction.Touch, is GameAction.Drag, is GameAction.Wait, GameAction.AdvanceDialogue -> true
        else -> false
    }

    /** Why the next step mustn't start in [now] (see the class). */
    private fun stopBefore(watch: BattleWatch?, now: GameState, started: kotlin.time.TimeMark): ChainStop? {
        watch?.check(now)?.let { return it }
        val limits = limits ?: return null
        val elapsed = started.elapsedNow()
        if (elapsed >= limits.total) return ChainStop.TimeCap(elapsed.inWholeSeconds)
        val quiet = idle()
        if (quiet >= limits.idle) return ChainStop.Idle(quiet.inWholeSeconds)
        return null
    }
}
