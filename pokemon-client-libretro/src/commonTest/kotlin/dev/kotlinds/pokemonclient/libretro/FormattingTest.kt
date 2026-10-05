package dev.kotlinds.pokemonclient.libretro

import kotlin.test.Test
import kotlin.test.assertEquals

/** The common formatter gives what `String.format` gave for every pattern the module uses. */
class FormattingTest {

    @Test
    fun hexadecimal() {
        assertEquals("021E1AC0", "%08X".fmt(0x021E1AC0))
        assertEquals("FFFFFFFF", "%08X".fmt(-1))
        assertEquals("0A", "%02X".fmt(10))
        assertEquals("ff", "%02x".fmt(0xFF.toByte()))
        assertEquals("0x0003", "0x%04X".fmt(3))
        assertEquals("mon:8dd175d1.76f3a6fb", "mon:%08x.%08x".fmt(0x8dd175d1.toInt(), 0x76f3a6fb))
    }

    @Test
    fun decimals() {
        assertEquals("+0.500", "%+.3f".fmt(0.5))
        assertEquals("-0.125", "%+.3f".fmt(-0.125))
        assertEquals("1.50", "%.2f".fmt(1.499))
        assertEquals(" 2.5", "%4.1f".fmt(2.5))
        assertEquals("0.000", "%.3f".fmt(-0.0001))
    }

    @Test
    fun integersAndStrings() {
        assertEquals("  +12", "%+5d".fmt(12))
        assertEquals(" 7", "%2d".fmt(7))
        assertEquals("ab   |", "%-5s|".fmt("ab"))
        assertEquals("abc", "%.3s".fmt("abcdef"))
        assertEquals("100%", "%d%%".fmt(100))
    }
}
