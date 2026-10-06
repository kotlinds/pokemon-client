package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.NdsRom
import dev.kotlinds.pokemonclient.world.SignKind
import dev.kotlinds.pokemonclient.world.WorldSource
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.PokemonGame

/**
 * [PokemonGame] for HeartGold / SoulSilver: maps the detailed [HgssReader] snapshot to the common [GameState]
 * ([HgssStateMapper]), enriched with what the ROM's maps tell (puzzles, triggers, examinables).
 *
 * One class serves every HG/SS build; only the [version]'s address table differs.
 */
class HgssGame(private val version: HgssVersion, rom: NdsRom? = null) : dev.kotlinds.pokemonclient.games.gen4.Gen4Game(version.gSystem) {

    /** Built from the ROM when one is given (maps are decoded lazily, area by area). */
    override val world: HgssWorldSource? = rom?.let { HgssWorldSource(it, version) }?.also { HgssData.useWorld(it) }

    /**
     * The game data read from the ROM, when one is given. Also installed in [HgssData] so the decoders' name and
     * data lookups read the ROM instead of the bundled tables.
     */
    override val data: HgssGameData? = rom?.let { HgssGameData(it, version) }?.also { HgssData.useGameData(it) }

    override val name = version.displayName

    override fun scriptVariable(memory: Memory, id: Int): Int? = HgssReader(memory, version).variable(id)

    override fun scriptFlag(memory: Memory, id: Int): Boolean? = HgssReader(memory, version).flag(id)

    /**
     * The place shown in game and the map's own name: "New Bark Town (New Bark Player House 2F)", read from this game's
     * ROM ([HgssWorldSource.mapName]); from the bundled decomp tables without a ROM ([HgssData.bundledMapName]).
     */
    override fun mapName(id: Int): dev.kotlinds.pokemonclient.state.MapName = world?.mapName(id) ?: HgssData.bundledMapName(id)

    override val fieldMoveBadges = HgssFieldMoves.BADGES

    /**
     * The registered item buttons of the field's bottom-screen menu (overlay 27, hitbox table `ov27_0225CF68`, entries
     * 8 and 9: x 203-255, y 8-39 and 46-77), which set `FieldSystem.lastTouchMenuInput` to 9 / 10: the first / second
     * registered item (src/field/field_control.c).
     */
    override fun registeredItemTouch(slot: Int): dev.kotlinds.pokemonclient.console.TouchPoint? = when (slot) {
        0 -> dev.kotlinds.pokemonclient.console.TouchPoint(229, 23)
        1 -> dev.kotlinds.pokemonclient.console.TouchPoint(229, 61)
        else -> null
    }

    private val mapper = HgssStateMapper()

    /** PC boxes, options, trainers, shop catalogs and Fly permission ([HgssServices]). */
    private val services = HgssServices()

    override fun state(memory: Memory): GameState = withFieldMoves(read(memory))

    /** The state read from RAM, enriched with the ROM's maps. */
    private fun read(memory: Memory): GameState {
        val reader = HgssReader(memory, version)
        val state = reader.read() ?: HgssState(frame = 0, mode = GameMode.UNKNOWN, modeDetail = "unreadable RAM")
        val hgssMemory = HgssMemory(memory, version)
        val mapped = services.enrich(mapper.map(state, hgssMemory), state, hgssMemory)
        val field = mapped.field ?: return mapped
        // The save's event flags: which people of the other maps are there (an exit whose arrival someone blocks).
        val flagged = reader.eventFlags()?.let { mapped.copy(eventFlags = it) } ?: mapped
        return withMapState(flagged, field, reader, state)
    }

    /** [mapped] with what the ROM's maps add to its [field]: the puzzle, hidden items picked up, triggers, examinables. */
    private fun withMapState(mapped: GameState, field: dev.kotlinds.pokemonclient.state.FieldState, reader: HgssReader, state: HgssState): GameState {
        val area = world?.areaOf(field.mapId)
        val people = field.objects.mapNotNull { o -> o.id.removePrefix("person:").toIntOrNull()?.let { HgssIlexFarfetchd.ObjectAt(it, o.x, o.y, o.facing) } }
        val puzzle = HgssPuzzles.read(field.mapId, HgssPuzzles.reads(reader), area, people)
        val pickedUp = area?.signs.orEmpty()
            .filter { it.zone == field.mapId && it.kind == SignKind.HIDDEN_ITEM && it.flag?.let(reader::flag) == true }
            .map { "hidden_item:${it.id}" }.toSet()
        // Placeholders and armed triggers whose script would end silently now (Trigger.quietWhen) start nothing: not active.
        val triggers = area?.sceneTriggerTiles(field.mapId, reader::variable, reader::flag).orEmpty()
        val examinables = HgssExaminables.of(state.surroundings?.objects.orEmpty(), field.mapId, world ?: HgssData.world, reader::flag)
        if (puzzle == null && pickedUp.isEmpty() && triggers.isEmpty() && examinables.isEmpty()) return mapped
        return mapped.copy(field = field.copy(puzzle = puzzle, pickedUp = pickedUp, activeTriggers = triggers, examinables = examinables))
    }
}
