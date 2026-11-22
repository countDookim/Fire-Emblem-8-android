package fe8.testing

import gba.testkit.ReferenceData
import gba.testkit.WatchTrace
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Inventory (not a port claim, PC-6i-load) of Chapter 1 opening map changes, initial unit loads,
 * and movement ONLY in `ram_trace_prologue_end` (`PC-cap1`, `f10298..f25580`), plus the turn-1
 * player-phase handoff (`f25421..f25580`). Dialogue, text, cursor/camera, fade, music, and
 * scripted-battle internals belong to `PC-6i-event` / `M6-G`.
 *
 * Proc rows reuse [EnemyPhaseInventoryTest.rows] over the `procs` watch (1-based steps). Unit,
 * map, RNG, and `ProcScr_MuCtrl` / `ProcScr_Mu` findings and proposed `<= 8`-function PORT rows
 * are recorded in `docs/plans/M6C-J-breakdown.md` ("PC-6i-load inventory").
 */
class Chapter1LoadInventoryTest {
    private val inventory = EnemyPhaseInventoryTest()
    private val ref = ReferenceData.fromEnvironment()

    private fun check(expected: List<String>, first: Int, last: Int) {
        assertEquals(
            expected.sorted(),
            inventory.rows(RUN, first, last).sorted(),
            "$RUN f$first..f$last",
        )
    }

    private fun openRun(): ReferenceData.Run {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run(RUN)
        assumeTrue(run.hasWatch("procs"), "$RUN has no procs watch")
        return run
    }

    @Test
    fun `chapter 1 load move kill and turn 1 handoff windows match the procs watch`() {
        check(WIN1_ENEMY_LOAD1_ROWS, 10298, 10378)
        check(WIN2_NPC_LOAD1_AND_DISA_ROWS, 13049, 13103)
        check(WIN3A_BREGUET_MOVE_ROWS, 15049, 15084)
        check(WIN3B_KILL_AND_DISA_IF_ROWS, 15526, 15558)
        check(WIN4_ENEMY_MOVE7_ROWS, 16019, 16153)
        check(WIN5_ALLY_LOAD2_ROWS, 20249, 20268)
        check(WIN6_TURN1_HANDOFF_ROWS, 25421, 25580)

        val run = openRun()
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val procs = run.watch("procs")
        val playSt = run.watch("gplayst")
        val flags = run.watch("flags")
        fun dump(step: Int) = ProcArrayDump.ofWatch(procs, step, names)
        fun ev(step: Int) = dump(step).find("ProcScr_StdEventEngine")!!

        // Pre-event transition f10297 -> f10298 starts Chapter 1 BeginningScene.
        assertEquals(8, dump(10297).find("gProc_BMapMain")!!.scrCur)
        assertNull(dump(10297).find("ProcScr_StdEventEngine"))
        assertEquals(12, dump(10298).find("gProc_BMapMain")!!.scrCur)
        val ev10298 = ev(10298)
        assertEquals(EVENT_CH1_BEGINNING + 0x004, ev10298.u32(EV_CURRENT))
        assertEquals(0, ev10298.u32(EV_CALLBACK))
        assertEquals(0x0002, ev10298.u16(EV_STATE_BITS))

        // Enemy LOAD1 (0x089EF84C) throttles at 4 MU slots using pCallback, then waits in ENUN.
        for (step in listOf(10299, 10300)) {
            assertEquals(CB_LOAD_UNIT_WITH_MOVEMENT, ev(step).u32(EV_CALLBACK))
            assertEquals(EVENT_CH1_BEGINNING + 0x004, ev(step).u32(EV_CURRENT))
        }
        for (step in listOf(10301, 10346, 10347, 10351, 10352, 10357)) {
            assertEquals(CB_LOAD_UNIT_WITH_MOVEMENT, ev(step).u32(EV_CALLBACK))
            assertEquals(EVENT_CH1_BEGINNING + 0x00C, ev(step).u32(EV_CURRENT))
        }
        assertEquals(0, ev(10358).u32(EV_CALLBACK))
        assertEquals(EVENT_CH1_BEGINNING + 0x00C, ev(10358).u32(EV_CURRENT))
        assertEquals(EVENT_CH1_BEGINNING + 0x010, ev(10378).u32(EV_CURRENT))

        // NPC LOAD1 (+0x038), ENUN (+0x040), SVAL B (+0x044), DISA (-2) (+0x04C -> +0x054).
        assertEquals(CB_LOAD_UNIT_WITH_MOVEMENT, ev(13049).u32(EV_CALLBACK))
        assertEquals(EVENT_CH1_BEGINNING + 0x038, ev(13049).u32(EV_CURRENT))
        assertEquals(0, ev(13050).u32(EV_CALLBACK))
        assertEquals(EVENT_CH1_BEGINNING + 0x040, ev(13050).u32(EV_CURRENT))
        assertEquals(EVENT_CH1_BEGINNING + 0x044, ev(13102).u32(EV_CURRENT))
        assertEquals(EVENT_CH1_BEGINNING + 0x054, ev(13103).u32(EV_CURRENT))

        // Breguet MOVE to (2,3) (+0x07C -> +0x080) and scripted KILL/DISA_IF (+0x0C8 -> +0x0D0).
        assertEquals(EVENT_CH1_BEGINNING + 0x07C, ev(15049).u32(EV_CURRENT))
        assertEquals(EVENT_CH1_BEGINNING + 0x080, ev(15084).u32(EV_CURRENT))
        assertEquals(EVENT_CH1_BEGINNING + 0x0C8, ev(15526).u32(EV_CURRENT))
        assertEquals(EVENT_CH1_BEGINNING + 0x0D0, ev(15558).u32(EV_CURRENT))

        // Enemy 7x MOVE (+0x0F4..+0x154) and ENUN (+0x15C -> +0x160), throttled at 4 MU slots.
        assertEquals(EVENT_CH1_BEGINNING + 0x104, ev(16019).u32(EV_CURRENT))
        assertEquals(EVENT_CH1_BEGINNING + 0x124, ev(16020).u32(EV_CURRENT))
        assertEquals(EVENT_CH1_BEGINNING + 0x134, ev(16021).u32(EV_CURRENT))
        assertEquals(EVENT_CH1_BEGINNING + 0x144, ev(16040).u32(EV_CURRENT))
        assertEquals(EVENT_CH1_BEGINNING + 0x154, ev(16057).u32(EV_CURRENT))
        assertEquals(EVENT_CH1_BEGINNING + 0x15C, ev(16144).u32(EV_CURRENT))
        assertEquals(EVENT_CH1_BEGINNING + 0x160, ev(16153).u32(EV_CURRENT))

        // Ally LOAD2 (+0x18C -> +0x194 -> +0x198).
        assertEquals(CB_LOAD_UNIT_WITH_MOVEMENT, ev(20249).u32(EV_CALLBACK))
        assertEquals(EVENT_CH1_BEGINNING + 0x18C, ev(20249).u32(EV_CURRENT))
        assertEquals(EVENT_CH1_BEGINNING + 0x194, ev(20250).u32(EV_CURRENT))
        assertEquals(EVENT_CH1_BEGINNING + 0x198, ev(20268).u32(EV_CURRENT))

        // End of BeginningScene -> UndeployEveryone/flag 0x84 clear -> Turn 1 player phase idle.
        assertNull(dump(25422).find("ProcScr_StdEventEngine"))
        assertEquals(12, dump(25422).find("gProc_BMapMain")!!.scrCur)
        assertEquals(0x80, flags.u8(25422, 3))
        assertEquals(0x40, playSt.u8(25422, PLAYST_FACTION))
        assertEquals(0, playSt.u16(25422, PLAYST_TURN))

        assertEquals(17, dump(25423).find("gProc_BMapMain")!!.scrCur)
        assertEquals(0x00, flags.u8(25423, 3))
        assertEquals(0x00, playSt.u8(25423, PLAYST_FACTION))
        assertEquals(1, playSt.u16(25423, PLAYST_TURN))
        assertEquals(EVENT_CH1_TURN1_PLAYER, ev(25423).u32(EV_START))
        assertNotNull(dump(25429).find("ProcScr_PhaseIntro"))
        assertNotNull(dump(25507).find("gProcScr_PlayerPhase"))
    }

