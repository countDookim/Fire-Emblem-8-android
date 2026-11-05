package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleMapStart
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterMapGfx
import fe8.game.bm.PlaySt
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.core.time.GameClock
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.sleep
import gbafe.render.BgHardware
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * M5-43: [BattleMapStart] against the first full dump after the game's `StartBattleMap` +
 * `StartBMapMain` (`ram_trace_newgame_bm` f09319, chapter 0). The state before the call is
 * synthetic: every `gPlaySt` field the function writes, all of `gBmSt` but `lock`, the first five
 * chapter flag bytes hold values the port must change, everything else
 * comes from the dump.
 *
 * Compared: the whole 0x4C bytes of `gPlaySt` (faction 0x40, turn 0, vision, weather, start time
 * from the game clock, support gain, `unk48`, `unk4A_1`, tutorial counter / exec type), the whole
 * 0x40 bytes of `gBmSt`, the 16 `gChapterFlagBits` bytes, `gBmMapSize` and the terrain pool, the
 * live proc array in slot order (script, tree, mark, lock count, BMapMain / BMVSync script
 * position, BMVSync end callback present), and the game control proc's `lockCnt`.
 *
 * `StartBattleMap` carries no `@VerifiesPort` tag: chapter 0 has no units yet (the deploy loop is
 * never exercised), its fog level and weather are both 0 and it is not a skirmish, so the vision
 * and weather branches, the skirmish roll and the deploy loop have no ground truth. The only
 * other trace with a battle map start, `ram_trace_ch6_start`, is a suspend resume
 * (`GameCtrl_StartResumedGame`: `ClearBattleMapState` + `StartBMapMain`, no `StartBattleMap`);
 * it checks `StartBMapMain` a second time (frame r01149, procs matched by script).
 *
 * Not compared: the unit deploy loop, the
 * MapTask script position (the dump was taken while the proc was in the middle of its first
 * frame), proc slot numbers and proc-private fields (stale from earlier procs), the BMapMain
 * script after its first yield (placeholder until M5-44), the LCD blend registers, which later
 * code in the frame changes, `GetBattleMapKind` (stand-in: STORY) and every stand-in hook.
 */
class BattleMapStartDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun ByteArray.u8(addr: Int) = this[addr - GbaMem.EWRAM].toInt() and 0xFF
    private fun ByteArray.u16(addr: Int) = u8(addr) or (u8(addr + 1) shl 8)
    private fun ByteArray.u32(addr: Int) = u16(addr) or (u16(addr + 2) shl 16)

    /** Runs the port from the synthetic pre-state; the dump is the expected post-state. */
    private fun run(): Run {
        val rom = romFile.readBytes()
        val view = RomView.ofRom(rom)
        val dump = ref.run("ram_trace_newgame_bm")
        val ew = dump.region(DUMP, "ewram")
        val iw = dump.region(DUMP, "iwram")

        val playSt = PlaySt().apply { decode(ew, PLAY_ST - GbaMem.EWRAM) }
        val post = ByteArray(PlaySt.SIZE).also { playSt.encode(it, 0) }
        val postBm = ByteArray(BM_ST_SIZE).also { out ->
            ew.copyInto(out, 0, BM_ST - GbaMem.EWRAM, BM_ST - GbaMem.EWRAM + BM_ST_SIZE)
        }
        val clock = GameClock().apply { set(playSt.timeChapterStarted.toLong() and 0xFFFFFFFFL) }
        // Values StartBattleMap must overwrite.
        playSt.faction = 0x80
        playSt.chapterTurnNumber = 7
        playSt.chapterVisionRange = 2
        playSt.chapterWeatherId = 5
        playSt.timeChapterStarted = 0x1234
        playSt.chapterTotalSupportGain = 9
        playSt.unk48 = 3
        playSt.unk4A1 = 1
        playSt.tutorialCounter = 4
        playSt.tutorialExecType = 3

        val bmSt = BmSt()
        bmSt.decode(ByteArray(BM_ST_SIZE) { -1 }, 0)
        bmSt.lock = postBm[1].toInt()

        val flags = Fe8EventFlags.create()
        val chapterBytes = ByteArray(Fe8EventFlags.CHAPTER_STORAGE_BYTES) {
            if (it < Fe8EventFlags.CHAPTER_SAVE_BYTES) -1 else iw[FLAGS - GbaMem.IWRAM + it]
        }
        flags.copyInChapter(chapterBytes)

        val hw = BgHardware()
        hw.vram.fill(0xAA.toByte())
        val engine = ProcEngine()
        val control = engine.start(CONTROL, 3)
        val chapters = ChapterData(view)
        val map = BmMap(view, chapters, bmSt)
        val start = BattleMapStart(
            engine, playSt, bmSt, hw, chapters, map,
            ChapterMapGfx(view, chapters, hw, playSt), BmMapRender(bmSt, map, hw),
            flags, RngService(), clock,
        )
        start.startBattleMap(control)
        return Run(
            ew, ProcArrayDump.ofEwram(ew, ProcArrayDump.romNames()), engine, control, playSt,
            post, bmSt, postBm, flags, iw, map,
        )
    }

    @Test
    // Not @VerifiesPort("StartBattleMap"): see the class KDoc (deploy loop, fog and skirmish).
    fun `play state flags and map match the dump`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val r = run()
        val got = ByteArray(PlaySt.SIZE).also { r.playSt.encode(it, 0) }
        for (i in got.indices) assertEquals(r.post[i], got[i], "gPlaySt+0x${i.toString(16)}")

        for (i in 0 until Fe8EventFlags.CHAPTER_STORAGE_BYTES) {
            assertEquals(
                r.iwram[FLAGS - GbaMem.IWRAM + i].toInt() and 0xFF,
                r.flags.byteAt(Fe8EventFlags.PERMANENT_STORAGE_BYTES + i),
                "gChapterFlagBits[$i]",
            )
        }

        assertEquals(r.ewram.u8(BM_MAP_SIZE), r.map.sizeX, "gBmMapSize.x")
        assertEquals(r.ewram.u8(BM_MAP_SIZE + 2), r.map.sizeY, "gBmMapSize.y")
        val rows = r.ewram.u32(GBM_MAP_TERRAIN)
        for (y in 0 until r.map.sizeY) {
            val row = r.ewram.u32(rows + 4 * y)
            for (x in 0 until r.map.sizeX) {
                assertEquals(r.ewram.u8(row + x), r.map.terrain[y, x], "terrain ($x,$y)")
            }
        }
    }

    @Test
    @VerifiesPort("ClearBattleMapState")
    fun `gBmSt is cleared`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val r = run()
        val got = ByteArray(BM_ST_SIZE).also { r.bmSt.encode(it, 0) }
        for (i in got.indices) assertEquals(r.postBm[i], got[i], "gBmSt+0x${i.toString(16)}")
    }

    @Test
    @VerifiesPort("StartBMapMain")
    fun `procs and the game control lock match the dump`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val r = run()
        val theirs = r.procs.live
        val ours = r.engine.procs.filter { it.script != null }
        assertEquals(
            listOf("gProcScr_GameControl", "gProc_BMapMain", "sProc_BMVSync", "gProc_MapTask"),
            theirs.map { it.name },
            "dump procs",
        )
        assertEquals(theirs.map { it.name }, ours.map { it.script!!.name }, "proc order")
        for ((t, o) in theirs.zip(ours)) {
            val at = t.name
            assertEquals(t.u32(0x14), o.rootIndex, "tree of $at")
            assertEquals(t.u8(0x26), o.mark, "mark of $at")
            assertEquals(t.u8(0x28), o.lockCnt, "lockCnt of $at")
            // MapTask: the dump caught it inside its first frame (scrCur 6), so no position.
            if (t.name != "gProc_MapTask" && t.name != "gProcScr_GameControl") {
                assertEquals(t.scrCur, o.scrCur, "scrCur of $at")
            }
        }
        assertTrue(theirs[2].u32(8) != 0 && ours[2].endCb != null, "BMVSync end callback")
        assertEquals(1, r.control.lockCnt, "game control lockCnt")
        assertEquals(theirs[0].u8(0x28), r.control.lockCnt, "dump game control lockCnt")
    }

    @Test
    @VerifiesPort("StartBMapMain")
    fun `resume case starts the same procs as the game`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val dump = ref.run("ram_trace_ch6_start")
        val post = ProcArrayDump.ofEwram(dump.region("r01149", "ewram"), ProcArrayDump.romNames())
        val view = RomView.ofRom(romFile.readBytes())
        val engine = ProcEngine()
        val control = engine.start(CONTROL, 3)
        val playSt = PlaySt()
        val bmSt = BmSt()
        val hw = BgHardware()
        val chapters = ChapterData(view)
        val map = BmMap(view, chapters, bmSt)
        val start = BattleMapStart(
            engine, playSt, bmSt, hw, chapters, map, ChapterMapGfx(view, chapters, hw, playSt),
            BmMapRender(bmSt, map, hw), Fe8EventFlags.create(), RngService(), GameClock(),
        )
        bmSt.gameGfxSemaphore = 5
        start.startBMapMain(control)

        // Slot numbers differ (the game's free list is in another order), so match by script.
        val theirs = post.live.associateBy { it.name!! }
        val ours = engine.procs.filter { it.script != null }.associateBy { it.script!!.name }
        assertEquals(theirs.keys, ours.keys, "scripts")
        assertEquals(4, ours.size)
        for ((name, t) in theirs) {
            val o = ours.getValue(name)
            assertEquals(t.u32(0x14), o.rootIndex, "tree of $name")
            assertEquals(t.u8(0x26), o.mark, "mark of $name")
            assertEquals(t.u8(0x28), o.lockCnt, "lockCnt of $name")
            // MapTask already ran a frame in the dump (scrCur 9); GameControl is a dummy here.
            if (name == "gProc_BMapMain" || name == "sProc_BMVSync") {
                assertEquals(t.scrCur, o.scrCur, "scrCur of $name")
            }
        }
        assertTrue(theirs.getValue("sProc_BMVSync").u32(8) != 0)
        assertTrue(ours.getValue("sProc_BMVSync").endCb != null, "BMVSync end callback")
        assertEquals(0, bmSt.gameGfxSemaphore, "gameGfxSemaphore")
    }

    private companion object {
        const val DUMP = "f09319"
        const val PLAY_ST = 0x0202BCF0
        const val BM_ST = 0x0202BCB0
        const val BM_ST_SIZE = 0x40
        const val FLAGS = 0x03005270
        const val BM_MAP_SIZE = 0x0202E4D4
        const val GBM_MAP_TERRAIN = 0x0202E4DC

        /** A proc standing for `gProcScr_GameControl`, which calls StartBattleMap. */
        val CONTROL = ProcScript(
            "gProcScr_GameControl",
            listOf(mark(0x0B), label(0), sleep(0), goto(0)),
        )
    }

    // Helper classes go last: tools/progress.py credits a tag to the last class above it.

    /** The port's result and the dump it is compared with. */
    private class Run(
        val ewram: ByteArray,
        val procs: ProcArrayDump,
        val engine: ProcEngine,
        val control: Proc,
        val playSt: PlaySt,
        val post: ByteArray,
        val bmSt: BmSt,
        val postBm: ByteArray,
        val flags: gbafe.core.flags.EventFlags,
        val iwram: ByteArray,
        val map: BmMap,
    )
}
