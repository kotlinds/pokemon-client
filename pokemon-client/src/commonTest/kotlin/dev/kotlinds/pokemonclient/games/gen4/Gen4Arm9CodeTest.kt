package dev.kotlinds.pokemonclient.games.gen4

import kotlin.test.Test
import kotlin.test.assertSame

/**
 * The ARM9 binary is decompressed only when its module parameters say it is compressed: a ROM rebuilt with it stored
 * uncompressed (NOTES-run-map-randomizer: the map randomizer's ROM, "BLZ: bad header length 0x0") must be read as is.
 */
class Gen4Arm9CodeTest {

    /** An ARM9-like binary holding the Nitro module parameters with [compressedEnd]. */
    private fun arm9(compressedEnd: Int): ByteArray {
        val bytes = ByteArray(0x1000) { (it * 7).toByte() }
        val signature = 0xBBC
        fun put32(o: Int, v: Int) { for (b in 0 until 4) bytes[o + b] = (v ushr (8 * b)).toByte() }
        put32(signature - 8, compressedEnd) // compressed_static_end
        put32(signature - 4, 0x4027533) // sdk_version
        put32(signature, 0xDEC00621.toInt())
        put32(signature + 4, 0x2106C0DE)
        return bytes
    }

    @Test
    fun anUncompressedBinaryIsReadAsItIs() {
        val raw = arm9(compressedEnd = 0)
        assertSame(raw, Gen4RomBytes.arm9Code(raw))
    }
}
