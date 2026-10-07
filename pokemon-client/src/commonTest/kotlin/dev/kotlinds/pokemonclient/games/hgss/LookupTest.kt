package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.data.KnowledgeLevel
import dev.kotlinds.pokemonclient.data.Lookup
import dev.kotlinds.pokemonclient.data.LookupKind
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `lookup` on the HeartGold ROM data (skipped without `POKEMON_ROM`). */
class LookupTest {

    private fun lookup(level: KnowledgeLevel = KnowledgeLevel.POKEDEX) = Lookup(HgssWorldRom.requireData(), level)

    /** `lookup encounters` standing on Route 1 (zone 9) with a Pokédex that has seen [seen]. */
    private fun encounters(level: KnowledgeLevel, seen: Set<Int>?, id: String = "here"): kotlinx.serialization.json.JsonObject {
        val world = HgssWorldRom.require()
        val context = dev.kotlinds.pokemonclient.data.EncounterContext(world, world::mapName, currentZone = 9, seen = seen?.map { dev.kotlinds.pokemonclient.state.SpeciesId(it) }?.toSet())
        return Lookup(HgssWorldRom.requireData(), level, context).lookup(LookupKind.ENCOUNTERS, id).getOrThrow()
    }

    private fun kotlinx.serialization.json.JsonObject.dayLand() = this["maps"]!!.jsonArray.single().jsonObject["groups"]!!.jsonArray.map { it.jsonObject }
        .single { it["method"]!!.jsonPrimitive.content == "walk" && it["time"]?.jsonPrimitive?.content == "day" }

