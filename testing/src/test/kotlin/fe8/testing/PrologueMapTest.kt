package fe8.testing

import gba.core.VerifiesPort
import gba.testkit.AllocationMeter
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * M5-59: the app path (boot, title A presses, New Game stand-in, real beginning events) reaches the
 * Prologue map. BG3 is compared with `ram_trace_first_phase` (idle player phase of chapter 0, the
 * map fits the screen so the camera is the same without seeding: the test checks it equals the
 * dump's). Only the map tiles (the 32x32 `gBG3TilemapBuffer`) are compared, not BG3 scroll or
 * palettes: the BG3 tag covers the window draw only at camera (0,0). The prologue has no fog,
 * so only palette bank 6 of `DisplayBmTile` is exercised (the bank 11 branch is held by the fog
 * tests).
 */
class PrologueMapTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("RenderBmMap", "DisplayBmTile")
    fun `BG3 of the Prologue map matches the first phase reference`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / reference data not present")
        val game = PrologueBoot(romFile)
        assertTrue(game.reachIdle(), "Prologue player phase not reached")
        val run = ref.run("ram_trace_first_phase")
        val ew = run.region("f19430", "ewram")
        val off = sym["gBG3TilemapBuffer"].address - GbaMem.EWRAM
        val cam = sym["gBmSt"].address - GbaMem.EWRAM + 0x0C
        fun s16(at: Int) = ((ew[at].toInt() and 0xFF) or (ew[at + 1].toInt() shl 8)).toShort().toInt()
        val bm = game.boot.loop.bmSt
        assertEquals(s16(cam), bm.cameraX, "camera x")
        assertEquals(s16(cam + 2), bm.cameraY, "camera y")
        val bg3 = game.boot.ui.env.hardware.tilemaps[3]
        var mismatches = 0
        var first = -1
        for (i in 0 until 1024) {
            val want = ((ew[off + 2 * i].toInt() and 0xFF) or (ew[off + 2 * i + 1].toInt() shl 8))
                .toShort()
            if (bg3[i] != want) {
                if (first < 0) first = i
                mismatches++
            }
        }
        assertEquals(0, mismatches, "BG3 entries differing, first at $first")
    }

    @Test
    fun `600 idle frames with cursor input allocate nothing`() {
        assumeTrue(AllocationMeter.supported && romFile.isFile, "ROM / meter not available")
        val game = PrologueBoot(romFile)
        assertTrue(game.reachIdle(), "Prologue player phase not reached")
        val dirs = arrayOf<Any>(0x0010, 0x0080, 0x0020, 0x0040) // RIGHT, DOWN, LEFT, UP
        val none: Any = 0
        var n = 0
        val bm = game.boot.loop.bmSt
        val warmup = 3000
        val iterations = 600
        val rounds = 3
        val movesPerRound = IntArray(rounds)
        var lastX = bm.playerCursorX
        var lastY = bm.playerCursorY
        val bytes = AllocationMeter.measureMin(warmup, iterations, rounds) {
            game.keys = if (n / 15 % 2 == 0) dirs[n / 30 % 4] else none
            n++
            game.boot.frame()
            val moved = bm.playerCursorX != lastX || bm.playerCursorY != lastY
            lastX = bm.playerCursorX
            lastY = bm.playerCursorY
            if (moved && n - 1 >= warmup) movesPerRound[(n - 1 - warmup) / iterations]++
        }
        assertEquals(0L, bytes)
        for (r in 0 until rounds) {
            assertTrue(movesPerRound[r] > 0, "the cursor never moved in measured round $r")
        }
    }
}
