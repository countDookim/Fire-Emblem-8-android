package fe8.testing

import fe8.game.RomView
import fe8.game.scene.TalkScene
import gba.core.VerifiesPort
import gba.testkit.RamSymbols
import gbafe.core.rng.LcgRng
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class TalkFacePaletteDifferentialTest {
    @Test
    @VerifiesPort("StartFaceFadeIn")
    fun `face fades match both reference palette banks on every frame`() {
        val assets = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets")
        val romFile = File(assets, "baserom.gba")
        val dir = File(assets, "reference/out/ram_trace_prologue_talk")
        assumeTrue(romFile.isFile && dir.isDirectory, "ROM or reference run not present")
        val bytes = romFile.readBytes()
        val trace = TalkTrace(dir)
        val engine = ProcEngine()
        val hw = BgHardware()
        val scene = TalkScene(
            RomView.ofRom(bytes), bytes, hw, engine, SpriteLayers(), KeyStatus(), { 0 },
            MsgStrings(bytes, MsgHuffman(bytes, 0, 0), 0), {}, { 0 }, {}, LcgRng(),
        )
        val faces = ProcArrayDump.ofEwram(trace.ewram(9871)).findAll(0x08591154)
        assertEquals(2, faces.size)
        val paletteOffset = RamSymbols.fromEnvironment()["gPaletteBuffer"].address - 0x02000000
        val started = faces.map { source ->
            val slot = source.u8(0x40)
            val previous = trace.ewram(9870)
            for (color in 0 until 16) {
                hw.palette[(22 + slot) * 16 + color] =
                    trace.u16(previous, paletteOffset + (22 + slot) * 32 + color * 2).toShort()
            }
            val face = scene.face.startFace(slot, source.u16(0x3E), 0, 0, source.u32(0x30))!!
            scene.startFaceFadeIn(face)
            assertTrue((0 until 16).all { hw.palette[(22 + slot) * 16 + it].toInt() == 0 })
            assertEquals(10, face.child!!.mark)
            face
        }
        for (frame in 9871..9884) {
            engine.run(engine.root(5))
            scene.flushTileMoves()
            val ew = trace.ewram(frame)
            for (face in started) {
                if (frame < 9884) {
                    assertNotNull(face.child)
                } else {
                    // Only the blink proc (started before the fade) remains under the face.
                    assertEquals(scene.face.blinkScript, face.child!!.script)
                }
            }
            for (source in faces) {
                val bank = 22 + source.u8(0x40)
                for (color in 0 until 16) {
                    assertEquals(
                        trace.u16(ew, paletteOffset + bank * 32 + color * 2),
                        hw.palette[bank * 16 + color].toInt() and 0xFFFF,
                        "frame $frame bank $bank color $color",
                    )
                }
            }
        }
        // The hardware palette dump precedes the EWRAM snapshot by one frame.
        val pal = trace.dump(9880, "pal")
        val previous = trace.ewram(9879)
        for (bank in 22..23) for (color in 0 until 16) {
            assertEquals(
                trace.u16(previous, paletteOffset + bank * 32 + color * 2),
                trace.u16(pal, bank * 32 + color * 2),
            )
        }
    }
}
