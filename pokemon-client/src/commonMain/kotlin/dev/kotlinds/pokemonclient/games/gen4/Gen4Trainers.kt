package dev.kotlinds.pokemonclient.games.gen4

/**
 * What every Gen 4 game does the same with its trainers (Platinum `Script_GetTrainerID` / `FLAG_OFFSET_TRAINER_DEFEATED`
 * in src/script_manager.c, HeartGold `ScriptNumToTrainerNum` / `TrainerFlagCheck` in src/fieldmap.c):
 * - a map object on a common trainer script battles trainer `script - 3000 + 1` (`std_trainer`, single battles) or
 *   `script - 5000 + 1` (`std_trainer_2`, the second trainer of a double battle);
 * - beating trainer `id` sets event flag [TRAINER_FLAG_BASE] + `id`.
 */
object Gen4Trainers {

    /** The trainer a common trainer script battles, or null for any other script. */
    fun trainerOfScript(script: Int): Int? = when (script) {
        in SINGLE_BATTLES until DOUBLE_BATTLES -> script - SINGLE_BATTLES + FIRST_TRAINER
        in DOUBLE_BATTLES until ITEM_BALLS -> script - DOUBLE_BATTLES + FIRST_TRAINER
        else -> null
    }

    /** The event flag set once trainer [trainerId] is beaten. */
    fun flagOf(trainerId: Int): Int = TRAINER_FLAG_BASE + trainerId

    /** First trainer flag (`TRAINER_FLAG_BASE` / `FLAG_OFFSET_TRAINER_DEFEATED`, 1360 in both games). */
    const val TRAINER_FLAG_BASE = 0x550

    private const val SINGLE_BATTLES = 3000
    private const val DOUBLE_BATTLES = 5000

    /** The common item ball scripts follow the trainer ones (`std_item_ball`). */
    private const val ITEM_BALLS = 7000
    private const val FIRST_TRAINER = 1
}
