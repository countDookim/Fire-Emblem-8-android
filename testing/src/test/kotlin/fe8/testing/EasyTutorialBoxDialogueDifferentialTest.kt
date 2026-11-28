package fe8.testing

import fe8.game.RomView
import fe8.game.text.Fe8Text
import fe8.game.ui.BoxDialogue
import fe8.game.ui.BoxDialogueTextState
import fe8.game.ui.HelpBoxState
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
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
import org.junit.jupiter.api.Timeout

/**
 * `PC-10e` against `ram_trace_newgame_easy`: the proc tree of the three tutorial boxes
 * (`f18230..18384`, `f18561..18743`, `f18810..18983`) per frame, from the box start to the
 * last frame of the box proc. Compared per frame: which of `gProcScr_BoxDialogue`,
 * `ProcScr_MergeBoxDialogue`, `ProcScr_BoxDialogueDrawTextExt`, `ProcScr_TalkBoxIdle`,
 * `gUnknown_08A01800` and `gProcScr_TalkWaitForInput` exist and their script positions; the text
 * proc's cursor fields; and the page waits (`[A]` wait: the wait proc exists from the frame of
 * `[A]` until the A press, `f18240..18271`, `f18330..18361`, `f18571..18601`,
 * `f18690..18721`, and in the third box). The wait proc here is a stand-in with the script of
 * `gProcScr_TalkWaitForInput` (its own port is tested with the scene `Talk`). The A presses
 * come from the capture's recorded input.
 */
