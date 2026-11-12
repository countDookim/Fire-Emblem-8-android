package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.PlayerPhase
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventInfoHooks
import fe8.game.event.EventInfos
import fe8.game.event.EventScrCore
import fe8.game.event.EventScrHooks
import fe8.game.event.EventState
import fe8.game.event.ScriptWords
import fe8.game.flags.Fe8EventFlags
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
 * What the player phase runs after the unit menu's Wait, in `ram_trace_prologue_select_move`:
 * f1356 to f1364 (seeded from f1355, the frame the menu has ended).
 *
 * The real `gProcScr_PlayerPhase` script runs from label 7 with the ported
 * `PlayerPhase_PrepareAction`, `ApplyUnitAction` (Wait), `HandlePostActionTraps`,
 * `RunPotentialWaitEvents` (the post-action event check of [EventInfos] on the chapter's misc
 * event list, the real event engine and its commands), `EnsureCameraOntoActiveUnitPosition` and
 * `PlayerPhase_FinishAction`, then label 0 again with `PlayerPhase_HandleAutoEnd`. As in the game
 * proc tree 2 does not run while the game is locked (the event engine locks it from f1356 to its
 * end), and trees run in order each frame.
 *
 * After every frame it compares Eirika's state bits (0x1, 0x41 after the action, 0x42 after the
 * finish), `gActionData`, the active unit, all of `gBmSt` and `gPlaySt`, all units, the unit map,
 * the event flags and slots, the event engine proc (present, command), that the MU proc exists
 * exactly until `EndAllMus` (f1360), and the PlayerPhase command (f1356 to f1359 at the call
 * after `RunPotentialWaitEvents`, f1364 at the idle repeat).
 *
 * Stand-ins: `WriteSuspendSave` and `StartPlayerPhaseSideWindows`, which the game spends f1360 to
 * f1363 in (the dumps are taken inside them, so the proc position there is not comparable: the
 * port finishes the label 0 pass in f1360, and proc tree 2 is skipped from f1361 to f1363, where
 * the game's proc runs no command either); `EndAllMus` and `GetPickTrapType` (no trap on the
 * tile).
 */
class PlayerPhaseActionDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "PlayerPhase_PrepareAction", "ApplyUnitAction", "HandlePostActionTraps",
        "RunPotentialWaitEvents", "EnsureCameraOntoActiveUnitPosition", "PlayerPhase_FinishAction",
        "PlayerPhase_HandleAutoEnd", "MoveActiveUnit", "CheckForWaitEvents", "RunWaitEvents",
        "ShouldCallEndEvent", "EvCheck01_AFEV", "CountAvailableBlueUnits", "AreAnyEnemyUnitDead",
    )
    fun `wait after the unit menu matches the game f1355 to f1364`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val run = ref.run("ram_trace_prologue_select_move")
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val procsTrace = run.watch("procs")
        fun name(f: Int): String {
            val full = "f%05d".format(f)
            return if (run.dumps.containsKey(full)) full else "r%05d".format(f)
        }
        fun ew(f: Int) = run.region(name(f), "ewram")
        fun iw(f: Int) = run.region(name(f), "iwram")

        val seed = ew(SEED)
        val seedIw = iw(SEED)
        val playSt = PlaySt().also { it.decode(seed, PLAY_ST - EWRAM) }
        val bm = BmSt().also { it.decode(seed, BM_ST - EWRAM) }
        val map = BmMap(rom, ChapterData(rom), bm).also { it.initChapterMap(playSt.chapterIndex) }
        val units = UnitTable(rom).also { it.loadFrom(seed) }
        readPool(seed, seedIw, GBM_MAP_TERRAIN, map, map.terrain)
        readPool(seed, seedIw, GBM_MAP_UNIT, map, map.unit)
        val engine = ProcEngine()
        val keys = KeyStatus()
        val flags = Fe8EventFlags.create().also { it.copyIn(seedIw, FLAGS - IWRAM) }
        val words = ScriptWords({ a ->
            val o = a - 0x08000000
            (romBytes[o].toInt() and 0xFF) or ((romBytes[o + 1].toInt() and 0xFF) shl 8)
        })
        val state = EventState()
        for (k in 0 until 14) state.slots[k] = word(seedIw, GLOBALS + 0x80 + 4 * k - IWRAM)

        // gActiveEventRegistry (RegisterEventActivation, ClearActiveEventRegistry,
        // GetEventTriggerId): not ported yet, a stand-in here.
        val registry = ArrayList<Pair<Int, Int>>()
        var compareMid: (Int, Int) -> Unit = { _, _ -> }
        var frame = SEED
        val events = EventEngine(
            engine, state, words,
            object : EventEngineHooks {
                override fun lockGame() { bm.lock++ }
                override fun resumeGameplay() {
                    bm.lock--
                    // EventEngine_OnEnd: the unlock is done, the proc is still there (the dump
                    // of f1358 is taken here).
                    compareMid(1358, 35)
                }
            },
        )
        EventScrCore(
            state, words, flags = flags, rng = RngService(), playSt = playSt, keys = keys,
            hooks = object : EventScrHooks {
                override fun countRedUnits(): Int = units.countRedUnits()
                override fun eventTriggerId(script: Int): Int =
                    registry.firstOrNull { it.first == script }?.second ?: 0
            },
        ).registerAll(events)
        val chapters = ChapterData(rom)
        val infos = EventInfos(words, flags, playSt)
        infos.hooks = object : EventInfoHooks {
            override fun callEvent(script: Int, execType: Int) = events.callEvent(script, execType)
            override fun chapterEventGroup(chapter: Int) =
                chapters.getChapterEventDataPointer(chapter)
            override fun registerEventActivation(script: Int, flag: Int) {
                registry += script to flag
            }
            override fun clearActiveEventRegistry() = registry.clear()
            override fun areAnyEnemyUnitDead(): Boolean = units.areAnyEnemyUnitAlive()
            override fun countAvailableBlueUnits(): Int = units.countAvailableBlueUnits()
        }

        val active = fe8.game.bm.ActiveUnit()
        val phase = PlayerPhase(
            engine, bm, playSt, map, units, CamMove(engine, bm),
            EntityBmMaps(map, units, playSt), BmMapRender(bm, map, BgHardware()), keys,
            { events.eventEngineExists() }, active = active,
        )
        active.unit = unitIdAt(units, word(seedIw, GACTIVEUNIT - IWRAM))
        active.unitId = seed[GACTIVEUNIT_ID - EWRAM].toInt() and 0xFF
        active.moveOriginX = half(seed, GACTIVEUNIT_ORIGIN - EWRAM)
        active.moveOriginY = half(seed, GACTIVEUNIT_ORIGIN - EWRAM + 2)
        readAction(seed, active)

        val proc = engine.start(phase.script, 2)
        proc.scrCur = 29 // label 7, the unit menu has ended
        var muEndedAt = 0
        val log = ArrayList<String>()
        val eirika = units.getUnitFromCharId(CHARACTER_EIRIKA)
        val stateSeen = ArrayList<String>()
        var comparisons = 0

        /** Compares everything against the dump of frame [f]; the proc position if [position]. */
        fun compare(f: Int, position: Int = -1) {
            val at = "f$f"
            val e = ew(f)
            val i = iw(f)
            val want = ProcArrayDump.ofWatch(procsTrace, f, names)
            if (position >= 0) {
                val wantProc = want.find("gProcScr_PlayerPhase")!!
                assertEquals(position, wantProc.scrCur, "$at PlayerPhase command (capture)")
                assertEquals(wantProc.scrCur, proc.scrCur, "$at PlayerPhase command")
                assertEquals(wantProc.u8(0x28), proc.lockCnt, "$at lockCnt")
            }
            // Event engine and MU.
            val wantEngine = want.find("ProcScr_StdEventEngine")
            val myEngine = engine.find(events.stdScript)
            assertEquals(wantEngine != null, myEngine != null, "$at event engine exists")
            if (wantEngine != null) {
                assertEquals(wantEngine.scrCur, myEngine!!.scrCur, "$at event engine command")
            }
            assertEquals(want.find("ProcScr_Mu") != null, muEndedAt == 0, "$at MU exists")
            for (k in 0 until 14) {
                assertEquals(word(i, GLOBALS + 0x80 + 4 * k - IWRAM), state.slots[k], "$at slot $k")
            }
            assertEquals(
                (0 until 0x30).map { i[FLAGS - IWRAM + it] },
                (0 until 0x30).map { flags.byteAt(it).toByte() }, "$at event flags",
            )

            // gBmSt, gPlaySt, active unit, action data, units, unit map.
            val bmBytes = ByteArray(BmSt.SIZE).also { bm.encode(it, 0) }
            assertEquals(e[BM_ST - EWRAM + 1], bmBytes[1], "$at game lock")
            for (k in listOf(4) + (0x0A until BmSt.SIZE)) {
                assertEquals(e[BM_ST - EWRAM + k], bmBytes[k], "$at gBmSt+0x${k.toString(16)}")
            }
            val psBytes = ByteArray(PlaySt.SIZE).also { playSt.encode(it, 0) }
            for (k in 6 until PlaySt.SIZE) {
                assertEquals(e[PLAY_ST - EWRAM + k], psBytes[k], "$at gPlaySt+0x${k.toString(16)}")
            }
            val activeAddress = if (active.unit == 0) 0
            else UnitTable.BLUE_ADDRESS + units.unitOffset(active.unit)
            assertEquals(word(i, GACTIVEUNIT - IWRAM), activeAddress, "$at gActiveUnit")
            assertEquals(e[GACTIVEUNIT_ID - EWRAM].toInt() and 0xFF, active.unitId, "$at id")
            val a = active.action
            val d = ACTION_DATA - EWRAM
            assertEquals(e[d + 0x0C].toInt() and 0xFF, a.subjectIndex, "$at subjectIndex")
            assertEquals(e[d + 0x0D].toInt() and 0xFF, a.targetIndex, "$at targetIndex")
            assertEquals(e[d + 0x0E].toInt() and 0xFF, a.xMove, "$at xMove")
            assertEquals(e[d + 0x0F].toInt() and 0xFF, a.yMove, "$at yMove")
            assertEquals(e[d + 0x10].toInt() and 0xFF, a.moveCount, "$at moveCount")
            assertEquals(e[d + 0x11].toInt() and 0xFF, a.unitActionType, "$at unitActionType")
            assertEquals(e[d + 0x12].toInt() and 0xFF, a.itemSlotIndex, "$at itemSlotIndex")
            assertEquals(e[d + 0x15].toInt() and 0xFF, a.trapType, "$at trapType")
            assertEquals(e[d + 0x16].toInt() and 0xFF, a.suspendPointType, "$at suspendPointType")
            for (k in 0 until UnitTable.TOTAL_SIZE) {
                if (k % UnitTable.UNIT_SIZE in 0x3C..0x3F) continue
                assertEquals(
                    e[UnitTable.BLUE_ADDRESS - EWRAM + k], units.bytes[k],
                    "$at unit byte 0x${k.toString(16)}",
                )
            }
            val firstRow = rowPointer(e, i, GBM_MAP_UNIT, -2)
            for (y in -2 until map.sizeY + 2) {
                for (x in 0 until map.sizeX + 2) {
                    assertEquals(
                        e[firstRow + (y + 2) * (map.sizeX + 2) + x - EWRAM].toInt() and 0xFF,
                        map.unit[y, x], "$at unit map ($x,$y)",
                    )
                }
            }
            stateSeen += "f$f:0x${units.state(eirika).toString(16)}"
            comparisons++
        }
        compareMid = { f, position -> compare(f, position) }

        val h = phase.hooks
        h.endAllMus = ProcFunc { muEndedAt = frame; log += "f$frame endAllMus" }
        // The game is inside WriteSuspendSave in the dumps f1360 to f1362 and inside
        // StartPlayerPhaseSideWindows in f1363: the state at those calls is compared.
        h.suspend = ProcFunc {
            log += "f$frame suspend"
            for (f in 1360..1362) compare(f)
        }
        h.startSideWindows = ProcFunc {
            log += "f$frame startSideWindows"
            compare(1363)
        }
        h.refreshUnitSprites = ProcFunc { log += "f$frame refreshUnitSprites" }
        h.writeSuspendSave = { log += "f$frame writeSuspendSave $it" }
        h.checkForWaitEvents = {
            log += "f$frame checkForWaitEvents"
            infos.checkForWaitEvents(units.xPos(active.unit), units.yPos(active.unit))
        }
        h.runWaitEvents = {
            log += "f$frame runWaitEvents"
            infos.runWaitEvents(units.xPos(active.unit), units.yPos(active.unit))
        }
        h.shouldCallEndEvent = { infos.shouldCallEndEvent() }
        log.clear()
        assertEquals(0x01, units.state(eirika) and 0xFF, "Eirika at the seed")

        // The main loop passes and the dump each one ends at. The dumps are taken at VBlank, so
        // a pass that takes longer shows in several: f1358 is inside EventEngine_OnEnd (compared
        // from the unlock) and f1359 after it; f1360 to f1363 are inside the pass that runs the
        // rest of the action and label 0 (see the hooks above).
        val passes = listOf(1356 to 35, 1357 to 35, 1358 to 35, 1360 to -1, 1364 to 16)
        val endFrames = mapOf(1358 to 1359)
        for ((pass, position) in passes) {
            frame = pass
            val keyStart = KEY_STATUS - EWRAM
            keys.readFrom(ew(pass).copyOfRange(keyStart, keyStart + KeyStatus.STRUCT_SIZE))
            for (t in 0 until 8) if (t != 2 || bm.lock == 0) engine.run(engine.root(t))
            val end = endFrames[pass] ?: if (pass == 1360) 1363 else pass
            if (pass != 1360) compare(end, position)
        }
        assertEquals(
            listOf(
                "f1356 checkForWaitEvents", "f1356 runWaitEvents", "f1360 endAllMus",
                "f1360 suspend", "f1360 startSideWindows",
            ),
            log.filter { !it.endsWith("refreshUnitSprites") },
            "hooks with their frames",
        )
        assertEquals(0x42, units.state(eirika) and 0xFF, "Eirika after the finish")
        println("PlayerPhaseAction: comparisons=$comparisons states=$stateSeen")
    }

    private fun readAction(e: ByteArray, active: fe8.game.bm.ActiveUnit) {
        val d = ACTION_DATA - EWRAM
        active.action.subjectIndex = e[d + 0x0C].toInt() and 0xFF
        active.action.targetIndex = e[d + 0x0D].toInt() and 0xFF
        active.action.xMove = e[d + 0x0E].toInt() and 0xFF
        active.action.yMove = e[d + 0x0F].toInt() and 0xFF
        active.action.moveCount = e[d + 0x10].toInt() and 0xFF
        active.action.unitActionType = e[d + 0x11].toInt() and 0xFF
        active.action.itemSlotIndex = e[d + 0x12].toInt() and 0xFF
        active.action.trapType = e[d + 0x15].toInt() and 0xFF
        active.action.suspendPointType = e[d + 0x16].toInt() and 0xFF
    }

    private fun unitIdAt(units: UnitTable, address: Int): Int {
        if (address == 0) return 0
        return units.bytes[address - UnitTable.BLUE_ADDRESS + 0x0B].toInt() and 0xFF
    }

    private fun word(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or
        ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or
        ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun half(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)).toShort().toInt()

    /** The GBA address of the row [row] data of the map pool whose handle is at [handle]. */
    private fun rowPointer(e: ByteArray, i: ByteArray, handle: Int, row: Int): Int {
        val pool = word(e, handle - EWRAM)
        return word(e, pool - EWRAM + 4 * row)
    }

    private fun readPool(e: ByteArray, i: ByteArray, handle: Int, map: BmMap, pool: BmMap.Pool) {
        val first = rowPointer(e, i, handle, -2)
        for (y in -2 until map.sizeY + 2) {
            for (x in 0 until map.sizeX + 2) {
                pool[y, x] = e[first + (y + 2) * (map.sizeX + 2) + x - EWRAM].toInt() and 0xFF
            }
        }
    }

    private companion object {
        const val SEED = 1355
        const val LAST = 1364
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val KEY_STATUS = 0x02024CC0
        const val ACTION_DATA = 0x0203A958
        const val GACTIVEUNIT = 0x03004E50
        const val GACTIVEUNIT_ID = 0x0202BE44
        const val GACTIVEUNIT_ORIGIN = 0x0202BE48
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val GLOBALS = 0x03000438
        const val FLAGS = 0x03005250
        const val CHARACTER_EIRIKA = 0x01
    }
}
