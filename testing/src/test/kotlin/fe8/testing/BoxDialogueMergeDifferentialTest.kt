package fe8.testing

import fe8.game.RomView
import fe8.game.text.Fe8Text
import fe8.game.ui.BoxDialogue
import fe8.game.ui.HelpBoxState
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.math.Interpolate
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `PC-11b2b` against `ram_trace_newgame_easy`: the life of the tutorial box from `f18230` to
 * `f18385`. The merge proc (`ProcScr_MergeBoxDialogue`, `f18231..18378`) is compared per frame
 * with the capture: script position, `timer`, `timerMax`, `xBox`, `yBox` and `wBoxInit`/`hBoxInit`
 * (reset by `MergeBoxDialogue2`), as are the box proc (`f18230..18384`) and `ProcScr_TalkBoxIdle`
 * (ended by `MergeBoxDialogue3` at `f18378`). The teardown (`sub_808A8AC` calling `sub_808A9F0`,
 * `sub_808BAA4`) clears the text strips, compared with the VRAM dump of `f18550` for the
 * strips 0, 1 and 4.
 *
 * Not covered: the frame drawing `sub_808ACFC` (PC-11b2c) is a hook whose arguments are checked
 * against the interpolation formula only; `DrawBoxDialogueText` is a hook that starts the merge
 * proc and seeds the fields it would set (final position and size from the capture row);
 * `sub_808BB74` and the pressed-key arm of `sub_808A87C` are not run in the window.
 */
class BoxDialogueMergeDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun romBytes(): ByteArray {
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(romFile.isFile, "ROM not present")
        return RomView.ofRom(romFile.readBytes()).wholeRomBytes()
    }

    private fun word(b: ByteArray, o: Int): Int =
        (0..3).fold(0) { acc, i -> acc or ((b[o + i].toInt() and 0xFF) shl (8 * i)) }

    private fun u16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    @Test
    @VerifiesPort(
        "ProcScr_MergeBoxDialogue", "MergeBoxDialogue1", "MergeBoxDialogue2", "MergeBoxDialogue3",
        "sub_808A8E4", "sub_808A9F0", "sub_808BAA4", "sub_808A8AC", "gProcScr_BoxDialogue",
        "ResetHelpBoxInitSize",
    )
    fun `box and merge procs run f18230 to f18385 like the capture`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val run = ref.run(RUN)
        assumeTrue(run.dumps.containsKey("r$CONF_DUMP"), "no r$CONF_DUMP dump")
        assumeTrue(run.dumps.containsKey("f$VRAM_DUMP"), "no f$VRAM_DUMP dump")
        val watch = run.watch("procs")
        val rom = romBytes()
        val engine = ProcEngine()
        val vram = TextVram()
        val textSystem = TextSystem(rom, vram, Fe8Text.CONFIG)
        val messages = MsgStrings(
            rom,
            MsgHuffman(
                rom,
                0x0815A72C - 0x08000000,
                word(rom, 0x0815D488 - 0x08000000) and 0x01FFFFFF,
            ),
            0x0815D48C - 0x08000000,
        )
        val box = BoxDialogue(engine, textSystem, messages)
        fun dump(step: Int) = ProcArrayDump.ofWatch(watch, step, names)
        val first = dump(FIRST).find("gProcScr_BoxDialogue")!!
        val seed = dump(FIRST + 1).find("ProcScr_MergeBoxDialogue")!!
        val drawn = ArrayList<List<Int>>()
        var merge: Proc? = null
        box.hooks.drawBoxDialogueText = { _, _, _ ->
            // The rest of DrawBoxDialogueText (PC-11b2c) fills the fields from the layout; the
            // test takes them from the capture row.
            val proc = engine.start(box.mergeScript, 3)
            val s = proc.state as HelpBoxState
            s.xBox = seed.u16(0x30)
            s.yBox = seed.u16(0x32)
            s.xBoxFinal = seed.u16(0x3C)
            s.yBoxFinal = seed.u16(0x3E)
            s.wBoxFinal = seed.u16(0x44)
            s.hBoxFinal = seed.u16(0x46)
            s.wBoxInit = seed.u16(0x40)
            s.hBoxInit = seed.u16(0x42)
            s.timer = 0
            s.timerMax = seed.u16(0x4A)
            merge = proc
        }
        var closeSounds = 0
        box.hooks.playCloseSound = { closeSounds++ }
        box.hooks.drawBoxFrame = { x, y, w, h -> drawn += listOf(x, y, w, h) }
        val boxProc = box.startBoxDialogue(first.u32(0x2C), first.u32(0x30), first.u16(0x34))
        box.setDialogueBoxConfig(u16(run.region("r$CONF_DUMP", "ewram"), CONF + 0x42))
        var frames = 0
        for (step in FIRST..LAST) {
            val d = dump(step)
            val boxRow = d.find("gProcScr_BoxDialogue")
            assertEquals(boxRow != null, engine.count(box.script) == 1, "box proc exists at f$step")
            if (boxRow != null) {
                assertEquals(boxRow.scrCur, boxProc.scrCur, "box script position at f$step")
            }
            val idle = d.find("ProcScr_TalkBoxIdle")
            assertEquals(idle != null, engine.count(box.talkBoxIdleScript) == 1, "idle at f$step")
            val row = d.find("ProcScr_MergeBoxDialogue")
            assertEquals(row != null, engine.count(box.mergeScript) == 1, "merge exists at f$step")
            val before = drawn.size
            if (row != null) {
                val proc = merge!!
                val s = proc.state as HelpBoxState
                assertEquals(row.scrCur, proc.scrCur, "merge script position at f$step")
                assertEquals(row.u16(0x48).toShort().toInt(), s.timer, "timer at f$step")
                assertEquals(row.u16(0x4A), s.timerMax, "timerMax at f$step")
                assertEquals(row.u16(0x30), s.xBox, "xBox at f$step")
                assertEquals(row.u16(0x32), s.yBox, "yBox at f$step")
                assertEquals(row.u16(0x40), s.wBoxInit, "wBoxInit at f$step")
                assertEquals(row.u16(0x42), s.hBoxInit, "hBoxInit at f$step")
                if (row.scrCur == 1 && step == LAST_OPEN) {
                    // Only MergeBoxDialogue2 can restore the size: clear it before the break.
                    s.wBoxInit = 0
                    s.hBoxInit = 0
                }
            }
            if (step !in LAG_FRAMES) engine.run(engine.root(3))
            frames++
            // The tutorial text proc (not ported) jumps the box proc to label 3 after the box ran.
            val next = dump(step + 1).find("gProcScr_BoxDialogue")
            if (boxRow != null && next != null && boxRow.scrCur == WAIT_SCR &&
                next.scrCur == LABEL3_SCR
            ) {
                engine.goto(boxProc, 3)
            }
            if (row != null && step !in LAG_FRAMES) {
                val t = row.u16(0x48).toShort().toInt()
                val next = dump(step + 1).find("ProcScr_MergeBoxDialogue")
                if (row.scrCur == 3 && t < 0) {
                    assertEquals(before, drawn.size, "no frame draw at f$step: the proc ends")
                } else {
                    assertEquals(1, drawn.size - before, "one frame draw at f$step")
                    val closing = row.scrCur == 3 || next!!.scrCur == 3
                    val method = if (closing) Interpolate.LINEAR else Interpolate.RCUBIC
                    // MergeBoxDialogue2 runs in the frame of the break, before the first draw.
                    val tDraw = when {
                        row.scrCur == 3 -> t
                        closing -> next!!.u16(0x48).toShort().toInt() + 1
                        else -> t
                    }
                    val tMax = if (closing && row.scrCur != 3) next!!.u16(0x4A) else row.u16(0x4A)
                    val wi = if (closing) 32 else row.u16(0x40)
                    val hi = if (closing) 16 else row.u16(0x42)
                    val w = Interpolate.interpolate(method, wi, row.u16(0x44), tDraw, tMax)
                    val h = Interpolate.interpolate(method, hi, row.u16(0x46), tDraw, tMax)
                    assertEquals(
                        listOf(row.u16(0x3C), row.u16(0x3E), w, h),
                        drawn.last(),
                        "frame draw at f$step",
                    )
                }
            }
        }
        assertEquals(LAST - FIRST + 1, frames)
        assertEquals(1, closeSounds, "close sound of config 0x110")
        // The strips cleared by sub_808BAA4 hold 0x44444444 in the capture after the box closed.
        val capVram = run.region("f$VRAM_DUMP", "vram")
        textSystem.setTextFont(box.font)
        // Strips 2 and 3 hold other drawing in the f18550 dump (written after the teardown).
        for ((i, t) in box.texts.withIndex()) {
            if (i == 2 || i == 3) continue
            val base = textSystem.getTextDrawDest(t.apply { x = 0 }) - 0x06000000
            for (row in 0..1) {
                for (o in 0 until 0x360 step 4) {
                    val a = base + row * 0x400 + o
                    assertEquals(word(capVram, a), word(vram.vram, a), "strip $i row $row +$o")
                }
            }
        }
    }

    private companion object {
        const val RUN = "ram_trace_newgame_easy"
        const val FIRST = 18230
        const val LAST = 18385
        const val LAST_OPEN = 18373

        /**
         * The merge timer stays 9 from f18240 to f18241: the capture's main loop skipped one frame
         * (the proc tree did not run) while `ProcScr_BoxDialogueDrawTextExt` drew the first page.
         */
        val LAG_FRAMES = setOf(18240)
        const val CONF_DUMP = 18240
        const val VRAM_DUMP = 18550
        const val CONF = 0x0203E7E8 - 0x02000000

        /** Script positions of `gProcScr_BoxDialogue` in the wait loop and blocked. */
        const val WAIT_SCR = 4
        const val LABEL3_SCR = 6
    }
}