@Timeout(300)
class EasyTutorialBoxDialogueDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun word(b: ByteArray, o: Int): Int =
        (0..3).fold(0) { acc, i -> acc or ((b[o + i].toInt() and 0xFF) shl (8 * i)) }

    private fun u16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    @Test
    @VerifiesPort(
        "gProcScr_BoxDialogue", "ProcScr_MergeBoxDialogue", "ProcScr_BoxDialogueDrawTextExt",
        "ProcScr_TalkBoxIdle", "BoxDialogueInterpreter_Main", "sub_808B904", "sub_808B7B8",
        "sub_808B844", "sub_808A87C", "sub_808A8AC", "sub_808A9F0",
    )
    fun `third box proc tree matches f18810 to f18983`() = window(18810, 18983, 18811)

    @Test
    @VerifiesPort(
        "gProcScr_BoxDialogue", "ProcScr_MergeBoxDialogue", "ProcScr_BoxDialogueDrawTextExt",
        "ProcScr_TalkBoxIdle", "BoxDialogueInterpreter_Main",
    )
    fun `second box proc tree matches f18561 to f18743`() = window(18561, 18743, 18562)

    @Test
    @VerifiesPort(
        "gProcScr_BoxDialogue", "ProcScr_MergeBoxDialogue", "ProcScr_BoxDialogueDrawTextExt",
        "ProcScr_TalkBoxIdle", "BoxDialogueInterpreter_Main",
    )
    fun `first box proc tree matches f18230 to f18384`() = window(18230, 18384, 18240)

    private fun window(first: Int, last: Int, confDump: Int) {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(romFile.isFile, "ROM not present")
        val run = ref.run(RUN)
        val confName = "r$confDump"
        assumeTrue(run.dumps.containsKey(confName), "no $confName dump")
        val watch = run.watch("procs")
        val rom = RomView.ofRom(romFile.readBytes()).wholeRomBytes()
        val engine = ProcEngine()
        val textSystem = TextSystem(rom, TextVram(), Fe8Text.CONFIG)
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
        var now = first
        val inputs = run.inputs
        fun newKeysAt(f: Int): Int = inputs[f] and inputs[f - 1].inv()
        val box = BoxDialogue(
            engine, textSystem, messages, newKeys = { newKeysAt(now) },
            view = RomView.ofRom(rom), layers = layers,
        )
        val waitScript = ProcScript(
            "gProcScr_TalkWaitForInput",
            listOf(
                ProcScripts.mark(5),
                ProcScripts.sleep(8),
                ProcScripts.repeat(
                    ProcFunc { if (newKeysAt(now) and 0xF3 != 0) engine.breakProc(it) },
                ),
                ProcScripts.sleep(1),
                ProcScripts.end(),
            ),
        )
        box.hooks.startTalkWaitForInput = { p, _, _ -> engine.startBlocking(waitScript, p) }
        fun dump(step: Int) = ProcArrayDump.ofWatch(watch, step, names)
        // unk_4c of the first text proc row in its loop is the text speed of the box.
        val speed = (first..last).firstNotNullOf {
            dump(it).find("ProcScr_BoxDialogueDrawTextExt")?.takeIf { r -> r.scrCur == 4 }
                ?.u16(0x4C)
        }
        box.hooks.textDisplaySpeed = { speed }
        val start = dump(first).find("gProcScr_BoxDialogue")!!
        val boxProc = box.startBoxDialogue(start.u32(0x2C), start.u32(0x30), start.u16(0x34))
        box.setDialogueBoxConfig(u16(run.region(confName, "ewram"), CONF + 0x42))
        var textBase = 0
        var waitFrames = 0
        var textFrames = 0
        for (step in first..last) {
            now = step
            val d = dump(step)
            val boxRow = d.find("gProcScr_BoxDialogue")
            assertEquals(boxRow != null, engine.count(box.script) == 1, "box proc at f$step")
            if (boxRow != null) {
                assertEquals(boxRow.scrCur, boxProc.scrCur, "box script position at f$step")
            }
            assertEquals(
                d.find("ProcScr_TalkBoxIdle") != null,
                engine.count(box.talkBoxIdleScript) == 1,
                "idle proc at f$step",
            )
            assertEquals(
                d.find("gUnknown_08A01800") != null,
                engine.count(box.unknown08A01800Script) == 1,
                "gUnknown_08A01800 at f$step",
            )
            val merge = d.find("ProcScr_MergeBoxDialogue")
            val mergeProc = engine.find(box.mergeScript)
            assertEquals(merge != null, mergeProc != null, "merge proc at f$step")
            if (merge != null && mergeProc != null) {
                assertEquals(merge.scrCur, mergeProc.scrCur, "merge script position at f$step")
                val s = mergeProc.state as HelpBoxState
                assertEquals(merge.u16(0x48).toShort().toInt(), s.timer, "merge timer at f$step")
            }
            var skipRest = false
            val textRow = d.find("ProcScr_BoxDialogueDrawTextExt")
            val textProc = engine.find(box.drawTextExtScript)
            assertEquals(textRow != null, textProc != null, "text proc at f$step")
            if (textRow != null && textProc != null) {
                if (textBase == 0) {
                    textBase = textRow.u32(0x2C)
                }
                val t = textProc.state as BoxDialogueTextState
                assertEquals(textRow.scrCur, textProc.scrCur, "text script position at f$step")
                if (step == MID_CALL_FRAME) {
                    // The snapshot caught the A-press call (up to 0x80 characters) half done:
                    // the drawn prefix of the page is shorter than what the finished call drew.
                    val cap = textRow.u32(0x2C) - textBase
                    assertTrue(cap in 1 until t.strPos, "mid-call str $cap < ${t.strPos} at f$step")
                    assertEquals(textRow.u16(0x4E), t.unk4e, "unk_4e at f$step")
                    textFrames++
                    skipRest = true
                }
                if (!skipRest) assertEquals(textRow.u32(0x2C) - textBase, t.strPos, "str at f$step")
                if (!skipRest) {
                    assertEquals(textRow.u16(0x48), t.currentLine, "current_line at f$step")
                    assertEquals(textRow.u16(0x4E), t.unk4e, "unk_4e at f$step")
                    // x_offset is stale memory on the first frame (the state is not cleared at
                    // start).
                    if (textFrames > 0 && step != LAG_FRAME) {
                        assertEquals(textRow.u8(0x52), t.xOffset, "x_offset at f$step")
                    }
                    textFrames++
                }
            }
            val wait = d.find("gProcScr_TalkWaitForInput")
            val waitProc = engine.find(waitScript)
            if (step != MID_CALL_FRAME) {
                assertEquals(wait != null, waitProc != null, "page wait proc at f$step")
            }
            if (step == MID_CALL_FRAME) {
                // The [A] that starts the wait is the end of the call the snapshot caught half done.
                assertEquals(null, wait, "capture has no page wait yet at f$step")
            } else if (wait != null && waitProc != null) {
                assertEquals(wait.scrCur, waitProc.scrCur, "page wait position at f$step")
                waitFrames++
            }
            if (step != LAG_FRAME && step != MID_CALL_FRAME) {
                layers.clear()
                engine.run(engine.root(3))
            }
        }
        assertTrue(textFrames > 100, "the text proc was compared per frame")
        assertTrue(waitFrames >= 50, "the page waits were compared")
        assertEquals(0, engine.count(box.script), "the box proc has ended")
    }

    private companion object {
        const val RUN = "ram_trace_newgame_easy"

        /** The capture's main loop skipped a frame here (the proc tree did not run). */
        const val LAG_FRAME = 18240

        /**
         * The A press at f18569 starts a call of `BoxDialogueInterpreter_Main` with the 0x80
         * character budget. The capture's f18570 snapshot is in the middle of it (the drawing
         * overran the vblank: `gGameClock` still ticks once per vblank, f18569..18573 =
         * 17439..17443, and the page is 38 chars in at f18570 and 52 at f18571, which the
         * budget-1 slow path of f18571 (`unk_4e` = 1, no keys, `unk_4a` timer) cannot draw).
         * The call ended during the next vblank period, so that frame's logic run is missing: the
         * merge timer at f18571 is one lower than after two runs. The port runs once for both.
         */
        const val MID_CALL_FRAME = 18570
        const val CONF = 0x0203E7E8 - 0x02000000
    }
}
