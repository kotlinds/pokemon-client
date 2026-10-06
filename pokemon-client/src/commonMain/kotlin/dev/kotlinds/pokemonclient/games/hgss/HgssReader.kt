package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.games.gen4.Gen4Pokemon
import dev.kotlinds.pokemonclient.games.gen4.Gen4Structs as S
import dev.kotlinds.pokemonclient.games.gen4.Gen4Text
import dev.kotlinds.pokemonclient.Memory
import dev.kotlinds.pokemonclient.games.hgss.HgssAddresses as A

/**
 * Reads a structured snapshot of Pokémon HeartGold / SoulSilver from NDS main RAM.
 *
 * The code is shared by every HG/SS build; absolute addresses come from a [HgssVersion] chosen by the ROM game
 * code found in the cartridge header copy in RAM (0x023FFE0C), or forced with [version]. Only "IPKE"
 * (HeartGold USA) is filled in for now.
 *
 * Entry points:
 *  - [read]: full snapshot ([HgssState]), or null if the RAM does not look like a running HGSS at all.
 *
 * Every pointer is validated to be inside main RAM before being followed; any unexpected value produces a
 * partial state (+ a warning) instead of an exception.
 */
class HgssReader(private val memory: Memory, private val version: HgssVersion? = null) {

    /** Addresses of the version being read, resolved at the start of each [read]. */
    private var v: HgssVersion = version ?: HgssVersion.HEARTGOLD_US
        set(value) {
            field = value
            mem = HgssMemory(memory, value)
        }

    /** The safe Gen 4 reads ([HgssMemory]) with the address table of [v]. */
    private var mem: HgssMemory = HgssMemory(memory, v)

    /**
     * Picks the address table: the forced [version], else the one matching the game code in RAM.
     * Returns null + the game code when the ROM is not a supported version.
     */
    private fun resolveVersion(): Pair<HgssVersion?, String?> {
        version?.let { return it to it.gameCode }
        val code = HgssVersion.readGameCode(memory) ?: return HgssVersion.HEARTGOLD_US to null
        return HgssVersion.forGameCode(code) to code
    }

    // ================================================================================================
    // Snapshot
    // ================================================================================================

    private class Ctx {
        val warnings = mutableListOf<String>()
    }

