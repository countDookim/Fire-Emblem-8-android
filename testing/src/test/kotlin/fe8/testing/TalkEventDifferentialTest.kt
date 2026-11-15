package fe8.testing

import fe8.game.event.EventConst.EV_STATE_0002
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.EventState
import fe8.game.event.EventText
import fe8.game.event.EventTextTalkHost
import fe8.game.event.ScriptMemory
import fe8.game.scene.TalkFaceHPos
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The talk of an event in the Prologue's Normal-mode captures (EP-11b), run through the real
 * event engine ([EventEngine.onUpdate]) with the text commands ([EventText]) over the real talk
 * code ([EventTextTalkHost]: `Talk`, `TalkBubble`, `TalkFace`), the way [EventTextDifferentialTest]
 * does it for the first talk.
 *
 * Both windows run the same four commands: `TEXTSTART` (0x1A), `TEXTSHOW` (0x1B, one message),
 * the wait for the talk (0x1D) and the text end (`TEXTSHOW` 2).
 * - The boss turn event of `ram_trace_prologue_clear`: seed step 2964 (the engine is parked on
 *   `TEXTSTART`), `TEXTSHOW` at 2966 (the Talk proc appears), the talk until 3704, 0x1D completes
 *   at 3705, the text end at 3706.
 * - The Soldiers' dialogue of `ram_trace_prologue_enemy_phase`: seed step 2463, the Talk proc at
 *   2465, ends at 2704, 0x1D completes at 2705, the text end at 2706.
 *
 * Start state: the `sTalkStateCore` and the BG tilemaps of the seed step's dump, the text speed of
 * `gPlaySt` there, the engine proc and the event slots of the seed step. The message is decoded
 * from the ROM and the Talk proc is started by the host. Inputs: the captured key mask per frame.
 *
 * Per step compared with the `procs` watch: the engine's `pEventCurrent`, `evStateBits`,
 * `evStallTimer`, `execType`, and the procs of the talk ([TalkRig.compareProcs]: Talk, skip
 * listener, open, bubble, shift, wait and lock procs by script and script position, face procs by
 * display bits, position, palette slot and face id). At every dumped step the whole talk of
 * [TalkRig.compare] (`sTalkStateCore`, texts, font, BG0 and BG1 tilemaps, face tiles and the
 * event slots) and the key and clock inputs are compared. At the text end the face wait proc
 * exists (`FaceExists`).
 *
 * Not modelled (EP-11d, EP-11f, EP-11h): the palette fade procs of face fade-in and fade-out
 * (EP-11f), and the music procs of the event; the delayed face end (`EndFaceIn8Frames`) and the
 * face wait (`_WhileFace6CExists`) are verified up to the wait finish.
 */
class TalkEventDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val romFile = File(assets, "baserom.gba")

    @Test
    @VerifiesPort(
        "StartTalkExt", "GetTalkFaceHPos", "GetTalkChoiceResult", "IsTalkActive", "FaceExists",
        "FindFreeFaceSlot", "GetPortraitData", "GetFaceDisplayBits", "EndFaceIn8Frames",
        "EndFacePtr", "_WhileFace6CExists",
    )
    fun `the boss turn event talk matches the captured procs and talk state per step`() {
        run(Window("ram_trace_prologue_clear", 2964, 2966, 3706, "f02960.io.bin", 3718))
    }

    @Test
    @VerifiesPort(
        "StartTalkExt", "GetTalkFaceHPos", "GetTalkChoiceResult", "IsTalkActive", "FaceExists",
        "FindFreeFaceSlot", "GetPortraitData", "GetFaceDisplayBits", "EndFaceIn8Frames",
        "EndFacePtr", "_WhileFace6CExists",
    )
    fun `the soldiers dialogue in the enemy phase matches the captured procs and talk state`() {
        run(Window("ram_trace_prologue_enemy_phase", 2463, 2465, 2706, "f02460.io.bin", 2718))
    }

    @Test
    fun `the face columns are the table and the fight event columns`() {
        val table = intArrayOf(3, 6, 9, 21, 24, 27, -8, 38)
        for (slot in 0 until 8) {
            assertEquals(table[slot], TalkFaceHPos.of(slot, false), "slot $slot")
            assertEquals(if (slot < 3) 4 else 26, TalkFaceHPos.of(slot, true), "fight slot $slot")
        }
    }

    /**
     * [seed] is the step the engine is parked on `TEXTSTART` after, [talk] the step `TEXTSHOW`
     * starts the Talk proc, [end] the step of the text end.
     */
    private class Window(
        val run: String,
        val seed: Int,
        val talk: Int,
        val end: Int,
        val ioDump: String,
        /** The last step compared: the wait for the faces has ended, the event goes on. */
        val finish: Int,
    )

    private fun run(w: Window) {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val run = ref.run(w.run)
        assumeTrue(run.hasWatch("procs"), "${w.run} has no procs watch")
        val rom = romFile.readBytes()
        val runDir = File(assets, "reference/out/${w.run}")
        val trace = TalkTrace(runDir)
        val procs = run.watch("procs")
        val names = ProcArrayDump.romNames()
        fun at(step: Int) = ProcArrayDump.ofWatch(procs, step, names)

        val rig = TalkRig(rom, runDir)
        val root = trace.u32(rom, 0x0815D488 - ROM_BASE) - ROM_BASE
        val messages = MsgStrings(
            rom, MsgHuffman(rom, 0x0815A72C - ROM_BASE, root), 0x0815D48C - ROM_BASE,
        )
        val seedEw = trace.ewram(w.seed)
        val speed = intArrayOf(8, 4, 1, 0)[(seedEw[PLAY_ST + 0x40].toInt() shr 5) and 3]
        rig.seedBeforeEvent(w.seed, messages.buffer, speed, w.ioDump)

        val state = EventState()
        val words = ScriptMemory { a ->
            val o = a - ROM_BASE
            (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8)
        }
        val events = EventEngine(rig.engine, state, words)
        val host = EventTextTalkHost(
            rig.engine, rig.talk, rig.bubble, rig.face, rig, rig,
        ) { msg ->
            messages.getStringFromIndex(msg)
            TalkRig.MSG_ADDR
        }
        EventText(state, words, host).registerOn(events)
        var waitCalls = 0
        val wait = events.lo[0x1D]
        events.register(0x1D, EventHandler { p ->
            waitCalls++
            wait.run(p)
        })
        events.register(0x2F, EventHandler { error("0x2F ran: the text end did not yield") })

        val engineSeed = at(w.seed).find(STD_EVENT_ENGINE)!!
        val proc = events.create(engineSeed.u32(0x30), engineSeed.u8(0x41))
        rig.engine.run(rig.engine.root(3)) // the engine proc's first run parks on its yield
        proc.pEventIdk = engineSeed.u32(0x34)
        proc.pEventCurrent = engineSeed.u32(0x38)
        proc.evStateBits = engineSeed.u16(0x3C)
        proc.evStallTimer = engineSeed.u16(0x3E)
        proc.overwrittenTextSpeed = engineSeed.u8(0x40).toByte().toInt()
        proc.activeTextType = engineSeed.u8(0x42)
        proc.chapterIndex = engineSeed.u8(0x43)
        assertEquals(EV_STATE_0002, proc.evStateBits, "seed: evStateBits")
        val seedIw = trace.iwram(w.seed)
        for (i in 1 until 14) state.slots[i] = trace.u32(seedIw, SLOTS + 4 * i)

        val inputs = run.inputs
        val seedClock = trace.clock(w.seed)
        var compared = 0
        var oamCompared = 0
        for (f in w.seed + 1..w.finish) {
            val keys = inputs[f - 1] and inputs[f - 2].inv() and 0x3FF
            val dumped = hasDump(runDir, f)
            if (dumped) {
                assertEquals(keys, trace.u16(trace.ewram(f), TalkRig.KEYS + 8), "newKeys at f$f")
                assertEquals(seedClock + (f - w.seed), trace.clock(f), "gGameClock at f$f")
            }
            rig.keys = keys
            rig.clock = seedClock + (f - w.seed)
            waitCalls = 0
            rig.clearFaceDraws()
            rig.engine.run(rig.engine.root(3))
            rig.engine.run(rig.engine.root(5))
            assertEquals(if (f in w.talk until w.end) 1 else 0, waitCalls, "0x1D calls at f$f")

            val want = at(f)
            val wantEngine = want.find(STD_EVENT_ENGINE) ?: error("engine proc gone at f$f")
            assertEquals(wantEngine.u32(0x38), proc.pEventCurrent, "pEventCurrent at f$f")
            assertEquals(wantEngine.u16(0x3C), proc.evStateBits, "evStateBits at f$f")
            assertEquals(wantEngine.u16(0x3E), proc.evStallTimer, "evStallTimer at f$f")
            assertEquals(wantEngine.u8(0x41), proc.execType, "execType at f$f")
            assertEquals(null, rig.compareProcs(want), "talk procs at f$f")
            if (f >= w.talk) {
                // The wait for the faces (`_WhileFace6CExists`) starts at the text end and ends
                // when the last face is gone.
                assertEquals(
                    want.findAll(FACE_WAIT).size, rig.engine.count(host.waitForFacesScript),
                    "face wait procs at f$f",
                )
                assertEquals(
                    want.findAll(FACE_SCRIPT).size, rig.engine.count(rig.face.faceScript),
                    "face procs at f$f",
                )
                if (hasOam(runDir, f) && want.findAll(FACE_SCRIPT).isNotEmpty()) {
                    val oam = File(runDir, "f%05d.oam.bin".format(f)).readBytes()
                    assertEquals(null, rig.compareFaceOam(oam), "face OAM at f$f")
                    oamCompared++
                }
            }
            if (dumped) {
                val iw = trace.iwram(f)
                rig.lcg.set(trace.u32(iw, 8))
                for (i in 1 until 14) {
                    assertEquals(trace.u32(iw, SLOTS + 4 * i), state.slots[i], "slot $i at f$f")
                }
                if (f in w.talk until w.end) assertEquals(null, rig.compare(f), "talk at f$f")
                compared++
            }
        }
        assertTrue(compared > 20, "dumped steps compared: $compared")
        assertTrue(oamCompared > 3, "OAM dumps compared: $oamCompared")
        // The wait ended as in the capture: no face, no face end proc, no wait proc, and the
        // engine proc is past the wait.
        assertEquals(0, rig.engine.count(host.waitForFacesScript), "the wait for the faces")
        assertEquals(0, rig.engine.count(rig.face.faceEndScript), "the face end procs")
        assertEquals(false, host.faceExists(), "FaceExists at f${w.finish}")
        assertEquals(0, rig.engine.procs.count { it.script === rig.talk.talkScript })
    }

    private fun hasOam(runDir: File, f: Int): Boolean =
        File(runDir, "f%05d.oam.bin".format(f)).isFile

    private fun hasDump(runDir: File, f: Int): Boolean =
        listOf("r", "f").any { File(runDir, "%s%05d.ewram.bin".format(it, f)).isFile } &&
            listOf("r", "f").any { File(runDir, "%s%05d.iwram.bin".format(it, f)).isFile }

    private companion object {
        const val ROM_BASE = 0x08000000
        const val STD_EVENT_ENGINE = 0x08591AC0
        const val FACE_WAIT = 0x08591DE8
        const val FACE_SCRIPT = 0x08591154
        const val PLAY_ST = 0x2BCF0
        const val SLOTS = 0x438 + 0x80
    }
}
