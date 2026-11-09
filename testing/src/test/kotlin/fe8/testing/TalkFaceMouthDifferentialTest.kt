package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import fe8.game.scene.FaceBlinkState
import fe8.game.scene.FaceMouthState
import fe8.game.scene.FaceState
import fe8.game.scene.TalkScene
import gba.core.VerifiesPort
import gbafe.core.rng.LcgRng
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class TalkFaceMouthDifferentialTest {
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val dir = File(assets, "reference/out/ram_trace_prologue_talk")
    private val trace = TalkTrace(dir)

    private fun rig(): MouthRig {
        val file = File(assets, "baserom.gba")
        assumeTrue(file.isFile && dir.isDirectory, "ROM or reference run not present")
        return MouthRig(file.readBytes())
    }

    @Test
    fun `game loop flushes mouth rows on vblank`() {
        val romFile = File(assets, "baserom.gba")
        assumeTrue(romFile.isFile && dir.isDirectory, "ROM or reference run not present")
        val boot = Fe8Boot(RomView.ofRom(romFile.readBytes()), { 0 }, RngService())
        val source = ProcArrayDump.ofEwram(trace.ewram(10078)).findAll(FACE_SCRIPT)
            .first { it.u32(0x30) and 0x30 != 0 }
        val face = boot.talkScene.face.startFace(
            source.u8(0x40), source.u16(0x3E), 0, 0, source.u32(0x30) and 0x30.inv(),
        )!!
        val state = face.state as FaceState
        repeat(3) {
            boot.engine.run(boot.engine.root(5))
            boot.talkScene.flushTileMoves()
        }
        boot.loop.onMain()
        boot.talkScene.flushTileMoves()
        boot.talkScene.face.setFaceDisplayBits(face, source.u32(0x30))
        val mouth = state.mouthProc!!.state as FaceMouthState
        mouth.phase = 0
        mouth.timer = 0
        val offset = 0x10000 + (((state.oam2 + 28) and 0x3FF) + 32) * 32
        val vram = boot.ui.env.hardware.vram
        val before = vram.copyOfRange(offset, offset + 128)
        val reference = trace.dump(10080, "vram").copyOfRange(offset, offset + 128)
        assertTrue(!before.contentEquals(reference), "fixture must require a transfer")
        boot.talkScene.face.sub8005FE0(state.mouthProc!!)
        assertTrue(before.contentEquals(vram.copyOfRange(offset, offset + 128)))
        boot.frame()
        assertTrue(vram.copyOfRange(offset, offset + 128).contentEquals(reference))
    }

    @Test
    @VerifiesPort("sub_8005FD4")
    fun `mouth child initializes to its reference parent and timer`() {
        val rig = rig()
        val before = ProcArrayDump.ofEwram(trace.ewram(9900))
        val faces = before.findAll(FACE_SCRIPT)
        assertEquals(2, faces.size)
        for (source in faces) {
            val reference = before.findAll(MOUTH_SCRIPT)
                .first { it.u32(0x2C) == source.address }
            val proc = rig.face(source)
            val state = proc.state as FaceMouthState
            assertEquals(rig.scene.face.gFaces[source.u8(0x40)], state.face)
            assertEquals(reference.u16(0x32), state.timer)
            state.phase = reference.u16(0x30)
            rig.scene.face.sub8005FD4(proc)
            assertEquals(reference.u16(0x30), state.phase, "initialization retains the phase")
        }
        val suppressed = rig.scene.face.startFace(3, faces[0].u16(0x3E), 0, 0, 0x1000)!!
        val state = suppressed.state as FaceState
        assertNull(state.mouthProc)
        assertNull(state.pBlinkProc)
    }

    @Test
    @VerifiesPort("sub_8005FE0")
    fun `mouth state and rng follow stable speaking and silent reference frames`() {
        val rig = rig()
        val modes = mutableSetOf<Int>()
        var rolls = 0
        for (frame in 9901..10200) {
            val before = ProcArrayDump.ofEwram(trace.ewram(frame - 1))
            val after = ProcArrayDump.ofEwram(trace.ewram(frame))
            for (refFace in before.findAll(FACE_SCRIPT)) {
                val nextFace = after.findAll(FACE_SCRIPT).first { it.address == refFace.address }
                val bits = refFace.u32(0x30)
                if (bits != nextFace.u32(0x30)) continue
                val refMouth = before.findAll(MOUTH_SCRIPT)
                    .first { it.u32(0x2C) == refFace.address }
                val nextMouth = after.findAll(MOUTH_SCRIPT)
                    .first { it.u32(0x2C) == refFace.address }
                val proc = rig.face(refFace)
                val mouth = proc.state as FaceMouthState
                mouth.phase = refMouth.u16(0x30)
                mouth.timer = refMouth.u16(0x32).toShort().toInt()
                val initialRng = trace.u32(trace.iwram(frame - 1), 8)
                rig.scene.lcg.set(initialRng)
                rig.layers.clear()
                rig.scene.face.sub8005FE0(proc)
                rig.scene.flushTileMoves()
                assertEquals(nextMouth.u16(0x30), mouth.phase, "phase f$frame")
                assertEquals(nextMouth.u16(0x32), mouth.timer and 0xFFFF, "timer f$frame")
                val talking = bits and 0x30 != 0
                modes += if (talking) 1 else 0
                if (talking && refMouth.u16(0x32) == 0) {
                    assertEquals(trace.u32(trace.iwram(frame), 8), rig.scene.lcg.value,
                        "LCG f$frame")
                    rolls++
                }
            }
        }
        assertEquals(setOf(0, 1), modes)
        assertTrue(rolls > 5, "reference must cover repeated mouth rolls")
    }

    @Test
    fun `mouth and blink children share reference rng in proc order`() {
        val rig = rig()
        val seed = ProcArrayDump.ofEwram(trace.ewram(9900))
        for (source in seed.findAll(FACE_SCRIPT).sortedBy { it.u8(0x40) }) {
            val proc = rig.face(source)
            val refMouth = seed.findAll(MOUTH_SCRIPT).first { it.u32(0x2C) == source.address }
            val mouth = proc.state as FaceMouthState
            mouth.phase = refMouth.u16(0x30)
            mouth.timer = refMouth.u16(0x32).toShort().toInt()
            val refBlink = seed.findAll(BLINK_SCRIPT).first { it.u32(0x2C) == source.address }
            val blink = (mouth.face!!.state as FaceState).pBlinkProc!!.state as FaceBlinkState
            blink.blinkControl = refBlink.u16(0x30)
            blink.unk32 = refBlink.u16(0x32)
            blink.unk34 = refBlink.u16(0x34)
            blink.unk38 = refBlink.u32(0x38)
        }
        rig.scene.lcg.set(trace.u32(trace.iwram(9900), 8))
        for (frame in 9901..10200) {
            val reference = ProcArrayDump.ofEwram(trace.ewram(frame))
            for (face in reference.findAll(FACE_SCRIPT)) rig.face(face)
            rig.layers.clear()
            rig.engine.run(rig.engine.root(5))
            rig.scene.flushTileMoves()
            for (face in reference.findAll(FACE_SCRIPT)) {
                val refMouth = reference.findAll(MOUTH_SCRIPT)
                    .first { it.u32(0x2C) == face.address }
                val mouth = rig.face(face).state as FaceMouthState
                assertEquals(refMouth.u16(0x30), mouth.phase, "phase f$frame")
                assertEquals(refMouth.u16(0x32), mouth.timer and 0xFFFF, "timer f$frame")
            }
            assertEquals(trace.u32(trace.iwram(frame), 8), rig.scene.lcg.value, "LCG f$frame")
        }
    }

    @Test
    @VerifiesPort("sub_8005FE0")
    fun `mouth OAM and updated tiles match reference in both orientations`() {
        val rig = rig()
        val orientations = mutableSetOf<Int>()
        val modes = mutableSetOf<Int>()
        for (frame in 9910..10200 step 10) {
            val before = ProcArrayDump.ofEwram(trace.ewram(frame - 2))
            val oamReference = trace.dump(frame, "oam")
            val vramReference = trace.dump(frame, "vram")
            for (refFace in before.findAll(FACE_SCRIPT)) {
                val refMouth = before.findAll(MOUTH_SCRIPT)
                    .first { it.u32(0x2C) == refFace.address }
                val bits = refFace.u32(0x30)
                val proc = rig.face(refFace)
                val mouth = proc.state as FaceMouthState
                mouth.phase = refMouth.u16(0x30)
                mouth.timer = refMouth.u16(0x32).toShort().toInt()
                rig.scene.lcg.set(trace.u32(trace.iwram(frame - 2), 8))
                rig.layers.clear()
                val faceState = (mouth.face!!.state as FaceState)
                val tileOffset = 0x10000 + ((faceState.oam2 + 28) and 0x3FF) * 32
                val beforeVblank = rig.hw.vram.copyOfRange(tileOffset, tileOffset + 128)
                rig.scene.face.sub8005FE0(proc)
                assertTrue(beforeVblank.contentEquals(
                    rig.hw.vram.copyOfRange(tileOffset, tileOffset + 128),
                ), "mouth transfer must wait for VBlank f$frame")
                rig.scene.flushTileMoves()
                val oam = OamBuffer()
                rig.layers.pushLayerObjects(0) {
                    oam.putHi(it.oam1, it.oam0, it.obj!!.data, it.oam2)
                }
                assertEquals(4, oam.hiPut, "mouth object f$frame")
                assertTrue((0 until 128).any { i ->
                    (0..2).all { attr ->
                        trace.u16(oamReference, i * 8 + attr * 2) ==
                            (oam.buffer[attr].toInt() and 0xFFFF)
                    }
                }, "mouth OAM f$frame slot${refFace.u8(0x40)}")
                // Find the last main pass which copied this mouth before the captured VBlank.
                var updateFrame = frame - 2
                var updateFace = refFace
                var updateMouth = refMouth
                while (updateFace.u32(0x30) and 0x30 != 0 &&
                    updateMouth.u16(0x32) != 0
                ) {
                    updateFrame--
                    val previous = ProcArrayDump.ofEwram(trace.ewram(updateFrame))
                    updateFace = previous.findAll(FACE_SCRIPT)
                        .first { it.address == refFace.address }
                    updateMouth = previous.findAll(MOUTH_SCRIPT)
                        .first { it.u32(0x2C) == updateFace.address }
                }
                val update = rig.face(updateFace)
                val updateState = update.state as FaceMouthState
                updateState.phase = updateMouth.u16(0x30)
                updateState.timer = updateMouth.u16(0x32).toShort().toInt()
                rig.scene.lcg.set(trace.u32(trace.iwram(updateFrame), 8))
                rig.scene.face.sub8005FE0(update)
                rig.scene.flushTileMoves()
                val tile = oam.buffer[2].toInt() and 0x3FF
                for (row in 0..1) {
                    val offset = 0x10000 + (tile + row * 32) * 32
                    assertTrue(
                        rig.hw.vram.copyOfRange(offset, offset + 128)
                            .contentEquals(vramReference.copyOfRange(offset, offset + 128)),
                        "mouth tiles f$frame slot${refFace.u8(0x40)} row$row",
                    )
                }
                modes += if (updateFace.u32(0x30) and 0x30 != 0) 1 else 0
                orientations += bits and 1
            }
        }
        assertEquals(setOf(0, 1), orientations)
        assertEquals(setOf(0, 1), modes)
    }
}

private class MouthRig(bytes: ByteArray) {
    val engine = ProcEngine()
    val layers = SpriteLayers()
    val hw = BgHardware()
    val scene = TalkScene(
        RomView.ofRom(bytes), bytes, hw, engine, layers, KeyStatus(), { 0 },
        MsgStrings(bytes, MsgHuffman(bytes, 0, 0), 0), {}, { 0 }, {}, LcgRng(),
    )

    fun face(source: ProcArrayDump.Proc): gbafe.proc.Proc {
        val slot = source.u8(0x40)
        val face = scene.face.gFaces[slot] ?: scene.face.startFace(
            slot, source.u16(0x3E), 0, 0, source.u32(0x30),
        )!!
        val state = face.state as FaceState
        state.xPos = source.u16(0x34).toShort().toInt()
        state.yPos = source.u16(0x36).toShort().toInt()
        state.spriteLayer = source.u8(0x41)
        scene.face.setFaceDisplayBits(face, source.u32(0x30))
        return state.mouthProc!!
    }
}

private const val FACE_SCRIPT = 0x08591154
private const val MOUTH_SCRIPT = 0x0859124C
private const val BLINK_SCRIPT = 0x08591264
