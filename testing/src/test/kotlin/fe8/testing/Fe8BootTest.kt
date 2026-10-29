package fe8.testing

import fe8.game.Fe8Boot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The device boot wiring ([Fe8Boot]: game loop + game control + early start screens + allocation-free
 * frame pipeline), started from power-on state with assets read from the ROM, draws the same
 * `boot_logos` frames as mGBA (same boot, A pressed at f181).
 */
class Fe8BootTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile =
        File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    @Test
    fun `boot from power-on draws the mGBA logo frames`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        var f = 0
        val boot = Fe8Boot(
            Fe8Boot.Assets.fromRom(romFile.readBytes()),
            keyInput = { if (f == A_FRAME) A_BUTTON else 0 },
            colorMath = MgbaColorMath,
        )
        // The clock is 0 at f10 (before StartGame); frame numbering as in EarlyStartUiBootDifferentialTest.
        val clockAt = sym["gGameClock"].address
        val clock20 = GbaMem.u32(ref.run("ram_trace_boot").region("r00020", "iwram"), GbaMem.IWRAM, clockAt)
            .toLong() and 0xFFFFFFFFL
        while (boot.clock.get() < clock20) boot.frame()
        val logos = ref.run("boot_logos")
        var compared = 0
        for (frame in 21..LOGO_FRAMES.last()) {
            f = frame
            boot.frame()
            if (frame !in LOGO_FRAMES) continue
            boot.render()
            val dump = "f%05d".format(frame)
            val out = File(System.getProperty("fe8.frameDiffDir") ?: "build/frame-diff", "fe8boot_$dump.png")
            assertEquals(0, HardwareScene.compare(boot.screen, logos.frameRgb(dump), out), "boot_logos/$dump")
            compared++
        }
        assertEquals(LOGO_FRAMES.size, compared)
    }

    private companion object {
        const val A_FRAME = 181
        const val A_BUTTON = 1
        val LOGO_FRAMES = listOf(300, 360, 430, 470)
    }
}
