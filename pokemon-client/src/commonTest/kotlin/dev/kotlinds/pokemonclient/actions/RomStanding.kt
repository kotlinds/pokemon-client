package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.games.hgss.HgssGame
import dev.kotlinds.pokemonclient.games.hgss.HgssVersion
import dev.kotlinds.pokemonclient.games.hgss.HgssWorldRom
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldObject
import dev.kotlinds.pokemonclient.state.FieldObjectKind
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.GameState
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.ObstacleKind
import dev.kotlinds.pokemonclient.state.PuzzleState
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.world.FieldMoveAccess
import dev.kotlinds.pokemonclient.world.FieldMoveKind

/**
 * The player standing still on zone [zone] of the real HeartGold maps (skipped without `POKEMON_ROM`), the map's
 * Strength boulders where it places them, for the answers `go_to` gives before moving (refusals, hints). Map names
 * from [HgssGame]; [height]: the player's ([FieldState.height]); [saved]: the puzzles of other maps the save keeps ([dev.kotlinds.pokemonclient.PokemonGame.savedPuzzles]); [fieldMoves]: what the party can do with
 * each field move ([GameState.fieldMoves]; null: no party, nobody knows any).
 */
internal class RomStanding(
    val zone: Int,
    x: Int,
    y: Int,
    private val height: Int = 0,
    private val saved: Map<Int, PuzzleState> = emptyMap(),
    private val fieldMoves: Map<FieldMoveKind, FieldMoveAccess>? = null,
) : GridGame(x, y) {
    private val hgss = HgssGame(HgssVersion.HEARTGOLD_US)
    override val name = "HeartGold maps"
    override val world = HgssWorldRom.require()
    override fun mapName(id: Int) = hgss.mapName(id)
    override fun scriptVariable(memory: Memory, id: Int) = 0
    override fun savedPuzzles(memory: Memory) = saved
    override fun step(direction: Direction) = Unit
    override fun state(memory: Memory): GameState {
        val boulders = world.areaOf(zone)!!.people.filter { it.zone == zone && it.obstacle == FieldMoveKind.STRENGTH }.map {
            FieldObject("person:${it.id}", "boulder", FieldObjectKind.OBSTACLE, it.x, it.y, it.facing, obstacle = ObstacleKind.BOULDER)
        }
        val field = FieldState(zone, hgss.mapName(zone), x, y, height, facing, MovementMode.WALK, moving = false, objects = boulders, puzzle = saved[zone])
        return GameState(0, Screen.Overworld(null, Awaiting.INPUT), null, emptyList(), null, null, field, fieldMoves = fieldMoves)
    }
}
