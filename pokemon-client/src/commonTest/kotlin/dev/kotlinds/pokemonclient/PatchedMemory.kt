package dev.kotlinds.pokemonclient

import dev.kotlinds.pokemonclient.games.gen4.Gen4Charmap
import dev.kotlinds.pokemonclient.games.gen4.Gen4Structs

/**
 * [base] (a captured RAM) with some bytes written over it (little-endian writers at bus addresses): a real capture
 * turned into a state no capture has (a structure written in free RAM, a field changed), when the decompilation says
 * how the game lays it out. The one such fake of the tests.
 */
internal class PatchedMemory(private val base: Memory) : Memory {
    private val bytes = HashMap<Long, Int>()
    fun u8(addr: Long, value: Int) { bytes[addr] = value and 0xFF }
    fun u16(addr: Long, value: Int) { u8(addr, value); u8(addr + 1, value shr 8) }
    fun u32(addr: Long, value: Long) { u16(addr, value.toInt()); u16(addr + 2, (value shr 16).toInt()) }
    override fun read8(addr: Long) = bytes[addr] ?: base.read8(addr)
    override fun read16(addr: Long) = read8(addr) or (read8(addr + 1) shl 8)
    override fun read32(addr: Long) = (read16(addr).toLong() or (read16(addr + 2).toLong() shl 16)) and 0xFFFFFFFFL
    override fun readBytes(addr: Long, size: Int) = ByteArray(size) { read8(addr + it).toByte() }

    /** A game `String` of [text] at [addr]. */
    fun string(addr: Long, text: String) {
        val reverse = Gen4Charmap.table.entries.filter { it.value.length == 1 }.associate { it.value[0] to it.key }
        u16(addr + Gen4Structs.STR_MAXSIZE, text.length)
        u16(addr + Gen4Structs.STR_SIZE, text.length)
        u32(addr + Gen4Structs.STR_MAGIC, Gen4Structs.STRING_MAGIC)
        text.forEachIndexed { i, c -> u16(addr + Gen4Structs.STR_DATA + 2L * i, reverse.getValue(c)) }
    }
}
