package dev.kotlinds.pokemonclient.games.platinum

import dev.kotlinds.pokemonclient.data.TextBankId
import dev.kotlinds.NarcArchive
import dev.kotlinds.NdsRom
import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.games.gen4.Gen4MapHeader
import dev.kotlinds.pokemonclient.games.gen4.Gen4MessageFile
import dev.kotlinds.pokemonclient.games.gen4.Gen4RomBytes
import dev.kotlinds.pokemonclient.games.gen4.Gen4RomBytes.u16
import dev.kotlinds.pokemonclient.games.gen4.Gen4RomBytes.u8
import dev.kotlinds.pokemonclient.games.gen4.Gen4WorldFiles
import dev.kotlinds.pokemonclient.games.gen4.Gen4WorldSource
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.world.EncounterConditions
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.WildEncounters
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.WarpTrigger

/**
 * The text banks of Platinum (`msgdata/pl_msg.narc`, member = `TEXT_BANK_*` of the decomp's generated
 * text_banks.h), decoded with the Gen 4 message format ([Gen4MessageFile]). The bank numbers are the same in every
 * language release.
 */
class PlatinumText(private val rom: NdsRom) {
    private val files: List<ByteArray> by lazy { NarcArchive.unpack(rom.files[MESSAGE_NARC] ?: error("missing $MESSAGE_NARC")) }

    /** Line [line] of bank [bank], control codes removed; null when out of range. */
    fun line(bank: TextBankId, line: Int): String? = files.getOrNull(bank.value)?.let { Gen4MessageFile(it).line(line) }

    companion object {
        const val MESSAGE_NARC = "msgdata/pl_msg.narc"

        /** TEXT_BANK_MAIN_MENU_ALERTS: line 5 is the new game warning. */
        val MAIN_MENU_ALERTS = TextBankId(14)
        const val MAIN_MENU_ALERT_NEW_GAME = 5

        /** TEXT_BANK_ROWAN_INTRO (res/text/rowan_intro.json). */
        val ROWAN_INTRO = TextBankId(389)

        /** TEXT_BANK_LOCATION_NAMES: index = `MapHeader.mapLabelTextID`. */
        val LOCATION_NAMES = TextBankId(433)

        /** TEXT_BANK_MAIN_MENU_OPTIONS (MainMenuOptions_Text_*). */
        val MAIN_MENU_OPTIONS = TextBankId(550)

        /** TEXT_BANK_ROWAN_INTRO_TV_APP: line 0 is the TV programme. */
        val ROWAN_INTRO_TV = TextBankId(607)
    }
}

/** One entry of Platinum's `sMapHeaders` (include/map_header.h): only what the world decoder needs. */
data class PlatinumMapHeader(
    val zoneId: Int,
    override val matrixId: Int,
    override val scriptsBank: Int,
    override val eventsBank: Int,
    /** `mapLabelTextID`: the location name's line in [PlatinumText.LOCATION_NAMES]. */
    val locationName: Int,
    /** `mapType` (7 bits). */
    val mapType: Int,
    /** `wildEncountersArchiveID`: member of the encounter NARC ([PlatinumWorldSource.ENCOUNTER_NARC]), 0xFFFF when none. */
    val wildEncounterBank: Int,
    override val bikeAllowed: Boolean,
    override val flyAllowed: Boolean,
) : Gen4MapHeader

/** Decodes Platinum's map header table from the (decompressed) ARM9 binary. */
object PlatinumMapHeaders {
    /** NELEMS(sMapHeaders): 0x3798 bytes / 24 (xMAP). */
    const val COUNT = 593
    const val SIZE = 24
    const val ARM9_LOAD_ADDRESS = 0x02000000L

    /**
     * Layout (include/map_header.h, 24 bytes): u8 areaDataArchiveID, u8 unk_01, u16 mapMatrixID, u16 scriptsArchiveID,
     * u16 initScriptsArchiveID, u16 msgArchiveID, u16 dayMusicID, u16 nightMusicID, u16 wildEncountersArchiveID,
     * u16 eventsArchiveID, u16 (mapLabelTextID:8, mapLabelWindowID:8), u8 weather, u8 cameraType, u16 (mapType:7,
     * battleBG:5, isBikeAllowed:1, isRunningAllowed:1, isEscapeRopeAllowed:1, isFlyAllowed:1). Bitfields from bit 0.
     * (Not the HGSS layout: [dev.kotlinds.pokemonclient.games.hgss.HgssMapHeaders].)
     */
    fun decode(arm9: ByteArray, tableAddress: Long): List<PlatinumMapHeader> {
        val base = (tableAddress - ARM9_LOAD_ADDRESS).toInt()
        require(base >= 0 && base + COUNT * SIZE <= arm9.size) { "map header table outside the ARM9 binary" }
        return List(COUNT) { id ->
            val o = base + id * SIZE
            val flags = u16(arm9, o + 22)
            PlatinumMapHeader(
                zoneId = id,
                matrixId = u16(arm9, o + 2),
                scriptsBank = u16(arm9, o + 4),
                eventsBank = u16(arm9, o + 16),
                locationName = u8(arm9, o + 18),
                mapType = flags and 0x7F,
                wildEncounterBank = u16(arm9, o + 14),
                bikeAllowed = (flags shr 12) and 1 == 1,
                flyAllowed = (flags shr 15) and 1 == 1,
            )
        }
    }
}

