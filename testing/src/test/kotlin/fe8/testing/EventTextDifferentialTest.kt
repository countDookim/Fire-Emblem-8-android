package fe8.testing

import fe8.game.bm.Mu
import fe8.game.event.EventConst.EV_STATE_0002
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.EventState
import fe8.game.event.EventText
import fe8.game.event.EventTextTalkHost
import fe8.game.event.ScriptMemory
import gba.core.VerifiesPort
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of the event text commands (`Event1A_TEXTSTART`, `Event1B_TEXTSHOW`,
 * `Event1D_WaitForLockTalk`, with `sub_800E210` and `sub_800E640`) against
 * `ram_trace_prologue_talk`, run through the real [EventEngine.onUpdate] and the real talk code
 * ([EventTextTalkHost] over `Talk`, `TalkBubble`, `TalkFace`).
 *
 * The script of the trace is, from `0x089EED00`: `0x1A` (TEXTSTART, sub 0) at f9864, `0x1B`
 * (TEXTSHOW, sub 0, message 0x903) at f9865, `0x1D` (wait for the talk) from f9865 until f10565
 * and `0x1B` (sub 2, text end) at f10566. The seed is the dump of f9863 (the end of the frame
 * before `0x1A` ran: engine at `0x089EED00`, no Talk proc, `sTalkStateCore` as left by the
 * previous scene). Nothing of the talk comes from a later dump: the message is decoded from the
 * ROM, the text speed is read from `gPlaySt` of f9863 and the Talk proc is started by the host.
 *
 * Per frame f9864..f10566 the event side is compared with the dump (`pEventCurrent`,
 * `evStateBits`, `evStallTimer`, `overwrittenTextSpeed`, `execType`, `activeTextType`, slots
 * 1..13) and from f9865 to f10565 the whole talk as in [TalkInterpretDifferentialTest]
 * ([TalkRig.compare]: `sTalkStateCore`, texts, font, BG0 and BG1 tilemaps, proc counts, faces).
 * f9864 covers `0x1A`, f9865 `0x1B` and `StartTalkMsg` (the command returned `CONTINUE`, the next
 * `0x1D` parked the engine on the live talk), f9866..f10564 the parked `0x1D`, f10565 its
 * completion (talk gone, `gEventSlots[0xC]` set, pc moved) and f10566 the text end: `EndTalk`,
 * `ClearTalkBubble` and the face fade-out wait of `sub_800E640` (engine pc and state bits, the
 * Talk proc count and the BG1 tilemap of that frame; the other talk-side checks are not made at
 * f10566 because the faces fade out there).
 *
 * Not modelled: the face fade-out itself (`StartFaceFadeOut` is a no-op in the rig; the
 * blocking wait proc created at f10566 is only counted), CG text and box dialogue (none exist in
 * the trace), `Event1A` with a changing type (`EndTalk` etc. on type change) and `0x1C`.
 */
class EventTextDifferentialTest {
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val romFile = File(assets, "baserom.gba")
    private val runDir = File(assets, "reference/out/ram_trace_prologue_talk")

    @Test
    fun `map display commands lock moving units once and release them once`() {
        assumeTrue(romFile.isFile && runDir.isDirectory, "ROM or reference run not present")
        val rig = TalkRig(romFile.readBytes(), runDir)
        var suspends = 0
        var resumes = 0
        val host = EventTextTalkHost(
            rig.engine, rig.talk, rig.bubble, rig.face, rig, rig,
            suspendMapDisp = { suspends++ },
            resumeMapDisp = { resumes++ },
        ) { 0 }
        val movingUnit = rig.engine.start(ProcScript("MU", listOf(ProcScripts.yield())), 3)
        movingUnit.mark = Mu.PROC_MARK_MU
        val event = EventEngineProc()
        host.disableMapDisp(event)
        host.disableMapDisp(event)
        assertEquals(1, movingUnit.lockCnt)
        assertEquals(1, suspends)
        host.enableMapDisp(event)
        host.enableMapDisp(event)
        assertEquals(0, movingUnit.lockCnt)
        assertEquals(1, resumes)
    }

