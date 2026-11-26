package fe8.testing

import fe8.game.bm.BattleDeamonFlag
import fe8.game.bm.EkrBattleStarting
import fe8.game.bm.EkrBattleStarting.Callback
import fe8.game.bm.EkrBattleStarting.DeamonCallback
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * `NewEkrBattleDeamon`, `nullsub_35`, `NewEkrBattleStarting`, `ekrBaStart_InitScreen`,
 * `ekrBaStart_SreenFailIn`, `ekrBaStart_InitBattleScreen` (`debugMode == 0`, `CLOSE` arm), and
 * `ekrBaStart_ExecEkrBattle6C` (`gBanimBG == 0` arm) against `ram_trace_prologue_attack`
 * (`f1464..1496`) and `ram_trace_prologue_battle` (`f14431..14467`) (BA-p1a).
 *
 * Dump N is the state after step N. No-run spillover steps documented in BA-i1 (where
 * `InitBattleScreen` or `UnitKakudaiPrepareAnimScript` spanned VBlanks and `ekrBattleStarting`
 * did not step) are skipped in the test loop.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class EkrBattleStartingDifferentialTest {
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
            val p = all.find("ProcScr_ekrBattleStarting")
            val fn = p?.u32(0x0C) ?: 0
            val d = all.find("gProc_ekrBattleDeamon")
            val dfn = d?.u32(0x0C) ?: 0
            out[n] = Step(
                e,
                if (fn == 0) null else funcs[fn],
                p?.scrCur ?: -1,
                p?.u16(0x2C) ?: -1,
                p?.u16(0x2E) ?: -1,
                if (p == null) emptyList() else listOf(0x32, 0x34, 0x3A, 0x3C).map { p.u16(it) },
                if (dfn == 0) null else funcs[dfn],
                d?.scrCur ?: -1,
                all.live.map { it.name ?: "?" }.toSet(),
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
        initRanStep: Int,
        initBattleStartStep: Int,
        initBattleEndStep: Int,
        noRun: Set<Int>,
        runInitOnStart: Boolean,
    ) {
        val pre = steps.getValue(start - 1).ram
        assertEquals(0, u32(pre, DEAMON_ACTIVE), "pre deamon active")
        var gameLock = u8(pre, BMST_LOCK)

        val loc = IntArray(4)
        var debugMode = 0
        var distanceType = 0
        val banimValid = BooleanArray(2)
        var banimBg = 0
        var dragonStatusType = 0

        fun readEnv(ram: ByteArray) {
            for (i in 0..3) loc[i] = s16(ram, BM_LOCATION + 2 * i)
            debugMode = s16(ram, DEBUG_MODE)
            distanceType = s16(ram, DISTANCE_TYPE)
            for (i in 0..1) banimValid[i] = s16(ram, BANIM_VALID + 2 * i) != 0
            banimBg = s16(ram, BANIM_BG)
            val leftDragon = u8(ram, DRAGON_STATUS)
            val rightDragon = u8(ram, DRAGON_STATUS + 0x10)
            dragonStatusType = if (leftDragon != 0) leftDragon else rightDragon
        }

        val env = object : EkrBattleStarting.Env {
            override val bmLocation get() = loc
            override val debugMode get() = debugMode
            override val distanceType get() = distanceType
            override val banimValid get() = banimValid
            override val banimBg get() = banimBg
            override val dragonStatusType get() = dragonStatusType
        }
        val calls = ArrayList<String>()
        val hooks = object : EkrBattleStarting.Hooks {
            override fun lockGame() {
                gameLock++
                calls += "lockGame"
            }
            override fun clearBg2Tilemap() { calls += "clearBg2" }
            override fun setBlendConfig(effect: Int, a: Int, b: Int, y: Int) {
                calls += "blendConfig($effect,$a,$b,$y)"
            }
            override fun setBlendTargetA(bg0: Int, bg1: Int, bg2: Int, bg3: Int, obj: Int) {
                calls += "blendTargetA($bg0,$bg1,$bg2,$bg3,$obj)"
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
            override fun setWinEnableBlend(win0: Int, wout: Int) {
                calls += "winEnableBlend($win0,$wout)"
            }
            override fun initOam(lo: Int) { calls += "initOam($lo)" }
            override fun bmapDispSuspend() { calls += "bmapDispSuspend" }
            override fun efxPalBlackInOut(line: Int, length: Int, ref: Int) {
                calls += "palBlackInOut($line,$length,$ref)"
            }
            override fun enablePaletteSync() { calls += "paletteSync" }
            override fun endAllMus() { calls += "endAllMus" }
            override fun newEkrGauge() { calls += "newEkrGauge" }
            override fun newEkrDispUp() { calls += "newEkrDispUp" }
            override fun setGaugeAndDispUp4C() { calls += "set4C" }
            override fun setGaugeAndDispUp50() { calls += "set50" }
            override fun efxClearScreenFx() { calls += "efxClearScreenFx" }
            override fun newEkrUnitKakudai(arg: Int) { calls += "unitKakudai($arg)" }
            override fun newEkrBaseKaiten(arg: Int) { calls += "baseKaiten($arg)" }
            override fun newEkrWindowAppear(arg: Int, duration: Int) {
                calls += "windowAppear($arg,$duration)"
            }
            override fun newEkrNamewinAppear(arg: Int, duration: Int, delay: Int) {
                calls += "namewinAppear($arg,$duration,$delay)"
            }
            override fun newEkrBaseAppear(arg: Int, duration: Int) {
                calls += "baseAppear($arg,$duration)"
            }
            override fun newEkrBattle() { calls += "newEkrBattle" }
        }

        val flag = BattleDeamonFlag()
        assertFalse(flag.isBattleDeamonActive())
        val starting = EkrBattleStarting(env, hooks, flag)
        val byName = mapOf(
            "ekrBaStart_InitScreen" to Callback.INIT_SCREEN,
            "ekrBaStart_SreenFailIn" to Callback.SREEN_FAIL_IN,
            "ekrBaStart_InitBattleScreen" to Callback.INIT_BATTLE_SCREEN,
            "ekrBaStart_ExecEkrBattle6C" to Callback.EXEC_EKR_BATTLE_6C,
        )
        val deamonByName = mapOf("nullsub_35" to DeamonCallback.NULLSUB_35)

        val sizes = HashMap<Int, Int>()
        for (n in start..end) {
            val step = steps.getValue(n)
            if (n == start) {
                readEnv(step.ram)
                starting.newEkrBattleDeamon()
                starting.start()
                starting.runDeamonStep()
                if (runInitOnStart) starting.runStep()
            } else {
                readEnv(steps.getValue(n - 1).ram)
                if (n !in noRun) {
                    starting.runDeamonStep()
                    starting.runStep()
                }
            }
            sizes[n] = calls.size
            val tag = "f$n"

            assertEquals(
                u32(step.ram, DEAMON_ACTIVE),
                flag.gBattleDeamonActive,
                "$tag deamonActive",
            )
            assertEquals(u8(step.ram, BMST_LOCK), gameLock, "$tag gBmSt.lock")
            assertTrue(flag.isBattleDeamonActive(), "$tag isBattleDeamonActive")
            assertEquals(step.deamonCb != null, starting.deamonActive, "$tag daemon active")
            assertEquals(
                step.deamonCb?.let { deamonByName.getValue(it) },
                starting.deamonCallback,
                "$tag daemon callback",
            )
            assertEquals(step.deamonCur, starting.deamonCursor, "$tag daemon cursor")

            assertEquals(n == end, starting.ended, "$tag proc ended")
            if (!starting.ended) {
                assertEquals(step.cb?.let { byName.getValue(it) }, starting.callback, "$tag cb")
                assertEquals(step.cur, starting.cursor, "$tag cursor")
                if (n >= initRanStep) {
                    assertEquals(step.timer, starting.timer, "$tag timer")
                    assertEquals(step.term, starting.terminator, "$tag terminator")
                    assertEquals(
                        step.box,
                        listOf(starting.x1, starting.x2, starting.y1, starting.y2),
                        "$tag x1 x2 y1 y2",
                    )
                }
            }
        }

        val cx = (loc[0] + loc[2]) * 8 + 8
        val cy = (loc[1] + loc[3]) * 8 + 8
        val sreenFailInCalls = (1..15).flatMap { t ->
            val l = (cx * t) / 15
            val tp = (cy * t) / 15
            val r = 240 + ((cx - 240) * t) / 15
            val b = 160 + ((cy - 160) * t) / 15
            listOf("box($l,$tp,$r,$b)") + if (t == 15) {
                listOf(
                    "woutLayers(1,1,1,1,1)", "initOam(0)", "bmapDispSuspend", "box(0,0,240,160)",
                    "palBlackInOut(6,10,4)", "paletteSync", "endAllMus",
                )
            } else {
                emptyList()
            }
        }
        val expectedCalls = listOf("lockGame") + listOf(
            "clearBg2", "blendConfig(3,0,0,4)", "blendTargetA(0,0,0,1,0)", "winEnable(1,0,0)",
            "box(0,0,240,160)", "win0Layers(1,1,1,1,1)", "woutLayers(1,1,1,1,0)",
            "winEnableBlend(0,1)",
        ) + sreenFailInCalls + listOf(
            "newEkrGauge", "newEkrDispUp", "efxClearScreenFx", "unitKakudai(0)", "baseKaiten(0)",
            "windowAppear(0,11)", "namewinAppear(0,11,0)", "baseAppear(0,11)",
        ) + listOf("newEkrBattle")
        assertEquals(expectedCalls, calls)

        assertEquals(if (runInitOnStart) 9 else 1, sizes.getValue(start))
        assertEquals(9, sizes.getValue(initRanStep))
        assertEquals(9 + 14 + 8, sizes.getValue(initBattleStartStep - 1))
        assertEquals(9 + 14 + 8 + 8, sizes.getValue(initBattleStartStep))
        assertEquals(sizes.getValue(initBattleStartStep), sizes.getValue(end - 1))
        assertEquals(expectedCalls.size, sizes.getValue(end))

        val added = { n: Int -> steps.getValue(n).procs - steps.getValue(n - 1).procs }
        val removed = { n: Int -> steps.getValue(n - 1).procs - steps.getValue(n).procs }
        assertTrue(
            added(start).containsAll(setOf("ProcScr_ekrBattleStarting", "gProc_ekrBattleDeamon")),
            "start procs",
        )
        assertTrue("ProcScr_Mu" in removed(initBattleStartStep - 1), "endAllMus removed Mu")
        assertEquals(
            setOf("ProcScr_ekrGauge", "gProc_ekrDispUP"),
            added(initBattleStartStep),
            "gauge and dispUP started",
        )
        assertTrue(
            steps.getValue(initBattleEndStep).procs.containsAll(
                setOf(
                    "ProcScr_ekrUnitKakudai",
                    "ProcScr_EkrBaseKaiten",
                    "ProcScr_ekrWindowAppear",
                    "ProcScr_ekrNamewinAppear",
                    "ProcScr_ekrBaseAppear",
                ),
            ),
            "intro procs started",
        )
        assertTrue("gProc_ekrBattle" in added(end), "ekrBattle started")
        assertTrue("ProcScr_ekrBattleStarting" in removed(end), "ekrBattleStarting ended")
    }

    @Test
    @VerifiesPort(
        "NewEkrBattleDeamon",
        "nullsub_35",
        "NewEkrBattleStarting",
        "ekrBaStart_InitScreen",
        "ekrBaStart_SreenFailIn",
        "ekrBaStart_InitBattleScreen",
        "ekrBaStart_ExecEkrBattle6C",
    )
    fun `attack entry daemon and starting proc f1464 to f1496`() {
        verifyWindow(
            steps = attack(),
            start = ATTACK_START,
            end = ATTACK_END,
            initRanStep = 1464,
            initBattleStartStep = 1480,
            initBattleEndStep = 1482,
            noRun = ATTACK_NO_RUN,
            runInitOnStart = true,
        )
    }

    @Test
    @VerifiesPort(
        "NewEkrBattleDeamon",
        "nullsub_35",
        "NewEkrBattleStarting",
        "ekrBaStart_InitScreen",
        "ekrBaStart_SreenFailIn",
        "ekrBaStart_InitBattleScreen",
        "ekrBaStart_ExecEkrBattle6C",
    )
    fun `scripted battle entry daemon and starting proc f14431 to f14467`() {
        verifyWindow(
            steps = script(),
            start = SCRIPT_START,
            end = SCRIPT_END,
            initRanStep = 14432,
            initBattleStartStep = 14448,
            initBattleEndStep = 14451,
            noRun = SCRIPT_NO_RUN,
            runInitOnStart = false,
        )
    }

    private class Step(
        val ram: ByteArray,
        val cb: String?,
        val cur: Int,
        val timer: Int,
        val term: Int,
        val box: List<Int>,
        val deamonCb: String?,
        val deamonCur: Int,
        val procs: Set<String>,
    )

    private companion object {
        const val ATTACK = "ram_trace_prologue_attack"
        const val ATTACK_START = 1464
        const val ATTACK_END = 1496
        val ATTACK_NO_RUN = setOf(1481, 1484, 1485)

        const val SCRIPT = "ram_trace_prologue_battle"
        const val SCRIPT_START = 14431
        const val SCRIPT_END = 14467
        val SCRIPT_NO_RUN = setOf(14449, 14450, 14453, 14454, 14455, 14456)

        const val EWRAM = 0x02000000
        const val DRAGON_STATUS = 0x02020048
        const val BMST_LOCK = 0x0202BCB1
        const val DEAMON_ACTIVE = 0x0203E0F4
        const val DEBUG_MODE = 0x0203E0FC
        const val BANIM_BG = 0x0203E0FE
        const val BANIM_VALID = 0x0203E104
        const val DISTANCE_TYPE = 0x0203E120
        const val BM_LOCATION = 0x0203E122
    }
}
