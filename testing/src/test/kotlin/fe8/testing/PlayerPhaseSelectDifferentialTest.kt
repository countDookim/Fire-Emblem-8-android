package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.AttackRange
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.ItemInfo
import fe8.game.bm.MovementMap
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
 * The unit select of [PlayerPhase] (A on Eirika, `PlayerPhase_MainIdle` to the sleep before
 * `PlayerPhase_RangeDisplayIdle`) against the per-frame RAM of the three Prologue captures that
 * select her: `ram_trace_prologue_select_move` (A at f1260), `ram_trace_prologue_cancel_end` (A at
 * f1260 and f1310) and `ram_trace_prologue_attack` (A at f1260).
 *
 * Each case seeds `gBmSt`, `gPlaySt`, the units, the terrain, unit and movement maps and
 * `gActionData` from the dump of the last idle frame before A, starts `gProcScr_PlayerPhase` at
 * its idle loop (label 9), then runs one frame per dump with the key status of that dump's frame
 * and compares, after every frame: the PlayerPhase proc's script position, sleep time, lock count
 * and repeat callback (the `procs` watch); `gActiveUnit`, `gActiveUnitId`,
 * `gActiveUnitMoveOrigin` and the modelled fields of `gActionData`; the whole of `gBmSt` and the
 * cursor in `gPlaySt`; all unit arrays (Eirika's state is 0x1, hidden, from f1260); the unit
 * map; and, from the frame the movement range is built, the whole movement map. The hooks that
 * run are recorded with their frame and arguments: `MuExists` answers from the `procs` watch,
 * and the `DisplayMoveRangeGraphics` stand-in (PP-4) sets `BM_FLAG_0` and must get the flags the
 * dump's `sProcScr_MoveLimitView` proc holds.
 *
 * `SetAllUnitNotBackSprite` runs here but no unit has `US_BIT8` in the window, so it is not tagged.
 *
 * Not exercised (no capture): the other select kinds (map menu on an empty tile or a unit that
 * acted, the enemy and berserk kinds, the preparation screen), `StartMu` of
 * `MakeMoveunitForActiveUnit` (the hover's MU exists already), `US_HAS_MOVED` in
 * `DisplayUnitEffectRange`, the cursor off the unit in `PlayerPhase_InitUnitMovementSelect`,
 * cantos, and the L, R and START buttons.
 */
class PlayerPhaseSelectDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "GetPlayerSelectKind", "UnitBeginAction", "PlayerPhase_InitUnitMovementSelect",
    )
    fun `select Eirika matches the game in select_move at f1260`() {
        val c = run(SelectCase("ram_trace_prologue_select_move", 1255, 1268, 1260))
        assertTrue(c.frames == 13 && c.rangeFrames >= 7, "$c")
    }

    @Test
    @VerifiesPort(
        "GetPlayerSelectKind", "UnitBeginAction", "PlayerPhase_InitUnitMovementSelect",
    )
    fun `select Eirika matches the game in cancel_end at f1260`() {
        val c = run(SelectCase("ram_trace_prologue_cancel_end", 1255, 1268, 1260))
        assertTrue(c.frames == 13 && c.rangeFrames >= 7, "$c")
    }

    @Test
    @VerifiesPort(
        "GetPlayerSelectKind", "UnitBeginAction", "PlayerPhase_InitUnitMovementSelect",
    )
    fun `select Eirika again after the cancel matches the game in cancel_end at f1310`() {
        val c = run(SelectCase("ram_trace_prologue_cancel_end", 1305, 1318, 1310))
        assertTrue(c.frames == 13 && c.rangeFrames >= 7, "$c")
    }

    @Test
    @VerifiesPort(
        "GetPlayerSelectKind", "UnitBeginAction", "PlayerPhase_InitUnitMovementSelect",
    )
    fun `select Eirika with enemies in reach matches the game in attack at f1260`() {
        val c = run(SelectCase("ram_trace_prologue_attack", 1255, 1268, 1260))
        assertTrue(c.frames == 13 && c.rangeFrames >= 7, "$c")
    }

    private fun dumpName(run: ReferenceData.Run, f: Int): String {
        val full = "f%05d".format(f)
        return if (run.dumps.containsKey(full)) full else "r%05d".format(f)
    }

    private fun run(case: SelectCase): SelectChecks {
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
        readPool(SelectDump(seedEwram, seedIwram), GBM_MAP_TERRAIN, map, map.terrain)
        readPool(SelectDump(seedEwram, seedIwram), GBM_MAP_UNIT, map, map.unit)
        val engine = ProcEngine()
        val keys = KeyStatus().also { it.readFrom(keyStruct(case.seed)) }
        val moves = MovementMap(rom, map, units, playSt)
        val phase = PlayerPhase(
            engine, bm, playSt, map, units, CamMove(engine, bm),
            EntityBmMaps(map, units, playSt), BmMapRender(bm, map, BgHardware()), keys,
            { false }, movementMap = moves,
        )
        val seedAction = SelectDump(seedEwram, seedIwram)
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
        h.startMu = { log += "f$frame startMu $it" }
        h.hideUnitSprite = { log += "f$frame hideUnitSprite $it" }
        h.setAutoMuDefaultFacing = ProcFunc { log += "f$frame setAutoMuDefaultFacing" }
        h.tryCallSelectEvents = ProcFunc { log += "f$frame tryCallSelectEvents" }
        h.resetBattleScratch = { log += "f$frame resetBattleScratch" }
        val loader = UnitLoader(units, rom, { 0 }, RngService(), { false })
        val attackRange =
            AttackRange(map, units, ItemInfo(rom), EntityBmMaps(map, units, playSt), loader)
        var attackRangeFlags = -1
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
        }
        h.pathArrowDispInit = { log += "f$frame pathArrowDispInit $it" }
        h.playSoundEffect = PlayerPhase.SoundEffect { log += "f$frame playSoundEffect $it" }

        val proc = engine.start(phase.script, 2)
        engine.goto(proc, 9)
        engine.run(engine.root(2))
        log.clear()

        val c = SelectChecks()
        for (f in case.seed + 1..case.last) {
            frame = f
            keys.readFrom(keyStruct(f))
            engine.run(engine.root(2))
            val at = "${case.run} f$f"
            val e = ew(f)
            val i = iw(f)
            val d = SelectDump(e, i)
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
            assertEquals(d.byte(ACTION_DATA + 0x0E), a.xMove, "$at xMove")
            assertEquals(d.byte(ACTION_DATA + 0x0F), a.yMove, "$at yMove")
            assertEquals(d.byte(ACTION_DATA + 0x10), a.moveCount, "$at moveCount")
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
            val eirika = units.getUnitFromCharId(CHARACTER_EIRIKA)
            val firstUnitRow = d.rowPointer(GBM_MAP_UNIT, -2)
            for (y in -2 until map.sizeY + 2) {
                for (x in 0 until map.sizeX + 2) {
                    assertEquals(
                        d.byte(firstUnitRow + (y + 2) * (map.sizeX + 2) + x), map.unit[y, x],
                        "$at unit map ($x,$y)",
                    )
                }
            }
            if (f >= case.aFrame) {
                assertEquals(1, units.state(eirika) and 1, "$at Eirika hidden")
                assertEquals(0, map.unit[units.yPos(eirika), units.xPos(eirika)], "$at cell")
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
        }

        // Which hooks ran, in which order (frames from the proc positions above).
        val a = case.aFrame
        val expected = listOf(
            "f$a pidStatsAddActAmt 1",
            "f${a + 1} endSideWindows",
            "f${a + 1} refreshUnitSprites",
            "f${a + 1} muExists true",
            "f${a + 1} setAutoMuDefaultFacing",
            "f${a + 1} tryCallSelectEvents",
            "f${a + 2} generateAttackRange ${units.getUnitFromCharId(CHARACTER_EIRIKA)}",
            "f${a + 2} displayMoveRangeGraphics $attackRangeFlags",
            "f${a + 2} pathArrowDispInit 0",
            "f${a + 2} playSoundEffect ${PlayerPhase.SONG_69}",
        )
        val shown = log.filter { !it.contains("resetBattleScratch") }
        assertEquals(expected, shown, "hooks in order")
        assertTrue(
            log.contains("f$a resetBattleScratch") && attackRangeFlags in 1..7,
            "$log $attackRangeFlags",
        )
        println("PlayerPhaseSelect ${case.run}: $c flags=$attackRangeFlags")
        return c
    }

    private fun unitIdAt(units: UnitTable, address: Int): Int {
        if (address == 0) return 0
        val offset = address - UnitTable.BLUE_ADDRESS
        return units.bytes[offset + 0x0B].toInt() and 0xFF
    }

    private fun readPool(dump: SelectDump, handle: Int, map: BmMap, pool: BmMap.Pool) {
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
    }
}

private class SelectCase(val run: String, val seed: Int, val last: Int, val aFrame: Int)

private class SelectChecks {
    var frames = 0
    var rangeFrames = 0

    override fun toString() = "frames=$frames rangeFrames=$rangeFrames"
}


private const val EWRAM = 0x02000000
private const val IWRAM = 0x03000000

/** The EWRAM and IWRAM of one dump, addressed with GBA addresses. */
private class SelectDump(val ewram: ByteArray, val iwram: ByteArray) {
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
