package fe8.testing

import gbafe.core.VerifiesPort
import gbafe.render.BgVerticalScroll
import gbafe.render.Camera
import gbafe.render.HBlankLines
import gbafe.render.FrameBuffer
import gbafe.render.GpuFrame
import gbafe.render.PackedCompositor
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
    private val sym = RamSymbols.fromEnvironment()

    private fun load(run: String, dump: String): Pair<RenderState, TileSheets> {
        val r = ref.run(run)
        val state = RenderState()
        val sheets = TileSheets()
        val io = r.region(dump, "io")
        check(!HardwareScene.forcedBlank(io)) { "$run/$dump is a forced-blank frame" }
        HardwareScene.load(state, sheets, r.region(dump, "vram"), r.region(dump, "pal"), r.region(dump, "oam"), io)
        applyHBlank(r, dump, state, io)
        return state to sheets
    }

    private fun diffs(run: String, dump: String): Int {
        val r = ref.run(run)
        val (state, sheets) = load(run, dump)
        val fb = FrameBuffer()
        SoftwareRenderer(state, sheets, fb, MgbaColorMath).render(Camera())
        val out = File(System.getProperty("fe8.frameDiffDir") ?: "build/frame-diff", "${run}_$dump.png")
        return HardwareScene.compare(fb, r.frameRgb(dump), out)
    }

    /**
     * Replays the frame's HBlank handler into per-line overrides. Only `HBlank_BgVerticalScroll` (menus) is
     * known so far; any other handler fails the frame so it is not silently drawn without its effect.
     */
    private fun applyHBlank(r: ReferenceData.Run, dump: String, state: RenderState, io: ByteArray) {
        if (r.dumps[dump] == null || !File(ref.root, "${r.name}/$dump.iwram.bin").isFile) return
        val iwram = r.region(dump, "iwram")
        fun ptr(name: String) = u32(iwram, sym[name].address - GbaMem.IWRAM)
        val h1 = ptr("sHBlankHandler1")
        val h2 = ptr("sHBlankHandler2")
        if (h1 == 0 && h2 == 0) return
        check(h2 == 0 && h1 == HBLANK_BG_VERTICAL_SCROLL) { "${r.name}/$dump: unsupported HBlank handler 0x%08x/0x%08x".format(h1, h2) }
        val p = ptr("gpBgVerticalScrollSt")
        val mem = if (p ushr 24 == 2) r.region(dump, "ewram") else iwram
        val scroll = BgVerticalScroll()
        scroll.readFrom(mem, p and 0xFFFFFF)
        // The dumped RAM is one game step ahead: the menu's SaveDraw_ScrollFogBG already wrote the next frame's
        // table into the other half and called FlipBgVerticalScroll, so the dumped frame was drawn from the
        // half before the flip.
        scroll.frameFlip = scroll.frameFlip xor 1
        HBlankLines.capture(io, state) { v, regs -> scroll.onHBlank(v, regs) }
    }

    private fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 3].toInt() and 0xFF) shl 24)

    @Test
    @VerifiesPort("HBlank_BgVerticalScroll")
    fun `menu frames with the wavy rune background match mGBA`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        // Main menu, mode select, save-slot select: BG2 (runes, HOFS rewritten per line) blended over BG3.
        val failed = MENU_FRAMES.map { "menu_screens/$it" to diffs("menu_screens", it) }.filter { it.second != 0 }
        assertEquals(emptyList<Pair<String, Int>>(), failed, "frames with differing pixels (see build/frame-diff)")
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
        val failed = BOOT_FRAMES.map { (run, dump) -> "$run/$dump" to diffs(run, dump) }.filter { it.second != 0 }
        assertEquals(emptyList<Pair<String, Int>>(), failed, "frames with differing pixels (see build/frame-diff)")
    }

    @Test
    fun `GPU packed compositor draws every compared frame like the software renderer`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val frames = MENU_FRAMES.map { "menu_screens" to it } + BOOT_FRAMES + ("boot_idle" to "f00600")
        val failed = frames.filter { (run, dump) ->
            val (state, sheets) = load(run, dump)
            val soft = FrameBuffer()
            val gpu = FrameBuffer()
            SoftwareRenderer(state, sheets, soft, MgbaColorMath).render(Camera())
            val frame = GpuFrame(state, sheets)
            frame.pack()
            PackedCompositor(frame, sheets, MgbaColorMath).render(gpu)
            System.getenv("FE8_SHADER_DUMP")?.let { dir ->
                val hw = FrameBuffer()
                SoftwareRenderer(state, sheets, hw).render(Camera())
                ShaderDump.write(File(dir, "${run}_$dump.json"), frame, sheets, hw)
            }
            !soft.pixels.contentEquals(gpu.pixels)
        }
        assertEquals(emptyList<Pair<String, String>>(), failed)
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

    private companion object {
        val MENU_FRAMES = listOf("m0962", "m1024", "m1086", "m1087")
        val BOOT_FRAMES = listOf("boot_idle" to "f00060", "boot_idle" to "f00300", "boot_to_title" to "f00240",
            "boot_to_title" to "f00360", "boot_to_title" to "f00600", "boot_to_title" to "f00720",
            "boot_to_title" to "f00900", "boot_to_title" to "f01200", "boot_to_title" to "f01500",
            "title_state_replay" to "down_c")

        /** ROM address of `HBlank_BgVerticalScroll` (Thumb bit set), from `decomp/fireemblem8.nm.txt`. */
        const val HBLANK_BG_VERTICAL_SCROLL = 0x08014CC5
    }
}

