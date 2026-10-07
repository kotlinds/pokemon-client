package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.games.gen4.Gen4RomBytes
import dev.kotlinds.NdsRom
import dev.kotlinds.pokemonclient.games.gen4.Gen4MessageFile
import dev.kotlinds.pokemonclient.games.gen4.Gen4Structs
import dev.kotlinds.pokemonclient.games.gen4.Gen4WorldFiles
import dev.kotlinds.pokemonclient.games.gen4.Gen4WorldSource
import dev.kotlinds.pokemonclient.games.gen4.Gen4ZoneEvents
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.world.Region
import dev.kotlinds.pokemonclient.world.WarpTrigger
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.ScriptWarp
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.Trigger
import dev.kotlinds.pokemonclient.world.TriggerWarp

/**
 * The static world of HeartGold / SoulSilver, decoded from the ROM by the Gen 4 decoder ([Gen4WorldSource]: matrices,
 * land data, events, areas). HGSS's own: the header table ([HgssMapHeaders], ARM9 `sMapHeaders`), the NARC paths
 * ([HgssWorldAddresses]), the tile behaviours ([HgssTileBehaviors]), the obstacle sprites, and what its script
 * bytecode tells ([HgssScripts]): triggers that do nothing, script warps within a zone, holes to another zone. Plus
 * the wild encounter tables and the regions (Johto / Kanto).
 *
 * The player's MapObject y is the BDHC height / 8 (verified live: Ecruteak 48 ↔ y 6, New Bark 16 ↔ y 2). The matrix
 * block altitudes are not added: the loader only uses them to place the 3D model (`altitude << 15`, ov01_021F5FB8),
 * and in the ROM only blocks without a zone (scenery around the routes) have a non-zero altitude. The game patches
 * some matrices at load time (Lake of Rage water level, Mahogany antenna tree, Safari Zone areas, src/map_matrix.c).
 */
class HgssWorldSource(rom: NdsRom, private val version: HgssVersion) : Gen4WorldSource<HgssMapHeader>(rom) {

    /** Every map header (index = zone id). */
    override val headers: List<HgssMapHeader> by lazy {
        val table = HgssWorldAddresses.mapHeadersAddress(version.gameCode)
            ?: error("no map header table address for ${version.gameCode}")
        HgssMapHeaders.decode(Gen4RomBytes.arm9Code(rom.arm9), table)
    }

    override val files = Gen4WorldFiles(
        mapMatrix = HgssWorldAddresses.MAP_MATRIX_NARC,
        landData = HgssWorldAddresses.LAND_DATA_NARC,
        zoneEvents = HgssWorldAddresses.ZONE_EVENT_NARC,
    )

    override val hiddenItemFlagBase: Int = HIDDEN_ITEMS_FLAG_BASE

    override val overworldName: String = "Johto and Kanto"

    /** The map section (place) names of this ROM ([HgssTextBanks.MAP_SECTION_NAMES]), in its language. */
    private val placeNames: Gen4MessageFile? by lazy {
        narc(HgssGameData.MESSAGE_NARC).getOrNull(HgssTextBanks.MAP_SECTION_NAMES.value)?.let { Gen4MessageFile(it) }
    }

    /**
     * The place shown in game, from this ROM's own header and text (never another game's: two games may be loaded),
     * and the map's own name ([HgssData.internalMapName], the decomp's `MAP_*` constant: the same in every release).
     */
    override fun readMapName(zoneId: Int): MapName =
        MapName(zoneId, header(zoneId)?.let { placeNames?.line(it.mapsec) }?.takeIf { it.isNotEmpty() }, HgssData.internalMapName(zoneId))

    override fun tileKind(behavior: Int, blocked: Boolean): TileKind = HgssTileBehaviors.kind(behavior, blocked)

    override fun warpTrigger(behavior: Int): WarpTrigger = HgssTileBehaviors.warpTrigger(behavior)

    override fun arrivalStep(behavior: Int): dev.kotlinds.pokemonclient.Direction? = HgssTileBehaviors.arrivalStep(behavior)

    /** The obstacles by sprite name (include/constants/sprites.h): the sprite ids are HGSS's. */
    override fun obstacle(sprite: Int): FieldMoveKind? = when (HgssData.spriteName(sprite)) {
        "TREE" -> FieldMoveKind.CUT
        "BREAKROCK" -> FieldMoveKind.ROCK_SMASH
        "ROCK" -> FieldMoveKind.STRENGTH
        else -> null
    }

    private val scriptFiles: List<ByteArray> by lazy { narc(HgssWorldAddresses.SCRIPT_NARC) }

