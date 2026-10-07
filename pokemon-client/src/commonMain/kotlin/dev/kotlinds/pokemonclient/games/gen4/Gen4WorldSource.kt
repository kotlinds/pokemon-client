package dev.kotlinds.pokemonclient.games.gen4

import dev.kotlinds.pokemonclient.Direction

import dev.kotlinds.NarcArchive
import dev.kotlinds.NdsRom
import dev.kotlinds.pokemonclient.SnapshotCache
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.world.Area
import dev.kotlinds.pokemonclient.world.Elevator
import dev.kotlinds.pokemonclient.world.ElevatorOperator
import dev.kotlinds.pokemonclient.world.ElevatorStop
import dev.kotlinds.pokemonclient.world.AreaKind
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.PersonTemplate
import dev.kotlinds.pokemonclient.world.ScriptWarp
import dev.kotlinds.pokemonclient.world.Sign
import dev.kotlinds.pokemonclient.world.SignKind
import dev.kotlinds.pokemonclient.world.TileInfo
import dev.kotlinds.pokemonclient.world.TileKind
import dev.kotlinds.pokemonclient.world.Trigger
import dev.kotlinds.pokemonclient.world.TriggerWarp
import dev.kotlinds.pokemonclient.world.Warp
import dev.kotlinds.pokemonclient.world.WarpTrigger
import dev.kotlinds.pokemonclient.world.WorldSource

/**
 * What the Gen 4 world decoder needs of one entry of a game's `sMapHeaders` table (include/map_header.h): the
 * layouts differ between Diamond / Pearl / Platinum and HeartGold / SoulSilver, these fields exist in both.
 */
interface Gen4MapHeader {
    /** Member of the map matrix NARC. */
    val matrixId: Int

    /** Member of the zone event NARC. */
    val eventsBank: Int

    /** Member of the script NARC holding the zone's scripts. */
    val scriptsBank: Int

    /** Fly (and Teleport) can be used from this zone: outdoors in the normal games, anywhere a randomizer says. */
    val flyAllowed: Boolean

    /** The Bicycle can be ridden in this zone. */
    val bikeAllowed: Boolean
}

/** Where a game keeps its world files in the ROM filesystem: the NARC paths differ, their formats don't. */
data class Gen4WorldFiles(
    /** `fielddata/mapmatrix/map_matrix.narc`: one matrix per `MapHeader.matrixId` ([Gen4MapMatrix]). */
    val mapMatrix: String,
    /** `fielddata/land_data/land_data.narc`: one member per matrix block ([Gen4LandData]). */
    val landData: String,
    /** `fielddata/eventdata/zone_event.narc`: one member per `MapHeader.eventsBank` ([Gen4ZoneEvents]). */
    val zoneEvents: String,
)

/**
 * The static world of a Gen 4 game, decoded from the ROM — the engine is the same in Diamond / Pearl / Platinum and
 * HeartGold / SoulSilver: zone → map header → map matrix (blocks of 32x32 tiles, [Gen4MapMatrix]) → land data of each
 * block ([Gen4LandData]: collision + behaviour per tile, BDHC heights) + zone events ([Gen4ZoneEvents]: warps, signs
 * and hidden items, people, coordinate triggers).
 *
 * Coordinates are the game's global tile coordinates, the ones of the player's MapObject x/z (and of the events):
 * tile (x, z) is tile (x % 32, z % 32) of block (x / 32, z / 32) of the zone's matrix. Areas start at (0, 0).
 * Heights ([TileInfo.heights]) are BDHC heights in world units (16 per tile side).
 *
 * An [Area] is a matrix: a matrix with a zone section (the overworld) holds every zone of its blocks and their
 * events; a matrix without one (buildings, caves, gyms) is loaded for one zone at a time, so its area holds that zone
 * only (two zones sharing such a matrix get two areas with the same [Area.id]).
 *
 * Per game (the subclasses): the header table ([headers]), the NARC paths ([files]), the tile behaviour codes
 * ([tileKind], [warpTrigger]), the overworld sprites that are field move obstacles ([obstacle]), the hidden item
 * flags ([hiddenItemFlagBase]), the names ([mapName], [overworldName]) and what needs the game's script bytecode
 * ([trigger], [scriptWarps], [triggerWarps]: nothing by default).
 *
 * Static data only: the game patches some matrices at load time and moves people around; read the RAM for the live
 * state. Everything is decoded lazily and cached (a world search reads the same areas again and again).
 */