    @Test
    fun `blue red and green unit load move bytes and RNG draw frames match PC cap1`() {
        val run = openRun()
        val blue = run.watch("blue")
        val red = run.watch("red")
        val rng = run.watch("rng")

        fun rngWords(step: Int) = listOf(rng.u16(step, 0), rng.u16(step, 2), rng.u16(step, 4))
        // The generator state only changes on frames that draw (autolevel, BestMove tie-breaks).
        fun rngDrew(step: Int) = rngWords(step) != rngWords(step - 1)
        fun ewram(step: Int): ByteArray = run.region(ewramTag(step), "ewram")

        // Pre-event UpdatePrevDeployStates / ResetAllPlayerUnitState moves blue to (-1, y).
        assertUnit(blue, 10297, 0, SETH_CHAR, PALADIN_CLASS, 8, 6, 1)
        assertUnit(blue, 10297, 1, EIRIKA_CHAR, LORD_CLASS, 4, 5, 1)
        assertUnit(blue, 10298, 0, SETH_CHAR, PALADIN_CLASS, -1, 6, 1)
        assertUnit(blue, 10298, 1, EIRIKA_CHAR, LORD_CLASS, -1, 5, 1)

        // Enemy LOAD1 throttles across f10299..f10358 with exact 14-draw autolevel bursts.
        assertEquals(0, activeUnitCount(red, 10298, 50))
        assertEquals(2, activeUnitCount(red, 10299, 50))
        assertEquals(4, activeUnitCount(red, 10300, 50))
        assertEquals(4, activeUnitCount(red, 10346, 50))
        assertEquals(5, activeUnitCount(red, 10347, 50))
        assertEquals(5, activeUnitCount(red, 10351, 50))
        assertEquals(6, activeUnitCount(red, 10352, 50))
        assertEquals(6, activeUnitCount(red, 10357, 50))
        assertEquals(7, activeUnitCount(red, 10358, 50))

        for (step in listOf(10299, 10300, 10347, 10352, 10358)) assertTrue(rngDrew(step), "f$step")
        for (step in listOf(10301, 10346, 10351, 10357)) assertFalse(rngDrew(step), "f$step")

        assertRedPostLoad(red, 10378)

        // NPC LOAD1, DISA, FIGHT death, KILL/DISA_IF verified in EWRAM gUnitArrayGreen.
        assertEquals(0, activeUnitCountInEwram(ewram(10380), ADDR_GREEN, 20))
        val ew13050 = ewram(13050)
        assertEquals(2, activeUnitCountInEwram(ew13050, ADDR_GREEN, 20))
        assertUnitInBytes(ew13050, GREEN_OFF, GREEN_CAV_CHAR, CAVALIER_CLASS, 2, 2, 1)
        assertUnitInBytes(ew13050, GREEN_OFF + UNIT_SIZE, FRELIAN_CHAR, MERCENARY_CLASS, 2, 2, 1)

        val ew13100 = ewram(13100)
        assertUnitInBytes(ew13100, GREEN_OFF, GREEN_CAV_CHAR, CAVALIER_CLASS, 0, 3, 1)
        assertUnitInBytes(ew13100, GREEN_OFF + UNIT_SIZE, FRELIAN_CHAR, MERCENARY_CLASS, 2, 2, 0)

        // DISA clears green[0]; green[1] stays in slot 1, loses HP to 0 in FIGHT, then cleared.
        val ew13110 = ewram(13110)
        assertEquals(0, unitCharPtr(ew13110, GREEN_OFF))
        assertUnitInBytes(ew13110, GREEN_OFF + UNIT_SIZE, FRELIAN_CHAR, MERCENARY_CLASS, 2, 2, 0)
        // KILL: curHp reaches 0 while maxHp stays (FIGHT), then the slot is cleared.
        assertEquals(0, ewram(15510)[GREEN_OFF + UNIT_SIZE + UNIT_CUR_HP].toInt() and 0xFF)
        assertTrue(ewram(15510)[GREEN_OFF + UNIT_SIZE + UNIT_MAX_HP].toInt() != 0)
        assertEquals(1, u32(ewram(15530), GREEN_OFF + UNIT_SIZE + UNIT_STATE))
        assertEquals(0, activeUnitCountInEwram(ewram(15560), ADDR_GREEN, 20))
        assertEquals(0, activeUnitCountInEwram(ewram(25580), ADDR_GREEN, 20))

        // Breguet MOVE to (2,3) and subsequent 7x MOVE + RNG path tie-breaks.
        assertUnit(red, 15084, 0, BREGUET_CHAR, ARMOR_KNIGHT_CLASS, 2, 3, 0)
        assertFalse(rngDrew(16021))
        assertTrue(rngDrew(16022)) // BestMove tie-break draws
        assertFalse(rngDrew(16039))
        assertTrue(rngDrew(16040))
        assertRedPostMove(red, 16155)
        assertRedPostMove(red, 25580)

        // Ally LOAD2 at f20249, path NextRN at f20250, settled at f20267 and f25580.
        assertEquals(2, activeUnitCount(blue, 20248, 2))
        assertEquals(-1, s8(blue, 20248, UNIT_X))
        assertEquals(14, s8(blue, 20249, UNIT_X))
        assertEquals(9, s8(blue, 20249, UNIT_Y))
        assertEquals(14, s8(blue, 20249, UNIT_SIZE + UNIT_X))
        assertEquals(9, s8(blue, 20249, UNIT_SIZE + UNIT_Y))
        assertFalse(rngDrew(20249))
        assertTrue(rngDrew(20250)) // BestMove tie-break draw

        for (step in listOf(20267, 25580)) {
            assertUnit(blue, step, 0, SETH_CHAR, PALADIN_CLASS, 13, 8, 0)
            assertUnit(blue, step, 1, EIRIKA_CHAR, LORD_CLASS, 12, 9, 0)
        }
    }

