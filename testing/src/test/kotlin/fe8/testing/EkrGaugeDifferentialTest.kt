package fe8.testing

import fe8.game.bm.EkrGauge
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.math.Interpolate
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * Differential verification of [EkrGauge] setup, control state, and lifecycle (BA-p9a) against
 * `ram_trace_prologue_attack` (`f1479..f1496`, `f1861..f1862`, `f1981..f1982`, and
 * `f1992..f2009`).
 *
 * `NewEkrGauge` creates `ProcScr_ekrGauge` at `f1480` with `gEkrGaugeHpBak == (-1, -1)`; the
 * entry (`f1482..f1496`) and ending (`f1993..f2008`) `ProcScr_ekrWindowAppear` windows drive
 * `EkrGauge_Clr323A`, `EkrGauge_ClrInitFlag`, and `EkrGauge_SetInitFlag`; `ekrBattleExecExpGain`
 * (`f1862`) and `ekrBattleLvupHanlder` (`f1982`) drive `EkrGauge_Setup44`; and
 * `ekrBattleEnding_8056288` ends `ProcScr_ekrGauge` via `EndEkrGauge` at `f2009`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(120)
class EkrGaugeDifferentialTest {
    private class GaugeState(
        val battleInit: Int,
        val valid: Int,
        val unk32: Int,
        val unk3A: Int,
        val unk44: Int,
        val unk4C: Int,
        val unk50: Int,
    )

    private class WindowState(
        val cb: Int,
        val timer: Int,
        val terminator: Int,
        val ymax: Int,
        val type: Int,
    )

    private class Step(
        val gpGauge: Int,
        val gauge: GaugeState?,
        val win: WindowState?,
        val hpBak: List<Int>,
        val gaugeHp: IntArray,
        val factionPal: IntArray,
        val hit: IntArray,
        val dmg: IntArray,
        val crt: IntArray,
        val quakeX: Int,
        val quakeY: Int,
        val weaponBeforeLeft: Int,
        val weaponBeforeRight: Int,
        val reusedSlot: GaugeState?,
    )

    private var steps: Map<Int, Step>? = null

    @AfterAll
    fun release() {
        steps = null
    }