abstract class Gen4WorldSource<H : Gen4MapHeader>(protected val rom: NdsRom) : WorldSource {

    /** Every map header of the game (index = zone id). */
    abstract val headers: List<H>

    /** Where the world files are in this game's ROM. */
    protected abstract val files: Gen4WorldFiles

    /** The [TileKind] of tile behaviour [behavior] (the codes differ a little between games). */
    protected abstract fun tileKind(behavior: Int, blocked: Boolean): TileKind

    /** What takes a warp on a tile of behaviour [behavior] ([WarpTrigger]: the codes differ a little between games). */
    protected abstract fun warpTrigger(behavior: Int): WarpTrigger

    /** Where the game moves the player arriving on a warp tile of [behavior] ([Warp.arrivalStep]); null by default. */
    protected open fun arrivalStep(behavior: Int): Direction? = null

    /** The field move that clears an overworld object of sprite [sprite] (small tree, cracked rock, boulder), or null. */
    protected abstract fun obstacle(sprite: Int): FieldMoveKind?

    /** `FLAG_OFFSET_HIDDEN_ITEMS`: the event flag of hidden item script [HIDDEN_ITEM_SCRIPTS].first. */
    protected abstract val hiddenItemFlagBase: Int

    /**
     * The name of the overworld's area (the region: "Sinnoh", "Johto and Kanto"): the area of matrix
     * [OVERWORLD_MATRIX] only. Any other matrix, even one holding several zones, is named like the zone its area was
     * built for (the first asked).
     */
    protected abstract val overworldName: String

    /** Reads the name of zone [zoneId] (the place from the ROM's text, the map's own name): see [mapName]. */
    protected abstract fun readMapName(zoneId: Int): MapName

    private val mapNames = SnapshotCache<Int, MapName>()

    /**
     * The name of zone [zoneId] ([dev.kotlinds.pokemonclient.PokemonGame.mapName]), decoded once: name lookups go
     * through every zone of the game.
     */
    fun mapName(zoneId: Int): MapName = mapNames.getOrPut(zoneId) { readMapName(zoneId) }

    /**
     * Coordinate trigger [index] of [zone]. The default knows nothing of its script ([Trigger.inert] false): games
     * that read their script bytecode tell the triggers that do nothing.
     */
    protected open fun trigger(zone: Int, index: Int, coord: Gen4ZoneEvents.CoordEvent): Trigger =
        Trigger(zone, index, coord.x, coord.z, coord.width, coord.height, coord.script, coord.variable, coord.value)

    /** Coordinate triggers of [zone] whose script moves the player within [zone] (needs the game's scripts): none by default. */
    protected open fun scriptWarps(zone: Int, events: Gen4ZoneEvents): List<ScriptWarp> = emptyList()

    /** The local ids of the people of [zone] its scripts move elsewhere ([PersonTemplate.scriptMoved]): none by default. */
    protected open fun movedPeople(zone: Int): Set<Int> = emptySet()

    /** Coordinate triggers of [zone] whose script warps the player to another zone (holes): none by default. */
    protected open fun triggerWarps(zone: Int, events: Gen4ZoneEvents): List<TriggerWarp> = emptyList()

    private val matrixFiles: List<ByteArray> by lazy { narc(files.mapMatrix) }
    private val landFiles: List<ByteArray> by lazy { narc(files.landData) }
    private val eventFiles: List<ByteArray> by lazy { narc(files.zoneEvents) }

