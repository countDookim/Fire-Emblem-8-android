package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.MovementMap
import fe8.game.bm.PLAY_FLAG_HARD
import fe8.game.bm.PlaySt
import fe8.game.bm.PlayerPhase
import fe8.game.bm.UnitTable
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.EventInfoHooks
import fe8.game.event.EventInfos
import fe8.game.event.EventScrCore
import fe8.game.event.EventScrHooks
import fe8.game.event.EventState
import fe8.game.event.EventUnitState
import fe8.game.event.EventUnitStateHooks
import fe8.game.event.EventCursorCamera
import fe8.game.event.ScriptMemory
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * PC-10b: differential verification of tutorial script commands in `ram_trace_newgame_easy`
 * (`PC-cap3`, `f18216..18454`, `f18541..18991`, `f19020..19024`):
 * - `Event19_Checks` sub 7 (`CHECK_TUTORIAL`) returning `1` and entering
 *   `EventScr_Prologue_ExecTut` via `EventScr_CallOnTutorialMode` at `f18229`.
 * - `Event2E_CheckAt` sub 1 (`CHECK_ACTIVE`, opcode `0x2E` in `EventScr_Tutorial_Exec0`; note
 *   `Event33_CheckUnitVarious` `CHECK_ACTIVEID` is not in any tutorial script) storing the active
 *   unit's character id in slot 0xC at `f18542`.
 * - `Event11_SetIgnoredKeys` (`IGNORE_KEYS`) updating `gKeyStatusIgnoredSt` (`0x010A` at `f18747`
 *   and `f18988`, `0x0000` at `f18800`) and matching the `sKeyStatusBuffer` slice in EWRAM.
 * - `Event3C_MoveCursor` (`CHECK_CURSOR` at `f18800` and `f19020`; slot-B `SET_CURSOR` snapping
 *   the cursor to `(4, 5)` at `f18800`).
 * - `Event0B_EnqueueCall` and `EnqueueTutEvent` re-queueing `Tutorial1` (`DESTSELECTED`, type 3,
 *   counter 2) at `f18988`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
class EasyTutorialCommandsDifferentialTest {
    private var shared: Rig? = null
    private var loaded = false

    /** Releases cached trace and dump references once all tests in this class complete. */
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
    @VerifiesPort("Event19_Checks")
    fun `Event19_Checks sub 7 returns 1 on Easy and enters ExecTut at f18229`() {
        val r = rig()
        r.assertExecTutCheckTutorial()
    }

    @Test
    @VerifiesPort("Event2E_CheckAt")
    fun `Event2E_CheckAt sub 1 CHECK_ACTIVE stores active character id in slot C at f18542`() {
        val r = rig()
        r.assertTutorial0CheckActive()
    }

    @Test
    @VerifiesPort("Event11_SetIgnoredKeys")
    fun `Event11_SetIgnoredKeys updates ignore mask at f18747 f18800 and f18988`() {
        val r = rig()
        r.assertIgnoreKeys()
    }

    @Test
    @VerifiesPort("Event3C_MoveCursor")
    fun `Event3C_MoveCursor CHECK_CURSOR and SET_CURSOR match f18800 and f19020`() {
        val r = rig()
        r.assertMoveCursor()
    }

    @Test
    @VerifiesPort("Event0B_EnqueueCall", "EnqueueTutEvent")
    fun `Event0B_EnqueueCall re-queues Tutorial1 DESTSELECTED at f18988`() {
        val r = rig()
        r.assertDestSelectedRequeue()
    }

