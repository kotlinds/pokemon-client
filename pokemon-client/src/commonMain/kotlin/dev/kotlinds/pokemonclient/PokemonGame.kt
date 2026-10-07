package dev.kotlinds.pokemonclient

import dev.kotlinds.pokemonclient.runtime.ActionScope
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.runtime.InputProbe

/**
 * A Pokémon game we know how to "see" through RAM and play through the console port.
 *
 * Part of the `pokemon-client` library (dev.kotlinds.pokemonclient): a common interface to read Pokémon
 * games (position, dialogue, team, battle...) and drive them, with one implementation per game.
 *
 * The AI doesn't look at the screen: it reads text/JSON. Each supported game therefore provides a reader turning raw
 * RAM into the common typed [GameState] (the same model for every game; the views turn it into what agents read).
 * Supporting another game (SoulSilver, Platinum, Emerald...) means implementing this interface.
 */
interface PokemonGame {
    /** Human-readable name, e.g. "Pokémon HeartGold (USA)". */
    val name: String

    /** Reads the current situation into the common typed model. */
    fun state(memory: Memory): GameState

    /** The static maps of the game (tiles, warps, people...) read from the ROM, or null when not available. */
    val world: dev.kotlinds.pokemonclient.world.WorldSource? get() = null

    /** The game's data (species, moves, items, types...) read from the ROM, or null when not available. */
    val data: dev.kotlinds.pokemonclient.data.GameData? get() = null

    /**
     * The name of map (zone) [id]: the one name the state, `go_to`, the map view and the errors use, in every game
     * ([MapName]: the place shown in game and the map's own name). Never null: what this game doesn't know stays
     * null in it and it shows `map:<id>`.
     */
    fun mapName(id: Int): MapName = MapName(id)

    /**
     * The value of the game's script variable [id] (what map triggers and events check), or null when this game
     * can't tell. Read only.
     */
    fun scriptVariable(memory: Memory, id: Int): Int? = null

    /**
     * The game's event flag [id] (what scripts branch on: a speech already heard, an item taken...), or null when this
     * game can't tell. Read only.
     */
    fun scriptFlag(memory: Memory, id: Int): Boolean? = null

    /** Reads which buttons the game has registered, so presses can check themselves (see [ActionScope.tap]). */
    val inputProbe: InputProbe

    /**
     * What field move [move] needs outside battle in this game (the move, the badge), or null when the game doesn't
     * have it. Routes use the field moves whose rule the party meets (Surf, Waterfall, Cut...).
     */
    fun fieldMoveRule(move: dev.kotlinds.pokemonclient.world.FieldMoveKind): dev.kotlinds.pokemonclient.world.FieldMoveRule? = null

    /**
     * Where to touch the bottom screen in the field to use the registered item of [slot] (0 = the first one, also on
     * Y), or null when this game has no such button.
     */
    fun registeredItemTouch(slot: Int): dev.kotlinds.pokemonclient.console.TouchPoint? = null

    /**
     * The movement puzzles of other maps whose state the save keeps (event flags), by zone, as they stand now: what a
     * way through those maps may need (the Ice Path boulders that must fall to the floor below). Only what the global
     * state tells (a map's temporary variables are unknown away from it); empty when the game has none or can't tell.
     * The current map's own puzzle is [dev.kotlinds.pokemonclient.state.FieldState.puzzle].
     */
    fun savedPuzzles(memory: Memory): Map<Int, dev.kotlinds.pokemonclient.state.PuzzleState> = emptyMap()

    /**
     * The direction the player must face to use a PC (they stand on the tile on the other side), or null when the
     * game answers from any side or doesn't say.
     */
    val pcFacing: dev.kotlinds.pokemonclient.Direction? get() = null

    /** The item id of the bicycle (a key item ridden from the field), or null when this game has none. */
    val bicycleItem: Int? get() = null

    /**
     * The item ids of the Repels, the longest-lasting first (what a walk uses again with `on_repel_end: reapply`), or
     * empty when this game has none known.
     */
    val repelItems: List<Int> get() = emptyList()
}
