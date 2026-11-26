package fe8.testing

import fe8.game.bm.EkrGauge
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.math.Interpolate
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * Differential verification of [EkrGauge] setup, control state, lifecycle (BA-p9a),
 * `EkrGauge_Setxy323A`, and `ekrGaugeMain` (BA-p9b) against `ram_trace_prologue_attack`
 * (`f1479..f2009`).
 *
 * `NewEkrGauge` creates `ProcScr_ekrGauge` at `f1480` with `gEkrGaugeHpBak == (-1, -1)`;
 * `MainUpdate_8055C68` (`f1480..1496` and `f1990..2008`) runs `TREE_1` (`ekrGaugeMain`) before
 * `TREE_3` (`ekrWindowAppear` / `ekrBattleStarting` / `ekrBattleEnding`), while
 * `InBattleMainRoutine` (`f1497..1989`) runs `TREE_3` (`efxHitQuake` / `efxHPBar` / `ekrBattle`)
 * before `TREE_1`. VBlank spillover / whole-screen no-run steps (`f1481`, `f1484`, `f1485`,
 * `f1582`, `f1752`, `f1789`, `f1997`) are skipped.
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

    private class OamObj(
        val tile: Int,
        val pal: Int,
        val prio: Int,
        val x: Int,
        val y: Int,
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
        val quake2X: Int,
        val quake2Y: Int,
        val weaponBeforeLeft: Int,
        val weaponBeforeRight: Int,
        val gameClock: Int,
        val distanceType: Int,
        val maxHp: IntArray,
        val wtaBonus: IntArray,
        val hitNow: IntArray,
        val banimValid: BooleanArray,
        val bg0FirstGaugeRow: Int?,
        val gOamArrowRot: BooleanArray,
        val oamWatched: Map<Int, OamObj>?,
        val reusedSlot: GaugeState?,
    )

    private var steps: Map<Int, Step>? = null

    @AfterAll
    fun release() {
        steps = null
    }

    private fun u8(e: ByteArray, a: Int, base: Int = EWRAM): Int = e[a - base].toInt() and 0xFF
    private fun u16(e: ByteArray, a: Int, base: Int = EWRAM): Int =
        u8(e, a, base) or (u8(e, a + 1, base) shl 8)
    private fun s16(e: ByteArray, a: Int, base: Int = EWRAM): Int =
        u16(e, a, base).toShort().toInt()
    private fun u32(e: ByteArray, a: Int, base: Int = EWRAM): Int =
        u16(e, a, base) or (u16(e, a + 2, base) shl 16)

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

    private fun readOamObj(oam: ByteArray, index: Int): OamObj {
        val off = index * 8
        val a0 = u16(oam, off, 0)
        val a1 = u16(oam, off + 2, 0)
        val a2 = u16(oam, off + 4, 0)
        return OamObj(
            tile = a2 and 0x3FF,
            pal = (a2 ushr 12) and 0xF,
            prio = (a2 ushr 10) and 3,
            x = a1 and 0x1FF,
            y = a0 and 0xFF,
        )
    }

    private fun load(): Map<Int, Step> {
        steps?.let { return it }
        val ref = ReferenceData.fromEnvironment()
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val capture = ref.run(RUN)
        val byStep = capture.dumps.entries.associate { it.value to it.key }
        val f1480Ewram = capture.region(byStep.getValue(1480), "ewram")
        val gaugeSlotAddr = u32(f1480Ewram, GP_PROC_EKR_GAUGE)
        val out = HashMap<Int, Step>(LAST_STEP - FIRST_STEP + 1)
        for (n in FIRST_STEP..LAST_STEP) {
            val dumpName = byStep.getValue(n)
            val e = capture.region(dumpName, "ewram")
            val iw = capture.region(dumpName, "iwram")
            val all = ProcArrayDump.ofEwram(e, names)
            val gp = all.find("ProcScr_ekrGauge")
            val wp = all.find("ProcScr_ekrWindowAppear")
            val unitLeft = u32(e, UNIT_LEFT)
            val unitRight = u32(e, UNIT_RIGHT)
            val firstRow = (13..20).firstOrNull { row ->
                u16(e, BG0_TILEMAP + row * 64) != 0x80
            }
            val oam = if (n in 1500..1990 && n % 10 == 0) {
                val raw = capture.region(dumpName, "oam")
                WATCHED_OAM_INDICES.associateWith { readOamObj(raw, it) }
            } else {
                null
            }
            val gOamArrowRot = booleanArrayOf(
                (u16(iw, ADDR_G_OAM, IWRAM) and 0x300) != 0,
                (u16(iw, ADDR_G_OAM + 8, IWRAM) and 0x300) != 0,
            )
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
                quake2X = s16(e, BG2_QUAKE),
                quake2Y = s16(e, BG2_QUAKE + 2),
                weaponBeforeLeft = u16(e, unitLeft + 0x4A),
                weaponBeforeRight = u16(e, unitRight + 0x4A),
                gameClock = u32(iw, GAME_CLOCK, IWRAM),
                distanceType = s16(e, DISTANCE_TYPE),
                maxHp = intArrayOf(s16(e, MAX_HP), s16(e, MAX_HP + 2)),
                wtaBonus = intArrayOf(s16(e, WTA_BONUS), s16(e, WTA_BONUS + 2)),
                hitNow = intArrayOf(s16(e, HIT_NOW), s16(e, HIT_NOW + 2)),
                banimValid = booleanArrayOf(s16(e, BANIM_VALID) != 0, s16(e, BANIM_VALID + 2) != 0),
                bg0FirstGaugeRow = firstRow,
                gOamArrowRot = gOamArrowRot,
                oamWatched = oam,
                reusedSlot = if (n == 1479) readGaugeStateRaw(e, gaugeSlotAddr) else null,
            )
        }
        steps = out
        return out
    }

    private fun envView(provider: () -> Step): EkrGauge.Env = object : EkrGauge.Env {
        override val quakeX get() = provider().quakeX
        override val quakeY get() = provider().quakeY
        override val gaugeHp get() = provider().gaugeHp
        override val factionPal get() = provider().factionPal
        override val hit get() = provider().hit
        override val dmg get() = provider().dmg
        override val crt get() = provider().crt
        override val weaponBeforeLeft get() = provider().weaponBeforeLeft
        override val weaponBeforeRight get() = provider().weaponBeforeRight
        override val gameClock get() = provider().gameClock
        override val distanceType get() = provider().distanceType
        override val maxHp get() = provider().maxHp
        override val wtaBonus get() = provider().wtaBonus
        override val hitNow get() = provider().hitNow
        override val banimValid get() = provider().banimValid
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

        val curEnv = f1479
        val env = envView { curEnv }
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

        assertEquals(0, s.getValue(1861).gauge!!.unk44, "f1861 unk44")
        gauge.setup44(1)
        assertEquals(s.getValue(1862).gauge!!.unk44, gauge.unk44, "f1862 unk44")
        assertEquals(0x400, s.getValue(1981).gauge!!.unk44, "f1981 unk44")
        gauge.setup44(0)
        assertEquals(s.getValue(1982).gauge!!.unk44, gauge.unk44, "f1982 unk44")

        val f1992 = s.getValue(1992)
        val f1993 = s.getValue(1993)
        assertMatches(1992, f1992.gauge!!, gauge)
        gauge.clrInitFlag()
        assertEquals(f1993.gauge!!.battleInit, gauge.battleInit, "f1993 clrInitFlag battleInit")
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

        val f2009 = s.getValue(2009)
        assertNotNull(f2008.gauge, "f2008 ProcScr_ekrGauge present")
        assertNull(f2009.gauge, "f2009 ProcScr_ekrGauge gone")
        gauge.endEkrGauge()
        assertEquals(false, gauge.active, "f2009 active")
    }

    private class MainSnapshot(
        val clk: Int,
        val icons: Map<Int, Triple<Int, Int, Int>>,
        val rightArrow: Triple<Int, Int, Int>,
        val rightRot: Boolean,
    )

    @Test
    @VerifiesPort("EkrGauge_Setxy323A", "ekrGaugeMain")
    fun `ekrGaugeMain and setxy323A across prologue attack f1480 to f2008`() {
        val s = load()
        var curEnv = s.getValue(1479)
        var curHitNow = IntArray(2)
        val stepCalls = ArrayList<String>()
        var stepBg0Rows = ArrayList<Int>()
        var stepDigits: List<Int>? = null
        val stepArrowRot = BooleanArray(2)
        val stepDisplayXy = ArrayList<Pair<Int, Int>>()
        var stepClk: Int? = null
        val stepWta = HashMap<Int, Int>()
        val stepIconCoords = HashMap<Int, Triple<Int, Int, Int>>()
        var stepArrowRight: Triple<Int, Int, Int>? = null

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
            override val gameClock get() = curEnv.gameClock
            override val distanceType get() = curEnv.distanceType
            override val maxHp get() = curEnv.maxHp
            override val wtaBonus get() = curEnv.wtaBonus
            override val hitNow get() = curHitNow
            override val banimValid get() = curEnv.banimValid
        }

        val hooks = object : EkrGauge.Hooks {
            override fun fillBgRect(tileOffset: Int, width: Int, height: Int, tile: Int, chr: Int) {
                stepCalls += "fillBgRect($tileOffset,$width,$height,$tile,$chr)"
            }

            override fun copyBgPanelLeft(
                rowOffset: Int,
                colOffset: Int,
                height: Int,
                srcOffset: Int,
            ) {
                stepCalls += "copyBgPanelLeft($rowOffset,$colOffset,$height,$srcOffset)"
                stepBg0Rows += rowOffset / 32
            }

            override fun copyBgPanelRight(
                rowOffset: Int,
                colOffset: Int,
                height: Int,
                srcOffset: Int,
                leftHidden: Boolean,
            ) {
                stepCalls +=
                    "copyBgPanelRight($rowOffset,$colOffset,$height,$srcOffset,$leftHidden)"
                stepBg0Rows += rowOffset / 32
            }

            override fun enableBg0Sync() {
                stepCalls += "enableBg0Sync"
            }

            override fun updateHpDigits(d0: Int, d1: Int, d2: Int, d3: Int) {
                stepCalls += "updateHpDigits($d0,$d1,$d2,$d3)"
                stepDigits = listOf(d0, d1, d2, d3)
            }

            override fun displayArrowAnim(
                side: Int,
                x: Int,
                y: Int,
                oam2Base: Int,
                oamBase: Int,
                rotScale: Boolean,
            ) {
                stepCalls += "displayArrowAnim($side,$x,$y,$oam2Base,$oamBase,$rotScale)"
                stepArrowRot[side] = rotScale
                if (rotScale) {
                    assertEquals(0x200, oamBase, "rotScale oamBase")
                    stepDisplayXy += (x + 8 - if (side == 0) 9 else 0x81) to (y + 8 - 0x91)
                } else {
                    assertEquals(0, oamBase, "normal arrow oamBase")
                    stepDisplayXy += (x - if (side == 0) 9 else 0x81) to (y - 0x91)
                }
                if (side == 1) stepArrowRight = Triple(x, y, oam2Base ushr 10 and 3)
            }

            override fun updateHpBarTiles(side: Int, upperRow: Boolean, barHp: Int, barMax: Int) {
                stepCalls += "updateHpBarTiles($side,$upperRow,$barHp,$barMax)"
            }

            override fun displayHpBar(
                side: Int,
                upperRow: Boolean,
                x: Int,
                y: Int,
                oam2Base: Int,
            ) {
                stepCalls += "displayHpBar($side,$upperRow,$x,$y,$oam2Base)"
                stepDisplayXy += (x - if (side == 0) 0x1D else 0x95) to (y - 0x91)
            }

            override fun registerHpBarDataMove() {
                stepCalls += "registerHpBarDataMove"
            }

            override fun displayStaticPanelSprites(
                side: Int,
                part: Int,
                x: Int,
                y: Int,
                oam2Base: Int,
            ) {
                stepCalls += "displayStaticPanelSprites($side,$part,$x,$y,$oam2Base)"
                val baseX = when {
                    side == 0 && part == 0 -> 0x12
                    side == 0 -> 0x65
                    part == 0 -> 0xD8
                    else -> 0x87
                }
                val baseY = if (part == 0) 0x70 else 0x78
                stepDisplayXy += (x - baseX) to (y - baseY)
            }

            override fun displayWtaArrow(
                side: Int,
                x: Int,
                y: Int,
                oam2Base: Int,
                wtaBonus: Int,
                clk: Int,
            ) {
                stepCalls += "displayWtaArrow($side,$x,$y,$oam2Base,$wtaBonus,$clk)"
                stepDisplayXy += (x - if (side == 0) 0x36 else 0x85) to (y - 0x79)
                stepClk = clk
                stepWta[side] = wtaBonus
            }

            override fun displayWeaponIcon(side: Int, x: Int, y: Int, oam2Base: Int) {
                stepCalls += "displayWeaponIcon($side,$x,$y,$oam2Base)"
                stepDisplayXy += (x - if (side == 0) 0x2C else 0x7B) to (y - 0x79)
                stepIconCoords[side] = Triple(x, y, oam2Base ushr 10 and 3)
            }
        }

        val gauge = EkrGauge(env, hooks)
        val bg0RedrawSteps = ArrayList<Int>()
        val hpChangedSteps = ArrayList<Int>()
        val quakeSteps = ArrayList<Pair<Int, Int>>()
        val rotLeftSteps = ArrayList<Int>()
        val rotRightSteps = ArrayList<Int>()
        val seenClks = HashSet<Int>()
        val seenBlankDigits = HashSet<String>()
        val seenWtaSigns = HashSet<Int>()
        var lastMainSnapshot: MainSnapshot? = null

        for (n in 1480..2008) {
            val step = s.getValue(n)
            val prev = s.getValue(n - 1)
            stepCalls.clear()
            stepBg0Rows = ArrayList()
            stepDigits = null
            stepArrowRot[0] = false
            stepArrowRot[1] = false
            stepDisplayXy.clear()
            stepClk = null
            stepWta.clear()
            stepIconCoords.clear()
            stepArrowRight = null

            var mainRan = false
            var expectedX = 0
            var expectedY = 0
            when (n) {
                1480 -> {
                    curEnv = prev
                    curHitNow = prev.hitNow
                    gauge.newEkrGauge()
                }
                1481 -> Unit // InitBattleScreen VBlank spillover; ekrGaugeMain has not run yet.
                1482 -> {
                    gauge.clr323A(step.quakeX, step.quakeY + step.win!!.ymax)
                    gauge.clrInitFlag()
                }
                in 1483..1496 -> {
                    if (n !in ENTRY_NO_RUN) {
                        val preGauge = prev.gauge!!
                        expectedX = preGauge.unk32
                        expectedY = preGauge.unk3A and 0xFFF8
                        curEnv = step
                        curHitNow = step.hitNow
                        gauge.ekrGaugeMain()
                        mainRan = true
                        if (n == 1496) {
                            gauge.setInitFlag()
                        } else {
                            val win = step.win!!
                            if (win.timer > prev.win!!.timer) {
                                val iy = Interpolate.interpolate(
                                    Interpolate.SQUARE,
                                    win.ymax,
                                    0,
                                    win.timer,
                                    win.terminator,
                                )
                                gauge.clr323A(step.quakeX, step.quakeY + iy)
                            }
                        }
                    }
                }
                in 1497..1989 -> {
                    if (n !in MAIN_NO_RUN) {
                        if (step.quake2X != prev.quake2X || step.quake2Y != prev.quake2Y) {
                            gauge.setxy323A(
                                -(step.quake2X + step.quakeX),
                                -(step.quake2Y + step.quakeY),
                            )
                        }
                        if (n == 1862) gauge.setup44(1)
                        if (n == 1982) gauge.setup44(0)
                        val g = step.gauge!!
                        expectedX = g.unk32
                        expectedY = g.unk3A
                        curEnv = step
                        // NewEfxHpBar (C1a in AnimUpdateAll) runs after ekrGaugeMain, while
                        // EfxHpBar_DeclineToDeath (TREE_3) clears gEkrHitNow before ekrGaugeMain.
                        curHitNow = intArrayOf(
                            prev.hitNow[0] and step.hitNow[0],
                            prev.hitNow[1] and step.hitNow[1],
                        )
                        gauge.ekrGaugeMain()
                        mainRan = true
                    }
                }
                in 1990..2008 -> {
                    if (n !in ENDING_NO_RUN) {
                        val preGauge = prev.gauge!!
                        expectedX = preGauge.unk32
                        expectedY = if (preGauge.battleInit == 0) {
                            preGauge.unk3A and 0xFFF8
                        } else {
                            preGauge.unk3A
                        }
                        curEnv = step
                        curHitNow = step.hitNow
                        gauge.ekrGaugeMain()
                        mainRan = true
                        if (n == 1993) {
                            gauge.clr323A(step.quakeX, step.quakeY)
                            gauge.clrInitFlag()
                        } else if (n == 2008) {
                            gauge.setInitFlag()
                        } else if (n > 1993) {
                            val win = step.win!!
                            if (win.timer > prev.win!!.timer) {
                                val iy = Interpolate.interpolate(
                                    Interpolate.RSQUARE,
                                    0,
                                    win.ymax,
                                    win.timer,
                                    win.terminator,
                                )
                                gauge.clr323A(step.quakeX, step.quakeY + iy)
                            }
                        }
                    }
                }
            }

            assertMatches(n, step.gauge!!, gauge)
            assertEquals(step.hpBak, gauge.hpBak.toList(), "f$n hpBak")

            if (mainRan) {
                val activeBattleInit = if (n in 1497..1989) {
                    step.gauge.battleInit
                } else {
                    prev.gauge!!.battleInit
                }
                if (activeBattleInit == 0) {
                    bg0RedrawSteps += n
                    assertEquals(
                        listOf(step.bg0FirstGaugeRow!!, step.bg0FirstGaugeRow),
                        stepBg0Rows,
                        "f$n bg0 first gauge row",
                    )
                } else {
                    assertTrue(stepBg0Rows.isEmpty(), "f$n unexpected BG0 redraw")
                }

                assertTrue(stepDisplayXy.all { it == (expectedX to expectedY) }, "f$n x/y mismatch")
                if (n in 1496..1984 && expectedY != 0) {
                    quakeSteps += n to expectedY
                    assertEquals(expectedY, expectedX, "f$n quake x==y")
                }

                val expectedHpChanged = step.hpBak != prev.hpBak
                if (expectedHpChanged) {
                    hpChangedSteps += n
                    val hp0 = step.gaugeHp[0]
                    val hp1 = step.gaugeHp[1]
                    val d0 = if (hp0 / 10 == 0) 0xB else hp0 / 10
                    val d2 = if (hp1 / 10 == 0) 0xB else hp1 / 10
                    assertEquals(listOf(d0, hp0 % 10, d2, hp1 % 10), stepDigits, "f$n digits")
                    if (d0 == 0xB) seenBlankDigits += "L:$hp0"
                    if (d2 == 0xB) seenBlankDigits += "R:$hp1"
                } else {
                    assertNull(stepDigits, "f$n unexpected digit update")
                }

                assertEquals(curHitNow[0] == 1, stepArrowRot[0], "f$n hitNow[0]")
                assertEquals(curHitNow[1] == 1, stepArrowRot[1], "f$n hitNow[1]")
                if (n in 1497..1989) {
                    assertEquals(step.gOamArrowRot[0], stepArrowRot[0], "f$n gOam[0] rot")
                    assertEquals(step.gOamArrowRot[1], stepArrowRot[1], "f$n gOam[1] rot")
                }
                if (stepArrowRot[0]) rotLeftSteps += n
                if (stepArrowRot[1]) rotRightSteps += n

                val expectedClk = (step.gameClock / 8) % 3
                assertEquals(expectedClk, stepClk, "f$n clk")
                seenClks += expectedClk
                seenWtaSigns += stepWta.getValue(0)
                seenWtaSigns += stepWta.getValue(1)

                assertStepHookOrder(
                    stepNum = n,
                    calls = stepCalls,
                    expectBg0 = activeBattleInit == 0,
                    expectHpChanged = expectedHpChanged,
                )

                // Hardware OAM at f(N) reflects the shadow buffer pushed during step N-1.
                if (n in 1500..1990 && n % 10 == 0) {
                    val oam = step.oamWatched!!
                    val snap = lastMainSnapshot!!
                    assertEquals(
                        snap.icons.getValue(0),
                        oam.getValue(41).let { Triple(it.x, it.y, it.prio) },
                        "f$n OAM #41 left icon",
                    )
                    assertEquals(
                        snap.icons.getValue(1),
                        oam.getValue(43).let { Triple(it.x, it.y, it.prio) },
                        "f$n OAM #43 right icon",
                    )
                    val (rx, ry, rPrio) = snap.rightArrow
                    val expectedRightOam = Triple(rx, if (snap.rightRot) ry + 4 else ry, rPrio)
                    assertEquals(
                        expectedRightOam,
                        oam.getValue(1).let { Triple(it.x, it.y, it.prio) },
                        "f$n OAM #1 right arrow",
                    )
                    val o40 = oam.getValue(40)
                    val o42 = oam.getValue(42)
                    val expectedWtaTiles = when (snap.clk) {
                        0 -> 0x1CC to 0x1CA
                        else -> 0x1CD to 0x1CB
                    }
                    assertEquals(expectedWtaTiles, o40.tile to o42.tile, "f$n OAM WTA tiles")
                    val leftIconY = snap.icons.getValue(0).second
                    val rightIconY = snap.icons.getValue(1).second
                    assertEquals(
                        if (snap.clk == 2) leftIconY + 1 else leftIconY,
                        o40.y,
                        "f$n OAM #40 y",
                    )
                    assertEquals(
                        if (snap.clk == 2) rightIconY - 1 else rightIconY,
                        o42.y,
                        "f$n OAM #42 y",
                    )
                }

                lastMainSnapshot = MainSnapshot(
                    clk = expectedClk,
                    icons = stepIconCoords.toMap(),
                    rightArrow = stepArrowRight!!,
                    rightRot = stepArrowRot[1],
                )
            }
        }

        // BG0 redraw runs during battleInit == 0: entry f1483..1496 (excl 1484..1485) and ending
        // f1994..2008 (excl 1997). In f1480..1993 specifically, it fires only in f1483..1496.
        assertEquals(
            listOf(1483) + (1486..1496) + (1994..1996) + (1998..2008),
            bg0RedrawSteps,
            "BG0 redraw steps",
        )
        assertEquals(24, hpChangedSteps.size, "hp_changed count (f1483 + 23 drain ticks)")
        assertEquals(1483, hpChangedSteps.first(), "first hp_changed step")
        assertEquals(
            listOf(
                1578 to -3, 1583 to 3, 1586 to -3, 1589 to 3, 1591 to -3, 1593 to 3, 1595 to -3,
                1596 to 3, 1660 to -3, 1664 to 3, 1667 to -3, 1670 to 3, 1672 to -3, 1674 to 3,
                1676 to -3, 1677 to 3, 1785 to -3, 1790 to 3, 1793 to -3, 1796 to 3, 1798 to -3,
                1800 to 3, 1802 to -3, 1803 to 3,
            ),
            quakeSteps,
            "24 quake steps",
        )
        assertEquals(
            (1578..1608).filter { it != 1582 } + (1785..1815).filter { it != 1789 },
            rotLeftSteps,
            "CheckEkrHitNow side 0 windows",
        )
        assertEquals((1660..1689).toList(), rotRightSteps, "CheckEkrHitNow side 1 window")
        assertEquals(setOf(0, 1, 2), seenClks, "all 3 clk values exercised")
        assertEquals(setOf(-15, 15), seenWtaSigns, "both WTA bonus signs exercised")
        assertEquals(setOf("R:9", "R:8", "R:7", "L:9", "L:8"), seenBlankDigits, "leading 0->0xB")
    }

    private fun assertStepHookOrder(
        stepNum: Int,
        calls: List<String>,
        expectBg0: Boolean,
        expectHpChanged: Boolean,
    ) {
        val prefixes = ArrayList<String>()
        if (expectBg0) {
            prefixes += listOf("fillBgRect", "copyBgPanelLeft", "copyBgPanelRight", "enableBg0Sync")
        }
        if (expectHpChanged) {
            prefixes += "updateHpDigits"
        }
        prefixes += listOf("displayArrowAnim", "displayArrowAnim")
        if (expectHpChanged) prefixes += "updateHpBarTiles"
        prefixes += "displayHpBar"
        if (expectHpChanged) prefixes += "updateHpBarTiles"
        prefixes += "displayHpBar"
        if (expectHpChanged) prefixes += "registerHpBarDataMove"
        prefixes += listOf(
            "displayStaticPanelSprites",
            "displayStaticPanelSprites",
            "displayStaticPanelSprites",
            "displayStaticPanelSprites",
            "displayWtaArrow",
            "displayWeaponIcon",
            "displayWtaArrow",
            "displayWeaponIcon",
        )
        assertEquals(prefixes, calls.map { it.substringBefore('(') }, "f$stepNum hook order")
    }

    private companion object {
        const val RUN = "ram_trace_prologue_attack"
        const val FIRST_STEP = 1479
        const val LAST_STEP = 2009
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val GAME_CLOCK = 0x03000014
        const val ADDR_G_OAM = 0x03003140
        const val BG0_QUAKE = 0x02000038
        const val GP_PROC_EKR_GAUGE = 0x02000068
        const val BG2_QUAKE = 0x02017760
        const val HIT_NOW = 0x02017780
        const val BG0_TILEMAP = 0x02022CA8
        const val BANIM_VALID = 0x0203E104
        const val FACTION_PAL = 0x0203E114
        const val DISTANCE_TYPE = 0x0203E120
        const val UNIT_LEFT = 0x0203E188
        const val UNIT_RIGHT = 0x0203E18C
        const val GAUGE_HP = 0x0203E1AC
        const val MAX_HP = 0x0203E1B0
        const val GAUGE_HP_BAK = 0x0203E1B4
        const val GAUGE_HIT = 0x0203E1B8
        const val GAUGE_DMG = 0x0203E1BC
        const val GAUGE_CRT = 0x0203E1C0
        const val WTA_BONUS = 0x0203E1D4

        val ENTRY_NO_RUN = setOf(1484, 1485)
        val MAIN_NO_RUN = setOf(1582, 1752, 1789)
        val ENDING_NO_RUN = setOf(1997)
        val WATCHED_OAM_INDICES = listOf(1, 40, 41, 42, 43)
    }
}
