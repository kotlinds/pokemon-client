package dev.kotlinds.pokemonclient.games.hgss

import dev.kotlinds.pokemonclient.games.gen4.Gen4RomBytes.s32
import dev.kotlinds.pokemonclient.games.gen4.Gen4RomBytes.u16
import dev.kotlinds.pokemonclient.games.gen4.Gen4RomBytes.u32
import dev.kotlinds.pokemonclient.world.FlagCondition

/**
 * Just enough of the map script files (NARC [HgssWorldAddresses.SCRIPT_NARC], member `MapHeader.scriptsBank`) to
 * find the warps that the zone events don't list: coordinate triggers whose script runs `Warp` to the same map
 * (Blackthorn Gym exit pads, scr_seq_T30GYM0101_002; Team Rocket HQ B1F trap tile, scr_seq_D35R0102_029).
 *
 * File layout (asm/macros/script.inc): `ScrDef` entries (u32, offset of the script relative to the end of the
 * entry) up to the u16 `ScrDefEnd` marker 0xFD13, then the bytecode. Event script ids are 1-based ("`_EV_..._002 + 1`"
 * is the third `ScrDef`); ids above the file's count are common scripts (`std_*`), ignored here.
 *
 * The bytecode isn't decoded (each command has its own argument sizes): the warp is found by its exact shape,
 * `Warp map, 0, x, z, dir` = u16 176, the zone itself, 0, x, z, a direction 0..3, between the start of the script
 * and the start of the next one.
 */
internal object HgssScripts {

    /** A `Warp` command found in a script: destination tile and facing (DIR_NORTH 0, SOUTH 1, WEST 2, EAST 3). */
    data class WarpCommand(val x: Int, val z: Int, val direction: Int)

    /** Start offsets of the scripts of [file], by 0-based index. */
    fun scriptStarts(file: ByteArray): List<Int> {
        val starts = mutableListOf<Int>()
        var o = 0
        while (o + 4 <= file.size && u16(file, o) != SCRDEF_END && starts.size < MAX_SCRIPTS) {
            val start = o + 4 + u32(file, o).toInt()
            if (start !in 0 until file.size) break
            starts += start
            o += 4
        }
        return starts
    }

    /** The `Warp` to zone [zone] itself in event script [scriptId] (1-based) of [file], or null. */
    fun sameZoneWarp(file: ByteArray, scriptId: Int, zone: Int): WarpCommand? {
        val starts = scriptStarts(file)
        val start = starts.getOrNull(scriptId - 1) ?: return null
        val end = minOf(starts.filter { it > start }.minOrNull() ?: file.size, start + MAX_SCAN, file.size)
        for (o in start..end - WARP_SIZE) {
            if (u16(file, o) != WARP_OPCODE || u16(file, o + 2) != zone || u16(file, o + 4) != 0) continue
            val direction = u16(file, o + 10)
            if (direction > 3) continue
            return WarpCommand(u16(file, o + 6), u16(file, o + 8), direction)
        }
        return null
    }

    /** A `Warp` to another zone found in a trigger script: the zone and the landing tile. */
    data class ZoneWarpCommand(val zone: Int, val x: Int, val z: Int)

    /**
     * The `Warp` to another zone than [zone] run by event script [scriptId] (1-based) of [file], or null: the holes of
     * Victory Road 3F (scr_seq_D43R0103_002..006) and Ice Path B1F (scr_seq_D39R0102_000..003). Those scripts set the
     * landing tile in temporary variables then jump to a shared fall routine:
     * `SetVar VAR_TEMP_x4000, x; SetVar VAR_TEMP_x4001, z; GoTo fall` … `Warp MAP_X, 0, VAR_TEMP_x4000, VAR_TEMP_x4001, dir`.
     * The leading `SetVar`s and `GoTo`s are followed, then the routine is scanned for the `Warp` (literal
     * coordinates, or the variables set before). [zoneCount] bounds the zone ids accepted.
     */
    fun zoneWarp(file: ByteArray, scriptId: Int, zone: Int, zoneCount: Int): ZoneWarpCommand? {
        val starts = scriptStarts(file)
        var pos = starts.getOrNull(scriptId - 1) ?: return null
        val vars = HashMap<Int, Int>()
        // Follow the prefix: SetVar (41: var, value) and GoTo (22: s32 offset from the end of the offset).
        repeat(MAX_PREFIX_COMMANDS) {
            if (pos + 6 > file.size) return null
            when (u16(file, pos)) {
                SETVAR_OPCODE -> {
                    vars[u16(file, pos + 2)] = u16(file, pos + 4)
                    pos += 6
                }
                GOTO_OPCODE -> {
                    pos = pos + 6 + s32(file, pos + 2)
                    if (pos !in file.indices) return null
                }
                else -> return@repeat
            }
        }
        fun value(raw: Int): Int? = if (raw >= VAR_BASE) vars[raw] else raw
        val end = minOf(pos + MAX_ZONE_WARP_SCAN, file.size)
        for (o in pos..end - WARP_SIZE) {
            if (u16(file, o) != WARP_OPCODE || u16(file, o + 4) != 0) continue
            val target = u16(file, o + 2)
            if (target == zone || target !in 0 until zoneCount) continue
            val x = value(u16(file, o + 6)) ?: continue
            val z = value(u16(file, o + 8)) ?: continue
            if (x >= MAX_COORDINATE || z >= MAX_COORDINATE) continue
            return ZoneWarpCommand(target, x, z)
        }
        return null
    }

