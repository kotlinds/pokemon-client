package dev.kotlinds.pokemonclient

/**
 * Real RAM snapshots captured with the bench's `fixture` command, stored sparsely: only the bytes the decoders touch
 * (format: "SPRM" then records of [u32 address][u32 length][bytes], big-endian, gzipped), in the test resources.
 */
internal object SparseFixtures {
    /** The main RAM of the fixture `<directory>/<name>.ram.sparse.gz`, zeros where nothing was captured. */
    fun load(directory: String, name: String): Memory {
        val path = "$directory/$name.ram.sparse.gz"
        val data = gunzip(readTestResourceOrNull(path) ?: error("missing fixture $name"))
        check(data.copyOfRange(0, 4).decodeToString() == "SPRM") { "not a sparse fixture: $path" }
        val ram = ByteArray(4 shl 20)
        var offset = 4
        while (offset + 8 <= data.size) {
            val address = be32(data, offset)
            val length = be32(data, offset + 4)
            data.copyInto(ram, address - 0x02000000, offset + 8, offset + 8 + length)
            offset += 8 + length
        }
        return RamMemory(ram)
    }

    private fun be32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or (data[offset + 3].toInt() and 0xFF)
}
