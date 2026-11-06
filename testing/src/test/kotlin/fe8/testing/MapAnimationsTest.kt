package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ChapterData
import fe8.game.bm.MapAnimations
import fe8.game.bm.PlaySt
import gba.core.VerifiesPort
import gbafe.render.BgHardware
import gba.testkit.ReferenceData
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** M6-19: the Prologue map tile animation (tiles 0x140.. of BG VRAM) matches the reference. */
class MapAnimationsTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("BMapVSync_UpdateMapImgAnimations")
    fun `animated map tiles match the first phase reference`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / reference data not present")
        val run = ref.run("ram_trace_first_phase")
        val proc = ProcArrayDump.ofEwram(run.region("f19430", "ewram")).find(BM_VSYNC)
        assertNotNull(proc, "BMVSync proc in the f19430 dump")
        val hw = BgHardware()
        val rom = RomView.ofRom(romFile.readBytes())
        val anims = MapAnimations(rom, ChapterData(rom), hw, PlaySt())
        anims.seedTileAnimation(proc!!.u32(0x2C), proc.u32(0x30), proc.u16(0x34).toShort().toInt())
        run.region("f19430", "vram").copyInto(hw.vram)
        var changed = false
        var prev = run.region("f19430", "vram")
        for (f in 19431..19520) {
            anims.updateMapImgAnimations()
            if ((f - 19430) % 10 != 0) continue
            val want = run.region("f$f", "vram")
            for (i in TILE_ANIM until TILE_ANIM + COMPARED) {
                assertEquals(want[i], hw.vram[i], "f$f vram byte $i")
            }
            if (!want.copyOfRange(TILE_ANIM, TILE_ANIM + COMPARED)
                    .contentEquals(prev.copyOfRange(TILE_ANIM, TILE_ANIM + COMPARED))) changed = true
            prev = want
        }
        assertTrue(changed, "the reference animation never changed")
    }

    private companion object {
        const val BM_VSYNC = 0x0859D8B8
        const val TILE_ANIM = 0xA000
        const val COMPARED = 0x1000
    }
}
