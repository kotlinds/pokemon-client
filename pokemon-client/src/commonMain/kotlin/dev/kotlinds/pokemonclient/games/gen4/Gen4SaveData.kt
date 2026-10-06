package dev.kotlinds.pokemonclient.games.gen4

import dev.kotlinds.pokemonclient.state.EventFlags
import dev.kotlinds.pokemonclient.state.PokedexState
import dev.kotlinds.pokemonclient.state.SpeciesSet

/**
 * Where a Gen 4 game keeps its save data in RAM (`SaveData`, pokeplatinum include/savedata.h, pokeheartgold
 * include/save.h): the save tables live in one buffer ([dataOffset]), each found through a header of [tableHeaders]
 * (16 bytes each: u32 id, u32 size, u32 offset into the buffer; `SaveData_SaveTable` / `SaveArray_Get`). The layouts
 * of the tables read here are the engine's: `VarsFlags` (u16 vars then the event flags) and `Pokedex`.
 */
data class Gen4SaveLayout(
    /** Offset of the tables' buffer (`body.data` / `dynamic_region`) in `SaveData`. */
    val dataOffset: Long,
    /** Offset of the table headers (`pageInfo` / `arrayHeaders`) in `SaveData`. */
    val tableHeaders: Long,
    /** Id of the `VarsFlags` table (`SAVE_TABLE_ENTRY_VARS_FLAGS` / `SAVE_FLAGS`). */
    val varsFlagsTable: Int,
    /** Offset of `flags` in `VarsFlags`: 2 × the game's `NUM_VARS` (its script variables, from [VAR_BASE]). */
    val flagsOffset: Long,
    /** Id of the `Pokedex` table (`SAVE_TABLE_ENTRY_POKEDEX` / `SAVE_POKEDEX`). */
    val pokedexTable: Int,
)

/**
 * Reads of a Gen 4 save in RAM ([layout] per game) shared by the games: a save table's address, the event flags and
 * the Pokédex, each in one bulk read into the common model's compact types ([EventFlags], [SpeciesSet]): cheap enough
 * for every state decode.
 */
class Gen4SaveData(private val mem: Gen4Memory, private val saveData: Long, private val layout: Gen4SaveLayout) {

    /** Address of save table [id] (checked: its header says [id] and it lies in RAM), or null. */
    fun table(id: Int): Long? {
        val header = saveData + layout.tableHeaders + id * TABLE_HEADER_SIZE
        if (mem.s32(header) != id) return null
        val addr = saveData + layout.dataOffset + mem.u32(header + 8)
        return addr.takeIf { mem.inRam(it, mem.u32(header + 4).coerceAtLeast(1)) }
    }

    /** Event flag [id] (`VarsFlags_CheckFlagInArray` / `Save_VarsFlags_CheckFlagInArray`), or null when unreadable. */
    fun flag(id: Int): Boolean? {
        if (id !in 0 until NUM_FLAGS) return null
        val vf = table(layout.varsFlagsTable) ?: return null
        return mem.u8(vf + layout.flagsOffset + id / 8) shr (id % 8) and 1 == 1
    }

    /** Save script variable [id] ([VAR_BASE] + n, `VarsFlags_GetVarAddress`), or null when unreadable or not a save variable. */
    fun variable(id: Int): Int? {
        if (id !in VAR_BASE until VAR_BASE + (layout.flagsOffset / 2).toInt()) return null
        val vf = table(layout.varsFlagsTable) ?: return null
        return mem.u16(vf + 2L * (id - VAR_BASE))
    }

    /** Every event flag (`VarsFlags.flags`: flag n is bit n % 8 of byte n / 8), in one read, or null. */
    fun eventFlags(): EventFlags? =
        table(layout.varsFlagsTable)?.let { mem.bytes(it + layout.flagsOffset, NUM_FLAGS / 8) }?.let(::EventFlags)

    /**
     * The Pokédex's seen and caught species (`Pokedex.seenPokemon` / `caughtPokemon`: species n is bit n - 1, both
     * decomps' `ReadBit_2Forms` / `CheckDexFlag`), or null.
     */
    fun pokedex(): PokedexState? {
        val dex = table(layout.pokedexTable) ?: return null
        val caught = mem.bytes(dex + DEX_CAUGHT, DEX_BYTES) ?: return null
        val seen = mem.bytes(dex + DEX_SEEN, DEX_BYTES) ?: return null
        return PokedexState(seen = SpeciesSet(seen, NATIONAL_DEX_COUNT), caught = SpeciesSet(caught, NATIONAL_DEX_COUNT))
    }

    companion object {
        /** Size of a table header (`SavePageInfo` / `SaveArrayHeader`). */
        const val TABLE_HEADER_SIZE = 16L

        /** `VARS_START`: the first save variable, in both decomps. */
        const val VAR_BASE = 0x4000

        /** `NUM_FLAGS`: 2912 in both decomps. */
        const val NUM_FLAGS = 2912

        /** `NATIONAL_DEX_COUNT`. */
        const val NATIONAL_DEX_COUNT = 493

        /** `Pokedex.caughtPokemon` (after `u32 magic`) and `seenPokemon` (after the 16 words of `DEX_SIZE_U32`). */
        const val DEX_CAUGHT = 0x04L
        const val DEX_SEEN = 0x44L

        /** `DEX_SIZE_U32` words. */
        const val DEX_BYTES = 16 * 4
    }
}