/** Writes a packed frame + expected pixels as JSON for `tools/shader_parity.mjs` (WebGL2 shader check). */
internal object ShaderDump {
    fun write(out: File, frame: GpuFrame, sheets: TileSheets, expected: FrameBuffer) {
        val st = frame.state
        val fx = st.effects
        val sb = StringBuilder()
        fun arr(name: String, n: Int, get: (Int) -> Int) {
            sb.append('"').append(name).append("\":[")
            for (i in 0 until n) { if (i > 0) sb.append(','); sb.append(get(i)) }
            sb.append("],")
        }
        sb.append('{')
        arr("palette", GpuFrame.PALETTE_WIDTH * frame.paletteRows) { frame.palette[it].toInt() and 0xFFFF }
        sb.append("\"paletteRows\":").append(frame.paletteRows).append(',')
        arr("lines", frame.lines.size) { frame.lines[it] }
        arr("obj", frame.obj.size) { frame.obj[it] }
        for (bg in 0 until RenderState.LAYERS) {
            val l = st.layers[bg]
            val s = sheets[l.sheet]
            arr("map$bg", l.entries.size) { l.entries[it].toInt() and 0xFFFF }
            val n = if (!l.enabled || s == null) 0 else minOf(s.size, 0x10000)
            arr("sheet$bg", n) { s!![it].toInt() and 0xFF }
            var f = 0
            if (l.enabled) f = f or 1
            if (l.affine) f = f or 2
            if (l.wrap) f = f or 4
            if (l.mosaic) f = f or 8
            if (l.bpp8) f = f or 16
            arr("layer$bg", 6) { intArrayOf(f, l.priority, l.widthTiles, l.heightTiles, l.pa, l.pc)[it] }
        }
        val winOn = (if (fx.win0) 1 else 0) or (if (fx.win1) 2 else 0) or (if (fx.objWindow) 4 else 0)
        arr("win", 5) { intArrayOf(winOn, fx.winInside[0], fx.winInside[1], fx.winInside[2], fx.winOutside)[it] }
        sb.append("\"mosaicH\":").append(maxOf(1, fx.mosaic[0])).append(',')
        arr("expected", FrameBuffer.GBA_WIDTH * FrameBuffer.GBA_HEIGHT) {
            expected.pixels[(it / FrameBuffer.GBA_WIDTH) * expected.width + it % FrameBuffer.GBA_WIDTH] and 0xFFFFFF
        }
        sb.setLength(sb.length - 1)
        sb.append('}')
        out.parentFile.mkdirs()
        out.writeText(sb.toString())
    }
}
