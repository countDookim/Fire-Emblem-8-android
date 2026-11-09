package fe8.testing

import fe8.game.RomView
import fe8.game.scene.FaceBlinkState
import fe8.game.scene.FaceState
import fe8.game.scene.TalkScene
import gba.core.VerifiesPort
import gbafe.core.rng.LcgRng
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class TalkFaceBlinkDifferentialTest {
    @Test
    @VerifiesPort(
        "sub_800623C", "sub_8006280", "sub_80062B8", "FaceBlinkProc_GenBlinkInterval",
    )
    fun `blink procs follow reference intervals and frame counters`() {
        val assets = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        )
        val romFile = File(assets, "baserom.gba")
        val dir = File(assets, "reference/out/ram_trace_prologue_talk")
        assumeTrue(romFile.isFile && dir.isDirectory, "ROM or reference run not present")
        val bytes = romFile.readBytes()
        val trace = TalkTrace(dir)
        val engine = ProcEngine()
        val layers = SpriteLayers()
        val scene = TalkScene(
            RomView.ofRom(bytes), bytes, BgHardware(), engine, layers, KeyStatus(), { 0 },
            MsgStrings(bytes, MsgHuffman(bytes, 0, 0), 0), {}, { 0 }, {}, LcgRng(),
        )
        val seed = 9900
        val before = ProcArrayDump.ofEwram(trace.ewram(seed))
        val blinks = before.findAll(BLINK_SCRIPT)
        assertEquals(2, blinks.size)
        val faces = before.findAll(FACE_SCRIPT)
        val iwramSeed = trace.iwram(seed)
        // The reference root-5 list is face slot 0 then slot 1.
        for (face in faces.sortedBy { it.u8(0x40) }) {
            val slot = face.u8(0x40)
            scene.face.startFace(slot, face.u16(0x3E), 0, 0, face.u32(0x30))!!
            val blink = blinks.first { it.u32(0x2C) == face.address }
            val state = ((scene.face.gFaces[slot]!!.state as FaceState).pBlinkProc!!.state
                as FaceBlinkState)
            state.blinkControl = blink.u16(0x30)
            state.unk32 = blink.u16(0x32)
            state.unk34 = blink.u16(0x34)
            state.unk38 = blink.u32(0x38)
        }
        scene.lcg.set(trace.u32(iwramSeed, LCG_IWRAM_OFFSET))
        var blinksSeen = 0
        for (frame in seed + 1..10200) {
            layers.clear()
            engine.run(engine.root(5))
            val now = ProcArrayDump.ofEwram(trace.ewram(frame))
            for (face in now.findAll(FACE_SCRIPT)) {
                val slot = face.u8(0x40)
                val reference = now.findAll(BLINK_SCRIPT).first { it.u32(0x2C) == face.address }
                val blinkProc = (scene.face.gFaces[slot]!!.state as FaceState).pBlinkProc!!
                val actual = blinkProc.state as FaceBlinkState
                val where = "frame $frame slot $slot"
                assertEquals(reference.u16(0x30), actual.blinkControl, "$where blinkControl")
                assertEquals(reference.u16(0x32), actual.unk32, "$where unk32")
                assertEquals(reference.u32(0x38), actual.unk38, "$where unk38")
                assertEquals(reference.scrCur, blinkProc.scrCur, "$where script position")
                if (reference.u16(0x34) != 1024) {
                    assertEquals(reference.u16(0x34), actual.unk34, "$where unk34")
                }
                if (reference.scrCur == 6) blinksSeen++
            }
            // The unported mouth proc (display bit 4) also advances the shared LCG, so the
            // generator is resynchronised from the reference after each frame.
            scene.lcg.set(trace.u32(trace.iwram(frame), LCG_IWRAM_OFFSET))
        }
        assertTrue(blinksSeen > 0, "no blink frame covered")
    }

    @Test
    @VerifiesPort("sub_80062B8")
    fun `eye objects match reference OAM and portrait tiles`() {
        val assets = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        )
        val romFile = File(assets, "baserom.gba")
        val dir = File(assets, "reference/out/ram_trace_prologue_talk")
        assumeTrue(romFile.isFile && dir.isDirectory, "ROM or reference run not present")
        val bytes = romFile.readBytes()
        val trace = TalkTrace(dir)
        val engine = ProcEngine()
        val layers = SpriteLayers()
        val hw = BgHardware()
        val scene = TalkScene(
            RomView.ofRom(bytes), bytes, hw, engine, layers, KeyStatus(), { 0 },
            MsgStrings(bytes, MsgHuffman(bytes, 0, 0), 0), {}, { 0 }, {}, LcgRng(),
        )
        val orientations = mutableSetOf<Int>()
        for (frame in 9910..10200 step 10) {
            // Hardware OAM is synced one frame after the main pass that produced it.
            val previous = ProcArrayDump.ofEwram(trace.ewram(frame - 2))
            val oamReference = trace.dump(frame, "oam")
            val vramReference = trace.dump(frame, "vram")
            for (source in previous.findAll(FACE_SCRIPT)) {
                val refBlink = previous.findAll(BLINK_SCRIPT)
                    .firstOrNull { it.u32(0x2C) == source.address } ?: continue
                if (refBlink.scrCur != 6 || refBlink.u16(0x34) !in 0..8) continue
                val slot = source.u8(0x40)
                val face = scene.face.gFaces[slot] ?: scene.face.startFace(
                    slot, source.u16(0x3E), 0, 0, source.u32(0x30),
                )!!
                val state = face.state as FaceState
                state.xPos = source.u16(0x34).toShort().toInt()
                state.yPos = source.u16(0x36).toShort().toInt()
                state.spriteLayer = source.u8(0x41)
                scene.face.setFaceDisplayBits(face, source.u32(0x30))
                scene.face.faceOnInit(face)
                val blink = state.pBlinkProc!!
                scene.face.sub800623C(blink)
                (blink.state as FaceBlinkState).unk34 = refBlink.u16(0x34)
                layers.clear()
                scene.face.sub80062B8(blink)
                val oam = OamBuffer()
                layers.pushLayerObjects(0) {
                    oam.putHi(it.oam1, it.oam0, it.obj!!.data, it.oam2)
                }
                assertEquals(4, oam.hiPut, "eye object f$frame slot$slot")
                assertTrue((0 until 128).any { i ->
                    (0..2).all { attr ->
                        trace.u16(oamReference, i * 8 + attr * 2) ==
                            (oam.buffer[attr].toInt() and 0xFFFF)
                    }
                }, "eye OAM f$frame slot$slot")
                val tile = oam.buffer[2].toInt() and 0x3FF
                for (row in 0..1) for (column in 0..3) {
                    val offset = 0x10000 + (tile + row * 32 + column) * 32
                    assertTrue(
                        hw.vram.copyOfRange(offset, offset + 32)
                            .contentEquals(vramReference.copyOfRange(offset, offset + 32)),
                        "eye tiles f$frame slot$slot row$row column$column",
                    )
                }
                orientations += source.u32(0x30) and 1
            }
        }
        assertEquals(setOf(0, 1), orientations, "both orientations must blink in reference")
    }
}

private const val FACE_SCRIPT = 0x08591154
private const val BLINK_SCRIPT = 0x08591264
private const val LCG_IWRAM_OFFSET = 0x08
