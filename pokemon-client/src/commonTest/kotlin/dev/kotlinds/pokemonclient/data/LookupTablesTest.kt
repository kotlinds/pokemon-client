package dev.kotlinds.pokemonclient.data

import dev.kotlinds.pokemonclient.actions.StubGameData
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.EncounterTables
import dev.kotlinds.pokemonclient.world.WorldSource
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `lookup encounters` on a game whose encounter tables aren't decoded ([EncounterTables.UNKNOWN]). */
class LookupTablesTest {

    private val world = object : WorldSource {
        override fun areaOf(zoneId: Int): Area? = null
        override val zoneCount = 3
    }

    @Test
    fun unknownTablesAreSaidUnknownNeverNone() {
        val context = EncounterContext(world, { MapName(it, map = "Route $it") }, currentZone = 1, seen = null)
        val answer = Lookup(StubGameData(), KnowledgeLevel.POKEDEX_PLUS_WALKTHROUGH, context).lookup(LookupKind.ENCOUNTERS, "here").getOrThrow()
        assertEquals("unknown", answer["tables"]!!.jsonPrimitive.content)
        assertTrue(answer["maps"]!!.jsonArray.isEmpty())
        val note = answer["note"]!!.jsonPrimitive.content
        assertTrue("no encounter tables for this game" in note && "no wild Pokémon" !in note, note)
    }
}
