package dev.kotlinds.pokemonclient.games.hgss

/**
 * What follows the Champion's defeat (`CallTask_GameClear`, src/game_clear.c), read from RAM:
 * 1. the registration in the Hall of Fame (overlay 63, `RegisterHallOfFame_*`, src/register_hall_of_fame.c): each
 *    team member is presented in turn with no input taken, then the whole team with the player, which waits for A
 *    (or B, a touch) once its animation is over: the only input of the sequence;
 * 2. the save (`Task_GameClear` states 2-10: "Saving... Don't turn off the power.", then the fade out), with the
 *    soft reset disabled while it writes;
 * 3. the credits (overlay 76, `Credits_*`, src/credits/credits.c): `CREDITS_FRAMES` (4976) frames of its main loop,
 *    which runs every other frame of the console (about 2 minutes 45 s), skippable with START or a
 *    touch only when the game had already been cleared before; then "The End", which waits for A / START / a touch;
 *    the game then restarts (`OS_ResetSystem`) at the title screen.
 *
 * Verified on a live League win (HeartGold USA, bench): offsets, scene and stage numbers below.
 */
internal object HgssGameClear {

    /** [HgssState.modeDetail] while the game saves after the Hall of Fame (`Task_GameClear` runs, no app). */
    const val SAVE_DETAIL = "game_clear_save"

    // RegisterHallOfFameData (src/register_hall_of_fame.c).
    private const val HOF_SUBPROC_CALLBACK = 0x08L
    private const val HOF_SUBPROC_STAGE = 0x0EL
    private const val HOF_NUM_MONS = 0x13048L
    private const val HOF_SCENE = 0x1304CL
    private const val HOF_SCENE_SUBSTEP = 0x13054L
    private const val HOF_CUR_MON = 0x13056L

    // RegisterHallOfFameScene.
    private const val SCENE_INDIV_MONS_EXIT = 4
    private const val SCENE_WHOLE_PARTY_MAIN = 6

    /** REGHOF_WHOLE_SUBSCENE_MAIN: the whole team's sub-process runs. */
    private const val WHOLE_SUBSCENE_MAIN = 1

    /** REGHOF_WHOLE_SUBPROC_WAIT_BUTTON: `RegisterHallOfFame_WholeMonsSceneSubproc` waits for A / B / a touch. */
    private const val WHOLE_SUBPROC_WAIT_BUTTON = 5

    /** CreditsArgs.gameCleared (include/credits/credits.h): the game had been cleared before this Hall of Fame. */
    private const val CREDITS_ARGS_GAME_CLEARED = 0x04L

    // CreditsAppState (the credits' OverlayManager state while Credits_Main runs): 0-3 roll (fade in, main, fade out,
    // fade into "The End"), 4-5 "The End" (then its music box), 6 its fade out.
    private const val CREDITS_STATE_THE_END = 4
    private const val CREDITS_STATE_THE_END_MUSIC_BOX = 5

    /** Where the Hall of Fame registration is (`RegisterHallOfFameData` at [data]). */
    data class Registration(
        /** Team members registered (eggs left out). */
        val count: Int,
        /** Presenting the team members one by one; [monIndex] is the one presented (from 0). */
        val presenting: Boolean,
        val monIndex: Int,
        /** The whole team is shown and its animation waits for A / B / a touch (the palette fade is over). */
        val waitsForButton: Boolean,
        /** The whole team's scene has started (its animation, the wait, the last flash). */
        val wholeTeam: Boolean,
        /** Past the wait: the last flash and the fade out. */
        val leaving: Boolean,
    )

    /** Reads the registration from its app [data]; null when it isn't laid out as expected. */
    fun registration(mem: HgssMemory, data: Long, fading: Boolean): Registration? {
        val count = mem.u32(data + HOF_NUM_MONS).toInt().takeIf { it in 1..6 } ?: return null
        val scene = mem.s32(data + HOF_SCENE)
        val stage = mem.u16(data + HOF_SUBPROC_STAGE)
        val callback = mem.u32(data + HOF_SUBPROC_CALLBACK) != 0L
        val substep = mem.u16(data + HOF_SCENE_SUBSTEP)
        val index = mem.u16(data + HOF_CUR_MON)
        val inWholeMain = scene == SCENE_WHOLE_PARTY_MAIN && substep == WHOLE_SUBSCENE_MAIN && callback
        val wholeTeam = scene > SCENE_INDIV_MONS_EXIT
        return Registration(
            count = count,
            presenting = !wholeTeam && index < count,
            monIndex = index,
            waitsForButton = inWholeMain && stage == WHOLE_SUBPROC_WAIT_BUTTON && !fading,
            wholeTeam = wholeTeam,
            leaving = wholeTeam && (scene > SCENE_WHOLE_PARTY_MAIN || inWholeMain && stage > WHOLE_SUBPROC_WAIT_BUTTON),
        )
    }

    /** Where the credits are. */
    enum class CreditsStage {
        /** The credits roll (and the fade before "The End"). */
        ROLLING,

        /** "The End" (with its music box after a while): waits for A / START / a touch. */
        THE_END,

        /** Leaving "The End": the game restarts at the title screen. */
        ENDING,
    }

    /** The credits' stage, and whether START / a touch skips them (a later Hall of Fame). */
    data class Credits(val stage: CreditsStage, val skippable: Boolean)

    /**
     * Reads the credits from their OverlayManager [manager] (its state is `CreditsAppState` while Credits_Main runs);
     * null while they start (`Credits_Init`) or end (`Credits_Exit`).
     */
    fun credits(mem: HgssMemory, manager: Long): Credits? {
        if (mem.s32(manager + HgssAddresses.OM_EXEC_STATE) != 2) return null
        val skippable = mem.ptr(manager + HgssAddresses.OM_ARGS)?.let { mem.u32(it + CREDITS_ARGS_GAME_CLEARED) != 0L } ?: false
        val stage = when (mem.s32(manager + HgssAddresses.OM_PROC_STATE)) {
            in 0 until CREDITS_STATE_THE_END -> CreditsStage.ROLLING
            CREDITS_STATE_THE_END, CREDITS_STATE_THE_END_MUSIC_BOX -> CreditsStage.THE_END
            else -> CreditsStage.ENDING
        }
        return Credits(stage, skippable)
    }
}
