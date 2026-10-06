package dev.kotlinds.pokemonclient.games.gen4

import dev.kotlinds.pokemonclient.RamMemory
import dev.kotlinds.pokemonclient.games.hgss.HgssAddresses
import dev.kotlinds.pokemonclient.games.hgss.HgssMemory
import dev.kotlinds.pokemonclient.games.hgss.HgssVersion
import dev.kotlinds.pokemonclient.games.platinum.PlatinumGame
import dev.kotlinds.pokemonclient.games.platinum.PlatinumMemory
import dev.kotlinds.pokemonclient.games.platinum.PlatinumVersion
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.state.SpeciesSet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Gen 4 save reader ([Gen4SaveData]) on a RAM laid out like each game's `SaveData`: the same `VarsFlags` and
 * `Pokedex` tables, found through each game's table headers (Platinum's `pageInfo` offsets checked on the bench).
 */
class Gen4SaveDataTest {

    private val ramStart = 0x02000000L
    private val saveData = 0x02100000L

    /**
     * A RAM with one save at [saveData] in [layout]: flags 5 and 300 set, variable 0x4001 = 7, Bulbasaur (1) and
     * Pikachu (25) seen, Pikachu caught.
     */
    private fun ram(layout: Gen4SaveLayout): ByteArray {
        val ram = ByteArray(4 shl 20)
        fun put32(addr: Long, v: Int) { val o = (addr - ramStart).toInt(); for (i in 0 until 4) ram[o + i] = (v shr (8 * i)).toByte() }
        fun setBit(addr: Long, bit: Int) { val o = (addr - ramStart).toInt() + bit / 8; ram[o] = (ram[o].toInt() or (1 shl (bit % 8))).toByte() }
        fun table(id: Int, offset: Int, size: Int) {
            val header = saveData + layout.tableHeaders + id * Gen4SaveData.TABLE_HEADER_SIZE
            put32(header, id); put32(header + 4, size); put32(header + 8, offset)
        }
        table(layout.varsFlagsTable, 0x100, 0x400)
        table(layout.pokedexTable, 0x800, 0x330)
        val flags = saveData + layout.dataOffset + 0x100 + layout.flagsOffset
        setBit(flags, 5); setBit(flags, 300)
        // Script variable 0x4001 = 7 (the u16 vars before the flags).
        ram[(saveData + layout.dataOffset + 0x100 + 2 - ramStart).toInt()] = 7
        val dex = saveData + layout.dataOffset + 0x800
        setBit(dex + Gen4SaveData.DEX_SEEN, 0); setBit(dex + Gen4SaveData.DEX_SEEN, 24); setBit(dex + Gen4SaveData.DEX_CAUGHT, 24)
        return ram
    }

    private fun check(save: Gen4SaveData) {
        val flags = assertNotNull(save.eventFlags())
        assertEquals(listOf(true, true, false), listOf(flags[5], flags[300], flags[6]))
        assertNull(flags[Gen4SaveData.NUM_FLAGS])
        assertEquals(listOf(true, false), listOf(save.flag(300), save.flag(301)))
        assertEquals(7, save.variable(0x4001))
        assertNull(save.variable(0x5000), "past the save variables")
        val dex = assertNotNull(save.pokedex())
        assertEquals(setOf(SpeciesId(1), SpeciesId(25)), dex.seen.toSet())
        assertEquals(listOf(SpeciesId(25)), dex.caught.toList())
        assertEquals(2, dex.seen.size)
        assertEquals(SpeciesSet.of(Gen4SaveData.NATIONAL_DEX_COUNT, listOf(SpeciesId(25))), dex.caught)
    }

    @Test
    fun platinumSave() {
        val mem = PlatinumMemory(RamMemory(ram(PlatinumGame.SAVE_LAYOUT)), PlatinumVersion.PLATINUM_US)
        check(Gen4SaveData(mem, saveData, PlatinumGame.SAVE_LAYOUT))
    }

    @Test
    fun heartGoldSave() {
        val mem = HgssMemory(RamMemory(ram(HgssAddresses.SAVE_LAYOUT)), HgssVersion.HEARTGOLD_US)
        check(Gen4SaveData(mem, saveData, HgssAddresses.SAVE_LAYOUT))
    }

    @Test
    fun aTableWhoseHeaderDoesNotMatchIsUnreadable() {
        val mem = PlatinumMemory(RamMemory(ByteArray(4 shl 20)), PlatinumVersion.PLATINUM_US)
        // Header 7 reads id 0: not the Pokédex.
        assertNull(Gen4SaveData(mem, saveData, PlatinumGame.SAVE_LAYOUT).pokedex())
    }
}
