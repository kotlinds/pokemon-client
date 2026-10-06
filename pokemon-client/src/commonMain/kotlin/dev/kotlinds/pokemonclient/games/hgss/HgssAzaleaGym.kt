package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.games.gen4.Gen4RomBytes
import dev.kotlinds.pokemonclient.state.PuzzleTile
import dev.kotlinds.pokemonclient.world.MechanismPress
import dev.kotlinds.pokemonclient.world.MechanismRide
import dev.kotlinds.pokemonclient.world.PuzzleMechanics
import dev.kotlinds.pokemonclient.world.PuzzleMechanism

/**
 * The Azalea Gym's Spinarak carts (`MAP_AZALEA_GYM`, T23GYM0102), with the game's rules, for the route planner
 * ([dev.kotlinds.pokemonclient.world.MechanismPlanner]: `go_to` rides the carts and flips the levers by itself).
 *
 * State: `Gymmick.azalea {u8 spiders[4]; int switches}` (include/gymmick.h, src/gymmick_init.c `InitAzaleaGym`): the
 * station each of the 4 carts waits at, and 2 lever bits. Stepping on a station's trigger while a cart waits there rides
 * it to the arrival station of the route the lever bits choose (`BeginAzaleaGymSpinarakRide` / `ov04_02254724`); the
 * cart stays there. A lever (`FlipAzaleaGymSwitch`, silent) toggles its bit. Routes: asm/overlay_04.s `ov04_022575A4`
 * (per station, per lever state: count, arrival station, path), station tiles `ov04_022575D4`.
 */
class HgssAzaleaGym(override val state: Carts) : PuzzleMechanics<HgssAzaleaGym.Carts> {

    /**
     * Where the carts wait ([stations], one entry per cart, sorted: carts are alike; a station may hold two) and the
     * lever bits ([switches], 0..3).
     */
    data class Carts(val stations: List<Int>, val switches: Int)

    override val mechanism = PuzzleMechanism.CART_RIDE

    override val triggerTiles: Set<Pair<Int, Int>> = STATIONS.indices.map { trigger(it).let { t -> t.x to t.y } }.toSet()

    override fun ride(state: Carts, x: Int, y: Int): MechanismRide<Carts>? {
        val station = STATIONS.indices.firstOrNull { trigger(it).let { t -> t.x == x && t.y == y } } ?: return null
        if (station !in state.stations) return null
        val to = route(station, state.switches) ?: return null
        val landing = landing(station, to)
        return MechanismRide(state.copy(stations = (state.stations - station + to).sorted()), landing.x, landing.y, moved = true)
    }

    /** Every lever, pressed from any free side (its bg event answers A from every direction: `dir` 4). */
    override fun presses(state: Carts): List<MechanismPress<Carts>> = LEVERS.flatMapIndexed { bit, levers ->
        levers.flatMap { (sign, tile) ->
            Direction.entries.map { d ->
                MechanismPress(tile.x - d.dx, tile.y - d.dy, d, "sign:$sign", state.copy(switches = state.switches xor (1 shl bit)))
            }
        }
    }

    companion object {
        /** `MAP_AZALEA_GYM` (T23GYM0102). */
        const val MAP = 180

        /** `GYMMICK_AZALEA` (include/gymmick.h). */
        internal const val GYMMICK_TYPE = 5

        /** Station tiles (`ov04_022575D4`): where the carts stop. */
        internal val STATIONS = listOf(
            PuzzleTile(3, 31), PuzzleTile(9, 31), PuzzleTile(15, 31),
            PuzzleTile(3, 24), PuzzleTile(9, 24), PuzzleTile(15, 24),
            PuzzleTile(3, 16), PuzzleTile(9, 16), PuzzleTile(15, 16),
            PuzzleTile(3, 9), PuzzleTile(9, 9), PuzzleTile(15, 9),
        )

        /**
         * Stations whose ride runs its path backwards (the jump table of `BeginAzaleaGymSpinarakRide`): their trigger
         * is the station tile itself and the player gets off two tiles south of the arrival station (placed one tile
         * south, then a step south). The others are entered from the tile south of the station and the player gets
         * off one tile north of the arrival station (placed on it, then a step north).
         */
        private val REVERSED = setOf(3, 4, 5, 9, 10, 11)

        /**
         * Arrival station per station and lever state (`switches` 0..3), null when the cart doesn't go anywhere
         * (`ov04_022575A4`: the `{count, arrival, path}` entries; count 0 = no route).
         */
        private val ROUTES: List<List<Int?>> = listOf(
            listOf(4, 4, 4, 4), listOf(5, 5, 5, 5), listOf(3, 3, 3, 3),
            listOf(2, 2, 2, 2), listOf(0, 0, 0, 0), listOf(1, 1, 1, 1),
            listOf(null, 9, null, 10), listOf(9, 11, 10, 11), listOf(null, null, null, null),
            listOf(7, 6, null, null), listOf(null, null, 7, 6), listOf(null, 7, null, 7),
        )

        /** Levers per bit: bit 0 = the 3 levers of bg events 0..2, bit 1 = the lever of bg event 3 (sign id, tile). */
        internal val LEVERS = listOf(
            listOf(0 to PuzzleTile(4, 7), 1 to PuzzleTile(11, 8), 2 to PuzzleTile(11, 18)),
            listOf(3 to PuzzleTile(2, 18)),
        )

        /** The trigger tile of [station] (the zone's coordinate events 0..11). */
        fun trigger(station: Int): PuzzleTile = STATIONS[station].let { if (station in REVERSED) it else PuzzleTile(it.x, it.y + 1) }

        /** Where the player stands after riding from [from] to [to]. */
        fun landing(from: Int, to: Int): PuzzleTile = STATIONS[to].let { if (from in REVERSED) PuzzleTile(it.x, it.y + 2) else PuzzleTile(it.x, it.y - 1) }

        /** The arrival station of a ride from [station] with lever bits [switches], or null. */
        fun route(station: Int, switches: Int): Int? = ROUTES.getOrNull(station)?.getOrNull(switches and 3)

        /** The carts in the save's `Gymmick` slot ([gymmick]: u32 type, then the union), or null when it isn't the Azalea Gym's. */
        fun carts(gymmick: ByteArray): Carts? {
            if (gymmick.size < 12 || Gen4RomBytes.s32(gymmick, 0) != GYMMICK_TYPE) return null
            val stations = (0 until 4).map { gymmick[4 + it].toInt() and 0xFF }.filter { it in STATIONS.indices }
            // Two carts may wait at one station: each one counts (riding one leaves the other there).
            return Carts(stations.sorted(), Gen4RomBytes.s32(gymmick, 8) and 3)
        }
    }
}