    @Test
    @VerifiesPort(
        "Event1A_TEXTSTART", "Event1B_TEXTSHOW", "Event1D_WaitForLockTalk", "sub_800E210",
        "sub_800E640", "StartTalkMsg", "StartTalkExt",
    )
    fun `the first talk is started waited for and ended by the event text commands`() {
        assumeTrue(romFile.isFile && runDir.isDirectory, "ROM or reference run not present")
        val rom = romFile.readBytes()
        val trace = TalkTrace(runDir)
        for (f in SEED + 1..END) {
            assertEquals(trace.clock(f - 1) + 1, trace.clock(f), "gGameClock step at f$f (lag)")
        }
        val rig = TalkRig(rom, runDir)
        val root = trace.u32(rom, 0x0815D488 - ROM_BASE) - ROM_BASE
        val messages = MsgStrings(
            rom, MsgHuffman(rom, 0x0815A72C - ROM_BASE, root), 0x0815D48C - ROM_BASE,
        )
        val seedEw = trace.ewram(SEED)
        val speed = intArrayOf(8, 4, 1, 0)[(seedEw[PLAY_ST + 0x40].toInt() shr 5) and 3]
        rig.seedBeforeEvent(SEED, messages.buffer, speed)

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
        // Counts the calls of 0x1D per frame; 0x2F (after the text end) must not run by f10566.
        var waitCalls = 0
        val wait = events.lo[0x1D]
        events.register(0x1D, EventHandler { p ->
            waitCalls++
            wait.run(p)
        })
        events.register(0x2F, EventHandler { error("0x2F ran: the text end did not yield") })

        val engineSeed = ProcArrayDump.ofEwram(seedEw).find(STD_EVENT_ENGINE)!!
        val proc = events.create(engineSeed.u32(0x30), engineSeed.u8(0x41))
        rig.engine.run(rig.engine.root(3)) // the engine proc's first run parks on its yield
        proc.pEventIdk = engineSeed.u32(0x34)
        proc.pEventCurrent = engineSeed.u32(0x38)
        proc.evStateBits = engineSeed.u16(0x3C)
        proc.evStallTimer = engineSeed.u16(0x3E)
        proc.overwrittenTextSpeed = engineSeed.u8(0x40).toByte().toInt()
        proc.activeTextType = engineSeed.u8(0x42)
        proc.chapterIndex = engineSeed.u8(0x43)
        assertEquals(0x089EED00, proc.pEventCurrent, "seed: the engine is at the 0x1A command")
        assertEquals(EV_STATE_0002, proc.evStateBits, "seed: evStateBits")
        val iw = trace.iwram(SEED)
        for (i in 1 until 14) state.slots[i] = trace.u32(iw, SLOTS + 4 * i)

        for (f in SEED + 1..END) {
            rig.keys = trace.u16(trace.ewram(f), TalkRig.KEYS + 8)
            rig.clock = trace.clock(f)
            waitCalls = 0
            rig.engine.run(rig.engine.root(3))
            rig.engine.run(rig.engine.root(5))
            // 0x1D runs once per frame from the frame 0x1B ran (CONTINUE) until it finishes.
            assertEquals(if (f in TALK..TALK + 700) 1 else 0, waitCalls, "0x1D calls at f$f")
            if (f == TALK) {
                val bubble = (trace.dump(9870, "vram"))
                val gfx = rig.vram
                val base = 0x6000200 - 0x6000000 + rig.backgroundTileDataOffset(1)
                var written = 0
                for (i in 0 until BUBBLE_BYTES) {
                    if (gfx[base + i] == TalkRig.SENT) continue
                    written++
                    assertEquals(bubble[base + i], gfx[base + i], "bubble tile byte $i at f$f")
                }
                assertTrue(written >= 256, "InitTalk unpacked the bubble tiles: $written bytes")
                val want = trace.ewram(f).copyOfRange(TalkRig.BUF, TalkRig.BUF + 0x1000)
                val len = want.indexOf(0) + 1
                assertArrayEquals(want.copyOf(len), messages.buffer.copyOf(len), "message bytes")
            }
            if (f in TALK until END) assertEquals(null, rig.compare(f), "talk at f$f")
            if (f == END) {
                val ew = trace.ewram(f)
                assertEquals(
                    ProcArrayDump.ofEwram(ew).findAll(0x08591DE8).size,
                    rig.engine.count(host.waitForFacesScript), "face wait procs at f$f",
                )
                for (i in rig.tm1.indices) {
                    assertEquals(
                        trace.u16(ew, TalkRig.BG1_TM + 2 * i), rig.tm1[i].toInt() and 0xFFFF,
                        "BG1 tilemap cell $i at f$f",
                    )
                }
            }
            assertEquals(null, eventSide(trace, f, proc, state), "event engine at f$f")
        }
        assertEquals(0, rig.engine.procs.count { it.script === rig.talk.talkScript })
    }

    private fun eventSide(
        trace: TalkTrace,
        f: Int,
        proc: EventEngineProc,
        state: EventState,
    ): String? {
        val want = ProcArrayDump.ofEwram(trace.ewram(f)).find(STD_EVENT_ENGINE)
            ?: return "engine proc gone"
        val pairs = listOf(
            "pEventCurrent" to (want.u32(0x38) to proc.pEventCurrent),
            "evStateBits" to (want.u16(0x3C) to proc.evStateBits),
            "evStallTimer" to (want.u16(0x3E) to proc.evStallTimer),
            "overwrittenTextSpeed" to (want.u8(0x40).toByte().toInt() to proc.overwrittenTextSpeed),
            "execType" to (want.u8(0x41) to proc.execType),
            "activeTextType" to (want.u8(0x42) to proc.activeTextType),
        )
        for ((n, wg) in pairs) {
            if (wg.first != wg.second) return "$n want %X got %X".format(wg.first, wg.second)
        }
        val iw = trace.iwram(f)
        for (i in 1 until 14) {
            val w = trace.u32(iw, SLOTS + 4 * i)
            if (w != state.slots[i]) return "slot $i want %X got %X".format(w, state.slots[i])
        }
        return null
    }

    private companion object {
        const val ROM_BASE = 0x08000000
        const val SEED = 9863
        const val TALK = 9865
        const val END = 10566
        const val STD_EVENT_ENGINE = 0x08591AC0
        const val PLAY_ST = 0x2BCF0
        const val SLOTS = 0x438 + 0x80
        const val BUBBLE_BYTES = 0x400
    }
}
