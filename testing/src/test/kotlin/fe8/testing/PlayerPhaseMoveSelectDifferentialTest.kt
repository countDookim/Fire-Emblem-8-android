package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.ActiveUnit
import fe8.game.bm.AttackRange
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.ItemInfo
import fe8.game.bm.MovementMap
import fe8.game.bm.PathArrow
import fe8.game.bm.PlayerPhase
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.render.BgHardware
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The move-select stage of [PlayerPhase] (`PlayerPhase_RangeDisplayIdle` and the functions it
 * calls) against the per-frame RAM of `ram_trace_prologue_select_move` (cursor right twice, A to
 * confirm at f1295) and `ram_trace_prologue_cancel_end` (B to cancel the select at f1290).
 *
 * Like `PlayerPhaseSelectDifferentialTest` each case seeds from the last idle frame before A
 * (f1259), runs the real select (A at f1260, with the [PathArrow] port wired in) and then goes on
 * frame by frame with the key status of each dump to the frame after the confirm or cancel.
 * After every frame it compares what that test does (proc position, active unit, action data,
 * all of `gBmSt`, units, unit map, movement and range maps while the range shows) plus that the
 * `sProcScr_MoveLimitView` proc exists exactly when the range shows (removed by the confirm and
 * the cancel), Eirika's state (0x1 hidden, back to 0x0 after the cancel) and the 0x43 bytes of
 * `gPathArrowProc` from the init frame on. The hooks that run (cursor sounds, the range squares
 * hidden, MUs ended, the cancel sound) are recorded with their frames.
 *
 * The third case seeds f1399 (unit menu open) and runs `PlayerPhase_BackToMove` (f1401, with the
 * script's `PrepareAction` call for no chosen action), the range display again and the B at f1425.
 * Stand-ins there: the script's wait in `PlayerPhase_ApplyUnitMovement` (the menu's B is not
 * ported), and `DoesBMXFADEExist` (the fade exists only inside f1401).
 *
 * Not exercised: `PlayerPhase_CancelAction` (not written: no capture runs it), A without an
 * active unit, the destination event, the range swap, R and L in the range display, a unit that
 * has moved (B fails), the ballista, `DrawPathArrow` (PP-6) and the MU walk after the confirm (PP-8).
 */
class PlayerPhaseMoveSelectDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "PlayerPhase_RangeDisplayIdle", "CanMoveActiveUnitTo",
    )
    fun `move to the right in select_move matches the game f1259 to f1296`() {
        val c = run(MoveCase("ram_trace_prologue_select_move", 1259, 1296, 1260, 1295, false))
        assertTrue(c.frames == 37 && c.arrowFrames >= 30 && c.cursorMoves == 2, "$c")
    }

    @Test
    @VerifiesPort("PlayerPhase_BackToMove", "PlayerPhase_RangeDisplayIdle")
    fun `back to move from the unit menu in cancel_end matches the game f1399 to f1426`() {
        val c = run(MoveCase("ram_trace_prologue_cancel_end", 1399, 1426, 1399, 1425, true, true))
        assertTrue(c.frames == 27 && c.arrowFrames >= 15, "$c")
    }

    @Test
    @VerifiesPort("PlayerPhase_RangeDisplayIdle")
    fun `cancel the select in cancel_end matches the game f1259 to f1292`() {
        val c = run(MoveCase("ram_trace_prologue_cancel_end", 1259, 1292, 1260, 1290, true))
        assertTrue(c.frames == 33 && c.arrowFrames >= 20, "$c")
    }

    private fun dumpName(run: ReferenceData.Run, f: Int): String {
        val full = "f%05d".format(f)
        return if (run.dumps.containsKey(full)) full else "r%05d".format(f)
    }

    private fun run(case: MoveCase): MoveChecks {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val run = ref.run(case.run)
        val rom = RomView.ofRom(romFile.readBytes())
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val procsTrace = run.watch("procs")
        fun ew(f: Int) = run.region(dumpName(run, f), "ewram")
        fun iw(f: Int) = run.region(dumpName(run, f), "iwram")
        fun keyStruct(f: Int) =
            ew(f).copyOfRange(KEY_STATUS - EWRAM, KEY_STATUS - EWRAM + KeyStatus.STRUCT_SIZE)
        fun symbol(name: String) =
            names.entries.first { it.value == name }.key

        // Seed from the last idle frame before A.
        val seedEwram = ew(case.seed)
        val seedIwram = iw(case.seed)
        val playSt = PlaySt().also { it.decode(seedEwram, PLAY_ST - EWRAM) }
        val bm = BmSt().also { it.decode(seedEwram, BM_ST - EWRAM) }
        val map = BmMap(rom, ChapterData(rom), bm).also { it.initChapterMap(playSt.chapterIndex) }
        val units = UnitTable(rom).also { it.loadFrom(seedEwram) }
        readPool(MoveDump(seedEwram, seedIwram), GBM_MAP_TERRAIN, map, map.terrain)
        readPool(MoveDump(seedEwram, seedIwram), GBM_MAP_UNIT, map, map.unit)
        val engine = ProcEngine()
        val keys = KeyStatus().also { it.readFrom(keyStruct(case.seed)) }
        val moves = MovementMap(rom, map, units, playSt)
        val active = ActiveUnit()
        val arrow = PathArrow(map, units, moves, active, bm)
        // The captures never re-route the path, so the best-script call (PP-8) must not run.
        arrow.hooks.generateBestMovementScript = { _, _, _ ->
            error("${case.run}: the path was re-routed (GenerateBestMovementScript called)")
        }
        val phase = PlayerPhase(
            engine, bm, playSt, map, units, CamMove(engine, bm),
            EntityBmMaps(map, units, playSt), BmMapRender(bm, map, BgHardware()), keys,
            { false }, active = active, movementMap = moves, pathArrow = arrow,
        )
        val seedAction = MoveDump(seedEwram, seedIwram)
        phase.active.unit = unitIdAt(units, seedAction.word(GACTIVEUNIT))
        phase.active.unitId = seedAction.byte(GACTIVEUNIT_ID)
        phase.active.moveOriginX = seedAction.half(GACTIVEUNIT_ORIGIN)
        phase.active.moveOriginY = seedAction.half(GACTIVEUNIT_ORIGIN + 2)
        phase.active.action.subjectIndex = seedAction.byte(ACTION_DATA + 0x0C)
        phase.active.action.targetIndex = seedAction.byte(ACTION_DATA + 0x0D)
        phase.active.action.xMove = seedAction.byte(ACTION_DATA + 0x0E)
        phase.active.action.yMove = seedAction.byte(ACTION_DATA + 0x0F)
        phase.active.action.moveCount = seedAction.byte(ACTION_DATA + 0x10)
        phase.active.action.unitActionType = seedAction.byte(ACTION_DATA + 0x11)

        // Hooks: record the ones the select runs, with the frame they ran in.
        var frame = case.seed
        val log = ArrayList<String>()
        val h = phase.hooks
        h.unitSelectEnabled = true
        h.endSideWindows = ProcFunc { log += "f$frame endSideWindows" }
        h.endAllMus = ProcFunc { log += "f$frame endAllMus" }
        h.showUnitSprite = { log += "f$frame showUnitSprite $it" }
        h.startMapMenu = { log += "f$frame startMapMenu" }
        h.pidStatsAddActAmt = { log += "f$frame pidStatsAddActAmt $it" }
        h.refreshUnitSprites = ProcFunc { log += "f$frame refreshUnitSprites" }
        h.muExists = ProcBoolFunc {
            val exists = ProcArrayDump.ofWatch(procsTrace, frame, names).find("ProcScr_Mu") != null
            log += "f$frame muExists $exists"
            exists
        }
        // The fade of the menu cancel (BMXFADE, tree 3) is created and ends within f1401, so no
        // dump has it, but the real script waits at label 1 (position 18) in that frame.
        h.doesBmxFadeExist = ProcBoolFunc { case.fromMenu && frame == case.seed + 2 }
        h.startMu = { log += "f$frame startMu $it" }
        h.hideUnitSprite = { log += "f$frame hideUnitSprite $it" }
        h.setAutoMuDefaultFacing = ProcFunc { log += "f$frame setAutoMuDefaultFacing" }
        h.tryCallSelectEvents = ProcFunc { log += "f$frame tryCallSelectEvents" }
        h.resetBattleScratch = { log += "f$frame resetBattleScratch" }
        val loader = UnitLoader(units, rom, { 0 }, RngService(), { false })
        val attackRange =
            AttackRange(map, units, ItemInfo(rom), EntityBmMaps(map, units, playSt), loader)
        var attackRangeFlags = -1
        var viewUp = false
        h.generateAttackRange = { unit ->
            log += "f$frame generateAttackRange $unit"
            // The !US_HAS_MOVED block of DisplayUnitEffectRange for a unit with only weapons:
            // the fills, the real range, and the LIMITVIEW flags of the weapon-only case.
            map.bmMapFill(map.other, 0)
            map.bmMapFill(map.range, 0)
            attackRange.generateUnitCompleteAttackRange(unit)
            attackRangeFlags = PlayerPhase.LIMITVIEW_RED or PlayerPhase.LIMITVIEW_BLUE
            attackRangeFlags
        }
        h.displayMoveRangeGraphics = { flags ->
            log += "f$frame displayMoveRangeGraphics $flags"
            bm.gameStateBits = bm.gameStateBits or 1 // MoveLimitView_OnInit: BM_FLAG_0 (PP-4)
            viewUp = true
        }
        var drawn = 0
        h.hideMoveRangeGraphics = ProcFunc {
            log += "f$frame hideMoveRangeGraphics"
            viewUp = false
            bm.gameStateBits = bm.gameStateBits and 3.inv() // MoveLimitView_OnEnd: BM_FLAG_0, _1
        }
        h.drawPathArrow = ProcFunc { drawn++ }
        h.cursorSound = PlayerPhase.SoundEffect { log += "f$frame cursorSound $it" }
        h.playSoundEffect = PlayerPhase.SoundEffect { log += "f$frame playSoundEffect $it" }

        h.prepareAction = ProcBoolFunc {
            // PlayerPhase_PrepareAction with no action chosen (the captures' only case).
            if (active.action.unitActionType == 0) phase.playerPhaseBackToMove(it)
            true
        }
        run.region(dumpName(run, case.seed), "ewram")
            .copyInto(arrow.buffer, 0, ARROW - EWRAM + 0x29, ARROW - EWRAM + 0x6C)
        val proc = engine.start(phase.script, 2)
        if (case.fromMenu) {
            // The unit menu is open: the script waits in PlayerPhase_ApplyUnitMovement (label 2),
            // a stand-in here that goes on in the frame the menu proc has ended (f1400; the
            // menu's B press is not ported), as the game's proc position shows.
            h.applyUnitMovement = ProcFunc { if (frame >= case.seed + 1) engine.breakProc(it) }
            engine.goto(proc, 2)
        } else {
            engine.goto(proc, 9)
            engine.run(engine.root(2))
        }
        log.clear()

        val c = MoveChecks()
        var lastCursorX = bm.playerCursorX
        var lastCursorY = bm.playerCursorY
        for (f in case.seed + 1..case.last) {
            frame = f
            keys.readFrom(keyStruct(f))
            engine.run(engine.root(2))
            val at = "${case.run} f$f"
            val e = ew(f)
            val i = iw(f)
            val d = MoveDump(e, i)
            val want = ProcArrayDump.ofWatch(procsTrace, f, names)

            // PlayerPhase proc.
            val wantProc = want.find("gProcScr_PlayerPhase")!!
            assertEquals(wantProc.scrCur, proc.scrCur, "$at PlayerPhase script position")
            assertEquals(wantProc.u16(0x24).toShort().toInt(), proc.sleepTime, "$at sleep")
            assertEquals(wantProc.u8(0x28), proc.lockCnt, "$at lockCnt")
            assertEquals(wantProc.u32(0x0C) != 0, proc.idleCb != null, "$at repeat callback")
            assertEquals(
                want.find("gProcScr_0859ACE8") != null,
                engine.find(phase.selectEventsScript) != null,
                "$at select events child",
            )

            // Active unit and action data.
            val activeAddress = if (phase.active.unit == 0) 0
            else UnitTable.BLUE_ADDRESS + units.unitOffset(phase.active.unit)
            assertEquals(d.word(GACTIVEUNIT), activeAddress, "$at gActiveUnit")
            assertEquals(d.byte(GACTIVEUNIT_ID), phase.active.unitId, "$at gActiveUnitId")
            assertEquals(d.half(GACTIVEUNIT_ORIGIN), phase.active.moveOriginX, "$at origin x")
            assertEquals(d.half(GACTIVEUNIT_ORIGIN + 2), phase.active.moveOriginY, "$at origin y")
            val a = phase.active.action
            assertEquals(d.byte(ACTION_DATA + 0x0C), a.subjectIndex, "$at subjectIndex")
            assertEquals(d.byte(ACTION_DATA + 0x0D), a.targetIndex, "$at targetIndex")
            // xMove, yMove and moveCount are set by PlayerPhase_DisplayUnitMovement (PP-8), which
            // runs in the frame after the confirm; they are compared up to the confirm.
            if (case.cancel || f <= case.bFrame) {
                assertEquals(d.byte(ACTION_DATA + 0x0E), a.xMove, "$at xMove")
                assertEquals(d.byte(ACTION_DATA + 0x0F), a.yMove, "$at yMove")
                assertEquals(d.byte(ACTION_DATA + 0x10), a.moveCount, "$at moveCount")
            }
            assertEquals(d.byte(ACTION_DATA + 0x11), a.unitActionType, "$at unitActionType")

            // gBmSt (all 0x40 bytes), gPlaySt cursor, units, unit map.
            val bmBytes = ByteArray(BmSt.SIZE).also { bm.encode(it, 0) }
            // Bytes 0..3, 5..9 are the main loop's lock/semaphore and the VCOUNT it last saw.
            for (k in listOf(4) + (0x0A until BmSt.SIZE)) {
                assertEquals(e[BM_ST - EWRAM + k], bmBytes[k], "$at gBmSt+0x${k.toString(16)}")
            }
            assertEquals(d.byte(PLAY_ST + 0x12), playSt.xCursor, "$at gPlaySt xCursor")
            assertEquals(d.byte(PLAY_ST + 0x13), playSt.yCursor, "$at gPlaySt yCursor")
            for (k in 0 until UnitTable.TOTAL_SIZE) {
                // pMapSpriteHandle (0x3C): RefreshUnitSprites is a recorded hook here.
                if (k % UnitTable.UNIT_SIZE in 0x3C..0x3F) continue
                assertEquals(
                    e[UnitTable.BLUE_ADDRESS - EWRAM + k], units.bytes[k],
                    "$at unit byte 0x${k.toString(16)}",
                )
            }
            val firstUnitRow = d.rowPointer(GBM_MAP_UNIT, -2)
            for (y in -2 until map.sizeY + 2) {
                for (x in 0 until map.sizeX + 2) {
                    assertEquals(
                        d.byte(firstUnitRow + (y + 2) * (map.sizeX + 2) + x), map.unit[y, x],
                        "$at unit map ($x,$y)",
                    )
                }
            }
            val eirika = units.getUnitFromCharId(CHARACTER_EIRIKA)
            val hidden = f >= case.aFrame && !(case.cancel && f >= case.bFrame)
            if (!case.fromMenu) {
                assertEquals(if (hidden) 1 else 0, units.state(eirika) and 1, "$at Eirika hidden")
            }
            assertEquals(
                want.find("sProcScr_MoveLimitView") != null, viewUp, "$at MoveLimitView exists",
            )
            if (viewUp && f >= case.aFrame + 2 && (!case.fromMenu || f >= 1403)) {
                // gPathArrowProc data (0x43 bytes after the proc header) from the init frame.
                for (k in 0 until 0x43) {
                    assertEquals(
                        d.byte(ARROW + 0x29 + k), arrow.buffer[k].toInt() and 0xFF,
                        "$at gPathArrowProc+0x${(0x29 + k).toString(16)}",
                    )
                }
                c.arrowFrames++
            }

            // Movement map from the frame the range is built.
            val built = want.find("sProcScr_MoveLimitView") != null &&
                f > case.aFrame
            if (built) {
                val first = d.rowPointer(GBM_MAP_MOVEMENT, -2)
                var reached = 0
                for (y in -2 until map.sizeY + 2) {
                    for (x in 0 until map.sizeX + 2) {
                        val expected = d.byte(first + (y + 2) * (map.sizeX + 2) + x)
                        assertEquals(expected, map.movement[y, x], "$at movement ($x,$y)")
                        if (y in 0 until map.sizeY && x < map.sizeX && expected < 0xFF) reached++
                    }
                }
                assertTrue(reached >= 5, "$at reached $reached")
                val firstRange = d.rowPointer(GBM_MAP_RANGE, -2)
                for (y in -2 until map.sizeY + 2) {
                    for (x in 0 until map.sizeX + 2) {
                        assertEquals(
                            d.byte(firstRange + (y + 2) * (map.sizeX + 2) + x), map.range[y, x],
                            "$at range ($x,$y)",
                        )
                    }
                }
                assertEquals(
                    attackRangeFlags, want.find("sProcScr_MoveLimitView")!!.u16(0x4A),
                    "$at MoveLimitView flags",
                )
                c.rangeFrames++
            }
            c.frames++
            if (bm.playerCursorX != lastCursorX || bm.playerCursorY != lastCursorY) c.cursorMoves++
            lastCursorX = bm.playerCursorX
            lastCursorY = bm.playerCursorY
        }

        // The hooks of the move-select stage, with their frames: the two cursor sounds, then
        // the confirm (range squares hidden) or the cancel (MUs ended, squares hidden, sprites
        // refreshed, cancel sound).
        val later = log.filter { it.substringBefore(' ').drop(1).toInt() > case.aFrame + 2 }
        val expected = if (case.fromMenu) {
            val eirika = units.getUnitFromCharId(CHARACTER_EIRIKA)
            assertEquals(
                listOf(
                    "f1401 hideUnitSprite $eirika", "f1401 endAllMus", "f1401 startMu $eirika",
                    "f1401 endSideWindows",
                ),
                log.filter {
                    it.startsWith("f1401 ") && !it.contains("refreshUnitSprites") &&
                        !it.contains("resetBattleScratch")
                },
                "back to move hooks",
            )
            listOf(
                "f1425 endAllMus", "f1425 hideMoveRangeGraphics", "f1425 refreshUnitSprites",
                "f1425 playSoundEffect ${PlayerPhase.SONG_CANCEL}",
            ).also { want -> assertEquals(want, later.filter { it.startsWith("f1425 ") }) }
        } else if (case.cancel) {
            listOf(
                "f${case.bFrame} endAllMus", "f${case.bFrame} hideMoveRangeGraphics",
                "f${case.bFrame} refreshUnitSprites",
                "f${case.bFrame} playSoundEffect ${PlayerPhase.SONG_CANCEL}",
            )
        } else {
            listOf(
                "f1275 cursorSound ${PlayerPhase.SONG_65}",
                "f1283 cursorSound ${PlayerPhase.SONG_65}",
                "f${case.bFrame} hideMoveRangeGraphics",
            )
        }
        if (!case.fromMenu) assertEquals(expected, later, "hooks in order")
        assertTrue(drawn >= 20, "drawPathArrow ran $drawn times")
        println("PlayerPhaseMoveSelect ${case.run}: $c")
        return c
    }

    private fun unitIdAt(units: UnitTable, address: Int): Int {
        if (address == 0) return 0
        val offset = address - UnitTable.BLUE_ADDRESS
        return units.bytes[offset + 0x0B].toInt() and 0xFF
    }

    private fun readPool(dump: MoveDump, handle: Int, map: BmMap, pool: BmMap.Pool) {
        val first = dump.rowPointer(handle, -2)
        for (y in -2 until map.sizeY + 2) {
            for (x in 0 until map.sizeX + 2) {
                pool[y, x] = dump.byte(first + (y + 2) * (map.sizeX + 2) + x)
            }
        }
    }

    private companion object {
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val KEY_STATUS = 0x02024CC0
        const val ACTION_DATA = 0x0203A958
        const val GACTIVEUNIT = 0x03004E50
        const val GACTIVEUNIT_ID = 0x0202BE44
        const val GACTIVEUNIT_ORIGIN = 0x0202BE48
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val GBM_MAP_MOVEMENT = 0x0202E4E0
        const val GBM_MAP_RANGE = 0x0202E4E4
        const val CHARACTER_EIRIKA = 0x01
        const val ARROW = 0x0203A990
    }
}

