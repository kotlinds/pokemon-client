package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.KnownMove
import dev.kotlinds.pokemonclient.state.MonId
import dev.kotlinds.pokemonclient.state.MoveId
import dev.kotlinds.pokemonclient.state.Named
import dev.kotlinds.pokemonclient.state.PartyMon
import dev.kotlinds.pokemonclient.state.PlayerInfo
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.world.FieldMoveAccess
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.FieldMoves
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** HeartGold / SoulSilver's field move rules: the Gen 4 move ids, HGSS's badges by id (src/field_move.c). */
class HgssFieldMovesTest {

    @Test
    fun everyFieldMoveHasItsMoveAndBadge() {
        val game = HgssGame(HgssVersion.HEARTGOLD_US)
        // Every Gen 4 field move but Platinum's Defog.
        assertNull(game.fieldMoveRule(FieldMoveKind.DEFOG))
        val rules = (FieldMoveKind.entries - FieldMoveKind.DEFOG).associateWith { game.fieldMoveRule(it)!! }
        assertEquals(19 to 4, rules.getValue(FieldMoveKind.FLY).let { it.move.value to it.badgeId }, "Fly: the Storm Badge")
        assertEquals(57 to 3, rules.getValue(FieldMoveKind.SURF).let { it.move.value to it.badgeId }, "Surf: the Fog Badge")
        assertEquals(15 to 1, rules.getValue(FieldMoveKind.CUT).let { it.move.value to it.badgeId }, "Cut: the Hive Badge")
        assertEquals(431 to 15, rules.getValue(FieldMoveKind.ROCK_CLIMB).let { it.move.value to it.badgeId }, "Rock Climb: the Earth Badge")
        assertEquals("Storm", rules.getValue(FieldMoveKind.FLY).badge)
        // The moves of use_field_move need no badge (FieldMove_CheckTeleport / Dig / SweetScent...).
        assertEquals(100 to null, rules.getValue(FieldMoveKind.TELEPORT).let { it.move.value to it.badge })
        assertEquals(91 to null, rules.getValue(FieldMoveKind.DIG).let { it.move.value to it.badge })
        assertEquals(230 to null, rules.getValue(FieldMoveKind.SWEET_SCENT).let { it.move.value to it.badge })
    }

    /**
     * The access to the field moves has one source: what the game read ([GameState.fieldMoves], every kind present).
     * A state built without its game (null: tests, hand-made states) is read with the game's rules, never taken as "no
     * field move" (which would hide Fly and drop Surf from the routes).
     */
    @Test
    fun theAccessIsTheGamesReadingAndAStateWithoutOneIsReadWithTheRules() {
        val game = HgssGame(HgssVersion.HEARTGOLD_US)
        val flyer = PartyMon(
            MonId(1, 1), 0, Named(SpeciesId(18), "PIDGEOT"), null, 40, 100, 100, null, listOf("Normal", "Flying"), null, null,
            listOf(KnownMove(Named(MoveId(19), "Fly"), 15, 15, "Flying")), emptyMap(), 0, null, false,
        )
        val bare = GameState(0, Screen.Overworld(null, Awaiting.INPUT), PlayerInfo("ACE", 0, listOf("Storm"), 1, badgeIds = setOf(4)), listOf(flyer), null, null, null)
        assertNull(bare.fieldMoves)
        val read = FieldMoves.of(bare, game::fieldMoveRule)
        assertEquals(FieldMoveAccess.Usable(0, "PIDGEOT"), read[FieldMoveKind.FLY])
        assertEquals(FieldMoveAccess.NoPokemon, read[FieldMoveKind.SURF])
        // A move without a badge is usable as soon as a Pokémon knows it; one the game doesn't have is Unknown.
        val teleporter = flyer.copy(moves = flyer.moves + KnownMove(Named(MoveId(100), "Teleport"), 20, 20, "Psychic"))
        assertEquals(FieldMoveAccess.Usable(0, "PIDGEOT"), FieldMoves.of(bare.copy(party = listOf(teleporter), player = null), game::fieldMoveRule)[FieldMoveKind.TELEPORT])
        assertEquals(FieldMoveAccess.Unknown, read[FieldMoveKind.DEFOG])
        assertEquals(FieldMoveKind.entries.toSet(), read.keys)
        // A state the game read keeps its own reading.
        val filled = bare.copy(fieldMoves = mapOf(FieldMoveKind.FLY to FieldMoveAccess.Unknown))
        assertEquals(mapOf(FieldMoveKind.FLY to FieldMoveAccess.Unknown), FieldMoves.of(filled, game::fieldMoveRule))
    }
}