    fun read(): HgssState? = try {
        val (resolved, code) = resolveVersion()
        when {
            resolved != null -> {
                v = resolved
                readInternal()?.let { st ->
                    if (code == null) st.copy(warnings = st.warnings + "game code unreadable, assumed ${v.gameCode}") else st
                }
            }
            // A HG/SS ROM we have no address table for: say so instead of reading garbage.
            code != null && code.take(3) in HgssVersion.HGSS_GAME_CODE_PREFIXES ->
                HgssState(frame = 0, mode = GameMode.UNKNOWN, modeDetail = "unsupported HG/SS version $code")
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    private fun readInternal(): HgssState? {
        val ctx = Ctx()
        val frame = mem.u32(v.gSystem + S.SYS_VBLANK_COUNTER)
        val mainOvy = mem.s32(v.mainAppState + A.MAIN_APP_OVERLAY_ID)
        val om = mem.ptr(v.mainAppState + A.MAIN_APP_OVERLAY_MANAGER)
            ?: return HgssState(frame = frame, mode = GameMode.LOADING, modeDetail = "no main app")
        val init = mem.fn(om + S.OM_INIT)
        val tmplOvy = mem.s32(om + A.OM_OVY_ID)
        val execState = mem.s32(om + S.OM_EXEC_STATE)

        if (init == v.fnFieldContinueAppInit || init == v.fnFieldNewGameAppInit) {
            return readField(ctx, frame, om, execState)
        }
        val (mode, detail) = when {
            mainOvy == A.OVY_INTRO_TITLE && init == v.fnIntroMovieInit -> GameMode.INTRO_MOVIE to null
            mainOvy == A.OVY_INTRO_TITLE && init == v.fnTitleScreenInit -> GameMode.TITLE_SCREEN to null
            mainOvy == A.OVY_MAIN_MENU && init == v.fnMainMenuInit -> GameMode.MAIN_MENU to null
            tmplOvy == A.OVY_OAK_SPEECH -> GameMode.NEW_GAME_INTRO to "oak_speech"
            init == v.fnCheckSaveInit -> GameMode.LOADING to "check_save"
            mainOvy == A.OVY_36 -> GameMode.LOADING to "starting_game"
            else -> GameMode.UNKNOWN to "mainOverlay=$mainOvy templateOverlay=$tmplOvy init=0x${init.toString(16)}"
        }
        // Outside the field we can only tell that nothing is fading (menus/intro wait for a button most of the time).
        // Their screens (Professor Oak's speech included) are decoded by HgssIntroScreens / HgssOakIntroScreens.
        val awaiting = mode in setOf(GameMode.INTRO_MOVIE, GameMode.TITLE_SCREEN, GameMode.MAIN_MENU, GameMode.NEW_GAME_INTRO) && !mem.fading
        return HgssState(frame = frame, mode = mode, modeDetail = detail, awaitingInput = awaiting)
    }

    private fun readField(ctx: Ctx, frame: Long, om: Long, execState: Int): HgssState {
        val omData = mem.ptr(om + S.OM_DATA)
        var fs = mem.ptr(v.fieldSystemPtr)
        if (fs == null || (omData != null && fs != omData)) {
            if (omData != null) {
                ctx.warnings += "sFieldSysPtr (${fs?.toString(16)}) != field app data (${omData.toString(16)}), using app data"
                fs = omData
            }
        }
        if (fs == null || execState < 2) {
            return HgssState(frame = frame, mode = GameMode.LOADING, modeDetail = "field starting", warnings = ctx.warnings)
        }

        val sub0 = mem.ptr(fs + A.FS_SUB0)
        val fieldMapApp = sub0?.let { mem.ptr(it + A.FSS0_FIELD_MAP_APP) }
        val subApp = sub0?.let { mem.ptr(it + A.FSS0_SUB_APP) }
        val paused = sub0?.let { mem.u32(it + A.FSS0_IS_PAUSED) != 0L } ?: true
        val taskman = mem.ptr(fs + A.FS_TASKMAN)
        val mapReady = mem.u32(fs + A.FS_MAP_READY) != 0L
        val playerControllable = subApp == null && fieldMapApp != null && taskman == null && mapReady && !paused

        var mode: GameMode
        var detail: String? = null
        var engagedTrainer: Int? = null
        var startMenu: StartMenuInfo? = null
        var app: AppInfo? = null
        var battle: BattleInfo? = null
        var battleSystem: Long? = null
        var battleSetup: Long? = null

        if (subApp != null) {
            val appInit = mem.fn(subApp + S.OM_INIT)
            val appOvy = mem.s32(subApp + A.OM_OVY_ID)
            val name = v.appByInit[appInit] ?: A.APP_BY_OVERLAY[appOvy] ?: "app(ovy=$appOvy,init=0x${appInit.toString(16)})"
            if (name == "battle") {
                mode = GameMode.BATTLE
                battleSetup = mem.ptr(subApp + S.OM_ARGS)
                val procState = mem.s32(subApp + S.OM_PROC_STATE)
                val appExec = mem.s32(subApp + S.OM_EXEC_STATE)
                if (appExec == 2 && procState == A.BATTLE_STATE_MAIN) {
                    battleSystem = mem.ptr(subApp + S.OM_DATA)
                    battle = battleSystem?.let { readBattle(ctx, it) }
                    detail = "battle"
                } else {
                    detail = "battle_transition(state=$procState)"
                }
            } else {
                mode = GameMode.APP
                detail = name
                app = runCatching { readApp(subApp, name, mem.ptr(v.saveDataPtr)) }.getOrNull()
            }
        } else if (taskman != null) {
            val tasks = mem.fieldTaskStack(fs)
            val scriptEnv = tasks.firstNotNullOfOrNull { (_, env) -> env?.takeIf { mem.u32(it + S.SM_MAGIC) == S.SCRIPT_MANAGER_MAGIC } }
            val startMenuTask = tasks.firstOrNull { (func, _) -> func == v.fnTaskStartMenu }
            val followerTask = tasks.firstOrNull()?.function == v.fnTaskFollowMonInteract
            when {
                // After the Hall of Fame (its app has ended): the game saves, then fades out to the credits.
                tasks.any { (func, _) -> func == v.fnTaskGameClear } -> {
                    mode = GameMode.FIELD_BUSY
                    detail = HgssGameClear.SAVE_DETAIL
                }

                // Its message (outside the script environment) is decoded by HgssTextScreens.
                followerTask -> {
                    mode = GameMode.SCRIPT
                    detail = FOLLOWER_DETAIL
                }

                // Its message boxes and menus are decoded by HgssTextScreens (HgssScriptScreens).
                scriptEnv != null -> {
                    mode = GameMode.SCRIPT
                    engagedTrainer = engagedTrainer(scriptEnv)
                }

                startMenuTask != null -> {
                    mode = GameMode.START_MENU
                    startMenu = startMenuTask.env?.let { readStartMenu(fs, it) }
                }

                else -> {
                    mode = GameMode.FIELD_BUSY
                    detail = when (tasks.firstOrNull()?.function) {
                        v.fnTaskWildEncounter -> "wild_encounter_start"
                        else -> "task=0x${tasks.firstOrNull()?.function?.toString(16)}"
                    }
                }
            }
        } else if (fieldMapApp != null && mapReady && !paused) {
            mode = GameMode.OVERWORLD
        } else {
            mode = GameMode.FIELD_BUSY
            detail = "map_not_ready"
        }

        val saveData = mem.ptr(v.saveDataPtr) ?: mem.ptr(fs + A.FS_SAVE_DATA)
        val player = saveData?.let { runCatching { readPlayer(it) }.getOrNull() }
        // In battle the game works on its own copy of the party (BattleSystem.trainerParty[0], same slot order as the
        // save, values updated live: HP, status, PP); the save's party is only written back when the battle ends.
        // The catching demo (BATTLE_TYPE_TUTORIAL) battles with Lyra's party and bag: the player's are the save's.
        val demo = battle?.battleTypeFlags?.contains("TUTORIAL") == true ||
            battleSetup?.let { mem.u32(it + A.SETUP_BATTLE_TYPE) and A.BATTLE_TYPE_TUTORIAL != 0L } == true
        val battleParty = if (battle != null && !demo) battleSystem?.let { bs -> mem.ptr(bs + A.BS_TRAINER_PARTY)?.let { runCatching { readPartyAt(ctx, it) }.getOrNull() } } else null
        // Outside the battle proper (intro, end of battle, evolutions after it) the battle app's setup holds the party
        // the game works on; the save's is only updated when the app ends.
        val setupParty = if (battleParty.isNullOrEmpty() && !demo) battleSetup?.let { mem.ptr(it + A.SETUP_PARTY) }?.let { runCatching { readPartyAt(ctx, it) }.getOrNull() } else null
        val copiedParty = battleParty?.takeIf { it.isNotEmpty() } ?: setupParty?.takeIf { it.isNotEmpty() }
        val saveParty = saveData?.let { runCatching { readParty(ctx, it) }.getOrNull() }
        val party = copiedParty ?: saveParty ?: emptyList()
        // Same for the bag: balls thrown and items used in battle come out of the setup's copy.
        val bag = (if (demo) null else battleSystem?.let { mem.ptr(it + A.BS_BAG) } ?: battleSetup?.let { mem.ptr(it + A.SETUP_BAG) })
            ?.let { runCatching { readBagAt(it) }.getOrNull() }
            ?: saveData?.let { runCatching { readBag(it) }.getOrNull() }
        val registered = saveData?.let { sd -> saveArray(sd, A.SAVE_BAG) }
            ?.let { b -> (0 until 2).map { mem.u16(b + A.BAG_REGISTERED_ITEMS + 2L * it) } }.orEmpty()
        val story = saveData?.let { runCatching { readStory(it) }.getOrNull() }

        // The overworld structures (map loader, map objects) are only alive while the field map app runs.
        val fieldAlive = subApp == null && fieldMapApp != null
        val location = runCatching { readLocation(ctx, fs, fieldAlive) }.getOrNull()
        val surroundings = if (fieldAlive && mapReady) runCatching { readSurroundings(ctx, fs, saveData) }.getOrNull() else null

        val fading = mem.fading
        val scenePending = mode == GameMode.OVERWORLD && sceneScriptPending(fs, saveData)
        val awaiting = !fading && when (mode) {
            GameMode.OVERWORLD -> playerControllable && location?.moving == false && !scenePending
            GameMode.START_MENU -> startMenu?.waiting == true
            GameMode.APP -> app?.waiting ?: (subApp != null && mem.s32(subApp + S.OM_EXEC_STATE) == S.OM_EXEC_MAIN)
            GameMode.BATTLE -> battle?.awaitingInput == true
            else -> false
        }
        return HgssState(
            frame = frame,
            mode = mode,
            modeDetail = detail,
            playerControllable = playerControllable,
            awaitingInput = awaiting,
            fading = fading,
            scenePending = scenePending,
            player = player,
            location = location,
            engagedTrainer = engagedTrainer,
            startMenu = startMenu,
            app = app,
            story = story,
            party = party,
            battle = battle,
            surroundings = surroundings,
            bag = bag,
            registeredItems = registered,
            warnings = ctx.warnings,
            partyBeforeWriteBack = if (copiedParty != null) saveParty else null,
        )
    }

    /**
     * True when a map scene script is about to start: the game checks the map's "on frame" table (var == value)
     * before reading the player's input (src/fieldmap.c GetMapSceneScriptId, FieldInput_Process), e.g. Mom's
     * scene right after arriving on the first floor.
     */
    private fun sceneScriptPending(fs: Long, saveData: Long?): Boolean {
        if (mem.u32(fs + A.FS_SCRIPTS_DISABLED) != 0L) return false
        val header = mem.ptr(fs + A.FS_MAP_EVENTS)?.let { it + A.ME_SCRIPT_HEADER } ?: return false
        var p = header
        var table: Long? = null
        while (p < header + 0x100) {
            val type = mem.u8(p)
            if (type == 0) break
            if (type == 1) {
                val ofs = mem.u32(p + 1)
                if (ofs != 0L) table = p + 5 + ofs
                break
            }
            p += 5
        }
        var t = table ?: return false
        repeat(32) {
            val var1 = mem.u16(t)
            if (var1 == 0) return false
            val a = varGet(saveData, var1)
            val b = varGet(saveData, mem.u16(t + 2))
            if (a != null && a == b) return true
            t += 6
        }
        return false
    }

    // ================================================================================================
    // Script / start menu
    // ================================================================================================

    /**
     * The trainer who saw the player (`ScriptEnvironment.engagedTrainers[0].trainerId`, set when a trainer's sight
     * catches the player; its approach and intro script run), null when none.
     */
    private fun engagedTrainer(env: Long): Int? = mem.s32(env + A.SE_ENGAGED_TRAINER_0_ID).takeIf { it in 1..MAX_TRAINER_ID }

    /** What we know about a full-screen app: at least its name; entries/cursor/prompt for the ones we decode. */
    private fun readApp(om: Long, name: String, saveData: Long?): AppInfo {
        val running = mem.s32(om + S.OM_EXEC_STATE) == S.OM_EXEC_MAIN
        val data = mem.ptr(om + S.OM_DATA)
        return when (name) {
            "choose_starter" -> {
                val work = data ?: return AppInfo(name, "Starter selection")
                // D-pad order: RIGHT turns the machine 0 -> 2 -> 1 (Chikorita -> Totodile -> Cyndaquil).
                val order = listOf(0, 2, 1)
                val labels = listOf("CHIKORITA (Grass)", "CYNDAQUIL (Fire)", "TOTODILE (Water)")
                val sel = mem.u32(work + A.CS_CUR_SELECTION).toInt().takeIf { it in 0..2 }
                val front = sel?.let { labels[it].substringBefore(' ') }
                val prompt = when (mem.u32(work + A.CS_SELECT_STATE).toInt()) {
                    0 -> "Prof. Elm: pick a Poké Ball. A looks at the ball in front, LEFT/RIGHT turn the machine to the next ball."
                    1 -> "The ball in front holds $front. A chooses it (Elm then asks to confirm), LEFT/RIGHT turn to another ball."
                    2 -> "Prof. Elm asks: do you want $front? A = yes, take it; B = no, look again."
                    else -> null
                }
                AppInfo(
                    name, "Starter selection (Prof. Elm's machine)", order.map { labels[it] }, sel?.let { order.indexOf(it) },
                    layout = "horizontal", prompt = prompt, waiting = running && mem.s32(om + S.OM_PROC_STATE) == A.CS_PROC_HANDLE_INPUT,
                )
            }
            // Only the whole team's wait takes input; the rest of the sequence plays by itself.
            "hall_of_fame_register" -> AppInfo(
                name, "Hall of Fame",
                waiting = running && data != null && HgssGameClear.registration(mem, data)?.waitsForButton == true,
            )
            // Only "The End" waits for input (the credits themselves can at most be skipped, and only on a replay).
            "credits" -> AppInfo(
                name, "Credits",
                waiting = !mem.fading && HgssGameClear.credits(mem, om)?.stage == HgssGameClear.CreditsStage.THE_END,
            )
            "mailbox" -> {
                val slots = (0 until 10).map { i ->
                    val mail = saveData?.let { saveArray(it, A.SAVE_MAILBOX) }?.let { it + i * A.MAIL_SIZE }
                    if (mail == null || mem.u8(mail + A.MAIL_TYPE) == 0xFF) "-"
                    else "mail from " + Gen4Text.decode(mem.chars(mail + A.MAIL_AUTHOR, 8))
                }
                // CANCEL is the bottom-right button: a "-" placeholder keeps the 2-column row-major layout.
                val entries = slots + "-" + "CANCEL"
                val next = data?.let { mem.ptr(it + A.MAILBOX_INNER) }?.let { mem.ptr(it + A.MAILBOX_GRID_INPUT) }?.let { mem.u8(it + A.GRID_INPUT_NEXT) }
                val cursor = when (next) {
                    in 0..9 -> next
                    10 -> 11
                    else -> null
                }
                AppInfo(
                    name, "PC Mailbox", entries, cursor, layout = "grid2",
                    prompt = "stored mail in 2 columns (\"-\" = empty slot): D-pad moves, A opens the selected mail's menu, B or CANCEL closes the Mailbox",
                    waiting = running && !mem.fading,
                )
            }
            else -> AppInfo(name, name.replace('_', ' '), waiting = running)
        }
    }

    private fun readStartMenu(fs: Long, env: Long): StartMenuInfo? {
        val n = mem.u32(env + A.SM_NUM_BUTTONS).toInt()
        if (n !in 1..10) return null
        // The last two entries are the fixed Pokégear touch buttons (actions 9 and 10), not grid icons.
        val actions = (0 until n).map { i -> mem.u8(env + A.SM_SELECTION_TO_ACTION + i) }
        val grid = arrayOfNulls<String>(8)
        val gridIds = arrayOfNulls<String>(8)
        val slotOfIndex = mutableMapOf<Int, Int>()
        actions.forEachIndexed { i, action ->
            if (i >= n - 2) return@forEachIndexed
            val icon = A.START_MENU_ICON_OF_ACTION[action] ?: return@forEachIndexed
            val label = if (action == A.START_MENU_ACTION_TRAINER_CARD) "TRAINER CARD" else A.START_MENU_LABELS.getOrElse(action) { "?" }
            grid[icon] = label
            gridIds[icon] = A.START_MENU_IDS.getOrElse(action) { "action$action" }
            slotOfIndex[i] = icon
        }
        // Grid slot (icon index) -> row = icon % 4, column = icon / 4; list row by row, dropping empty rows.
        val rows = (0 until 4).filter { r -> grid[r] != null || grid[r + 4] != null }
        val items = rows.flatMap { r -> listOf(grid[r] ?: "-", grid[r + 4] ?: "-") }
        val ids = rows.flatMap { r -> listOf(gridIds[r], gridIds[r + 4]) }
        val cursorIndex = mem.u8(fs + A.FS_START_MENU_CURSOR)
        val cursor = slotOfIndex[cursorIndex]?.let { icon -> rows.indexOf(icon % 4).takeIf { it >= 0 }?.let { it * 2 + icon / 4 } }
        return StartMenuInfo(
            items, cursor, waiting = mem.u16(env + A.SM_STATE) == A.SM_STATE_HANDLE_INPUT, ids = ids,
            leftColumn = (0 until 4).mapNotNull { grid[it] }, rightColumn = (4 until 8).mapNotNull { grid[it] },
        )
    }

    // ================================================================================================
    // Save data: player, party, bag
    // ================================================================================================

    /** Address of save array [id] (SaveArray_Get, src/save.c:128), or null. */
    private fun saveArray(saveData: Long, id: Int): Long? =
        dev.kotlinds.pokemonclient.games.gen4.Gen4SaveData(mem, saveData, A.SAVE_LAYOUT).table(id)

    private val badgeNames = listOf(
        "Zephyr", "Hive", "Plain", "Fog", "Storm", "Mineral", "Glacier", "Rising",
        "Boulder", "Cascade", "Thunder", "Rainbow", "Soul", "Marsh", "Volcano", "Earth",
    )

    private fun readPlayer(saveData: Long): PlayerInfo? {
        val pd = saveArray(saveData, A.SAVE_PLAYERDATA) ?: return null
        val prof = pd + A.PD_PROFILE
        val johto = mem.u8(prof + A.PP_JOHTO_BADGES)
        val kanto = mem.u8(prof + A.PP_KANTO_BADGES)
        val badges = (0 until 8).filter { johto shr it and 1 == 1 }.map { badgeNames[it] } +
            (0 until 8).filter { kanto shr it and 1 == 1 }.map { badgeNames[8 + it] }
        return PlayerInfo(
            name = Gen4Text.decode(mem.chars(prof + A.PP_NAME, 8)),
            gender = if (mem.u8(prof + A.PP_GENDER) == 0) "male" else "female",
            trainerId = mem.u32(prof + A.PP_ID) and 0xFFFF,
            money = mem.u32(prof + A.PP_MONEY),
            coins = mem.u16(pd + A.PD_COINS),
            badges = badges,
            badgeCount = badges.size,
            playTime = Triple(mem.u16(pd + A.PD_PLAY_TIME), mem.u8(pd + A.PD_PLAY_TIME + 2), mem.u8(pd + A.PD_PLAY_TIME + 3))
                .takeIf { (_, m, s) -> m < 60 && s < 60 },
            badgeIds = ((0 until 8).filter { johto shr it and 1 == 1 } + (0 until 8).filter { kanto shr it and 1 == 1 }.map { 8 + it }).toSet(),
            flyPoints = saveArray(saveData, A.SAVE_FLAGS)?.let(::flyPoints).orEmpty(),
        )
    }

    /** The fly points whose flag is set in the save's flags at [varsFlags] (`Save_VarsFlags_FlypointFlagAction`). */
    private fun flyPoints(varsFlags: Long): List<Int> =
        HgssFlyMapAddresses.FLYPOINTS.indices.filter { i ->
            val flag = HgssFlyMapAddresses.FLAG_FLYPOINT_FIRST + HgssFlyMapAddresses.FLYPOINTS[i].flag
            mem.u8(varsFlags + A.FLAGS_OFFSET + flag / 8) shr (flag % 8) and 1 == 1
        }

    private fun moveInfo(id: Int, pp: Int, maxPp: Int): MoveInfo {
        val d = HgssData.moveData[id]
        return MoveInfo(id, HgssData.moveName(id), pp, maxPp, d?.type, d?.category, d?.power, d?.accuracy)
    }

    /** Raw bytes of the save's party slots (0xEC each), for diagnostics and fixtures. */
    fun partyRaw(): List<ByteArray> {
        val saveData = mem.ptr(v.saveDataPtr) ?: return emptyList()
        val party = saveArray(saveData, A.SAVE_PARTY) ?: return emptyList()
        val count = mem.s32(party + S.PARTY_CUR_COUNT).takeIf { it in 0..6 } ?: return emptyList()
        return (0 until count).mapNotNull { mem.bytes(party + S.PARTY_MONS + it * S.POKEMON_SIZE, S.POKEMON_SIZE.toInt()) }
    }

    /** Raw bytes of the slots of PC box [box] (0x88 each), for diagnostics. */
    fun boxRaw(box: Int): List<ByteArray> {
        val saveData = mem.ptr(v.saveDataPtr) ?: return emptyList()
        val storage = saveArray(saveData, A.SAVE_PC_STORAGE) ?: return emptyList()
        val k = HgssKeyboardPcShopAddresses
        return (0 until k.BOX_SLOTS).mapNotNull { mem.bytes(storage + box * k.PCS_BOX_STRIDE + it * k.BOX_MON_SIZE, k.BOX_MON_SIZE) }
    }

    private fun readParty(ctx: Ctx, saveData: Long): List<PartyMon> {
        val party = saveArray(saveData, A.SAVE_PARTY) ?: return emptyList()
        return readPartyAt(ctx, party)
    }

    /** Reads a `Party` struct (the save party, or a battle party copy). */
    private fun readPartyAt(ctx: Ctx, party: Long): List<PartyMon> {
        val count = mem.s32(party + S.PARTY_CUR_COUNT)
        if (count !in 0..6) {
            ctx.warnings += "party count $count out of range"
            return emptyList()
        }
        return (0 until count).mapNotNull { i ->
            val raw = mem.bytes(party + S.PARTY_MONS + i * S.POKEMON_SIZE, S.POKEMON_SIZE.toInt()) ?: return@mapNotNull null
            // Every way the structure can be while the game rewrites it is tried; problems name what is still wrong.
            val mon = Gen4Pokemon.decode(raw, HgssMonCheck::isPlausible) ?: return@mapNotNull null
            toPartyMon(i, mon).copy(problems = HgssMonCheck.problems(mon))
        }
    }

    private fun toPartyMon(slot: Int, mon: Gen4Pokemon.Decoded): PartyMon {
        val species = mon.species
        val moves = (0 until 4).mapNotNull { m ->
            val id = mon.move(m)
            if (id == 0) null else moveInfo(id, mon.movePp(m), HgssData.maxPp(id, mon.movePpUps(m)))
        }
        val nickname = Gen4Text.decode(mon.nicknameChars)
        return PartyMon(
            slot = slot,
            personality = mon.personality,
            otId = mon.otId,
            heldItemId = mon.heldItem,
            statusRaw = mon.status,
            species = species,
            speciesName = HgssData.speciesName(species),
            nickname = nickname.takeIf { it.isNotEmpty() },
            level = mon.level,
            hp = mon.hp,
            maxHp = mon.maxHp,
            status = if (mon.isEgg) "EGG" else Gen4Pokemon.statusName(mon.status, mon.hp),
            types = HgssData.speciesTypes(species),
            heldItem = mon.heldItem.takeIf { it != 0 }?.let { HgssData.itemName(it) },
            ability = mon.ability.takeIf { it != 0 }?.let { HgssData.abilityName(it) },
            isEgg = mon.isEgg,
            moves = moves,
            stats = mapOf("atk" to mon.atk, "def" to mon.def, "speed" to mon.speed, "spAtk" to mon.spAtk, "spDef" to mon.spDef),
            exp = mon.exp,
            friendship = mon.friendship,
            checksumOk = mon.checksumOk,
        )
    }

    private fun readBag(saveData: Long): List<BagPocket>? = saveArray(saveData, A.SAVE_BAG)?.let(::readBagAt)

    /** Reads a `Bag` struct (the save's, or the battle's copy). */
    private fun readBagAt(bag: Long): List<BagPocket>? {
        if (!mem.inRam(bag, A.BAG_REGISTERED_ITEMS + 4)) return null
        return A.BAG_POCKETS.map { (name, off, n) ->
            val items = (0 until n).mapNotNull { i ->
                val id = mem.u16(bag + off + 4L * i)
                val qty = mem.u16(bag + off + 4L * i + 2)
                if (id == 0 || qty == 0) null else BagItem(id, HgssData.itemName(id), qty)
            }
            BagPocket(name, items)
        }
    }

    /**
     * Story flags/vars listed in [HgssProgress] and needed by [HgssStoryTable] (its steps and curated blockers)
     * (SaveVarsFlags, src/save_vars_flags.c), with the badges (PlayerProfile).
     */
    private fun readStory(saveData: Long): StoryInfo? {
        val vf = saveArray(saveData, A.SAVE_FLAGS) ?: return null
        val flags = STORY_FLAGS.filter { id -> mem.u8(vf + A.FLAGS_OFFSET + id / 8) shr (id % 8) and 1 == 1 }.toSet()
        val vars = STORY_VARS.associateWith { id -> mem.u16(vf + 2L * (id - A.VAR_BASE)) }
        val badges = saveArray(saveData, A.SAVE_PLAYERDATA)?.let { pd ->
            val prof = pd + A.PD_PROFILE
            val johto = mem.u8(prof + A.PP_JOHTO_BADGES)
            val kanto = mem.u8(prof + A.PP_KANTO_BADGES)
            (0 until 8).filter { johto shr it and 1 == 1 }.toSet() + (0 until 8).filter { kanto shr it and 1 == 1 }.map { it + 8 }
        } ?: emptySet()
        return StoryInfo(flags, vars, badges)
    }

    /** The value of script variable [varId] right now (save vars; ids below 0x4000 are literal values), or null. */
    fun variable(varId: Int): Int? = try {
        resolveVersion().first?.let { resolved ->
            v = resolved
            varGet(mem.ptr(v.saveDataPtr), varId)
        }
    } catch (_: Exception) {
        null
    }

    /** Event flag [flagId] right now (save flags, SaveVarsFlags.flags, src/save_vars_flags.c), or null. */
    fun flag(flagId: Int): Boolean? = try {
        resolveVersion().first?.let { resolved ->
            v = resolved
            if (flagId !in 0 until A.NUM_SAVE_FLAGS) return@let null
            mem.ptr(v.saveDataPtr)?.let { sd -> saveArray(sd, A.SAVE_FLAGS) }?.let { vf -> mem.u8(vf + A.FLAGS_OFFSET + flagId / 8) shr (flagId % 8) and 1 == 1 }
        }
    } catch (_: Exception) {
        null
    }

    /** Every event flag of the save ([flag]'s array, `SaveVarsFlags.flags`) in one read ([dev.kotlinds.pokemonclient.games.gen4.Gen4SaveData.eventFlags]), or null. */
    fun eventFlags(): dev.kotlinds.pokemonclient.state.EventFlags? = try {
        resolveVersion().first?.let { resolved ->
            v = resolved
            mem.ptr(v.saveDataPtr)?.let { sd -> dev.kotlinds.pokemonclient.games.gen4.Gen4SaveData(mem, sd, A.SAVE_LAYOUT).eventFlags() }
        }
    } catch (_: Exception) {
        null
    }

    /**
     * The gym puzzle slot of the save (`Gymmick`, include/gymmick.h, in SAVE_MISC): u32 type then 0x20 bytes of
     * data, set up when entering a puzzle gym and cleared on map change (src/field_warp_tasks.c:185). Null when
     * unreadable.
     */
    fun gymmick(): ByteArray? = try {
        resolveVersion().first?.let { resolved ->
            v = resolved
            mem.ptr(v.saveDataPtr)?.let { sd -> saveArray(sd, A.SAVE_MISC) }?.let { misc -> mem.bytes(misc + A.MISC_GYMMICK, A.GYMMICK_SIZE) }
        }
    } catch (_: Exception) {
        null
    }

    /** Script var value (FieldSystem_VarGet, src/fieldmap.c:365) — only save vars are supported. */
    private fun varGet(saveData: Long?, varId: Int): Int? = when {
        varId < A.VAR_BASE -> varId
        varId < A.VAR_BASE + A.NUM_VARS -> saveData?.let { sd -> saveArray(sd, A.SAVE_FLAGS)?.let { mem.u16(it + 2L * (varId - A.VAR_BASE)) } }
        else -> null
    }

    // ================================================================================================
    // Location / player avatar
    // ================================================================================================

    private fun playerMapObject(fs: Long): Long? = mem.ptr(fs + A.FS_PLAYER_AVATAR)?.let { mem.ptr(it + S.PA_MAP_OBJECT) }

    private fun readLocation(ctx: Ctx, fs: Long, fieldAlive: Boolean): LocationInfo? {
        val loc = mem.ptr(fs + A.FS_LOCATION) ?: return null
        val avatar = mem.ptr(fs + A.FS_PLAYER_AVATAR)
        // The live map object only while the field map runs; the saved Location otherwise.
        val mo = avatar?.let { mem.ptr(it + S.PA_MAP_OBJECT) }?.takeIf { fieldAlive }
        val position = mem.playerPosition(loc, mo) ?: run {
            ctx.warnings += "map id ${mem.s32(loc + S.LOC_MAP_ID)} out of range"
            return null
        }
        val (mapId, x, z, height, facing, moving) = position
        val state = avatar?.let { mem.s32(it + A.PA_STATE) }
        val shoes = avatar?.let { mem.ptr(it + A.PA_PLAYER_SAVE_DATA) }?.let { mem.u16(it) != 0 }

        var standing: String? = null
        var facingTile: String? = null
        var facingBlocked: Boolean? = null
        if (fieldAlive && mem.u32(fs + A.FS_MAP_READY) != 0L) {
            val tiles = TileReader(fs)
            tiles.attr(x, z)?.let { standing = HgssData.tileBehaviorNames.getOrNull(it and 0xFF) }
            facing?.let { tiles.attr(x + it.dx, z + it.dy) }?.let {
                facingTile = HgssData.tileBehaviorNames.getOrNull(it and 0xFF)
                facingBlocked = it and A.TILE_COLLISION_BIT != 0
            }
        }
        return LocationInfo(
            mapId = mapId,
            x = x,
            z = z,
            height = height,
            facing = facing?.name?.lowercase() ?: "?",
            moving = moving,
            avatarState = state?.let { A.PLAYER_STATES.getOrElse(it) { "STATE_$it" } } ?: "UNKNOWN",
            hasRunningShoes = shoes,
            standingOn = standing,
            facingTile = facingTile,
            facingTileBlocked = facingBlocked,
        )
    }

    // ================================================================================================
    // Tiles (collision + behavior)
    // ================================================================================================

    /**
     * Resolves the u16 terrain attribute of a global tile, the same way the game does
     * (GetMetatileBehavior -> FieldSystem.unk60 accessor, asm/unk_02054648.s).
     */
    private inner class TileReader(fs: Long) {
        private val accessor = mem.u32(fs + A.FS_TERRAIN_ACCESSOR)
        private val loader = mem.ptr(fs + A.FS_MAP_LOADER)
        private val terrain = mem.ptr(fs + A.FS_TERRAIN_ATTRIBUTES, 2)
        private val matrix = mem.ptr(fs + A.FS_MAP_MATRIX, 2)

        val width: Int
        val height: Int
        private val slots = LongArray(A.ML_SLOT_COUNT)
        private val slotBlocks = IntArray(A.ML_SLOT_COUNT) { -1 }

        init {
            var w = 0
            var h = 0
            if (accessor == v.terrainAccessorLoader && loader != null) {
                w = mem.s32(loader + A.ML_MATRIX_WIDTH)
                h = mem.s32(loader + A.ML_MATRIX_HEIGHT)
                for (i in 0 until A.ML_SLOT_COUNT) {
                    val buf = mem.ptr(loader + A.ML_BLOCK_BUFFERS + 4L * i) ?: continue
                    if (!mem.inRam(buf, A.ML_BUFFER_BLOCK_INDEX + 4)) continue
                    slots[i] = buf
                    slotBlocks[i] = mem.s32(buf + A.ML_BUFFER_BLOCK_INDEX)
                }
            } else if (accessor == v.terrainAccessorTerrainAttributes && matrix != null) {
                w = mem.u8(matrix + A.MM_WIDTH)
                h = mem.u8(matrix + A.MM_HEIGHT)
            }
            if (w !in 1..255 || h !in 1..255) {
                w = 0; h = 0
            }
            width = w
            height = h
        }

        /** Returns the attribute (bit15 collision, low byte behavior) or null if unknown / not loaded. */
        fun attr(x: Int, z: Int): Int? {
            if (x < 0 || z < 0 || width == 0) return null
            val bx = x / A.BLOCK_TILES
            val bz = z / A.BLOCK_TILES
            if (bx >= width || bz >= height) return null
            val block = bz * width + bx
            val tile = (z % A.BLOCK_TILES) * A.BLOCK_TILES + (x % A.BLOCK_TILES)
            if (accessor == v.terrainAccessorLoader) {
                for (i in 0 until A.ML_SLOT_COUNT) {
                    if (slots[i] != 0L && slotBlocks[i] == block) return mem.u16(slots[i] + 2L * tile)
                }
                return null
            }
            val ta = terrain ?: return null
            if (block >= A.TA_MAX_MATRIX) return null
            val idx = mem.u8(ta + block)
            if (idx >= A.TA_MAX_BLOCKS) return null
            return mem.u16(ta + A.TA_ATTRS + 2L * (idx * 1024 + tile))
        }
    }

    // ================================================================================================
    // Surroundings: map objects, events, grid
    // ================================================================================================

    private fun readSurroundings(ctx: Ctx, fs: Long, saveData: Long?): Surroundings? {
        val playerMo = playerMapObject(fs) ?: return null
        val px = mem.s32(playerMo + S.MO_X)
        val pz = mem.s32(playerMo + S.MO_Z)
        val mapId = mem.ptr(fs + A.FS_LOCATION)?.let { mem.s32(it + S.LOC_MAP_ID) } ?: -1
        val mapType = HgssData.mapType(mapId)
        val interior = mapType == "INTERIOR"
        val tiles = TileReader(fs)
        val objects = readMapObjects(fs, playerMo, px, pz)
        val (bgs, triggers) = readEvents(ctx, fs, saveData, mapId, tiles)
        return Surroundings(
            matrixWidth = tiles.width.takeIf { it > 0 },
            matrixHeight = tiles.height.takeIf { it > 0 },
            mapType = mapType,
            grid = buildArea(tiles, px, pz, interior),
            objects = objects,
            bgEvents = bgs,
            triggers = triggers,
        )
    }

    private fun readMapObjects(fs: Long, playerMo: Long, px: Int, pz: Int): List<MapObjectInfo> {
        val mom = mem.ptr(fs + A.FS_MAP_OBJECT_MANAGER) ?: return emptyList()
        val count = mem.u32(mom + A.MOM_OBJECT_COUNT).toInt()
        val objs = mem.ptr(mom + A.MOM_OBJECTS) ?: return emptyList()
        if (count !in 1..128 || !mem.inRam(objs, count * A.MO_SIZE)) return emptyList()
        val follower = mem.ptr(fs + A.FS_FOLLOW_MON + A.FOLLOW_MON_MAP_OBJECT)
        val out = mutableListOf<MapObjectInfo>()
        for (i in 0 until count) {
            val o = objs + i * A.MO_SIZE
            if (o == playerMo) continue
            val flags = mem.u32(o + A.MO_FLAGS)
            if (flags and A.MO_FLAG_ACTIVE == 0L) continue
            val sprite = mem.s32(o + A.MO_SPRITE_ID)
            if (sprite == A.SPRITE_CAMERA_FOCUS) continue
            val spriteName = HgssData.spriteName(sprite)
            val x = mem.s32(o + S.MO_X)
            val z = mem.s32(o + S.MO_Z)
            val kind = when {
                o == follower -> "follower"
                spriteName == "MONSTARBALL" -> "item_ball"
                spriteName in OBSTACLE_SPRITES -> "obstacle"
                else -> "npc"
            }
            // An object of the zone just left, kept on screen across a map connection, is re-tagged with the new zone:
            // script 0xFFFF, and its old zone in the event flag (sub_0205F058, src/map_object.c).
            val script = mem.s32(o + A.MO_SCRIPT_ID)
            val carried = script == A.MO_SCRIPT_CARRIED
            out += MapObjectInfo(
                id = mem.s32(o + S.MO_LOCAL_ID),
                sprite = spriteName,
                x = x,
                z = z,
                dx = x - px,
                dz = z - pz,
                height = mem.s32(o + S.MO_Y),
                facing = S.DIRECTIONS.getOrNull(mem.s32(o + S.MO_FACING))?.name?.lowercase() ?: "?",
                movement = mem.s32(o + A.MO_MOVEMENT),
                type = mem.s32(o + A.MO_TYPE),
                scriptId = mem.s32(o + A.MO_SCRIPT_ID),
                hidden = flags and A.MO_FLAG_HIDDEN != 0L,
                kind = kind,
                label = if (kind == "follower") "your Pokémon (following you)" else HgssLabels.person(spriteName),
                mapId = if (carried) mem.s32(o + A.MO_EVENT_FLAG) else mem.s32(o + A.MO_MAP_ID),
                eventFlag = if (carried) 0 else mem.s32(o + A.MO_EVENT_FLAG),
                param0 = mem.s32(o + A.MO_PARAM0),
            )
        }
        return HgssLabels.bigSpriteParts(out).sortedBy { Math.abs(it.dx) + Math.abs(it.dz) }
    }

    private fun readEvents(ctx: Ctx, fs: Long, saveData: Long?, mapId: Int, tiles: TileReader): Pair<List<BgEventInfo>, List<TriggerInfo>> {
        val me = mem.ptr(fs + A.FS_MAP_EVENTS) ?: return Pair(emptyList(), emptyList())
        fun count(off: Long) = mem.u32(me + off).toInt().takeIf { it in 0..256 } ?: 0.also { ctx.warnings += "bad event count" }

        val bgs = mem.ptr(me + A.ME_BG, 2)?.let { base ->
            (0 until count(A.ME_NUM_BG)).map { i ->
                val b = base + i * A.BG_SIZE
                val x = mem.s32(b + A.BG_X)
                val z = mem.s32(b + A.BG_Z)
                val type = when (mem.u16(b + A.BG_TYPE)) {
                    0 -> "normal"
                    1 -> "sign"
                    2 -> "hidden_item"
                    else -> "type${mem.u16(b + A.BG_TYPE)}"
                }
                val attr = tiles.attr(x, z)
                BgEventInfo(x, z, type, mem.u16(b + A.BG_SCRIPT), blocked = attr != null && attr and A.TILE_COLLISION_BIT != 0)
            }
        } ?: emptyList()

        // Triggers whose script ends silently in some story state (world Trigger.quietWhen, from the ROM's scripts).
        val quietWhen = HgssData.world?.areaOf(mapId)?.triggers.orEmpty().filter { it.zone == mapId }.associate { it.id to it.quietWhen }
        val triggers = mem.ptr(me + A.ME_COORD, 2)?.let { base ->
            (0 until count(A.ME_NUM_COORD)).map { i ->
                val c = base + i * A.COORD_SIZE
                val variable = mem.u16(c + A.COORD_VAR)
                val value = mem.u16(c + A.COORD_VAL)
                val varValue = varGet(saveData, variable)
                TriggerInfo(
                    index = i,
                    x = mem.s16(c + A.COORD_X), z = mem.s16(c + A.COORD_Z),
                    width = mem.u16(c + A.COORD_W), height = mem.u16(c + A.COORD_H),
                    scriptId = mem.u16(c + A.COORD_SCRIPT),
                    active = varValue?.let { it == value },
                    variable = variable,
                    value = value,
                    quiet = quietWhen[i]?.let { flag(it.flag) == it.set } == true,
                )
            }
        } ?: emptyList()
        return Pair(bgs, triggers)
    }

    private companion object {
        /** Half size of the area read around the player (the loaded blocks never reach further). */
        const val AREA_HALF_SIZE = 32

        /** [HgssState.modeDetail] while talking to the following Pokémon (Task_FollowMonInteract). */
        const val FOLLOWER_DETAIL = "follower"

        /** Highest NPC trainer id (trdata has 737 records in HG/SS): beyond, the field is garbage. */
        const val MAX_TRAINER_ID = 1000

        /** Map objects that are things, not people: Cut trees, Rock Smash rocks, boulders. */
        val OBSTACLE_SPRITES = setOf("TREE", "ROCK", "BREAKROCK", "ICE")

        /** Every story flag read into [StoryInfo]: the early-game ones and those of the story table. */
        val STORY_FLAGS: List<Int> by lazy { (HgssProgress.FLAGS + HgssStoryTable.flagIds).distinct().sorted() }

        /** Every story var read into [StoryInfo]. */
        val STORY_VARS: List<Int> by lazy { (HgssProgress.VARS + HgssStoryTable.varIds).distinct().sorted() }
    }

    private val bJumpEast by lazy { HgssData.behaviorId("JUMP_EAST") }
    private val bJumpWest by lazy { HgssData.behaviorId("JUMP_WEST") }
    private val bJumpNorth by lazy { HgssData.behaviorId("JUMP_NORTH") }
    private val bJumpSouth by lazy { HgssData.behaviorId("JUMP_SOUTH") }
    private val grassBehaviors by lazy {
        HgssData.tileBehaviorNames.withIndex().filter { (i, name) ->
            HgssData.tileBehaviorFlags[i] and 2 != 0 && "CAVE" !in name
        }.map { it.index }.toSet()
    }

    private fun terrainChar(attr: Int): Char {
        if (attr < 0) return '-'
        val b = attr and 0xFF
        return when {
            b == bJumpSouth -> '_'
            b == bJumpNorth -> '='
            b == bJumpWest -> '{'
            b == bJumpEast -> '}'
            HgssData.tileBehaviorFlags[b] and 1 != 0 -> '~'
            attr and A.TILE_COLLISION_BIT != 0 -> '#'
            b in grassBehaviors -> '"'
            else -> '.'
        }
    }

    /**
     * The terrain around the player, cropped to what is real.
     *
     * Rooms are 32x32 blocks where only the room itself is used: the walls have the collision bit and everything
     * beyond them is plain 0x0000, like a walkable floor. So the area reachable from the player (flood fill through
     * tiles without collision, ignoring people) is computed over the loaded blocks; indoors, tiles that are neither
     * reachable nor next to a reachable tile are outside the room ('-'). Tiles outside the matrix or not loaded are
     * '-' too. The result is the window of [halfWidth]/[halfHeight] tiles around the player (by default the whole area
     * the game keeps loaded: the 2x2 blocks of 32x32 tiles around the player), cropped to the bounding box of the real
     * tiles.
     */
    private fun buildArea(tiles: TileReader, px: Int, pz: Int, interior: Boolean, halfWidth: Int = AREA_HALF_SIZE, halfHeight: Int = AREA_HALF_SIZE): LocalGrid? {
        if (tiles.width == 0) return null
        val mw = tiles.width * A.BLOCK_TILES
        val mh = tiles.height * A.BLOCK_TILES
        val reach = A.BLOCK_TILES
        val x0 = maxOf(0, px - reach)
        val x1 = minOf(mw - 1, px + reach)
        val z0 = maxOf(0, pz - reach)
        val z1 = minOf(mh - 1, pz + reach)
        if (x1 < x0 || z1 < z0 || px !in x0..x1 || pz !in z0..z1) return null
        val w = x1 - x0 + 1
        val h = z1 - z0 + 1
        val attrs = IntArray(w * h) { i -> tiles.attr(x0 + i % w, z0 + i / w) ?: -1 }
        fun passable(i: Int) = attrs[i] >= 0 && attrs[i] and A.TILE_COLLISION_BIT == 0

        val reached = BooleanArray(w * h)
        val queue = ArrayDeque<Int>()
        fun seed(i: Int, force: Boolean = false) {
            if (!reached[i] && (force || passable(i))) {
                reached[i] = true
                queue.addLast(i)
            }
        }
        seed((pz - z0) * w + (px - x0), force = true)
        // Areas that continue beyond what we read (matrix continues past our window, or blocks not loaded).
        if (!interior) for (i in 0 until w * h) {
            val x = x0 + i % w
            val z = z0 + i / w
            val open = (x == x0 && x > 0) || (x == x1 && x < mw - 1) || (z == z0 && z > 0) || (z == z1 && z < mh - 1) ||
                listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).any { (dx, dz) ->
                    val nx = x + dx - x0
                    val nz = z + dz - z0
                    nx in 0 until w && nz in 0 until h && attrs[nz * w + nx] < 0
                }
            if (open) seed(i)
        }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i % w
            val z = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (z > 0) seed(i - w)
            if (z < h - 1) seed(i + w)
        }
        fun real(x: Int, z: Int): Boolean {
            val i = z * w + x
            if (attrs[i] < 0) return false
            if (!interior) return true
            for (dz in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val nz = z + dz
                if (nx in 0 until w && nz in 0 until h && reached[nz * w + nx]) return true
            }
            return false
        }

        // Window around the player, cropped to the real tiles.
        val wx0 = maxOf(x0, px - halfWidth) - x0
        val wx1 = minOf(x1, px + halfWidth) - x0
        val wz0 = maxOf(z0, pz - halfHeight) - z0
        val wz1 = minOf(z1, pz + halfHeight) - z0
        var cx0 = Int.MAX_VALUE
        var cx1 = Int.MIN_VALUE
        var cz0 = Int.MAX_VALUE
        var cz1 = Int.MIN_VALUE
        val isReal = Array(wz1 - wz0 + 1) { r -> BooleanArray(wx1 - wx0 + 1) { c -> real(wx0 + c, wz0 + r) } }
        for (r in isReal.indices) for (c in isReal[r].indices) if (isReal[r][c]) {
            cx0 = minOf(cx0, c); cx1 = maxOf(cx1, c); cz0 = minOf(cz0, r); cz1 = maxOf(cz1, r)
        }
        if (cx0 > cx1) return null
        val rows = (cz0..cz1).map { r ->
            buildString {
                for (c in cx0..cx1) append(if (isReal[r][c]) terrainChar(attrs[(wz0 + r) * w + wx0 + c]) else '-')
            }
        }
        return LocalGrid(x0 + wx0 + cx0, z0 + wz0 + cz0, cx1 - cx0 + 1, cz1 - cz0 + 1, rows)
    }

