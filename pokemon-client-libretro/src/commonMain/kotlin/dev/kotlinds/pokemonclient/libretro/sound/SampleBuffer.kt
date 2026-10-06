package dev.kotlinds.pokemonclient.libretro.sound

/**
 * A growable buffer of interleaved stereo 16-bit samples (left, right, left...), as a libretro core's audio callback
 * gives them: what the app captures of the shadow's sound (the frame after its end, faded out if the resync is
 * refused) and what the bench records of both consoles to compare and write as WAVs ([toWav]).
 *
 * Not thread-safe: one writer at a time (the console's audio callback).
 */
class SampleBuffer(initialCapacity: Int = 4096) {
    /** The samples; only the first [size] are meaningful (read-only view for analyses, no copy). */
    var data = ShortArray(initialCapacity)
        private set

    /** Samples held (both channels: twice [frames]). */
    var size = 0
        private set

    /** Stereo frames held. */
    val frames: Int get() = size / 2

    /** Appends the first [count] samples of [samples]. */
    fun add(samples: ShortArray, count: Int) {
        if (size + count > data.size) data = data.copyOf(maxOf(data.size * 2, size + count))
        samples.copyInto(data, size, 0, count)
        size += count
    }

    /** Appends [count] samples of silence. */
    fun addSilence(count: Int) = add(ShortArray(count), count)

    /** Appends everything [other] holds. */
    fun addAll(other: SampleBuffer) = add(other.data, other.size)

    /** A copy of the samples held. */
    fun toArray(): ShortArray = data.copyOf(size)

    /** The samples as a 16-bit stereo WAV file at [sampleRate] Hz (see [Wav.encode]). */
    fun toWav(sampleRate: Int): ByteArray = Wav.encode(ByteArray(size * 2).also { pcm ->
        for (i in 0 until size) {
            val sample = data[i].toInt()
            pcm[2 * i] = sample.toByte()
            pcm[2 * i + 1] = (sample shr 8).toByte()
        }
    }, sampleRate)
}

/** The WAV file format, for listening to captured game sound (bench, end-to-end checks); never played by them. */
object Wav {
    /** Size of the RIFF / fmt / data headers written by [encode]. */
    const val HEADER_SIZE = 44

    /**
     * A WAV file of [pcm]: interleaved stereo 16-bit little-endian samples (what the cores give and the app's audio
     * device receives) at [sampleRate] Hz.
     */
    fun encode(pcm: ByteArray, sampleRate: Int): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        var at = 0
        fun ascii(text: String) = text.forEach { header[at++] = it.code.toByte() }
        fun int(value: Int, bytes: Int) = repeat(bytes) { header[at++] = (value ushr (8 * it)).toByte() }
        ascii("RIFF"); int(36 + pcm.size, 4); ascii("WAVEfmt "); int(16, 4)
        int(1, 2) // PCM
        int(CHANNELS, 2); int(sampleRate, 4); int(sampleRate * BLOCK_ALIGN, 4); int(BLOCK_ALIGN, 2); int(16, 2)
        ascii("data"); int(pcm.size, 4)
        return header + pcm
    }

    private const val CHANNELS = 2

    /** Bytes per stereo frame: 2 channels of 16 bits. */
    private const val BLOCK_ALIGN = 4
}