    // Read from the console thread and the MCP server's: published snapshots ([SnapshotCache]), no lock.
    private val matrices = SnapshotCache<Int, Gen4MapMatrix>()
    private val lands = SnapshotCache<Int, DecodedLand>()
    private val events = SnapshotCache<Int, Gen4ZoneEvents>()
    private val areas = SnapshotCache<AreaKey, Area>()

    /** Cache key of an area: a shared matrix, or one zone of a per-zone matrix. */
    private data class AreaKey(val matrixId: Int, val zoneId: Int?)

    /** A land data member with its per-tile heights (decoded once). */
    private class DecodedLand(val data: Gen4LandData) {
        val heights: Array<List<Int>> by lazy { data.bdhc?.tileHeights() ?: Array(TILES_PER_BLOCK) { emptyList() } }
    }

    fun header(zoneId: Int): H? = headers.getOrNull(zoneId)

    override val zoneCount: Int get() = headers.size

    override fun flyAllowed(zoneId: Int): Boolean? = header(zoneId)?.flyAllowed

    override fun bikeAllowed(zoneId: Int): Boolean? = header(zoneId)?.bikeAllowed

    fun matrix(matrixId: Int): Gen4MapMatrix? =
        matrices.getOrPutNotNull(matrixId) { matrixFiles.getOrNull(matrixId)?.let { Gen4MapMatrix.parse(matrixId, it) } }

    fun landData(landId: Int): Gen4LandData? = land(landId)?.data

    /** A command of zone [zoneId]'s scripts sending a lift to warp [warp] of zone [zone], run by event script [scriptId] (1-based). */
    data class DynamicWarp(val scriptId: Int, val zone: Int, val warp: Int)

    /**
     * The lift commands of zone [zoneId]'s scripts ([DynamicWarp]); empty by default (a game whose scripts aren't read
     * here: no lift is known, [elevatorOf] says null).
     */
    protected open fun dynamicWarps(zoneId: Int): List<DynamicWarp> = emptyList()

    /** The lift of each zone asked ([elevatorOf]), none as an empty list (a cache of non-null values). */
    private val elevators = SnapshotCache<Int, List<Elevator>>()

    /**
     * The lift of zone [zoneId]: a room with warps to the dynamic destination ([DYNAMIC_ZONE]) and scripts sending it
     * to floors ([dynamicWarps]). Who runs those scripts tells how it is operated: a person ([ElevatorOperator.Attendant]),
     * a background event ([ElevatorOperator.Panel]), else the map itself on entering ([ElevatorOperator.Shuttle] between
     * two floors, [ElevatorOperator.EntryMenu] for more).
     */
    override fun elevatorOf(zoneId: Int): Elevator? = elevators.getOrPut(zoneId) { listOfNotNull(readElevator(zoneId)) }.firstOrNull()

    private fun readElevator(zoneId: Int): Elevator? {
        val events = events(zoneId) ?: return null
        val exits = events.warps.indices.filter { events.warps[it].header == DYNAMIC_ZONE }
        if (exits.isEmpty()) return null
        val commands = dynamicWarps(zoneId).filter { c -> c.zone != zoneId && events(c.zone)?.warps?.getOrNull(c.warp) != null }
        if (commands.isEmpty()) return null
        val scripts = commands.map { it.scriptId }.toSet()
        val person = events.objects.firstOrNull { it.script in scripts }
        val sign = events.bgs.indexOfFirst { it.script in scripts }
        val stops = commands.map { ElevatorStop(it.zone, it.warp) }.distinct()
        val operator = when {
            person != null -> ElevatorOperator.Attendant(person.id)
            sign >= 0 -> ElevatorOperator.Panel(sign)
            // Run by the map on entering: to the other floor of two (the Celadon Condominiums' right lift), else the
            // game asks which floor (its left lift, three floors: scr_seq_T07R0206_000's menu).
            stops.size == 2 -> ElevatorOperator.Shuttle
            else -> ElevatorOperator.EntryMenu
        }
        return Elevator(zoneId, exits, stops, operator)
    }

