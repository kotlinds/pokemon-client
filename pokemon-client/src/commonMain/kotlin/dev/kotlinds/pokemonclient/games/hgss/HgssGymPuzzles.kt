package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.games.gen4.Gen4RomBytes
import dev.kotlinds.pokemonclient.state.PuzzleBarrier
import dev.kotlinds.pokemonclient.state.PuzzleIceBlock
import dev.kotlinds.pokemonclient.state.PuzzleStepAside
import dev.kotlinds.pokemonclient.state.StepAsideMove
import dev.kotlinds.pokemonclient.state.PuzzleIndicator
import dev.kotlinds.pokemonclient.state.PuzzleKind
import dev.kotlinds.pokemonclient.state.PuzzleState
import dev.kotlinds.pokemonclient.state.PuzzleSurface
import dev.kotlinds.pokemonclient.state.PuzzleSwitch
import dev.kotlinds.pokemonclient.state.PuzzleTeleport
import dev.kotlinds.pokemonclient.state.PuzzleTile
import dev.kotlinds.pokemonclient.state.TeleportKind
import dev.kotlinds.pokemonclient.world.Area

/**
 * The gym mechanisms of HeartGold / SoulSilver read from the save's `Gymmick` slot (include/gymmick.h: u32 type, then
 * the union; set up by each gym's init script on entry, cleared on leaving), with the rules from the decomp:
 *
 * - **Violet Gym lift** (`MAP_VIOLET_GYM`, `GYMMICK_VIOLET` {BOOL liftState}): a 3×3 platform (14..16, 19..21) whose
 *   floor is at the lift's height. Stepping on its center (15,20), the coordinate trigger of `scr_seq_T22GYM0101_004`
 *   (`VioletGymElevator`, always armed), moves it to the other floor with the player on it: up when the player stands
 *   at height 2.0, down otherwise (`ov04_02253ED4`); heights 2.0 / 31.0 (fx32 `2 << 16` / `0x1F << 16`, the platform
 *   region set up by `ov04_02253E20` with `ov01_021FB3E4(0, 14, 19, 3, ...)`). The upper floor (Falkner) is reached
 *   only this way.
 * - **Ecruteak Gym** (`MAP_ECRUTEAK_GYM`, `GYMMICK_ECRUTEAK` {u8 candles[4]}): the floor is invisible; 15 coordinate
 *   triggers are pits that warp the player back to the entrance (listed as pads by [HgssPuzzles]). Each Medium carries
 *   a candle (`sMortyGymTrainerObjectIds`: objects 2..5), blown out once she is beaten (`ScrCmd_317` after the battle
 *   in scr_seq_0953, `ov04_02254E50` sets `candles[i] = 1`); the init script lights them all again on each entry.
 * - **Cianwood Gym** (`MAP_CIANWOOD_GYM`, `GYMMICK_CIANWOOD` {BOOL winch}): Chuck trains under a waterfall and won't
 *   battle (`VAR_TEMP_x4000` == 0) until the winch (bg event 0, yes/no) is turned: `CianwoodGymTurnWinch` sets the
 *   winch and the var, the script sets `FLAG_SYS_CIANWOOD_WATERFALL_DISABLE`; the init script clears the flag on each
 *   entry. The waterfall blocks no tile (Chuck's niche at (13,10) is walled by the map itself).
 * - **Vermilion Gym** (`MAP_VERMILION_GYM`, `GYMMICK_VERMILION` {u8 switches[2]; u8 gates[2]}): 15 trash cans (bg
 *   events 0..14, a 5×3 grid); `switches[0]` hides the first switch (opens gate 0: the stop objects 3..5 on row 10),
 *   then `switches[1]`, a neighbour can, the second (opens gate 1: objects 0..2 on row 8). A wrong second can closes
 *   gate 0 and draws new cans (`ov04_022563C4`, `VermilionGymLockAction`, `PlaceVermilionGymSwitches`). With the
 *   Thunder Badge both gates start open.
 * - **Mahogany Gym** (`MAP_MAHOGANY_GYM_ROOM_1` / `_ROOM_2` / `_LEADER_ROOM`): ice blocks (`ICE` objects) on the ice.
 *   Sliding into a block pushes it on in that direction until it stops (a wall, a tile that isn't ice, another object);
 *   two blocks meeting freeze together and both turn north: they never move again (src/unk_0206D494.c). The objects
 *   are placed anew on each entry, so leaving the room puts every block back.
 * - **Cinnabar Gym** (`MAP_SEAFOAM_ISLANDS_CINNABAR_GYM`, scr_seq_0015_D11R0106.s): each trainer, once beaten, walks
 *   one tile and turns back (`ApplyMovement` after `TrainerBattle`), then sets its flag (`FLAG_UNK_13B`..`_140`).
 *   Three always step the same way; three step away from the player (`GetPlayerFacing`): the way the player faces when
 *   it is theirs, else a default. On entry the init script (`scr_seq_D11R0106_008`) puts three of them back where they
 *   stepped (flags 13B, 13D, 13E); the others stand on their own tile again.
 */
