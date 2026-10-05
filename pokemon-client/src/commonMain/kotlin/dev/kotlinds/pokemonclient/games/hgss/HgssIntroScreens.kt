package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.console.Button
import dev.kotlinds.pokemonclient.games.gen4.Gen4Structs
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.CancelBehavior
import dev.kotlinds.pokemonclient.state.ContinueReason
import dev.kotlinds.pokemonclient.state.Cursor
import dev.kotlinds.pokemonclient.state.Entry
import dev.kotlinds.pokemonclient.state.IntroInputs
import dev.kotlinds.pokemonclient.state.IntroStage
import dev.kotlinds.pokemonclient.state.MenuKind
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.Topology
import dev.kotlinds.pokemonclient.games.hgss.HgssAddresses as A

/**
 * RAM layout of the main menu shown after the title screen (`MainMenuAppData`, src/application/main_menu/main_menu.c,
 * overlay 74): the data of the main overlay manager.
 */
internal object HgssMainMenuAddresses {
    const val CURRENT_OPTION = 0x54L         // u16 currentOption: button slot highlighted (index into the slots below)
    const val SLOTS = 0xECL                  // u32 unkEC[9]: per button slot, its MainMenu_AppOption, 0 when not shown
    const val SLOT_COUNT = 9
    const val INPUT_STATE = 0x1ACL           // MenuInputStateMgr: 0 buttons (highlight drawn), 1 touch (no highlight)
    const val INPUT_STATE_TOUCH = 1

    /** `MainMenuApp_Main` state 5: MainMenu_HandleInput (keys / touch read). */
    const val PROC_HANDLE_INPUT = 5

    /** `MainMenuApp_Main` state 7: graphics freed, the menu exits (its VBlank callback is gone). */
    const val PROC_EXIT = 7

    /**
     * u32 unk13C: the menu's wireless scan (ov74_022276AC): 11 starting, 13 scanning (for Mystery Gift, Ranger, Wii
     * beacons, 120 iterations), 12 off. Set to 10 by state 4, which also installs the menu's VBlank callback.
     */
    const val WIRELESS = 0x13CL
    val WIRELESS_RUNNING = setOf(11, 13)

    /** `MainMenu_AppOption` values (1..9) → semantic id and the label's line of msg bank 442 (display only). */
    val OPTIONS: Map<Int, Pair<String, Int?>> = mapOf(
        1 to ("option:continue" to 0),
        2 to ("option:new_game" to 1),
        3 to ("option:pokewalker" to 9),
        4 to ("option:mystery_gift" to 2),
        5 to ("option:ranger" to 3),
        6 to ("option:migrate" to null),
        7 to ("option:connect_to_wii" to 11),
        8 to ("option:wfc" to 12),
        9 to ("option:wii_settings" to 10),
    )

    /** English labels, when the ROM's text isn't available. */
    val FALLBACK_LABELS = mapOf(
        "option:continue" to "CONTINUE", "option:new_game" to "NEW GAME", "option:pokewalker" to "CONNECT TO POKéWALKER",
        "option:mystery_gift" to "MYSTERY GIFT", "option:ranger" to "CONNECT TO RANGER", "option:migrate" to "MIGRATE",
        "option:connect_to_wii" to "CONNECT TO WII", "option:wfc" to "NINTENDO WFC SETTINGS", "option:wii_settings" to "WII MESSAGE SETTINGS",
    )

    /** Main menu labels (msg_0442). */
    val LABEL_BANK = dev.kotlinds.pokemonclient.data.TextBankId(442)
}

/**
 * RAM layout of the intro movie and the title screen (overlay intro_title = 60): `IntroMovieOverlayData`
 * (include/intro_movie_internal.h, src/intro_movie.c) and `TitleScreenOverlayData` (include/title_screen.h,
 * src/title_screen.c), the data of the main overlay manager.
 */
internal object HgssTitleAddresses {
    /** IntroMovieOverlayData.introSkipped (BOOL): set by the frame that took the skip; the movie then ends. */
    const val MOVIE_SKIPPED = 0x08L

    /** IntroMovieOverlayData.skipAllowed (u8): set by the first scene once the Game Freak logo appears (~2 s in). */
    const val MOVIE_SKIP_ALLOWED = 0x628L

    /** TitleScreenOverlayData.initialDelay (u32): 30 main-loop iterations during which no input is read. */
    const val TITLE_INITIAL_DELAY = 0x2E4L

    /** `TitleScreen_Main` state TITLESCREEN_MAIN_PLAY: the only state reading input (the others load, flash or fade). */
    const val TITLE_STATE_PLAY = 2
}

/**
 * Screens before the game starts, with the inputs each one takes right now:
 * - the intro movie: A, START or a touch skip it, once its first scene allowed it (`skipAllowed`, ~2 s in);
 * - the title screen: A, START or a touch anywhere on the bottom screen open the main menu, only in its PLAY state
 *   and after its 30 first iterations (`initialDelay`): an input before is lost. Left alone (2340 iterations) it
 *   plays the intro movie again;
 * - the main menu (CONTINUE / NEW GAME / ...), as a list whose ids are the options the game knows
 *   (`option:continue`...), never their text.
 */
internal object HgssIntroScreens : HgssScreenDecoder {
    private val M = HgssMainMenuAddresses
    private val T = HgssTitleAddresses