    private fun u8(e: ByteArray, a: Int): Int = e[a - EWRAM].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int): Int = u8(e, a) or (u8(e, a + 1) shl 8)
    private fun s16(e: ByteArray, a: Int): Int = u16(e, a).toShort().toInt()
    private fun u32(e: ByteArray, a: Int): Int = u16(e, a) or (u16(e, a + 2) shl 16)

    private fun readGaugeState(proc: ProcArrayDump.Proc): GaugeState = GaugeState(
        battleInit = proc.u8(0x29),
        valid = proc.u8(0x2A),
        unk32 = proc.u16(0x32).toShort().toInt(),
        unk3A = proc.u16(0x3A).toShort().toInt(),
        unk44 = proc.u16(0x44),
        unk4C = proc.u16(0x4C),
        unk50 = proc.u16(0x50),
    )

    private fun readGaugeStateRaw(ewram: ByteArray, addr: Int): GaugeState = GaugeState(
        battleInit = u8(ewram, addr + 0x29),
        valid = u8(ewram, addr + 0x2A),
        unk32 = s16(ewram, addr + 0x32),
        unk3A = s16(ewram, addr + 0x3A),
        unk44 = u16(ewram, addr + 0x44),
        unk4C = u16(ewram, addr + 0x4C),
        unk50 = u16(ewram, addr + 0x50),
    )

    private fun load(): Map<Int, Step> {
        steps?.let { return it }
        val ref = ReferenceData.fromEnvironment()
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val capture = ref.run(RUN)
        val byStep = capture.dumps.entries.associate { it.value to it.key }
        val neededSteps = (1479..1496) + listOf(1861, 1862, 1981, 1982) + (1992..2009)
        val f1480Ewram = capture.region(byStep.getValue(1480), "ewram")
        val gaugeSlotAddr = u32(f1480Ewram, GP_PROC_EKR_GAUGE)
        val out = HashMap<Int, Step>(neededSteps.size)
        for (n in neededSteps) {
            val e = capture.region(byStep.getValue(n), "ewram")
            val all = ProcArrayDump.ofEwram(e, names)
            val gp = all.find("ProcScr_ekrGauge")
            val wp = all.find("ProcScr_ekrWindowAppear")
            val unitLeft = u32(e, UNIT_LEFT)
            val unitRight = u32(e, UNIT_RIGHT)
            out[n] = Step(
                gpGauge = u32(e, GP_PROC_EKR_GAUGE),
                gauge = gp?.let(::readGaugeState),
                win = wp?.let {
                    WindowState(
                        cb = it.u32(0x0C),
                        timer = it.u16(0x2C).toShort().toInt(),
                        terminator = it.u16(0x2E).toShort().toInt(),
                        ymax = it.u16(0x30).toShort().toInt(),
                        type = it.u32(0x44),
                    )
                },
                hpBak = listOf(s16(e, GAUGE_HP_BAK), s16(e, GAUGE_HP_BAK + 2)),
                gaugeHp = intArrayOf(s16(e, GAUGE_HP), s16(e, GAUGE_HP + 2)),
                factionPal = intArrayOf(s16(e, FACTION_PAL), s16(e, FACTION_PAL + 2)),
                hit = intArrayOf(s16(e, GAUGE_HIT), s16(e, GAUGE_HIT + 2)),
                dmg = intArrayOf(s16(e, GAUGE_DMG), s16(e, GAUGE_DMG + 2)),
                crt = intArrayOf(s16(e, GAUGE_CRT), s16(e, GAUGE_CRT + 2)),
                quakeX = s16(e, BG0_QUAKE),
                quakeY = s16(e, BG0_QUAKE + 2),
                weaponBeforeLeft = u16(e, unitLeft + 0x4A),
                weaponBeforeRight = u16(e, unitRight + 0x4A),
                reusedSlot = if (n == 1479) readGaugeStateRaw(e, gaugeSlotAddr) else null,
            )
        }
        steps = out
        return out
    }

    private fun assertMatches(stepNum: Int, expected: GaugeState, actual: EkrGauge) {
        assertEquals(true, actual.active, "f$stepNum active")
        assertEquals(expected.battleInit, actual.battleInit, "f$stepNum battleInit")
        assertEquals(expected.valid, actual.valid, "f$stepNum valid")
        assertEquals(expected.unk32, actual.unk32, "f$stepNum unk32")
        assertEquals(expected.unk3A, actual.unk3A, "f$stepNum unk3A")
        assertEquals(expected.unk44, actual.unk44, "f$stepNum unk44")
        assertEquals(expected.unk4C, actual.unk4C, "f$stepNum unk4C")
        assertEquals(expected.unk50, actual.unk50, "f$stepNum unk50")
    }

    @Test
    @VerifiesPort(
        "NewEkrGauge",
        "EndEkrGauge",
        "EkrGauge_Clr4C50",
        "EkrGauge_Setup44",
        "EkrGauge_Clr323A",
        "EkrGauge_SetInitFlag",
        "EkrGauge_ClrInitFlag",
        "DisableEkrGauge",
    )
    fun `gauge setup, window transitions, setup44 and end against prologue attack`() {
        val s = load()
        val f1479 = s.getValue(1479)
        assertNull(f1479.gauge, "f1479 ProcScr_ekrGauge absent")
        assertEquals(0, f1479.gpGauge, "f1479 gpProcEkrGauge")

        var curEnv = f1479
        val env = object : EkrGauge.Env {
            override val quakeX get() = curEnv.quakeX
            override val quakeY get() = curEnv.quakeY
            override val gaugeHp get() = curEnv.gaugeHp
            override val factionPal get() = curEnv.factionPal
            override val hit get() = curEnv.hit
            override val dmg get() = curEnv.dmg
            override val crt get() = curEnv.crt
            override val weaponBeforeLeft get() = curEnv.weaponBeforeLeft
            override val weaponBeforeRight get() = curEnv.weaponBeforeRight
        }
        val calls = ArrayList<String>()
        val hooks = object : EkrGauge.Hooks {
            override fun copyHpBarPalette(side: Int, purple: Boolean, factionPal: Int) {
                calls += "copyHpBarPalette($side,$purple,$factionPal)"
            }

            override fun lz77UnCompSideGfx() {
                calls += "lz77UnCompSideGfx"
            }

            override fun copySideFactionPalette(side: Int, factionPal: Int) {
                calls += "copySideFactionPalette($side,$factionPal)"
            }

            override fun enablePaletteSync() {
                calls += "enablePaletteSync"
            }

            override fun modDecAndRegisterHitDmgCrit(
                hit0: Int,
                dmg0: Int,
                crt0: Int,
                hit1: Int,
                dmg1: Int,
                crt1: Int,
            ) {
                calls += "modDecAndRegisterHitDmgCrit($hit0,$dmg0,$crt0,$hit1,$dmg1,$crt1)"
            }

            override fun resetIconGraphics() {
                calls += "resetIconGraphics"
            }

            override fun loadIconPalette(palSlot: Int, palBank: Int) {
                calls += "loadIconPalette($palSlot,$palBank)"
            }

            override fun loadIconObjectGraphics(weaponId: Int, oamTile: Int) {
                calls += "loadIconObjectGraphics($weaponId,$oamTile)"
            }

            override fun applyMiscUiPalette(palOffset: Int) {
                calls += "applyMiscUiPalette($palOffset)"
            }
        }

        val gauge = EkrGauge(env, hooks)
        // Preload reused slot garbage from f1479 and stale non-zero flags that NewEkrGauge clears.
        val stale = f1479.reusedSlot!!
        gauge.battleInit = 1
        gauge.valid = 1
        gauge.unk32 = stale.unk32
        gauge.unk3A = stale.unk3A
        gauge.unk44 = stale.unk44
        gauge.unk4C = stale.unk4C
        gauge.unk50 = 1
        gauge.hpBak[0] = 0
        gauge.hpBak[1] = 0

        gauge.newEkrGauge()
        val f1480 = s.getValue(1480)
        assertMatches(1480, f1480.gauge!!, gauge)
        assertEquals(f1480.hpBak, gauge.hpBak.toList(), "f1480 gEkrGaugeHpBak")
        assertEquals(listOf(-1, -1), s.getValue(1481).hpBak, "f1481 gEkrGaugeHpBak")
        assertEquals(listOf(-1, -1), s.getValue(1482).hpBak, "f1482 gEkrGaugeHpBak")
        assertEquals(s.getValue(1483).gaugeHp.toList(), s.getValue(1483).hpBak, "f1483 hpBak")
        assertEquals(
            listOf(
                "copyHpBarPalette(0,${f1479.gaugeHp[0] > 0x50},${f1479.factionPal[0]})",
                "copyHpBarPalette(1,${f1479.gaugeHp[1] > 0x50},${f1479.factionPal[1]})",
                "lz77UnCompSideGfx",
                "copySideFactionPalette(0,${f1479.factionPal[0]})",
                "copySideFactionPalette(1,${f1479.factionPal[1]})",
                "enablePaletteSync",
                "modDecAndRegisterHitDmgCrit(${f1479.hit[0]},${f1479.dmg[0]},${f1479.crt[0]}," +
                    "${f1479.hit[1]},${f1479.dmg[1]},${f1479.crt[1]})",
                "resetIconGraphics",
                "loadIconPalette(0,29)",
                "loadIconPalette(0,30)",
                "loadIconObjectGraphics(${f1479.weaponBeforeLeft},476)",
                "loadIconObjectGraphics(${f1479.weaponBeforeRight},478)",
                "applyMiscUiPalette(16)",
            ),
            calls,
            "f1480 NewEkrGauge hooks",
        )

        // Entry window appear (f1482..f1496): type 0, SQUARE from ymax to 0, then SetInitFlag.
        val f1482 = s.getValue(1482)
        val entryWinStart = f1482.win!!
        assertEquals(0, entryWinStart.type)
        gauge.clr323A(f1482.quakeX, f1482.quakeY + entryWinStart.ymax)
        gauge.clrInitFlag()
        assertMatches(1482, f1482.gauge!!, gauge)
        for (n in 1483..1495) {
            val prevWin = s.getValue(n - 1).win!!
            val post = s.getValue(n)
            val win = post.win!!
            if (win.timer > prevWin.timer) {
                val iy = Interpolate.interpolate(
                    Interpolate.SQUARE,
                    win.ymax,
                    0,
                    win.timer,
                    win.terminator,
                )
                gauge.clr323A(post.quakeX, post.quakeY + iy)
            }
            assertMatches(n, post.gauge!!, gauge)
        }
        val f1496 = s.getValue(1496)
        assertEquals(0, f1496.win!!.cb)
        gauge.setInitFlag()
        assertMatches(1496, f1496.gauge!!, gauge)

        // Exp bar priority shift (f1862: setup44(1) -> 0x400; f1982: setup44(0) -> 0).
        assertEquals(0, s.getValue(1861).gauge!!.unk44, "f1861 unk44")
        gauge.setup44(1)
        assertEquals(s.getValue(1862).gauge!!.unk44, gauge.unk44, "f1862 unk44")
        assertEquals(0x400, s.getValue(1981).gauge!!.unk44, "f1981 unk44")
        gauge.setup44(0)
        assertEquals(s.getValue(1982).gauge!!.unk44, gauge.unk44, "f1982 unk44")

        // Ending window appear (f1993..f2008): type 1, clr323A + clrInitFlag clear battleInit.
        val f1992 = s.getValue(1992)
        val f1993 = s.getValue(1993)
        assertMatches(1992, f1992.gauge!!, gauge)
        // Verify clrInitFlag independently clears battleInit from f1992 (1) to f1993 (0).
        gauge.clrInitFlag()
        assertEquals(f1993.gauge!!.battleInit, gauge.battleInit, "f1993 clrInitFlag battleInit")
        // Verify clr323A independently clears battleInit from f1992 (1) to f1993 (0).
        gauge.battleInit = f1992.gauge.battleInit
        gauge.clr323A(f1993.quakeX, f1993.quakeY)
        assertEquals(f1993.gauge.battleInit, gauge.battleInit, "f1993 clr323A battleInit")
        gauge.clrInitFlag()
        assertMatches(1993, f1993.gauge, gauge)

        for (n in 1994..2007) {
            val prevWin = s.getValue(n - 1).win!!
            val post = s.getValue(n)
            val win = post.win!!
            if (win.timer > prevWin.timer) {
                val iy = Interpolate.interpolate(
                    Interpolate.RSQUARE,
                    0,
                    win.ymax,
                    win.timer,
                    win.terminator,
                )
                gauge.clr323A(post.quakeX, post.quakeY + iy)
            }
            assertMatches(n, post.gauge!!, gauge)
        }
        val f2008 = s.getValue(2008)
        assertEquals(0, f2008.win!!.cb)
        gauge.setInitFlag()
        assertMatches(2008, f2008.gauge!!, gauge)

        // EndEkrGauge (f2009): ProcScr_ekrGauge is alive at f2008 and gone at f2009.
        val f2009 = s.getValue(2009)
        assertNotNull(f2008.gauge, "f2008 ProcScr_ekrGauge present")
        assertNull(f2009.gauge, "f2009 ProcScr_ekrGauge gone")
        gauge.endEkrGauge()
        assertEquals(false, gauge.active, "f2009 active")
    }

    private companion object {
        const val RUN = "ram_trace_prologue_attack"
        const val EWRAM = 0x02000000
        const val BG0_QUAKE = 0x02000038
        const val GP_PROC_EKR_GAUGE = 0x02000068
        const val FACTION_PAL = 0x0203E114
        const val UNIT_LEFT = 0x0203E188
        const val UNIT_RIGHT = 0x0203E18C
        const val GAUGE_HP = 0x0203E1AC
        const val GAUGE_HP_BAK = 0x0203E1B4
        const val GAUGE_HIT = 0x0203E1B8
        const val GAUGE_DMG = 0x0203E1BC
        const val GAUGE_CRT = 0x0203E1C0
    }
}
