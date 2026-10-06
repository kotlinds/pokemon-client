package dev.kotlinds.pokemonclient.games.gen4

import dev.kotlinds.pokemonclient.Direction
import dev.kotlinds.pokemonclient.RamMemory
import dev.kotlinds.pokemonclient.games.hgss.HgssMemory
import dev.kotlinds.pokemonclient.games.hgss.HgssVersion
import dev.kotlinds.pokemonclient.games.platinum.PlatinumMemory
import dev.kotlinds.pokemonclient.games.platinum.PlatinumVersion
import dev.kotlinds.pokemonclient.state.Awaiting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.kotlinds.pokemonclient.games.gen4.Gen4Structs as S

/**
 * The shared Gen 4 readings ([Gen4Memory]) on a RAM laid out by hand, for both games: the printed text and its wait
 * states (they differ per game), the player's position, and the script contexts that really wait.
 */
class Gen4MemoryTest {

    /** A blank main RAM and little-endian writers at bus addresses. */
    private class Ram {
        val bytes = ByteArray(4 shl 20)
        val memory = RamMemory(bytes)
        private fun at(addr: Long) = (addr - S.MAIN_RAM_START).toInt()
        fun u8(addr: Long, value: Int) { bytes[at(addr)] = value.toByte() }
        fun u16(addr: Long, value: Int) { u8(addr, value); u8(addr + 1, value shr 8) }
        fun u32(addr: Long, value: Long) { u16(addr, value.toInt()); u16(addr + 2, (value shr 16).toInt()) }

        /** A game `String` of [chars] (game character codes) at [addr]. */
        fun string(addr: Long, chars: IntArray) {
            u16(addr + S.STR_MAXSIZE, chars.size)
            u16(addr + S.STR_SIZE, chars.size)
            u32(addr + S.STR_MAGIC, S.STRING_MAGIC)
            chars.forEachIndexed { i, c -> u16(addr + S.STR_DATA + 2L * i, c) }
        }
    }

    private val reverse = Gen4Charmap.table.entries.filter { it.value.length == 1 }.associate { it.value[0] to it.key }
    private fun encode(text: String) = text.map { reverse.getValue(it) }.toIntArray()

    private val string = 0x02300000L
    private val task = 0x02301000L
    private val printer = 0x02302000L

    /** Printer 0 printing the first [printed] characters of "Hello there" at [string], in render state [state]. */
    private fun printing(ram: Ram, tasks: Long, state: Int, printed: Int) {
        ram.string(string, encode("Hello there"))
        ram.u32(tasks, task)
        ram.u32(task + S.SYSTASK_DATA, printer)
        ram.u32(printer + S.TP_CURRENT_CHAR, string + S.STR_DATA + 2L * printed)
        ram.u8(printer + S.TP_STATE, state)
    }

    @Test
    fun theStatesWaitingForAAreEachGamesOwn() {
        val hgss = HgssVersion.HEARTGOLD_US
        val platinum = PlatinumVersion.PLATINUM_US
        for (state in 0..8) {
            val h = Ram().also { printing(it, hgss.textPrinters.tasks, state, 5) }
            val p = Ram().also { printing(it, platinum.textPrinters.tasks, state, 5) }
            val hText = assertNotNull(HgssMemory(h.memory, hgss).printedText(string, 0))
            val pText = assertNotNull(PlatinumMemory(p.memory, platinum).printedText(string, 0))
            // 2 / 3 in every Gen 4 game; 7 / 8 only in HGSS (its two added render states).
            assertEquals(if (state in setOf(2, 3, 7, 8)) Awaiting.INPUT else Awaiting.TEXT_PRINTING, hText.awaiting, "HGSS state $state")
            assertEquals(if (state in setOf(2, 3)) Awaiting.INPUT else Awaiting.TEXT_PRINTING, pText.awaiting, "Platinum state $state")
            assertEquals("Hello", hText.visible)
            assertEquals("Hello there", hText.full)
            assertTrue(hText.printerAlive)
        }
    }

    @Test
    fun aPrinterPrintingAnotherTextIsNotTrusted() {
        val version = HgssVersion.HEARTGOLD_US
        val ram = Ram().also { printing(it, version.textPrinters.tasks, 0, 5) }
        // The printer's slot was reused by another text: this one is complete and waits for a key.
        ram.u32(printer + S.TP_CURRENT_CHAR, 0x02200000L)
        val text = assertNotNull(HgssMemory(ram.memory, version).printedText(string, 0))
        assertEquals(Awaiting.INPUT, text.awaiting)
        assertFalse(text.printerAlive)
        assertEquals("Hello there", text.visible)
        assertNull(HgssMemory(ram.memory, version).printedText(0x02310000L, 0))
    }