    // ================================================================================================
    // Battle
    // ================================================================================================

    private fun readBattle(ctx: Ctx, bs: Long): BattleInfo? {
        val battleCtx = mem.ptr(bs + A.BS_CTX) ?: return null
        val type = mem.u32(bs + A.BS_BATTLE_TYPE)
        val maxBattlers = mem.s32(bs + A.BS_MAX_BATTLERS)
        if (maxBattlers !in 2..4) {
            ctx.warnings += "battle: maxBattlers=$maxBattlers"
            return null
        }
        if (!mem.inRam(battleCtx, A.BC_BATTLE_MONS + 4 * A.BM_SIZE)) return null
        val flags = A.BATTLE_TYPE_FLAGS.filter { (bit, _) -> type shr bit and 1L == 1L }.map { it.second }
        val battlers = (0 until maxBattlers).map { id -> readBattler(battleCtx, id) }
        val trainers = if (type and 1L != 0L) {
            (0 until maxBattlers).filter { it % 2 == 1 }.mapNotNull { id ->
                val tid = mem.u16(bs + A.BS_TRAINER_ID + 2L * id)
                if (tid == 0) return@mapNotNull null
                val t = bs + A.BS_TRAINERS + id * A.TRAINER_SIZE
                TrainerInfo(id, tid, HgssData.trainerClassName(mem.u8(t + A.TRAINER_CLASS)), Gen4Text.decode(mem.chars(t + A.TRAINER_NAME, 8)))
            }.distinctBy { it.trainerId }
        } else emptyList()
        val input = mem.ptr(bs + A.BS_BATTLE_INPUT)
        val menuId = input?.let { mem.s8(it + A.BI_CUR_MENU_ID) }
        val menu = menuId?.let { A.BATTLE_MENUS[it] }
        val cursor = input?.let {
            if (mem.u8(it + A.BI_MENU_CURSOR) != 0) listOf(mem.s8(it + A.BI_MENU_CURSOR + 1), mem.s8(it + A.BI_MENU_CURSOR + 2)) else null
        }
        // battle_input.c / the player's battle controller: the menu is up, not sliding in, no button animation,
        // and (for the command/move/target menus) the engine is in the selection phase waiting for battler 0.
        // The battler's selection state must match the menu shown (1 command, 4 move, 6 target): right after FIGHT is
        // chosen the battler is already choosing a move while the main menu is still displayed.
        val battlerState = mem.u8(battleCtx + A.BC_BATTLER_STATE)
        val expectedState = when (menuId) {
            in 1..10 -> 1
            11 -> 4
            12 -> 6
            else -> null
        }
        // Bag (8) / party screen (10) opened from the battle menu: their own screens handle the input.
        val subScreen = if (mem.s32(battleCtx + A.BC_COMMAND) == A.BC_COMMAND_SELECTION) when (battlerState) {
            8 -> "BAG_SCREEN"
            10 -> "PARTY_SCREEN"
            else -> null
        } else null
        val awaiting = subScreen != null || input != null && menuId != null && menuId in 1..17 &&
            mem.u8(input + A.BI_TOUCH_DISABLED) == 0 && mem.u32(input + A.BI_FEEDBACK_TASK) == 0L && mem.u32(input + A.BI_UNK10_TASK) == 0L &&
            (menuId >= 13 || (mem.s32(battleCtx + A.BC_COMMAND) == A.BC_COMMAND_SELECTION && battlerState == expectedState))
        return BattleInfo(
            isWild = type and 1L == 0L,
            battleTypeFlags = flags,
            isDoubles = type and 2L != 0L,
            turn = mem.s32(battleCtx + A.BC_TOTAL_TURNS).takeIf { it in 0..10000 },
            partyOrder = (0 until 6).map { mem.u8(battleCtx + A.BC_PARTY_ORDER + it) }.takeIf { order -> order.sorted() == (0 until 6).toList() } ?: emptyList(),
            player = battlers.filter { it.side == "player" && it.species != 0 },
            opponents = battlers.filter { it.side == "opponent" && it.species != 0 },
            trainers = trainers,
            menu = subScreen ?: menu,
            menuCursor = if (subScreen != null) null else cursor,
            awaitingInput = awaiting,
            message = mem.gameString(mem.ptr(bs + A.BS_MSG_BUFFER)),
            safariBalls = if (type and (1L shl 5) != 0L) mem.s32(bs + A.BS_SAFARI_BALLS) else null,
            outcomeFlag = mem.u8(bs + A.BS_OUTCOME_FLAG),
            sidesOut = sidesOut(ctx, bs, battleCtx, type, battlers),
        )
    }

