package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.MovementMap
import fe8.game.bm.PlaySt
import fe8.game.bm.PlayerPhase
import fe8.game.bm.UnitTable
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_EXEC_CUTSCENE
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.EventInfoHooks
import fe8.game.event.EventInfos
import fe8.game.event.EventScrCore
import fe8.game.event.EventScrHooks
import fe8.game.event.EventState
import fe8.game.event.ScriptMemory
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.flags.EventFlags
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.render.BgHardware
import java.io.File
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * PC-10a: differential verification of tutorial event dispatch in [EventInfos], [EventScrCore],
 * [PlayerPhase], and [fe8.game.PlayerPhaseWiring] against `ram_trace_newgame_easy` (`PC-cap3`):
 * - `Event0B_EnqueueCall` (sub-command 1, literal and slot-2 pointers) and `EnqueueTutEvent` at
 *   `f18448`, `f18747`, `f18988`, and `f19021`.
 * - `StartPlayerPhaseStartTutorialEvent` at `f18531..f18535` (leaves pending `ONSELECT` queued).
 * - `TryCallSelectEvents` and `RunTutorialEvent(ONSELECT)` at `f18540..f18560`.
 * - `StartDestSelectedEvent` and `RunTutorialEvent(DESTSELECTED)` at `f18800..f18805` (hint) and
 *   `f19020..f19024` (accepted destination, enqueueing `AFTERMOVE`, engine end).
 * - Wired hooks (`startTutorialEvent`, `tryCallSelectEvents`, `startDestSelectedEvent`) in
 *   [Fe8Boot].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
class EasyTutorialDispatchDifferentialTest {
    private var shared: Rig? = null
    private var loaded = false

    /** Releases cached trace and watch references once all tests in this class complete. */
    @AfterAll
    fun releaseRig() {
        shared = null
        loaded = false
    }

    private fun rig(): Rig {
        if (!loaded) {
            shared = loadRig()
            loaded = true
        }
        val r = shared
        assumeTrue(r != null, "reference data, ROM, or nm file not present")
        return r!!
    }

    @Test
    @VerifiesPort("Event0B_EnqueueCall", "EnqueueTutEvent")
    fun `Event0B_EnqueueCall sub-cmd 1 and EnqueueTutEvent match f18448 f18747 f18988 f19021`() {
        val r = rig()
        // f18448: EventScr_Prologue_ExecTut literal pointer -> Tutorial0 (ONSELECT, counter 1).
        r.assertEnqueueStep(18447, 18448)
        // f18747: Tutorial_Exec0 negative ptr (-1) -> slot 2 -> Tutorial1 (DESTSELECTED, 2).
        r.assertEnqueueStep(18746, 18747, stubContinue = emptySet(), stubYield = setOf(0x1B))
        // f18988: Tutorial_Exec1 fail branch -> Tutorial1 (DESTSELECTED, 2) through EV_EXEC_UNK5.
        r.assertEnqueueStep(18987, 18988, stubContinue = setOf(0x3D), stubYield = emptySet())
        // f19021: Tutorial_Exec1 success branch -> Tutorial2 (AFTERMOVE, 3) through EV_EXEC_UNK5.
        r.assertEnqueueStep(19020, 19021, stubContinue = setOf(0x3D), stubYield = emptySet())
    }

    @Test
    @VerifiesPort("Event0B_EnqueueCall", "EnqueueTutEvent")
    fun `Event0B_EnqueueCall enqueues Tutorial4 ONSELECT at f22137`() {
        // f22137: the turn event's EvtEnqueueConditionalTutCall (queue 0/0 -> ONSELECT(2)/5).
        rig().assertEnqueueStep(22136, 22137)
    }

    @Test
    @VerifiesPort("RunTutorialEvent", "TryCallSelectEvents")
    fun `phase start keeps ONSELECT queued and unit select dispatches Tutorial0 at f18541`() {
        val r = rig()
        r.assertPhaseStartLeavesOnSelectQueued()
        r.assertSelectDispatchWindow()
    }

    @Test
    @VerifiesPort("RunTutorialEvent", "StartDestSelectedEvent")
    fun `destination select dispatches Tutorial1 hint at f18800 and success at f19020`() {
        val r = rig()
        r.assertDestHintWindow()
        r.assertDestAcceptWindow()
    }

    @Test
    fun `Fe8Boot PlayerPhaseWiring wires startTutorialEvent tryCallSelectEvents and dest`() {
        val r = rig()
        r.assertFe8BootWiring()
    }