private class MoveCase(
    val run: String,
    val seed: Int,
    val last: Int,
    val aFrame: Int,
    val bFrame: Int,
    val cancel: Boolean,
    val fromMenu: Boolean = false,
)

private class MoveChecks {
    var frames = 0
    var rangeFrames = 0
    var arrowFrames = 0
    var cursorMoves = 0

    override fun toString() =
        "frames=$frames rangeFrames=$rangeFrames arrowFrames=$arrowFrames cursorMoves=$cursorMoves"
}

private const val EWRAM = 0x02000000
private const val IWRAM = 0x03000000

/** The EWRAM and IWRAM of one dump, addressed with GBA addresses. */
private class MoveDump(val ewram: ByteArray, val iwram: ByteArray) {
    fun byte(address: Int): Int =
        if (address >= IWRAM) {
            iwram[address - IWRAM].toInt() and 0xFF
        } else {
            ewram[address - EWRAM].toInt() and 0xFF
        }

    fun half(address: Int): Int = (byte(address) or (byte(address + 1) shl 8)).toShort().toInt()

    fun word(address: Int): Int = byte(address) or (byte(address + 1) shl 8) or
        (byte(address + 2) shl 16) or (byte(address + 3) shl 24)

    /** The address of the data of row [row] (the handle points at the row 0 pointer). */
    fun rowPointer(handle: Int, row: Int): Int = word(word(handle) + 4 * row)
}