    /** A `SetDynamicWarp` command: event script [scriptId] (1-based) sends the lift to warp [warp] of zone [zone]. */
    data class DynamicWarpCommand(val scriptId: Int, val zone: Int, val warp: Int)

    /**
     * Every `SetDynamicWarp map, warp, x, z, dir` of [file] (u16 240 then five u16, asm/macros/script.inc): what a
     * lift's scripts send it to (scr_seq_D27R0108_000, the Olivine Lighthouse's: the light room or 1F;
     * scr_seq_T25R1007_000, the Goldenrod Dept. Store's: one per floor of the attendant's menu). Found by its exact
     * shape (a zone below [zoneCount], a warp index, coordinates, a direction 0..3), in the script whose start is the
     * last one before it.
     */
    fun dynamicWarps(file: ByteArray, zoneCount: Int): List<DynamicWarpCommand> {
        val starts = scriptStarts(file).withIndex().sortedBy { it.value }
        if (starts.isEmpty()) return emptyList()
        val found = mutableListOf<DynamicWarpCommand>()
        for (o in starts.first().value..file.size - DYNAMIC_WARP_SIZE) {
            if (u16(file, o) != DYNAMIC_WARP_OPCODE) continue
            val zone = u16(file, o + 2)
            val warp = u16(file, o + 4)
            if (zone !in 0 until zoneCount || warp >= MAX_WARPS) continue
            if (u16(file, o + 6) >= MAX_COORDINATE || u16(file, o + 8) >= MAX_COORDINATE || u16(file, o + 10) > 3) continue
            val script = starts.last { it.value <= o }.index + 1
            found += DynamicWarpCommand(script, zone, warp)
        }
        return found
    }

    private const val DYNAMIC_WARP_OPCODE = 240
    private const val DYNAMIC_WARP_SIZE = 12

    /** More warps than any map has: a lift command's warp index is below this. */
    private const val MAX_WARPS = 64

    /**
     * True when event script [scriptId] (1-based) of [file] does nothing: its first command is `End` (a placeholder
     * scene trigger like Violet City's on the Sprout Tower bridge, scr_seq_T22_002). Stepping on its trigger runs
     * nothing the player would notice.
     */
    fun isEmpty(file: ByteArray, scriptId: Int): Boolean {
        val start = scriptStarts(file).getOrNull(scriptId - 1) ?: return false
        return start + 2 <= file.size && u16(file, start) == END_OPCODE
    }

