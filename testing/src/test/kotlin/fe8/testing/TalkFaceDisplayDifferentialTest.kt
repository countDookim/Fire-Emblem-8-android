package fe8.testing

import fe8.game.RomView
import fe8.game.scene.FaceState
import fe8.game.scene.TalkScene
import gba.core.VerifiesPort
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Base face OBJ output only: mouth/blink overlays and palette fades remain outside this test. */
class TalkFaceDisplayDifferentialTest {
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val runDir = File(assets, "reference/out/ram_trace_prologue_talk")
    private val trace = TalkTrace(runDir)

    private fun rig(): TalkFaceRig {
        val file = File(assets, "baserom.gba")
        assumeTrue(file.isFile && runDir.isDirectory, "ROM or reference run not present")
        return TalkFaceRig(file.readBytes())
    }

    @Test
    @VerifiesPort("Face_OnIdle", "FaceRefreshSprite")
    fun `production host submits both flipped base faces as the OAM trace`() {
        val rig = rig()
        var compared = 0
        val observedModes = mutableSetOf<Int>()
        // After the entrance move finishes: during movement the move proc changes x after
        // Face_OnIdle has drawn, so an end-of-frame state cannot seed that frame's OBJ.
        for (frame in 9950..10560 step 10) {
            val ew = trace.ewram(frame)
            val reference = trace.dump(frame, "oam")
            for (source in ProcArrayDump.ofEwram(ew).findAll(0x08591154)) {
                observedModes += source.u32(0x30) and 0x807
                val slot = source.u8(0x40)
                var proc = rig.scene.face.gFaces[slot]
                if (proc == null) {
                    proc = rig.scene.face.startFace(
                        slot, source.u16(0x3E), 0, 0, source.u32(0x30),
                    )!!
                }
                val state = proc.state as FaceState
                state.xPos = source.u16(0x34).toShort().toInt()
                state.yPos = source.u16(0x36).toShort().toInt()
                state.spriteLayer = source.u8(0x41)
                rig.scene.face.setFaceDisplayBits(proc, source.u32(0x30))
                assertEquals(source.u32(0x38), state.sprite?.id, "sprite f$frame slot$slot")
                assertEquals(source.u16(0x3C), state.oam2, "oam2 f$frame slot$slot")
                rig.sprites.clear()
                rig.scene.face.faceOnIdle(proc)
                assertEquals(1, rig.sprites.allocIt, "production host drew no face")
                val entry = rig.sprites.pool[0]
                assertEquals(state.spriteLayer, rig.sprites.layers.indexOfFirst {
                    it.next === entry
                })
                val oam = OamBuffer()
                rig.sprites.pushLayerObjects(0) {
                    oam.putHi(it.oam1, it.oam0, it.obj!!.data, it.oam2)
                }
                val count = oam.hiPut / 4
                assertTrue(count > 0, "base composition must contain objects")
                // Animated mouth/eye procs may submit extra objects. Compare the complete,
                // consecutive base composition with hardware OAM, excluding those overlays.
                assertTrue((0..128 - count).any { start ->
                    (0 until count).all { i ->
                        (0..2).all { attr ->
                            trace.u16(reference, (start + i) * 8 + attr * 2) ==
                                (oam.buffer[i * 4 + attr].toInt() and 0xFFFF)
                        }
                    }
                }, "base face OBJ differs at f$frame slot$slot")
                compared++
            }
        }
        assertEquals(setOf(2, 3), observedModes, "both reference orientations must be covered")
        assertTrue(compared > 100, "both faces must be compared across the talk")
    }

    @Test
    fun `face proc draws cached ROM compositions and preserves blend and coordinate flags`() {
        val rig = rig()
        val proc = rig.scene.face.startFace(0, 1, -513, -257, 0x400)!!
        rig.sprites.clear()
        repeat(3) { rig.engine.run(rig.engine.root(5)) }
        assertTrue(rig.sprites.allocIt > 0, "face script must invoke Face_OnIdle")
        val state = proc.state as FaceState
        val cached = state.sprite
        rig.sprites.clear()
        rig.scene.face.faceOnIdle(proc)
        assertEquals(511, rig.sprites.pool[0].oam1)
        assertEquals(0x4FF, rig.sprites.pool[0].oam0)
        rig.scene.face.setFaceDisplayBits(proc, 0)
        assertSame(cached, state.sprite, "composition should be cached")
        rig.scene.face.setFaceDisplayBits(proc, 0x4000)
        rig.sprites.clear()
        rig.scene.face.faceOnIdle(proc)
        assertEquals(0, rig.sprites.allocIt)
        // All compositions are read from ROM; no sprite halfwords are reproduced in this test.
        for (disp in intArrayOf(0, 1, 2, 3, 4, 5, 0x800, 0x801)) {
            rig.scene.face.setFaceDisplayBits(proc, disp)
            val sprite = state.sprite!!
            assertEquals(1 + 3 * rig.rom.u16(sprite.id), sprite.data.size)
            assertTrue(sprite.data.indices.all {
                (sprite.data[it].toInt() and 0xFFFF) == rig.rom.u16(sprite.id + 2 * it)
            })
        }
        val previous = state.sprite
        rig.scene.face.setFaceDisplayBits(proc, 6)
        assertSame(previous, state.sprite, "unhandled display bits retain the C sprite pointer")
    }
}

private class TalkFaceRig(bytes: ByteArray) {
    val rom = RomView.ofRom(bytes)
    val engine = ProcEngine()
    val sprites = SpriteLayers()
    val scene = TalkScene(
        rom, bytes, BgHardware(), engine, sprites, KeyStatus(), { 0 },
        MsgStrings(bytes, MsgHuffman(bytes, 0, 0), 0), {}, { 0 }, {},
    )
}
