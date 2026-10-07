package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.data.MoveCategory
import dev.kotlinds.pokemonclient.state.BattlerState
import dev.kotlinds.pokemonclient.state.KnownMove
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.VolatileStatus

/**
 * Which moves the active Pokémon can be told to use this turn, read from the battle state before anything is pressed
 * (one source for `attack`'s availability, its choices and its recipe):
 * - a move without PP, or the one held by Disable, can't be chosen; under Taunt neither can a move without base power
 *   (`StruggleCheck`, src/battle/overlay_12_0224E4FC.c: `moveData.power == 0`, the status moves);
 * - under Encore only the encored move is played, and the game skips the move list: FIGHT alone plays it
 *   (Gen 4 `battle_controller_player.c`, `unk88.encoredMove`);
 * - when no move can be chosen the game uses Struggle, also straight from FIGHT (`StruggleCheck`).
 * What the state can't tell (Torment, Imprison, Gravity, Heal Block, a Choice item) the move list itself refuses: its
 * entries aren't selectable, and the recipe backs out of it with a typed error; when they leave no move at all, the
 * game skips the list and uses Struggle (`battle_controller_player.c`: `StruggleCheck(...) == 15`), which the recipe
 * reads on screen ([STRUGGLED_UNSEEN]).
 */
internal object BattleMoveChoice {

    /** Struggle (move 165 in every generation): what a Pokémon uses when none of its moves can be chosen. */
    val STRUGGLE = MoveId(165)

    /** The move [actor] is held to by Encore, when the game says which. */
    fun encored(actor: BattlerState): VolatileStatus.Encored? = actor.volatile.filterIsInstance<VolatileStatus.Encored>().firstOrNull()

    /** The move Disable holds, when the game says which. */
    private fun disabled(actor: BattlerState): VolatileStatus.Disabled? = actor.volatile.filterIsInstance<VolatileStatus.Disabled>().firstOrNull()

    /** Why [move] can't be chosen now (no PP, Disable, Taunt), null when it can. */
    private fun blocked(actor: BattlerState, move: KnownMove): ActionError.Unavailable? {
        if (move.pp == 0) return ActionError.Unavailable(UnavailableReason.NO_PP, "${move.move.name} has no PP left")
        disabled(actor)?.takeIf { it.move?.id == move.move.id }?.let {
            return ActionError.Unavailable(UnavailableReason.DISABLED, "${move.move.name} is disabled (${it.turns} more turns)", "use another move")
        }
        // The game's rule is the move's base power (0: the status moves), not its category; the category only when
        // the power isn't known.
        if (VolatileStatus.Taunted in actor.volatile && (move.power?.let { it == 0 } ?: (move.category == MoveCategory.STATUS))) {
            return ActionError.Unavailable(UnavailableReason.TAUNTED, "${actor.nickname ?: actor.species.name} is taunted: ${move.move.name} (no base power) can't be chosen", "use a damaging move")
        }
        return null
    }

    /** The moves [actor] can be told to use (empty: it will Struggle). */
    fun usable(actor: BattlerState): List<KnownMove> = actor.moves.filter { blocked(actor, it) == null }

    /** True when no move can be chosen: FIGHT makes it Struggle. */
    fun struggles(actor: BattlerState): Boolean = actor.moves.isNotEmpty() && usable(actor).isEmpty()

    /**
     * The moves `attack` offers: the encored move alone under Encore, Struggle when nothing else can be chosen, else
     * the usable moves (`move:<id>` and a label).
     */
    fun choices(actor: BattlerState): List<Pair<String, String>> {
        encored(actor)?.move?.let { m -> return listOf("move:${m.id.value}" to "${m.name} (Encore: ${encored(actor)?.turns} more turns, FIGHT plays it)") }
        if (struggles(actor)) return listOf("move:${STRUGGLE.value}" to "Struggle (no move can be chosen)")
        return usable(actor).map { "move:${it.move.id.value}" to "${it.move.name} (${it.type ?: "?"}, ${it.pp}/${it.maxPp} PP)" }
    }

    /**
     * Why `attack` with [ref] can't be done by [actor] this turn, before anything is pressed: a move it doesn't know
     * ([ActionError.InvalidParameter], listing its moves), another move than the encored one ([UnavailableReason.ENCORED]),
     * a move without PP, disabled or taunted. Struggle is accepted only when no move can be chosen. Null: go on.
     */
    fun refusal(actor: BattlerState, ref: MoveRef): ActionError? {
        if (actor.moves.isEmpty()) return null
        val known = actor.moves.firstOrNull { matchesRef(ref.raw, "move", it.move.id.value, it.move.name) }
        val struggling = struggles(actor)
        if (known == null) {
            if (struggling && isStruggle(ref)) return null
            return ActionError.InvalidParameter("move", ref.raw, choices(actor).map { (id, label) -> "$id = $label" })
        }
        encored(actor)?.let { encore ->
            val held = encore.move ?: return@let
            if (held.id != known.move.id) return ActionError.Unavailable(
                UnavailableReason.ENCORED,
                "${actor.nickname ?: actor.species.name} is under Encore: it must use ${held.name} (${encore.turns} more turns)",
                "attack move:${held.id.value} (FIGHT plays it at once)",
            )
            return null
        }
        return blocked(actor, known)?.let { error ->
            if (struggling) error.copy(hint = "no move can be chosen: attack move:${STRUGGLE.value} (Struggle)") else error
        }
    }

    /** [ref] names Struggle (`move:165` or its English name; the game's own name isn't known without its data). */
    fun isStruggle(ref: MoveRef): Boolean = matchesRef(ref.raw, "move", STRUGGLE.value, "Struggle")

    /**
     * What FIGHT alone did when the game skipped the move list for [actor]: the encored move, or Struggle. Null when
     * the move list should have opened.
     */
    fun skippedList(actor: BattlerState): String? {
        encored(actor)?.let { e -> return "Encore: FIGHT used ${e.move?.name ?: "the encored move"} (${e.turns} more turns)" }
        if (struggles(actor)) return "no move could be chosen: FIGHT used Struggle"
        return null
    }

    /**
     * What FIGHT did when the game skipped the move list although the state allowed a move: what the state can't
     * tell (Torment, Imprison, Gravity, Heal Block, a Choice item) left none, so the game used Struggle.
     */
    const val STRUGGLED_UNSEEN = "the game skipped the move list: no move could be chosen this turn (Torment, Imprison, Gravity, " +
        "Heal Block or a Choice item), FIGHT used Struggle"
}
