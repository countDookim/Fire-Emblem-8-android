package fe8.testing

import fe8.game.scene.Talk
import fe8.game.scene.TalkBubble
import fe8.game.scene.TalkBubbleHost
import fe8.game.scene.TalkFlags
import fe8.game.scene.TalkHost
import fe8.game.scene.TalkState
import fe8.game.text.Fe8Text
import gba.core.VerifiesPort
import gba.data.Lz77
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.yield
import gbafe.text.Text
import gbafe.text.TextHost
import gbafe.text.TextSystem
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
 * Not ported here (M6-35): face procs, temporary lock, skip listener and `GetStrTalkLen`. The
 * [Rig] hosts them with transcriptions of the decomp (durations, state writes), not with trace
 * values; their start frames and lifetimes are compared with the trace like the rest. Not
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
        "TalkShiftClear_OnInit", "TalkShiftClear_OnIdle",
    )
    fun `the first talk runs frame by frame as traced`() {
        assumeTrue(romFile.isFile && runDir.isDirectory, "ROM or reference run not present")
        val rom = romFile.readBytes()
        val rig = Rig(rom, runDir)
        for (f in FIRST + 1..LAST) {
            assertEquals(clock(f - 1) + 1, clock(f), "gGameClock step at f$f (lag)")
        }
        rig.seed(FIRST)
        for (f in FIRST + 1..LAST) {
            rig.keys = u16(ewram(f), KEYS + 8)
            rig.clock = clock(f)
            rig.engine.run(rig.engine.root(3))
            val err = compare(rig, f)
            assertEquals(null, err, "f$f")
        }
        // The arrow is drawn on every frame the wait proc is past its first sleep and before its
        // last, which the dumps show as script position 3 (the repeat command).
        var drawn = 0
        for (f in FIRST + 1..LAST) {
            if (ProcArrayDump.ofEwram(ewram(f)).find(ADDR_WAIT)?.scrCur == 3) drawn++
        }
        assertTrue(drawn > 100, "trace shows the arrow: $drawn")
        assertEquals(drawn, rig.waitSprites, "PutSprite calls")
        assertEquals(0, rig.engine.procs.count { it.script === rig.talk.talkScript })
    }

    private fun compare(rig: Rig, f: Int): String? {
        val iw = iwram(f)
        val ew = ewram(f)
        val got = ByteArray(TalkState.SIZE)
        rig.talk.state.encode(got)
        for (i in 0 until TalkState.SIZE) {
            val want = iw[STATE + i]
            if (i in 0x18 until 0x38) {
                if (i % 4 != 0) continue
                val w = u32(iw, STATE + i) != 0
                val g = u32(got, i) != 0
                if (w != g) return "face handle ${(i - 0x18) / 4} null-ness want $w"
            } else if (got[i] != want) {
                return "state byte 0x%02X want %d got %d".format(i, want.toInt() and 0xFF,
                    got[i].toInt() and 0xFF)
            }
        }
        val t = ByteArray(Text.SIZE)
        for (i in 0..1) {
            rig.talk.texts[i].serialize(t)
            for (k in 0 until Text.SIZE) {
                if (t[k] != iw[TEXTS + 8 * i + k]) return "text $i byte $k"
            }
        }
        val font = ByteArray(0x18)
        rig.talk.font.serialize(font)
        for (k in 0 until 0x18) if (font[k] != iw[FONT + k]) return "font byte $k"
        for (i in rig.tm.indices) {
            if (rig.tm[i].toInt() and 0xFFFF != u16(ew, BG0_TM + 2 * i)) {
                return "tilemap cell $i"
            }
        }
        for (i in rig.tm1.indices) {
            if (rig.tm1[i].toInt() and 0xFFFF != u16(ew, BG1_TM + 2 * i)) {
                return "BG1 tilemap cell $i want ${u16(ew, BG1_TM + 2 * i)} got ${rig.tm1[i]}"
            }
        }
        val procs = ProcArrayDump.ofEwram(ew)
        for ((addr, script) in rig.watched) {
            val want = procs.findAll(addr).size
            val have = rig.engine.procs.count { it.script === script }
            if (want != have) return "${script.name} count want $want got $have"
        }
        val talkProc = procs.find(ADDR_TALK)
        val mine = rig.engine.find(rig.talk.talkScript)
        if (talkProc != null && mine != null) {
            if (talkProc.u8(0x28) != mine.lockCnt) return "Talk lockCnt"
            if (talkProc.scrCur != mine.scrCur) return "Talk scrCur"
        }
        val waitProc = procs.find(ADDR_WAIT)
        val myWait = rig.engine.find(rig.talk.waitForInputScript)
        if (waitProc != null && myWait != null) {
            val w = myWait.state as fe8.game.scene.TalkWaitState
            if (waitProc.u16(0x64) != w.x) return "wait x want ${waitProc.u16(0x64)} got ${w.x}"
            if (waitProc.u16(0x66) != w.y) return "wait y"
            if (waitProc.scrCur != myWait.scrCur) return "wait scrCur"
            if (waitProc.u8(0x28) != myWait.lockCnt) return "wait lockCnt"
        }
        val vramFile = File(runDir, "f%05d.vram.bin".format(f))
        if (vramFile.isFile) {
            val v = vramFile.readBytes()
            for (i in STRIPS until STRIPS + STRIP_BYTES) {
                if (v[i] != rig.vram[i]) return "vram strip byte ${i - STRIPS}"
            }
        }
        return null
    }

    private fun dump(frame: Int, kind: String): ByteArray {
        val r = File(runDir, "r%05d.$kind.bin".format(frame))
        return (if (r.isFile) r else File(runDir, "f%05d.$kind.bin".format(frame))).readBytes()
    }

    private fun iwram(f: Int) = dump(f, "iwram")

    private fun ewram(f: Int) = dump(f, "ewram")

    private fun clock(f: Int) = u32(iwram(f), 0x14)

    private fun u16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    /**
     * The talk plus everything around it that is not ported yet, as transcriptions of the decomp.
     */
    private inner class Rig(val rom: ByteArray, val runDir: File) :
        TextHost, TalkHost, TalkBubbleHost {
        val vram = ByteArray(0x18000) { SENT }
        val tm = ShortArray(0x400)
        val tm1 = ShortArray(0x400)
        val engine = ProcEngine()
        val ts = TextSystem(rom, this, Fe8Text.CONFIG)
        val talk = Talk(ts, engine, this)
        val bubble = TalkBubble(ts, engine, talk, this, this)
        var keys = 0
        var clock = 0
        var waitSprites = 0
        private var io = ByteArray(0x400)
        private var msg = ByteArray(0)
        private var displaySpeed = 0
        private val faceProcs = arrayOfNulls<Proc>(8)

        val skipScript = ProcScript(
            "gProcScr_TalkSkipListener",
            listOf(mark(Talk.PROC_MARK_TALK), repeat(skipIdle()), end()),
        )
        val faceScript = ProcScript("gProcScr_E_FACE", listOf(block(), end())) { FakeFace() }
        val moveScript = ProcScript(
            "gProcScr_TalkFaceMove",
            listOf(sleep(1), call(moveInit()), repeat(moveIdle()), end()),
        ) { MoveState() }
        val lockScript = ProcScript(
            "ProcScr_TemporaryLock", listOf(yield(), repeat(lockIdle()), end()),
        ) { CounterState() }
        val watched: List<Pair<Int, ProcScript>> = listOf(
            ADDR_TALK to talk.talkScript,
            0x08591340 to skipScript,
            0x085913B0 to moveScript,
            0x085913D0 to talk.pauseScript,
            ADDR_WAIT to talk.waitForInputScript,
            0x085914C8 to bubble.shiftClearScript,
            0x08591530 to bubble.bubbleOpenScript,
            0x08591540 to bubble.openScript,
            0x08591154 to faceScript,
            0x0859A0D4 to lockScript,
        )

        /** Seeds the state once from the dump of [frame] and starts the Talk proc. */
        fun seed(frame: Int) {
            io = File(runDir, "f09860.io.bin").readBytes()
            val ew = ewram(frame)
            msg = ew.copyOfRange(BUF, BUF + 0x1000)
            for (i in tm.indices) tm[i] = u16(ew, BG0_TM + 2 * i).toShort()
            for (i in tm1.indices) tm1[i] = u16(ew, BG1_TM + 2 * i).toShort()
            talk.initTalk(0x80, 2, false)
            talk.state.decode(iwram(frame), STATE)
            displaySpeed = talk.state.printDelay // GetTextDisplaySpeed() fed StartTalkExt
            engine.start(talk.talkScript, 3)
        }

        override fun language() = 1

        override fun applyTextPalette(talk: Boolean, palid: Int) {}

        override fun readVram32(addr: Int): Int {
            var v = 0
            for (k in 0..3) v = v or ((vram[addr - VRAM_BASE + k].toInt() and 0xFF) shl (8 * k))
            return v
        }

        override fun writeVram32(addr: Int, value: Int) {
            for (k in 0..3) vram[addr - VRAM_BASE + k] = (value shr (8 * k)).toByte()
        }

        override fun clearVram(dest: Int, byteCount: Int) {
            vram.fill(0, dest - VRAM_BASE, dest - VRAM_BASE + byteCount)
        }

        override fun backgroundTileDataOffset(bg: Int): Int =
            ((u16(io, 8 + 2 * bg) shr 2) and 3) * 0x4000

        override fun decompressToVram(src: Int, dest: Int) {
            Lz77.decode(rom, src - ROM_BASE).data.copyInto(vram, dest - VRAM_BASE)
        }

        override fun applyPalette(src: Int, palId: Int) {}

        override fun loadObjUiGfx() {}

        override fun setBgPosition(bg: Int, x: Int, y: Int) {}

        override fun startTalkSkipListener() {
            engine.start(skipScript, 3)
        }

        override fun bg0Tilemap() = tm

        override fun stringArray(addr: Int): ByteArray =
            if (addr - MSG_ADDR in 0 until msg.size) msg else rom

        override fun stringOffset(addr: Int): Int =
            if (addr - MSG_ADDR in 0 until msg.size) addr - MSG_ADDR else addr - ROM_BASE

        /** Transcribed from src/scene.c: GetStrTalkLen (without the nested-string codes). */
        override fun getStrTalkLen(str: Int, isBubbleOpen: Boolean): Int {
            val s = talk.state
            val a = stringArray(str)
            var i = stringOffset(str)
            var open = isBubbleOpen
            var speak = s.speakingFaceSlot
            var active = s.activeFaceSlot
            var cur = 0
            var max = 24
            val w = IntArray(1)
            while (true) {
                val c = a[i].toInt() and 0xFF
                when (c) {
                    0x00, 0x15 -> return maxOf(max, cur)
                    0x01, 0x02 -> {
                        max = maxOf(max, cur)
                        cur = 0
                        i++
                    }
                    0x04, 0x05, 0x06, 0x07, 0x16, 0x17, 0x1C, 0x1D -> i++
                    0x03 -> {
                        cur += 12
                        i++
                    }
                    in 0x08..0x0F -> {
                        active = c - 8
                        i++
                    }
                    0x10 -> {
                        while (true) {
                            val d = a[i].toInt() and 0xFF
                            if (d in 0x08..0x0F) {
                                active = d - 8
                                i++
                            } else if (d == 0x10) {
                                i += 3
                            } else {
                                break
                            }
                        }
                    }
                    0x11 -> {
                        if (active == speak) return maxOf(max, cur)
                        i++
                    }
                    0x12, 0x13, 0x14 -> {
                        if (!open) return maxOf(max, cur)
                        i++
                    }
                    0x18, 0x19, 0x1A, 0x1B -> {
                        cur += 0x80
                        i++
                    }
                    0x80 -> {
                        i++
                        when (a[i].toInt() and 0xFF) {
                            in 0x0A..0x11 -> {
                                active = (a[i].toInt() and 0xFF) - 0x0A
                                i++
                            }
                            0x05, 0x06, 0x20 -> error("nested string unused")
                            else -> i++
                        }
                    }
                    else -> {
                        if (c == 0x81 && (a[i + 1].toInt() and 0xFF) == 0x40) {
                            i += 2
                            cur += 6
                        } else {
                            if (active != speak && active != 0xFF) {
                                if (!open) {
                                    open = true
                                    speak = active
                                } else {
                                    return maxOf(max, cur)
                                }
                            }
                            i = ts.getCharTextLen(a, i, w)
                            cur += w[0]
                        }
                    }
                }
            }
        }

        override fun clearTalkBubble() = bubble.clearTalkBubble()

        override fun startTalkOpen(faceSlot: Int, parent: Proc) =
            bubble.startTalkOpen(faceSlot, parent)

        override fun bg1Tilemap() = tm1

        override fun isBattleDeamonActive() = false

        override fun toggleInvertedPalette(flag: Int) {}

        override fun initTalkTextWin(x: Int, y: Int, width: Int, height: Int) {}

        override fun disableTalkWindows() {}

        override fun initTalkBlend() {}

        override fun setBlendAlpha(a: Int, b: Int) {}

        override fun spriteTextClear(text: Text) = error("no sprite talk")

        override fun setTalkFaceLayer(faceSlot: Int, flag: Int) {}

        override fun startTalkShiftClear(parent: Proc) = bubble.startTalkShiftClear(parent)

        override fun talkBgSync(bg: Int) {}

        override fun setTalkFaceMouthMove(faceSlot: Int) {}

        override fun isTalkFaceMoving() = engine.find(moveScript) != null

        override fun setTalkFaceNoMouthMove(faceSlot: Int) {}

        /** TalkLoadFace as far as the talk state goes: a handle, a face proc, the 8-frame lock. */
        override fun talkLoadFace(proc: Proc) {
            val s = talk.state
            if (s.activeFaceSlot == 0xFF) talk.setActiveTalkFace(1)
            val slot = s.activeFaceSlot
            if (s.faces[slot] != 0) return
            s.faces[slot] = FAKE_FACE + slot
            val face = engine.start(faceScript, 3)
            (face.state as FakeFace).x = GAME_HPOS[slot] * 8
            faceProcs[slot] = face
            startTemporaryLock(proc, 8)
        }

        override fun startFaceFadeOut(face: Int) {}

        override fun startTemporaryLock(proc: Proc, frames: Int) {
            val p = engine.startBlocking(lockScript, proc)!!
            (p.state as CounterState).n = frames
        }

        override fun moveTalkFace(from: Int, to: Int) {
            val s = talk.state
            var swap = false
            if (s.faces[to] != 0) {
                swap = true
                startFaceMove(to, from)
            }
            startFaceMove(from, to)
            val h = s.faces[from]
            s.faces[from] = s.faces[to]
            s.faces[to] = h
            val p = faceProcs[from]
            faceProcs[from] = faceProcs[to]
            faceProcs[to] = p
            check(swap || s.faces[from] == 0)
        }

        private fun startFaceMove(from: Int, to: Int) {
            val face = faceProcs[from] ?: return
            val m = engine.start(moveScript, face).state as MoveState
            m.from = (face.state as FakeFace).x
            m.to = GAME_HPOS[to] * 8
        }

        override fun setFaceBlinkControl(face: Int, mode: Int) {}

        override fun setFaceEyes(face: Int, mode: Int) {}

        override fun startTalkChoice(
            buySell: Boolean,
            text: Text,
            tm: ShortArray,
            offset: Int,
            defaultChoice: Int,
            color: Int,
            parent: Proc,
        ) = error("no choice code in the trace")

        override fun startTalkShiftClearAll(parent: Proc) = bubble.startTalkShiftClearAll(parent)

        override fun startTalkSpriteShiftClear(parent: Proc) = error("no sprite talk")

        override fun talkFlushAllLine() = bubble.talkFlushAllLine()

        override fun endTalkSkipListener() {
            engine.endEach(skipScript)
        }

        override fun endTalkShiftClearAll() = bubble.endTalkShiftClearAll()

        override fun numberToStringAscii(number: Int, buf: ByteArray) = error("no [G]")

        override fun tacticianName(): Int = error("no [Tact]")

        override fun textDisplaySpeed() = displaySpeed

        override fun gameClock() = clock

        override fun newKeys() = keys

        override fun playSoundEffect(song: Int) {}

        override fun putSprite(layer: Int, x: Int, y: Int, lutIndex: Int, oam2: Int) {
            assertEquals(2, layer)
            assertEquals(4, oam2)
            assertEquals((clock / 2) and 0xF, lutIndex)
            waitSprites++
        }

        /** Transcribed from src/scene.c: TalkSkipListener_OnIdle (the fast-forward branch). */
        private fun skipIdle() = ProcFunc {
            val s = talk.state
            if (engine.find(bubble.shiftClearScript) != null) return@ProcFunc
            check(talk.checkTalkFlag(TalkFlags.NOSKIP) != 0 || keys and 0x0A == 0) { "skip" }
            if (engine.find(talk.waitForInputScript) != null) return@ProcFunc
            if (talk.checkTalkFlag(TalkFlags.NOFAST) == 0 && keys and 0xF3 != 0) s.instantScroll = 1
        }

        private fun moveInit() = ProcFunc { p ->
            val m = p.state as MoveState
            m.t58 = 0
            m.t5C = if (Math.abs(m.from - m.to) > 24) 32 else 16
        }

        private fun moveIdle() = ProcFunc { p ->
            val m = p.state as MoveState
            if (m.t58 >= m.t5C) engine.breakProc(p) else m.t58++
        }

        private fun lockIdle() = ProcFunc { p ->
            val c = p.state as CounterState
            if (c.n == 0) engine.breakProc(p) else c.n--
        }

    }

    private class FakeFace {
        var x = 0
    }

    private class MoveState {
        var from = 0
        var to = 0
        var t58 = 0
        var t5C = 0
    }

    private class CounterState {
        var n = 0
    }

    private companion object {
        const val SENT: Byte = 0xA5.toByte()
        const val ROM_BASE = 0x08000000
        const val VRAM_BASE = 0x06000000
        const val MSG_ADDR = 0x0202A6AC
        const val FIRST = 9865
        const val LAST = 10565
        const val STATE = 0x48
        const val TEXTS = 0xD0
        const val FONT = 0xF0
        const val BUF = 0x2A6AC
        const val BG0_TM = 0x22CA8
        const val BG1_TM = 0x234A8
        const val KEYS = 0x24CC0
        const val STRIPS = 0x1000
        const val STRIP_BYTES = 2 * 30 * 2 * 0x20
        const val ADDR_TALK = 0x08591358
        const val ADDR_WAIT = 0x085913F0
        const val FAKE_FACE = 0x02030000
        val GAME_HPOS = intArrayOf(3, 6, 9, 21, 24, 27, -8, 38)
    }
}
