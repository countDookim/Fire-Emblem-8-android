package fe8.testing

import fe8.game.bm.EkrBattleEnding
import fe8.game.bm.EkrBattleEnding.Callback
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * `NewEkrbattleending` and the first five `ProcScr_ekrBattleEnding` callbacks
 * (`ekrBattleEnding_80560F0` to `ekrBattleEnding_8056288`, `gBanimBG == 0` arms) against
 * `ram_trace_prologue_attack` f1989 -> f2009 (BA-p8a). Dump N is the state after step N: the
 * callback (mapped through the nm file), script cursor and timer are compared per step, `x1..y2`
 * from f1993. The `ekrBattleEnding_8056288` timer does not advance in f1995..1997 (BA-i8:
 * unattributable without a proc-run trace), so those steps are not run. The procs the hooks
 * start and end are in the dumps only; the test checks the hook counts at the steps where the
 * dumps show them appear (f1993) and go (f2009), and `gEkrWindowAppearExist`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class EkrBattleEndingDifferentialTest {
    private class Step(
        val ram: ByteArray,
        val cb: String?,
        val cur: Int,
        val timer: Int,
        val box: List<Int>,
        val procs: Set<String>,
    )

    private var steps: Map<Int, Step>? = null

    @AfterAll
    fun release() {
        steps = null
    }

    private fun u8(e: ByteArray, a: Int) = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int) = u8(e, a) or (u8(e, a + 1) shl 8)
    private fun s16(e: ByteArray, a: Int) = u16(e, a).toShort().toInt()
    private fun u32(e: ByteArray, a: Int) = u16(e, a) or (u16(e, a + 2) shl 16)

    private fun load(): Map<Int, Step> {
        steps?.let { return it }
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
        val capture = ref.run(RUN)
        val byStep = capture.dumps.entries.associate { it.value to it.key }
        val out = HashMap<Int, Step>()
        for (n in START..END) {
            val e = capture.region(byStep.getValue(n), "ewram")
            val all = ProcArrayDump.ofEwram(e, names)
            val p = all.find("ProcScr_ekrBattleEnding")
            checkNotNull(p) { "ending proc missing at f$n" }
            val fn = p.u32(0x0C)
            out[n] = Step(
                e,
                if (fn == 0) null else funcs[fn],
                p.scrCur,
                p.u16(0x2C),
                listOf(0x32, 0x34, 0x3A, 0x3C).map { p.u16(it) },
                all.live.map { it.name ?: "?" }.toSet(),
            )
        }
        steps = out
        return out
    }

    @Test
    @VerifiesPort(
        "NewEkrbattleending",
        "ekrBattleEnding_8056228",
        "ekrBattleEnding_8056288",
    )
    fun `ending start, box setup and wait f1989 to f2009`() {
        val s = load()
        val first = s.getValue(START).ram
        var banimBg = u16(first, BANIM_BG)
        val loc = IntArray(4)
        val env = object : EkrBattleEnding.Env {
            override val banimBg get() = banimBg
            override val bmLocation get() = loc
        }
        val calls = ArrayList<String>()
        val hooks = object : EkrBattleEnding.Hooks {
            override fun animClearAll() {
                calls += "animClearAll"
            }

            override fun newEkrUnitKakudai(arg: Int) {
                calls += "unitKakudai($arg)"
            }

            override fun newEkrBaseKaiten(arg: Int) {
                calls += "baseKaiten($arg)"
            }

            override fun newEkrWindowAppear(arg: Int, duration: Int) {
                calls += "windowAppear($arg,$duration)"
            }

            override fun newEkrBaseAppear(arg: Int, duration: Int) {
                calls += "baseAppear($arg,$duration)"
            }

            override fun endEkrGauge() {
                calls += "endGauge"
            }

            override fun initBmBgLayers() {
                calls += "initBgLayers"
            }

            override fun setWinEnable(win0: Int, win1: Int, objWin: Int) {
                calls += "winEnable($win0,$win1,$objWin)"
            }

            override fun setWin0Box(left: Int, top: Int, right: Int, bottom: Int) {
                calls += "box($left,$top,$right,$bottom)"
            }

            override fun setWin0Layers(bg0: Int, bg1: Int, bg2: Int, bg3: Int, obj: Int) {
                calls += "win0Layers($bg0,$bg1,$bg2,$bg3,$obj)"
            }

            override fun setWOutLayers(bg0: Int, bg1: Int, bg2: Int, bg3: Int, obj: Int) {
                calls += "woutLayers($bg0,$bg1,$bg2,$bg3,$obj)"
            }
        }
        val ending = EkrBattleEnding(env, hooks)
        val byName = mapOf(
            "ekrBattleEnding_80560F0" to Callback.C_80560F0,
            "ekrBattleEnding_8056170" to Callback.C_8056170,
            "ekrBattleEnding_80561C8" to Callback.C_80561C8,
            "ekrBattleEnding_8056228" to Callback.C_8056228,
            "ekrBattleEnding_8056288" to Callback.C_8056288,
        )

        ending.start()
        for (n in START..END) {
            val step = s.getValue(n)
            if (n > START) {
                val prev = s.getValue(n - 1).ram
                banimBg = u16(prev, BANIM_BG)
                for (i in 0..3) loc[i] = s16(prev, BM_LOCATION + 2 * i)
                if (n !in NO_RUN) ending.runStep()
            }
            val tag = "f$n"
            assertEquals(step.cb?.let { byName.getValue(it) }, ending.callback, "$tag callback")
            assertEquals(step.cur, ending.cursor, "$tag cursor")
            assertEquals(step.timer, ending.timer, "$tag timer")
            if (n >= 1993) {
                assertEquals(
                    step.box,
                    listOf(ending.x1, ending.x2, ending.y1, ending.y2),
                    "$tag x1 x2 y1 y2",
                )
            }
            if (n == 1993) {
                assertEquals(
                    listOf(
                        "animClearAll", "unitKakudai(1)", "baseKaiten(1)", "windowAppear(1,11)",
                        "baseAppear(1,11)",
                    ),
                    calls,
                    "f1993 hooks",
                )
                assertEquals(0, u32(s.getValue(1992).ram, WINDOW_APPEAR_EXIST))
                assertEquals(1, u32(step.ram, WINDOW_APPEAR_EXIST))
                assertEquals(
                    setOf(
                        "ProcScr_EkrBaseKaiten", "ProcScr_ekrWindowAppear",
                        "ProcScr_ekrUnitKakudai", "ProcScr_ekrBaseAppear",
                    ),
                    step.procs - s.getValue(1992).procs,
                )
            } else if (n < 2009) {
                assertEquals(if (n >= 1993) 5 else 0, calls.size, "$tag hook calls")
            }
        }
        assertEquals(
            listOf(
                "animClearAll", "unitKakudai(1)", "baseKaiten(1)", "windowAppear(1,11)",
                "baseAppear(1,11)", "endGauge", "initBgLayers", "winEnable(1,0,0)",
                "box(0,0,0,0)", "win0Layers(1,1,1,1,1)", "woutLayers(1,1,1,1,0)",
            ),
            calls,
        )
        assertEquals(0, u32(s.getValue(END).ram, WINDOW_APPEAR_EXIST))
        assertEquals(
            setOf("ProcScr_ekrGauge", "ProcScr_EkrBaseKaiten", "ProcScr_ekrWindowAppear",
                "ProcScr_ekrBaseAppear"),
            s.getValue(2008).procs - s.getValue(END).procs,
        )
    }

    private companion object {
        const val RUN = "ram_trace_prologue_attack"
        const val START = 1989
        const val END = 2009
        val NO_RUN = 1995..1997
        const val EWRAM = 0x02000000
        const val WINDOW_APPEAR_EXIST = 0x0201FACC
        const val BANIM_BG = 0x0203E0FE
        const val BM_LOCATION = 0x0203E122
    }
}