    /** The wild encounter tables, by `wildEncounterBank` (null where a member can't be read). */
    private val encounterBanks: List<HgssEncounterTable?> by lazy {
        narc(HgssWorldAddresses.encounterNarc(version.gameCode)).map { HgssEncounterTable.parse(it) }
    }

    /** The wild encounter table of zone [zoneId], or null when it has none (`ENCDATA_NA`). */
    fun encounters(zoneId: Int): HgssEncounterTable? =
        header(zoneId)?.wildEncounterBank?.takeIf { it != NO_ENCOUNTERS }?.let { encounterBanks.getOrNull(it) }

    override val encounterTables = dev.kotlinds.pokemonclient.world.EncounterTables.DECODED

    override fun encounterChance(zoneId: Int, water: Boolean, conditions: dev.kotlinds.pokemonclient.world.EncounterConditions): Double =
        encounters(zoneId)?.let { HgssEncounters.chance(it, water, conditions) } ?: 0.0

    override fun wildEncounters(zoneId: Int): dev.kotlinds.pokemonclient.world.WildEncounters? =
        encounters(zoneId)?.let { HgssEncounters.wild(zoneId, it) }?.takeIf { it.groups.isNotEmpty() }

    override fun regionOf(zoneId: Int): Region? = header(zoneId)?.region?.let { Region(it, if (it == HgssMapHeaders.REGION_KANTO) "Kanto" else "Johto") }

    /** The script file (bytecode, [HgssScripts]) of zone [zoneId], or null. */
    fun scriptFile(zoneId: Int): ByteArray? = header(zoneId)?.scriptsBank?.let { scriptFiles.getOrNull(it) }

    /** A coordinate trigger, with what its script tells: it does nothing ([Trigger.inert]), or when it stays quiet. */
    override fun trigger(zone: Int, index: Int, coord: Gen4ZoneEvents.CoordEvent): Trigger {
        val scripts = scriptFile(zone)
        val inert = scripts != null && HgssScripts.isEmpty(scripts, coord.script)
        val quiet = scripts?.let { HgssScripts.quietWhen(it, coord.script) }
        return Trigger(zone, index, coord.x, coord.z, coord.width, coord.height, coord.script, coord.variable, coord.value, inert, quiet)
    }

    /**
     * Coordinate triggers of [zone] whose script warps the player to another zone: holes ([HgssScripts.zoneWarp]). A
     * trigger on a warp's own tile leading to the warp's map is that warp (New Bark's trigger on the ladder up to Elm's
     * lab 2F runs the warp with a scene around it): the warp already lists it, it is no hole.
     */
    override fun triggerWarps(zone: Int, events: Gen4ZoneEvents): List<TriggerWarp> {
        if (events.coords.isEmpty()) return emptyList()
        val file = scriptFile(zone) ?: return emptyList()
        return events.coords.mapIndexedNotNull { i, c ->
            HgssScripts.zoneWarp(file, c.script, zone, headers.size)
                ?.takeIf { w -> events.warps.none { it.x == c.x && it.z == c.z && it.header == w.zone } }
                ?.let { w -> TriggerWarp(zone, i, c.x, c.z, w.zone, w.x, w.z) }
        }
    }

    /** The people of [zone] its scripts move elsewhere ([HgssScripts.movedPeople]). */
    override fun movedPeople(zone: Int): Set<Int> = scriptFile(zone)?.let(HgssScripts::movedPeople).orEmpty()

    /** The lift commands (`SetDynamicWarp`) of zone [zoneId]'s scripts ([HgssScripts.dynamicWarps]). */
    override fun dynamicWarps(zoneId: Int): List<DynamicWarp> =
        scriptFile(zoneId)?.let { file -> HgssScripts.dynamicWarps(file, headers.size).map { DynamicWarp(it.scriptId, it.zone, it.warp) } }.orEmpty()

    /** Coordinate triggers of [zone] whose script warps the player within [zone] itself ([HgssScripts]). */
    override fun scriptWarps(zone: Int, events: Gen4ZoneEvents): List<ScriptWarp> {
        if (events.coords.isEmpty()) return emptyList()
        val file = scriptFile(zone) ?: return emptyList()
        return events.coords.mapIndexedNotNull { i, c ->
            HgssScripts.sameZoneWarp(file, c.script, zone)?.let { w -> ScriptWarp(zone, i, w.x, w.z, Gen4Structs.DIRECTIONS.getOrNull(w.direction)) }
        }
    }

    companion object {
        /** `ENCDATA_NA` (include/map_header.h): a zone without wild encounters. */
        const val NO_ENCOUNTERS = 0xFF

        /** `HIDDEN_ITEMS_FLAG_BASE` (include/constants/flags.h). */
        const val HIDDEN_ITEMS_FLAG_BASE = 800
    }
}
