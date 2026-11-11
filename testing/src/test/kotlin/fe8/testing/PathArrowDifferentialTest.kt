package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.MovementMap
import fe8.game.bm.PathArrow
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The path arrow's data ([PathArrow]) against the per-frame RAM of the Prologue captures. A case
 * seeds the unit table, `gBmSt`, `gActionData`, the terrain and movement maps from the dump of
 * the frame in which `PlayerPhase_InitUnitMovementSelect` (PP-3) calls `PathArrowDisp_Init(0)`
 * and the 0x43 bytes of `gPathArrowProc` after its 0x29-byte proc header from the frame before.
 * It runs `init(0)` with the cursor of that frame, then one `UpdatePathArrowWithCursor` per frame
 * (the call of `PlayerPhase_RangeDisplayIdle`, PP-7) with the cursor of that frame's `gBmSt`.
 * After each call all 0x43 bytes (last x and y, `maxMov`, `pathLen`, `pathX`, `pathY`,
 * `pathCosts`) are compared.
 *
 * Not exercised: `ResetPathArrow` and the re-route of `UpdatePathArrowWithCursor` (the cursor
 * never leaves the range or crosses the path), `PathArrowDisp_Init(1)`.
 */
class PathArrowDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "CutOffPathLength", "AddPointToPathArrowProc", "UpdatePathArrowWithCursor",
        "GetPointAlongPath", "SetLastCoords", "PathArrowDisp_Init",
    )
    fun `select_move path arrow matches the game`() {
        val c = run("ram_trace_prologue_select_move", 1262, 1296)
        assertTrue(c.changes == 3, "$c")
    }

    @Test
    @VerifiesPort(
        "CutOffPathLength", "AddPointToPathArrowProc", "UpdatePathArrowWithCursor",
        "GetPointAlongPath", "SetLastCoords", "PathArrowDisp_Init",
    )
    fun `cancel_end path arrow matches the game after the first select`() {
        val c = run("ram_trace_prologue_cancel_end", 1262, 1290)
        assertTrue(c.frames >= 20, "$c")
    }

    @Test
    @VerifiesPort(
        "CutOffPathLength", "AddPointToPathArrowProc", "UpdatePathArrowWithCursor",
        "GetPointAlongPath", "SetLastCoords", "PathArrowDisp_Init",
    )
    fun `cancel_end path arrow matches the game after the move is cancelled`() {
        val c = run("ram_trace_prologue_cancel_end", 1401, 1410, init = false)
        assertTrue(c.frames == 10, "$c")
    }

    private class Checks {
        var frames = 0
        var changes = 0

        override fun toString() = "frames=$frames changes=$changes"
    }

    private fun dumpName(run: ReferenceData.Run, f: Int): String {
        val full = "f%05d".format(f)
        return if (run.dumps.containsKey(full)) full else "r%05d".format(f)
    }

    private fun run(name: String, initFrame: Int, last: Int, init: Boolean = true): Checks {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val run = ref.run(name)
        val rom = RomView.ofRom(romFile.readBytes())
        fun dump(f: Int) = PathDump(
            run.region(dumpName(run, f), "ewram"), run.region(dumpName(run, f), "iwram"),
        )
        val seed = dump(initFrame - 1)
        val playSt = PlaySt().also { it.decode(seed.ewram, PLAY_ST - EWRAM) }
        val bm = BmSt().also { it.decode(seed.ewram, BM_ST - EWRAM) }
        val map = BmMap(rom, ChapterData(rom), bm).also { it.initChapterMap(playSt.chapterIndex) }
        val units = UnitTable(rom).also { it.loadFrom(dump(initFrame).ewram) }
        readPool(seed, GBM_MAP_TERRAIN, map, map.terrain)
        val moves = MovementMap(rom, map, units, playSt)
        val active = fe8.game.bm.ActiveUnit()
        val activeAddress = dump(initFrame).word(GACTIVEUNIT)
        active.unit = units.bytes[activeAddress - UnitTable.BLUE_ADDRESS + 0x0B].toInt() and 0xFF
        active.action.moveCount = dump(initFrame).byte(ACTION_DATA + 0x10)
        // The working cost table DisplayUnitEffectRange left (the flood it runs is overwritten).
        moves.generateUnitMovementMapExt(active.unit, units.movement(active.unit))
        // The range is built in the frame of the init call, before it.
        readPool(dump(initFrame), GBM_MAP_MOVEMENT, map, map.movement)
        val arrow = PathArrow(map, units, moves, active, bm)
        // The capture never re-routes, so the best-script call (PP-8) must not run.
        arrow.hooks.generateBestMovementScript = { _, _, _ ->
            error("$name: the path was re-routed (GenerateBestMovementScript called)")
        }
        seed.ewram.copyInto(arrow.buffer, 0, ARROW - EWRAM + 0x29, ARROW - EWRAM + 0x6C)

        val c = Checks()
        var previous = arrow.buffer.copyOf()
        for (f in initFrame..last) {
            val d = dump(f)
            bm.playerCursorX = d.half(BM_ST + 0x14)
            bm.playerCursorY = d.half(BM_ST + 0x16)
            if (f == initFrame && init) arrow.init(0) else arrow.updatePathArrowWithCursor()
            val at = "$name f$f"
            for (k in 0 until 0x43) {
                assertEquals(
                    d.byte(ARROW + 0x29 + k), arrow.buffer[k].toInt() and 0xFF,
                    "$at gPathArrowProc+0x${(0x29 + k).toString(16)}",
                )
            }
            if (!previous.contentEquals(arrow.buffer)) c.changes++
            previous = arrow.buffer.copyOf()
            c.frames++
        }
        println("PathArrow $name: $c")
        return c
    }

    private fun readPool(dump: PathDump, handle: Int, map: BmMap, pool: BmMap.Pool) {
        val first = dump.rowPointer(handle, -2)
        for (y in -2 until map.sizeY + 2) {
            for (x in 0 until map.sizeX + 2) {
                pool[y, x] = dump.byte(first + (y + 2) * (map.sizeX + 2) + x)
            }
        }
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val ACTION_DATA = 0x0203A958
        const val ARROW = 0x0203A990
        const val GACTIVEUNIT = 0x03004E50
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val GBM_MAP_MOVEMENT = 0x0202E4E0
    }
}

/** The EWRAM and IWRAM of one dump, addressed with GBA addresses. */
private class PathDump(val ewram: ByteArray, val iwram: ByteArray) {
    fun byte(address: Int): Int =
        if (address >= 0x03000000) {
            iwram[address - 0x03000000].toInt() and 0xFF
        } else {
            ewram[address - 0x02000000].toInt() and 0xFF
        }

    fun half(address: Int): Int = (byte(address) or (byte(address + 1) shl 8)).toShort().toInt()

    fun word(address: Int): Int = byte(address) or (byte(address + 1) shl 8) or
        (byte(address + 2) shl 16) or (byte(address + 3) shl 24)

    /** The address of the data of row [row] (the handle points at the row 0 pointer). */
    fun rowPointer(handle: Int, row: Int): Int = word(word(handle) + 4 * row)
}