object HgssGymPuzzles {

    /** `MAP_ECRUTEAK_GYM` (T27GYM0101). */
    const val ECRUTEAK_GYM = 80

    /** `MAP_VIOLET_GYM` (T22GYM0101). */
    const val VIOLET_GYM = 135

    /** `MAP_CIANWOOD_GYM` (T24GYM0101). */
    const val CIANWOOD_GYM = 139

    /** `MAP_VERMILION_GYM` (T06GYM0101). */
    const val VERMILION_GYM = 365

    /** `MAP_MAHOGANY_GYM_LEADER_ROOM` (T28GYM0101), `_ROOM_2` (T28GYM0102), `_ROOM_1` (T28GYM0103). */
    val MAHOGANY_GYM = setOf(140, 396, 397)

    /** `MAP_SEAFOAM_ISLANDS_CINNABAR_GYM` (D11R0106). */
    const val CINNABAR_GYM = 457

    /** `GymmickType` (include/gymmick.h). */
    private const val GYMMICK_ECRUTEAK = 1
    private const val GYMMICK_CIANWOOD = 2
    private const val GYMMICK_VERMILION = 3
    private const val GYMMICK_VIOLET = 4
    private const val GYMMICK_BLACKTHORN = 6
    private const val GYMMICK_FUCHSIA = 7

    /** Offset of the `GymmickUnion` in the slot (after the u32 type). */
    private const val DATA = 4

    /** The mechanism of gym [mapId] right now, or null (not one of these gyms, or its slot isn't set up). */
    fun read(mapId: Int, reads: HgssPuzzles.Reads, area: Area?, iceBlocks: List<HgssIlexFarfetchd.ObjectAt> = emptyList()): PuzzleState? = when (mapId) {
        in MAHOGANY_GYM -> mahoganyGym(iceBlocks)
        CINNABAR_GYM -> cinnabarGym(reads)
        VIOLET_GYM -> violetGym(reads)
        ECRUTEAK_GYM -> ecruteakGym(reads, area)
        CIANWOOD_GYM -> cianwoodGym(reads)
        VERMILION_GYM -> vermilionGym(reads)
        else -> null
    }

    /**
     * What the route planner doesn't model on the current map, from the gym slot's type (it only holds the current
     * gym's mechanism), or null.
     */
    fun unmodeled(reads: HgssPuzzles.Reads): String? {
        val gymmick = reads.gymmick() ?: return null
        return when (Gen4RomBytes.s32(gymmick, 0)) {
            GYMMICK_BLACKTHORN -> "three moving and rotating platforms (Blackthorn Gym): pushing one moves or turns it, and only " +
                "their positions decide which ways are open"
            GYMMICK_FUCHSIA -> "invisible walls (Fuchsia Gym): some floor tiles are blocked by walls you can't see"
            else -> null
        }
    }

    // region Violet Gym lift

    /** The lift's center: the coordinate trigger that starts it. */
    val LIFT = PuzzleTile(15, 20)

    /** The platform tiles (`ov01_021FB3E4(0, 14, 19, 3, ...)`: 3×3 from (14,19)). */
    val LIFT_PLATFORM: List<PuzzleTile> = (19..21).flatMap { y -> (14..16).map { x -> PuzzleTile(x, y) } }

    /** The lift's floors in [dev.kotlinds.pokemonclient.state.FieldState.height] units (half world units: 2.0 and 31.0). */
    const val LIFT_DOWN = 4
    const val LIFT_UP = 62

    private const val LIFT_RULE =
        "The lift is the 3x3 platform around 15,20: step on its center (15,20) to ride it to the other floor (it moves " +
            "whichever floor you're on; the upper floor with Falkner is only reached this way). Routes use it by themselves."

