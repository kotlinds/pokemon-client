package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MoveId

/**
 * What a game says about one field move ([dev.kotlinds.pokemonclient.PokemonGame.fieldMoveRule]): its [FieldMoveRule]
 * when it has the move, else whether it is known not to have it ([NotInGame]) or doesn't say ([Unknown]). Two
 * different answers: a move the game doesn't have is an action that doesn't exist in it
 * ([dev.kotlinds.pokemonclient.actions.Availability.NotInThisGame]), a move whose rule isn't declared is only unknown.
 */
sealed interface FieldMoveSupport {
    /** The game doesn't have this field move (Defog in HeartGold / SoulSilver; Whirlpool, Headbutt in Platinum). */
    data object NotInGame : FieldMoveSupport

    /** The game doesn't declare its field moves (the default of [dev.kotlinds.pokemonclient.PokemonGame.fieldMoveRule]): nothing is known. */
    data object Unknown : FieldMoveSupport
}

/**
 * What a game asks before a field move can be used outside battle: a Pokémon of the party knowing [move], and the
 * badge [badge] (as [dev.kotlinds.pokemonclient.state.PlayerInfo.badges] lists it) when it needs one. Games give one
 * per move they have ([dev.kotlinds.pokemonclient.PokemonGame.fieldMoveRule]); HGSS checks them in `src/field_move.c`.
 * Where the move works (a dark cave for Flash, outside towns for Teleport...) is the game's own check when it's used.
 */
data class FieldMoveRule(
    val move: MoveId,
    /** The badge's name, for messages (display only); null when the move needs no badge (Teleport, Dig...). */
    val badge: String?,
    /**
     * The badge's id ([dev.kotlinds.pokemonclient.state.PlayerInfo.badgeIds]): what the check uses when the game gives
     * one (never the name, which depends on the language). Null: the name is compared (games without badge ids).
     */
    val badgeId: Int? = null,
) : FieldMoveSupport {
    /** True when [player] owns the badge (by id when known, else by name), or no badge is needed. */
    fun badgeOwned(player: dev.kotlinds.pokemonclient.state.PlayerInfo): Boolean = when {
        badgeId != null -> badgeId in player.badgeIds
        badge != null -> badge in player.badges
        else -> true
    }
}

/** Whether the party can use a field move right now, and when not, what is missing. */
sealed interface FieldMoveAccess {
    /** A Pokémon knows the move ([monName], party slot [slot]) and the badge is owned. */
    data class Usable(val slot: Int, val monName: String) : FieldMoveAccess

    /** No Pokémon of the party knows the move. */
    data object NoPokemon : FieldMoveAccess

    /** A Pokémon knows it, but the badge [badge] isn't owned yet. */
    data class NoBadge(val badge: String) : FieldMoveAccess

    /** The game doesn't have this field move ([FieldMoveSupport.NotInGame]): no action using it exists in the game. */
    data object NotInGame : FieldMoveAccess

    /** The game doesn't say whether it has this field move nor what it needs ([FieldMoveSupport.Unknown]). */
    data object Unknown : FieldMoveAccess

    /**
     * The game has the move, but the library can't use it there yet: it doesn't read this game's party (whether a
     * Pokémon knows it) nor decode the party menu it is used from (Platinum, for now). Said so, never "no Pokémon".
     */
    data object NotSupported : FieldMoveAccess
}

/** Field moves usable now and why the others aren't, read from the party and the badges. */
object FieldMoves {

    /**
     * The access to every [FieldMoveKind] in [state], with the game's [rule]s; unless the game reads its party and
     * decodes its party menu ([partyRead] false): then every move it has is [FieldMoveAccess.NotSupported].
     */
    fun access(state: GameState, rule: (FieldMoveKind) -> FieldMoveSupport): Map<FieldMoveKind, FieldMoveAccess> = access(state, partyRead = true, rule)

    /** [access], [partyRead] telling whether the game reads its party (see above). */
    fun access(state: GameState, partyRead: Boolean, rule: (FieldMoveKind) -> FieldMoveSupport): Map<FieldMoveKind, FieldMoveAccess> =
        FieldMoveKind.entries.associateWith { kind ->
            val r = rule(kind)
            if (!partyRead && r is FieldMoveRule) FieldMoveAccess.NotSupported else access(state, r)
        }

    /**
     * The access of [state]: what its game read ([GameState.fieldMoves], the one source of the action list and the
     * walks); a state built without its game (null, tests) is read now with the game's [rule], the same way.
     */
    fun of(state: GameState, rule: (FieldMoveKind) -> FieldMoveSupport): Map<FieldMoveKind, FieldMoveAccess> =
        state.fieldMoves ?: access(state, rule)

    /** The access to one field move with what its game says of it ([support]: its rule, or why there is none). */
    fun access(state: GameState, support: FieldMoveSupport): FieldMoveAccess {
        val rule = when (support) {
            is FieldMoveRule -> support
            FieldMoveSupport.NotInGame -> return FieldMoveAccess.NotInGame
            FieldMoveSupport.Unknown -> return FieldMoveAccess.Unknown
        }
        // The game takes the first Pokémon knowing the move (GetPartySlotWithMove).
        val mon = knowers(state, rule.move).firstOrNull() ?: return FieldMoveAccess.NoPokemon
        val badge = rule.badge
        if (badge != null) {
            val player = state.player ?: return FieldMoveAccess.NoBadge(badge)
            if (!rule.badgeOwned(player)) return FieldMoveAccess.NoBadge(badge)
        }
        return FieldMoveAccess.Usable(mon.slot, mon.displayName)
    }

    /** The Pokémon of the party knowing [move], in party order: eggs never count (the game skips them). */
    fun knowers(state: GameState, move: MoveId): List<dev.kotlinds.pokemonclient.state.PartyMon> =
        state.party.filter { mon -> !mon.isEgg && mon.moves.any { it.move.id == move } }

    /** The moves of [access] that can be used now. */
    fun usable(access: Map<FieldMoveKind, FieldMoveAccess>): Set<FieldMoveKind> =
        access.filterValues { it is FieldMoveAccess.Usable }.keys
}
