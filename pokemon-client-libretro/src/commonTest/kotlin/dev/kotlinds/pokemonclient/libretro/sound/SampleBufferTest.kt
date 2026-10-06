package dev.kotlinds.pokemonclient.libretro.sound

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The sample buffer the app's shadow and the bench record into, and the WAV files written from it. */
class SampleBufferTest {

    @Test
    fun growsPastItsCapacityAndKeepsTheSamplesInOrder() {
        val buffer = SampleBuffer(initialCapacity = 4)
        buffer.add(shortArrayOf(1, 2, 3, 4, 99), 4) // only the first 4
        buffer.addSilence(2)
        buffer.addAll(SampleBuffer().also { it.add(shortArrayOf(-5, 6), 2) })
        assertEquals(8, buffer.size)
        assertEquals(4, buffer.frames)
        assertContentEquals(shortArrayOf(1, 2, 3, 4, 0, 0, -5, 6), buffer.toArray())
    }

    @Test
    fun writesA16BitStereoWav() {
        val wav = SampleBuffer().also { it.add(shortArrayOf(0x1234, -2), 2) }.toWav(32768)
        val expected = "RIFF".encodeToByteArray() + le(36 + 4, 4) + "WAVEfmt ".encodeToByteArray() + le(16, 4) +
            le(1, 2) + le(2, 2) + le(32768, 4) + le(32768 * 4, 4) + le(4, 2) + le(16, 2) +
            "data".encodeToByteArray() + le(4, 4) + byteArrayOf(0x34, 0x12, 0xFE.toByte(), 0xFF.toByte())
        assertContentEquals(expected, wav)
        assertEquals(Wav.HEADER_SIZE + 4, wav.size)
        assertContentEquals(expected, Wav.encode(byteArrayOf(0x34, 0x12, 0xFE.toByte(), 0xFF.toByte()), 32768))
    }

    private fun le(value: Int, bytes: Int) = ByteArray(bytes) { (value ushr (8 * it)).toByte() }
}