    /** What the intro movie and the title screen take: `gSystem.newKeys & (A | START) || gSystem.touchNew`. */
    private val A_START_OR_TOUCH = IntroInputs(setOf(Button.A, Button.START), touchAnywhere = true)

    override fun decode(mem: HgssMemory, state: HgssState): Screen? = when (state.mode) {
        GameMode.MAIN_MENU -> mainMenu(mem)
        GameMode.INTRO_MOVIE -> introMovie(mem)
        GameMode.TITLE_SCREEN -> titleScreen(mem)
        else -> null
    }

    /** The running top-level application's data, while its main function runs (exec state 2); null otherwise. */
    private fun runningAppData(mem: HgssMemory): Pair<Long, Long>? {
        val om = mem.ptr(mem.version.mainAppState + A.MAIN_APP_OVERLAY_MANAGER) ?: return null
        if (mem.s32(om + A.OM_EXEC_STATE) != 2) return null
        val data = mem.ptr(om + A.OM_DATA) ?: return null
        return om to data
    }

    /** `IntroMovie_Main` reads the skip at the top of every iteration, while `skipAllowed` and not yet skipped. */
    private fun introMovie(mem: HgssMemory): Screen {
        val (_, data) = runningAppData(mem) ?: return Screen.Intro(IntroStage.INTRO_MOVIE, Awaiting.ANIMATION)
        val skippable = mem.u8(data + T.MOVIE_SKIP_ALLOWED) != 0 && mem.s32(data + T.MOVIE_SKIPPED) == 0
        return if (skippable) Screen.Intro(IntroStage.INTRO_MOVIE, Awaiting.INPUT, A_START_OR_TOUCH)
        else Screen.Intro(IntroStage.INTRO_MOVIE, Awaiting.ANIMATION)
    }

    /** `TitleScreen_Main`: input is read in TITLESCREEN_MAIN_PLAY once `initialDelay` is over, nowhere else. */
    private fun titleScreen(mem: HgssMemory): Screen {
        val (om, data) = runningAppData(mem) ?: return Screen.Intro(IntroStage.TITLE_SCREEN, Awaiting.ANIMATION)
        val ready = mem.s32(om + A.OM_PROC_STATE) == T.TITLE_STATE_PLAY && mem.u32(data + T.TITLE_INITIAL_DELAY) == 0L
        return if (ready) Screen.Intro(IntroStage.TITLE_SCREEN, Awaiting.INPUT, A_START_OR_TOUCH)
        else Screen.Intro(IntroStage.TITLE_SCREEN, Awaiting.ANIMATION)
    }

    /**
     * The wireless scan the main menu starts once shown failed: the game stopped in its communication error loop
     * (src/main.c sub_02000FD8, ShowCommunicationError), which waits for A then soft-resets to the title screen. The
     * menu no longer runs; the error removed its VBlank callback (Main_SetVBlankIntrCB(NULL)), which the menu sets
     * when it starts the scan and only removes when it exits. Seen on melonDS (no wireless) with a save.
     */
    private fun communicationError(mem: HgssMemory, om: Long, data: Long): Boolean =
        mem.s32(om + A.OM_EXEC_STATE) == 2 && mem.s32(om + A.OM_PROC_STATE) != M.PROC_EXIT &&
            mem.s32(data + M.WIRELESS) in M.WIRELESS_RUNNING && mem.u32(mem.version.gSystem + Gen4Structs.SYS_VBLANK_CALLBACK) == 0L

    /**
     * The main menu while it reads input (state 5). The cursor is the highlighted button slot; after a touch the
     * highlight isn't drawn and the first key only draws it again ([Cursor.Hidden]). UP / DOWN move between the shown
     * buttons without wrapping (ChangeCurrentAppOption); B goes back to the title screen.
     */
    fun mainMenu(mem: HgssMemory): Screen {
        val notReady = Screen.Intro(IntroStage.MAIN_MENU, Awaiting.ANIMATION)
        val om = mem.ptr(mem.version.mainAppState + A.MAIN_APP_OVERLAY_MANAGER) ?: return notReady
        val data = mem.ptr(om + A.OM_DATA) ?: return notReady
        if (communicationError(mem, om, data)) return Screen.PressToContinue(ContinueReason.COMMUNICATION_ERROR)
        if (mem.s32(om + A.OM_EXEC_STATE) != 2 || mem.s32(om + A.OM_PROC_STATE) != M.PROC_HANDLE_INPUT) return notReady
        val labels = HgssData.gameData?.bank(M.LABEL_BANK)
        val shown = (0 until M.SLOT_COUNT).mapNotNull { slot ->
            val (id, line) = M.OPTIONS[mem.u32(data + M.SLOTS + 4L * slot).toInt()] ?: return@mapNotNull null
            val label = line?.let { labels?.getOrNull(it) }?.takeIf { it.isNotBlank() }?.replace('\n', ' ') ?: M.FALLBACK_LABELS.getValue(id)
            slot to Entry(id, label, dangerous = id == "option:new_game")
        }
        if (shown.isEmpty()) return notReady
        val current = shown.indexOfFirst { it.first == mem.u16(data + M.CURRENT_OPTION) }
        val cursor = if (current < 0 || mem.s32(data + M.INPUT_STATE) == M.INPUT_STATE_TOUCH) Cursor.Hidden else Cursor.At(current)
        return Screen.ListMenu(MenuKind.MAIN_MENU, shown.map { it.second }, cursor, Topology.vertical(shown.size), CancelBehavior.CLOSES)
    }
}
