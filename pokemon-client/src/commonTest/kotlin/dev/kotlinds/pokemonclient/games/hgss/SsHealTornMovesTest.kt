package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.games.gen4.Gen4RomBytes
import dev.kotlinds.pokemonclient.games.gen4.Gen4Pokemon
import dev.kotlinds.pokemonclient.games.gen4.Gen4Structs as S
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The nurse restores the PP of the party (`ss_center_party`: the League Center right after a heal). SetMonData decrypts
 * a Pokémon, writes the new PP, then rewrites the checksum and encrypts it again (src/pokemon.c:886): a frame can end
 * with the blocks already holding the new PP in plain while the header still has the old checksum. Every torn way of
 * reading that is tried ([Gen4Pokemon.decode]), and a few match the old checksum by accident with garbage words: before
 * the moves were checked, such a reading passed as "PILOSWINE learned MOVE_57918, forgot Strength" (the Vermilion bug).
 */
class SsHealTornMovesTest {

    private val party = HgssReader(HgssFixtures.load("ss_center_party"), HgssVersion.HEARTGOLD_US).partyRaw()

    @Test
    fun theFixtureHoldsTheHealedParty() {
        assertEquals(6, party.size)
        party.forEach { assertTrue(HgssMonCheck.isPlausible(Gen4Pokemon.decode(it, HgssMonCheck::isPlausible)!!)) }
    }

    @Test
    fun aReadingCaughtWhileThePpAreRestoredNeverShowsOtherMoves() {
        var accepted = 0
        var trials = 0
        for (raw in party) {
            val truth = Gen4Pokemon.decode(raw, HgssMonCheck::isPlausible)!!
            val moves = (0 until 4).map { truth.move(it) }
            val checksum = Gen4RomBytes.u16(raw, 6)
            val plain = raw.copyOfRange(8, 0x88).also { Gen4Pokemon.crypt(it, 0, it.size, checksum.toLong()) }
            val blockB = S.POKEMON_BLOCK_OFFSETS[((truth.personality shr 13) and 31).toInt()][1]
            // The PP before the heal (anything lower), the restored ones written in plain, the header not updated yet.
            for (pp0 in 0..30) for (pp1 in 0..30) for (pp2 in listOf(0, 4, 9)) {
                val healed = plain.copyOf().also { it[blockB + 8] = pp0.toByte(); it[blockB + 9] = pp1.toByte(); it[blockB + 10] = pp2.toByte() }
                val torn = raw.copyOf().also { healed.copyInto(it, 8) }
                trials++
                val read = Gen4Pokemon.decode(torn, HgssMonCheck::isPlausible) ?: continue
                if (!HgssMonCheck.isPlausible(read)) continue
                accepted++
                assertEquals(moves, (0 until 4).map { read.move(it) }, "an accepted torn reading of species ${read.species}")
                assertEquals(truth.isEgg, read.isEgg)
            }
        }
        assertTrue(trials > 10_000 && accepted < trials, "$accepted accepted of $trials")
    }

    @Test
    fun movesNoPokemonCanKnowAreRejected() {
        // PILOSWINE: Strength, Ice Shard, Icy Wind, Mud Bomb.
        val truth = Gen4Pokemon.decode(party[4], HgssMonCheck::isPlausible)!!
        val checksum = Gen4RomBytes.u16(party[4], 6)
        val blockB = S.POKEMON_BLOCK_OFFSETS[((truth.personality shr 13) and 31).toInt()][1]
        fun withMoves(vararg ids: Int): Gen4Pokemon.Decoded {
            val plain = party[4].copyOfRange(8, 0x88).also { Gen4Pokemon.crypt(it, 0, it.size, checksum.toLong()) }
            ids.forEachIndexed { i, id -> plain[blockB + 2 * i] = (id and 0xFF).toByte(); plain[blockB + 2 * i + 1] = (id shr 8).toByte() }
            val b = { which: Int -> val off = S.POKEMON_BLOCK_OFFSETS[((truth.personality shr 13) and 31).toInt()][which]; plain.copyOfRange(off, off + 0x20) }
            return Gen4Pokemon.Decoded(truth.personality, b(0), b(1), b(2), b(3), null, checksumOk = true)
        }
        assertTrue(HgssMonCheck.problems(withMoves(57918, 420, 196, 426)).any { it is HgssMonCheck.Problem.BadMoves })
        assertTrue(HgssMonCheck.problems(withMoves(70, 0, 196, 426)).any { it is HgssMonCheck.Problem.BadMoves }, "a move after an empty slot")
        assertTrue(HgssMonCheck.problems(withMoves(70, 70, 196, 426)).any { it is HgssMonCheck.Problem.BadMoves }, "the same move twice")
        assertTrue(HgssMonCheck.problems(withMoves(70, 420, 196, 426)).none { it is HgssMonCheck.Problem.BadMoves })
    }
}