    private val location = 0x02303000L
    private val mapObject = 0x02304000L

    /** The fixed-point coordinate of the center of tile [tile]. */
    private fun center(tile: Int) = (tile * 16 * 4096 + 8 * 4096).toLong()

    private fun standing(ram: Ram, x: Int, z: Int) {
        ram.u32(mapObject + S.MO_X, x.toLong())
        ram.u32(mapObject + S.MO_Z, z.toLong())
        ram.u32(mapObject + S.MO_Y, 2)
        ram.u32(mapObject + S.MO_PREVIOUS_X, x.toLong())
        ram.u32(mapObject + S.MO_PREVIOUS_Z, z.toLong())
        ram.u32(mapObject + S.MO_POSITION_VECTOR, center(x))
        ram.u32(mapObject + S.MO_POSITION_VECTOR + 8, center(z))
        ram.u32(mapObject + S.MO_FACING, 3) // east (Gen4Structs.DIRECTIONS)
    }

    @Test
    fun thePlayersPositionIsTheLiveMapObjectElseTheSavedLocation() {
        val ram = Ram()
        ram.u32(location + S.LOC_MAP_ID, 64)
        ram.u32(location + S.LOC_X, 6)
        ram.u32(location + S.LOC_Z, 7)
        ram.u32(location + S.LOC_DIRECTION, 1)
        val mem = HgssMemory(ram.memory, HgssVersion.HEARTGOLD_US)
        // Without the field map: the saved Location, height 0, never moving.
        assertEquals(Gen4Position(64, 6, 7, 0, S.DIRECTIONS[1], moving = false), mem.playerPosition(location, null))
        standing(ram, 10, 12)
        assertEquals(Gen4Position(64, 10, 12, 2, Direction.EAST, moving = false), mem.playerPosition(location, mapObject))
        // Between two tiles (the position vector off the tile's center): moving.
        ram.u32(mapObject + S.MO_POSITION_VECTOR, center(10) + 4096)
        assertTrue(assertNotNull(mem.playerPosition(location, mapObject)).moving)
        // The step just committed to the next tile (previous tile still the old one): moving too.
        standing(ram, 10, 12)
        ram.u32(mapObject + S.MO_PREVIOUS_X, 9)
        assertTrue(assertNotNull(mem.playerPosition(location, mapObject)).moving)
        // A garbage map id: no position.
        ram.u32(location + S.LOC_MAP_ID, 5000)
        assertNull(mem.playerPosition(location, mapObject))
    }

    private val manager = 0x02305000L
    private fun context(i: Int) = 0x02306000L + 0x100L * i

    @Test
    fun theWaitingContextsSkipACallerParkedOnItsChild() {
        val waitChild = 0x02040BCCL
        val menuWait = 0x020418B4L
        val ram = Ram()
        ram.u8(manager + S.SM_ACTIVE_CONTEXTS, 2)
        for (i in 0 until 3) ram.u32(manager + S.SM_CONTEXTS + 4L * i, context(i))
        // The caller (0) waits for its child (1), which waits for a menu; slot 2 is stale (beyond the active count).
        ram.u8(context(0) + S.SC_STATE, S.SC_STATE_NATIVE)
        ram.u32(context(0) + S.SC_NATIVE, waitChild or 1)
        ram.u8(context(1) + S.SC_STATE, S.SC_STATE_NATIVE)
        ram.u32(context(1) + S.SC_NATIVE, menuWait or 1)
        ram.u8(context(2) + S.SC_STATE, S.SC_STATE_NATIVE)
        val mem = HgssMemory(ram.memory, HgssVersion.HEARTGOLD_US)
        val waiting = mem.waitingScriptContexts(manager, slots = 3, waitChild = waitChild)
        assertEquals(listOf(context(1)), waiting)
        assertEquals(menuWait, mem.scriptNative(waiting.single()))
        // Without a sane active count every slot is tried (the stale one included), the caller still left out.
        ram.u8(manager + S.SM_ACTIVE_CONTEXTS, 0)
        assertEquals(listOf(context(2), context(1)), mem.waitingScriptContexts(manager, slots = 3, waitChild = waitChild))
        // A context running bytecode has no native wait.
        ram.u8(context(1) + S.SC_STATE, 1)
        assertNull(mem.scriptNative(context(1)))
    }
}
