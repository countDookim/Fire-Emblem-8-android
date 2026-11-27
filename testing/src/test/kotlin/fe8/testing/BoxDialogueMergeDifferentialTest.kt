package fe8.testing

import fe8.game.RomView
import fe8.game.text.Fe8Text
import fe8.game.ui.BoxDialogue
import fe8.game.ui.BoxDialogue8A01800State
import fe8.game.ui.BoxDialogueTextState
import fe8.game.ui.HelpBoxState
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.core.math.Interpolate
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import gbafe.render.SpriteLayers
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
 * The text proc (`ProcScr_BoxDialogueDrawTextExt`, `PC-11b2c`) is compared per frame (script
 * position, `str`, `current_line`, `unk_4a..4e`, `x_offset`, `unk_54`, `unk_55`, `unk_59`, the
 * timer once used) and the strips' pen and colour at every dump; the scrolled tiles are not
 * (no VRAM dump). Not covered: the frame drawing `sub_808ACFC` (PC-11b2c) is a hook whose arguments are checked
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
        "ResetHelpBoxInitSize", "DrawBoxDialogueText", "sub_808ACFC", "sub_808A7B0",
        "SetBoxDialogueSize", "sub_808BA60", "GetBoxDialogueSize",
        "ProcScr_BoxDialogueDrawTextExt", "gUnknown_08A01800", "sub_808B928", "sub_808B904",
        "sub_808B09C", "DialogBoxGetGlyphLen", "BoxDialogueInterpreter_Main", "sub_808B7B8",
        "sub_808B844",
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
        textSystem.resetText()
        val messages = MsgStrings(
            rom,
            MsgHuffman(
                rom,
                0x0815A72C - 0x08000000,
                word(rom, 0x0815D488 - 0x08000000) and 0x01FFFFFF,
            ),
            0x0815D48C - 0x08000000,
        )
        val layers = SpriteLayers()
        var now = FIRST
        val inputs = run.inputs
        fun newKeysAt(f: Int): Int = inputs[f] and inputs[f - 1].inv()
        val box = BoxDialogue(
            engine, textSystem, messages, newKeys = { newKeysAt(now) },
            view = RomView.ofRom(rom), layers = layers,
        )
        val waitScript = ProcScript(
            "TalkWaitForInput",
            listOf(
                ProcScripts.sleep(8),
                ProcScripts.repeat(
                    ProcFunc { if (newKeysAt(now) and 0xF3 != 0) engine.breakProc(it) },
                ),
                ProcScripts.sleep(1),
                ProcScripts.end(),
            ),
        )
        box.hooks.startTalkWaitForInput = { p, _, _ -> engine.startBlocking(waitScript, p) }
        box.hooks.textDisplaySpeed = { TEXT_SPEED }
        fun dump(step: Int) = ProcArrayDump.ofWatch(watch, step, names)
        val first = dump(FIRST).find("gProcScr_BoxDialogue")!!
        val seed = dump(FIRST + 1).find("ProcScr_MergeBoxDialogue")!!
        val drawn = ArrayList<List<Int>>()
        var closeSounds = 0
        box.hooks.playCloseSound = { closeSounds++ }
        box.hooks.frameDrawn = { x, y, w, h -> drawn += listOf(x, y, w, h) }
        val boxProc = box.startBoxDialogue(first.u32(0x2C), first.u32(0x30), first.u16(0x34))
        box.setDialogueBoxConfig(u16(run.region("r$CONF_DUMP", "ewram"), CONF + 0x42))
        var frames = 0
        var textBase = 0
        var textFrames = 0
        var textDumps = 0
        for (step in FIRST..LAST) {
            now = step
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
                val proc = engine.find(box.mergeScript)!!
                val s = proc.state as HelpBoxState
                assertEquals(row.scrCur, proc.scrCur, "merge script position at f$step")
                assertEquals(row.u16(0x48).toShort().toInt(), s.timer, "timer at f$step")
                assertEquals(row.u16(0x4A), s.timerMax, "timerMax at f$step")
                // xBox and yBox are uninitialised memory until the first frame draw.
                if (step > FIRST + 1) {
                    assertEquals(row.u16(0x30), s.xBox, "xBox at f$step")
                    assertEquals(row.u16(0x32), s.yBox, "yBox at f$step")
                }
                assertEquals(row.u16(0x40), s.wBoxInit, "wBoxInit at f$step")
                assertEquals(row.u16(0x42), s.hBoxInit, "hBoxInit at f$step")
                if (row.scrCur == 1 && step == LAST_OPEN) {
                    // Only MergeBoxDialogue2 can restore the size: clear it before the break.
                    s.wBoxInit = 0
                    s.hBoxInit = 0
                }
            }
            val textRow = d.find("ProcScr_BoxDialogueDrawTextExt")
            val textProc = engine.find(box.drawTextExtScript)
            assertEquals(textRow != null, textProc != null, "text proc exists at f$step")
            if (textRow != null && textProc != null) {
                val t = textProc.state as BoxDialogueTextState
                if (textBase == 0) textBase = textRow.u32(0x2C)
                assertEquals(textRow.scrCur, textProc.scrCur, "text script position at f$step")
                assertEquals(textRow.u32(0x2C) - textBase, t.strPos, "str at f$step")
                assertEquals(textRow.u16(0x48), t.currentLine, "current_line at f$step")
                assertEquals(textRow.u16(0x4A).toShort().toInt(), t.unk4a, "unk_4a at f$step")
                assertEquals(textRow.u16(0x4C), t.unk4c, "unk_4c at f$step")
                assertEquals(textRow.u16(0x4E), t.unk4e, "unk_4e at f$step")
                // The capture's frame f18240 ends inside DialogBoxGetGlyphLen of the first `[A]`:
                // it has cleared `x_offset` and not yet stored the width.
                if (step !in LAG_FRAMES) {
                    assertEquals(textRow.u8(0x52), t.xOffset, "x_offset at f$step")
                }
                // unk_56, unk_57 (never written in the window) and the timer before its first use
                // hold stale memory: the proc's state is not cleared when it starts.
                assertEquals(
                    listOf(0x54, 0x55, 0x59).map { textRow.u8(it) },
                    listOf(t.unk54, t.unk55, t.unk59),
                    "text proc bytes at f$step",
                )
                if (step >= FIRST_SCROLL) assertEquals(textRow.u8(0x58), t.timer, "timer at f$step")
                textFrames++
                val dumpName = "r%05d".format(step)
                if (run.dumps.containsKey(dumpName) && step > FIRST + 10) {
                    // gBoxDialogueConf.texts: pen x (+2) and colour (+3) of each strip.
                    val ewram = run.region(dumpName, "ewram")
                    for (i in 0 until 3) {
                        val a = CONF + 0x18 + 8 * i
                        assertEquals(
                            listOf(ewram[a + 2].toInt() and 0xFF, ewram[a + 3].toInt() and 0xFF),
                            listOf(box.texts[i].x, box.texts[i].colorId),
                            "text $i pen and colour at f$step",
                        )
                    }
                    textDumps++
                }
            }
            if (step == FIRST + 1) checkDrawBoxDialogueText(engine, box, dump(step))
            if (step !in LAG_FRAMES) {
                layers.clear()
                engine.run(engine.root(3))
            }
            val capDump = listOf("r%05d", "f%05d").map { it.format(step) }
                .firstOrNull { run.dumps.containsKey(it) }
            if (capDump != null && row != null) {
                assertEquals(
                    capturePool(run.region(capDump, "ewram"), run.region(capDump, "iwram")),
                    ownPool(layers),
                    "layer 2 box sprites at f$step",
                )
            }
            frames++
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
        assertTrue(textDumps >= 10, "the strips' pen and colour were compared")
        assertTrue(textFrames > 100, "the text proc was compared per frame")
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

    /** The fields `DrawBoxDialogueText` and `sub_808BA60` set, against the dump of [FIRST] + 1. */
    private fun checkDrawBoxDialogueText(engine: ProcEngine, box: BoxDialogue, d: ProcArrayDump) {
        val row = d.find("ProcScr_MergeBoxDialogue")!!
        val s = engine.find(box.mergeScript)!!.state as HelpBoxState
        val offsets = listOf(0x38, 0x3A, 0x3C, 0x3E, 0x40, 0x42, 0x44, 0x46, 0x4A, 0x4C, 0x4E)
        assertEquals(
            offsets.map { row.u16(it) },
            listOf(
                s.xBoxInit, s.yBoxInit, s.xBoxFinal, s.yBoxFinal, s.wBoxInit, s.hBoxInit,
                s.wBoxFinal, s.hBoxFinal, s.timerMax, s.msgId, s.item,
            ).map { it and 0xFFFF },
            "DrawBoxDialogueText fields",
        )
        assertEquals(0, row.u32(0x2C), "info")
        val text = d.find("gUnknown_08A01800")!!
        val t = engine.find(box.unknown08A01800Script)!!.state as BoxDialogue8A01800State
        assertEquals(
            listOf(text.u8(0x2C), text.u8(0x30), text.u16(0x5C)),
            listOf(t.unk2c, t.unk30, t.msgId),
            "sub_808BA60 fields",
        )
    }

    /** The box frame sprites use palette bank 5 of OBJ (`pad_idx` 5, bank `0x15`). */
    private fun isBoxAttr(a2: Int): Boolean = a2 and 0xF000 == 0x5000

    /** Layer 2 box entries of the capture's `sSpritePool`: oam1, oam0, oam2, object. */
    private fun capturePool(ewram: ByteArray, iwram: ByteArray): List<List<Int>> {
        val allocIt = GbaMem.u32(iwram, GbaMem.IWRAM, ADDR_G_SPRITE_ALLOC_IT)
        var addr = word(ewram, ADDR_S_SPRITE_LAYERS - GbaMem.EWRAM + 2 * ENTRY_SIZE)
        val out = ArrayList<List<Int>>()
        while (addr in ADDR_S_SPRITE_POOL until allocIt) {
            val base = addr - GbaMem.EWRAM
            if (isBoxAttr(u16(ewram, base + 8))) {
                out += listOf(
                    u16(ewram, base + 4).toShort().toInt(), u16(ewram, base + 6).toShort().toInt(),
                    u16(ewram, base + 8), word(ewram, base + 0x0C),
                )
            }
            addr = word(ewram, base)
        }
        return out
    }

    private fun ownPool(layers: SpriteLayers): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var e = layers.layers[2].next
        while (e != null && e in layers.pool) {
            if (isBoxAttr(e.oam2)) out += listOf(e.oam1, e.oam0, e.oam2, e.obj!!.id)
            e = e.next
        }
        return out
    }

    private companion object {
        const val ADDR_S_SPRITE_POOL = 0x02029D8C
        const val ADDR_S_SPRITE_LAYERS = 0x0202A58C
        const val ADDR_G_SPRITE_ALLOC_IT = 0x03004970
        const val ENTRY_SIZE = 0x10

        const val RUN = "ram_trace_newgame_easy"
        const val FIRST = 18230
        const val LAST = 18385
        const val LAST_OPEN = 18373

        /** The first frame of the page scroll (`sub_808B7B8`): the text proc's timer is used. */
        const val FIRST_SCROLL = 18300

        /** `unk_4c` of the capture's text proc: the text speed of the tutorial box. */
        const val TEXT_SPEED = 4

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
