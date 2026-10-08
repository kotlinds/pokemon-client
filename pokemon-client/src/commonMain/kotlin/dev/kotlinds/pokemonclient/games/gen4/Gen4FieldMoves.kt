package dev.kotlinds.pokemonclient.games.gen4

import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.FieldMoveRule
import dev.kotlinds.pokemonclient.world.FieldMoveSupport

/**
 * The field moves of the Gen 4 engine: every Gen 4 game checks a field move outside battle the same way (a Pokémon of
 * the party knowing the move, and a badge: `FieldMoves_Check*` in Platinum src/field_move_tasks.c, `FieldMove_Check*`
 * in HGSS src/field_move.c), with the same move ids (one move table, include/constants/moves.h). Only which badge
 * allows which move differs: each game gives its [Badge] table ([Gen4Game.fieldMoveBadges]).
 */
object Gen4FieldMoves {

    /** A badge as a field move rule needs it: its [id] (bit of the badge flags, what the check uses), its [name] for messages. */
    data class Badge(val name: String, val id: Int)

    /**
     * The rule of field move [kind] in a game whose field moves are [badges] (each one with the badge it needs, null
     * for none: Teleport, Dig...); [FieldMoveSupport.NotInGame] when that game doesn't have the move (missing from
     * [badges]: Defog in HeartGold / SoulSilver, Whirlpool and Headbutt in Platinum).
     */
    fun rule(kind: FieldMoveKind, badges: Map<FieldMoveKind, Badge?>): FieldMoveSupport =
        if (kind !in badges) FieldMoveSupport.NotInGame else badges[kind].let { FieldMoveRule(MoveId(moveId(kind)), it?.name, it?.id) }

    /** The move id of [kind] (include/constants/moves.h, the same in every Gen 4 game). */
    fun moveId(kind: FieldMoveKind): Int = when (kind) {
        FieldMoveKind.CUT -> MOVE_CUT
        FieldMoveKind.FLY -> MOVE_FLY
        FieldMoveKind.SURF -> MOVE_SURF
        FieldMoveKind.STRENGTH -> MOVE_STRENGTH
        FieldMoveKind.WATERFALL -> MOVE_WATERFALL
        FieldMoveKind.ROCK_SMASH -> MOVE_ROCK_SMASH
        FieldMoveKind.WHIRLPOOL -> MOVE_WHIRLPOOL
        FieldMoveKind.ROCK_CLIMB -> MOVE_ROCK_CLIMB
        FieldMoveKind.FLASH -> MOVE_FLASH
        FieldMoveKind.TELEPORT -> MOVE_TELEPORT
        FieldMoveKind.DIG -> MOVE_DIG
        FieldMoveKind.SWEET_SCENT -> MOVE_SWEET_SCENT
        FieldMoveKind.MILK_DRINK -> MOVE_MILK_DRINK
        FieldMoveKind.SOFTBOILED -> MOVE_SOFTBOILED
        FieldMoveKind.HEADBUTT -> MOVE_HEADBUTT
        FieldMoveKind.CHATTER -> MOVE_CHATTER
        FieldMoveKind.DEFOG -> MOVE_DEFOG
    }

    private const val MOVE_CUT = 15
    private const val MOVE_FLY = 19
    private const val MOVE_SURF = 57
    private const val MOVE_STRENGTH = 70
    private const val MOVE_WATERFALL = 127
    private const val MOVE_ROCK_SMASH = 249
    private const val MOVE_WHIRLPOOL = 250
    private const val MOVE_ROCK_CLIMB = 431
    private const val MOVE_HEADBUTT = 29
    private const val MOVE_DIG = 91
    private const val MOVE_TELEPORT = 100
    private const val MOVE_SOFTBOILED = 135
    private const val MOVE_FLASH = 148
    private const val MOVE_MILK_DRINK = 208
    private const val MOVE_SWEET_SCENT = 230
    private const val MOVE_DEFOG = 432
    private const val MOVE_CHATTER = 448
}