/**
 * Platinum's tile behaviours (`enum TileBehavior`, include/constants/field/map_tile_behaviors.h) as [TileKind]s.
 * Most codes are the Gen 4 ones HeartGold / SoulSilver also use (water 0x10-0x15, ledges 0x38-0x3B, warps 0x5E-0x6F,
 * door 0x69, bridges 0x70-0x75, counter 0x80, PC 0x83); a few differ (0x2C is REFLECTIVE here, MAGMA in HGSS; no
 * ladders at 0x3C-0x3E; no whirlpool 0x11), so the table is per game.
 */
object PlatinumTileBehaviors {
    private val WATER = setOf(0x10, 0x15, 0x19, 0x7C)
    private val GRASS = setOf(0x02, 0x03, 0xA6, 0xA7)
    private val WARPS = (0x5E..0x6F).toSet() - setOf(0x60, 0x61, 0x66, 0x68)
    private val RAILINGS = mapOf(
        0x30 to setOf(Direction.EAST), 0x31 to setOf(Direction.WEST), 0x32 to setOf(Direction.NORTH), 0x33 to setOf(Direction.SOUTH),
        0x34 to setOf(Direction.NORTH, Direction.EAST), 0x35 to setOf(Direction.NORTH, Direction.WEST),
        0x36 to setOf(Direction.SOUTH, Direction.EAST), 0x37 to setOf(Direction.SOUTH, Direction.WEST),
        0x49 to setOf(Direction.NORTH, Direction.SOUTH), 0x4A to setOf(Direction.WEST, Direction.EAST),
    )

    fun kind(behavior: Int, blocked: Boolean): TileKind = when (behavior) {
        0x00 -> if (blocked) TileKind.Wall else TileKind.Floor
        in GRASS -> TileKind.TallGrass
        0x08 -> TileKind.Cave
        0x13 -> TileKind.Waterfall
        in WATER -> TileKind.Water(surfable = true, fishable = true)
        0x20 -> TileKind.Ice
        0x21 -> TileKind.Sand
        0x38 -> TileKind.Ledge(Direction.EAST)
        0x39 -> TileKind.Ledge(Direction.WEST)
        0x3A -> TileKind.Ledge(Direction.NORTH)
        0x3B -> TileKind.Ledge(Direction.SOUTH)
        in RAILINGS -> if (blocked) TileKind.Wall else TileKind.Railing(RAILINGS.getValue(behavior))
        // TILE_BEHAVIOR_ROCK_CLIMB_N_S / _E_W (map_tile_behaviors.h).
        0x4B -> TileKind.RockClimb(dev.kotlinds.pokemonclient.world.ClimbAxis.NORTH_SOUTH)
        0x4C -> TileKind.RockClimb(dev.kotlinds.pokemonclient.world.ClimbAxis.EAST_WEST)
        0x70 -> if (blocked) TileKind.Wall else TileKind.Bridge(start = true)
        0x71, 0x72, 0x74, 0x75 -> if (blocked) TileKind.Wall else TileKind.Bridge()
        0x73 -> TileKind.Bridge(overWater = true)
        0x80 -> TileKind.Counter
        0x83 -> TileKind.Pc
        in WARPS -> TileKind.Door
        else -> if (blocked) TileKind.Wall else TileKind.Floor
    }

    /**
     * What takes a warp on a tile of [behavior], by the same rules as HGSS (src/overlay005/field_control.c,
     * `Field_CheckMapTransition` and the step's transition check): a press towards its direction on the east / west /
     * south mats and side stairs (0x5E, 0x5F, 0x62, 0x63, 0x65, 0x6C, 0x6D, 0x6F), the end of a step onto a north
     * entrance, a warp panel or an escalator (0x64, 0x6E, 0x67, 0x6A, 0x6B), a door walked into (0x69); nothing elsewhere.
     */
    fun warpTrigger(behavior: Int): WarpTrigger = when (behavior) {
        0x62, 0x6C, 0x5E -> WarpTrigger.Press(Direction.EAST)
        0x63, 0x6D, 0x5F -> WarpTrigger.Press(Direction.WEST)
        0x65, 0x6F -> WarpTrigger.Press(Direction.SOUTH)
        0x64, 0x6E, 0x67, 0x69, 0x6A, 0x6B -> WarpTrigger.Enter
        else -> WarpTrigger.Never
    }
}

