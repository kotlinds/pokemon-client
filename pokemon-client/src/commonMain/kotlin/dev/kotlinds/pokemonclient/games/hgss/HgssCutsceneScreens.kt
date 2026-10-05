package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.games.hgss.HgssAddresses as A

/**
 * Field sub-applications and tasks that only play a scene, as [Screen.Animation]s with a hint on how to get through
 * them:
 * - after the Hall of Fame, the save of the game clear ([HgssGameClear], `Task_GameClear`): [AnimationKind.SAVING];
 * - the credits (overlay 76, `Credits_Init`, src/credits/credits.c), after that save: [AnimationKind.CREDITS], then
 *   "The End", a [Screen.PressToContinue] of [ContinueReason.THE_END] (A restarts the game at the title screen);
 * - the S.S. Aqua crossing between Olivine City and Vermilion City (overlay 105, launched by `sub_0203FC90`): it ends
 *   by itself at the other port.
 */
internal object HgssCutsceneScreens : HgssScreenDecoder {

    /** What [Screen.Animation.hint] says on the credits the first time (they can't be skipped). */
    const val CREDITS_HINT = "The credits after the Hall of Fame (about 3 minutes, they can't be skipped the first time): " +
        "the game was saved when the team was registered, so soft_reset now skips them with nothing lost (you continue " +
        "at home in New Bark Town); else wait until THE END, where A restarts the game at the title screen"

    /** What [Screen.Animation.hint] says on the credits of a later Hall of Fame (START or a touch skips them). */
    const val CREDITS_SKIPPABLE_HINT = "The credits after the Hall of Fame: the game was saved when the team was " +
        "registered; START (or a touch) skips them to THE END, where A restarts the game at the title screen " +
        "(soft_reset also works, with nothing lost)"

    /** What [Screen.Animation.hint] says while the game saves after the Hall of Fame. */
    const val SAVE_HINT = "The game saves after the Hall of Fame (a reset is refused meanwhile), then the credits start: " +
        "wait, or watch_hall_of_fame waits until they start"

    /** What [Screen.PressToContinue.text] says on "The End". */
    const val THE_END_TEXT = "THE END: A restarts the game at the title screen (it was saved after the Hall of Fame)"

    /** What [Screen.Animation.hint] says during the crossing. */
    const val SHIP_HINT = "The S.S. Aqua crossing between Olivine City and Vermilion City: wait, it ends by itself at the other port"

    override fun decode(mem: HgssMemory, state: HgssState): Screen? {
        if (state.modeDetail == HgssGameClear.SAVE_DETAIL) return Screen.Animation(AnimationKind.SAVING, SAVE_HINT)
        if (state.mode != GameMode.APP) return null
        return when (state.modeDetail) {
            "credits" -> credits(mem, state)
            "ship_crossing" -> Screen.Animation(AnimationKind.CUTSCENE, SHIP_HINT)
            else -> null
        }
    }

    /** The credits as they roll (skippable or not), "The End" once it waits for input, a transition otherwise. */
    private fun credits(mem: HgssMemory, state: HgssState): Screen {
        val manager = mem.ptr(mem.version.fieldSystemPtr)?.let { mem.ptr(it + A.FS_SUB0) }?.let { mem.ptr(it + A.FSS0_SUB_APP) }
        val credits = manager?.let { HgssGameClear.credits(mem, it) }
            ?: return Screen.Animation(AnimationKind.TRANSITION)
        return when (credits.stage) {
            HgssGameClear.CreditsStage.ROLLING -> Screen.Animation(AnimationKind.CREDITS, if (credits.skippable) CREDITS_SKIPPABLE_HINT else CREDITS_HINT)
            HgssGameClear.CreditsStage.THE_END ->
                if (state.awaitingInput) Screen.PressToContinue(ContinueReason.THE_END, THE_END_TEXT) else Screen.Animation(AnimationKind.TRANSITION)
            HgssGameClear.CreditsStage.ENDING -> Screen.Animation(AnimationKind.TRANSITION)
        }
    }
}
