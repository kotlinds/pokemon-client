package dev.kotlinds.pokemonclient.games.platinum

import dev.kotlinds.pokemonclient.PokemonGames
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.SpeciesId
import dev.kotlinds.pokemonclient.world.EncounterCondition
import dev.kotlinds.pokemonclient.world.EncounterConditions
import dev.kotlinds.pokemonclient.world.EncounterMethod
import dev.kotlinds.pokemonclient.world.EncounterSlot
import dev.kotlinds.pokemonclient.world.TimeOfDay
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.view.MapView
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.SignKind
import dev.kotlinds.pokemonclient.world.TileKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
        assertNull(game.fieldMoveRule(FieldMoveKind.HEADBUTT))
        // Defog needs the Relic Badge (FieldMoves_CheckDefog); Teleport no badge (FieldMoves_CheckTeleport).
        assertEquals(Triple(432, "Relic", 4), game.fieldMoveRule(FieldMoveKind.DEFOG)!!.let { Triple(it.move.value, it.badge, it.badgeId) })
        assertEquals(100 to null, game.fieldMoveRule(FieldMoveKind.TELEPORT)!!.let { it.move.value to it.badge })
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
        assertEquals(dev.kotlinds.pokemonclient.world.WarpTrigger.Press(dev.kotlinds.pokemonclient.Direction.EAST), stairs.trigger)
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

    /**
     * Platinum's wild encounter tables (`WildEncounters`, pl_enc_data.narc) in the common model, as the decomp's
     * res/field/encounters JSON says: Route 201 (zone 342), land rate 30, Starly / Bidoof / Kricketot, with the day's
     * and night's slots 2-3, the swarm (Doduo), the Poké Radar (Nidoran); Valley Windworks (zone 200) surfing rate 10.
     */
    @Test
    fun `wild encounter tables from the ROM`() {
        val world = PlatinumRom.requireGame().world!!
        assertEquals(dev.kotlinds.pokemonclient.world.EncounterTables.DECODED, world.encounterTables)
        val wild = assertNotNull(world.wildEncounters(342))
        fun walk(time: TimeOfDay?, condition: EncounterCondition? = null) =
            wild.groups.single { it.method == EncounterMethod.WALK && it.time == time && it.condition == condition }
        // Morning: Starly (slots 0, 2, 4, 6, 8, 10: 20+10+10+5+4+1), Bidoof (1, 5, 7, 9, 11: 20+10+5+4+1), Kricketot (3).
        assertEquals(
            listOf(EncounterSlot(SpeciesId(396), 50, 2..3), EncounterSlot(SpeciesId(399), 40, 2..3), EncounterSlot(SpeciesId(401), 10, 3..3)),
            walk(TimeOfDay.MORNING).slots,
        )
        assertEquals(30, walk(TimeOfDay.MORNING).rate)
        // Night: slots 2-3 are Kricketot and Bidoof.
        assertEquals(listOf(SpeciesId(396) to 40, SpeciesId(399) to 50, SpeciesId(401) to 10), walk(TimeOfDay.NIGHT).slots.map { it.species to it.chance })
        assertEquals(listOf(EncounterSlot(SpeciesId(84), 40, 2..2)), walk(null, EncounterCondition.SWARM).slots)
        assertEquals(listOf(SpeciesId(32) to 11, SpeciesId(29) to 11), walk(null, EncounterCondition.POKE_RADAR).slots.map { it.species to it.chance })
        assertTrue(wild.groups.none { it.method == EncounterMethod.SURF })
        // The roll: a flat 40 on foot, running or not (ShouldGetRandomEncounter), then the rate.
        assertEquals(0.40 * 0.30, world.encounterChance(342, water = false, EncounterConditions(MovementMode.WALK)), 1e-9)
        assertEquals(0.40 * 0.30, world.encounterChance(342, water = false, EncounterConditions(MovementMode.RUN)), 1e-9)
        assertEquals(0.70 * 0.30, world.encounterChance(342, water = false, EncounterConditions(MovementMode.BIKE)), 1e-9)
        // Valley Windworks: surfing, Shellos and Tentacool 20-30 (60 % + 30 %).
        val windworks = assertNotNull(world.wildEncounters(200)).groups.single { it.method == EncounterMethod.SURF }
        assertEquals(10, windworks.rate)
        assertEquals(EncounterSlot(SpeciesId(422), 60, 20..30), windworks.slots.first())
        assertEquals(0.40 * 0.10, world.encounterChance(200, water = true, EncounterConditions(MovementMode.WALK)), 1e-9)
        // Twinleaf Town: water only (its pond); the player's house has no table.
        assertEquals(0.0, world.encounterChance(411, water = false, EncounterConditions(MovementMode.RUN)))
        assertNull(world.wildEncounters(415))
        // Walking doesn't make Platinum's roll rarer: the grass is run through (no walked zone).
        val weights = dev.kotlinds.pokemonclient.world.StepWeights.of(world, EncounterConditions(MovementMode.WALK, travelMovement = MovementMode.RUN), listOf(342))
        assertTrue(weights.walkedZones.isEmpty(), weights.toString())
        assertTrue((weights.landEncounter[342] ?: 0) > 0, weights.toString())
    }

    /**
     * The same field move actions as every game, said unsupported while Platinum's party and party menu aren't
     * decoded: Fly is listed unavailable (NOT_SUPPORTED_BY_GAME), never "no Pokémon knows Fly" nor tried on a menu the
     * library can't read.
     */
    @Test
    fun `field moves are said unsupported, not missing`() {
        val game = PlatinumRom.requireGame()
        val state = game.state(PlatinumFixtures.load("pt_bedroom"))
        val access = assertNotNull(state.fieldMoves)
        assertEquals(dev.kotlinds.pokemonclient.world.FieldMoveAccess.NotSupported, access[FieldMoveKind.FLY])
        assertEquals(dev.kotlinds.pokemonclient.world.FieldMoveAccess.Unknown, access[FieldMoveKind.WHIRLPOOL])
        val fly = dev.kotlinds.pokemonclient.actions.ActionRegistry.of().unavailable(state, dev.kotlinds.pokemonclient.actions.ActionMode.ASSISTED)
            .singleOrNull { it.name == "fly" }
        if (state.screen is Screen.Overworld) {
            assertEquals(dev.kotlinds.pokemonclient.actions.UnavailableReason.NOT_SUPPORTED_BY_GAME, assertNotNull(fly).reason)
        }
    }
}
