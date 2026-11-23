package fe8.testing

import gbafe.render.BgHardware
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Asserts the VBlank sync requests a ported function leaves: exactly the BGs of [bgMask]
 * (`BG_EnableSyncByMask` bits, `hardware.modifiedBgs`) and, when [palette] is true,
 * `EnablePaletteSync` (`hardware.modifiedPalette`). Tilemap and palette buffers alone do not
 * reach VRAM or palette RAM without them.
 */
internal fun assertSyncRequested(
    hardware: BgHardware,
    bgMask: Int,
    palette: Boolean,
    message: String = "",
) {
    assertEquals(bgMask, hardware.modifiedBgs, "BG sync mask $message")
    assertEquals(palette, hardware.modifiedPalette, "palette sync $message")
}
