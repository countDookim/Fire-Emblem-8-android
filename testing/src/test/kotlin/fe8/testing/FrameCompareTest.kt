package fe8.testing

import gbafe.render.Camera
import gbafe.render.FrameBuffer
import gbafe.render.RenderState
import gbafe.render.SoftwareRenderer
import gbafe.render.TileSheets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M3-06 frame compare: the software compositor, fed the hardware state mGBA dumped for a frame
 * ([HardwareScene]), must reproduce mGBA's own screen for that frame at 5-bit precision. A failing frame
 * writes `build/frame-diff/<run>_<dump>.png` (expected | ours | differences in red).
 *
 * Only frames whose picture comes from state set up before the frame are usable this way (no mid-frame
 * HBlank register changes, no VRAM upload between draw and dump), so the set below is static screens.
 */
class FrameCompareTest {
    private val ref = ReferenceData.fromEnvironment()

    private fun diffs(run: String, dump: String): Int {
        val r = ref.run(run)
        val state = RenderState()
        val sheets = TileSheets()
        val fb = FrameBuffer()
        val io = r.region(dump, "io")
        check(!HardwareScene.forcedBlank(io)) { "$run/$dump is a forced-blank frame" }
        HardwareScene.load(state, sheets, r.region(dump, "vram"), r.region(dump, "pal"), r.region(dump, "oam"), io)
        SoftwareRenderer(state, sheets, fb).render(Camera())
        val out = File(System.getProperty("fe8.frameDiffDir") ?: "build/frame-diff", "${run}_$dump.png")
        return HardwareScene.compare(fb, r.frameRgb(dump), out)
    }

    @Test
    fun `health and safety screen matches mGBA`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assertEquals(0, diffs("boot_idle", "f00600")) // BG0 + brightness increase
    }

    @Test
    fun `boot logos, intro and title screen frames match mGBA`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        // f00720.. = title screen: BG0-1 + 128 OAM entries, alpha blend (BLDCNT 0x0340); 85 colours.
        val frames = listOf("boot_idle" to "f00060", "boot_idle" to "f00300", "boot_to_title" to "f00240",
            "boot_to_title" to "f00360", "boot_to_title" to "f00600", "boot_to_title" to "f00720",
            "boot_to_title" to "f00900", "boot_to_title" to "f01200", "boot_to_title" to "f01500",
            "title_state_replay" to "down_c")
        val failed = frames.map { (run, dump) -> "$run/$dump" to diffs(run, dump) }.filter { it.second != 0 }
        assertEquals(emptyList<Pair<String, Int>>(), failed, "frames with differing pixels (see build/frame-diff)")
    }

    @Test
    fun `compare detects a wrong frame`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val r = ref.run("boot_to_title")
        val fb = FrameBuffer()
        fb.clear(0)
        val d = HardwareScene.compare(fb, r.frameRgb("f00900"))
        assertTrue(d > 10_000, "an all-black frame differs from the title in only $d pixels")
    }
}