    private class Rig(
        private val romBytes: ByteArray,
        private val trace: EventTrace,
        private val run: ReferenceData.Run,
    ) {
        private val rom = RomView.ofRom(romBytes)
        private val chapters = ChapterData(rom)
        private val mem = ScriptMemory { a ->
            val o = a - ROM_BASE
            (romBytes[o].toInt() and 0xFF) or ((romBytes[o + 1].toInt() and 0xFF) shl 8)
        }

        private fun ew(step: Int): ByteArray = run.region(dumpTag(step), "ewram")
        private fun iw(step: Int): ByteArray = run.region(dumpTag(step), "iwram")

        private fun dumpTag(step: Int): String {
            val full = "f%05d".format(step)
            return if (run.dumps.containsKey(full)) full else "r%05d".format(step)
        }

        private fun keySlice(ewram: ByteArray): ByteArray =
            ewram.copyOfRange(KEY_STATUS - EWRAM, KEY_STATUS - EWRAM + KeyStatus.STRUCT_SIZE)

        private fun tutorialPtr(chapterIndex: Int, index: Int): Int {
            val group = chapters.getChapterEventDataPointer(chapterIndex)
            val list = u32(romBytes, group - ROM_BASE + TUTORIAL_EVENTS_OFFSET)
            return if (list == 0) 0 else u32(romBytes, list - ROM_BASE + 4 * index)
        }

        private fun seedEventState(snap: EventSnapshot, state: EventState) {
            snap.slots.copyInto(state.slots)
            snap.slotQueue.copyInto(state.slotQueue)
            state.slotCounter = snap.slotCounter
            snap.callQueue.forEachIndexed { i, e ->
                state.callQueue[2 * i] = e.events
                state.callQueue[2 * i + 1] = (e.execType and 0xFF) or ((e.isUsed and 0xFF) shl 8)
            }
            snap.activeQueue.forEachIndexed { i, e ->
                state.activeQueue[2 * i] = e.evt1
                state.activeQueue[2 * i + 1] = e.evt2
            }
        }

        private fun seedProcEngine(
            procs: ProcEngine,
            state: EventState,
            flags: EventFlags,
            playSt: PlaySt,
            keys: KeyStatus,
            onLock: (Int) -> Unit,
            extraSetup: (EventEngine) -> Unit = {},
        ): EventEngine {
            val engine = EventEngine(
                procs, state, mem,
                object : EventEngineHooks {
                    override fun lockGame() = onLock(1)
                    override fun resumeGameplay() = onLock(-1)
                },
            )
            val core = EventScrCore(
                state, mem, flags = flags, rng = RngService(), playSt = playSt, keys = keys,
                hooks = object : EventScrHooks {
                    override fun tutorialEvent(chapterIndex: Int, index: Int): Int =
                        tutorialPtr(chapterIndex, index)
                },
            )
            core.registerAll(engine)
            extraSetup(engine)
            return engine
        }

        private fun seedInfos(
            flags: EventFlags,
            playSt: PlaySt,
            events: EventEngine,
        ): EventInfos = EventInfos(mem, flags, playSt).also { infos ->
            infos.hooks = object : EventInfoHooks {
                override fun callEvent(script: Int, execType: Int) =
                    events.callEvent(script, execType)

                override fun chapterEventGroup(chapter: Int): Int =
                    chapters.getChapterEventDataPointer(chapter)
            }
        }

        private fun readPool(
            ewram: ByteArray,
            iwram: ByteArray,
            handle: Int,
            map: BmMap,
            pool: BmMap.Pool,
        ) {
            val ptr = readWord(ewram, iwram, handle)
            for (y in -2 until map.sizeY + 2) {
                val row = readWord(ewram, iwram, ptr + 4 * y)
                for (x in 0 until map.sizeX + 2) {
                    pool[y, x] = readByte(ewram, iwram, row + x)
                }
            }
        }

        private fun seedActive(
            ewram: ByteArray,
            iwram: ByteArray,
            units: UnitTable,
            phase: PlayerPhase,
        ) {
            val ptr = readWord(ewram, iwram, GACTIVEUNIT)
            phase.active.unit = if (ptr == 0) 0 else {
                val off = ptr - UnitTable.BLUE_ADDRESS
                units.bytes[off + 0x0B].toInt() and 0xFF
            }
            phase.active.unitId = readByte(ewram, iwram, GACTIVEUNIT_ID)
            phase.active.moveOriginX = readHalf(ewram, iwram, GACTIVEUNIT_ORIGIN)
            phase.active.moveOriginY = readHalf(ewram, iwram, GACTIVEUNIT_ORIGIN + 2)
            phase.active.action.subjectIndex = readByte(ewram, iwram, ACTION_DATA + 0x0C)
            phase.active.action.targetIndex = readByte(ewram, iwram, ACTION_DATA + 0x0D)
            phase.active.action.xMove = readByte(ewram, iwram, ACTION_DATA + 0x0E)
            phase.active.action.yMove = readByte(ewram, iwram, ACTION_DATA + 0x0F)
            phase.active.action.moveCount = readByte(ewram, iwram, ACTION_DATA + 0x10)
            phase.active.action.unitActionType = readByte(ewram, iwram, ACTION_DATA + 0x11)
        }

        private fun stepAllTrees(procs: ProcEngine, lock: Int) {
            for (t in 0 until 8) {
                if (t != 2 || lock == 0) procs.run(procs.root(t))
            }
        }

        fun assertEnqueueStep(
            aStep: Int,
            bStep: Int,
            stubContinue: Set<Int> = emptySet(),
            stubYield: Set<Int> = emptySet(),
        ) {
            val a = trace.at(aStep)
            val b = trace.at(bStep)
            val ea = checkNotNull(a.engine) { "engine at f$aStep" }
            val eb = checkNotNull(b.engine) { "engine at f$bStep" }
            val state = EventState()
            seedEventState(a, state)
            val flags = Fe8EventFlags.create().also {
                it.copyIn(a.permanentFlags!! + a.chapterFlags!!)
            }
            val playSt = PlaySt().also { it.decode(a.playSt!!, 0) }
            val procs = ProcEngine()
            var enqueueCalls = 0
            val events = seedProcEngine(procs, state, flags, playSt, KeyStatus(), {}) { eng ->
                val baseEnqueue = eng.lo[0x0B]
                eng.register(0x0B, EventHandler { p ->
                    enqueueCalls++
                    baseEnqueue.run(p)
                })
                for (code in stubContinue) eng.register(code, EventHandler { EVC_ADVANCE_CONTINUE })
                for (code in stubYield) eng.register(code, EventHandler { EVC_ADVANCE_YIELD })
            }
            val proc = events.create(ea.pEventStart, ea.execType)
            proc.pEventIdk = ea.pEventIdk
            proc.pEventCurrent = ea.pEventCurrent
            proc.evStateBits = ea.evStateBits
            proc.evStallTimer = ea.evStallTimer

            events.onUpdate(proc)

            assertTrue(enqueueCalls > 0, "f$aStep -> f$bStep did not execute Event0B_EnqueueCall")
            assertEquals(eb.pEventStart, proc.pEventStart, "f$bStep pEventStart")
            assertEquals(eb.pEventIdk, proc.pEventIdk, "f$bStep pEventIdk")
            assertEquals(eb.pEventCurrent, proc.pEventCurrent, "f$bStep pEventCurrent")
            assertEquals(eb.evStateBits, proc.evStateBits, "f$bStep evStateBits")
            assertEquals(eb.evStallTimer, proc.evStallTimer, "f$bStep evStallTimer")
            assertEquals(eb.execType, proc.execType, "f$bStep execType")
            for (i in 1 until EventState.SLOT_COUNT) {
                assertEquals(b.slots[i], state.slots[i], "f$bStep slot$i")
            }
            assertTrue(state.slotQueue.contentEquals(b.slotQueue), "f$bStep slotQueue")
            assertEquals(b.slotCounter, state.slotCounter, "f$bStep slotCounter")
            b.activeQueue.forEachIndexed { i, entry ->
                assertEquals(entry.evt1, state.activeQueue[2 * i], "f$bStep active[$i].evt1")
                assertEquals(entry.evt2, state.activeQueue[2 * i + 1], "f$bStep active[$i].evt2")
            }
            val outFlags = ByteArray(0x30).also { flags.copyOut(it) }
            val wantFlags = (b.permanentFlags!! + b.chapterFlags!!).toList()
            assertEquals(wantFlags, outFlags.toList(), "f$bStep flags")
            val wantPlaySt = PlaySt().also { it.decode(b.playSt!!, 0) }
            assertEquals(wantPlaySt.tutorialCounter, playSt.tutorialCounter, "f$bStep tutCounter")
            assertEquals(
                wantPlaySt.tutorialExecType,
                playSt.tutorialExecType,
                "f$bStep tutExecType",
            )
        }

        fun assertPhaseStartLeavesOnSelectQueued() {
            val ew = ew(18531)
            val iw = iw(18531)
            val playSt = PlaySt().also { it.decode(ew, PLAY_ST - EWRAM) }
            assertEquals(1, playSt.tutorialCounter, "seed r18531 tutorialCounter")
            assertEquals(2, playSt.tutorialExecType, "seed r18531 tutorialExecType (ONSELECT)")

            val bm = BmSt().also { it.decode(ew, BM_ST - EWRAM) }
            val map = BmMap(rom, chapters, bm).also { it.initChapterMap(playSt.chapterIndex) }
            val units = UnitTable(rom).also { it.loadFrom(ew) }
            readPool(ew, iw, GBM_MAP_TERRAIN, map, map.terrain)
            readPool(ew, iw, GBM_MAP_UNIT, map, map.unit)
            val flags = Fe8EventFlags.create().also { it.copyIn(iw, FLAGS - IWRAM) }
            val state = EventState()
            val procs = ProcEngine()
            val keys = KeyStatus().also { it.readFrom(keySlice(ew)) }
            val events = seedProcEngine(procs, state, flags, playSt, keys, { bm.lock += it })
            val infos = seedInfos(flags, playSt, events)
            val phase = PlayerPhase(
                procs, bm, playSt, map, units, CamMove(procs, bm),
                EntityBmMaps(map, units, playSt), BmMapRender(bm, map, BgHardware()), keys,
                { events.eventEngineExists() },
            )
            var startCalls = 0
            phase.hooks.startTutorialEvent = ProcFunc {
                startCalls++
                infos.startPlayerPhaseStartTutorialEvent()
            }
            phase.hooks.tryCallSelectEvents = ProcFunc { infos.tryCallSelectEvents() }

            val ppProc = procs.start(phase.script, 2)
            // Step frames past label 0 until PlayerPhase_MainIdle (scrCur == 16).
            for (step in 0 until 4) {
                stepAllTrees(procs, bm.lock)
                if (ppProc.scrCur == 16) break
            }
            assertEquals(1, startCalls, "startTutorialEvent called once at phase start")
            assertEquals(16, ppProc.scrCur, "PlayerPhase reaches MainIdle without event blocking")
            assertFalse(events.eventEngineExists(), "no StdEventEngine started for PLAYERPHASE")
            assertEquals(0, bm.lock, "game remains unlocked")
            assertEquals(1, playSt.tutorialCounter, "ONSELECT tutorial stays queued")
            assertEquals(2, playSt.tutorialExecType, "ONSELECT exec type stays queued")
        }

        fun assertSelectDispatchWindow() {
            val ew = ew(18539)
            val iw = iw(18539)
            val playSt = PlaySt().also { it.decode(ew, PLAY_ST - EWRAM) }
            val bm = BmSt().also { it.decode(ew, BM_ST - EWRAM) }
            val map = BmMap(rom, chapters, bm).also { it.initChapterMap(playSt.chapterIndex) }
            val units = UnitTable(rom).also { it.loadFrom(ew) }
            readPool(ew, iw, GBM_MAP_TERRAIN, map, map.terrain)
            readPool(ew, iw, GBM_MAP_UNIT, map, map.unit)
            val flags = Fe8EventFlags.create().also { it.copyIn(iw, FLAGS - IWRAM) }
            val state = EventState()
            seedEventState(trace.at(18539), state)
            val procs = ProcEngine()
            val keys = KeyStatus().also { it.readFrom(keySlice(ew)) }
            val events = seedProcEngine(
                procs, state, flags, playSt, keys, { bm.lock += it },
            ) { eng ->
                eng.register(0x1A, EventHandler { EVC_ADVANCE_YIELD })
                eng.register(0x2E, EventHandler {
                    state.slots[0xC] = 1
                    EVC_ADVANCE_CONTINUE
                })
                eng.register(0x31, EventHandler { EVC_ADVANCE_YIELD })
                eng.register(0x3B, EventHandler { EVC_ADVANCE_CONTINUE })
            }
            val infos = seedInfos(flags, playSt, events)
            val moves = MovementMap(rom, map, units, playSt)
            val phase = PlayerPhase(
                procs, bm, playSt, map, units, CamMove(procs, bm),
                EntityBmMaps(map, units, playSt), BmMapRender(bm, map, BgHardware()), keys,
                { events.eventEngineExists() }, movementMap = moves,
            )
            seedActive(ew, iw, units, phase)
            var selectResult: Boolean? = null
            phase.hooks.unitSelectEnabled = true
            phase.hooks.muExists = ProcBoolFunc { true }
            phase.hooks.tryCallSelectEvents = ProcFunc {
                selectResult = infos.tryCallSelectEvents()
            }
            phase.hooks.displayMoveRangeGraphics = { bm.gameStateBits = bm.gameStateBits or 1 }

            val ppProc = procs.start(phase.script, 2)
            procs.goto(ppProc, 9)
            stepAllTrees(procs, bm.lock)
            assertEquals(16, ppProc.scrCur, "seed f18539 PlayerPhase at MainIdle")

            for (f in 18540..18560) {
                keys.readFrom(keySlice(ew(f)))
                stepAllTrees(procs, bm.lock)

                val at = "select f$f"
                val wantProcs = trace.procsAt(f)
                val wantPp = wantProcs.find("gProcScr_PlayerPhase")!!
                assertEquals(wantPp.scrCur, ppProc.scrCur, "$at PlayerPhase scrCur")
                assertEquals(wantPp.u8(0x28), ppProc.lockCnt, "$at PlayerPhase lockCnt")
                assertEquals(wantPp.u32(0x0C) != 0, ppProc.idleCb != null, "$at PlayerPhase repeat")

                val selProc = procs.find(phase.selectEventsScript)
                val wantSel = wantProcs.find("gProcScr_0859ACE8")
                assertEquals(wantSel != null, selProc != null, "$at selectEventsScript exists")
                if (wantSel != null) {
                    assertEquals(wantSel.scrCur, selProc!!.scrCur, "$at selectEventsScript scrCur")
                }

                val snap = trace.at(f)
                val myEng = events.findStd()?.state as? EventEngineProc
                val wantEng = snap.engine
                assertEquals(wantEng != null, myEng != null, "$at StdEventEngine exists")
                if (wantEng != null) {
                    assertEquals(wantEng.pEventStart, myEng!!.pEventStart, "$at pEventStart")
                    assertEquals(wantEng.pEventIdk, myEng.pEventIdk, "$at pEventIdk")
                    assertEquals(wantEng.pEventCurrent, myEng.pEventCurrent, "$at pEventCurrent")
                    assertEquals(wantEng.evStateBits, myEng.evStateBits, "$at evStateBits")
                    assertEquals(wantEng.evStallTimer, myEng.evStallTimer, "$at evStallTimer")
                    assertEquals(wantEng.execType, myEng.execType, "$at execType")
                }

                val wantBmLock = ew(f)[BM_ST - EWRAM + 1].toInt() and 0xFF
                assertEquals(wantBmLock, bm.lock, "$at gBmSt.lock")
                assertEquals(
                    readByte(ew(f), iw(f), GACTIVEUNIT_ID), phase.active.unitId,
                    "$at active.unitId",
                )
                val wantPs = PlaySt().also { it.decode(snap.playSt!!, 0) }
                assertEquals(wantPs.tutorialCounter, playSt.tutorialCounter, "$at tutCounter")
                assertEquals(wantPs.tutorialExecType, playSt.tutorialExecType, "$at tutExecType")
            }
            assertEquals(true, selectResult, "tryCallSelectEvents returned true at f18541")
            assertEquals(0, infos.registry.idx, "clearActiveEventRegistry left idx 0")
        }

        fun assertDestHintWindow() {
            val ew = ew(18799)
            val iw = iw(18799)
            val playSt = PlaySt().also { it.decode(ew, PLAY_ST - EWRAM) }
            val bm = BmSt().also { it.decode(ew, BM_ST - EWRAM) }
            val map = BmMap(rom, chapters, bm).also { it.initChapterMap(playSt.chapterIndex) }
            val units = UnitTable(rom).also { it.loadFrom(ew) }
            readPool(ew, iw, GBM_MAP_TERRAIN, map, map.terrain)
            readPool(ew, iw, GBM_MAP_UNIT, map, map.unit)
            readPool(ew, iw, GBM_MAP_MOVEMENT, map, map.movement)
            val flags = Fe8EventFlags.create().also { it.copyIn(iw, FLAGS - IWRAM) }
            val state = EventState()
            seedEventState(trace.at(18799), state)
            val procs = ProcEngine()
            val keys = KeyStatus().also { it.readFrom(keySlice(ew)) }
            val events = seedProcEngine(
                procs, state, flags, playSt, keys, { bm.lock += it },
            ) { eng ->
                eng.register(0x3C, EventHandler { p ->
                    val sub = mem.u16(p.pEventCurrent) and 0xF
                    if (sub == 0) {
                        state.slots[0xC] = (bm.playerCursorX and 0xFFFF) or
                            ((bm.playerCursorY and 0xFFFF) shl 16)
                    } else if (sub == 1) {
                        bm.playerCursorX = 4
                        bm.playerCursorY = 5
                    }
                    EVC_ADVANCE_CONTINUE
                })
                eng.register(0x26, EventHandler { EVC_ADVANCE_YIELD })
                eng.register(0x3B, EventHandler { EVC_ADVANCE_CONTINUE })
            }
            val infos = seedInfos(flags, playSt, events)
            val phase = PlayerPhase(
                procs, bm, playSt, map, units, CamMove(procs, bm),
                EntityBmMaps(map, units, playSt), BmMapRender(bm, map, BgHardware()), keys,
                { events.eventEngineExists() },
            )
            seedActive(ew, iw, units, phase)
            var destResult: Boolean? = null
            phase.hooks.startDestSelectedEvent = {
                infos.startDestSelectedEvent().also { destResult = it }
            }

            val ppProc = procs.start(phase.script, 2)
            ppProc.scrCur = 25
            ppProc.idleCb = ProcFunc { phase.playerPhaseRangeDisplayIdle(it) }

            for (f in 18800..18805) {
                keys.readFrom(keySlice(ew(f)))
                stepAllTrees(procs, bm.lock)

                val at = "destHint f$f"
                val wantPp = trace.procsAt(f).find("gProcScr_PlayerPhase")!!
                assertEquals(wantPp.scrCur, ppProc.scrCur, "$at PlayerPhase scrCur")
                val snap = trace.at(f)
                val myEng = events.findStd()?.state as? EventEngineProc
                val wantEng = checkNotNull(snap.engine) { "$at want engine" }
                assertNotNull(myEng, "$at StdEventEngine started")
                assertEquals(wantEng.pEventStart, myEng!!.pEventStart, "$at pEventStart")
                assertEquals(wantEng.pEventIdk, myEng.pEventIdk, "$at pEventIdk")
                assertEquals(wantEng.pEventCurrent, myEng.pEventCurrent, "$at pEventCurrent")
                assertEquals(wantEng.evStateBits, myEng.evStateBits, "$at evStateBits")
                assertEquals(wantEng.evStallTimer, myEng.evStallTimer, "$at evStallTimer")
                assertEquals(wantEng.execType, myEng.execType, "$at execType")

                val wantBmLock = ew(f)[BM_ST - EWRAM + 1].toInt() and 0xFF
                assertEquals(wantBmLock, bm.lock, "$at gBmSt.lock")
                val wantPs = PlaySt().also { it.decode(snap.playSt!!, 0) }
                assertEquals(wantPs.tutorialCounter, playSt.tutorialCounter, "$at tutCounter")
                assertEquals(wantPs.tutorialExecType, playSt.tutorialExecType, "$at tutExecType")
                val wantCurX = ew(f)[BM_ST - EWRAM + 0x14].toInt() and 0xFF
                val wantCurY = ew(f)[BM_ST - EWRAM + 0x16].toInt() and 0xFF
                assertEquals(wantCurX, bm.playerCursorX, "$at curX")
                assertEquals(wantCurY, bm.playerCursorY, "$at curY")
            }
            assertEquals(true, destResult, "startDestSelectedEvent returned true at f18800")
        }

        fun assertDestAcceptWindow() {
            val ew = ew(19019)
            val iw = iw(19019)
            val playSt = PlaySt().also { it.decode(ew, PLAY_ST - EWRAM) }
            val bm = BmSt().also { it.decode(ew, BM_ST - EWRAM) }
            val map = BmMap(rom, chapters, bm).also { it.initChapterMap(playSt.chapterIndex) }
            val units = UnitTable(rom).also { it.loadFrom(ew) }
            readPool(ew, iw, GBM_MAP_TERRAIN, map, map.terrain)
            readPool(ew, iw, GBM_MAP_UNIT, map, map.unit)
            readPool(ew, iw, GBM_MAP_MOVEMENT, map, map.movement)
            val flags = Fe8EventFlags.create().also { it.copyIn(iw, FLAGS - IWRAM) }
            val state = EventState()
            seedEventState(trace.at(19019), state)
            val procs = ProcEngine()
            val keys = KeyStatus().also { it.readFrom(keySlice(ew)) }
            val events = seedProcEngine(
                procs, state, flags, playSt, keys, { bm.lock += it },
            ) { eng ->
                eng.register(0x3C, EventHandler {
                    state.slots[0xC] = (bm.playerCursorX and 0xFFFF) or
                        ((bm.playerCursorY and 0xFFFF) shl 16)
                    EVC_ADVANCE_CONTINUE
                })
                eng.register(0x0D, EventHandler { EVC_ADVANCE_YIELD })
                eng.register(0x3D, EventHandler { EVC_ADVANCE_CONTINUE })
            }
            val infos = seedInfos(flags, playSt, events)
            val phase = PlayerPhase(
                procs, bm, playSt, map, units, CamMove(procs, bm),
                EntityBmMaps(map, units, playSt), BmMapRender(bm, map, BgHardware()), keys,
                { events.eventEngineExists() },
            )
            seedActive(ew, iw, units, phase)
            var destResult: Boolean? = null
            phase.hooks.startDestSelectedEvent = {
                infos.startDestSelectedEvent().also { destResult = it }
            }

            val ppProc = procs.start(phase.script, 2)
            ppProc.scrCur = 25
            ppProc.idleCb = ProcFunc { phase.playerPhaseRangeDisplayIdle(it) }

            for (f in 19020..19024) {
                keys.readFrom(keySlice(ew(f)))
                stepAllTrees(procs, bm.lock)

                // f19023 in the capture is mid-VBlank inside EventEngine_OnEnd before UnlockGame;
                // our instant restoreAfterEvent5 completes onEnd in f19023, matching f19024.
                if (f == 19023) continue

                val at = "destAccept f$f"
                val wantPp = trace.procsAt(f).find("gProcScr_PlayerPhase")!!
                assertEquals(wantPp.scrCur, ppProc.scrCur, "$at PlayerPhase scrCur")
                val snap = trace.at(f)
                val myEng = events.findStd()?.state as? EventEngineProc
                val wantEng = snap.engine
                assertEquals(wantEng != null, myEng != null, "$at StdEventEngine exists")
                if (wantEng != null) {
                    assertEquals(wantEng.pEventStart, myEng!!.pEventStart, "$at pEventStart")
                    assertEquals(wantEng.pEventIdk, myEng.pEventIdk, "$at pEventIdk")
                    assertEquals(wantEng.pEventCurrent, myEng.pEventCurrent, "$at pEventCurrent")
                    assertEquals(wantEng.evStateBits, myEng.evStateBits, "$at evStateBits")
                    assertEquals(wantEng.evStallTimer, myEng.evStallTimer, "$at evStallTimer")
                    assertEquals(wantEng.execType, myEng.execType, "$at execType")
                }

                val wantBmLock = ew(f)[BM_ST - EWRAM + 1].toInt() and 0xFF
                assertEquals(wantBmLock, bm.lock, "$at gBmSt.lock")
                val wantPs = PlaySt().also { it.decode(snap.playSt!!, 0) }
                assertEquals(wantPs.tutorialCounter, playSt.tutorialCounter, "$at tutCounter")
                assertEquals(wantPs.tutorialExecType, playSt.tutorialExecType, "$at tutExecType")
            }
            assertEquals(true, destResult, "startDestSelectedEvent returned true at f19020")
        }

        fun assertFe8BootWiring() {
            var nextKey = 0
            val boot = Fe8Boot(rom, { nextKey }, RngService())
            // Touch bmapMain so PlayerPhaseWiring runs and wires PlayerPhase hooks.
            boot.bmapMain

            fun seedBoot(step: Int) {
                val ew = ew(step)
                val iw = iw(step)
                boot.engine.init()
                boot.control.playSt.decode(ew, PLAY_ST - EWRAM)
                boot.loop.bmSt.decode(ew, BM_ST - EWRAM)
                boot.battleMap.hooks.readGameSaveCoreGfx()
                boot.units.loadFrom(ew)
                boot.battleBmMap.initChapterMap(boot.control.playSt.chapterIndex)
                readPool(ew, iw, GBM_MAP_TERRAIN, boot.battleBmMap, boot.battleBmMap.terrain)
                readPool(ew, iw, GBM_MAP_UNIT, boot.battleBmMap, boot.battleBmMap.unit)
                readPool(ew, iw, GBM_MAP_MOVEMENT, boot.battleBmMap, boot.battleBmMap.movement)
                boot.flags.copyIn(iw, FLAGS - IWRAM)
            }

            // 1. Phase start (r18531) leaves ONSELECT (2, 1) queued and reaches idle.
            seedBoot(18531)
            val pp1 = boot.engine.start(boot.playerPhaseScript, 2)
            for (i in 0 until 4) {
                boot.frame()
                if (pp1.scrCur == 16) break
            }
            assertEquals(16, pp1.scrCur, "boot PlayerPhase reaches MainIdle")
            assertNull(boot.eventEngine.findStd(), "boot startTutorialEvent skips ONSELECT")
            assertEquals(1, boot.control.playSt.tutorialCounter)
            assertEquals(2, boot.control.playSt.tutorialExecType)

            // 2. Unit select (r18539 -> A -> selectEventsScript) dispatches Tutorial0.
            seedBoot(18539)
            val pp2 = boot.engine.start(boot.playerPhaseScript, 2)
            boot.engine.goto(pp2, 9)
            boot.frame()
            assertEquals(16, pp2.scrCur)
            nextKey = 1 // A_BUTTON
            boot.frame() // f18540: PlayerPhase_MainIdle selects Eirika
            nextKey = 0
            boot.frame() // f18541: selectEventsScript runs wired tryCallSelectEvents
            val selEv = boot.eventEngine.findStd()?.state as? EventEngineProc
            assertNotNull(selEv, "boot tryCallSelectEvents started StdEventEngine")
            assertEquals(0x089EF2B0, selEv!!.pEventStart, "boot dispatched Tutorial0")
            assertEquals(EV_EXEC_CUTSCENE, selEv.execType, "boot Tutorial0 execType")
            assertEquals(1, boot.loop.bmSt.lock, "boot locked game on Tutorial0")
            assertEquals(0, boot.control.playSt.tutorialCounter, "boot cleared tutorialCounter")
            assertEquals(0, boot.control.playSt.tutorialExecType, "boot cleared tutorialExecType")

            // 3. Destination select dispatches Tutorial1 via wired startDestSelectedEvent:
            //    off-tile (10,5) stops in STAL3 8 (execType EV_EXEC_CUTSCENE, tut cleared 0/0);
            //    accepted tile (4,5) passes CHECK_CURSOR and enqueues Tutorial2 (4, 3).
            fun bootToRangeIdle(cursorX: Int, cursorY: Int): gbafe.proc.Proc {
                seedBoot(18539)
                boot.control.playSt.tutorialCounter = 0
                boot.control.playSt.tutorialExecType = 0
                val pp = boot.engine.start(boot.playerPhaseScript, 2)
                boot.engine.goto(pp, 9)
                boot.frame()
                nextKey = 1
                boot.frame() // select Eirika
                nextKey = 0
                boot.frame() // selectEventsScript (nothing queued)
                boot.frame() // PlayerPhase_InitUnitMovementSelect -> sleep(1)
                boot.frame() // enter PlayerPhase_RangeDisplayIdle (scrCur == 25)
                assertEquals(25, pp.scrCur, "boot entered RangeDisplayIdle")
                boot.control.playSt.tutorialExecType = 3
                boot.control.playSt.tutorialCounter = 2
                boot.loop.bmSt.playerCursorX = cursorX
                boot.loop.bmSt.playerCursorY = cursorY
                nextKey = 1
                boot.frame() // A in RangeDisplayIdle runs wired startDestSelectedEvent
                nextKey = 0
                return pp
            }

            val ppHint = bootToRangeIdle(10, 5)
            val hintEv = boot.eventEngine.findStd()?.state as? EventEngineProc
            assertNotNull(hintEv, "boot startDestSelectedEvent started Tutorial1 hint")
            assertEquals(0x089EF328, hintEv!!.pEventStart, "boot dispatched Tutorial1 hint")
            assertEquals(EV_EXEC_CUTSCENE, hintEv.execType, "boot Tutorial1 hint execType")
            assertEquals(1, boot.loop.bmSt.lock, "boot locked game on Tutorial1 hint")
            assertEquals(25, ppHint.scrCur, "boot PlayerPhase stayed in RangeDisplayIdle")
            assertEquals(0, boot.control.playSt.tutorialCounter, "boot cleared tutorialCounter")
            assertEquals(0, boot.control.playSt.tutorialExecType, "boot cleared tutorialExecType")

            val ppAccept = bootToRangeIdle(4, 5)
            val acceptEv = boot.eventEngine.findStd()?.state as? EventEngineProc
            assertNotNull(acceptEv, "boot startDestSelectedEvent started Tutorial1 accept")
            assertEquals(0x089EF328, acceptEv!!.pEventStart, "boot dispatched Tutorial1 accept")
            assertEquals(1, boot.loop.bmSt.lock, "boot locked game on Tutorial1 accept")
            assertEquals(25, ppAccept.scrCur, "boot PlayerPhase stayed in RangeDisplayIdle")
            // The ASMC (PlayPhaseForcePressAButtonInRangeDisp) yields one frame before the enqueue.
            boot.frame()
            assertEquals(3, boot.control.playSt.tutorialCounter, "boot queued Tutorial2")
            assertEquals(4, boot.control.playSt.tutorialExecType, "boot queued AFTERMOVE")
        }

        private fun readByte(ew: ByteArray, iw: ByteArray, addr: Int): Int =
            if (addr >= IWRAM) {
                iw[addr - IWRAM].toInt() and 0xFF
            } else {
                ew[addr - EWRAM].toInt() and 0xFF
            }

        private fun readHalf(ew: ByteArray, iw: ByteArray, addr: Int): Int =
            readByte(ew, iw, addr) or (readByte(ew, iw, addr + 1) shl 8)

        private fun readWord(ew: ByteArray, iw: ByteArray, addr: Int): Int =
            readHalf(ew, iw, addr) or (readHalf(ew, iw, addr + 2) shl 16)
    }

    private companion object {
        const val RUN = "ram_trace_newgame_easy"
        const val ROM_BASE = 0x08000000
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val KEY_STATUS = 0x02024CC0
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val FLAGS = 0x03005250
        const val ACTION_DATA = 0x0203A958
        const val GACTIVEUNIT = 0x03004E50
        const val GACTIVEUNIT_ID = 0x0202BE44
        const val GACTIVEUNIT_ORIGIN = 0x0202BE48
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val GBM_MAP_MOVEMENT = 0x0202E4E0
        const val TUTORIAL_EVENTS_OFFSET = 0x1C

        fun loadRig(): Rig? {
            val ref = ReferenceData.fromEnvironment()
            val assets =
                System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
            val romFile = File(assets, "baserom.gba")
            if (!ref.available || !romFile.isFile) return null
            if (ProcArrayDump.romNames().isEmpty()) return null
            val trace = EventTrace.prologue(ref, RUN) ?: return null
            return Rig(romFile.readBytes(), trace, ref.run(RUN))
        }

        fun u32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or
                ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or
                ((b[o + 3].toInt() and 0xFF) shl 24)
    }
}
