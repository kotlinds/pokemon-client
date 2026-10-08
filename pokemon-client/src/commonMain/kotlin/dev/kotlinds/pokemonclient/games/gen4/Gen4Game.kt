package dev.kotlinds.pokemonclient.games.gen4

import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.runtime.InputProbe
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.FieldMoveSupport
import dev.kotlinds.pokemonclient.world.FieldMoves
import dev.kotlinds.pokemonclient.state.GameState

/**
 * Base of the Generation 4 games (Diamond / Pearl / Platinum, HeartGold / SoulSilver): what their shared engine gives
 * every one of them, with the per-ROM addresses injected. The generic contract stays [PokemonGame]; the games
 * themselves ([dev.kotlinds.pokemonclient.games.hgss.HgssGame], [dev.kotlinds.pokemonclient.games.platinum.PlatinumGame]) decode
 * their own screens.
 *
 * @param gSystem the address of `gSystem` in the running ROM.
 */
abstract class Gen4Game(gSystem: Long) : PokemonGame {

    /** `gSystem.heldKeysRaw`, same structure in every Gen 4 game ([Gen4InputProbe]). */
    override val inputProbe: InputProbe = Gen4InputProbe(gSystem)

    /** Which badge allows which field move in this game (the moves and the check are the engine's: [Gen4FieldMoves]). */
    protected abstract val fieldMoveBadges: Map<FieldMoveKind, Gen4FieldMoves.Badge?>

    /** The rule of [move] from [fieldMoveBadges]; a move missing from them is one this game doesn't have. */
    override fun fieldMoveRule(move: FieldMoveKind): FieldMoveSupport = Gen4FieldMoves.rule(move, fieldMoveBadges)

    /**
     * Whether this game's party is read and its party menu decoded (where field moves are used from): when not, every
     * field move is [dev.kotlinds.pokemonclient.world.FieldMoveAccess.NotSupported] (the same actions, said
     * unsupported, never failing blindly on a screen not decoded).
     */
    protected open val partyRead: Boolean = true

    /** [state] with its [GameState.fieldMoves] (the access to this game's field moves), the last step of [state]. */
    protected fun withFieldMoves(state: GameState): GameState = state.copy(fieldMoves = FieldMoves.access(state, partyRead, ::fieldMoveRule))

    /** Every Gen 4 game gives its own recipes, built on the Gen 4 ones ([Gen4Recipes]). */
    abstract override val recipes: Gen4Recipes

    /** ITEM_BICYCLE: the same item id (450) in every Gen 4 game. */
    override val bicycleItem: Int? get() = ITEM_BICYCLE

    /** A PC only answers when faced from the south (src/field/field_control.c: `IsPC && facingDirection == DIR_NORTH`). */
    override val pcFacing: dev.kotlinds.pokemonclient.Direction? get() = dev.kotlinds.pokemonclient.Direction.NORTH

    /** ITEM_MAX_REPEL (77), ITEM_SUPER_REPEL (76), ITEM_REPEL (79): the same ids in every Gen 4 game. */
    override val repelItems: List<Int> get() = listOf(77, 76, 79)

    private companion object {
        const val ITEM_BICYCLE = 450
    }
}