    /** The raw events of zone [zoneId] (member `eventsBank` of its header). */
    fun events(zoneId: Int): Gen4ZoneEvents? {
        val bank = header(zoneId)?.eventsBank ?: return null
        return events.getOrPutNotNull(bank) { eventFiles.getOrNull(bank)?.let { Gen4ZoneEvents.parse(it) } }
    }

    override fun areaOf(zoneId: Int): Area? {
        val header = header(zoneId) ?: return null
        val matrix = matrix(header.matrixId) ?: return null
        val key = AreaKey(matrix.id, if (matrix.zones != null) null else zoneId)
        return areas.getOrPut(key) { buildArea(matrix, zoneId) }
    }

    private fun land(landId: Int): DecodedLand? =
        lands.getOrPutNotNull(landId) { landFiles.getOrNull(landId)?.let { DecodedLand(Gen4LandData.parse(it)) } }

    protected fun narc(path: String): List<ByteArray> =
        NarcArchive.unpack(rom.files[path] ?: error("missing $path in the ROM"))

    private fun buildArea(matrix: Gen4MapMatrix, zoneId: Int): Area {
        val b = Gen4MapMatrix.BLOCK_TILES
        val width = matrix.width * b
        val height = matrix.height * b
        val tiles = arrayOfNulls<TileInfo>(width * height)
        val zones = IntArray(width * height) { -1 }
        // Most tiles are identical (same attribute, same flat height): share the instances.
        val interned = HashMap<TileInfo, TileInfo>()
        for (bz in 0 until matrix.height) for (bx in 0 until matrix.width) {
            val landId = matrix.landAt(bx, bz)
            if (landId == Gen4MapMatrix.NO_LAND) continue
            val land = land(landId) ?: continue
            // Overworld blocks of zone 0 (MAP_EVERYWHERE) are scenery around the routes: no zone.
            val blockZone = matrix.zoneAt(bx, bz)?.takeIf { it != EVERYWHERE } ?: if (matrix.zones == null) zoneId else -1
            for (lz in 0 until b) for (lx in 0 until b) {
                val attr = land.data.attribute(lx, lz)
                val blocked = attr and COLLISION_BIT != 0
                val info = TileInfo(blocked, tileKind(attr and BEHAVIOR_MASK, blocked), land.heights[lz * b + lx])
                val i = (bz * b + lz) * width + bx * b + lx
                tiles[i] = interned.getOrPut(info) { info }
                zones[i] = blockZone
            }
        }
        fun behaviorAt(x: Int, z: Int): Int? =
            if (x in 0 until width && z in 0 until height) {
                matrix.landAt(x / b, z / b).takeIf { it != Gen4MapMatrix.NO_LAND }
                    ?.let { land(it)?.data?.attribute(x % b, z % b)?.and(BEHAVIOR_MASK) }
            } else null
        val zoneIds = matrix.zones?.let { all -> (all.filter { it != EVERYWHERE }.toSortedSet() + zoneId).toList() } ?: listOf(zoneId)
        val warps = mutableListOf<Warp>()
        val signs = mutableListOf<Sign>()
        val people = mutableListOf<PersonTemplate>()
        val triggers = mutableListOf<Trigger>()
        val scriptWarps = mutableListOf<ScriptWarp>()
        val triggerWarps = mutableListOf<TriggerWarp>()
        for (zone in zoneIds) {
            val ev = events(zone) ?: continue
            val moved = movedPeople(zone)
            ev.warps.forEachIndexed { i, w ->
                // On a tile with several warps the game takes the first one (Field_GetWarpEventAtXYPos, pokeplatinum
                // map_header_data.c: the first warp event at those coordinates): the others are only arrivals of
                // scripts (the Ruins of Alph's warp:11..14 on the tiles of warp:8 / warp:9), nothing takes them.
                val shadowed = ev.warps.take(i).any { it.x == w.x && it.z == w.z }
                val behavior = behaviorAt(w.x, w.z)
                val trigger = if (shadowed) WarpTrigger.Never else behavior?.let(::warpTrigger) ?: WarpTrigger.Enter
                warps += Warp(zone, i, w.x, w.z, w.header, w.anchor, trigger, behavior?.let(::arrivalStep))
            }
            ev.bgs.forEachIndexed { i, bg -> signs += sign(zone, i, bg) }
            ev.objects.forEach { o ->
                people += PersonTemplate(
                    zone = zone,
                    id = o.id,
                    sprite = o.sprite,
                    x = o.x,
                    y = o.z,
                    facing = Gen4Structs.DIRECTIONS.getOrNull(o.facing),
                    sightRange = if (o.isTrainer) o.params[0] else 0,
                    script = o.script,
                    hiddenByFlag = o.eventFlag,
                    obstacle = obstacle(o.sprite),
                    // A trainer on a common trainer script: its flag tells whether it was beaten (Gen4Trainers).
                    trainerFlag = Gen4Trainers.trainerOfScript(o.script)?.let(Gen4Trainers::flagOf),
                    // A wander range: the person walks around that far from where the map places it.
                    wanders = o.xRange > 0 || o.zRange > 0,
                    looks = Gen4MovementTypes.looks(o.movement),
                    scriptMoved = o.id in moved,
                )
            }
            ev.coords.forEachIndexed { i, c -> triggers += trigger(zone, i, c) }
            scriptWarps += scriptWarps(zone, ev)
            triggerWarps += triggerWarps(zone, ev)
        }
        val name = if (matrix.zones != null && matrix.id == OVERWORLD_MATRIX) overworldName else mapName(zoneId).toString()
        val kind = if (matrix.zones != null) AreaKind.OVERWORLD else AreaKind.SINGLE_MAP
        return Area(matrix.id, name, 0, 0, width, height, tiles, warps, signs, people, triggers, zones, scriptWarps, triggerWarps, kind)
    }