    @Test
    fun `terrain hidden trap pool unit and other maps match ch1 boundaries`() {
        val run = openRun()
        val baseEwram = run.region(ewramTag(10297), "ewram")
        assertEquals(15, u16(baseEwram, ADDR_MAP_SIZE - EWRAM_BASE))
        assertEquals(10, u16(baseEwram, ADDR_MAP_SIZE + 2 - EWRAM_BASE))
        val baseTerrain = readMapBytes(baseEwram, ADDR_MAP_TERRAIN, 15, 10)

        val allSteps = listOf(
            10297, 10298, 10299, 10300, 10301, 10346, 10350, 10360, 10378, 10380,
            13050, 13100, 13110, 15050, 15080, 15090, 15510, 15560,
            16020, 16030, 16040, 16050, 16060, 16150, 16160,
            20250, 20270, 25426, 25507, 25510, 25580,
        )
        for (step in allSteps) {
            val ew = run.region(ewramTag(step), "ewram")
            assertEquals(15, u16(ew, ADDR_MAP_SIZE - EWRAM_BASE), "width f$step")
            assertEquals(10, u16(ew, ADDR_MAP_SIZE + 2 - EWRAM_BASE), "height f$step")
            assertArrayEquals(
                baseTerrain,
                readMapBytes(ew, ADDR_MAP_TERRAIN, 15, 10),
                "gBmMapTerrain unchanged at f$step",
            )
            assertTrue(
                readMapBytes(ew, ADDR_MAP_HIDDEN, 15, 10).all { it.toInt() == 0 },
                "gBmMapHidden zero at f$step",
            )
            val trapOff = ADDR_TRAP_POOL - EWRAM_BASE
            assertTrue(
                (0 until TRAP_POOL_BYTES).all { ew[trapOff + it].toInt() == 0 },
                "sTrapPool zero at f$step",
            )
        }

        fun checkMaps(step: Int, expectedUnit: List<TileVal>, expectedOther: List<TileVal>) {
            val ew = run.region(ewramTag(step), "ewram")
            assertEquals(expectedUnit, nonZeroMap(ew, ADDR_MAP_UNIT, 15, 10), "unit f$step")
            assertEquals(expectedOther, nonZeroMap(ew, ADDR_MAP_OTHER, 15, 10), "other f$step")
        }

        // Before LOAD1: chapter-intro left stale values in gBmMapOther; LOAD1 fills it to 0.
        val ew10298 = run.region("r10298", "ewram")
        assertEquals(emptyList<TileVal>(), nonZeroMap(ew10298, ADDR_MAP_UNIT, 15, 10))
        assertEquals(149, nonZeroMap(ew10298, ADDR_MAP_OTHER, 15, 10).size)

        checkMaps(10299, emptyList(), listOf(TileVal(2, 5, 70)))
        checkMaps(
            10300,
            emptyList(),
            listOf(TileVal(2, 5, 70), TileVal(1, 6, 128), TileVal(3, 6, 128)),
        )
        checkMaps(
            10301,
            emptyList(),
            listOf(TileVal(2, 5, 70), TileVal(1, 6, 128), TileVal(3, 6, 128), TileVal(2, 7, 128)),
        )
        checkMaps(
            10346,
            listOf(TileVal(2, 5, 129)),
            listOf(TileVal(2, 5, 70), TileVal(1, 6, 128), TileVal(3, 6, 128), TileVal(2, 7, 128)),
        )
        checkMaps(
            10360,
            listOf(TileVal(2, 5, 129), TileVal(1, 6, 130), TileVal(2, 7, 132), TileVal(2, 9, 135)),
            listOf(
                TileVal(2, 5, 70), TileVal(1, 6, 128), TileVal(3, 6, 128),
                TileVal(2, 7, 128), TileVal(1, 8, 128), TileVal(3, 8, 128), TileVal(2, 9, 128),
            ),
        )
        val enemiesLoaded = listOf(
            TileVal(2, 5, 129), TileVal(1, 6, 130), TileVal(3, 6, 131), TileVal(2, 7, 132),
            TileVal(1, 8, 133), TileVal(3, 8, 134), TileVal(2, 9, 135),
        )
        checkMaps(10378, enemiesLoaded, emptyList())
        checkMaps(13050, enemiesLoaded, listOf(TileVal(0, 0, 193), TileVal(2, 2, 192)))
        val withMerc = (listOf(TileVal(2, 2, 66)) + enemiesLoaded).sortedWith(TILE_ORDER)
        checkMaps(13100, withMerc, listOf(TileVal(0, 0, 193), TileVal(2, 2, 192)))
        checkMaps(13110, withMerc, emptyList())

        // Breguet MOVE and KILL/DISA_IF.
        val withoutBreguet = withMerc.filterNot { it.id == 129 }
        checkMaps(15050, withoutBreguet, listOf(TileVal(2, 3, 70)))
        val withBreguetAt23 = (listOf(TileVal(2, 2, 66), TileVal(2, 3, 129)) +
            enemiesLoaded.filterNot { it.id == 129 }).sortedWith(TILE_ORDER)
        checkMaps(15510, withBreguetAt23, emptyList())
        val enemiesAt23 = (listOf(TileVal(2, 3, 129)) +
            enemiesLoaded.filterNot { it.id == 129 }).sortedWith(TILE_ORDER)
        checkMaps(15560, enemiesAt23, emptyList())

        // 7x MOVE: Event2F clears gBmMapOther on entry; MuCtr_ExecREDA writes it on Tree 5.
        checkMaps(
            16020,
            listOf(TileVal(2, 7, 132), TileVal(1, 8, 133), TileVal(3, 8, 134), TileVal(2, 9, 135)),
            emptyList(),
        )
        checkMaps(
            16030,
            listOf(TileVal(2, 7, 132), TileVal(3, 8, 134), TileVal(2, 9, 135)),
            emptyList(),
        )
        checkMaps(
            16040,
            listOf(TileVal(2, 2, 129), TileVal(3, 8, 134), TileVal(2, 9, 135)),
            listOf(TileVal(8, 3, 128)),
        )
        checkMaps(
            16050,
            listOf(TileVal(2, 2, 129), TileVal(3, 8, 134), TileVal(2, 9, 135)),
            emptyList(),
        )
        checkMaps(
            16060,
            listOf(TileVal(2, 2, 129), TileVal(1, 3, 130), TileVal(3, 3, 131)),
            listOf(TileVal(4, 7, 128), TileVal(2, 8, 128)),
        )
        val enemiesMoved = listOf(
            TileVal(2, 2, 129), TileVal(1, 3, 130), TileVal(3, 3, 131), TileVal(8, 3, 132),
            TileVal(9, 5, 133), TileVal(4, 7, 134), TileVal(2, 8, 135),
        )
        checkMaps(16160, enemiesMoved, emptyList())

        // Ally LOAD2 reservations and settled gBmMapUnit through turn 1 handoff f25580.
        checkMaps(20250, enemiesMoved, listOf(TileVal(13, 8, 2), TileVal(12, 9, 1)))
        val allSettled = (enemiesMoved + listOf(TileVal(13, 8, 1), TileVal(12, 9, 2)))
            .sortedWith(TILE_ORDER)
        for (step in listOf(20270, 25426, 25507, 25510, 25580)) {
            checkMaps(step, allSettled, emptyList())
        }
    }

