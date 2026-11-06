package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.MapCursor
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Differential test of the map cursor sprite against the first phase dumps. */
class MapCursorDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    /**
     * In the full dumps f19430..f19530 hardware OAM objects 0..3 are the cursor (oam2 base 2,
     * kind 0); OAM reflects clock = dump clock - 1.
     */
    @Test
    @VerifiesPort("PutMapCursor")
    fun `map cursor objects match the dump OAM`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val run = ref.run("ram_trace_first_phase")
        val rom = RomView.ofRom(romFile.readBytes())
        val e = GbaMem.EWRAM
        val positions = sortedSetOf<String>()
        val frames = sortedSetOf<Int>()
        for (frame in 19430..19530 step 10) {
            val dump = "f$frame"
            val ew = run.region(dump, "ewram")
            val iw = run.region(dump, "iwram")
            val hw = run.region(dump, "oam")
            val dumpClock = GbaMem.u32(iw, GbaMem.IWRAM, sym["gGameClock"].address)
            val bmSt = BmSt().apply { decode(ew, sym["gBmSt"].address - e) }
            // The dump is taken after the cursor slide advanced past the frame that drew the OAM,
            // so the drawn position is the display position minus up to one slide step.
            var matched = ""
            for (dx in 0..8) {
                for (dy in 0..8) {
                    if (matched.isNotEmpty()) break
                    val layers = SpriteLayers()
                    val port = MapCursor(rom, bmSt, layers)
                    port.gameClock = { dumpClock - 1 }
                    port.put(bmSt.playerCursorDisplayX - dx, bmSt.playerCursorDisplayY - dy, 0)
                    val oam = OamBuffer()
                    layers.pushLayerObjects(4) {
                        oam.putHi(it.oam1, it.oam0, it.obj!!.data, it.oam2)
                    }
                    assertEquals(4, oam.hiPut / 4, "$dump objects")
                    val same = (0 until 12).all {
                        GbaMem.u16(hw, 0, (it / 3) * 8 + 2 * (it % 3)) ==
                            (oam.buffer[(it / 3) * 4 + it % 3].toInt() and 0xFFFF)
                    }
                    if (same) matched = "-$dx,-$dy"
                }
            }
            assertTrue(matched.isNotEmpty(), "$dump (clock $dumpClock): no offset matches OAM")
            println("$dump clock $dumpClock cursor ${bmSt.playerCursorDisplayX}," +
                "${bmSt.playerCursorDisplayY} drawn at offset $matched frame " +
                "${(dumpClock - 1) / 2 % 16}")
            positions += "${bmSt.playerCursorDisplayX},${bmSt.playerCursorDisplayY}"
            frames += ((dumpClock - 1) / 2 % 16).toInt()
        }
        assertTrue(positions.size > 1, "cursor never moves: $positions")
        assertTrue(frames.size > 1, "one animation frame: $frames")
    }
}
