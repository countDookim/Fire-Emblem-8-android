package fe8.testing

import fe8.game.bm.EkrBattleAppear
import fe8.game.bm.EkrBattleAppear.EkrBaseAppear
import fe8.game.bm.EkrBattleAppear.EkrNamewinAppear
import fe8.game.bm.EkrBattleAppear.EkrWindowAppear
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.math.Interpolate
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * Differential verification of `NewEkrWindowAppear`, `EkrWindowAppearMain`,
 * `NewEkrNamewinAppear`, `EkrNamewinAppearDelay`, `EkrNamewinAppearMain`, `NewEkrBaseAppear`,
 * and `EkrBaseAppearMain` (`type == 0` arms) against `ram_trace_prologue_attack` (`f1482..1499`)
 * and `ram_trace_prologue_battle` (`f14451..14468`) (BA-p1b).
 *
 * `ProcScr_ekrWindowAppear` and `ProcScr_ekrBaseAppear` end at `f1497` / `f14468`;
 * `ProcScr_ekrNamewinAppear` runs one step longer because `EkrNamewinAppearDelay` breaks on its
 * first run (`f1485` / `f14456`). In `attack` (no quote), `namewin` ends at `f1499`; in `battle`
 * (`f14470` starts a new `type == 1` quote appear), the window stops at `f14468` when `namewin`
 * breaks (`exist == false`, `dispUp.sync == 1`). Documented no-run spillover steps (`f1483..1484`,
 * `f1498` in attack; `f14452..14455` in battle) are skipped.
 * Hook effects on `ProcScr_ekrGauge` (`unk32`, `unk3A`, `battle_init`) and `gProc_ekrDispUP`
 * (`x`, `y`, `sync`) are checked directly against those procs in the EWRAM dumps alongside the
 * `BG_2` scroll sequence.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class EkrBattleAppearDifferentialTest {
    private class ProcSnap(
        val cb: String?,
        val cur: Int,
        val timer: Int,
        val term: Int,
        val ymax: Int,
        val type: Int,
        val ymaxName: Int,
    )

    private class PanelSnap(
        val flag: Int,
        val x: Int,
        val y: Int,
    )

    private class Step(
        val ram: ByteArray,
        val quakeX: Int,
        val quakeY: Int,
        val win: ProcSnap?,
        val name: ProcSnap?,
        val base: ProcSnap?,
        val gauge: PanelSnap?,
        val dispUp: PanelSnap?,
    )

    private var attackSteps: Map<Int, Step>? = null
    private var scriptSteps: Map<Int, Step>? = null

    @AfterAll
    fun release() {
        attackSteps = null
        scriptSteps = null
    }

    private fun u8(e: ByteArray, a: Int) = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int) = u8(e, a) or (u8(e, a + 1) shl 8)
    private fun s16(e: ByteArray, a: Int) = u16(e, a).toShort().toInt()
    private fun u32(e: ByteArray, a: Int) = u16(e, a) or (u16(e, a + 2) shl 16)

    private fun load(run: String, range: IntRange): Map<Int, Step> {
        val ref = ReferenceData.fromEnvironment()
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val names = ProcArrayDump.romNames()
        val nm = ProcArrayDump.nmFile()
        assumeTrue(names.isNotEmpty() && nm != null, "decomp nm file not present")
        val funcs = HashMap<Int, String>()
        nm!!.forEachLine {
            val p = it.split(" ")
            if (p.size >= 4 && p[2] == "T") funcs[p[0].toLong(16).toInt() or 1] = p[3]
        }
        val capture = ref.run(run)
        val byStep = capture.dumps.entries.associate { it.value to it.key }
        val out = HashMap<Int, Step>()
        for (n in range) {
            val e = capture.region(byStep.getValue(n), "ewram")
            val all = ProcArrayDump.ofEwram(e, names)
            fun snap(procName: String): ProcSnap? {
                val p = all.find(procName) ?: return null
                val fn = p.u32(0x0C)
                return ProcSnap(
                    if (fn == 0) null else funcs[fn],
                    p.scrCur,
                    p.u16(0x2C).toShort().toInt(),
                    p.u16(0x2E).toShort().toInt(),
                    p.u16(0x30).toShort().toInt(),
                    p.u32(0x44),
                    p.u32(0x48),
                )
            }
            fun panel(procName: String): PanelSnap? {
                val p = all.find(procName) ?: return null
                return PanelSnap(
                    p.u8(0x29),
                    p.u16(0x32).toShort().toInt(),
                    p.u16(0x3A).toShort().toInt(),
                )
            }
            out[n] = Step(
                ram = e,
                quakeX = s16(e, BG0_QUAKE_VEC),
                quakeY = s16(e, BG0_QUAKE_VEC + 2),
                win = snap("ProcScr_ekrWindowAppear"),
                name = snap("ProcScr_ekrNamewinAppear"),
                base = snap("ProcScr_ekrBaseAppear"),
                gauge = panel("ProcScr_ekrGauge"),
                dispUp = panel("gProc_ekrDispUP"),
            )
        }
        return out
    }

    private fun attack(): Map<Int, Step> =
        attackSteps ?: load(ATTACK, ATTACK_START - 1..ATTACK_END).also { attackSteps = it }

    private fun script(): Map<Int, Step> =
        scriptSteps ?: load(SCRIPT, SCRIPT_START - 1..SCRIPT_END).also { scriptSteps = it }

    private fun verifyWindow(
        steps: Map<Int, Step>,
        start: Int,
        end: Int,
        winBaseEnd: Int,
        nameEnd: Int?,
        noRunAll: Set<Int>,
        noRunNamewin: Set<Int>,
    ) {
        val pre = steps.getValue(start - 1)
        assertEquals(0, u32(pre.ram, WINDOW_EXIST), "pre window exist")
        assertEquals(0, u32(pre.ram, NAMEWIN_EXIST), "pre namewin exist")
        assertEquals(0, u32(pre.ram, BASE_EXIST), "pre base exist")

        var quakeX = pre.quakeX
        var quakeY = pre.quakeY
        val env = object : EkrBattleAppear.Env {
            override val quakeX get() = quakeX
            override val quakeY get() = quakeY
        }

        val preGauge = pre.gauge!!
        val preDispUp = pre.dispUp!!
        var gaugeInit = preGauge.flag
        var gaugeX = preGauge.x
        var gaugeY = preGauge.y
        var dispUpSync = preDispUp.flag
        var dispUpX = preDispUp.x
        var dispUpY = preDispUp.y
        val bg2YHistory = ArrayList<Int>()
        val calls = ArrayList<String>()

        val hooks = object : EkrBattleAppear.Hooks {
            override fun ekrGaugeClr323A(x: Int, y: Int) {
                gaugeX = x
                gaugeY = y
                gaugeInit = 0
                calls += "clr323A($x,$y)"
            }

            override fun ekrGaugeClrInitFlag() {
                gaugeInit = 0
                calls += "clrInitFlag"
            }

            override fun ekrGaugeSetInitFlag() {
                gaugeInit = 1
                calls += "setInitFlag"
            }

            override fun ekrDispUpSetPositionUnsync(x: Int, y: Int) {
                dispUpX = x
                dispUpY = y
                dispUpSync = 0
                calls += "dispUpPos($x,$y)"
            }

            override fun unsyncEkrDispUp() {
                dispUpSync = 0
                calls += "unsyncDispUp"
            }

            override fun syncEkrDispUp() {
                dispUpSync = 1
                calls += "syncDispUp"
            }

            override fun endEkrDispUp() {
                calls += "endDispUp"
            }

            override fun bgSetPosition(bg: Int, x: Int, y: Int) {
                assertEquals(EkrBattleAppear.BG_2, bg)
                assertEquals(0, x)
                bg2YHistory += y
                calls += "bgPos($bg,$x,$y)"
            }
        }

        val appear = EkrBattleAppear(env, hooks)
        val win = appear.windowAppear
        val namewin = appear.namewinAppear
        val base = appear.baseAppear

        val winByName = mapOf("EkrWindowAppearMain" to EkrWindowAppear.Callback.MAIN)
        val nameByName = mapOf(
            "EkrNamewinAppearDelay" to EkrNamewinAppear.Callback.DELAY,
            "EkrNamewinAppearMain" to EkrNamewinAppear.Callback.MAIN,
        )
        val baseByName = mapOf("EkrBaseAppearMain" to EkrBaseAppear.Callback.MAIN)

        for (n in start..end) {
            val step = steps.getValue(n)
            if (n == start) {
                quakeX = step.quakeX
                quakeY = step.quakeY
                win.start(0, DURATION)
                namewin.start(0, DURATION, 0)
                base.start(0, DURATION)
            } else {
                val prev = steps.getValue(n - 1)
                quakeX = prev.quakeX
                quakeY = prev.quakeY
                if (n !in noRunAll) {
                    if (!win.ended) win.runStep()
                    if (!namewin.ended && n !in noRunNamewin) namewin.runStep()
                    if (!base.ended) base.runStep()
                }
            }

            val tag = "f$n"
            assertEquals(u32(step.ram, WINDOW_EXIST) != 0, win.exist, "$tag win exist")
            assertEquals(u32(step.ram, NAMEWIN_EXIST) != 0, namewin.exist, "$tag namewin exist")
            assertEquals(u32(step.ram, BASE_EXIST) != 0, base.exist, "$tag base exist")

            assertEquals(n >= winBaseEnd, win.ended, "$tag win ended")
            assertEquals(nameEnd != null && n >= nameEnd, namewin.ended, "$tag namewin ended")
            assertEquals(n >= winBaseEnd, base.ended, "$tag base ended")

            if (!win.ended) {
                val w = step.win!!
                assertEquals(w.cb?.let { winByName.getValue(it) }, win.callback, "$tag win cb")
                assertEquals(w.cur, win.cursor, "$tag win cur")
                assertEquals(w.timer, win.timer, "$tag win timer")
                assertEquals(w.term, win.terminator, "$tag win term")
                assertEquals(w.ymax, win.ymax, "$tag win ymax")
                assertEquals(w.type, win.type, "$tag win type")
            } else {
                assertEquals(null, step.win, "$tag win gone")
            }

            if (!namewin.ended) {
                val nw = step.name!!
                assertEquals(nw.cb?.let { nameByName.getValue(it) }, namewin.callback, "$tag nw cb")
                assertEquals(nw.cur, namewin.cursor, "$tag nw cur")
                assertEquals(nw.timer, namewin.timer, "$tag nw timer")
                assertEquals(nw.term, namewin.terminator, "$tag nw term")
                assertEquals(nw.ymax, namewin.ymax, "$tag nw ymax")
                assertEquals(nw.type, namewin.type, "$tag nw type")
                assertEquals(nw.ymaxName, namewin.ymaxName, "$tag nw ymaxName")
            } else {
                assertEquals(null, step.name, "$tag nw gone")
            }

            if (!base.ended) {
                val b = step.base!!
                assertEquals(b.cb?.let { baseByName.getValue(it) }, base.callback, "$tag base cb")
                assertEquals(b.cur, base.cursor, "$tag base cur")
                assertEquals(b.timer, base.timer, "$tag base timer")
                assertEquals(b.term, base.terminator, "$tag base term")
                assertEquals(b.type, base.type, "$tag base type")
            } else {
                assertEquals(null, step.base, "$tag base gone")
            }

            val g = step.gauge!!
            assertEquals(g.flag, gaugeInit, "$tag gauge battle_init")
            assertEquals(g.x, gaugeX, "$tag gauge x")
            assertEquals(g.y, gaugeY, "$tag gauge y")

            val du = step.dispUp!!
            assertEquals(du.flag, dispUpSync, "$tag dispUp sync")
            assertEquals(du.x, dispUpX, "$tag dispUp x")
            assertEquals(du.y, dispUpY, "$tag dispUp y")
        }

        val expectedBg2Y = listOf(-0x58) + (1..DURATION).map { t ->
            Interpolate.interpolate(Interpolate.SQUARE, -0x50, 0, t, DURATION)
        }
        assertEquals(expectedBg2Y, bg2YHistory, "BG_2 y history")

        val expectedWinCalls = (1..DURATION).map { t ->
            "clr323A(0,${Interpolate.interpolate(Interpolate.SQUARE, 0x39, 0, t, DURATION)})"
        }
        val expectedNameCalls = (1..DURATION).map { t ->
            "dispUpPos(0,${Interpolate.interpolate(Interpolate.SQUARE, -49, 0, t, DURATION)})"
        }
        val expectedBaseCalls = (1..DURATION).map { t ->
            "bgPos(2,0,${Interpolate.interpolate(Interpolate.SQUARE, -0x50, 0, t, DURATION)})"
        }
        val expectedCalls = ArrayList<String>()
        expectedCalls += listOf(
            "clr323A(0,57)",
            "clrInitFlag",
            "dispUpPos(0,-49)",
            "unsyncDispUp",
            "bgPos(2,0,-88)",
        )
        // First active step: window t=1, namewin Delay breaks, base t=1.
        expectedCalls += expectedWinCalls[0]
        expectedCalls += expectedBaseCalls[0]
        // Next 10 active steps: window t=2..11, namewin t=1..10, base t=2..11.
        for (i in 1 until DURATION) {
            expectedCalls += expectedWinCalls[i]
            expectedCalls += expectedNameCalls[i - 1]
            expectedCalls += expectedBaseCalls[i]
        }
        // Final main step for window/base (t >= 11) + namewin t=11.
        expectedCalls += "setInitFlag"
        expectedCalls += expectedNameCalls[DURATION - 1]
        // Final main step for namewin (t >= 11).
        expectedCalls += "syncDispUp"
        assertEquals(expectedCalls, calls, "hook call sequence")
    }

    @Test
    @VerifiesPort(
        "NewEkrWindowAppear",
        "EkrWindowAppearMain",
        "NewEkrNamewinAppear",
        "EkrNamewinAppearDelay",
        "EkrNamewinAppearMain",
        "NewEkrBaseAppear",
        "EkrBaseAppearMain",
    )
    fun `attack intro appear procs f1482 to f1499`() {
        verifyWindow(
            steps = attack(),
            start = ATTACK_START,
            end = ATTACK_END,
            winBaseEnd = 1497,
            nameEnd = 1499,
            noRunAll = ATTACK_NO_RUN_ALL,
            noRunNamewin = ATTACK_NO_RUN_NAMEWIN,
        )
    }

    @Test
    @VerifiesPort(
        "NewEkrWindowAppear",
        "EkrWindowAppearMain",
        "NewEkrNamewinAppear",
        "EkrNamewinAppearDelay",
        "EkrNamewinAppearMain",
        "NewEkrBaseAppear",
        "EkrBaseAppearMain",
    )
    fun `scripted battle intro appear procs f14451 to f14468`() {
        verifyWindow(
            steps = script(),
            start = SCRIPT_START,
            end = SCRIPT_END,
            winBaseEnd = 14468,
            nameEnd = null,
            noRunAll = SCRIPT_NO_RUN_ALL,
            noRunNamewin = emptySet(),
        )
    }

    private companion object {
        const val ATTACK = "ram_trace_prologue_attack"
        const val ATTACK_START = 1482
        const val ATTACK_END = 1499
        val ATTACK_NO_RUN_ALL = setOf(1483, 1484)
        val ATTACK_NO_RUN_NAMEWIN = setOf(1498)

        const val SCRIPT = "ram_trace_prologue_battle"
        const val SCRIPT_START = 14451
        const val SCRIPT_END = 14468
        val SCRIPT_NO_RUN_ALL = setOf(14452, 14453, 14454, 14455)

        const val DURATION = 0xB
        const val EWRAM = 0x02000000
        const val BG0_QUAKE_VEC = 0x02000038
        const val WINDOW_EXIST = 0x0201FACC
        const val NAMEWIN_EXIST = 0x0201FAD0
        const val BASE_EXIST = 0x0201FAD4
    }
}