    @Test
    fun `MuCtrl and Mu states track delays multi leg REDAs dependencies and speeds`() {
        val run = openRun()
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val procs = run.watch("procs")
        val mcScript = names.entries.first { it.value == "ProcScr_MuCtrl" }.key
        val muScript = names.entries.first { it.value == "ProcScr_Mu" }.key
        fun dump(step: Int) = ProcArrayDump.ofWatch(procs, step, names)
        fun mc(step: Int, unitAddr: Int) =
            dump(step).findAll(mcScript).singleOrNull { it.u32(MU_UNIT) == unitAddr }
        fun mu(step: Int, unitAddr: Int) =
            dump(step).findAll(muScript).singleOrNull { it.u32(MU_UNIT) == unitAddr }

        // Enemy LOAD1: staggered delayFrames (16, 16, 32), 2-leg timer 0->1, and same-tile no-op.
        assertEquals(15, mc(10301, ADDR_RED + UNIT_SIZE)!!.s16(MC_DELAY))
        assertEquals(15, mc(10301, ADDR_RED + 2 * UNIT_SIZE)!!.s16(MC_DELAY))
        assertEquals(31, mc(10301, ADDR_RED + 3 * UNIT_SIZE)!!.s16(MC_DELAY))
        assertEquals(0x18, mu(10301, ADDR_RED)!!.u16(MU_CONFIG))

        val leg2Before = mc(10338, ADDR_RED + 2 * UNIT_SIZE)!!
        assertEquals(2, leg2Before.u16(MC_REDA_CNT))
        assertEquals(0, leg2Before.u16(MC_TIMER))
        assertEquals(3, leg2Before.s8(MC_DST_X))
        assertEquals(8, leg2Before.s8(MC_DST_Y))
        val leg2After = mc(10339, ADDR_RED + 2 * UNIT_SIZE)!!
        assertEquals(1, leg2After.u16(MC_TIMER))
        assertEquals(3, leg2After.s8(MC_DST_X))
        assertEquals(6, leg2After.s8(MC_DST_Y))

        val sameTile = mc(10358, ADDR_RED + 6 * UNIT_SIZE)!!
        assertEquals(1, sameTile.u16(MC_TIMER))
        assertEquals(2, sameTile.s8(MC_DST_X))
        assertEquals(9, sameTile.s8(MC_DST_Y))
        assertNull(mc(10359, ADDR_RED + 6 * UNIT_SIZE))
        assertNull(mu(10359, ADDR_RED + 6 * UNIT_SIZE))

        // NPC LOAD1: green[1] waits on green[0] (a=0xC1, b=0) until leg 1 at f13059; 2.0 px/f.
        val cavStart = mc(13050, ADDR_GREEN)!!
        assertEquals(3, cavStart.u16(MC_REDA_CNT))
        assertEquals(0, cavStart.u16(MC_TIMER))
        assertEquals(544, mu(13050, ADDR_GREEN)!!.s16(MU_YQ4)) // 512 + 32 (down 2.0 px/f)
        val mercWait = mu(13058, ADDR_GREEN + UNIT_SIZE)!!
        assertEquals(1, mercWait.u8(MU_STATE))
        assertEquals(1, mc(13059, ADDR_GREEN)!!.u16(MC_TIMER))
        assertNull(mc(13059, ADDR_GREEN + UNIT_SIZE))
        assertEquals(2, mc(13076, ADDR_GREEN)!!.u16(MC_TIMER))
        assertNull(mc(13101, ADDR_GREEN))

        // Breguet MOVE (speed 0 -> 1.0 px/f = 16 Q4/f) and KILL death fade (state 7).
        assertEquals(1264, mu(15050, ADDR_RED)!!.s16(MU_YQ4))
        assertEquals(1248, mu(15051, ADDR_RED)!!.s16(MU_YQ4))
        assertEquals(768, mu(15082, ADDR_RED)!!.s16(MU_YQ4))
        assertNull(mc(15083, ADDR_RED))
        val dyingMu = mu(15526, ADDR_GREEN + UNIT_SIZE)!!
        assertEquals(7, dyingMu.u8(MU_STATE))
        assertNotNull(dump(15526).find("ProcScr_MuDeathFade"))
        assertNull(mu(15557, ADDR_GREEN + UNIT_SIZE))

        // Enemy 7x MOVE (speed 24 -> 1.5 px/f = 24 Q4/f vs Breguet speed 0 -> 16 Q4/f).
        assertEquals(768, mu(16019, ADDR_RED)!!.s16(MU_YQ4))
        assertEquals(752, mu(16021, ADDR_RED)!!.s16(MU_YQ4))
        assertEquals(256, mu(16021, ADDR_RED + 4 * UNIT_SIZE)!!.s16(MU_XQ4))
        assertEquals(280, mu(16022, ADDR_RED + 4 * UNIT_SIZE)!!.s16(MU_XQ4))
        assertNull(mu(16039, ADDR_RED))
        assertNull(mu(16152, ADDR_RED + 3 * UNIT_SIZE))

        // Ally LOAD2 (speed default 2.0 px/f = 32 Q4/f).
        assertEquals(3552, mu(20250, ADDR_BLUE)!!.s16(MU_XQ4))
        assertEquals(3520, mu(20251, ADDR_BLUE)!!.s16(MU_XQ4))
        assertEquals(3552, mu(20250, ADDR_BLUE + UNIT_SIZE)!!.s16(MU_XQ4))
        assertEquals(3520, mu(20251, ADDR_BLUE + UNIT_SIZE)!!.s16(MU_XQ4))
        assertNull(mu(20267, ADDR_BLUE))
        assertNull(mu(20267, ADDR_BLUE + UNIT_SIZE))
    }

