package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.GameControl
import fe8.game.RomView
import fe8.game.event.EventConst
import fe8.game.save.Fe8SramLayout as L
import gba.core.sram.SaveStorage
import gba.testkit.AllocationMeter
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * PC-3a WIRE: the real game loop ([Fe8Boot], whole ROM) from the Prologue's ending event to the
 * post-chapter save menu proc. The boot resumes the Prologue suspend save of
 * `ram_trace_prologue_resume`; once the player phase is idle the ending event is started the way
 * `RunPotentialWaitEvents` does (`EventEngine_Create`, `EV_EXEC_CUTSCENE`; the winning kill of
 * `ram_trace_prologue_end` is not replayed, so the state before the event is the resume save's,
 * not `f4893`) and skipped with Start. Its tail runs `MNC2`, which sets `EV_STATE_CHANGEGM`;
 * `EventEngine_OnEnd` then ends the Mus and `BMapMain`, and `GameControl` runs
 * `GameControl_PostChapterSwitch`, `GameControl_ChapterSwitch` and
 * `GameControl_CallPostChapterSaveMenu` as in `f9440..9444` of `ram_trace_prologue_end`.
 */
class PrologueEndingWireTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )

    private class Frames {
        var frame = 0
        var keys = 0
        var manual = false
    }

    private fun bootAtIdle(f: Frames): Fe8Boot {
        val save = ref.run("ram_trace_prologue_resume").sram("s01001")
        val storage = object : SaveStorage {
            override fun load(dest: ByteArray): Boolean {
                save.copyInto(dest)
                return true
            }

            override fun store(src: ByteArray) {}
        }
        val pulse = arrayOf<Any>(0, 1)
        val boot = Fe8Boot(
            RomView.ofRom(File(assets, "baserom.gba").readBytes()),
            @Suppress("UNCHECKED_CAST")
            ({
                if (f.manual) f.keys else pulse[if (f.frame % 20 in 5..8) 1 else 0]
            } as () -> Int),
            RngService(),
            saveStorage = storage,
        )
        while (f.frame < 3000 && boot.engine.find(boot.playerPhaseScript)?.scrCur != 16) {
            boot.frame()
            f.frame++
        }
        assertEquals(16, boot.engine.find(boot.playerPhaseScript)?.scrCur, "idle player phase")
        f.manual = true
        return boot
    }

    private class Gc(val cursor: Int, val lock: Int)

    /** The capture's `GameControl` proc at [step]: script cursor and lock count. */
    private fun captured(run: ReferenceData.Run, step: Int): Gc {
        val gc = checkNotNull(ProcArrayDump.ofWatch(run.watch("procs"), step).find(GAME_CONTROL))
        return Gc(gc.scrCur, gc.u8(0x28))
    }

    @Test
    fun `the ending event reaches the post chapter save menu through the real game loop`() {
        assumeTrue(ref.available && File(assets, "baserom.gba").isFile, "reference data / ROM")
        val romNames = ProcArrayDump.romNames()
        assumeTrue(romNames.isNotEmpty(), "decomp nm file")
        val ending = romNames.entries.first { it.value == "EventScr_Prologue_EndingScene" }.key
        val run = ref.run("ram_trace_prologue_end")
        val capGc = ProcArrayDump.ofWatch(run.watch("procs"), 9441).find(GAME_CONTROL)!!
        val f = Frames()
        val boot = bootAtIdle(f)

        fun live() = boot.engine.procs.mapNotNull { it.script?.name }
        val gc = boot.engine.procs.first { it.script === boot.control.script }
        assertTrue("gProc_BMapMain" in live() && "gProcScr_PlayerPhase" in live())
        assertEquals(captured(run, 9440).lock, gc.lockCnt, "BMapMain holds the lock")

        boot.eventEngine.callEvent(ending, EventConst.EV_EXEC_CUTSCENE)
        var frames = 0
        // Start (skip) every 8 frames until BMapMain is gone.
        while (frames < 2000 && "gProc_BMapMain" in live()) {
            f.keys = if (frames % 8 in 2..3) START else 0
            boot.frame()
            f.frame++
            frames++
        }
        f.keys = 0
        assertTrue("gProc_BMapMain" !in live(), "BMapMain ended (EndBMapMain) within 2000 frames")
        // f9441: the teardown frame; Mu, PlayerPhase, the event engine and the DISP procs are gone.
        for (gone in listOf(
            "ProcScr_Mu", "gProcScr_PlayerPhase", "ProcScr_StdEventEngine", "gProc_MapTask",
            "sProc_BMVSync",
        )) {
            assertTrue(gone !in live(), "$gone ended with BMapMain")
        }
        assertEquals(captured(run, 9441).lock, gc.lockCnt, "f9441 lock released")
        assertEquals(captured(run, 9441).cursor, gc.scrCur, "f9441 cursor")
        val state = gc.state as GameControl.State
        assertEquals(capGc.u8(0x29), state.nextAction, "nextAction")
        assertEquals(capGc.u8(0x2A), state.nextChapter, "nextChapter")

        // f9442..f9444: PostChapterSwitch, ChapterSwitch (RN state, chapter) and the save menu.
        for (step in 9442..9444) {
            boot.frame()
            f.frame++
            val want = captured(run, step)
            assertEquals(want.cursor, gc.scrCur, "f$step cursor")
            assertEquals(want.lock, gc.lockCnt, "f$step lock")
        }
        assertEquals(state.nextChapter, boot.control.playSt.chapterIndex, "chapter switched")
        assertEquals(
            listOf(0, 1, 2).map { boot.rng.primary.word(it) },
            boot.control.gmMonsterRnState.toList(),
            "gGmMonsterRnState is the RN state ChapterSwitch stored",
        )
        assertEquals(SAVE_MENU, gc.child?.script?.name, "the save menu is GameControl's child")
        assertNull(boot.engine.find(boot.playerPhaseScript), "no player phase")
    }

    /** Ends the idle-phase windows the real flow has already closed when the ending starts. */
    private fun closeIdleWindows(boot: Fe8Boot) {
        for (stray in STRAY_PROCS) {
            boot.engine.procs.filter { it.script?.name == stray }.forEach { boot.engine.end(it) }
        }
    }

    private fun reachMenu(boot: Fe8Boot, f: Frames, ending: Int, gc: gbafe.proc.Proc) {
        closeIdleWindows(boot)
        boot.eventEngine.callEvent(ending, EventConst.EV_EXEC_CUTSCENE)
        var n = 0
        while (n < 2000 && gc.child?.script?.name != SAVE_MENU) {
            f.keys = if (n % 8 in 2..3) START else 0
            boot.frame()
            f.frame++
            n++
        }
        assertEquals(SAVE_MENU, gc.child?.script?.name, "save menu reached")
    }

    private fun u16(b: ByteArray, o: Int) =
        (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)

    /** Per-layer differences of the boot's display state from the capture dump [d]. */
    private class Layers(
        val dispcnt: Boolean,
        val bgcnt: Boolean,
        val tilemapRows: List<List<Int>>,
        val palette: List<Int>,
        val oam: List<Int>,
    )

    private fun layers(boot: Fe8Boot, run: ReferenceData.Run, d: String): Layers {
        val hw = boot.ui.env.hardware
        val io = run.region(d, "io")
        val vr = run.region(d, "vram")
        val pal = run.region(d, "pal")
        val oamCap = run.region(d, "oam")
        val oam = boot.loop.oam.oam
        val rows = (0..3).map { bg ->
            val base = ((u16(io, 8 + 2 * bg) shr 8) and 31) * 0x800
            (0 until 0x400).filter {
                u16(vr, base + it * 2) !=
                    ((hw.vram[base + it * 2].toInt() and 255) or
                        ((hw.vram[base + it * 2 + 1].toInt() and 255) shl 8))
            }.map { it / 32 }.distinct()
        }
        return Layers(
            dispcnt = u16(io, 0) == hw.lcd.dispcnt and 0xFFFF,
            bgcnt = (0..3).all { u16(io, 8 + 2 * it) == hw.lcd.bgCnt[it] and 0xFFFF },
            tilemapRows = rows,
            palette = (0 until 512).filter {
                hw.palette[it].toInt() and 0xFFFF != u16(pal, it * 2)
            },
            oam = (0 until 128).filter { i ->
                (0..3).any { oam[i * 4 + it].toInt() and 0xFFFF != u16(oamCap, (i * 4 + it) * 2) }
            },
        )
    }

    private fun sramCopy(boot: Fe8Boot) =
        ByteArray(L.SRAM_SIZE).also { boot.sram.copyOut(it) }

    private class MenuRun(val shift: Int, val menuEnd: Int)

    /**
     * Plays the capture's inputs through the save menu until it ends. The capture's
     * `SaveMenu_InitScreen` spans 14 frames of VBlank lag (savedraw starts f9459, BmFadeIN f9461)
     * that the port does not have; the boot's frame is aligned to the capture's by the start of
     * `BmFadeIN` (`shift`, 15) and [onFrame] gets the capture frame equivalent after each frame.
     * The capture's A presses are input indexes 9599 and 9799, which the game sees one step later.
     */
    private fun playMenu(
        boot: Fe8Boot,
        f: Frames,
        run: ReferenceData.Run,
        gc: gbafe.proc.Proc,
        step: () -> Unit = { boot.frame() },
        onFrame: (Int) -> Unit = {},
    ): MenuRun {
        var n = 0
        var shift = Int.MIN_VALUE
        while (n < 700) {
            n++
            f.keys = if (shift == Int.MIN_VALUE) 0 else run.inputs[9444 + n + shift - 1]
            step()
            f.frame++
            if (shift == Int.MIN_VALUE &&
                boot.engine.procs.any { it.script?.name == "ProcScr_BmFadeIN" }
            ) {
                shift = FADE_IN_CAPTURE_FRAME - (9444 + n)
            }
            if (shift == Int.MIN_VALUE) continue
            val cap = 9444 + n + shift
            onFrame(cap)
            if (gc.child?.script?.name != SAVE_MENU) return MenuRun(shift, cap)
        }
        return MenuRun(shift, -1)
    }

    private fun start(): Triple<Fe8Boot, Frames, gbafe.proc.Proc> {
        val ending = ProcArrayDump.romNames().entries
            .first { it.value == "EventScr_Prologue_EndingScene" }.key
        val f = Frames()
        val boot = bootAtIdle(f)
        val gc = boot.engine.procs.first { it.script === boot.control.script }
        reachMenu(boot, f, ending, gc)
        return Triple(boot, f, gc)
    }

    private fun available() = ref.available && File(assets, "baserom.gba").isFile &&
        ProcArrayDump.romNames().isNotEmpty()

    @Test
    fun `the save menu replay matches the capture`() {
        assumeTrue(available(), "reference data / ROM / decomp nm file")
        val run = ref.run("ram_trace_prologue_end")
        val (boot, f, gc) = start()
        fun live() = boot.engine.procs.mapNotNull { it.script?.name }
        var sramWrite = -1
        var fadeOutFrames = 0
        var prev = sramCopy(boot)
        val seen = HashMap<Int, Layers>()
        val result = playMenu(boot, f, run, gc) { cap ->
            val cur = sramCopy(boot)
            if (sramWrite < 0 && !cur.contentEquals(prev)) sramWrite = cap
            prev = cur
            if ("ProcScr_BmFadeOUT" in live()) fadeOutFrames++
            if (cap in CHECK_FRAMES) seen[cap] = layers(boot, run, "f%05d".format(cap))
        }
        // The slot 0 write lands at the capture's first SRAM change (s09800), the fade-out takes
        // the capture's 16 frames (BmFadeOUT f9845..f9860).
        assertEquals(FADE_IN_CAPTURE_FRAME - 9446, result.shift, "alignment shift")
        assertEquals(9800, sramWrite, "SRAM write frame")
        assertEquals(16, fadeOutFrames, "BmFadeOUT frames")
        // The boot ends the menu before the capture's f9862: the capture's 3-frame SRAM write and
        // its slot flip run slower than the port's (not modelled).
        assertTrue(result.menuEnd in 9840..9862, "menu ended at equivalent f${result.menuEnd}")

        // Display: BG0..3 tilemaps, BGCNT and DISPCNT equal at every checked frame before the
        // write result is shown (f9802 is 2 steps into the capture's write lag).
        for ((c, l) in seen) {
            assertTrue(l.dispcnt && l.bgcnt, "f$c DISPCNT/BGCNT")
            // Palette: BG bank 3 colours 1..4 and 13..15 are the talk bubble palette of the ending
            // dialogue, which the Start skip never loads; 273 and 417 are the animated cursor
            // colours (a frame apart).
            assertTrue(
                l.palette.all { it in STALE_PALETTE }, "f$c palette differs at ${l.palette}",
            )
        }
        for (c in intArrayOf(9494, 9500, 9600, 9700, 9799, 9800)) {
            assertEquals(listOf<List<Int>>(emptyList(), emptyList(), emptyList(), emptyList()),
                seen[c]!!.tilemapRows, "f$c BG0..3 tilemaps")
        }
        assertEquals(listOf(17, 18), seen[9802]!!.tilemapRows[1], "f9802 BG1 rows (write lag)")
        // OAM: the slot boxes, banner and cursor objects (indexes 0..45) are equal while the
        // slot select idles; later indexes are the play time and gold digits, whose values differ
        // with the state. The hand cursor (index 0) moves after the confirm.
        for (c in intArrayOf(9494, 9500, 9600)) {
            assertTrue(seen[c]!!.oam.none { it < 46 }, "f$c OAM 0..45 differs ${seen[c]!!.oam}")
        }
        for (c in intArrayOf(9700, 9799, 9800)) {
            assertTrue(seen[c]!!.oam.none { it in 1..49 }, "f$c OAM 1..49 differs")
        }

        // SRAM: slot 0 verifies, is the last save and the global header equals s09802.
        val capSram = run.sram("s09802")
        val got = sramCopy(boot)
        assertTrue(boot.saveLib.isSaveValid(0), "slot 0 block checksum")
        assertEquals(0, boot.saveLib.readLastGameSaveId(), "last game save id")
        val header = (0 until L.HEADER_SIZE).filter { got[it] != capSram[it] }
        assertEquals(emptyList<Int>(), header, "global header vs s09802")
        // Block infos: slot 0's differ only in checksum32 (state), the suspend infos 3 and 4 only
        // in their uninitialised residue bytes (see PostChapterSaveMenuWireTest); the rest equal.
        for (i in 0 until L.BLOCK_INFO_COUNT) {
            val o = L.blockInfoOffset(i)
            val diff = (0 until L.BLOCK_INFO_SIZE).filter { got[o + it] != capSram[o + it] }
            val allowed = when (i) {
                0 -> listOf(12, 13, 14, 15)
                3, 4 -> listOf(0, 2, 3, 7, 12, 13, 14, 15)
                else -> emptyList()
            }
            assertEquals(allowed, diff, "block info $i vs s09802")
        }
        val game = (0 until L.GAME_SIZE).filter {
            got[L.GAME0_OFFSET + it] != capSram[L.GAME0_OFFSET + it]
        }
        // The game block holds state of the two plays that differ (resume save plus the skipped
        // ending vs the capture): the PlaySt (time, rankings, RN state, flags: 0..0x8F),
        // gGmMonsterRnState (0x778..0x77F), the unit and supply area around 0x84B..0x87F, three
        // bytes at 0xCAC and the chapter-stat / wmStuff tail (0xD6F..0xDAF).
        val masks = listOf(0..0x8F, 0x778..0x77F, 0x84B..0x87F, 0xCAC..0xCAE, 0xD6F..0xDAF)
        assertEquals(
            emptyList<Int>(), game.filter { d -> masks.none { d in it } },
            "game block bytes outside the masks differ from s09802",
        )

        // After the menu: GameControl takes label 6 (EXEC_BM_EXT, save_menu_type 2) to
        // StartBattleMap and waits holding the BMapMain lock at cursor 54 as at f9870 of the
        // capture; Chapter 1's ChapterIntro starts.
        repeat(120) {
            f.keys = 0
            boot.frame()
            f.frame++
        }
        assertEquals(54, gc.scrCur, "GameControl cursor after the menu")
        assertEquals(1, gc.lockCnt, "GameControl lock held by BMapMain")
        assertTrue("gProc_BMapMain" in live() && "gProcScr_ChapterIntro" in live(), "${live()}")
    }

    @Test
    fun `the save menu replay allocation per frame stays bounded`() {
        assumeTrue(AllocationMeter.supported && available(), "allocation meter / reference data")
        val run = ref.run("ram_trace_prologue_end")
        // Two passes, the minimum per frame (see PlayerPhaseAllocationTest). Unlike the player
        // phase the save menu does NOT reach zero: about 272 bytes per frame while a slot is
        // selected (f9494..9598), 320 with the confirm box and 288 in the fade-out. A guard against
        // growth, not the zero-allocation target; the allocation source is not found yet.
        val first = menuBytes(run)
        val second = menuBytes(run)
        for (window in ALLOC_WINDOWS) {
            val from = window.first
            val to = window.last
            val worst = window.maxOf { minOf(first[it], second[it]) }
            assertTrue(worst <= ALLOC_LIMIT, "f$from..f$to: up to $worst bytes in one frame")
        }
    }

    /** Bytes allocated by each frame of the menu replay, indexed by capture frame equivalent. */
    private fun menuBytes(run: ReferenceData.Run): LongArray {
        val (boot, f, gc) = start()
        boot.gpuFrame // the GPU pack path
        val bytes = LongArray(10000)
        var used = 0L
        playMenu(
            boot, f, run, gc,
            step = {
                val before = AllocationMeter.current()
                boot.frame()
                boot.pack()
                used = AllocationMeter.current() - before
            },
            onFrame = { cap -> bytes[cap] = used },
        )
        return bytes
    }

    private companion object {
        const val GAME_CONTROL = 0x085916D4
        const val START = 0x08
        const val FADE_IN_CAPTURE_FRAME = 9461
        val STALE_PALETTE = setOf(49, 50, 51, 52, 61, 62, 63, 273, 417)
        const val SAVE_MENU = "gProcScr_SaveMenuPostChapter"
        val ALLOC_WINDOWS = listOf(9494..9598, 9601..9798, 9815..9840)
        const val ALLOC_LIMIT = 512L
        val CHECK_FRAMES = setOf(9494, 9500, 9600, 9700, 9799, 9800, 9802)
        val STRAY_PROCS = listOf(
            "gProcScr_TerrainDisplay", "gProcScr_GoalDisplay", "gProcScr_UnitDisplay_MinimugBox",
            "ProcScr_GreenTextColor",
        )
    }
}