    @Test
    fun encountersNameOnlyTheSpeciesSeenAtThePokedexLevel() {
        // Seen Pidgey (16) only: Rattata, Sentret and Furret are counted, not named.
        val pokedex = encounters(KnowledgeLevel.POKEDEX, seen = setOf(16)).dayLand()
        assertEquals(listOf("species:16 PIDGEY 45% Lv2-4"), pokedex["species"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("3 species not seen yet (55% of these encounters)", pokedex["unseen"]!!.jsonPrimitive.content)
        assertEquals(20, pokedex["rate"]!!.jsonPrimitive.content.toInt())
        // A walkthrough names them all; Route 1 by name gives the same map.
        val all = encounters(KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH, seen = null, id = "Route 1").dayLand()
        assertEquals(4, all["species"]!!.jsonArray.size)
        assertTrue("unseen" !in all)
        // Below the Pokédex level: refused.
        assertTrue(Lookup(HgssWorldRom.requireData(), KnowledgeLevel.NONE).lookup(LookupKind.ENCOUNTERS, "here").isFailure)
    }

    @Test
    fun speciesByIdAndByNameGiveTheSameSheet() {
        val byId = lookup().lookup(LookupKind.SPECIES, "species:1").getOrThrow()
        val byName = lookup().lookup(LookupKind.SPECIES, "bulbasaur").getOrThrow()
        assertEquals(byId, byName)
        assertEquals(318, byId["base_stats"]!!.jsonObject["total"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("Grass", "Poison"), byId["types"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun speciesSheetGivesTheGrowthCurveAndTheMachines() {
        val bulbasaur = lookup().lookup(LookupKind.SPECIES, "species:1").getOrThrow()
        val growth = bulbasaur["growth_rate"]!!.jsonObject
        assertEquals("medium_slow", growth["curve"]!!.jsonPrimitive.content)
        assertEquals(1059860, growth["exp_to_level_100"]!!.jsonPrimitive.content.toInt())
        val machines = bulbasaur["machines"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("TM22 SolarBeam" in machines || machines.any { it.startsWith("TM22 ") }, machines.toString())
        assertTrue(machines.any { it.startsWith("HM01 ") }, "Bulbasaur learns Cut")
        assertTrue(machines.none { it.startsWith("TM24 ") }, "no Thunderbolt")
    }

    @Test
    fun movesMachinesAndTypes() {
        val thunderbolt = lookup().lookup(LookupKind.MOVE, "Thunderbolt").getOrThrow()
        assertEquals("95", thunderbolt["power"]!!.jsonPrimitive.content)
        val tm01 = lookup().lookup(LookupKind.MACHINE, "TM01").getOrThrow()
        assertEquals("Focus Punch", tm01["move"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        val fire = lookup().lookup(LookupKind.TYPE, "fire").getOrThrow()
        assertTrue("Grass" in fire["super_effective"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun theKnowledgeLevelLimitsWhatIsShown() {
        val none = lookup(KnowledgeLevel.NONE)
        assertTrue(none.lookup(LookupKind.SPECIES, "species:1").isFailure)
        assertEquals("300", none.lookup(LookupKind.ITEM, "Potion").getOrThrow()["price"]!!.jsonPrimitive.content)
        assertTrue(lookup().lookup(LookupKind.MOVE, "no such move").isFailure)
    }

    @Test
    fun itemsTellWhatTheyDoAtEveryKnowledgeLevel() {
        // What the bag's description tells a player: shown without the Pokédex.
        val none = lookup(KnowledgeLevel.NONE)
        fun effect(name: String) = none.lookup(LookupKind.ITEM, name).getOrThrow()["effect"]?.jsonObject
        assertEquals("20", effect("Potion")!!["hp"]!!.jsonPrimitive.content)
        assertEquals("full", effect("Max Potion")!!["hp"]!!.jsonPrimitive.content)
        // Full Heal: every major status and confusion (not infatuation).
        assertEquals(listOf("sleep", "poison", "burn", "freeze", "paralysis", "confusion"), effect("Full Heal")!!["cures"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("paralysis"), effect("Parlyz Heal")!!["cures"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Full Restore: HP and statuses in one turn (Claude used a Max Potion then a status heal on Lance's Dragonite).
        val fullRestore = effect("Full Restore")!!
        assertEquals("full", fullRestore["hp"]!!.jsonPrimitive.content)
        assertEquals("a fainted Pokémon", effect("Revive")!!["revives"]!!.jsonPrimitive.content)
        assertEquals("10 PP to one move", effect("Ether")!!["pp"]!!.jsonPrimitive.content)
        assertEquals("1", effect("X Attack")!!["battle_stages"]!!.jsonObject["attack"]!!.jsonPrimitive.content)
        assertEquals("1", effect("Rare Candy")!!["level_up"]!!.jsonPrimitive.content)
        assertEquals("10", effect("HP Up")!!["effort_values"]!!.jsonObject["hp"]!!.jsonPrimitive.content)
        // Nothing to use on a Pokémon: no effect.
        assertEquals(null, effect("Poke Ball"))
    }

    /** A party member of species [species] (index [n]) knowing [moves], for `mon:` ids and who can learn a TM. */
    private fun partyMon(n: Int, species: Int, moves: List<Int> = emptyList()) = dev.kotlinds.pokemonclient.state.PartyMon(
        id = dev.kotlinds.pokemonclient.state.MonId(n.toLong(), 7), slot = n - 1,
        species = dev.kotlinds.pokemonclient.state.Named(dev.kotlinds.pokemonclient.state.SpeciesId(species), "MON$n"), nickname = null, level = 30,
        hp = 50, maxHp = 50, status = null, types = emptyList(), heldItem = null, ability = null,
        moves = moves.map { dev.kotlinds.pokemonclient.state.KnownMove(dev.kotlinds.pokemonclient.state.Named(dev.kotlinds.pokemonclient.state.MoveId(it), "M$it"), 10, 10) },
        stats = emptyMap(), exp = 0, expToNextLevel = null, isEgg = false,
    )

    @Test
    fun machinesSayWhoOfThePartyCanLearnThem() {
        // Race: Shadow Ball (TM30) taught blindly to Tentacruel and Feraligatr (their first stages here), Fly (HM02) to Togepi and Rattata.
        val party = listOf(partyMon(1, 72), partyMon(2, 92), partyMon(3, 158), partyMon(4, 18))
        val data = HgssWorldRom.requireData()
        val lookup = Lookup(data, KnowledgeLevel.POKEDEX, party = party)
        val shadowBall = lookup.lookup(LookupKind.MACHINE, "TM30").getOrThrow()
        assertEquals(listOf("${party[1].id} MON2"), shadowBall["party_can_learn"]!!.jsonArray.map { it.jsonPrimitive.content })
        // The machine's item id gives the same answer; Fly: Pidgeot only.
        assertEquals(shadowBall, lookup.lookup(LookupKind.MACHINE, "item:357").getOrThrow())
        assertEquals(listOf("${party[3].id} MON4"), lookup.lookup(LookupKind.MACHINE, "HM02").getOrThrow()["party_can_learn"]!!.jsonArray.map { it.jsonPrimitive.content })
        // Nobody: said so.
        assertEquals(listOf("nobody in the party"), Lookup(data, KnowledgeLevel.POKEDEX, party = listOf(party[2])).lookup(LookupKind.MACHINE, "HM02").getOrThrow()["party_can_learn"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun learnsetsListTheMachinesAndAcceptPartyIds() {
        val party = listOf(partyMon(1, 1))
        val lookup = Lookup(HgssWorldRom.requireData(), KnowledgeLevel.POKEDEX, party = party)
        val byMon = lookup.lookup(LookupKind.LEARNSET, party[0].id.toString()).getOrThrow()
        assertEquals(lookup.lookup(LookupKind.LEARNSET, "species:1").getOrThrow(), byMon)
        assertTrue(byMon["machines"]!!.jsonArray.any { it.jsonPrimitive.content.startsWith("HM01 ") }, "Bulbasaur learns Cut")
        assertEquals(lookup.lookup(LookupKind.SPECIES, "species:1").getOrThrow(), lookup.lookup(LookupKind.SPECIES, party[0].id.toString()).getOrThrow())
        assertTrue(lookup.lookup(LookupKind.SPECIES, "mon:00000009.00000009").exceptionOrNull()?.message.orEmpty().contains("in the party"))
    }

    @Test
    fun itemsAreFoundByTheirNames() {
        val none = lookup(KnowledgeLevel.NONE)
        for ((name, id) in listOf("Revive" to 28, "REVIVE" to 28, "Full Restore" to 23, "Poke Ball" to 4, "Poké Ball" to 4, "Max Repel" to 77, "HP Up" to 45, "Parlyz Heal" to 22, "X Sp. Def" to 62)) {
            assertEquals("item:$id", none.lookup(LookupKind.ITEM, name).getOrThrow()["id"]!!.jsonPrimitive.content, name)
        }
    }

    @Test
    fun unknownNamesListCloseMatches() {
        val error = lookup(KnowledgeLevel.NONE).lookup(LookupKind.ITEM, "Revve").exceptionOrNull()?.message.orEmpty()
        assertTrue("Revive" in error, error)
        val move = lookup().lookup(LookupKind.MOVE, "thunderbol").exceptionOrNull()?.message.orEmpty()
        assertTrue("Thunderbolt" in move, move)
    }
}

/** Estimated effectiveness on a real battle fixture with the ROM's type chart (skipped without `POKEMON_ROM`). */
class MatchupsTest {
    @Test
    fun ampharosMovesAgainstTheWildPokemon() {
        val data = HgssWorldRom.requireData()
        val state = HgssGame(HgssVersion.HEARTGOLD_US).state(HgssFixtures.load("bt_cmd_hidden"))
        val battle = state.battle ?: error("not a battle fixture")
        val matchups = dev.kotlinds.pokemonclient.data.Matchups.estimate(battle, data)
        assertTrue(matchups.isNotEmpty())
        assertTrue(matchups.all { it.multiplier in setOf(0.0, 0.25, 0.5, 1.0, 2.0, 4.0) })
    }
}

/**
 * Place names and map types read from the ROM equal the decomp's tables, for every map (skipped without `POKEMON_ROM`).
 * The place names come from the world source's own ROM (its header and text), with no game data installed globally.
 */
class HgssMapNamesTest {
    @Test
    fun everyMapHasTheSamePlaceNameAndTypeAsTheDecomp() {
        val world = HgssWorldRom.require()
        val expectedLocations = HgssData.lines("map_locations.txt")
        val expectedTypes = HgssData.lines("map_types.txt")
        HgssData.useWorld(world)
        try {
            for (zone in expectedLocations.indices) {
                kotlin.test.assertEquals(expectedLocations[zone].ifEmpty { null }, world.mapName(zone).location, "location of zone $zone")
                kotlin.test.assertEquals(expectedLocations[zone].ifEmpty { null }, HgssData.bundledMapName(zone).location, "bundled location of zone $zone")
                kotlin.test.assertEquals(expectedTypes[zone].ifEmpty { null }, HgssData.mapType(zone), "type of zone $zone")
            }
        } finally {
            HgssData.useWorld(null)
        }
    }
}
