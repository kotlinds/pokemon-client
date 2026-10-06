package dev.kotlinds.pokemonclient.games.gen4

import dev.kotlinds.pokemonclient.PokemonGame
import dev.kotlinds.pokemonclient.runtime.InputProbe
import dev.kotlinds.pokemonclient.world.FieldMoveKind
import dev.kotlinds.pokemonclient.world.FieldMoveRule
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
    protected abstract val fieldMoveBadges: Map<FieldMoveKind, Gen4FieldMoves.Badge>

    override fun fieldMoveRule(move: FieldMoveKind): FieldMoveRule? = Gen4FieldMoves.rule(move, fieldMoveBadges)

    /** [state] with its [GameState.fieldMoves] (the access to this game's field moves), the last step of [state]. */
    protected fun withFieldMoves(state: GameState): GameState = state.copy(fieldMoves = FieldMoves.access(state, ::fieldMoveRule))

    /** ITEM_BICYCLE: the same item id (450) in every Gen 4 game. */
    override val bicycleItem: Int? get() = ITEM_BICYCLE

    private companion object {
        const val ITEM_BICYCLE = 450
    }
}
