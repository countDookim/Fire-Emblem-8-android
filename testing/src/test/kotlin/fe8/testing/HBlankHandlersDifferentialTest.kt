package fe8.testing

import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.render.HBlankHandler
import gbafe.render.HBlankHandlers
import gbafe.render.LcdControl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * [HBlankHandlers] against mGBA: for a dump with no HBlank handler (`boot_logos` f300, intro logos) and one with
 * `HBlank_BgVerticalScroll` as primary (`menu_screens` m0962, main menu), setting the dumped `sHBlankHandler1/2`
 * on a copy of the dumped `gLCDControlBuffer` whose DISPSTAT HBlank bit was flipped must give back the dumped
 * DISPSTAT, the `REG_IE` HBlank bit of the dumped I/O registers and, with a handler, `gIRQHandlers[1]`.
 * Addresses from decomp/fireemblem8.nm.txt (pinned 04d1edb).
 */
class HBlankHandlersDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()

    @Test
    @VerifiesPort("SetPrimaryHBlankHandler", "SetSecondaryHBlankHandler", "UpdateHBlankHandlerState")
    fun `HBlank handler state matches mGBA`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val scroll = HBlankHandler { _, _ -> }
        var withHandler = 0
        for ((runName, dump) in listOf("boot_logos" to "f00300", "menu_screens" to "m0962")) {
            val run = ref.run(runName)
            val iw = run.region(dump, "iwram")
            val io = run.region(dump, "io")
            fun ptr(name: String) = GbaMem.u32(iw, GbaMem.IWRAM, sym[name].address)
            fun handler(p: Int): HBlankHandler? = when (p) {
                0 -> null
                HBLANK_BG_VERTICAL_SCROLL -> scroll
                else -> error("$runName/$dump: unknown handler 0x%08x".format(p))
            }
            val dumped = LcdControl().apply { readFrom(iw, sym["gLCDControlBuffer"].address - GbaMem.IWRAM) }
            val lcd = LcdControl().apply { readFrom(iw, sym["gLCDControlBuffer"].address - GbaMem.IWRAM) }
            lcd.dispstat = lcd.dispstat xor 0x10
            val h = HBlankHandlers(lcd)
            h.setPrimary(handler(ptr("sHBlankHandler1")))
            h.setSecondary(handler(ptr("sHBlankHandler2")))
            assertEquals(dumped.dispstat, lcd.dispstat, "$runName/$dump dispstat")
            val ie = GbaMem.u16(io, 0, 0x200)
            assertEquals(ie and 2 != 0, h.irqEnabled, "$runName/$dump REG_IE HBlank")
            if (h.primary != null || h.secondary != null) {
                withHandler++
                assertSame(handler(GbaMem.u32(iw, GbaMem.IWRAM, sym["gIRQHandlers"].address + 4)), h.active, "$runName/$dump gIRQHandlers[1]")
            }
        }
        assertEquals(1, withHandler)
    }

    private companion object {
        const val HBLANK_BG_VERTICAL_SCROLL = 0x08014CC5 // Thumb address of HBlank_BgVerticalScroll
    }
}
