package dev.kotlinds.pokemonclient.actions

import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.MovementMode

/**
 * How a walk moves on land: the one source of the route weights ([MovePlans.stepWeights]) and of the B button the
 * walker holds ([MovePlans.runOnto]).
 *
 * - [land]: the pace on the tiles where nothing can appear: the bike when riding (or [MoveOptions.bike] where cycling
 *   is allowed); running with [MoveOptions.run] (the default) when the player owns the running shoes
 *   ([FieldState.runningShoes]), or always with the running shoes switched on ([FieldState.autoRun]: HGSS's touch screen
 *   button makes the game hold B, `FieldInput_Update`); walking otherwise.
 * - [encounterTiles]: the movement onto the tiles where wild Pokémon can appear (tall grass, cave floors): walking
 *   while running elsewhere, since running doubles the encounter roll (HGSS src/field/encounter_check.c
 *   `FieldSystem_EncounterRateRoll`: 40 running or cycling, 20 walking), unless [MoveOptions.runInEncounterAreas], or
 *   the running shoes switched on (the game runs whatever is pressed: walking there would need them switched off).
 *   Only where walking makes the roll rarer ([dev.kotlinds.pokemonclient.world.StepWeights.walkedZones]: Platinum rolls
 *   the same on foot, its walks run through), and where something can appear at that point of the walk (a Repel at
 *   work keeps the weaker wild Pokémon away for its steps left: run through until it wears off,
 *   [dev.kotlinds.pokemonclient.world.StepWeights.after]).
 *   The bike rides on (getting off for a few tiles costs more than it saves).
 */
internal data class FootPace(val land: MovementMode, val encounterTiles: MovementMode) {
    companion object {
        fun of(field: FieldState?, options: MoveOptions): FootPace {
            if (field?.movement == MovementMode.BIKE || (options.bike && field?.bikeAllowed != false)) return FootPace(MovementMode.BIKE, MovementMode.BIKE)
            val forced = field?.autoRun == true
            val runs = forced || (options.run && field?.runningShoes != false)
            if (!runs) return FootPace(MovementMode.WALK, MovementMode.WALK)
            return FootPace(MovementMode.RUN, if (forced || options.runInEncounterAreas) MovementMode.RUN else MovementMode.WALK)
        }
    }
}
