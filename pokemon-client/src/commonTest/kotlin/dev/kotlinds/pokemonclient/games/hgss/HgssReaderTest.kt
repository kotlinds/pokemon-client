package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.games.gen4.Gen4RomBytes
import dev.kotlinds.pokemonclient.games.gen4.Gen4Pokemon
import dev.kotlinds.pokemonclient.games.gen4.Gen4Structs as S
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.RamMemory
import dev.kotlinds.pokemonclient.games.gen4.Gen4Structs
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs [HgssReader] on real HeartGold (USA) main RAM snapshots taken with melonDS.
 *
 * The fixtures are sparse: they only contain the bytes the reader touched on the full 4 MB dump
 * (format: "SPRM" then records of [u32 address][u32 length][bytes], gzipped). If the reader starts reading
 * other addresses, regenerate them from the full dumps with a recording [Memory] (see HGSS_RAM_NOTES.md).
 *  - d1: title screen
 *  - d5: Professor Oak's intro ("Welcome to the world of Pokémon!")
 *  - d9: first controllable frame, bedroom of New Bark Town house 2F, player "AAAAAAA"
 *  - d10: same room after DOWN x2, LEFT x2
 */
class HgssReaderTest {

    private fun load(name: String): Memory = HgssFixtures.load(name)

    @Test
    fun titleScreen() {
        val state = assertNotNull(HgssReader(load("d1")).read())
        assertEquals(GameMode.TITLE_SCREEN, state.mode)
        assertNull(state.location)
    }

    @Test
    fun oakSpeechDialogue() {
        val memory = load("d5")
        val state = assertNotNull(HgssReader(memory).read())
        assertEquals(GameMode.NEW_GAME_INTRO, state.mode)
        // The professor's message (OakSpeechData.string), read the way his screen decoder (HgssOakIntroScreens) reads
        // it: freed once printed, still readable. Only the text: this sparse fixture was captured by the former reader,
        // which never read the speech's state bytes, so its screen decodes as a transition here; the screen itself
        // (Screen.Dialogue of the intro, waiting for A) is checked on `oak_speech_wait` (HgssTextScreensTest).
        val mem = HgssMemory(memory, HgssVersion.HEARTGOLD_US)
        val app = assertNotNull(mem.ptr(HgssVersion.HEARTGOLD_US.mainAppState + HgssAddresses.MAIN_APP_OVERLAY_MANAGER))
        val data = assertNotNull(mem.ptr(app + Gen4Structs.OM_DATA))
        val message = assertNotNull(mem.printedText(mem.ptr(data + HgssAddresses.OAK_STRING), printerId = null, allowFreed = true))
        assertContains(message.full, "Welcome to the world of Pokémon!")
    }

    @Test
    fun bedroomFirstFrame() {
        val reader = HgssReader(load("d9"))
        val state = assertNotNull(reader.read())
        assertEquals(GameMode.OVERWORLD, state.mode)
        assertTrue(state.playerControllable)
        val player = assertNotNull(state.player)
        assertEquals("AAAAAAA", player.name)
        assertEquals(3000, player.money)
        assertEquals(0, player.badgeCount)
        val loc = assertNotNull(state.location)
        assertEquals(64, loc.mapId) // MAP_NEW_BARK_PLAYER_HOUSE_2F
        assertEquals("New Bark Town", HgssData.mapName(loc.mapId).place)
        // sLocation_PlayerRoom (src/location_backup.c): (6, 6) facing south
        assertEquals(6, loc.x)
        assertEquals(6, loc.z)
        assertEquals("south", loc.facing)
        assertEquals(false, loc.moving)
        assertTrue(state.party.isEmpty())
        // Terrain only, cropped to the room: the void beyond the walls is '-'.
        val grid = assertNotNull(state.surroundings?.grid)
        assertEquals('.', grid.at(6, 6))
        assertEquals('#', grid.at(9, 4)) // TV (collision)
        assertEquals('#', grid.at(11, 6)) // east wall
        assertEquals('-', grid.at(13, 6)) // outside the room
        assertEquals("INTERIOR", state.surroundings?.mapType)
    }

    @Test
    fun bedroomAfterMoving() {
        val state = assertNotNull(HgssReader(load("d10")).read())
        val loc = assertNotNull(state.location)
        assertEquals(64, loc.mapId)
        assertEquals(4, loc.x)
        assertEquals(8, loc.z)
        assertEquals("west", loc.facing)
    }

    @Test
    fun garbageMemoryDoesNotThrow() {
        val random = kotlin.random.Random(42)
        val ram = ByteArray(4 shl 20).also { random.nextBytes(it) }
        HgssReader(RamMemory(ram), HgssVersion.HEARTGOLD_US).read()
        HgssReader(RamMemory(ByteArray(4 shl 20))).read()
    }

    @Test
    fun pokemonDecryptionRoundTrip() {
        val pid = 0x12345678L
        val plain = ByteArray(0xEC)
        fun put16(o: Int, v: Int) {
            plain[o] = v.toByte(); plain[o + 1] = (v shr 8).toByte()
        }
        plain[0] = 0x78; plain[1] = 0x56; plain[2] = 0x34; plain[3] = 0x12
        val order = S.POKEMON_BLOCK_OFFSETS[((pid shr 13) and 31).toInt()]
        put16(8 + order[0], 155) // block A: species Cyndaquil
        put16(8 + order[1], 33) // block B: Tackle
        plain[8 + order[1] + 8] = 35 // PP
        var sum = 0
        for (i in 0 until 0x40) sum = (sum + Gen4RomBytes.u16(plain, 8 + 2 * i)) and 0xFFFF
        put16(6, sum)
        plain[0x8C] = 5
        put16(0x8E, 19)
        put16(0x90, 20)
        val enc = plain.copyOf()
        Gen4Pokemon.crypt(enc, 8, 0x80, sum.toLong())
        Gen4Pokemon.crypt(enc, 0x88, 0x64, pid)
        val mon = assertNotNull(Gen4Pokemon.decode(enc))
        assertTrue(mon.checksumOk)
        assertEquals(155, mon.species)
        assertEquals(33, mon.move(0))
        assertEquals(35, mon.movePp(0))
        assertEquals(5, mon.level)
        assertEquals(19, mon.hp)
        assertEquals(20, mon.maxHp)
    }
}