    private fun assertRedPostLoad(red: WatchTrace, step: Int) {
        assertUnit(red, step, 0, BREGUET_CHAR, ARMOR_KNIGHT_CLASS, 2, 5, 0)
        assertUnit(red, step, 1, GENERIC_CHAR, SOLDIER_CLASS, 1, 6, 0)
        assertUnit(red, step, 2, GENERIC_CHAR, SOLDIER_CLASS, 3, 6, 0)
        assertUnit(red, step, 3, GENERIC_CHAR, SOLDIER_CLASS, 2, 7, 0)
        assertUnit(red, step, 4, GENERIC_CHAR, FIGHTER_CLASS, 1, 8, 0)
        assertUnit(red, step, 5, GENERIC_CHAR, FIGHTER_CLASS, 3, 8, 0)
        assertUnit(red, step, 6, GENERIC_CHAR, FIGHTER_CLASS, 2, 9, 0)
    }

    private fun assertRedPostMove(red: WatchTrace, step: Int) {
        assertUnit(red, step, 0, BREGUET_CHAR, ARMOR_KNIGHT_CLASS, 2, 2, 0)
        assertUnit(red, step, 1, GENERIC_CHAR, SOLDIER_CLASS, 1, 3, 0)
        assertUnit(red, step, 2, GENERIC_CHAR, SOLDIER_CLASS, 3, 3, 0)
        assertUnit(red, step, 3, GENERIC_CHAR, SOLDIER_CLASS, 8, 3, 0)
        assertUnit(red, step, 4, GENERIC_CHAR, FIGHTER_CLASS, 9, 5, 0)
        assertUnit(red, step, 5, GENERIC_CHAR, FIGHTER_CLASS, 4, 7, 0)
        assertUnit(red, step, 6, GENERIC_CHAR, FIGHTER_CLASS, 2, 8, 0)
    }

