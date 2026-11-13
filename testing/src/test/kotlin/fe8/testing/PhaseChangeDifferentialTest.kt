package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BMapMain
import fe8.game.bm.BattleMapNodes
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.CpPhase
import fe8.game.bm.PhaseIntro
import fe8.game.bm.PlaySt
import fe8.game.bm.ResetCursorPosition
import fe8.game.bm.UnitTable
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventInfoHooks
import fe8.game.event.EventInfos
import fe8.game.event.EventScrCore
import fe8.game.event.EventState
import fe8.game.event.ScriptWords
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * End turn to the enemy phase banner: [BMapMain] from f1510 (the player phase has just ended) to
 * f1521 in `ram_trace_prologue_cancel_end`.
 *
 * The real `gProc_BMapMain` runs from the seed (the proc state, `gPlaySt`, `gBmSt`, the units and
 * the flags of f1510) with the ported berserk phase and order ([CpPhase]), `BmMain_ChangePhase`
 * with the real `RunPhaseSwitchEvents` (the turn list of the Prologue, started through the real
 * event engine and its commands), `BmMain_UpdateTraps`, and the real [PhaseIntro]. As in `OnMain`,
 * proc tree 2 (BMapMain) does not run while the game is locked.
 *
 * The dumps are taken at VBlank, so a main loop pass that overruns shows in several dumps. The
 * passes are: f1511 to f1515 one pass per dump; the event engine's end (`EventEngine_OnEnd`)
 * spans f1516 (dump taken after `UnlockGame`, before the proc is freed) and ends at f1517;
 * the suspend save, label 9 and the banner's start span f1518 to f1520 (BMapMain inside
 * `WriteSuspendSave`, then inside the start of `PhaseIntro`) and end at f1521. Each pass is run
 * once and compared with the dump the pass ends at; the dumps inside a pass are compared with the
 * state the port has at the matching call (the unlock, the suspend save).
 *
 * Stand-ins: the AI flag setup (`CpUtility`, verified in `CpAiFlagsDifferentialTest`; `CpPhase`
 * here gets no map objects, so it is skipped and `gAiState.bestBlueMov`, byte 0x85, is not
 * compared), the suspend save write, the unit sprite refresh and the grayed state reset.
 */
class PhaseChangeDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private val noNodes = object : BattleMapNodes {
        override fun chapterId(node: Int) = -1
        override fun isCleared(node: Int) = false
        override fun encounters(node: Int) = 0
        override fun placementFlag(node: Int) = 0
    }

    @Test
    @VerifiesPort(
        "AiPhaseBerserkInit", "AiPhaseCleanup", "CpOrderBerserkInit", "CpOrderFunc_End",
    )
    fun `berserk phase and order run and end at once after the player phase`() {
        val sim = Sim()
        sim.run()
        // f1511: both procs; f1512: the order ended; f1513: both gone (compared in run()).
        assertEquals(listOf(0x04, 0x00), sim.aiFlagsSeen, "gAiState.flags in f1511 and f1513")
    }

    @Test
    @VerifiesPort("BmMain_UpdateTraps", "RunPhaseSwitchEvents", "StartEventFromInfo")
    fun `the phase switches to the enemy and the turn event runs`() {
        val sim = Sim()
        sim.run()
        assertEquals(listOf(0x089EF214 to 1), sim.eventCalls, "events started (script, exec type)")
        assertEquals(0x80, sim.playSt.faction, "faction after the switch")
        assertEquals(1, sim.playSt.chapterTurnNumber, "turn")
        assertTrue(sim.sawPhaseIntro, "PhaseIntro live at f1521")
    }

    private inner class Sim {
        init {
            assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        }

        val data = ref.run("ram_trace_prologue_cancel_end")
        val names = data.dumps.entries.associate { it.value to it.key }
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val engine = ProcEngine()
        val bm = BmSt()
        val playSt = PlaySt()
        val units = UnitTable(rom)
        val flags = Fe8EventFlags.create()
        val camMove = CamMove(engine, bm)
        val reset = ResetCursorPosition(engine, bm, playSt, units, camMove, noNodes)
        val main = BMapMain(engine, playSt, units, flags, ChapterData(rom), rom, reset, camMove)
        val cp = CpPhase(engine, rom, playSt, units)
        val intro = PhaseIntro(
            engine, bm, playSt, units, rom, BgHardware(), OamBuffer(), SpriteLayers(),
        )
        val words = ScriptWords({ a ->
            val o = a - 0x08000000
            (romBytes[o].toInt() and 0xFF) or ((romBytes[o + 1].toInt() and 0xFF) shl 8)
        })
        val state = EventState()
        var lock = 0
        val eventCalls = ArrayList<Pair<Int, Int>>()
        val aiFlagsSeen = ArrayList<Int>()
        var sawPhaseIntro = false
        var suspendCalls = 0
        var unlockCalls = 0
        val events = EventEngine(
            engine, state, words,
            object : EventEngineHooks {
                override fun lockGame() { lock++ }
                override fun resumeGameplay() {
                    lock--
                    unlockCalls++
                    // f1516 is the dump inside EventEngine_OnEnd: the unlock is done, the proc
                    // is still there at its last command.
                    compareMid(1516, mapOf(STD_EVENT to 6), lockWant = 0)
                }
            },
        )
        val infos = EventInfos(words, flags, playSt)

        fun ewram(f: Int): ByteArray = data.region(names.getValue(f), "ewram")

        fun procs(f: Int) = ProcArrayDump.ofEwram(ewram(f))

        fun run() {
            val e = ewram(1510)
            bm.decode(e, BM_ST - EWRAM)
            playSt.decode(e, PLAY_ST - EWRAM)
            units.loadFrom(e)
            flags.copyIn(data.region(names.getValue(1510), "iwram"), FLAGS - IWRAM)
            EventScrCore(
                state, words, flags = flags, rng = RngService(), playSt = playSt,
                keys = KeyStatus(),
            ).registerAll(events)
            val chapters = ChapterData(rom)
            infos.hooks = object : EventInfoHooks {
                override fun callEvent(script: Int, execType: Int) {
                    eventCalls.add(script to execType)
                    events.callEvent(script, execType)
                }

                override fun chapterEventGroup(chapter: Int) =
                    chapters.getChapterEventDataPointer(chapter)
            }
            main.hooks.runPhaseSwitchEvents = { infos.runPhaseSwitchEvents() }
            main.hooks.berserkCpPhase = cp.berserkPhaseScript
            main.hooks.phaseIntro = intro.script
            main.hooks.writeSuspendSave = {
                suspendCalls++
                // f1518 and f1519: the dumps inside WriteSuspendSave.
                val m = engine.find(main.script)!!
                for (f in 1518..1519) {
                    assertEquals(procs(f).find(MAIN)!!.scrCur, m.scrCur, "f$f BMapMain in suspend")
                    assertEquals(faction(f), playSt.faction, "f$f faction in suspend")
                }
            }
            val mainProc = engine.start(main.script, 2)
            mainProc.scrCur = procs(1510).find(MAIN)!!.scrCur
            assertEquals(31, mainProc.scrCur, "BMapMain at f1510")
            compare(1510)

            for (f in listOf(1511, 1512, 1513, 1514, 1515, 1517, 1521)) {
                for (t in 0 until 8) if (t != 2 || lock == 0) engine.run(engine.root(t))
                if (f == 1511) aiFlagsSeen.add(cp.aiState.flags)
                if (f == 1513) aiFlagsSeen.add(cp.aiState.flags)
                compare(f)
            }
            assertEquals(1, suspendCalls, "suspend save calls")
            assertEquals(1, unlockCalls, "event engine ended once")
            // The dump of f1516 equals f1515's procs but for the unlock: nothing else moved.
            assertEquals(
                procs(1515).live.map { it.script to it.scrCur },
                procs(1516).live.map { it.script to it.scrCur }, "f1516 procs",
            )
        }

        fun faction(f: Int): Int = PlaySt().also { it.decode(ewram(f), PLAY_ST - EWRAM) }.faction

        /** The dump [f] taken inside a pass: the given procs are alive at the given command. */
        fun compareMid(f: Int, cmds: Map<Int, Int>, lockWant: Int) {
            val dump = procs(f)
            for ((script, cmd) in cmds) {
                assertEquals(cmd, dump.find(script)!!.scrCur, "f$f cmd of ${script.toString(16)}")
                assertEquals(
                    cmd, engine.procs.first { it.script != null && address(it.script!!) == script }
                        .scrCur,
                    "f$f mine cmd of ${script.toString(16)}",
                )
            }
            assertEquals(lockWant, lock, "f$f game lock")
            assertEquals(lockWant, ewram(f)[BM_ST - EWRAM + 1].toInt(), "f$f gBmSt.lock")
        }

        fun address(script: ProcScript): Int = when (script) {
            main.script -> MAIN
            cp.berserkPhaseScript -> BERSERK_PHASE
            cp.berserkOrderScript -> BERSERK_ORDER
            events.stdScript -> STD_EVENT
            intro.script -> PHASE_INTRO
            reset.script -> RESET
            camMove.script -> CAM_MOVE
            else -> 0
        }

        fun compare(f: Int) {
            val at = "f$f"
            val e = ewram(f)
            val want = PlaySt().also { it.decode(e, PLAY_ST - EWRAM) }
            assertEquals(want.faction, playSt.faction, "$at faction")
            assertEquals(want.chapterTurnNumber, playSt.chapterTurnNumber, "$at turn")
            val mine = ByteArray(PlaySt.SIZE).also { playSt.encode(it, 0) }
            for (k in 6 until PlaySt.SIZE) {
                assertEquals(e[PLAY_ST - EWRAM + k], mine[k], "$at gPlaySt+0x${k.toString(16)}")
            }
            assertEquals(e[BM_ST - EWRAM + 1].toInt(), lock, "$at game lock")

            val dump = procs(f)
            val scripts = listOf(
                MAIN, BERSERK_PHASE, BERSERK_ORDER, STD_EVENT, PHASE_INTRO, RESET, CAM_MOVE,
            )
            for (address in scripts) {
                val w = dump.findAll(address)
                val g: List<Proc> = engine.procs.filter {
                    it.script != null && address(it.script!!) == address
                }
                assertEquals(w.size, g.size, "$at procs of ${address.toString(16)}")
                if (w.isNotEmpty() && address != PHASE_INTRO) {
                    assertEquals(w[0].scrCur, g[0].scrCur, "$at cmd of ${address.toString(16)}")
                }
            }
            if (dump.find(PHASE_INTRO) != null) sawPhaseIntro = true
            // The live procs of the dump are exactly the ones modelled (plus the fixed ones).
            val known = scripts.toSet() + FIXED
            assertTrue(
                dump.live.all { it.script in known || it.script in PHASE_CHILDREN },
                "$at unknown procs: ${dump.live.map { it.script.toString(16) }}",
            )
            compareAiState(f, e)
        }

        fun compareAiState(f: Int, e: ByteArray) {
            val o = AI_STATE - EWRAM
            val ai = cp.aiState
            assertEquals(e[o + 0x78].toInt() and 0xFF, ai.orderState, "f$f aiState.orderState")
            assertEquals(e[o + 0x7B].toInt() and 0xFF, ai.flags, "f$f aiState.flags")
            assertEquals(e[o + 0x7E].toInt() and 0xFF, ai.unk7E, "f$f aiState.unk7E")
            val special = (0..3).sumOf { (e[o + 0x80 + it].toInt() and 0xFF) shl (8 * it) }
            assertEquals(special, ai.specialItemFlags, "f$f aiState.specialItemFlags")
            for (k in 0 until 8) {
                assertEquals(
                    e[o + 0x86 + k].toInt() and 0xFF, ai.cmdResult[k].toInt() and 0xFF,
                    "f$f aiState.cmd_result[$k]",
                )
            }
            for (k in 0 until 4) {
                assertEquals(0, e[o + 0x74 + k].toInt(), "f$f aiState.unitIt (no berserk unit)")
            }
        }
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val FLAGS = 0x03005250
        const val AI_STATE = 0x0203AA04
        const val MAIN = 0x0859A1F0
        const val BERSERK_PHASE = 0x085A7F30
        const val BERSERK_ORDER = 0x085A7F74
        const val STD_EVENT = 0x08591AC0
        const val PHASE_INTRO = 0x0859AF80
        const val RESET = 0x0859AE00
        const val CAM_MOVE = 0x0859A548

        /** GameControl, its child, and the map task proc. */
        val FIXED = setOf(0x085916D4, 0x0859D8B8, 0x0859D908)

        /** The banner's text, squares and blend box procs. */
        val PHASE_CHILDREN = setOf(0x0859AF60, 0x0859AF40, 0x0859AEF8)
    }
}
