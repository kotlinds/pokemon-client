package dev.kotlinds.pokemonclient.libretro

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SaveFormatTest {

    private val raw = ByteArray(512 * 1024) { (it * 31).toByte() }

    @Test
    fun desmumeFooterRoundTrips() {
        val dsv = SaveFormat.DESMUME.toCore(raw)
        assertEquals(raw.size + 122, dsv.size)
        assertTrue(dsv.copyOfRange(dsv.size - 16, dsv.size).decodeToString() == "|-DESMUME SAVE-|")
        assertContentEquals(raw, SaveFormat.DESMUME.fromCore(dsv))
    }

    @Test
    fun footerFieldsMatchWhatDesmumeWritesForA512KbFlash() {
        val dsv = SaveFormat.DESMUME.toCore(raw)
        val numbers = dsv.copyOfRange(raw.size + 82, raw.size + 106)
        val expected = "010004000000080006000000030000000000080000000000".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertContentEquals(expected, numbers)
    }

    @Test
    fun theNewestFileWinsInBothDirections() {
        val dir = Files.createTemporaryDirectory("saves")
        try {
            val sav = Path(dir, "game.sav").also { Files.writeBytes(it, raw) }
            SaveFormat.DESMUME.prepare(dir, "game")
            val dsv = Path(dir, "game.dsv")
            assertContentEquals(raw, SaveFormat.DESMUME.fromCore(Files.readBytes(dsv)))

            // The core wrote a newer save: it goes back to game.sav when the game closes.
            val played = raw.copyOf().also { it[0] = 42 }
            Files.writeBytes(dsv, SaveFormat.DESMUME.toCore(played))
            setLastModifiedMillis(dsv, lastModifiedMillis(sav)!! + 5_000)
            SaveFormat.DESMUME.sync(dir, "game")
            assertContentEquals(played, Files.readBytes(sav))
        } finally {
            Files.deleteRecursively(dir)
        }
    }

    @Test
    fun rawFormatIsTheSavFileItself() {
        val dir = Files.createTemporaryDirectory("saves")
        try {
            Files.writeBytes(Path(dir, "game.sav"), raw)
            SaveFormat.RAW.prepare(dir, "game")
            SaveFormat.RAW.sync(dir, "game")
            assertContentEquals(raw, Files.readBytes(Path(dir, "game.sav")))
        } finally {
            Files.deleteRecursively(dir)
        }
    }
}