    private fun assertUnit(
        trace: WatchTrace,
        step: Int,
        slot: Int,
        charPtr: Int,
        classPtr: Int,
        x: Int,
        y: Int,
        state: Int,
    ) = assertUnitInBytes(
        trace.step(step), slot * UNIT_SIZE, charPtr, classPtr, x, y, state, "slot=$slot f$step",
    )

    private fun assertUnitInBytes(
        bytes: ByteArray,
        off: Int,
        charPtr: Int,
        classPtr: Int,
        x: Int,
        y: Int,
        state: Int,
        ctx: String = "off=0x${off.toString(16)}",
    ) {
        assertEquals(charPtr, u32(bytes, off + UNIT_PCHAR), "$ctx pChar")
        assertEquals(classPtr, u32(bytes, off + UNIT_PCLASS), "$ctx pClass")
        assertEquals(x, bytes[off + UNIT_X].toInt(), "$ctx x")
        assertEquals(y, bytes[off + UNIT_Y].toInt(), "$ctx y")
        assertEquals(state, u32(bytes, off + UNIT_STATE), "$ctx state")
    }

    private fun readMapBytes(ewram: ByteArray, ptrAddr: Int, w: Int, h: Int): ByteArray {
        val ptr = u32(ewram, ptrAddr - EWRAM_BASE)
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val rowOff = u32(ewram, ptr + 4 * y - EWRAM_BASE) - EWRAM_BASE
            System.arraycopy(ewram, rowOff, out, y * w, w)
        }
        return out
    }

    private fun nonZeroMap(ewram: ByteArray, ptrAddr: Int, w: Int, h: Int): List<TileVal> {
        val bytes = readMapBytes(ewram, ptrAddr, w, h)
        val res = ArrayList<TileVal>()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val v = bytes[y * w + x].toInt() and 0xFF
                if (v != 0) res += TileVal(x, y, v)
            }
        }
        return res
    }

    private fun activeUnitCount(trace: WatchTrace, step: Int, maxSlots: Int): Int =
        activeUnitCountInEwram(trace.step(step), EWRAM_BASE, maxSlots)

    private fun activeUnitCountInEwram(
        bytes: ByteArray,
        addr: Int,
        maxSlots: Int,
    ): Int {
        val base = addr - EWRAM_BASE
        return (0 until maxSlots).count { u32(bytes, base + it * UNIT_SIZE) != 0 }
    }

    private fun unitCharPtr(ewram: ByteArray, off: Int): Int = u32(ewram, off + UNIT_PCHAR)

    private fun s8(trace: WatchTrace, step: Int, off: Int): Int = trace.step(step)[off].toInt()

    private fun u16(bytes: ByteArray, off: Int): Int =
        (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(bytes: ByteArray, off: Int): Int =
        u16(bytes, off) or (u16(bytes, off + 2) shl 16)

    private fun ProcArrayDump.Proc.s8(field: Int): Int = u8(field).toByte().toInt()
    private fun ProcArrayDump.Proc.s16(field: Int): Int = u16(field).toShort().toInt()

    private fun ewramTag(step: Int): String =
        if (step in F_EWRAM_STEPS) "f%05d".format(step) else "r%05d".format(step)

    private data class TileVal(val x: Int, val y: Int, val id: Int)

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val EWRAM_BASE = 0x02000000

        const val ADDR_BLUE = 0x0202BE4C
        const val ADDR_RED = 0x0202CFBC
        const val ADDR_GREEN = 0x0202DDCC
        const val GREEN_OFF = ADDR_GREEN - EWRAM_BASE

        const val ADDR_MAP_SIZE = 0x0202E4D4
        const val ADDR_MAP_UNIT = 0x0202E4D8
        const val ADDR_MAP_TERRAIN = 0x0202E4DC
        const val ADDR_MAP_HIDDEN = 0x0202E4EC
        const val ADDR_MAP_OTHER = 0x0202E4F0
        const val ADDR_TRAP_POOL = 0x0203A614
        const val TRAP_POOL_BYTES = 0x200

        const val EVENT_CH1_BEGINNING = 0x089EF848
        const val EVENT_CH1_TURN1_PLAYER = 0x089EFA28
        const val CB_LOAD_UNIT_WITH_MOVEMENT = 0x0800F5B9

        const val EV_CALLBACK = 0x2C
        const val EV_START = 0x30
        const val EV_CURRENT = 0x38
        const val EV_STATE_BITS = 0x3C

        const val PLAYST_FACTION = 0x0F
        const val PLAYST_TURN = 0x10

        const val UNIT_SIZE = 0x48
        const val UNIT_PCHAR = 0x00
        const val UNIT_PCLASS = 0x04
        const val UNIT_STATE = 0x0C
        const val UNIT_X = 0x10
        const val UNIT_Y = 0x11
        const val UNIT_MAX_HP = 0x12
        const val UNIT_CUR_HP = 0x13

        const val CHAR_DATA = 0x08803D64
        const val CLASS_DATA = 0x08807164
        const val EIRIKA_CHAR = CHAR_DATA
        const val SETH_CHAR = CHAR_DATA + 0x34
        const val BREGUET_CHAR = CHAR_DATA + 0xE04
        const val GENERIC_CHAR = CHAR_DATA + 0x19CC
        const val FRELIAN_CHAR = CHAR_DATA + 0x26CC
        const val GREEN_CAV_CHAR = CHAR_DATA + 0x2700

        const val LORD_CLASS = CLASS_DATA + 0x54
        const val CAVALIER_CLASS = CLASS_DATA + 0x150
        const val PALADIN_CLASS = CLASS_DATA + 0x1F8
        const val ARMOR_KNIGHT_CLASS = CLASS_DATA + 0x2A0
        const val MERCENARY_CLASS = CLASS_DATA + 0x498
        const val FIGHTER_CLASS = CLASS_DATA + 0x1458
        const val SOLDIER_CLASS = CLASS_DATA + 0x1944

        const val MU_UNIT = 0x2C
        const val MC_REDA_CNT = 0x38
        const val MC_TIMER = 0x3A
        const val MC_DELAY = 0x3C
        const val MC_DST_X = 0x42
        const val MC_DST_Y = 0x43
        const val MU_STATE = 0x3F
        const val MU_CONFIG = 0x4A
        const val MU_XQ4 = 0x4C
        const val MU_YQ4 = 0x4E

        val F_EWRAM_STEPS = setOf(10300, 13100, 25426, 25507, 25510, 25580)
        val TILE_ORDER = compareBy<TileVal>({ it.y }, { it.x })

        val WIN1_ENEMY_LOAD1_ROWS = listOf(
            "ProcScr_Mu <- tree 5 10299-10345",
            "ProcScr_Mu <- tree 5 10299-10350",
            "ProcScr_Mu <- tree 5 10300-10356",
            "ProcScr_Mu <- tree 5 10300-10364",
            "ProcScr_Mu <- tree 5 10347-10361",
            "ProcScr_Mu <- tree 5 10352-10376",
            "ProcScr_Mu <- tree 5 10358-10358",
            "ProcScr_MuCtrl <- tree 5 10299-10345",
            "ProcScr_MuCtrl <- tree 5 10299-10350",
            "ProcScr_MuCtrl <- tree 5 10300-10356",
            "ProcScr_MuCtrl <- tree 5 10300-10364",
            "ProcScr_MuCtrl <- tree 5 10347-10361",
            "ProcScr_MuCtrl <- tree 5 10352-10376",
            "ProcScr_MuCtrl <- tree 5 10358-10358",
            "ProcScr_MuStepSe <- tree 3 10301-10302",
            "ProcScr_MuStepSe <- tree 3 10316-10317",
            "ProcScr_MuStepSe <- tree 3 10325-10326",
            "ProcScr_MuStepSe <- tree 3 10332-10333",
            "ProcScr_MuStepSe <- tree 3 10341-10342",
            "ProcScr_MuStepSe <- tree 3 10349-10350",
            "ProcScr_MuStepSe <- tree 3 10354-10355",
            "ProcScr_MuStepSe <- tree 3 10358-10360",
            "ProcScr_MuStepSe <- tree 3 10363-10364",
            "ProcScr_MuStepSe <- tree 3 10371-10372",
            "ProcScr_StdEventEngine <- tree 3 10298-10378",
            "gProcScr_GameControl <- tree 3 10298-10378",
            "gProc_BMapMain <- tree 2 10298-10378",
            "gProc_MapTask <- tree 4 10298-10378",
            "sProc_BMVSync <- tree 0 10298-10378",
        )

        val WIN2_NPC_LOAD1_AND_DISA_ROWS = listOf(
            "ProcScr_EventDisplayCursor <- ProcScr_StdEventEngine 13103-13103",
            "ProcScr_Mu <- tree 5 13049-13058",
            "ProcScr_Mu <- tree 5 13049-13100",
            "ProcScr_MuCtrl <- tree 5 13049-13058",
            "ProcScr_MuCtrl <- tree 5 13049-13100",
            "ProcScr_MuStepSe <- tree 3 13050-13053",
            "ProcScr_MuStepSe <- tree 3 13059-13060",
            "ProcScr_MuStepSe <- tree 3 13070-13071",
            "ProcScr_MuStepSe <- tree 3 13073-13074",
            "ProcScr_MuStepSe <- tree 3 13080-13081",
            "ProcScr_MuStepSe <- tree 3 13091-13092",
            "ProcScr_MuStepSe <- tree 3 13094-13095",
            "ProcScr_StdEventEngine <- tree 3 13049-13103",
            "gProcScr_GameControl <- tree 3 13049-13103",
            "gProc_BMapMain <- tree 2 13049-13103",
            "gProc_MapTask <- tree 4 13049-13103",
            "sProc_BMVSync <- tree 0 13049-13103",
        )

        val WIN3A_BREGUET_MOVE_ROWS = listOf(
            "ProcScr_Mu <- tree 5 15049-15082",
            "ProcScr_MuCtrl <- tree 5 15049-15082",
            "ProcScr_MuStepSe <- tree 3 15049-15051",
            "ProcScr_MuStepSe <- tree 3 15065-15066",
            "ProcScr_MuStepSe <- tree 3 15081-15082",
            "ProcScr_StdEventEngine <- tree 3 15049-15084",
            "gProcScr_GameControl <- tree 3 15049-15084",
            "gProc_BMapMain <- tree 2 15049-15084",
            "gProc_MapTask <- tree 4 15049-15084",
            "sProc_BMVSync <- tree 0 15049-15084",
        )

        val WIN3B_KILL_AND_DISA_IF_ROWS = listOf(
            "ProcScr_EventDisplayCursor <- ProcScr_StdEventEngine 15558-15558",
            "ProcScr_Mu <- tree 5 15526-15556",
            "ProcScr_MuDeathFade <- ProcScr_Mu 15526-15556",
            "ProcScr_MuHitFlash <- ProcScr_Mu 15526-15542",
            "ProcScr_PalFade <- ProcScr_Mu 15526-15546",
            "ProcScr_StdEventEngine <- tree 3 15526-15558",
            "gProcScr_GameControl <- tree 3 15526-15558",
            "gProc_BMapMain <- tree 2 15526-15558",
            "gProc_MapTask <- tree 4 15526-15558",
            "sProc_BMVSync <- tree 0 15526-15558",
        )

        val WIN4_ENEMY_MOVE7_ROWS = listOf(
            "ProcScr_Mu <- tree 5 16019-16038",
            "ProcScr_Mu <- tree 5 16019-16055",
            "ProcScr_Mu <- tree 5 16020-16055",
            "ProcScr_Mu <- tree 5 16020-16144",
            "ProcScr_Mu <- tree 5 16040-16151",
            "ProcScr_Mu <- tree 5 16057-16069",
            "ProcScr_Mu <- tree 5 16057-16080",
            "ProcScr_MuCtrl <- tree 5 16019-16038",
            "ProcScr_MuCtrl <- tree 5 16019-16055",
            "ProcScr_MuCtrl <- tree 5 16020-16055",
            "ProcScr_MuCtrl <- tree 5 16020-16144",
            "ProcScr_MuCtrl <- tree 5 16040-16151",
            "ProcScr_MuCtrl <- tree 5 16057-16069",
            "ProcScr_MuCtrl <- tree 5 16057-16080",
            "ProcScr_MuStepSe <- tree 3 16021-16023",
            "ProcScr_MuStepSe <- tree 3 16031-16032",
            "ProcScr_MuStepSe <- tree 3 16037-16038",
            "ProcScr_MuStepSe <- tree 3 16039-16042",
            "ProcScr_MuStepSe <- tree 3 16047-16050",
            "ProcScr_MuStepSe <- tree 3 16055-16059",
            "ProcScr_MuStepSe <- tree 3 16064-16067",
            "ProcScr_MuStepSe <- tree 3 16072-16075",
            "ProcScr_MuStepSe <- tree 3 16080-16083",
            "ProcScr_MuStepSe <- tree 3 16088-16091",
            "ProcScr_MuStepSe <- tree 3 16096-16099",
            "ProcScr_MuStepSe <- tree 3 16104-16107",
            "ProcScr_MuStepSe <- tree 3 16112-16115",
            "ProcScr_MuStepSe <- tree 3 16120-16123",
            "ProcScr_MuStepSe <- tree 3 16128-16131",
            "ProcScr_MuStepSe <- tree 3 16136-16139",
            "ProcScr_MuStepSe <- tree 3 16146-16147",
            "ProcScr_StdEventEngine <- tree 3 16019-16153",
            "gProcScr_GameControl <- tree 3 16019-16153",
            "gProc_BMapMain <- tree 2 16019-16153",
            "gProc_MapTask <- tree 4 16019-16153",
            "sProc_BMVSync <- tree 0 16019-16153",
        )

        val WIN5_ALLY_LOAD2_ROWS = listOf(
            "ProcScr_Mu <- tree 5 20249-20266",
            "ProcScr_Mu <- tree 5 20249-20266",
            "ProcScr_MuCtrl <- tree 5 20249-20266",
            "ProcScr_MuCtrl <- tree 5 20249-20266",
            "ProcScr_MuStepSe <- tree 3 20250-20251",
            "ProcScr_MuStepSe <- tree 3 20254-20255",
            "ProcScr_MuStepSe <- tree 3 20257-20258",
            "ProcScr_MuStepSe <- tree 3 20261-20262",
            "ProcScr_MuStepSe <- tree 3 20265-20266",
            "ProcScr_StdEventEngine <- tree 3 20249-20268",
            "gProcScr_GameControl <- tree 3 20249-20268",
            "gProc_BMapMain <- tree 2 20249-20268",
            "gProc_MapTask <- tree 4 20249-20268",
            "sProc_BMVSync <- tree 0 20249-20268",
        )

        val WIN6_TURN1_HANDOFF_ROWS = listOf(
            "ProcScr_GreenTextColor <- gProcScr_GoalDisplay 25510-25580",
            "ProcScr_Mu <- tree 5 25515-25580",
            "ProcScr_PhaseIntro <- gProc_BMapMain 25429-25500",
            "ProcScr_StdEventEngine <- tree 3 25421-25421",
            "ProcScr_StdEventEngine <- tree 3 25423-25425",
            "gProcScr_GameControl <- tree 3 25421-25580",
            "gProcScr_GoalDisplay <- tree 3 25510-25580",
            "gProcScr_PhaseIntroBlendBox <- ProcScr_PhaseIntro 25430-25491",
            "gProcScr_PhaseIntroSquares <- ProcScr_PhaseIntro 25430-25496",
            "gProcScr_PhaseIntroText <- ProcScr_PhaseIntro 25430-25498",
            "gProcScr_PhaseIntroUnk <- gProcScr_PhaseIntroText 25437-25498",
            "gProcScr_PlayerPhase <- gProc_BMapMain 25507-25580",
            "gProcScr_ResetCursorPosition <- gProc_BMapMain 25429-25429",
            "gProcScr_TerrainDisplay <- tree 3 25510-25580",
            "gProcScr_UnitDisplay_MinimugBox <- tree 3 25510-25580",
            "gProc_BMapMain <- tree 2 25421-25580",
            "gProc_MapTask <- tree 4 25421-25580",
            "sProc_BMVSync <- tree 0 25421-25580",
        )
    }
}