    /**
     * The sides (0 the player's, 1 the opponent's) left without any Pokémon able to battle, by the rule the game
     * applies once the faints are processed (ov12_0224D7EC, src/battle/battle_controller_player.c): a battler of the
     * side is at 0 HP and its party's HP add up to 0. Computed here because the game only sets its end flag after the
     * faint messages, while an agent's chain must already know the battle is decided (the last foe fainting with the
     * player's Pokémon by Destiny Bond: two faint messages before the flag). The Pokémon on the field count with their
     * battle HP (`BattleMon.hp`, live), the party copy being updated a little later (CopyBattleMonToPartyMon).
     * Multi and tag battles (a partner's party counts too) are left to the game's flag. A side whose party doesn't read
     * cleanly (being rewritten) isn't decided.
     */
    private fun sidesOut(ctx: Ctx, bs: Long, battleCtx: Long, type: Long, battlers: List<Battler>): Set<Int> {
        if (type and A.BATTLE_TYPE_PARTNER_PARTIES != 0L) return emptySet()
        return (0..1).filter { side ->
            val onField = battlers.filter { it.battlerId % 2 == side && it.species != 0 }
            // The game only looks at a side once one of its battlers is down (and an empty side isn't a battle yet).
            if (onField.none { it.hp == 0 }) return@filter false
            // A battler caught mid-rewrite may read 0 HP: no decision on such a reading.
            if (onField.any { HgssBattlerCheck.problems(it).isNotEmpty() }) return@filter false
            // BattleSystem_GetParty: trainerParty[battlerId & 1] in doubles, trainerParty[battlerId] in singles: the side's party either way.
            val partyPtr = mem.ptr(bs + A.BS_TRAINER_PARTY + 4L * side) ?: return@filter false
            val count = mem.s32(partyPtr + S.PARTY_CUR_COUNT)
            val party = runCatching { readPartyAt(ctx, partyPtr) }.getOrNull() ?: return@filter false
            if (party.size != count || party.any { it.problems.isNotEmpty() }) return@filter false
            val fieldHp = onField.mapNotNull { b -> b.partySlot?.let { it to b.hp } }.toMap()
            party.filter { it.species != 0 && !it.isEgg }.sumOf { fieldHp[it.slot] ?: it.hp } == 0
        }.toSet()
    }

