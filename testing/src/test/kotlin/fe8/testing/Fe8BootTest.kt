package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The device boot wiring ([Fe8Boot]: game loop + game control + early start screens +
 * allocation-free frame pipeline), started from power-on state with assets read from the ROM
 * or the extracted windows, draws the same `boot_logos` frames as mGBA (same boot, A at f181), and
 * with the `ram_trace_logo_skip` inputs (A at f181, B at f470) skips the same steps (lag frames)
 * and draws the same op anim and title frames.
 */
class Fe8BootTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val assetsDir = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
    private val romFile = File(assetsDir, "baserom.gba")

    @Test
    fun `boot from power-on draws the mGBA logo frames`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        checkLogoFrames(RomView.ofRom(romFile.readBytes()))
    }

    @Test
    fun `boot from the extracted boot windows draws the same frames`() {
        val dir = File(romFile.parentFile, "extracted/boot")
        assumeTrue(ref.available && dir.isDirectory, "extracted boot/ or reference missing")
        checkLogoFrames(RomView.ofBootWindows { File(dir, it).readBytes() })
    }

    @Test
    fun `op anim and title lag and draw like mGBA`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val boot = startBoot(RomView.ofRom(romFile.readBytes()), bFrame = B_FRAME)
        val bm = sym["gBmSt"].address - GbaMem.EWRAM
        var compared = 0
        var lagFrames = 0
        var prevEnded = true
        for (frame in 21..LAST) {
            f = frame
            val oursLag = boot.loop.overrunning
            boot.frame()
            if (frame < FIRST_TRACED) continue
            // A frame runs no step while the previous dump shows a step still running.
            val theirLag = !prevEnded
            prevEnded = dump(frame, "ewram")[bm].toInt() != 0
            if (frame == FIRST_TRACED) continue
            assertEquals(theirLag, oursLag, "f$frame lag frame")
            if (theirLag) lagFrames++
            if (frame <= 1100 || frame % 10 != 0 || frame in FRAME_GAPS) continue
            val nf = "f%05d".format(frame)
            boot.render()
            val out = File(diffDir, "fe8boot_$nf.png")
            val diff = HardwareScene.compare(boot.screen, traceRun(frame).frameRgb(nf), out)
            if (frame in SPLIT_FRAMES) {
                assertTrue(diff <= 80, "$nf: $diff pixels") // HBlank BLDCNT drawn, map switch not
            } else {
                assertEquals(0, diff, nf)
            }
            compared++
        }
        assertEquals(27, lagFrames)
        assertEquals(239, compared)
    }

    private fun traceRun(frame: Int) = ref.run(
        when {
            frame <= 520 -> "ram_trace_logo_skip"
            frame <= 1100 -> "ram_trace_opanim"
            frame <= 1600 -> "ram_trace_opanim2"
            frame <= 2600 -> "ram_trace_opanim3"
            frame <= 3000 -> "ram_trace_opanim4"
            else -> "ram_trace_title1"
        },
    )

    private fun dump(frame: Int, region: String) =
        traceRun(frame).region(
            (if (frame > 1100 && frame % 10 == 0) "f%05d" else "r%05d").format(frame),
            region,
        )

    private var f = 0
    private val diffDir = System.getProperty("fe8.frameDiffDir") ?: "build/frame-diff"

    private fun startBoot(rom: RomView, bFrame: Int = -1): Fe8Boot {
        val keys = { if (f == A_FRAME) A_BUTTON else if (f == bFrame) B_BUTTON else 0 }
        val boot = Fe8Boot(rom, keyInput = keys, colorMath = MgbaColorMath)
        // The clock is 0 at f10 (before StartGame); frames numbered as in
        // EarlyStartUiBootDifferentialTest.
        val clockAt = sym["gGameClock"].address
        val iwram20 = ref.run("ram_trace_boot").region("r00020", "iwram")
        val clock20 = GbaMem.u32(iwram20, GbaMem.IWRAM, clockAt).toLong() and 0xFFFFFFFFL
        while (boot.clock.get() < clock20) boot.frame()
        return boot
    }

    private fun checkLogoFrames(rom: RomView) {
        val boot = startBoot(rom)
        val logos = ref.run("boot_logos")
        var compared = 0
        for (frame in 21..LOGO_FRAMES.last()) {
            f = frame
            boot.frame()
            if (frame !in LOGO_FRAMES) continue
            boot.render()
            val dump = "f%05d".format(frame)
            val out = File(diffDir, "fe8boot_$dump.png")
            val diff = HardwareScene.compare(boot.screen, logos.frameRgb(dump), out)
            assertEquals(0, diff, "boot_logos/$dump")
            compared++
        }
        assertEquals(LOGO_FRAMES.size, compared)
    }

    private companion object {
        const val A_FRAME = 181
        const val A_BUTTON = 1
        const val B_FRAME = 470
        const val B_BUTTON = 2
        const val FIRST_TRACED = 400
        const val LAST = 3600

        // BLDCNT darken one step brighter than mGBA (f1170..1210, f2200..2230: see HANDOFF);
        // f3000 / f3010: the title's first frames, sprites one step off;
        // OpAnimHBlank1 frames: its per-line BLDCNT is drawn, its BG2 map block switch is not (78-80 px).
        val SPLIT_FRAMES = setOf(1110, 1120, 1130, 1140)

        val FRAME_GAPS = setOf(1170, 1180, 1190, 1200, 1210, 2200, 2210, 2220, 2230, 3000, 3010)
        val LOGO_FRAMES = listOf(300, 360, 430, 470)
    }
}