    private class Rig(
        private val romBytes: ByteArray,
        private val trace: EventTrace,
        private val run: ReferenceData.Run,
    ) {
        private val rom = RomView.ofRom(romBytes)
        private val chapters = ChapterData(rom)
        private val scripts = ScriptNames.load()
        private val sym = RamSymbols.fromEnvironment()
        private val keyStatusAddr = sym["sKeyStatusBuffer"].address
        private val keyIgnoreAddr = sym["gKeyStatusIgnoredSt"].address
        private val bmStAddr = sym["gBmSt"].address
        private val playStAddr = sym["gPlaySt"].address
        private val activeUnitAddr = sym["gActiveUnit"].address
        private val activeUnitIdAddr = sym["gActiveUnitId"].address
        private val activeOriginAddr = sym["gActiveUnitMoveOrigin"].address
        private val actionDataAddr = sym["gActionData"].address
        private val bmMapUnitAddr = sym["gBmMapUnit"].address
        private val bmMapTerrainAddr = sym["gBmMapTerrain"].address
        private val bmMapMovementAddr = sym["gBmMapMovement"].address
        private val flagsOffset = sym["gPermanentFlagBits"].address - GbaMem.IWRAM

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

        private fun ignoreMaskAt(step: Int): Int =
            GbaMem.u16(iw(step), GbaMem.IWRAM, keyIgnoreAddr)

        private fun keySlice(ewram: ByteArray): ByteArray = ewram.copyOfRange(
            keyStatusAddr - GbaMem.EWRAM,
            keyStatusAddr - GbaMem.EWRAM + KeyStatus.STRUCT_SIZE,
        )

        private fun tutorialPtr(chapterIndex: Int, index: Int): Int {
            val group = chapters.getChapterEventDataPointer(chapterIndex)
            val list = u32(romBytes, group - ROM_BASE + TUTORIAL_EVENTS_OFFSET)
            return if (list == 0) 0 else u32(romBytes, list - ROM_BASE + 4 * index)
        }

        private fun scanScript(startAddr: Int): List<Pair<Int, Int>> {
            val out = ArrayList<Pair<Int, Int>>()
            var pc = startAddr
            for (n in 0 until 64) {
                val head = mem.u16(pc)
                val code = head ushr 8
                val words = (head ushr 4) and 0xF
                if (words == 0) break
                out += pc to head
                if (code == 0x01) break
                pc += words * 2
            }
            return out
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
            val ptr = readWord(ewram, iwram, activeUnitAddr)
            phase.active.unit = if (ptr == 0) 0 else {
                val off = ptr - UnitTable.BLUE_ADDRESS
                units.bytes[off + 0x0B].toInt() and 0xFF
            }
            phase.active.unitId = readByte(ewram, iwram, activeUnitIdAddr)
            phase.active.moveOriginX = readHalf(ewram, iwram, activeOriginAddr)
            phase.active.moveOriginY = readHalf(ewram, iwram, activeOriginAddr + 2)
            phase.active.action.subjectIndex = readByte(ewram, iwram, actionDataAddr + 0x0C)
            phase.active.action.targetIndex = readByte(ewram, iwram, actionDataAddr + 0x0D)
            phase.active.action.xMove = readByte(ewram, iwram, actionDataAddr + 0x0E)
            phase.active.action.yMove = readByte(ewram, iwram, actionDataAddr + 0x0F)
            phase.active.action.moveCount = readByte(ewram, iwram, actionDataAddr + 0x10)
            phase.active.action.unitActionType = readByte(ewram, iwram, actionDataAddr + 0x11)
        }

        private fun stepAllTrees(procs: ProcEngine, lock: Int) {
            for (t in 0 until 8) {
                if (t != 2 || lock == 0) procs.run(procs.root(t))
            }
        }

        private fun assertEngineAndGlobalsEqual(
            step: Int,
            proc: EventEngineProc,
            state: EventState,
        ) {
            val snap = trace.at(step)
            val want = checkNotNull(snap.engine) { "engine at f$step" }
            assertEquals(want.pEventStart, proc.pEventStart, "f$step pEventStart")
            assertEquals(want.pEventIdk, proc.pEventIdk, "f$step pEventIdk")
            assertEquals(want.pEventCurrent, proc.pEventCurrent, "f$step pEventCurrent")
            assertEquals(want.evStateBits, proc.evStateBits, "f$step evStateBits")
            assertEquals(want.evStallTimer, proc.evStallTimer, "f$step evStallTimer")
            assertEquals(want.execType, proc.execType, "f$step execType")
            for (i in 1 until EventState.SLOT_COUNT) {
                assertEquals(snap.slots[i], state.slots[i], "f$step slot$i")
            }
            assertTrue(state.slotQueue.contentEquals(snap.slotQueue), "f$step slotQueue")
            assertEquals(snap.slotCounter, state.slotCounter, "f$step slotCounter")
            snap.activeQueue.forEachIndexed { i, entry ->
                assertEquals(entry.evt1, state.activeQueue[2 * i], "f$step active[$i].evt1")
                assertEquals(entry.evt2, state.activeQueue[2 * i + 1], "f$step active[$i].evt2")
            }
        }

        private fun newEngine(
            procs: ProcEngine,
            state: EventState,
            flags: EventFlags,
            playSt: PlaySt,
            keys: KeyStatus,
            onLock: (Int) -> Unit = {},
            extraSetup: (EventEngine) -> Unit = {},
        ): Pair<EventEngine, EventScrCore> {
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
            return engine to core
        }

        fun assertExecTutCheckTutorial() {
            val a = trace.at(18228)
            val b = trace.at(18229)
            val ea = checkNotNull(a.engine)
            val eb = checkNotNull(b.engine)

            // Confirm at runtime from ROM that f18228 runs EventScr_CallOnTutorialMode (0x19 sub 7)
            // and jumps into EventScr_Prologue_ExecTut.
            assertEquals(0x05, mem.u16(ea.pEventCurrent) ushr 8)
            val targetExecTut = mem.u16(ea.pEventCurrent + 4) or
                (mem.u16(ea.pEventCurrent + 6) shl 16)
            assertEquals(eb.pEventIdk, targetExecTut, "CALL target is EventScr_Prologue_ExecTut")
            assertEquals("EventScr_Prologue_ExecTut", scripts.name(eb.pEventIdk)?.name)
            val callOnTutAddr = mem.u16(ea.pEventCurrent + 12) or
                (mem.u16(ea.pEventCurrent + 14) shl 16)
            assertEquals("EventScr_CallOnTutorialMode", scripts.name(callOnTutAddr)?.name)
            val callOnTutCmds = scanScript(callOnTutAddr)
            assertEquals(
                0x1927,
                callOnTutCmds[0].second,
                "EventScr_CallOnTutorialMode starts 0x19 sub 7",
            )

            val state = EventState().also { seedEventState(a, it) }
            val flags = Fe8EventFlags.create().also {
                it.copyIn(a.permanentFlags!! + a.chapterFlags!!)
            }
            val playSt = PlaySt().also { it.decode(a.playSt!!, 0) }
            assertEquals(0, playSt.controller, "Easy seed has controller == 0")
            assertEquals(0, playSt.chapterStateBits and PLAY_FLAG_HARD)
            assertEquals(0, state.slots[0xC], "slot C before CHECK_TUTORIAL")

            val procs = ProcEngine()
            var checkCalls = 0
            val (events, core) = newEngine(procs, state, flags, playSt, KeyStatus()) { eng ->
                eng.register(0x1A, EventHandler { EVC_ADVANCE_YIELD })
            }
            val base19 = core.event19Checks
            events.register(0x19, EventHandler { p ->
                checkCalls++
                base19.run(p)
            })
            val proc = events.create(ea.pEventStart, ea.execType)
            proc.pEventIdk = ea.pEventIdk
            proc.pEventCurrent = ea.pEventCurrent
            proc.evStateBits = ea.evStateBits
            proc.evStallTimer = ea.evStallTimer

            events.onUpdate(proc)

            assertEquals(1, checkCalls, "Event19_Checks executed once")
            assertEquals(1, state.slots[0xC], "CHECK_TUTORIAL returned 1 on Easy")
            assertEngineAndGlobalsEqual(18229, proc, state)
        }

        fun assertTutorial0CheckActive() {
            val a = trace.at(18541)
            val b = trace.at(18542)
            val ea = checkNotNull(a.engine)
            val eb = checkNotNull(b.engine)

            // Confirm at runtime that Tutorial0 calls EventScr_Tutorial_Exec0 and f18541 points at
            // opcode 0x2E sub 1 (CHECK_ACTIVE, Event2E_CheckAt), not 0x33 (CHECK_ACTIVEID).
            assertEquals(tutorialPtr(0, 0), ea.pEventStart, "Tutorial0 at chapter 0 index 0")
            assertEquals("EventScr_Tutorial_Exec0", scripts.name(ea.pEventIdk)?.name)
            assertEquals(0x2E21, mem.u16(ea.pEventCurrent), "f18541 runs 0x2E sub 1 CHECK_ACTIVE")
            val exec0Cmds = scanScript(ea.pEventIdk)
            assertTrue(exec0Cmds.none { (it.second ushr 8) == 0x33 }, "Exec0 has no 0x33 command")

            // 1. Direct step replay f18541 -> f18542 with real EventUnitState.
            val ew18541 = ew(18541)
            val iw18541 = iw(18541)
            val units = UnitTable(rom).also { it.loadFrom(ew18541) }
            val activePtr = readWord(ew18541, iw18541, activeUnitAddr)
            val activeId = (activePtr - UnitTable.BLUE_ADDRESS) / UnitTable.UNIT_SIZE + 1
            assertEquals(activeId, units.bytes[units.unitOffset(activeId) + 0x0B].toInt() and 0xFF)
            val expectedPid = units.charId(activeId)

            val state = EventState().also { seedEventState(a, it) }
            val flags = Fe8EventFlags.create().also {
                it.copyIn(a.permanentFlags!! + a.chapterFlags!!)
            }
            val playSt = PlaySt().also { it.decode(a.playSt!!, 0) }
            val procs = ProcEngine()
            val loader = fe8.game.bm.UnitLoader(units, rom, { 0 }, RngService(), { false })
            val unitState = EventUnitState(
                state, mem, units, loader,
                object : EventUnitStateHooks {
                    override fun activeUnit(): Int = activeId
                },
            )
            var checkAtCalls = 0
            val (events, _) = newEngine(procs, state, flags, playSt, KeyStatus()) { eng ->
                unitState.registerOn(eng)
                val base2e = eng.lo[0x2E]
                eng.register(0x2E, EventHandler { p ->
                    checkAtCalls++
                    base2e.run(p)
                })
                eng.register(0x31, EventHandler { EVC_ADVANCE_YIELD })
            }
            val proc = events.create(ea.pEventStart, ea.execType)
            proc.pEventIdk = ea.pEventIdk
            proc.pEventCurrent = ea.pEventCurrent
            proc.evStateBits = ea.evStateBits
            proc.evStallTimer = ea.evStallTimer

            events.onUpdate(proc)

            assertEquals(1, checkAtCalls, "Event2E_CheckAt executed once")
            assertEquals(expectedPid, state.slots[0xC], "slot C holds active unit character id")
            assertEngineAndGlobalsEqual(18542, proc, state)

            // 2. Clean-slot replay from r18539 through PlayerPhase select (f18540 -> f18542) so
            //    slot C starts at 0 and must be written by Event2E_CheckAt to pass BNE.
            val ew39 = ew(18539)
            val iw39 = iw(18539)
            val ps39 = PlaySt().also { it.decode(ew39, playStAddr - GbaMem.EWRAM) }
            val bm39 = BmSt().also { it.decode(ew39, bmStAddr - GbaMem.EWRAM) }
            val map39 = BmMap(rom, chapters, bm39).also { it.initChapterMap(ps39.chapterIndex) }
            val units39 = UnitTable(rom).also { it.loadFrom(ew39) }
            readPool(ew39, iw39, bmMapTerrainAddr, map39, map39.terrain)
            readPool(ew39, iw39, bmMapUnitAddr, map39, map39.unit)
            val flags39 = Fe8EventFlags.create().also { it.copyIn(iw39, flagsOffset) }
            val state39 = EventState()
            assertEquals(0, state39.slots[0xC], "clean EventState starts with slot C == 0")
            val procs39 = ProcEngine()
            val keys39 = KeyStatus().also { it.readFrom(keySlice(ew39)) }
            val loader39 = fe8.game.bm.UnitLoader(units39, rom, { 0 }, RngService(), { false })
            lateinit var phase39: PlayerPhase
            val unitState39 = EventUnitState(
                state39, mem, units39, loader39,
                object : EventUnitStateHooks {
                    override fun activeUnit(): Int = phase39.active.unit
                },
            )
            var cleanCalls = 0
            val (events39, _) = newEngine(
                procs39, state39, flags39, ps39, keys39, { bm39.lock += it },
            ) { eng ->
                unitState39.registerOn(eng)
                val base2e = eng.lo[0x2E]
                eng.register(0x2E, EventHandler { p ->
                    cleanCalls++
                    base2e.run(p)
                })
                eng.register(0x1A, EventHandler { EVC_ADVANCE_YIELD })
                eng.register(0x31, EventHandler { EVC_ADVANCE_YIELD })
            }
            val infos39 = EventInfos(mem, flags39, ps39).also { infos ->
                infos.hooks = object : EventInfoHooks {
                    override fun callEvent(script: Int, execType: Int) =
                        events39.callEvent(script, execType)

                    override fun chapterEventGroup(chapter: Int): Int =
                        chapters.getChapterEventDataPointer(chapter)
                }
            }
            phase39 = PlayerPhase(
                procs39, bm39, ps39, map39, units39, CamMove(procs39, bm39),
                EntityBmMaps(map39, units39, ps39), BmMapRender(bm39, map39, BgHardware()), keys39,
                { events39.eventEngineExists() },
                movementMap = MovementMap(rom, map39, units39, ps39),
            )
            seedActive(ew39, iw39, units39, phase39)
            phase39.hooks.unitSelectEnabled = true
            phase39.hooks.muExists = ProcBoolFunc { true }
            phase39.hooks.tryCallSelectEvents = ProcFunc { infos39.tryCallSelectEvents() }
            phase39.hooks.displayMoveRangeGraphics = {
                bm39.gameStateBits = bm39.gameStateBits or 1
            }
            val ppProc = procs39.start(phase39.script, 2)
            procs39.goto(ppProc, 9)
            stepAllTrees(procs39, bm39.lock)
            for (f in 18540..18542) {
                keys39.readFrom(keySlice(ew(f)))
                stepAllTrees(procs39, bm39.lock)
            }
            val engProc = checkNotNull(events39.findStd()?.state as? EventEngineProc)
            assertEquals(1, cleanCalls, "clean replay executed Event2E_CheckAt")
            assertEquals(expectedPid, state39.slots[0xC], "clean replay wrote Eirika PID to slot C")
            assertEquals(
                eb.pEventCurrent,
                engProc.pEventCurrent,
                "clean replay took success branch",
            )
        }

        fun assertIgnoreKeys() {
            // 1. f18746 -> f18747: EventScr_Tutorial_Exec0 runs IGNORE_KEYS(R|START|B) = 0x010A.
            val a = trace.at(18746)
            val ea = checkNotNull(a.engine)
            assertEquals(0x1120, mem.u16(ea.pEventCurrent), "f18746 runs 0x11 sub 0")
            assertEquals(0x010A, mem.u16(ea.pEventCurrent + 2), "f18746 IGNORE_KEYS mask is 0x010A")

            val state = EventState().also { seedEventState(a, it) }
            val flags = Fe8EventFlags.create().also {
                it.copyIn(a.permanentFlags!! + a.chapterFlags!!)
            }
            val playSt = PlaySt().also { it.decode(a.playSt!!, 0) }
            val keys = KeyStatus().also {
                it.readFrom(keySlice(ew(18746)))
                it.ignoreMask = ignoreMaskAt(18746)
            }
            assertEquals(0, keys.ignoreMask, "f18746 starts with ignoreMask 0")

            val procs = ProcEngine()
            var ignoreCalls = 0
            val (events, _) = newEngine(procs, state, flags, playSt, keys) { eng ->
                val base11 = eng.lo[0x11]
                eng.register(0x11, EventHandler { p ->
                    ignoreCalls++
                    base11.run(p)
                })
                eng.register(0x1B, EventHandler { EVC_ADVANCE_YIELD })
            }
            val proc = events.create(ea.pEventStart, ea.execType)
            proc.pEventIdk = ea.pEventIdk
            proc.pEventCurrent = ea.pEventCurrent
            proc.evStateBits = ea.evStateBits
            proc.evStallTimer = ea.evStallTimer

            events.onUpdate(proc)

            assertEquals(1, ignoreCalls, "Event11_SetIgnoredKeys executed at f18747")
            assertEngineAndGlobalsEqual(18747, proc, state)
            assertEquals(ignoreMaskAt(18747), keys.ignoreMask, "gKeyStatusIgnoredSt at f18747")
            // Also verify KeyStatus EWRAM buffer update matches f18747 after applying inputs.
            keys.update(run.inputs[18747 - 1])
            val outKeys = ByteArray(KeyStatus.STRUCT_SIZE)
            keys.writeTo(outKeys)
            assertEquals(
                keySlice(ew(18747)).toList(),
                outKeys.toList(),
                "sKeyStatusBuffer EWRAM slice at f18747",
            )

            // 2. f18799 -> f18800: Tutorial1 clears IGNORE_KEYS(0).
            val tut1 = tutorialPtr(0, 1)
            val tut1Cmds = scanScript(tut1)
            assertTrue(
                tut1Cmds.any { it.second == 0x1120 && mem.u16(it.first + 2) == 0 },
                "Tutorial1 clears IGNORE_KEYS(0)",
            )
            assertTrue(
                tut1Cmds.any { it.second == 0x1120 && mem.u16(it.first + 2) == 0x010A },
                "Tutorial1 restores IGNORE_KEYS(0x010A)",
            )
            val keysClear = KeyStatus().also {
                it.readFrom(keySlice(ew(18799)))
                it.ignoreMask = ignoreMaskAt(18799)
            }
            assertEquals(0x010A, keysClear.ignoreMask)
            val procsClear = ProcEngine()
            val stateClear = EventState()
            val flagsClear = Fe8EventFlags.create().also { it.copyIn(iw(18799), flagsOffset) }
            val psClear = PlaySt().also { it.decode(ew(18799), playStAddr - GbaMem.EWRAM) }
            val bmClear = BmSt().also { it.decode(ew(18799), bmStAddr - GbaMem.EWRAM) }
            var clearCalls = 0
            val (evClear, _) =
                newEngine(procsClear, stateClear, flagsClear, psClear, keysClear) { eng ->
                    EventCursorCamera(stateClear, mem, bmClear).registerOn(eng)
                    val base11 = eng.lo[0x11]
                    eng.register(0x11, EventHandler { p ->
                        clearCalls++
                        base11.run(p)
                    })
                    eng.register(0x26, EventHandler { EVC_ADVANCE_YIELD })
                }
            val pClear = evClear.create(tut1, 1)
            evClear.onUpdate(pClear)
            assertEquals(1, clearCalls)
            assertEquals(
                ignoreMaskAt(18800),
                keysClear.ignoreMask,
                "gKeyStatusIgnoredSt cleared at f18800",
            )

            // 3. f18987 -> f18988: Tutorial1 sets IGNORE_KEYS(0x010A) after Exec1 returns.
            val a987 = trace.at(18987)
            val ea987 = checkNotNull(a987.engine)
            val state987 = EventState().also { seedEventState(a987, it) }
            val flags987 = Fe8EventFlags.create().also {
                it.copyIn(a987.permanentFlags!! + a987.chapterFlags!!)
            }
            val ps987 = PlaySt().also { it.decode(a987.playSt!!, 0) }
            val keys987 = KeyStatus().also {
                it.readFrom(keySlice(ew(18987)))
                it.ignoreMask = ignoreMaskAt(18987)
            }
            assertEquals(0, keys987.ignoreMask)
            val procs987 = ProcEngine()
            var restoreCalls = 0
            val (ev987, _) = newEngine(procs987, state987, flags987, ps987, keys987) { eng ->
                val base11 = eng.lo[0x11]
                eng.register(0x11, EventHandler { p ->
                    restoreCalls++
                    base11.run(p)
                })
                eng.register(0x3D, EventHandler { EVC_ADVANCE_CONTINUE })
            }
            val p987 = ev987.create(ea987.pEventStart, ea987.execType)
            p987.pEventIdk = ea987.pEventIdk
            p987.pEventCurrent = ea987.pEventCurrent
            p987.evStateBits = ea987.evStateBits
            p987.evStallTimer = ea987.evStallTimer
            ev987.onUpdate(p987)
            assertEquals(1, restoreCalls)
            assertEquals(
                ignoreMaskAt(18988),
                keys987.ignoreMask,
                "gKeyStatusIgnoredSt restored at f18988",
            )
        }

        fun assertMoveCursor() {
            // Confirm at runtime that Exec1 uses 0x3C sub 0 (CHECK_CURSOR) and sub 1 with -1.
            val tut1 = tutorialPtr(0, 1)
            val tut1Cmds = scanScript(tut1)
            val callExec1 = tut1Cmds.first { (it.second ushr 8) == 0x0A }.first
            val exec1Addr = mem.u16(callExec1 + 4) or (mem.u16(callExec1 + 6) shl 16)
            assertEquals("EventScr_Tutorial_Exec1", scripts.name(exec1Addr)?.name)
            val exec1Cmds = scanScript(exec1Addr)
            assertEquals(0x3C20, exec1Cmds[0].second, "Exec1 begins with 0x3C sub 0 CHECK_CURSOR")
            val setCursorCmd = exec1Cmds.first { it.second == 0x3C21 }
            assertEquals(
                0xFFFF,
                mem.u16(setCursorCmd.first + 2),
                "Exec1 SET_CURSOR reads slot B (-1)",
            )

            // 1. f18799 -> f18800: CHECK_CURSOR reads (10,5) -> 0x0005000A, BNE jumps to LABEL 0,
            //    SET_CURSOR(-1,-1) snaps cursor from (10,5) to slot B (4,5).
            //    0x26 is stubbed as EVC_ADVANCE_YIELD so only Event3C_MoveCursor updates bmSt.
            runDestStep(18799, 18800, stub26Yield = true, expectedMoveCursorCalls = 2)

            // 2. f19019 -> f19020: CHECK_CURSOR reads (4,5) -> 0x00050004, BNE not taken -> ASMC.
            runDestStep(19019, 19020, stub26Yield = false, expectedMoveCursorCalls = 1)
        }

        private fun runDestStep(
            seedStep: Int,
            targetStep: Int,
            stub26Yield: Boolean,
            expectedMoveCursorCalls: Int,
        ) {
            val ewSeed = ew(seedStep)
            val iwSeed = iw(seedStep)
            val playSt = PlaySt().also { it.decode(ewSeed, playStAddr - GbaMem.EWRAM) }
            val bm = BmSt().also { it.decode(ewSeed, bmStAddr - GbaMem.EWRAM) }
            val map = BmMap(rom, chapters, bm).also { it.initChapterMap(playSt.chapterIndex) }
            val units = UnitTable(rom).also { it.loadFrom(ewSeed) }
            readPool(ewSeed, iwSeed, bmMapTerrainAddr, map, map.terrain)
            readPool(ewSeed, iwSeed, bmMapUnitAddr, map, map.unit)
            readPool(ewSeed, iwSeed, bmMapMovementAddr, map, map.movement)
            val flags = Fe8EventFlags.create().also { it.copyIn(iwSeed, flagsOffset) }
            val state = EventState().also { seedEventState(trace.at(seedStep), it) }
            val procs = ProcEngine()
            val keys = KeyStatus().also {
                it.readFrom(keySlice(ewSeed))
                it.ignoreMask = ignoreMaskAt(seedStep)
            }
            var moveCursorCalls = 0
            val cursorCam = EventCursorCamera(state, mem, bm)
            val (events, _) = newEngine(
                procs, state, flags, playSt, keys, { bm.lock += it },
            ) { eng ->
                cursorCam.registerOn(eng)
                eng.register(0x3C, EventHandler { p ->
                    moveCursorCalls++
                    cursorCam.event3cMoveCursor.run(p)
                })
                if (stub26Yield) {
                    eng.register(0x26, EventHandler { EVC_ADVANCE_YIELD })
                }
                eng.register(0x0D, EventHandler { EVC_ADVANCE_YIELD })
            }
            val infos = EventInfos(mem, flags, playSt).also { infos ->
                infos.hooks = object : EventInfoHooks {
                    override fun callEvent(script: Int, execType: Int) =
                        events.callEvent(script, execType)

                    override fun chapterEventGroup(chapter: Int): Int =
                        chapters.getChapterEventDataPointer(chapter)
                }
            }
            val phase = PlayerPhase(
                procs, bm, playSt, map, units, CamMove(procs, bm),
                EntityBmMaps(map, units, playSt), BmMapRender(bm, map, BgHardware()), keys,
                { events.eventEngineExists() },
            )
            seedActive(ewSeed, iwSeed, units, phase)
            var destStarted = false
            phase.hooks.startDestSelectedEvent = {
                infos.startDestSelectedEvent().also { destStarted = it }
            }
            val ppProc = procs.start(phase.script, 2)
            ppProc.scrCur = 25
            ppProc.idleCb = ProcFunc { phase.playerPhaseRangeDisplayIdle(it) }

            keys.readFrom(keySlice(ew(targetStep)))
            stepAllTrees(procs, bm.lock)

            assertTrue(destStarted, "f$targetStep started DESTSELECTED event")
            assertEquals(expectedMoveCursorCalls, moveCursorCalls, "f$targetStep Event3C calls")
            val engProc = checkNotNull(events.findStd()?.state as? EventEngineProc)
            assertEngineAndGlobalsEqual(targetStep, engProc, state)

            val wantBm = BmSt().also { it.decode(ew(targetStep), bmStAddr - GbaMem.EWRAM) }
            assertEquals(wantBm.playerCursorX, bm.playerCursorX, "f$targetStep playerCursorX")
            assertEquals(wantBm.playerCursorY, bm.playerCursorY, "f$targetStep playerCursorY")
            assertEquals(wantBm.cursorTargetX, bm.cursorTargetX, "f$targetStep cursorTargetX")
            assertEquals(wantBm.cursorTargetY, bm.cursorTargetY, "f$targetStep cursorTargetY")
            assertEquals(wantBm.playerCursorDisplayX, bm.playerCursorDisplayX, "f$targetStep dispX")
            assertEquals(wantBm.playerCursorDisplayY, bm.playerCursorDisplayY, "f$targetStep dispY")
            assertEquals(ignoreMaskAt(targetStep), keys.ignoreMask, "f$targetStep ignoreMask")
        }

        fun assertDestSelectedRequeue() {
            val a = trace.at(18987)
            val b = trace.at(18988)
            val ea = checkNotNull(a.engine)
            val tut1 = tutorialPtr(0, 1)
            assertEquals("EventScr_Prologue_Tutorial1", scripts.name(tut1)?.name)

            // Confirm at runtime that f18987's slotQueue holds Tutorial2 then Tutorial1 and the
            // upcoming Event0B_EnqueueCall command uses -1 (slot 2) and exec type 3 (DESTSELECTED).
            assertEquals(tut1, a.slotQueue[1], "f18987 slotQueue[1] is Tutorial1")
            val state = EventState().also { seedEventState(a, it) }
            val flags = Fe8EventFlags.create().also {
                it.copyIn(a.permanentFlags!! + a.chapterFlags!!)
            }
            val playSt = PlaySt().also { it.decode(a.playSt!!, 0) }
            val keys = KeyStatus().also {
                it.readFrom(keySlice(ew(18987)))
                it.ignoreMask = ignoreMaskAt(18987)
            }
            val procs = ProcEngine()
            var enqueueCalls = 0
            val (events, _) = newEngine(procs, state, flags, playSt, keys) { eng ->
                val base0B = eng.lo[0x0B]
                eng.register(0x0B, EventHandler { p ->
                    enqueueCalls++
                    val cur = p.pEventCurrent
                    assertEquals(0x0B41, mem.u16(cur), "Event0B sub 1 ENQUEUE_TUT")
                    assertEquals(3, mem.u16(cur + 2), "DESTSELECTED exec type 3")
                    assertEquals(0xFFFF, mem.u16(cur + 4), "negative ptr reads slot 2")
                    assertEquals(0xFFFF, mem.u16(cur + 6))
                    assertEquals(tut1, state.slots[2], "slot 2 holds Tutorial1")
                    base0B.run(p)
                })
                eng.register(0x3D, EventHandler { EVC_ADVANCE_CONTINUE })
            }
            val proc = events.create(ea.pEventStart, ea.execType)
            proc.pEventIdk = ea.pEventIdk
            proc.pEventCurrent = ea.pEventCurrent
            proc.evStateBits = ea.evStateBits
            proc.evStallTimer = ea.evStallTimer

            events.onUpdate(proc)

            assertEquals(1, enqueueCalls, "Event0B_EnqueueCall executed once at f18988")
            assertEngineAndGlobalsEqual(18988, proc, state)
            val outFlags = ByteArray(0x30).also { flags.copyOut(it) }
            assertEquals((b.permanentFlags!! + b.chapterFlags!!).toList(), outFlags.toList())
            val wantPs = PlaySt().also { it.decode(b.playSt!!, 0) }
            assertEquals(2, wantPs.tutorialCounter, "capture tutorialCounter is 2 (Tutorial1)")
            assertEquals(3, wantPs.tutorialExecType, "capture tutorialExecType is 3 (DESTSELECTED)")
            assertEquals(wantPs.tutorialCounter, playSt.tutorialCounter, "f18988 tutorialCounter")
            assertEquals(
                wantPs.tutorialExecType,
                playSt.tutorialExecType,
                "f18988 tutorialExecType",
            )
            assertEquals(ignoreMaskAt(18988), keys.ignoreMask, "f18988 gKeyStatusIgnoredSt")
        }

        private fun readByte(ew: ByteArray, iw: ByteArray, addr: Int): Int =
            if (addr >= GbaMem.IWRAM) {
                iw[addr - GbaMem.IWRAM].toInt() and 0xFF
            } else {
                ew[addr - GbaMem.EWRAM].toInt() and 0xFF
            }

        private fun readHalf(ew: ByteArray, iw: ByteArray, addr: Int): Int =
            readByte(ew, iw, addr) or (readByte(ew, iw, addr + 1) shl 8)

        private fun readWord(ew: ByteArray, iw: ByteArray, addr: Int): Int =
            readHalf(ew, iw, addr) or (readHalf(ew, iw, addr + 2) shl 16)
    }

    private companion object {
        const val RUN = "ram_trace_newgame_easy"
        const val ROM_BASE = 0x08000000
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
