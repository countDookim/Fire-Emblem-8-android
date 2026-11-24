package fe8.testing

import fe8.game.GameLoop
import fe8.game.RomView
import fe8.game.bm.BMapMain
import fe8.game.bm.BattleMapNodes
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.PlaySt
import fe8.game.bm.ResetCursorPosition
import fe8.game.bm.UnitDeployState
import fe8.game.bm.UnitDeployStateHooks
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.EventState
import fe8.game.event.ScriptMemory
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.core.time.GameClock
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.TestInstance

/**
 * PC-6e-b: the beginning event of Chapter 1 in `ram_trace_prologue_end` (`PC-cap1`), at its head
 * (`f10297 -> f10298`) and its tail (`f25419 .. f25423`; `f25421` is sampled inside an overrun
 * step and skipped).
 *
 * The real objects run through [GameLoop.onMain] (tree 2 `BMapMain` only while `gBmSt.lock` is 0,
 * then tree 3, the event engine): [BMapMain], [EventEngine] and [UnitDeployState], seeded from the
 * per-step watches (`gplayst`, `flags`, `gbmst`, `evglobals`, `procs`) and the ewram dump of the
 * first frame. After every frame the engine's existence and start, `gBmSt.lock`, the `BMapMain`
 * cursor, flag 0x84, faction and turn, the event call queue and the unit arrays are compared with
 * the capture.
 *
 * Stand-ins: every event command is inert (a command stays parked where the capture's cursor
 * is, otherwise continues), the Turn 1 event the phase switch starts is a `callEvent` of the
 * start the capture shows (`RunPhaseSwitchEvents` is another task), and the engine's teardown
 * hooks only record their order.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class Chapter1BeginningTailDifferentialTest {
    private var shared: Rig? = null
    private var loaded = false

    @AfterAll
    fun releaseRig() {
        shared = null
    }

    private fun rig(): Rig {
        if (!loaded) {
            shared = loadRig()
            loaded = true
        }
        val r = shared
        assumeTrue(r != null, "reference data or ROM not present")
        return r!!
    }

    private fun loadRig(): Rig? {
        val ref = ReferenceData.fromEnvironment()
        val assets = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        )
        val rom = File(assets, "baserom.gba")
        if (!ref.available || !rom.isFile) return null
        val trace = EventTrace.prologue(ref, RUN) ?: return null
        return Rig(rom.readBytes(), trace, ref.run(RUN))
    }

    @Test
    @VerifiesPort("UpdatePrevDeployStates", "ResetAllPlayerUnitState", "CallBeginningEvents")
    fun `the head of the beginning event matches PC-cap1`() {
        rig().head(10297, 10298)
    }

    @Test
    @VerifiesPort("UndeployEveryone", "EventEngine_OnEnd", "CallNextQueuedEvent")
    fun `the tail of the beginning event matches PC-cap1`() {
        rig().tail(25419, listOf(25420, 25422, 25423))
    }

    private class Rig(
        private val rom: ByteArray,
        private val trace: EventTrace,
        private val run: ReferenceData.Run,
    ) {
        private val romView = RomView.ofRom(rom)
        private val gbm = run.watch("gbmst")
        private val dumpAt = run.dumps.entries.associate { it.value to it.key }
        private val calls = ArrayList<String>()
        private var nextB = 0

        private val mem = ScriptMemory { a ->
            val o = a - ROM_BASE
            (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8)
        }
        private val procs = ProcEngine()
        private val loop = GameLoop(procs, KeyStatus(), GameClock(), { 0 })
        private val state = EventState()
        private val flags = Fe8EventFlags.create()
        private val playSt = PlaySt()
        private val units = UnitTable(romView)
        private val loader = UnitLoader(units, romView, { 0 }, RngService(), { false })
        private val deploy = UnitDeployState(
            units, loader, playSt,
            object : UnitDeployStateHooks {
                override fun refreshEntityBmMaps() { calls += "refreshEntityBmMaps" }
                override fun endAllMus() { calls += "endAllMus" }
                override fun storeUnitWordStructs() { calls += "storeUnitWordStructs" }
            },
        )
        private val events = EventEngine(
            procs, state, mem,
            object : EventEngineHooks {
                override fun lockGame() = loop.lockGame()
                override fun resetTextFont() { calls += "resetTextFont" }
                override fun restoreAfterEvent5(mapSpritePalIdOverride: Int) {
                    calls += "restoreAfterEvent5"
                }
                override fun resumeGameplay() {
                    calls += "resumeGameplay"
                    loop.unlockGame()
                }
                override fun endEventTalk() { calls += "endEventTalk" }
                override fun eventEndCleanup(proc: EventEngineProc) { calls += "eventEndCleanup" }
            },
        )
        private val camMove = CamMove(procs, loop.bmSt)
        private val noNodes = object : BattleMapNodes {
            override fun chapterId(node: Int) = -1
            override fun isCleared(node: Int) = false
            override fun encounters(node: Int) = 0
            override fun placementFlag(node: Int) = 0
        }
        private val reset = ResetCursorPosition(procs, loop.bmSt, playSt, units, camMove, noNodes)
        private val main = BMapMain(
            procs, playSt, units, flags, ChapterData(romView), romView, reset, camMove,
        )
        private var turnOneStart = 0
        private var commands = 0

        init {
            procs.init()
            for (code in 0..0xFF) events.register(code, EventHandler { p ->
                check(++commands <= MAX_COMMANDS) {
                    "more than $MAX_COMMANDS commands in one step, cursor %X"
                        .format(p.pEventCurrent)
                }
                // Inert commands: the Chapter 1 events themselves are other tasks.
                if (p.pEventCurrent == nextB || nextB == 0) EVC_STOP_YIELD
                else fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
            })
            main.hooks.updatePrevDeployStates = { deploy.updatePrevDeployStates() }
            main.hooks.callEvent = { ev, execType, _ -> events.callEvent(ev, execType) }
            main.hooks.runPhaseSwitchEvents = {
                if (turnOneStart != 0) {
                    events.callEvent(turnOneStart, 1)
                    true
                } else {
                    false
                }
            }
        }

        private fun seed(step: Int, mainCursor: Int) {
            procs.init()
            calls.clear()
            val s = trace.at(step)
            playSt.decode(s.playSt!!, 0)
            flags.copyIn(s.permanentFlags!! + s.chapterFlags!!)
            loop.bmSt.decode(gbm.step(step), 0)
            units.loadFrom(run.region(dumpAt.getValue(step), "ewram"))
            for (i in 0 until 16) {
                state.callQueue[2 * i] = s.callQueue[i].events
                state.callQueue[2 * i + 1] =
                    (s.callQueue[i].execType and 0xFF) or ((s.callQueue[i].isUsed and 0xFF) shl 8)
            }
            val mainProc = procs.start(main.script, BMAP_MAIN_TREE)
            mainProc.scrCur = mainCursor
            procs.breakProc(mainProc)
        }

        fun head(aStep: Int, bStep: Int) {
            calls.clear()
            seed(aStep, 8)
            nextB = trace.at(bStep).engine!!.pEventCurrent
            commands = 0
            loop.onMain()
            compare(bStep)
            val e = checkNotNull(findEngine()) { "engine at f$bStep" }
            val want = checkNotNull(trace.at(bStep).engine)
            assertEquals(want.pEventStart, e.pEventStart, "start")
            assertEquals(want.execType, e.execType, "execType")
            assertEquals(want.pEventCurrent, e.pEventCurrent, "cursor")
            assertEquals(
                listOf("refreshEntityBmMaps", "endAllMus"), calls, "UpdatePrevDeployStates calls",
            )
        }

        fun tail(aStep: Int, samples: List<Int>) {
            calls.clear()
            seed(aStep, 12)
            val a = checkNotNull(trace.at(aStep).engine)
            val old = events.create(a.pEventStart, a.execType)
            old.pEventIdk = a.pEventIdk
            old.pEventCurrent = a.pEventCurrent
            old.evStateBits = a.evStateBits
            old.proc!!.scrCur = trace.procsAt(aStep).find("ProcScr_StdEventEngine")!!.scrCur
            procs.breakProc(old.proc!!)
            turnOneStart = trace.at(aStep + 4).engine!!.pEventStart
            calls.clear()
            nextB = 0
            // The step that ends the engine (sim frame 2) overran its frame in the capture: f25421
            // was sampled inside it (`gBmSt.mainLoopEnded` 0, the engine still listed, lock 1) and
            // f25422 is its end. The next simulated frame is f25423.
            for (step in samples) {
                commands = 0
                loop.onMain()
                compare(step)
            }
            assertEquals(
                listOf(
                    "resetTextFont", "restoreAfterEvent5", "resumeGameplay", "endEventTalk",
                    "eventEndCleanup",
                ),
                calls.filter { !it.startsWith("refresh") && it != "endAllMus" },
                "EventEngine_OnEnd calls",
            )
        }

        private fun findEngine(): EventEngineProc? =
            procs.find(events.stdScript)?.state as EventEngineProc?

        private fun compare(step: Int) {
            val at = "f$step"
            val s = trace.at(step)
            val d = trace.procsAt(step)
            val e = findEngine()
            assertEquals(s.engine != null, e != null, "$at engine exists")
            if (s.engine != null) assertEquals(s.engine!!.pEventStart, e!!.pEventStart, "$at start")
            assertEquals(gbm.u8(step, 1), loop.lock, "$at gBmSt.lock")
            assertEquals(
                d.find("gProc_BMapMain")!!.scrCur, procs.find(main.script)!!.scrCur,
                "$at BMapMain cmd",
            )
            assertEquals(
                s.permanentFlags!![3].toInt() and 0x80 != 0, flags.checkFlag(0x84), "$at flag 0x84",
            )
            val want = PlaySt().also { it.decode(s.playSt!!, 0) }
            assertEquals(want.faction, playSt.faction, "$at faction")
            assertEquals(want.chapterTurnNumber, playSt.chapterTurnNumber, "$at turn")
            s.callQueue.forEachIndexed { i, c ->
                assertEquals(c.events, state.callQueue[2 * i], "$at callQueue[$i].events")
                assertEquals(
                    (c.execType and 0xFF) or ((c.isUsed and 0xFF) shl 8),
                    state.callQueue[2 * i + 1] and 0xFFFF, "$at callQueue[$i].meta",
                )
            }
            val ewram = run.region(dumpAt.getValue(step), "ewram")
            val off = UnitTable.BLUE_ADDRESS - EWRAM
            for (i in units.bytes.indices) {
                if (i % UnitTable.UNIT_SIZE in 0x3C..0x3F) continue
                assertEquals(ewram[off + i], units.bytes[i], "$at unit byte %X".format(i))
            }
        }
    }

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val ROM_BASE = 0x08000000
        const val EWRAM = 0x02000000
        const val BMAP_MAIN_TREE = 2

        /** Bound of the stubbed commands of one step (a script that loops is an error). */
        const val MAX_COMMANDS = 10_000
    }
}
