package dev.kotlinds.pokemonclient.games.platinum

import dev.kotlinds.pokemonclient.PokemonGames
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.view.MapView
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.SignKind
import dev.kotlinds.pokemonclient.world.TileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Platinum with its ROM (skipped without `PLATINUM_ROM`): detection, text banks, the maps of the player's house. */
class PlatinumRomTest {

    @Test
    fun `the ROM is detected as Platinum`() {
        val rom = PlatinumRom.requireRom()
        val game = assertIs<PlatinumGame>(PokemonGames.detect(rom))
        assertEquals("Pokémon Platinum (USA)", game.name)
        assertEquals("CPUE", rom.gameCode)
        // (PokemonGames.detect(bytes), the app's and the bench's path, is checked live: parsing the 128 MB ROM a
        // second time in the test JVM runs out of its default heap.)
    }

    @Test
    fun `screens read their text from the ROM`() {
        val game = PlatinumRom.requireGame()
        fun text(name: String) = (game.state(PlatinumFixtures.load(name)).screen as Screen.PressToContinue).text.orEmpty()
        assertTrue(text("pt_control_text").startsWith("Moves the main character."), text("pt_control_text"))
        assertTrue(text("pt_new_game_warning").startsWith("WARNING! There is already another saved game file."))
        assertTrue(text("pt_tv").startsWith("“Pokémon are by our side, always."))
        val menu = game.state(PlatinumFixtures.load("pt_main_menu")).screen as Screen.ListMenu
        assertEquals(listOf("CONTINUE", "NEW GAME", "NINTENDO WFC SETTINGS"), menu.entries.take(3).map { it.label })
    }

    @Test
    fun `map headers and location names`() {
        val world = PlatinumRom.requireGame().world!!
        assertEquals(PlatinumMapHeaders.COUNT, world.zoneCount)
        assertEquals("Twinleaf Town", world.locationName(415))
        assertEquals("Twinleaf Town", world.locationName(414))
    }

    /** A5: the same name model as HGSS ([dev.kotlinds.pokemonclient.state.MapName]): the place from the ROM, then the map's own name. */
    @Test
    fun `map names are the place then the map, without the id`() {
        val game = PlatinumRom.requireGame()
        assertEquals("Twinleaf Town (Twinleaf Town Player House 2F)", game.mapName(415).toString())
        assertEquals("Twinleaf Town", game.mapName(411).toString())
        val bedroom = game.state(PlatinumFixtures.load("pt_bedroom")).field!!
        assertEquals(game.mapName(415), bedroom.mapName)
        assertEquals("Twinleaf Town (Twinleaf Town Player House 2F)", game.world!!.areaOf(415)!!.name)
    }

    /**
     * B1: Platinum's world is decoded by the Gen 4 decoder like HGSS's: field move obstacles (by Platinum's sprite
     * ids), hidden items (flag = script - 8000 + 730, Script_GetHiddenItemFlag), the decoded areas kept.
     */
    @Test
    fun `obstacles and hidden items from the ROM`() {
        val world = PlatinumRom.requireGame().world!!
        // Eterna City (zone 65): the small trees west of the city.
        val eterna = world.areaOf(65)!!
        assertEquals(FieldMoveKind.CUT, eterna.people.single { it.zone == 65 && it.x == 304 && it.y == 521 }.obstacle)
        // Mt. Coronet 1F north room 1 (zone 218): cracked rocks, boulders and a hidden item.
        val coronet = world.areaOf(218)!!
        assertEquals(FieldMoveKind.ROCK_SMASH, coronet.people.single { it.x == 24 && it.y == 9 }.obstacle)
        assertEquals(FieldMoveKind.STRENGTH, coronet.people.single { it.x == 29 && it.y == 30 }.obstacle)
        val hidden = coronet.signs.single { it.x == 14 && it.y == 9 }
        assertEquals(SignKind.HIDDEN_ITEM, hidden.kind)
        assertEquals(8065 - 8000 + 730, hidden.flag)
        assertSame(coronet, world.areaOf(218), "decoded once")
    }

    /** A7: Platinum's field move rules (Fly needs the Cobble Badge, id 2; no Whirlpool), the Gen 4 move ids. */
    @Test
    fun `field move rules are Platinum's`() {
        val game = PlatinumGame(PlatinumVersion.PLATINUM_US)
        val fly = game.fieldMoveRule(FieldMoveKind.FLY)!!
        assertEquals(19, fly.move.value)
        assertEquals("Cobble" to 2, fly.badge to fly.badgeId)
        assertNull(game.fieldMoveRule(FieldMoveKind.WHIRLPOOL))
        // Without a ROM, the maps keep their own names.
        assertEquals("Twinleaf Town Player House 2F", game.mapName(415).toString())
    }

    @Test
    fun `bedroom tiles and stairs from the ROM`() {
        val game = PlatinumRom.requireGame()
        val area = game.world!!.areaOf(415)!!
        // The stairs down (WARP_STAIRS_EAST) at (8, 4): step on, then press east.
        val stairs = area.warps.single()
        assertEquals(8 to 4, stairs.x to stairs.y)
        assertEquals(414, stairs.targetZone)
        assertEquals(dev.kotlinds.pokemonclient.Direction.EAST, stairs.exitDirection)
        assertEquals(TileKind.Door, area.tile(8, 4)?.kind)
        assertEquals(false, area.tile(4, 6)?.blocked) // where the player stands
        assertEquals(true, area.tile(4, 4)?.blocked)  // the TV
        assertEquals(true, area.tile(0, 6)?.blocked)  // the west wall
        // Downstairs leads back up.
        assertTrue(game.world!!.areaOf(414)!!.warps.any { it.targetZone == 415 && it.x == 10 && it.y == 3 })
    }

    @Test
    fun `text map of the bedroom`() {
        val game = PlatinumRom.requireGame()
        val state = game.state(PlatinumFixtures.load("pt_bedroom"))
        val field = state.field!!
        val view = MapView.render(game.world!!.areaOf(field.mapId)!!, field, game::mapName, world = game.world)
        val exits = view["exits"].toString()
        assertTrue("warp:0 at 8,4" in exits && "press east" in exits, exits)
    }
}
