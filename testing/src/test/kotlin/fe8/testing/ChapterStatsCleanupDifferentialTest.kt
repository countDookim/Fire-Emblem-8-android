package fe8.testing

import fe8.game.GameControl
import fe8.game.RomView
import fe8.game.bm.BattleMapStart
import fe8.game.bm.PLAY_FLAG_HARD
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.flags.Fe8EventFlags
import fe8.game.save.BmSaveLib
import fe8.game.save.ChapterStats
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import gbafe.core.time.GameClock
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * `PC-3b` (`r09443 -> f09444`) of `ram_trace_prologue_end` (`PC-cap1`):
 * [ChapterStats.registerChapterTimeAndTurnCount], [ChapterStats.getNextChapterStatsSlot],
 * [ChapterStats.getChapterStats], and [UnitTable.chapterChangeUnitCleanup] wired through
 * [GameControl.chapterSwitch].
 *
 * All four functions (and [GameControl.chapterSwitch]) remain `Transcribed` because several
 * branches never execute at `r09443 -> f09444` (non-zero chapter stats slots, time/turn clamps,
 * non-zero `chapterIndex`, phantoms, surviving red/green units, non-zero status/torch/barrier/
 * rescue/supportBits, `US_NOT_DEPLOYED` -> `US_BIT21`, `CA_SUPPLY`, `PLAY_FLAG_PREPSCREEN`, and
 * `PC-3c` rankings). Those unexercised branches are covered by synthetic and `ram_trace_ch6_start`
 * tests below (no `@VerifiesPort` tags).
 */
class ChapterStatsCleanupDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun requireRom(): RomView {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        return RomView.ofRom(romFile.readBytes())
    }

    @Test
    fun `f9443 to f9444 chapter switch produces captured gChapterStats and cleaned unit arrays`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val rom = requireRom()
        val run = ref.run(RUN_PROLOGUE_END)
        val ew9443 = run.region("r09443", "ewram")
        val iw9443 = run.region("r09443", "iwram")
        val ew9444 = run.region("f09444", "ewram")
        val iw9444 = run.region("f09444", "iwram")

        val dump9443 = ProcArrayDump.ofWatch(run.watch("procs"), 9443)
        val ctrl9443 = checkNotNull(dump9443.find(GAME_CONTROL)) { "no GameControl at f9443" }

        val engine = ProcEngine()
        val stand = ProcScript("stand-in", emptyList())
        val control = GameControl(engine, KeyStatus(), { stand }, { stand })
        val rngWatch = run.watch("rng")
        control.rng.primary.load(
            rngWatch.u16(9443, 0),
            rngWatch.u16(9443, 2),
            rngWatch.u16(9443, 4),
        )
        control.playSt.decode(ew9443, ADDR_PLAY_ST - EWRAM_BASE)

        val saveLib = BmSaveLib(CartSram())
        val chOff = ADDR_CHAPTER_STATS - EWRAM_BASE
        ew9443.copyInto(saveLib.chapterStatsData, 0, chOff, chOff + ChapterStats.SIZE)
        val chapterStats = saveLib.chapterStats()

        val clock = GameClock().apply {
            set(u32(iw9443, ADDR_GAME_CLOCK - IWRAM_BASE).toLong() and 0xFFFFFFFFL)
        }
        val units = UnitTable(rom).apply { loadFrom(ew9443) }
        val loader = UnitLoader(
            units,
            rom,
            { type ->
                when (type) {
                    0 -> control.playSt.unk1C0
                    1 -> control.playSt.unk1C1
                    else -> 0
                }
            },
            control.rng,
            { control.playSt.chapterStateBits and PLAY_FLAG_HARD != 0 },
        )

        val eventFlags = Fe8EventFlags.create().apply {
            copyIn(iw9443, ADDR_FLAGS - IWRAM_BASE)
        }
        var rankingsCalled = 0
        var saveMenuCalled = 0
        control.hooks.winFlagSet = { eventFlags.checkFlag(EVFLAG_WIN) }
        control.hooks.registerChapterTimeAndTurnCount = ProcFunc {
            chapterStats.registerChapterTimeAndTurnCount(control.playSt, clock.get().toInt())
        }
        control.hooks.computeChapterRankings = ProcFunc { rankingsCalled++ }
        control.hooks.chapterChangeUnitCleanup = ProcFunc {
            units.chapterChangeUnitCleanup(control.playSt) { id ->
                loader.getUnitMaxHp(units.unitOffset(id))
            }
        }
        control.hooks.chapterSwitch = ProcFunc { control.chapterSwitch(it) }
        control.hooks.callPostChapterSaveMenu = ProcFunc { control.callPostChapterSaveMenu(it) }
        control.hooks.startPostChapterSaveMenu = ProcFunc {
            saveMenuCalled++
            engine.startBlocking(ProcScript("SaveMenuPostChapter (stand-in)", listOf(block())), it)
        }

        val proc = engine.start(control.script, 3)
        proc.idleCb = null
        proc.scrCur = ctrl9443.scrCur
        val state = proc.state as GameControl.State
        state.nextAction = ctrl9443.u8(0x29)
        state.nextChapter = ctrl9443.u8(0x2A)
        state.idleStatus = ctrl9443.u8(0x2B)
        state.unk2C = ctrl9443.u8(0x2C)
        state.unk2E = ctrl9443.u16(0x2E)
        state.chIndex = ctrl9443.u8(0x30)

        // Pre-conditions from r09443: Seth (id 1) HP 13 state 1, Eirika (id 2) HP 7 state 0.
        assertEquals(13, units.currentHp(1))
        assertEquals(1, units.state(1))
        assertEquals(7, units.currentHp(2))
        assertEquals(0, units.state(2))

        engine.run(engine.root(3))

        // 1. gChapterStats (all 192 bytes) and slot 0 decoded values match f09444 EWRAM.
        val wantStats = ew9444.copyOfRange(chOff, chOff + ChapterStats.SIZE)
        assertArrayEquals(wantStats, chapterStats.data, "gChapterStats full array at f09444")
        assertEquals(0x00360180, u32(chapterStats.data, 0), "gChapterStats[0] word")
        assertEquals(0, chapterStats.chapterIndex(0))
        assertEquals(3, chapterStats.chapterTurn(0))
        assertEquals(54, chapterStats.chapterTime(0))

        // 2. All unit arrays (blue/red/green/purple = 0x2688 bytes) match f09444 EWRAM exactly.
        val unitsOff = UnitTable.BLUE_ADDRESS - EWRAM_BASE
        val wantUnits = ew9444.copyOfRange(unitsOff, unitsOff + UnitTable.TOTAL_SIZE)
        assertArrayEquals(wantUnits, units.bytes, "all unit arrays at f09444")
        assertEquals(30, units.currentHp(1), "Seth healed to maxHP 30")
        assertEquals(0x09, units.state(1), "Seth state 0x09 (US_HIDDEN | US_NOT_DEPLOYED)")
        assertEquals(16, units.currentHp(2), "Eirika healed to maxHP 16")
        assertEquals(0x09, units.state(2), "Eirika state 0x09 (US_HIDDEN | US_NOT_DEPLOYED)")

        // 3. gPlaySt matches f09444 outside PC-3c ranking fields (unk_2C_04 at 0x2C..0x2D,
        //    unk_30 block at 0x30..0x3F).
        val psOff = ADDR_PLAY_ST - EWRAM_BASE
        val wantPlaySt = ew9444.copyOfRange(psOff, psOff + PlaySt.SIZE)
        val gotPlaySt = ByteArray(PlaySt.SIZE).also { control.playSt.encode(it, 0) }
        for (i in 0x2C..0x2D) gotPlaySt[i] = wantPlaySt[i]
        for (i in 0x30..0x3F) gotPlaySt[i] = wantPlaySt[i]
        assertArrayEquals(wantPlaySt, gotPlaySt, "gPlaySt at f09444 (rankings masked)")
        assertEquals(1, control.playSt.chapterIndex, "chapterIndex switched 0 -> 1")

        // 4. RNG, gGmMonsterRnState, proc state and hook counts.
        assertEquals(
            listOf(rngWatch.u16(9444, 0), rngWatch.u16(9444, 2), rngWatch.u16(9444, 4)),
            listOf(0, 1, 2).map { control.rng.primary.word(it) },
            "f9444 rng",
        )
        val wantMonsterRn = (0..2).map { i -> u16(iw9444, ADDR_MONSTER_RN - IWRAM_BASE + i * 2) }
        assertEquals(wantMonsterRn, control.gmMonsterRnState.toList(), "gGmMonsterRnState")
        val gc9444 = checkNotNull(
            ProcArrayDump.ofWatch(run.watch("procs"), 9444).find(GAME_CONTROL),
        )
        assertEquals(gc9444.scrCur, proc.scrCur, "f9444 GameControl scrCur")
        assertEquals(gc9444.u8(0x28), proc.lockCnt, "f9444 GameControl lockCnt")
        assertEquals(1, rankingsCalled)
        assertEquals(1, saveMenuCalled)
    }

    @Test
    fun `chapter6 dump tests GetNextChapterStatsSlot and GetChapterStats multi-slot traversal`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_ch6_start")
        val ew = run.region("r01142", "ewram")
        val chOff = ADDR_CHAPTER_STATS - EWRAM_BASE
        val stats = ChapterStats(ew.copyOfRange(chOff, chOff + ChapterStats.SIZE))

        assertEquals(7, stats.getNextChapterStatsSlot())
        assertEquals(7 * ChapterStats.ENTRY_SIZE, stats.getChapterStats(7))
        assertEquals(listOf(0, 1, 2, 3, 4, 6, 5), (0 until 7).map { stats.chapterIndex(it) })
        assertEquals(listOf(2, 6, 8, 12, 6, 10, 15), (0 until 7).map { stats.chapterTurn(it) })
        assertEquals(
            listOf(113, 90, 169, 209, 194, 284, 302),
            (0 until 7).map { stats.chapterTime(it) },
        )

        val snapshotBefore = stats.data.copyOf()
        val playSt = PlaySt().apply {
            chapterIndex = 7
            chapterTurnNumber = 11
            timeChapterStarted = 1000
        }
        stats.registerChapterTimeAndTurnCount(playSt, gameClock = 1000 + 180 * 42)
        assertArrayEquals(
            snapshotBefore.copyOfRange(0, 7 * ChapterStats.ENTRY_SIZE),
            stats.data.copyOfRange(0, 7 * ChapterStats.ENTRY_SIZE),
            "existing slots 0..6 untouched",
        )
        assertEquals(7, stats.chapterIndex(7))
        assertEquals(11, stats.chapterTurn(7))
        assertEquals(42, stats.chapterTime(7))
        assertEquals(8, stats.getNextChapterStatsSlot())
    }

    @Test
    fun `ChapterStats clamps time and turn counts and handles full table safely`() {
        val stats = ChapterStats()
        val playSt = PlaySt().apply {
            chapterIndex = 0x15
            chapterTurnNumber = 777
            timeChapterStarted = 10
        }
        // Elapsed frames exceed 50 * 60 * 20 * 180 = 10_800_000 frames; turn exceeds 500.
        stats.registerChapterTimeAndTurnCount(playSt, gameClock = 10 + 12_000_000)
        assertEquals(0x15, stats.chapterIndex(0))
        assertEquals(ChapterStats.MAX_CHAPTER_TURN, stats.chapterTurn(0))
        assertEquals(ChapterStats.MAX_CHAPTER_TIME, stats.chapterTime(0))

        // Unsigned 32-bit wraparound: clock wrapped past 2^31 / 2^32.
        val wrapped = PlaySt().apply {
            chapterIndex = 3
            chapterTurnNumber = 9
            timeChapterStarted = -200
        }
        stats.registerChapterTimeAndTurnCount(wrapped, gameClock = 340) // diff = 540u -> 3
        assertEquals(3, stats.chapterIndex(1))
        assertEquals(9, stats.chapterTurn(1))
        assertEquals(3, stats.chapterTime(1))

        // Fill remaining slots through 47 and verify full-table safety guard.
        for (slot in 2 until ChapterStats.ARRAY_NUM) {
            stats.registerChapterTimeAndTurnCount(wrapped, gameClock = 340)
            assertEquals(slot + 1, stats.getNextChapterStatsSlot())
        }
        val fullBefore = stats.data.copyOf()
        stats.registerChapterTimeAndTurnCount(wrapped, gameClock = 340)
        assertArrayEquals(fullBefore, stats.data, "full table write is a safe no-op")
    }

    @Test
    fun `ChapterChangeUnitCleanup clears phantoms, non-blue and resets blue flags synthetically`() {
        val rom = requireRom()
        val table = UnitTable(rom).apply { initUnits() }
        val playSt = PlaySt().apply {
            chapterStateBits = PLAY_FLAG_HARD or BattleMapStart.PLAY_FLAG_PREPSCREEN
        }

        // Unit 1: regular blue unit with temporary states, status, torch/barrier, rescue, support.
        val u1 = table.unitOffset(1)
        put32(table.bytes, u1, CHAR_EIRIKA_PTR)
        put32(table.bytes, u1 + UnitTable.OFF_CLASS, CLASS_LORD_EIRIKA_PTR)
        table.bytes[u1 + 0x12] = 20 // maxHP
        table.bytes[u1 + 0x13] = 5  // curHP
        table.setUnitStatusExt(1, UnitTable.STATUS_SLEEP, 3)
        table.bytes[u1 + 0x31] = 0x54.toByte() // torch 4, barrier 5
        table.setRescue(1, 2)
        table.bytes[u1 + 0x39] = 0x1F // supportBits
        table.setState(
            1,
            US_DEAD or US_GROWTH_BOOST or US_SOLOANIM_1 or US_SOLOANIM_2 or
                US_BIT16 or US_BIT20 or US_BIT21 or US_BIT25 or US_BIT26 or
                UnitTable.US_UNSELECTABLE or UnitTable.US_RESCUING or UnitTable.US_IN_BALLISTA or
                US_HAS_MOVED or US_DROP_ITEM,
        )

        // Unit 2: undeployed blue unit -> US_BIT21 must become set (plus 0x09).
        val u2 = table.unitOffset(2)
        put32(table.bytes, u2, CHAR_SETH_PTR)
        put32(table.bytes, u2 + UnitTable.OFF_CLASS, CLASS_PALADIN_PTR)
        table.bytes[u2 + 0x12] = 30
        table.bytes[u2 + 0x13] = 10
        table.setState(2, US_NOT_DEPLOYED)

        // Unit 3: blue phantom unit -> cleared completely except index 3.
        val u3 = table.unitOffset(3)
        put32(table.bytes, u3, CHAR_SETH_PTR)
        put32(table.bytes, u3 + UnitTable.OFF_CLASS, CLASS_PHANTOM_PTR)
        table.bytes[u3 + 0x12] = 1
        table.bytes[u3 + 0x13] = 1

        // Unit 4: blue CA_SUPPLY unit with US_DEAD -> US_DEAD cleared, other kept bits retained.
        val u4 = table.unitOffset(4)
        put32(table.bytes, u4, CHAR_EIRIKA_PTR)
        put32(table.bytes, u4 + UnitTable.OFF_CLASS, CLASS_TRANSPORTER_PTR)
        table.bytes[u4 + 0x12] = 18
        table.bytes[u4 + 0x13] = 1
        table.setState(4, US_DEAD or US_GROWTH_BOOST)

        // Green unit (0x41) and Red unit (0x81) -> cleared completely except their index bytes.
        val g1 = table.unitOffset(0x41)
        put32(table.bytes, g1, CHAR_EIRIKA_PTR)
        put32(table.bytes, g1 + UnitTable.OFF_CLASS, CLASS_LORD_EIRIKA_PTR)
        table.bytes[g1 + 0x13] = 12
        val r1 = table.unitOffset(0x81)
        put32(table.bytes, r1, CHAR_SETH_PTR)
        put32(table.bytes, r1 + UnitTable.OFF_CLASS, CLASS_PALADIN_PTR)
        table.bytes[r1 + 0x13] = 22

        val loader = UnitLoader(table, rom, { 0 }, gbafe.core.rng.RngService(), { false })
        table.chapterChangeUnitCleanup(playSt) { id ->
            loader.getUnitMaxHp(table.unitOffset(id))
        }

        // Phantoms, green, red cleared and index preserved.
        assertFalse(table.isValid(3), "blue phantom cleared")
        assertEquals(3, table.index(3), "phantom index preserved")
        assertEquals(0, table.bytes[u3 + 0x12].toInt(), "phantom struct zeroed")
        assertFalse(table.isValid(0x41), "green unit cleared")
        assertEquals(0x41, table.index(0x41), "green index preserved")
        assertEquals(0, table.bytes[g1 + 0x13].toInt())
        assertFalse(table.isValid(0x81), "red unit cleared")
        assertEquals(0x81.toByte().toInt(), table.index(0x81), "red index preserved")
        assertEquals(0, table.bytes[r1 + 0x13].toInt())

        // Unit 1: healed, temp fields cleared, US_BIT21 cleared (was deployed), persistent kept.
        assertEquals(20, table.currentHp(1))
        assertEquals(0, table.bytes[u1 + UnitTable.OFF_STATUS].toInt())
        assertEquals(0, table.bytes[u1 + 0x31].toInt())
        assertEquals(0, table.rescue(1))
        assertEquals(0, table.bytes[u1 + 0x39].toInt())
        val expectedU1State = US_DEAD or US_GROWTH_BOOST or US_SOLOANIM_1 or US_SOLOANIM_2 or
            US_BIT16 or US_BIT20 or US_BIT25 or US_BIT26 or US_HIDDEN or US_NOT_DEPLOYED
        assertEquals(expectedU1State, table.state(1), "u1 kept bits + 0x09 - US_BIT21 - temp bits")

        // Unit 2: was US_NOT_DEPLOYED -> US_BIT21 set along with 0x09.
        assertEquals(30, table.currentHp(2))
        assertEquals(US_BIT21 or US_HIDDEN or US_NOT_DEPLOYED, table.state(2))

        // Unit 4: CA_SUPPLY cleared US_DEAD while preserving US_GROWTH_BOOST.
        assertEquals(18, table.currentHp(4))
        assertEquals(US_GROWTH_BOOST or US_HIDDEN or US_NOT_DEPLOYED, table.state(4))

        // PlaySt cleared PLAY_FLAG_PREPSCREEN while keeping PLAY_FLAG_HARD.
        assertEquals(PLAY_FLAG_HARD, playSt.chapterStateBits)
    }

    private companion object {
        const val RUN_PROLOGUE_END = "ram_trace_prologue_end"
        const val EWRAM_BASE = 0x02000000
        const val IWRAM_BASE = 0x03000000
        const val ADDR_PLAY_ST = 0x0202BCF0
        const val ADDR_CHAPTER_STATS = 0x0203ECF4
        const val ADDR_GAME_CLOCK = 0x03000014
        const val ADDR_FLAGS = 0x03005250
        const val ADDR_MONSTER_RN = 0x0300534E
        const val EVFLAG_WIN = 3
        const val GAME_CONTROL = 0x085916D4

        // Real ROM struct pointers (FE8U baserom.gba).
        const val CHAR_EIRIKA_PTR = 0x08803D64
        const val CHAR_SETH_PTR = 0x08803D98
        const val CLASS_DATA = 0x08807110
        const val CLASS_SIZE = 0x54
        const val CLASS_LORD_EIRIKA_PTR = CLASS_DATA + 0x02 * CLASS_SIZE
        const val CLASS_PALADIN_PTR = CLASS_DATA + 0x07 * CLASS_SIZE
        const val CLASS_PHANTOM_PTR = CLASS_DATA + 0x51 * CLASS_SIZE
        const val CLASS_TRANSPORTER_PTR = CLASS_DATA + 0x79 * CLASS_SIZE

        const val US_HIDDEN = 1 shl 0
        const val US_DEAD = 1 shl 2
        const val US_NOT_DEPLOYED = 1 shl 3
        const val US_HAS_MOVED = 1 shl 6
        const val US_DROP_ITEM = 1 shl 12
        const val US_GROWTH_BOOST = 1 shl 13
        const val US_SOLOANIM_1 = 1 shl 14
        const val US_SOLOANIM_2 = 1 shl 15
        const val US_BIT16 = 1 shl 16
        const val US_BIT20 = 1 shl 20
        const val US_BIT21 = 1 shl 21
        const val US_BIT25 = 1 shl 25
        const val US_BIT26 = 1 shl 26

        fun u16(buf: ByteArray, off: Int): Int =
            (buf[off].toInt() and 0xFF) or ((buf[off + 1].toInt() and 0xFF) shl 8)

        fun u32(buf: ByteArray, off: Int): Int = u16(buf, off) or (u16(buf, off + 2) shl 16)

        fun put32(buf: ByteArray, off: Int, value: Int) {
            buf[off] = value.toByte()
            buf[off + 1] = (value shr 8).toByte()
            buf[off + 2] = (value shr 16).toByte()
            buf[off + 3] = (value shr 24).toByte()
        }
    }
}
