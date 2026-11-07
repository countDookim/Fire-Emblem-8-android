package fe8.testing

import gba.core.VerifiesPort
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of the talk interpreter ([Talk.talkOnIdle], [Talk.talkInterpret], the pause
 * and wait-for-input procs) against `ram_trace_prologue_talk`.
 *
 * Seeding: once, from the dump of f9865 (the frame `StartTalkExt` ran: `sTalkStateCore`, the
 * message buffer, the BG0 tilemap, the frame's key state is not used). From there the test drives
 * the engine one frame per dump, feeding only the inputs the game reads (`newKeys` of
 * `sKeyStatusBuffer` and `gGameClock` of that frame's dump), and compares every later frame with
 * its own dump: all of `sTalkStateCore` (face handles by null-ness), `sTalkText[0..1]`,
 * `sTalkFont`, the BG0 tilemap, the counts of the talk-related procs per script, the Talk
 * proc's lock count and script position, and the wait proc's arrow position. At the f-dumps
 * (every 10th frame) the text strips in VRAM are compared too.
 *
 * Frames: the run has a dump for every frame 9851..10640. `gGameClock` rises by exactly 1 per
 * frame over the whole range (asserted), so there is no lag and no mid-frame dump; each dump is
 * the state at the end of its frame, after the key update (a key pressed at frame f is visible to
 * the procs of frame f, as the skip listener's reaction in the same dump shows).
 *
 * Coverage: f9866..f10565, the whole talk (Talk proc ends at f10564, its skip listener with it).
 * The first frame that does not match is f10566: the caller's clean-up after the talk (face
 * fade-out, event engine) resets `lineActive`, which is not the talk's own code, so the run
 * stops at f10565. There is no earlier divergence, so no face code needed the M6-35 procs
 * beyond the stand-ins below.
 *
 * Bubble and shift-clear procs are the real ones ([TalkBubble]); the BG1 tilemap is compared too.
 * Talk faces are the real ones ([TalkFace]): handles, the face procs' position, display bits,
 * oam2, id and layer, and each face's tiles at its first f-dump. Not compared: face palettes (the
 * fade-in procs are not ported), blink and mouth animation, face sprites.
 * Not ported here (M6-35): the blink/fade procs, temporary lock, skip listener and
 * `GetStrTalkLen`. The [Rig] hosts them with transcriptions of the decomp (durations, state
 * writes), not with trace values; their start frames and lifetimes are compared with the trace
 * like the rest. Not
 * modelled: sprites (the wait arrow's `PutSprite` is only counted), sound, palettes, windows,
 * the blend registers.
 */
class TalkInterpretDifferentialTest {
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val romFile = File(assets, "baserom.gba")
    private val runDir = File(assets, "reference/out/ram_trace_prologue_talk")

    @Test
    @VerifiesPort(
        "TalkInterpret", "Talk_OnIdle", "TalkWaitForInput_OnIdle", "StartTalkWaitForInput",
        "Talk_OnEnd", "ClearPutTalkText", "StartTalkOpen", "TalkOpen_PutTalkBubble",
        "TalkOpen_OnIdle", "PutTalkBubble", "PutTalkBubbleTm", "PutTalkBubbleTail",
        "StartOpenTalkBubble", "TalkBubbleOpen_OnIdle", "ClearTalkBubble",
        "TalkShiftClear_OnInit", "TalkShiftClear_OnIdle", "TalkLoadFace", "SetTalkFaceLayer",
        "MoveTalkFace", "IsTalkFaceMoving", "StartTalkFaceMove", "GetFaceIdByXPos",
        "TalkFaceMove_OnInit", "TalkFaceMove_OnIdle", "SetTalkFaceDisp",
    )
    fun `the first talk runs frame by frame as traced`() {
        assumeTrue(romFile.isFile && runDir.isDirectory, "ROM or reference run not present")
        val rom = romFile.readBytes()
        val rig = TalkRig(rom, runDir)
        for (f in TalkRig.FIRST + 1..TalkRig.LAST) {
            assertEquals(clock(f - 1) + 1, clock(f), "gGameClock step at f$f (lag)")
        }
        rig.seed(TalkRig.FIRST)
        for (f in TalkRig.FIRST + 1..TalkRig.LAST) {
            rig.keys = u16(ewram(f), TalkRig.KEYS + 8)
            rig.clock = clock(f)
            rig.engine.run(rig.engine.root(3))
            rig.engine.run(rig.engine.root(5))
            val err = rig.compare(f)
            assertEquals(null, err, "f$f")
        }
        // The arrow is drawn on every frame the wait proc is past its first sleep and before its
        // last, which the dumps show as script position 3 (the repeat command).
        var drawn = 0
        for (f in TalkRig.FIRST + 1..TalkRig.LAST) {
            if (ProcArrayDump.ofEwram(ewram(f)).find(TalkRig.ADDR_WAIT)?.scrCur == 3) drawn++
        }
        assertTrue(drawn > 100, "trace shows the arrow: $drawn")
        assertEquals(drawn, rig.waitSprites, "PutSprite calls")
        assertEquals(0, rig.engine.procs.count { it.script === rig.talk.talkScript })
    }

    private fun iwram(f: Int) = trace.iwram(f)

    private fun ewram(f: Int) = trace.ewram(f)

    private fun clock(f: Int) = trace.clock(f)

    private fun u16(b: ByteArray, o: Int) = trace.u16(b, o)

    private val trace = TalkTrace(runDir)
}