    /**
     * The flag state under which event script [scriptId] (1-based) of [file] ends without showing anything, or null:
     * the script opens with commands nobody sees ([QUIET_COMMANDS]: locks, follower housekeeping, variables), then
     * branches on a flag (`GoToIfSet` / `GoToIfUnset` = `CheckFlag flag` then `GoToIf TRUE|FALSE, dest`), and one
     * of the two branches only runs such commands until `End`. That branch is the silent case.
     *
     * The Viridian Gym's guide (scr_seq_T02GYM0101_003): `ScrCmd_609; LockAll; GoToIfSet FLAG_UNK_13A, _037D`, and
     * `_037D` is `ScrCmd_600; SetFollowMonInhibitState 1; ScrCmd_607; ScrCmd_109 253, 56; SetVar VAR_UNK_4127, 1;
     * ReleaseAll; End`: once his speech was heard, stepping on the row in front of the door does nothing visible,
     * although the map's init script arms the trigger again on every entry.
     *
     * Only the first flag test is looked at, and any command outside [QUIET_COMMANDS] (a message, a movement, a
     * sound, a battle...) makes a branch "not silent": a script this can't read is never called quiet.
     */
    fun quietWhen(file: ByteArray, scriptId: Int): FlagCondition? {
        val start = scriptStarts(file).getOrNull(scriptId - 1) ?: return null
        var pos = quietRun(file, start) ?: return null
        if (pos + CHECKFLAG_SIZE + GOTOIF_SIZE > file.size) return null
        if (u16(file, pos) != CHECKFLAG_OPCODE || u16(file, pos + CHECKFLAG_SIZE) != GOTOIF_OPCODE) return null
        val flag = u16(file, pos + 2)
        pos += CHECKFLAG_SIZE
        val condition = file[pos + 2].toInt() and 0xFF
        val jump = pos + GOTOIF_SIZE + s32(file, pos + 3)
        val next = pos + GOTOIF_SIZE
        // GoToIf TRUE jumps when the flag is set; GoToIf FALSE when it is clear (asm/macros/script.inc).
        val (whenSet, whenClear) = when (condition) {
            CONDITION_TRUE -> jump to next
            CONDITION_FALSE -> next to jump
            else -> return null
        }
        return when {
            silentToEnd(file, whenSet) -> FlagCondition(flag, set = true)
            silentToEnd(file, whenClear) -> FlagCondition(flag, set = false)
            else -> null
        }
    }

    /**
     * Follows [QUIET_COMMANDS] and `GoTo`s from [pos]; the position of the first other command, or null when the
     * bytecode runs out (or loops).
     */
    private fun quietRun(file: ByteArray, from: Int): Int? {
        var pos = from
        repeat(MAX_QUIET_COMMANDS) {
            if (pos !in 0..file.size - 2) return null
            val opcode = u16(file, pos)
            when {
                opcode == GOTO_OPCODE -> {
                    if (pos + 6 > file.size) return null
                    pos = pos + 6 + s32(file, pos + 2)
                }
                opcode == END_OPCODE -> return pos
                opcode in QUIET_COMMANDS -> pos += 2 + QUIET_COMMANDS.getValue(opcode)
                else -> return pos
            }
        }
        return null
    }

    /** True when the bytecode from [pos] only runs [QUIET_COMMANDS] (and `GoTo`s) until `End`. */
    private fun silentToEnd(file: ByteArray, pos: Int): Boolean {
        val stop = quietRun(file, pos) ?: return false
        return stop + 2 <= file.size && u16(file, stop) == END_OPCODE
    }

    /**
     * Script commands that show nothing and take no time, with the size of their arguments (asm/macros/script.inc,
     * src/scrcmd_c.c): SetFlag 30, ClearFlag 31, SetVar 41, CopyVar 42, LockAll 96, ReleaseAll 97, ScrCmd_109 (an
     * object's movement type), the follower's bookkeeping ScrCmd_600 / 607 / 608 / 609 and SetFollowMonInhibitState 783.
     */
    private val QUIET_COMMANDS = mapOf(
        30 to 2, 31 to 2, 41 to 4, 42 to 4, 96 to 0, 97 to 0, 109 to 4,
        600 to 0, 607 to 0, 608 to 0, 609 to 0, 783 to 1,
    )

    /** `CheckFlag flag` (command 32: u16 flag). */
    private const val CHECKFLAG_OPCODE = 32
    private const val CHECKFLAG_SIZE = 4

    /** `GoToIf condition, dest` (command 28: u8 condition, s32 offset from the end of the command). */
    private const val GOTOIF_OPCODE = 28
    private const val GOTOIF_SIZE = 7
    private const val CONDITION_FALSE = 0
    private const val CONDITION_TRUE = 1

    /** A silent branch is a handful of commands: past this, it isn't one. */
    private const val MAX_QUIET_COMMANDS = 32