/**
 * The static world of Platinum, decoded from the ROM by the Gen 4 decoder ([Gen4WorldSource]: map matrices, land
 * data, zone events, the same file formats as HGSS). Platinum's own: the header table ([PlatinumMapHeaders]), the NARC
 * paths, the tile behaviours ([PlatinumTileBehaviors]), the obstacle sprites, the hidden item flags, the names and the
 * wild encounter tables ([PlatinumEncounters]).
 *
 * Not done yet: what needs Platinum's script bytecode (its commands are not HGSS's): triggers that do nothing, script
 * warps and holes ([Gen4WorldSource.scriptWarps], [Gen4WorldSource.triggerWarps]); region / dynamic matrices (the
 * Distortion World...).
 */
class PlatinumWorldSource(rom: NdsRom, private val version: PlatinumVersion) : Gen4WorldSource<PlatinumMapHeader>(rom) {

    override val headers: List<PlatinumMapHeader> by lazy {
        PlatinumMapHeaders.decode(Gen4RomBytes.arm9Code(rom.arm9), version.mapHeaders)
    }

    val text = PlatinumText(rom)

    override val files = Gen4WorldFiles(
        mapMatrix = "fielddata/mapmatrix/map_matrix.narc",
        landData = "fielddata/land_data/land_data.narc",
        zoneEvents = "fielddata/eventdata/zone_event.narc",
    )

    /** `FLAG_OFFSET_HIDDEN_ITEMS` (include/script_manager.h). */
    override val hiddenItemFlagBase: Int = 730

    override val overworldName: String = "Sinnoh"

    /** The place shown in game (the ROM's text) and the map's own name ([PlatinumMapNames]). */
    override fun readMapName(zoneId: Int): MapName = MapName(zoneId, locationName(zoneId), PlatinumMapNames.of(zoneId))

    override fun tileKind(behavior: Int, blocked: Boolean): TileKind = PlatinumTileBehaviors.kind(behavior, blocked)

    override fun warpTrigger(behavior: Int): WarpTrigger = PlatinumTileBehaviors.warpTrigger(behavior)

    /**
     * `OBJ_EVENT_GFX_STRENGTH_BOULDER`, `_ROCK_SMASH`, `_CUT_TREE` (enum ObjectEventGfx, the pokeplatinum decomp's
     * generated/object_events_gfx.txt, from 0).
     */
    override fun obstacle(sprite: Int): FieldMoveKind? = when (sprite) {
        SPRITE_STRENGTH_BOULDER -> FieldMoveKind.STRENGTH
        SPRITE_ROCK_SMASH -> FieldMoveKind.ROCK_SMASH
        SPRITE_CUT_TREE -> FieldMoveKind.CUT
        else -> null
    }

    /** The wild encounter tables, by `wildEncountersArchiveID` (null where a member can't be read). */
    private val encounterBanks: List<PlatinumEncounterTable?> by lazy { narc(ENCOUNTER_NARC).map { PlatinumEncounterTable.parse(it) } }

    /** The wild encounter table of zone [zoneId], or null when it has none (`MapHeader_HasWildEncounters`). */
    fun encounters(zoneId: Int): PlatinumEncounterTable? =
        header(zoneId)?.wildEncounterBank?.takeIf { it != NO_ENCOUNTERS }?.let { encounterBanks.getOrNull(it) }

    override val encounterTables = dev.kotlinds.pokemonclient.world.EncounterTables.DECODED

    override fun encounterChance(zoneId: Int, water: Boolean, conditions: EncounterConditions): Double =
        encounters(zoneId)?.let { PlatinumEncounters.chance(it, water, conditions) } ?: 0.0

    override fun wildEncounters(zoneId: Int): WildEncounters? =
        encounters(zoneId)?.let { PlatinumEncounters.wild(zoneId, it) }?.takeIf { it.groups.isNotEmpty() }

    /** The location name of zone [zoneId] (e.g. "Twinleaf Town"), null when unknown. */
    fun locationName(zoneId: Int): String? = header(zoneId)?.let { text.line(PlatinumText.LOCATION_NAMES, it.locationName) }

    companion object {
        const val SPRITE_STRENGTH_BOULDER = 84
        const val SPRITE_ROCK_SMASH = 85
        const val SPRITE_CUT_TREE = 86

        /** The wild encounter tables (`NARC_INDEX_FIELDDATA__ENCOUNTDATA__PL_ENC_DATA`, src/map_header_data.c). */
        const val ENCOUNTER_NARC = "fielddata/encountdata/pl_enc_data.narc"

        /** `wildEncountersArchiveID` of a zone without wild encounters (`MapHeader_HasWildEncounters`). */
        const val NO_ENCOUNTERS = 0xFFFF
    }
}