    /**
     * A background event: a hidden item when its script is a hidden item script ([HIDDEN_ITEM_SCRIPTS]), whose flag
     * is `script - first + FLAG_OFFSET_HIDDEN_ITEMS` (Script_GetHiddenItemFlag, Platinum src/script_manager.c;
     * HiddenItemScriptNoToFlagId, HGSS src/fieldmap.c); a sign otherwise.
     */
    private fun sign(zone: Int, index: Int, bg: Gen4ZoneEvents.BgEvent): Sign =
        if (bg.script in HIDDEN_ITEM_SCRIPTS) {
            Sign(zone, index, bg.x, bg.z, bg.script, SignKind.HIDDEN_ITEM, bg.script - HIDDEN_ITEM_SCRIPTS.first + hiddenItemFlagBase)
        } else Sign(zone, index, bg.x, bg.z, bg.script)

    companion object {
        /** The zone of a warp leading to the dynamic destination a script set (a lift's way out): `0xFFF`. */
        const val DYNAMIC_ZONE = 4095

        /** Bit 15 of a terrain attribute: the tile cannot be entered (the same in every Gen 4 game). */
        const val COLLISION_BIT = 0x8000

        /** Bits 0-7 of a terrain attribute: the tile behaviour. */
        const val BEHAVIOR_MASK = 0xFF

        /** `MAP_EVERYWHERE`: the zone of the overworld blocks that belong to no route or town. */
        const val EVERYWHERE = 0

        /** The overworld matrix (matrix 0 in both Platinum and HeartGold / SoulSilver). */
        const val OVERWORLD_MATRIX = 0

        /**
         * The hidden item scripts: `SCRIPT_ID_OFFSET_HIDDEN_ITEMS` .. `SCRIPT_ID_OFFSET_SAFARI_GAME - 1` (Platinum
         * include/script_manager.h; `_std_hidden_item` .. `_std_safari - 1` in HGSS include/constants/std_script.h).
         */
        val HIDDEN_ITEM_SCRIPTS = 8000 until 8800

        private const val TILES_PER_BLOCK = Gen4MapMatrix.BLOCK_TILES * Gen4MapMatrix.BLOCK_TILES
    }
}