    /**
     * The item event script [scriptId] (1-based) of [file] gives, or null: the `ItemVars item, quantity` of an item
     * gift (`SetVar VAR_SPECIAL_x8004, item`) followed by `CallStd std_obtain_item_verbose` / `std_give_item_verbose`
     * before the next script starts (the Cerulean Gym's Machine Part, scr_seq_T04GYM0101_005).
     */
    fun givenItem(file: ByteArray, scriptId: Int): Int? {
        val starts = scriptStarts(file)
        val start = starts.getOrNull(scriptId - 1) ?: return null
        val end = minOf(starts.filter { it > start }.minOrNull() ?: file.size, start + MAX_ITEM_SCAN, file.size)
        var item: Int? = null
        for (o in start..end - 4) {
            if (o + 6 <= end && u16(file, o) == SETVAR_OPCODE && u16(file, o + 2) == VAR_ITEM) item = u16(file, o + 4)
            if (item != null && u16(file, o) == CALLSTD_OPCODE && u16(file, o + 2) in ITEM_GIFT_STDS) return item
        }
        return null
    }

    /**
     * The local ids of the people some script of [file] puts somewhere else than the map places them:
     * `MovePersonFacing person, x, z, y, facing` (command 339, `MapObject_SetPositionFromXYZAndDirection`) and
     * `MovePerson person, x, z` (command 338, `Field_SetEventDefaultXYPos`), src/scrcmd_c.c. Mostly the map's entry
     * scripts: the Cinnabar Gym's (scr_seq_D11R0106_008, run on every entry) moves its three beaten trainers out of
     * the corridors. Found by their exact shape (a literal local id, plausible coordinates, a direction 0..3 for 339);
     * a person moved through a variable isn't known.
     */
    fun movedPeople(file: ByteArray): Set<Int> {
        val code = scriptStarts(file).minOrNull() ?: return emptySet()
        val moved = HashSet<Int>()
        for (o in code..file.size - MOVE_PERSON_SIZE) {
            val person = u16(file, o + 2)
            if (person >= MAX_LOCAL_ID || u16(file, o + 4) >= MAX_COORDINATE || u16(file, o + 6) >= MAX_COORDINATE) continue
            when (u16(file, o)) {
                MOVE_PERSON_OPCODE -> moved += person
                MOVE_PERSON_FACING_OPCODE -> if (o + MOVE_PERSON_FACING_SIZE <= file.size && u16(file, o + 8) < MAX_COORDINATE && u16(file, o + 10) <= 3) moved += person
            }
        }
        return moved
    }

    /** `MovePerson` (338: person, x, z) and `MovePersonFacing` (339: person, x, z, y, facing), all u16. */
    private const val MOVE_PERSON_OPCODE = 338
    private const val MOVE_PERSON_FACING_OPCODE = 339
    private const val MOVE_PERSON_SIZE = 8
    private const val MOVE_PERSON_FACING_SIZE = 12

    /** Map objects have small local ids (the player 0xFF and the camera are not people of the map). */
    private const val MAX_LOCAL_ID = 64

    /** `CallStd` (script command 20, asm/macros/script.inc). */
    private const val CALLSTD_OPCODE = 20

    /** `VAR_SPECIAL_x8004`: the item of `ItemVars`. */
    private const val VAR_ITEM = 0x8004

    /** `std_obtain_item_verbose` (2008), `std_give_item_verbose` (2033): include/constants/std_script.h. */
    private val ITEM_GIFT_STDS = setOf(2008, 2033)

    /** An item gift script (message, bag check, gift, hide the objects) is short. */
    private const val MAX_ITEM_SCAN = 0x80

    /** `End` (script command 2). */
    private const val END_OPCODE = 2
    private const val SETVAR_OPCODE = 41
    private const val GOTO_OPCODE = 22
    private const val VAR_BASE = 0x4000
    private const val MAX_PREFIX_COMMANDS = 8

    /** The shared fall routine moves the player and the follower before warping: a bit longer than a pad's script. */
    private const val MAX_ZONE_WARP_SCAN = 0x180
    private const val MAX_COORDINATE = 4096

    /** `Warp` (script command 176, asm/macros/script.inc: map, 0, x, z, direction as u16). */
    private const val WARP_OPCODE = 176
    private const val WARP_SIZE = 12
    private const val SCRDEF_END = 0xFD13
    private const val MAX_SCRIPTS = 512

    /** Warp scripts are short (lock, sound, fade, warp): don't look further than this into a script. */
    private const val MAX_SCAN = 0x100
}
