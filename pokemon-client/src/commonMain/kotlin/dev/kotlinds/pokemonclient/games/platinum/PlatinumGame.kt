package dev.kotlinds.pokemonclient.games.platinum

import dev.kotlinds.NdsRom
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.games.gen4.Gen4Game
import dev.kotlinds.pokemonclient.games.gen4.Gen4SaveData
import dev.kotlinds.pokemonclient.games.gen4.Gen4SaveLayout
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.IntroStage
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.state.Screen

/**
 * [dev.kotlinds.pokemonclient.PokemonGame] for Pokémon Platinum (early support): the intro (opening movie, title
 * screen, main menu, Professor Rowan's new game with its menus, the naming keyboard and the TV programme), the
 * player's position, walking and the scripts' messages in the field, the map of the current zone from the ROM, the
 * save's event flags and Pokédex. Party, bag, battles, menus of the field are not decoded yet ([Screen.Unknown]).
 *
 * Built on the Gen 4 layer ([Gen4Game]: input probe, safe reads, text, naming keyboard, map formats); only the
 * addresses ([PlatinumVersion]) and the screens that differ from HGSS are Platinum's own.
 */
class PlatinumGame(private val version: PlatinumVersion, rom: NdsRom? = null) : Gen4Game(version.gSystem) {

    override val name: String = version.displayName

    override val fieldMoveBadges = PlatinumFieldMoves.BADGES

    override val recipes: dev.kotlinds.pokemonclient.games.gen4.Gen4Recipes get() = PlatinumRecipes

    /** The party and its menu aren't decoded yet: field moves (Fly, Surf...) are said unsupported, never tried. */
    override val partyRead: Boolean = false

    /** The maps of the ROM, and its text banks (location names, intro text blocks). */
    override val world: PlatinumWorldSource? = rom?.let { PlatinumWorldSource(it, version) }

    private val text: PlatinumText? = world?.text

    /**
     * The place (from the ROM's text, when a ROM is given) and the map's own name ([PlatinumMapNames]): "Twinleaf Town
     * (Twinleaf Town Player House 2F)".
     */
    override fun mapName(id: Int): MapName = world?.mapName(id) ?: MapName(id, map = PlatinumMapNames.of(id))

    /** Save script variable [id] (the Gen 4 `VarsFlags`, [Gen4SaveData.variable]), null when unreadable. */
    override fun scriptVariable(memory: Memory, id: Int): Int? = save(memory)?.variable(id)

    /** Event flag [id] (the Gen 4 `VarsFlags`, [Gen4SaveData.flag]), null when unreadable. */
    override fun scriptFlag(memory: Memory, id: Int): Boolean? = save(memory)?.flag(id)

    /** The save in RAM (all game long once loaded), or null before. */
    private fun save(memory: Memory): Gen4SaveData? {
        val mem = PlatinumMemory(memory, version)
        return mem.ptr(version.saveDataPtr)?.let { Gen4SaveData(mem, it, SAVE_LAYOUT) }
    }

    override fun state(memory: Memory): GameState {
        val mem = PlatinumMemory(memory, version)
        val top = PlatinumTopApp.read(mem)
        val fs = PlatinumField.fieldSystem(mem, top)
        val field = fs?.let { PlatinumField.position(mem, it, ::mapName) }
        val screen = PlatinumIntroScreens.decode(mem, top, text)
            ?: fs?.let { PlatinumField.screen(mem, it, field) }
            ?: when (top.app) {
                PlatinumApp.NONE, PlatinumApp.LOADING -> Screen.Intro(IntroStage.LOADING, Awaiting.ANIMATION)
                else -> Screen.Unknown("application not decoded yet for Platinum (main 0x${top.manager?.let { mem.fn(it + 4) }?.toString(16)})", Awaiting.INPUT)
            }
        // The save (in RAM all game long): the event flags (who stands where on other maps) and the Pokédex.
        val save = save(memory)
        return withFieldMoves(GameState(
            frame = memory.read32(version.gSystem + dev.kotlinds.pokemonclient.games.gen4.Gen4Structs.SYS_VBLANK_COUNTER),
            screen = screen,
            player = null,
            party = emptyList(),
            bag = null,
            battle = null,
            field = field?.copy(
                flyAllowed = world?.flyAllowed(field.mapId),
                bikeAllowed = world?.bikeAllowed(field.mapId),
                // The armed scene triggers, by the one rule of every reader (Area.sceneTriggerTiles): the walks and the
                // view agree. Platinum's scripts aren't decoded: no trigger is known to be a placeholder or quiet.
                activeTriggers = save?.let { s -> world?.areaOf(field.mapId)?.sceneTriggerTiles(field.mapId, s::variable, s::flag) }.orEmpty(),
            ),
            pokedex = save?.pokedex(),
            eventFlags = save?.eventFlags(),
        ))
    }

    companion object {
        /**
         * Platinum's `SaveData` (include/savedata.h) for the Gen 4 save reader: 5 words, then `body.data` (0x20000
         * bytes) and the counters, then `pageInfo` (checked on the bench: page 4 `VarsFlags` at 0x20024 + 4 × 16).
         * `VarsFlags` holds `NUM_VARS` = 288 u16 (VARS_END - VARS_START) before its flags; the Pokédex is page 7.
         */
        val SAVE_LAYOUT = Gen4SaveLayout(dataOffset = 0x14L, tableHeaders = 0x20024L, varsFlagsTable = 4, flagsOffset = 2L * 288, pokedexTable = 7)
    }
}
