package fe8.testing

import fe8.game.RomView
import fe8.game.scene.TalkFace
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
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class TalkFaceFadeOutDifferentialTest {
    @Test
    @VerifiesPort("StartFaceFadeOut")
    fun `fade out follows reference palettes and face lifetimes`() {
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
        // Find the first completed snapshot in which the two face-end procs appear.
        val start = (10500..10600).first { frame ->
            File(dir, "r%05d.ewram.bin".format(frame)).isFile &&
                ProcArrayDump.ofEwram(trace.ewram(frame)).findAll(FACE_END).size == 2
        }
        val before = trace.ewram(start - 1)
        val sources = ProcArrayDump.ofEwram(before).findAll(FACE_SCRIPT)
        assertEquals(2, sources.size)
        val paletteOffset = RamSymbols.fromEnvironment()["gPaletteBuffer"].address - 0x02000000
        for (source in sources) {
            val slot = source.u8(0x40)
            val bank = 22 + slot
            scene.face.startFace(slot, source.u16(0x3E), 0, 0, source.u32(0x30))!!
            for (color in 0 until 16) {
                hw.palette[bank * 16 + color] =
                    trace.u16(before, paletteOffset + bank * 32 + color * 2).toShort()
            }
            scene.startFaceFadeOut(TalkFace.FACE_HANDLE + slot)
        }
        val end = (start..10600).first { frame ->
            ProcArrayDump.ofEwram(trace.ewram(frame)).findAll(FACE_SCRIPT).isEmpty()
        }
        for (frame in start..end + 2) {
            // The original starts these procs after tree 3 has run in the start frame.
            if (frame != start) engine.run(engine.root(3))
            engine.run(engine.root(5))
            val ew = trace.ewram(frame)
            val refs = ProcArrayDump.ofEwram(ew).findAll(FACE_SCRIPT)
            for (source in sources) {
                val slot = source.u8(0x40)
                val bank = 22 + slot
                assertEquals(refs.any { it.u8(0x40) == slot }, scene.face.gFaces[slot] != null,
                    "frame $frame slot $slot")
                val referenceFace = refs.firstOrNull { it.u8(0x40) == slot }
                val actualFace = scene.face.gFaces[slot]
                if (referenceFace != null) {
                    val childOffset = referenceFace.u32(0x18) - 0x02000000
                    assertEquals(PAL_FADE, trace.u32(ew, childOffset), "frame $frame fade child")
                    assertEquals(10, actualFace!!.child!!.mark, "frame $frame fade mark")
                }
                for (color in 0 until 16) {
                    assertEquals(trace.u16(ew, paletteOffset + bank * 32 + color * 2),
                        hw.palette[bank * 16 + color].toInt() and 0xFFFF,
                        "frame $frame bank $bank color $color")
                }
            }
        }
    }
}

private const val FACE_SCRIPT = 0x08591154
private const val FACE_END = 0x08591234
private const val PAL_FADE = 0x0859A00C
