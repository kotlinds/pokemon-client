package dev.kotlinds.pokemonclient.games.platinum

import dev.kotlinds.pokemonclient.games.gen4.Gen4Structs as S
import dev.kotlinds.pokemonclient.state.AnimationKind
import dev.kotlinds.pokemonclient.state.FieldNotice
import dev.kotlinds.pokemonclient.state.Awaiting
import dev.kotlinds.pokemonclient.state.FieldState
import dev.kotlinds.pokemonclient.state.MapName
import dev.kotlinds.pokemonclient.state.MovementMode
import dev.kotlinds.pokemonclient.state.Screen
import dev.kotlinds.pokemonclient.state.TextSource

/**
 * The field of Platinum (basic support): where the player is, and whether they can walk or a script runs a message.
 *
 * `FieldSystem` (include/field/field_system.h) differs from HGSS's, but what is read through it is Gen 4 shared
 * ([S]): the `Location` (save), the player's `MapObject` (position, height, facing), the `FieldTask` stack, the
 * `ScriptManager` (message box, text printer, script contexts).
 */
internal object PlatinumField {

    /** FieldSystem offsets (offsetof with the decomp's compiler). */
    private const val FS_PROCESS_MANAGER = 0x00L
    private const val FS_TASK = 0x10L
    private const val FS_LOCATION = 0x1CL
    private const val FS_PLAYER_AVATAR = 0x3CL
    private const val FS_RUNNING_FIELD_MAP = 0x68L

    /** FieldProcessManager: `parent` (the field map overlay), `child` (an application launched from the field), `pause`. */
    private const val PM_CHILD = 0x04L
    private const val PM_PAUSE = 0x08L

    /**
     * `SCRIPT_ID(COMMON_SCRIPTS, 32)` (2000 + 32), the script `Repel_UpdateSteps` starts when the Repel's steps run out
     * (src/overlay006/repel_step_update.c): [FieldNotice.REPEL_WORE_OFF].
     */
    private const val REPEL_WORE_OFF_SCRIPT = 2032

    /** ScriptManager.msgBuf (Platinum include/script_manager.h:162; HGSS has another field there). */
    private const val SM_MSG_BUF = 0x44L

    /** The field system while the field app runs, else null (`sFieldSystem` stays set after the field app ends). */
    fun fieldSystem(mem: PlatinumMemory, top: PlatinumTopApp): Long? =
        if (top.app == PlatinumApp.FIELD) mem.ptr(mem.version.fieldSystemPtr) else null

    /** The player's position: the live `MapObject` when the map runs, the saved `Location` otherwise. */
    fun position(mem: PlatinumMemory, fs: Long, mapName: (Int) -> MapName): FieldState? {
        val loc = mem.ptr(fs + FS_LOCATION) ?: return null
        val mo = mem.ptr(fs + FS_PLAYER_AVATAR)?.let { mem.ptr(it + S.PA_MAP_OBJECT) }?.takeIf { mem.s32(fs + FS_RUNNING_FIELD_MAP) != 0 }
        val p = mem.playerPosition(loc, mo) ?: return null
        return FieldState(
            mapId = p.mapId, mapName = mapName(p.mapId), x = p.x, y = p.z, height = p.height,
            facing = p.facing, movement = MovementMode.WALK, moving = p.moving,
        )
    }

    /**
     * What the field waits for: free to walk (no field task, the map running, not paused: the input check of
     * src/field_system.c:236), a script's message (field text printer, A waits of the script context), or something
     * not decoded yet.
     */
    fun screen(mem: PlatinumMemory, fs: Long, field: FieldState?): Screen {
        val pm = mem.ptr(fs + FS_PROCESS_MANAGER)
        if (pm != null && mem.ptr(pm + PM_CHILD) != null) return Screen.Unknown("an application launched from the field", Awaiting.INPUT)
        val task = mem.ptr(fs + FS_TASK)
        if (task != null) return task(mem, task)
        if (mem.s32(fs + FS_RUNNING_FIELD_MAP) == 0 || (pm != null && mem.s32(pm + PM_PAUSE) != 0) || mem.fading) {
            return Screen.Animation(AnimationKind.TRANSITION)
        }
        return Screen.Overworld(awaiting = if (field?.moving == true) Awaiting.ANIMATION else Awaiting.INPUT)
    }

