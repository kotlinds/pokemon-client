package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.VolatileStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Foe conditions read from `BattleMon.status2` and `moveEffectFlags` (include/constants/battle.h). */
class SsVolatileStatusesTest {
    @Test
    fun destinyBondIsStatus2Bit25() {
        // Set by Destiny Bond, cleared when its user starts its next move (battle_controller_player.c:2109).
        assertEquals(setOf<VolatileStatus>(VolatileStatus.DestinyBond), HgssStatuses.volatile(1L shl 25, 0, 0))
    }

    @Test
    fun moveEffectFlagsGiveTheOtherConditions() {
        val flags = (1L shl 14) or (1L shl 27) or (1L shl 24) or (1L shl 9)
        val read = HgssStatuses.volatile(0, flags, 0)
        assertTrue(read.containsAll(listOf(VolatileStatus.Grudge, VolatileStatus.MagnetRise, VolatileStatus.AquaRing, VolatileStatus.Charged)), read.toString())
        assertEquals(4, read.size)
    }

    @Test
    fun disableAndEncoreNameTheirMoveAndTurns() {
        // unk88 counters: disabledTurns bits 0-2, encoredTurns bits 3-5; the moves from unk88.disabledMove / encoredMove.
        val names = mapOf(55 to "Water Gun", 33 to "Tackle")
        val read = HgssStatuses.volatile(0, 0, 3L or (2L shl 3), disabledMove = 55, encoredMove = 33) { names[it] }
        assertEquals(
            setOf<VolatileStatus>(
                VolatileStatus.Disabled(dev.kotlinds.pokemonclient.state.Named(dev.kotlinds.pokemonclient.state.MoveId(55), "Water Gun"), 3),
                VolatileStatus.Encored(dev.kotlinds.pokemonclient.state.Named(dev.kotlinds.pokemonclient.state.MoveId(33), "Tackle"), 2),
            ),
            read,
        )
        // No counter: no condition, whatever move the struct still holds.
        assertTrue(HgssStatuses.volatile(0, 0, 0, disabledMove = 55, encoredMove = 33).isEmpty())
    }
}
