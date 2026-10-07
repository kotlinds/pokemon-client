package dev.kotlinds.pokemonclient.games.gen4

import dev.kotlinds.pokemonclient.data.BattleStatBoost
import dev.kotlinds.pokemonclient.data.EffortStat
import dev.kotlinds.pokemonclient.data.HpRestore
import dev.kotlinds.pokemonclient.data.ItemEffect
import dev.kotlinds.pokemonclient.data.PpRestore
import dev.kotlinds.pokemonclient.data.PpUp
import dev.kotlinds.pokemonclient.data.StatusCure

/**
 * The Gen 4 item data record (`ItemData`, 36 bytes, include/item.h; the same in HeartGold / SoulSilver and Platinum):
 * what using an item on a Pokémon does, from its `ItemPartyParam` bit array at [PARTY_PARAM].
 */
object Gen4ItemData {

    /** `ItemData.partyUseParam`: after price, hold effect, pluck / fling / natural gift bytes, the pocket word and the use funcs. */
    const val PARTY_PARAM = 0x0E

    /**
     * `ItemData.partyUse`: 0 for an item never used on a Pokémon (a ball, a key item...), whose [PARTY_PARAM] bytes are
     * then unused (and not zero: the Poké Ball's say "cures sleep and poison").
     */
    const val PARTY_USE = 0x0C

    /** The bytes an item record needs for [effect]. */
    const val SIZE = 0x22

    /** `HP_RESTORE_ALL` / `_HALF` / `_QTR`, `PP_RESTORE_ALL` (include/constants/items.h). */
    private const val HP_ALL = 255
    private const val HP_HALF = 254
    private const val HP_QUARTER = 253
    private const val PP_ALL = 127

    /** The effect of item record [data] on a Pokémon; null when it has none (or the record is too short). */
    fun effect(data: ByteArray): ItemEffect? {
        if (data.size < SIZE || data[PARTY_USE].toInt() == 0) return null
        fun byte(i: Int) = data[PARTY_PARAM + i].toInt() and 0xFF
        fun bit(i: Int, b: Int) = byte(i) shr b and 1 == 1
        fun nibble(i: Int, high: Boolean) = if (high) byte(i) shr 4 and 0xF else byte(i) and 0xF
        fun signed(i: Int) = data[PARTY_PARAM + i].toInt()
        val cures = buildSet {
            if (bit(0, 0)) add(StatusCure.SLEEP)
            if (bit(0, 1)) add(StatusCure.POISON)
            if (bit(0, 2)) add(StatusCure.BURN)
            if (bit(0, 3)) add(StatusCure.FREEZE)
            if (bit(0, 4)) add(StatusCure.PARALYSIS)
            if (bit(0, 5)) add(StatusCure.CONFUSION)
            if (bit(0, 6)) add(StatusCure.INFATUATION)
        }
        val stages = buildMap {
            nibble(1, true).takeIf { it != 0 }?.let { put(BattleStatBoost.ATTACK, it) }
            nibble(2, false).takeIf { it != 0 }?.let { put(BattleStatBoost.DEFENSE, it) }
            nibble(2, true).takeIf { it != 0 }?.let { put(BattleStatBoost.SP_ATTACK, it) }
            nibble(3, false).takeIf { it != 0 }?.let { put(BattleStatBoost.SP_DEFENSE, it) }
            nibble(3, true).takeIf { it != 0 }?.let { put(BattleStatBoost.SPEED, it) }
            nibble(4, false).takeIf { it != 0 }?.let { put(BattleStatBoost.ACCURACY, it) }
            (byte(4) shr 4 and 0x3).takeIf { it != 0 }?.let { put(BattleStatBoost.CRITICAL_HIT, it) }
        }
        // EV flags (byte 5 bits 3-7, byte 6 bit 0) and their signed amounts (bytes 7..12).
        val evs = buildMap {
            listOf(
                EffortStat.HP to (5 to 3), EffortStat.ATTACK to (5 to 4), EffortStat.DEFENSE to (5 to 5),
                EffortStat.SPEED to (5 to 6), EffortStat.SP_ATTACK to (5 to 7), EffortStat.SP_DEFENSE to (6 to 0),
            ).forEachIndexed { i, (stat, flag) -> if (bit(flag.first, flag.second)) put(stat, signed(7 + i)) }
        }
        val revives = bit(1, 0)
        val hpParam = byte(13)
        val hp = if (bit(5, 2) || revives) when (hpParam) {
            HP_ALL -> HpRestore.Full
            HP_HALF -> HpRestore.Half
            HP_QUARTER -> HpRestore.Quarter
            0 -> null
            else -> HpRestore.Points(hpParam)
        } else null
        val ppParam = byte(14)
        val pp = when {
            bit(5, 1) -> PpRestore(ppParam.takeIf { it != PP_ALL }, allMoves = true)
            bit(5, 0) -> PpRestore(ppParam.takeIf { it != PP_ALL }, allMoves = false)
            else -> null
        }
        val effect = ItemEffect(
            hp = hp,
            cures = cures,
            revives = revives,
            revivesParty = bit(1, 1),
            pp = pp,
            ppUp = when {
                bit(4, 7) -> PpUp.MAX
                bit(4, 6) -> PpUp.ONE_STEP
                else -> null
            },
            statStages = stages,
            guardSpec = bit(0, 7),
            levelUp = bit(1, 2),
            evolves = bit(1, 3),
            effortValues = evs,
        )
        return effect.takeIf { it != ItemEffect() }
    }
}
