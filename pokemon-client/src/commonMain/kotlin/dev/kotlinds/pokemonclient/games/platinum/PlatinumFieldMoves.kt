package dev.kotlinds.pokemonclient.games.platinum

import dev.kotlinds.pokemonclient.games.gen4.Gen4FieldMoves.Badge
import dev.kotlinds.pokemonclient.world.FieldMoveKind

/**
 * The badge each field move of Platinum needs outside battle (`FieldMoves_Check*` in src/field_move_tasks.c, badge
 * ids `BADGE_ID_*` of the decomp's generated/badges.txt); the moves are the Gen 4 ones
 * ([dev.kotlinds.pokemonclient.games.gen4.Gen4FieldMoves]). Platinum has no Whirlpool field move.
 */
internal object PlatinumFieldMoves {

    val BADGES: Map<FieldMoveKind, Badge?> = mapOf(
        FieldMoveKind.ROCK_SMASH to Badge("Coal", 0),
        FieldMoveKind.CUT to Badge("Forest", 1),
        FieldMoveKind.FLY to Badge("Cobble", 2),
        FieldMoveKind.SURF to Badge("Fen", 3),
        FieldMoveKind.DEFOG to Badge("Relic", 4),
        FieldMoveKind.STRENGTH to Badge("Mine", 5),
        FieldMoveKind.ROCK_CLIMB to Badge("Icicle", 6),
        FieldMoveKind.WATERFALL to Badge("Beacon", 7),
        // No badge in their checks (FieldMoves_CheckFlash / Teleport / Dig / SweetScent / Chatter); Milk Drink and
        // Softboiled are the party menu's own.
        FieldMoveKind.FLASH to null,
        FieldMoveKind.TELEPORT to null,
        FieldMoveKind.DIG to null,
        FieldMoveKind.SWEET_SCENT to null,
        FieldMoveKind.CHATTER to null,
        FieldMoveKind.MILK_DRINK to null,
        FieldMoveKind.SOFTBOILED to null,
    )
}
