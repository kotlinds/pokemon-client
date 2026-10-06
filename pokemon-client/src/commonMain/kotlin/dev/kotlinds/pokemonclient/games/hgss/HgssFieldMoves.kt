package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.games.gen4.Gen4FieldMoves.Badge
import dev.kotlinds.pokemonclient.world.FieldMoveKind

/**
 * The badge each field move of HeartGold / SoulSilver needs outside battle (`FieldMove_Check*` in src/field_move.c,
 * and `CheckBadge` in the std scripts of scr_seq_0146.s); the moves are the Gen 4 ones
 * ([dev.kotlinds.pokemonclient.games.gen4.Gen4FieldMoves]). Badges are checked by id
 * ([dev.kotlinds.pokemonclient.state.PlayerInfo.badgeIds]: bit n of the Johto byte, 8 + n for Kanto,
 * include/constants/badges.h); the names are for messages.
 */
internal object HgssFieldMoves {

    val BADGES: Map<FieldMoveKind, Badge> = mapOf(
        FieldMoveKind.ROCK_SMASH to Badge("Zephyr", 0),
        FieldMoveKind.CUT to Badge("Hive", 1),
        FieldMoveKind.STRENGTH to Badge("Plain", 2),
        FieldMoveKind.SURF to Badge("Fog", 3),
        FieldMoveKind.FLY to Badge("Storm", 4),
        FieldMoveKind.WHIRLPOOL to Badge("Glacier", 6),
        FieldMoveKind.WATERFALL to Badge("Rising", 7),
        FieldMoveKind.ROCK_CLIMB to Badge("Earth", 15),
    )
}