    private fun violetGym(reads: HgssPuzzles.Reads): PuzzleState? {
        val gymmick = reads.gymmick()?.takeIf { Gen4RomBytes.s32(it, 0) == GYMMICK_VIOLET } ?: return null
        val up = Gen4RomBytes.s32(gymmick, DATA) != 0
        val rides = listOf(
            PuzzleTeleport("lift:up", TeleportKind.LIFT, listOf(LIFT), LIFT, fromHeight = LIFT_DOWN, toHeight = LIFT_UP),
            PuzzleTeleport("lift:down", TeleportKind.LIFT, listOf(LIFT), LIFT, fromHeight = LIFT_UP, toHeight = LIFT_DOWN),
        )
        return PuzzleState(
            PuzzleKind.LIFT, LIFT_RULE,
            teleports = rides,
            indicators = listOf(PuzzleIndicator("lift:0", up, LIFT_PLATFORM, "up (at Falkner's floor); down when off")),
            surfaces = listOf(PuzzleSurface(LIFT_PLATFORM, listOf(LIFT_DOWN, LIFT_UP))),
        )
    }

    // endregion

    // region Ecruteak Gym

    /** The Mediums' object ids (`sMortyGymTrainerObjectIds`): candle i is carried by object 2 + i. */
    private const val FIRST_MEDIUM = 2

    private const val ECRUTEAK_RULE =
        "The floor is invisible: only a hidden path holds. Every other tile is a pit (teleport:N tiles) that drops you " +
            "back at the entrance (16,49); routes avoid them. Each Medium carries a candle (candle:N), blown out when she is beaten; they are lit again on each entry."

    private fun ecruteakGym(reads: HgssPuzzles.Reads, area: Area?): PuzzleState {
        val gymmick = reads.gymmick()?.takeIf { Gen4RomBytes.s32(it, 0) == GYMMICK_ECRUTEAK }
        val candles = (0 until 4).mapNotNull { i ->
            val medium = area?.people?.firstOrNull { it.zone == ECRUTEAK_GYM && it.id == FIRST_MEDIUM + i } ?: return@mapNotNull null
            val out = gymmick?.let { (it[DATA + i].toInt() and 0xFF) != 0 } ?: return@mapNotNull null
            PuzzleIndicator("candle:$i", !out, listOf(PuzzleTile(medium.x, medium.y)), "lit (blown out when its Medium, person:${medium.id}, is beaten during this visit)")
        }
        return PuzzleState(PuzzleKind.HIDDEN_FLOOR, ECRUTEAK_RULE, indicators = candles)
    }

    // endregion

    // region Cianwood Gym

    /** `FLAG_SYS_CIANWOOD_WATERFALL_DISABLE` (include/constants/flags.h). */
    const val FLAG_WATERFALL_DISABLE = 0x981

    /** The winch: bg event 0, examined from the south. */
    private val WINCH = PuzzleTile(10, 2)

    /** Chuck's niche, under the waterfall. */
    private val CHUCK = PuzzleTile(13, 10)

    private const val CIANWOOD_RULE =
        "Chuck (person:0) trains under the waterfall and won't battle while it flows: turn the winch (switch:0, " +
            "interact sign:0 facing north, answer yes) to stop it. It flows again each time you enter. The waterfall blocks no tile."

    private fun cianwoodGym(reads: HgssPuzzles.Reads): PuzzleState {
        val winch = reads.gymmick()?.takeIf { Gen4RomBytes.s32(it, 0) == GYMMICK_CIANWOOD }?.let { Gen4RomBytes.s32(it, DATA) != 0 } ?: false
        val stopped = winch || reads.flag(FLAG_WATERFALL_DISABLE) == true
        return PuzzleState(
            PuzzleKind.WATERFALL_WINCH, CIANWOOD_RULE,
            switches = listOf(PuzzleSwitch("switch:0", listOf("sign:0"), listOf(WINCH), toggles = listOf("waterfall:0"), oneShot = true, used = stopped)),
            indicators = listOf(PuzzleIndicator("waterfall:0", !stopped, listOf(CHUCK), "flowing (Chuck won't battle)")),
        )
    }

    // endregion

    // region Mahogany Gym

    private const val MAHOGANY_RULE =
        "Ice blocks on the ice: sliding into one (walk onto the ice towards it) pushes it on that way until it stops " +
            "against a wall, a tile that isn't ice or another object, and you stop where it was. Two blocks meeting freeze " +
            "together and never move again. Leaving the room and coming back puts every block back in place (the way to " +
            "start again after a wrong push). go_to pushes them by itself when it plans the puzzles (else slide into them with step)."

    /**
     * The ice blocks of a Mahogany Gym room ([blocks]: their objects): a block moves only while it faces south, as the
     * map places it; one that froze to another faces north (`sub_0206D590` in src/unk_0206D494.c pushes an `SPRITE_ICE`
     * object only when it faces `DIR_SOUTH`, asserting `DIR_NORTH` otherwise).
     */
    private fun mahoganyGym(blocks: List<HgssIlexFarfetchd.ObjectAt>): PuzzleState? {
        if (blocks.isEmpty()) return null
        return PuzzleState(
            PuzzleKind.ICE_BLOCKS, MAHOGANY_RULE,
            iceBlocks = blocks.map { PuzzleIceBlock("person:${it.id}", PuzzleTile(it.x, it.y), movable = it.facing == Direction.SOUTH) },
        )
    }

