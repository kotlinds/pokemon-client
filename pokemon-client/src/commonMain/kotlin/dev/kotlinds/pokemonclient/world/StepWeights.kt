package dev.kotlinds.pokemonclient.world

import dev.kotlinds.pokemonclient.state.MovementMode
import kotlin.math.roundToInt

/**
 * What the player brings to the game's wild encounter roll right now, besides the map: read from the state by the
 * action layer, turned into chances by the game ([WorldSource.encounterChance]).
 */
data class EncounterConditions(
    /**
     * How the player moves onto land encounter tiles (tall grass, cave floors) during the trip: [MovementMode.WALK],
     * [MovementMode.RUN] (running shoes held or switched on) or [MovementMode.BIKE]. Running and cycling roll more
     * often than walking: HGSS's first roll is 40 % running or cycling (+30 on the bike), 20 % walking
     * (src/field/encounter_check.c `FieldSystem_EncounterRateRoll`), so walks walk there by default ([travelMovement]).
     */
    val landMovement: MovementMode = MovementMode.WALK,
    /**
     * While a Repel works: the level of the first Pokémon of the party that can fight; wild Pokémon of a lower level
     * don't appear. Null without Repel.
     */
    val repelLevel: Int? = null,
    /** The item the first Pokémon of the party holds (some make encounters rarer), as the game's item id. */
    val leadItem: Int? = null,
    /**
     * How the player moves on the other land tiles (where nothing can appear): the pace of the trip, the unit of every
     * cost ([StepWeights.framesPerStep]). Faster than [landMovement] when the walk runs but walks onto encounter tiles
     * (`run_in_encounter_areas` off, the default): those moves then also cost the time walking loses
     * ([StepWeights.walkedTileCost]).
     */
    val travelMovement: MovementMode = landMovement,
)

/**
 * Soft costs of a route's moves, in steps, on top of their length and turns: the expected time a step loses to what it
 * may start. Always counted (unlike [RouteOptions.avoidTallGrass] / [RouteOptions.avoidTrainers], which ban), so of two
 * ways the one that is faster on average wins: a grass-free way a few tiles longer, a land way rather than surfing, a
 * way out of an unbeaten trainer's sight.
 *
 * - Wild encounters: every encounter check on a tile of zone `z` costs [landEncounter]`[z]` steps on land encounter
 *   tiles ([TileInfo.landEncounters]) and [surfEncounter]`[z]` on water with encounters
 *   ([TileKind.Water.wildEncounters]): the chance the game starts a battle there ([WorldSource.encounterChance]) times
 *   what an encounter costs ([ENCOUNTER_FRAMES]), in steps of the movement ([framesPerStep]). The game checks on every
 *   step onto such a tile, and on every turn in place on it (seen on the bench: turning on the spot in Route 1's grass
 *   starts battles), so a turn there costs the tile's weight too.
 * - Trainers: every tile in an unbeaten trainer's line of sight costs [trainerSight], a whole battle
 *   ([TRAINER_BATTLE_FRAMES]): walking into it starts one for sure.
 *
 * Costs are whole steps (the search is a Dijkstra over integer costs, all non-negative: exact, see [Pathfinder]).
 */