    private fun task(mem: PlatinumMemory, task: Long): Screen {
        val v = mem.version
        if (mem.fn(task + S.FIELD_TASK_FUNC) in v.fnMapTransitionTasks) return Screen.Animation(AnimationKind.TRANSITION)
        if (mem.fn(task + S.FIELD_TASK_FUNC) != v.fnFieldTaskRunScript) return Screen.Unknown("field task 0x${mem.fn(task + S.FIELD_TASK_FUNC).toString(16)} (start menu, scene...)", Awaiting.INPUT)
        val sm = mem.ptr(task + S.FIELD_TASK_ENV)?.takeIf { mem.u32(it + S.SM_MAGIC) == S.SCRIPT_MANAGER_MAGIC }
            ?: return Screen.Animation(AnimationKind.CUTSCENE)
        // The waits of the contexts that really wait (a caller parked on its common script's run left out).
        val waits = mem.waitingScriptContexts(sm, v.scriptContexts, v.fnScrWaitSubContext).mapNotNull(mem::scriptNative)
        val boxOpen = mem.u8(sm + S.SM_MSG_BOX_OPEN) != 0
        if (v.fnScrWaitForYesNoResult in waits) return Screen.Unknown("a script's yes / no (not decoded yet for Platinum)", Awaiting.INPUT)
        // Signs (and tips like "The X Button opens the menu!") print in a signpost window, not the message box
        // (ScrCmd_GetSignpostInput / WaitScrollingSignpostInput, src/scrcmd.c).
        if (v.fnScrSignpostInput in waits || v.fnScrSignpostPrinting in waits) {
            val printer = mem.textPrinter(mem.u8(sm + S.SM_MESSAGE_ID))
            val awaiting = if (v.fnScrSignpostInput in waits || printer?.let(mem::printerWaitsForInput) == true) Awaiting.INPUT else Awaiting.TEXT_PRINTING
            return Screen.Dialogue(TextSource.SIGN, null, message(mem, sm), awaiting)
        }
        if (!boxOpen) return Screen.Animation(AnimationKind.CUTSCENE, "script running (waits ${waits.joinToString { "0x" + it.toString(16) }})")
        val printer = mem.textPrinter(mem.u8(sm + S.SM_MESSAGE_ID))
        val text = message(mem, sm)
        val awaitsA = waits.any { it == v.fnScrCheckABPress || it == v.fnScrCheckABXPadPress || it == v.fnScrCheckABPadPress || it == v.fnScrDecrementABPressTimer }
        val awaiting = when {
            awaitsA -> Awaiting.INPUT
            printer != null -> if (mem.printerWaitsForInput(printer)) Awaiting.INPUT else Awaiting.TEXT_PRINTING
            else -> Awaiting.ANIMATION
        }
        // A message the game shows by itself on the field (a Repel wearing off): told by the script printing it.
        val notice = if (mem.u16(sm + S.SM_SCRIPT_ID) == REPEL_WORE_OFF_SCRIPT) FieldNotice.REPEL_WORE_OFF else null
        return Screen.Dialogue(TextSource.FIELD, null, text, awaiting, notice)
    }

    /** The page of the script's message (`ScriptManager.msgBuf`) the box shows, "" when unreadable. */
    private fun message(mem: PlatinumMemory, sm: Long): String =
        mem.printedText(mem.ptr(sm + SM_MSG_BUF), mem.u8(sm + S.SM_MESSAGE_ID), allowFreed = true)?.visible.orEmpty()
}
