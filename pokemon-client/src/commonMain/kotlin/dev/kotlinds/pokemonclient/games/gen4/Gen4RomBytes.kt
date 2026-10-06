package dev.kotlinds.pokemonclient.games.gen4

import dev.kotlinds.BlzCodec

/**
 * Little-endian reads of byte arrays (the DS is little-endian): ROM files (NARC members, the ARM9 binary) and structures
 * copied out of RAM (a Pokémon, the save's gym puzzle slot), shared by every Gen 4 decoder.
 */
object Gen4RomBytes {
    fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
    fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
    fun s16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()
    fun s32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)
    fun u32(b: ByteArray, o: Int): Long = s32(b, o).toLong() and 0xFFFFFFFFL

    /**
     * The ARM9 binary's code as the game runs it: BLZ-decompressed when the ROM stores it compressed, as is otherwise.
     * The binary says so itself: its Nitro SDK module parameters (found by their "nitrocode" signature, in the part
     * that is never compressed) hold `compressed_static_end`, 0 when the binary is stored uncompressed. HeartGold ships
     * it compressed, Platinum not, and a ROM rebuilt by a tool (a map randomizer) may store it either way: decompressing
     * an uncompressed binary fails ("BLZ: bad header length"), which left the world and the game data unreadable.
     */
    fun arm9Code(arm9: ByteArray): ByteArray {
        val signature = indexOf(arm9, NITROCODE)
        if (signature < 0) {
            // No module parameters found: try to decompress, keep the binary as it is when that isn't BLZ.
            return runCatching { BlzCodec.decompress(arm9) }.getOrNull()?.takeIf { it.size > arm9.size } ?: arm9
        }
        val compressedEnd = u32(arm9, signature - COMPRESSED_END_BEFORE_SIGNATURE)
        return if (compressedEnd == 0L) arm9 else BlzCodec.decompress(arm9)
    }

    /** The Nitro SDK's module parameters signature: the words 0xDEC00621 then 0x2106C0DE, little-endian. */
    private val NITROCODE = byteArrayOf(0x21, 0x06, 0xC0.toByte(), 0xDE.toByte(), 0xDE.toByte(), 0xC0.toByte(), 0x06, 0x21)

    /** `compressed_static_end` comes 8 bytes before the signature (then `sdk_version`, then the signature). */
    private const val COMPRESSED_END_BEFORE_SIGNATURE = 8

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