    private fun readBattler(battleCtx: Long, id: Int): Battler {
        val m = battleCtx + A.BC_BATTLE_MONS + id * A.BM_SIZE
        val species = mem.u16(m + A.BM_SPECIES)
        val moves = (0 until 4).mapNotNull { i ->
            val mv = mem.u16(m + A.BM_MOVES + 2L * i)
            // BattleMon.movePP holds the PP Ups (src/battle/overlay_12_0224E4FC.c), not the max PP.
            if (mv == 0) null else moveInfo(mv, mem.u8(m + A.BM_PP_CUR + i), HgssData.maxPp(mv, mem.u8(m + A.BM_PP_MAX + i)))
        }
        val t1 = mem.u8(m + A.BM_TYPE1)
        val t2 = mem.u8(m + A.BM_TYPE2)
        val stageNames = listOf("hp", "atk", "def", "speed", "spAtk", "spDef", "accuracy", "evasion")
        val stages = (1 until 8).associate { i -> stageNames[i] to mem.s8(m + A.BM_STAT_CHANGES + i) - 6 }
        val hp = mem.s32(m + A.BM_HP)
        val item = mem.u16(m + A.BM_ITEM)
        return Battler(
            battlerId = id,
            personality = mem.u32(m + A.BM_PERSONALITY),
            otId = mem.u32(m + A.BM_OTID),
            statusRaw = mem.u32(m + A.BM_STATUS),
            status2 = mem.u32(m + A.BM_STATUS2),
            moveEffects = mem.u32(m + A.BM_MOVE_EFFECT_FLAGS),
            counters = mem.u32(m + A.BM_SUB),
            side = if (id % 2 == 0) "player" else "opponent",
            partySlot = mem.u8(battleCtx + A.BC_SELECTED_MON_INDEX + id).takeIf { it < 6 },
            species = species,
            speciesName = HgssData.speciesName(species),
            nickname = Gen4Text.decode(mem.chars(m + A.BM_NICKNAME, 11)).takeIf { it.isNotEmpty() },
            level = mem.u8(m + A.BM_LEVEL),
            hp = hp,
            maxHp = mem.s32(m + A.BM_MAX_HP),
            status = Gen4Pokemon.statusName(mem.u32(m + A.BM_STATUS), hp),
            types = listOf(t1, t2).distinct().map { HgssData.typeName(it) },
            ability = mem.u8(m + A.BM_ABILITY).takeIf { it != 0 }?.let { HgssData.abilityName(it) },
            heldItem = item.takeIf { it != 0 }?.let { HgssData.itemName(it) },
            moves = moves,
            statStages = stages,
            abilityId = mem.u8(m + A.BM_ABILITY),
            heldItemId = item,
            announceFlags = mem.u32(m + A.BM_ANNOUNCE_FLAGS),
        )
    }
}
