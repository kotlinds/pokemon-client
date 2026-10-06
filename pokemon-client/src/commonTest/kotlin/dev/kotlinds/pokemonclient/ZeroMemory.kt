package dev.kotlinds.pokemonclient

/**
 * A RAM where every byte reads 0, for tests whose game is scripted (the states don't come from the RAM) or that check
 * how a reader copes with nothing loaded. The one such fake of the tests.
 */
object ZeroMemory : Memory {
    override fun read8(addr: Long) = 0
    override fun read16(addr: Long) = 0
    override fun read32(addr: Long) = 0L
    override fun readBytes(addr: Long, size: Int) = ByteArray(size)
}