    // endregion

    // region Cinnabar Gym

    /**
     * A Cinnabar Gym trainer: object [id], its beaten flag, the way it steps ([always]), or away from the player
     * ([away]: when the player faces that way, else [otherwise]).
     */
    private class Stepper(val id: Int, val flag: Int, val always: Direction? = null, val away: Direction? = null, val otherwise: Direction? = null) {
        fun step(playerFacing: Direction): Direction = always ?: if (playerFacing == away) away else otherwise!!
    }

    /** The six trainers (scr_seq_D11R0106_002..007 and their movements _0424.._0484). */
    private val CINNABAR_TRAINERS = listOf(
        Stepper(2, 0x13E, always = Direction.NORTH),
        Stepper(3, 0x13F, away = Direction.EAST, otherwise = Direction.WEST),
        Stepper(4, 0x140, away = Direction.WEST, otherwise = Direction.EAST),
        Stepper(5, 0x13B, always = Direction.EAST),
        Stepper(6, 0x13C, away = Direction.NORTH, otherwise = Direction.SOUTH),
        Stepper(7, 0x13D, always = Direction.SOUTH),
    )

    private const val CINNABAR_RULE =
        "Each trainer steps one tile aside once beaten (some always the same way, others away from you, so the side " +
            "you talk from decides it): a step can open the way or close it. interact and go_to talk to them from a side " +
            "where the step keeps the way open. Some of them stand on their own tile again when you come back in."

    /** Which way each one steps is the gym's script, not something seen in the game: a walkthrough's. */
    private const val CINNABAR_WALKTHROUGH_RULE = "step_aside tells which way each trainer steps."

    private fun cinnabarGym(reads: HgssPuzzles.Reads): PuzzleState = PuzzleState(
        PuzzleKind.TRAINERS_STEP_ASIDE, CINNABAR_RULE, walkthroughRule = CINNABAR_WALKTHROUGH_RULE,
        stepAside = CINNABAR_TRAINERS.map { t ->
            PuzzleStepAside("person:${t.id}", reads.flag(t.flag) == true, Direction.entries.map { StepAsideMove(it, t.step(it)) })
        },
    )

    // endregion

    // region Vermilion Gym

    /** Trash can [can] (bg event [can], 0..14): a 5×3 grid from (2,13), 2 tiles apart. */
    fun trashCan(can: Int): PuzzleTile = PuzzleTile(2 + 2 * (can % 5), 13 + 2 * (can / 5))

    /** Tiles of each electric gate (its three stop objects): gate 0 on row 10 (objects 3..5), gate 1 on row 8 (0..2). */
    private val GATES = listOf((5..7).map { PuzzleTile(it, 10) }, (5..7).map { PuzzleTile(it, 8) })

    private const val VERMILION_RULE =
        "Two electric gates (gate:0, then gate:1) close the way to Lt. Surge. Examine the trash cans (sign:0..14) with A: " +
            "one hides the first switch (opens gate:0), then one of its neighbours hides the second (opens gate:1). A wrong " +
            "second can closes gate:0 again and hides both switches elsewhere."

    private fun vermilionGym(reads: HgssPuzzles.Reads): PuzzleState? {
        val gymmick = reads.gymmick()?.takeIf { Gen4RomBytes.s32(it, 0) == GYMMICK_VERMILION } ?: return null
        val cans = (0 until 2).map { gymmick[DATA + it].toInt() and 0xFF }
        val open = (0 until 2).map { (gymmick[DATA + 2 + it].toInt() and 0xFF) != 0 }
        val solved = open.all { it }
        val switches = (0 until 2).mapNotNull { i ->
            val can = cans[i].takeIf { it in 0 until 15 } ?: return@mapNotNull null
            PuzzleSwitch(
                "switch:$i", listOf("sign:$can"), listOf(trashCan(can)),
                toggles = listOf("gate:$i"), oneShot = true, used = open[i], hidden = true,
            )
        }
        return PuzzleState(
            PuzzleKind.TRASH_CAN_SWITCHES, VERMILION_RULE,
            switches = if (solved) emptyList() else switches,
            barriers = GATES.mapIndexed { i, tiles -> PuzzleBarrier("gate:$i", open[i], tiles) },
        )
    }

    // endregion

}