data class StepWeights(
    /** Extra cost of an encounter check on a land encounter tile of each zone (zone id → steps); absent: 0. */
    val landEncounter: Map<Int, Int> = emptyMap(),
    /** Extra cost of an encounter check surfing on encounter water of each zone (zone id → steps); absent: 0. */
    val surfEncounter: Map<Int, Int> = emptyMap(),
    /** Extra cost of a tile in an unbeaten trainer's line of sight (steps). */
    val trainerSight: Int = 0,
    /**
     * Zones whose land encounter tiles are walked onto although the trip runs elsewhere
     * ([EncounterConditions.landMovement] slower than [EncounterConditions.travelMovement]), where wild Pokémon can
     * appear now: the walker lets go of B onto those tiles ([walksOnto]), and each such move costs [walkedTileCost].
     */
    val walkedZones: Set<Int> = emptySet(),
    /** What walking a tile of [walkedZones] costs on top of running it, in steps of the trip's pace. */
    val walkedTileCost: Int = 0,
) {
    /** What one encounter check on [tile] (of zone [zone]) costs, in steps: 0 where nothing can appear. */
    fun encounter(tile: TileInfo, zone: Int?): Int {
        zone ?: return 0
        if (tile.landEncounters) return landEncounter[zone] ?: 0
        val kind = tile.kind
        return if (kind is TileKind.Water && kind.wildEncounters) surfEncounter[zone] ?: 0 else 0
    }

    /** True when the walk moves onto [tile] (of zone [zone]) walking rather than at its pace (see [walkedZones]). */
    fun walksOnto(tile: TileInfo, zone: Int?): Boolean = zone != null && tile.landEncounters && zone in walkedZones

    /** What a move ending on [tile] (of zone [zone]) costs on top of its length: its encounter check, and walking it. */
    fun moveOnto(tile: TileInfo, zone: Int?): Int = encounter(tile, zone) + if (walksOnto(tile, zone)) walkedTileCost else 0

    companion object {
        /** No soft cost: routes compared by length and turns only. */
        val NONE = StepWeights()

        /**
         * What a wild encounter costs, in frames, when it is fled: measured on the bench (HeartGold, DeSmuME, Route 1,
         * a level 54 lead against level 2-6 Pokémon, battle animations off): about 670 frames from the step that
         * starts it to the command menu (the field effect ~125, the intro texts ~540), then 336 frames from RUN to the
         * control back on the field. Winning it instead took 1,065 frames after the menu (one hit, two Exp. texts):
         * 1,730 in all. The fled one is the cost of a trip (an agent travelling flees, or would have lost the time
         * anyway); it leaves out the agent's own turns, which only make detours better.
         */
        const val ENCOUNTER_FRAMES = 1_000

        /**
         * What triggering an unbeaten trainer costs, in frames: measured on the bench (Route 1, School Kid Sherman, two
         * Pokémon each knocked out in one hit by a level 54 lead): about 3,950 frames from the step into his sight to
         * the control back (the walk up and his words ~270, the battle intro ~610, ~1,450 per Pokémon with the Exp.
         * texts, the defeat texts and the prize ~450). A closer fight only lasts longer: this is the least it costs.
         */
        const val TRAINER_BATTLE_FRAMES = 4_000

        /**
         * Frames one tile takes in [mode], measured on the bench: running and surfing 8 (a straight run of n tiles
         * takes 8n + 12, see [RouteOptions.TURN_COST]: that save runs all the time, [dev.kotlinds.pokemonclient.state.FieldState.autoRun]);
         * walking 16 (the same 8 tiles in Viridian: 140 frames walking, 76 running); the bike 6 (8 for the first
         * tiles of a run, 5 once at speed, see [RouteOptions.BIKE_TURN_COST]).
         */
        fun framesPerStep(mode: MovementMode): Int = when (mode) {
            MovementMode.WALK -> 16
            MovementMode.RUN, MovementMode.SURF -> 8
            MovementMode.BIKE -> 6
        }

        /**
         * The weights of a trip under [conditions]: each zone's encounter chances from [world] (the zones of
         * [zones], by default every zone the world knows), converted to steps of the trip's pace on land
         * ([EncounterConditions.travelMovement]) / on the water, and the trainer battle in steps of that pace. Where
         * land encounter tiles are walked although the trip runs (and something can appear there now: a Repel
         * strong enough leaves the grass free, run through; and walking makes it rarer: a game whose roll doesn't
         * depend on the pace, like Platinum, runs through), they are [walkedZones] and cost the time walking loses.
         */
        fun of(world: WorldSource?, conditions: EncounterConditions, zones: Iterable<Int>? = null): StepWeights {
            val land = HashMap<Int, Int>()
            val surf = HashMap<Int, Int>()
            val walked = HashSet<Int>()
            val pace = conditions.travelMovement
            val slower = framesPerStep(conditions.landMovement) > framesPerStep(pace)
            if (world != null) for (zone in zones ?: (0 until world.zoneCount)) {
                val chance = world.encounterChance(zone, water = false, conditions)
                // Walked only where the game rolls less often walking (HGSS; Platinum rolls the same on foot).
                if (chance > 0 && slower && chance < world.encounterChance(zone, water = false, conditions.copy(landMovement = pace))) walked += zone
                steps(chance, pace).takeIf { it > 0 }?.let { land[zone] = it }
                steps(world.encounterChance(zone, water = true, conditions), MovementMode.SURF).takeIf { it > 0 }?.let { surf[zone] = it }
            }
            val walkedCost = if (slower) ((framesPerStep(conditions.landMovement) - framesPerStep(pace)).toDouble() / framesPerStep(pace)).roundToInt() else 0
            return StepWeights(land, surf, trainerSight = TRAINER_BATTLE_FRAMES / framesPerStep(pace), walkedZones = walked, walkedTileCost = walkedCost)
        }

        /** The expected cost of a check with [chance] of an encounter, in steps of [mode] (rounded). */
        fun steps(chance: Double, mode: MovementMode): Int = (chance * ENCOUNTER_FRAMES / framesPerStep(mode)).roundToInt()
    }
}
