package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BMapMain
import fe8.game.bm.BattleMapNodes
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.PlaySt
import fe8.game.bm.ResetCursorPosition
import fe8.game.bm.UnitTable
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * [BMapMain] against per-frame RAM dumps from the end of the beginning event to the start
 * of the player phase. The procs, `gPlaySt`, `gBmSt`, the units and the flags are seeded once
 * (the last frame of the event engine); then the proc engine runs the real
 * `gProc_BMapMain` script, [ResetCursorPosition] and [CamMove] free-running. Every frame, the
 * script position of BMapMain, ResetCursorPosition and CamMove, the faction, the turn, flag 0x84,
 * the cursor, the camera, the unit array and which of the procs it starts exist are compared with
 * the dump. Stand-ins: `ProcScr_PhaseIntro` lives as long as the dump shows it, the player phase
 * is an idle proc, the event and save calls are recorded.
 *
 * The suspend save takes three VBlanks on the hardware (the dumps show BMapMain in front of the
 * label 9 for three frames); the engine steps once for them: the state during the save call is
 * compared with those three frames and the state after the step with the next.
 *
 * - `ram_trace_first_phase`: seed f19312, compare f19313..f19393 (PlayerPhase starts; 80 frames,
 *   f19316 is inside the first run of the phase start children and is skipped).
 * - `ram_trace_ch6_phase_start`: seed f1679, compare f1680..f1760 (a map larger than the screen:
 *   ResetCursorPosition starts a CamMove).
 */
class BMapMainDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("SwitchPhases", "BmMain_ChangePhase", "BmMain_StartPhase")
    fun `first phase runs from the end of the beginning event to the player phase`() {
        val sim = sim("ram_trace_first_phase", 19312, 19393)
        sim.run()
        assertEquals(80, sim.compared, "frames compared (3 of them for the suspend save)")
        assertEquals(0, sim.cameraMoves, "no CamMove (the map fits the screen)")
    }

    @Test
    @VerifiesPort("SwitchPhases", "BmMain_ChangePhase", "BmMain_StartPhase")
    fun `ch6 phase start scrolls the camera before the player phase`() {
        val sim = sim("ram_trace_ch6_phase_start", 1679, 1760)
        sim.run()
        assertEquals(80, sim.compared, "frames compared (3 of them for the suspend save)")
        assertTrue(sim.cameraMoves > 10, "CamMove frames ${sim.cameraMoves}")
    }

    @Test
    @VerifiesPort("BmMain_SuspendBeforePhase")
    fun `the phase change calls its hooks in the order of the C`() {
        val sim = sim("ram_trace_first_phase", 19312, 19393)
        sim.run()
        assertEquals(
            listOf(
                "clearActiveFactionGrayedStates", "refreshUnitSprites", "processTurnSupportExp",
                "runPhaseSwitchEvents", "writeSuspendSave(3)",
            ),
            sim.calls,
        )
        assertEquals(9, sim.main.suspendPointType, "SUSPEND_POINT_PHASECHANGE")
    }

    @Test
    fun `CallBeginningEvents targets the beginning scene events of the ROM chapter`() {
        val first = sim("ram_trace_first_phase", 19312, 19393).also { it.seedData() }
        assertEquals(first.eventEngineStart(), first.main.beginningSceneEvents(), "first_phase")
        val ch6 = sim("ram_trace_ch6_phase_start", 1679, 1760).also { it.seedData() }
        assertEquals(ch6.eventEngineStart(), ch6.main.beginningSceneEvents(), "ch6")
    }

    private fun sim(run: String, seed: Int, last: Int): Sim {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        return Sim(run, seed, last)
    }

    private val noNodes = object : BattleMapNodes {
        override fun chapterId(node: Int) = -1
        override fun isCleared(node: Int) = false
        override fun encounters(node: Int) = 0
        override fun placementFlag(node: Int) = 0
    }

    private inner class Sim(val run: String, val seed: Int, val last: Int) {
        val data = ref.run(run)
        val names = data.dumps.entries
            .filter { File(ref.root, "$run/${it.key}.ewram.bin").isFile }
            .associate { it.value to it.key }
        val rom = RomView.ofRom(romFile.readBytes())
        val engine = ProcEngine()
        val bm = BmSt()
        val playSt = PlaySt()
        val units = UnitTable(rom)
        val flags = Fe8EventFlags.create()
        val camMove = CamMove(engine, bm)
        val reset = ResetCursorPosition(engine, bm, playSt, units, camMove, noNodes)
        val main = BMapMain(engine, playSt, units, flags, ChapterData(rom), rom, reset, camMove)
        val calls = ArrayList<String>()
        var compared = 0
        var cameraMoves = 0

        /** The frame whose dump the stand-in `PhaseIntro` follows. */
        var dumpFrame = seed

        var snapshot: Snapshot? = null

        fun ewram(frame: Int): ByteArray = data.region(names.getValue(frame), "ewram")

        fun procs(frame: Int) = ProcArrayDump.ofEwram(ewram(frame))

        fun eventEngineStart(): Int {
            var f = seed
            while (procs(f).find(STD_EVENT) == null) f--
            return procs(f).find(STD_EVENT)!!.u32(0x30)
        }

        fun seedData() {
            val e = ewram(seed)
            bm.decode(e, BM_ST - EWRAM)
            playSt.decode(e, PLAY_ST - EWRAM)
            units.loadFrom(e)
            flags.copyIn(data.region(names.getValue(seed), "iwram"), FLAGS - IWRAM)
        }

        fun run() {
            hookUp()
            seedData()
            val mainProc = engine.start(main.script, BMAP_MAIN_TREE)
            mainProc.scrCur = procs(seed).find(MAIN)!!.scrCur
            assertEquals(12, mainProc.scrCur, "BMapMain after CallBeginningEvents")

            var f = seed + 1
            while (f <= last) {
                var cmp = f
                while (scrCur(cmp) == AT_SUSPEND) cmp++
                if (cmp > f && scrCur(cmp) == AT_START_CHILDREN) cmp++
                dumpFrame = cmp
                snapshot = null
                for (t in 0 until 8) engine.run(engine.root(t))
                if (cmp > f) {
                    val s = snapshot
                    assertTrue(s != null, "f$f: the suspend save was called")
                    for (g in f until cmp) if (scrCur(g) == AT_SUSPEND) compareSnapshot(g, s!!)
                }
                compare(cmp)
                if (procs(cmp).find(PLAYER_PHASE) != null) break
                f = cmp + 1
            }
        }

        fun scrCur(frame: Int): Int = procs(frame).find(MAIN)!!.scrCur

        fun hookUp() {
            val h = main.hooks
            h.clearActiveFactionGrayedStates = { calls.add("clearActiveFactionGrayedStates") }
            h.refreshUnitSprites = { calls.add("refreshUnitSprites") }
            h.processTurnSupportExp = { calls.add("processTurnSupportExp") }
            h.runPhaseSwitchEvents = { calls.add("runPhaseSwitchEvents"); false }
            h.writeSuspendSave = {
                calls.add("writeSuspendSave($it)")
                snapshot = Snapshot(
                    playSt.faction, playSt.chapterTurnNumber, flags.checkFlag(0x84),
                    engine.find(main.script)!!.scrCur,
                )
            }
            h.phaseIntro = ProcScript(
                "ProcScr_PhaseIntro",
                listOf(
                    ProcScripts.repeat {
                        if (procs(dumpFrame).find(PHASE_INTRO) == null) engine.end(it)
                    },
                    ProcScripts.end(),
                ),
            )
        }

        fun compareSnapshot(f: Int, s: Snapshot) {
            val at = "f$f (suspend save)"
            val e = ewram(f)
            val want = PlaySt().also { it.decode(e, PLAY_ST - EWRAM) }
            assertEquals(want.faction, s.faction, "$at faction")
            assertEquals(want.chapterTurnNumber, s.turn, "$at turn")
            assertEquals(procs(f).find(MAIN)!!.scrCur, s.scrCur, "$at BMapMain cmd")
            assertEquals(flagBit(f), s.flag84, "$at flag 0x84")
            compared++
        }

        fun flagBit(f: Int): Boolean {
            val iwram = data.region(names.getValue(f), "iwram")
            return iwram[FLAGS - IWRAM + 3].toInt() and 0x80 != 0
        }

        fun compare(f: Int) {
            val at = "f$f"
            val e = ewram(f)
            val want = PlaySt().also { it.decode(e, PLAY_ST - EWRAM) }
            assertEquals(want.faction, playSt.faction, "$at faction")
            assertEquals(want.chapterTurnNumber, playSt.chapterTurnNumber, "$at turn")
            assertEquals(flagBit(f), flags.checkFlag(0x84), "$at flag 0x84")
            val wantBm = BmSt().also { it.decode(e, BM_ST - EWRAM) }
            assertEquals(wantBm.cameraX, bm.cameraX, "$at camera.x")
            assertEquals(wantBm.cameraY, bm.cameraY, "$at camera.y")
            assertEquals(wantBm.playerCursorX, bm.playerCursorX, "$at cursor.x")
            assertEquals(wantBm.playerCursorY, bm.playerCursorY, "$at cursor.y")
            val unitOff = UnitTable.BLUE_ADDRESS - EWRAM
            for (i in units.bytes.indices) {
                assertEquals(e[unitOff + i], units.bytes[i], "$at unit byte $i")
            }

            val dump = procs(f)
            val wantMain = dump.find(MAIN)!!
            assertEquals(wantMain.scrCur, engine.find(main.script)!!.scrCur, "$at BMapMain cmd")
            val pairs = listOf(
                Triple(RESET, "ResetCursorPosition", reset.script),
                Triple(CAM_MOVE, "CamMove", camMove.script),
                Triple(PHASE_INTRO, "PhaseIntro", main.hooks.phaseIntro),
                Triple(PLAYER_PHASE, "PlayerPhase", main.hooks.playerPhase),
            )
            for ((address, name, script) in pairs) {
                val w = dump.find(address)
                val g: Proc? = engine.find(script)
                assertEquals(w == null, g == null, "$at $name proc live")
                if (w != null && address != PHASE_INTRO && address != PLAYER_PHASE) {
                    assertEquals(w.scrCur, g!!.scrCur, "$at $name cmd")
                }
            }
            if (dump.find(CAM_MOVE) != null) cameraMoves++
            compared++
        }
    }

    private class Snapshot(
        val faction: Int,
        val turn: Int,
        val flag84: Boolean,
        val scrCur: Int,
    )

    private companion object {
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val FLAGS = 0x03005250
        const val BMAP_MAIN_TREE = 2
        const val MAIN = 0x0859A1F0
        const val RESET = 0x0859AE00
        const val CAM_MOVE = 0x0859A548
        const val PHASE_INTRO = 0x0859AF80
        const val PLAYER_PHASE = 0x0859AAD8
        const val STD_EVENT = 0x08591AC0

        /** `scrCur` of BMapMain while it is inside `BmMain_SuspendBeforePhase`. */
        const val AT_SUSPEND = 18

        /**
         * `scrCur` of BMapMain in the VBlank during the first run of the phase start children
         * (the dump is taken inside `Proc_StartBlocking`).
         */
        const val AT_START_CHILDREN = 20
    }
}
