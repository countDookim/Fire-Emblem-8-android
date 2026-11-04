package fe8.testing

import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Sanity of [M4aRam] on consecutive per-frame dumps: a playing player's `clock` and `tempoC`
 * advance as `MPlayMain`'s tempo loop does (one tick per 150 of `tempoC + tempoI`).
 */
class M4aRamTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    fun `playing players advance clock and tempo like the tempo loop`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        var checked = 0
        for (dir in ref.root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name !in M4A_UNCOVERED_RUNS }) {
            val frames = dir.listFiles().orEmpty().mapNotNull { f ->
                DUMP.matchEntire(f.name)?.let { it.groupValues[1].toInt() to f }
            }.toMap()
            for ((n, file) in frames) {
                val next = frames[n + 1] ?: continue
                val a = file.readBytes()
                val b = next.readBytes()
                for ((address, slots) in M4aRam.PLAYERS.values) {
                    val p = M4aRam.player(a, address, slots)
                    val q = M4aRam.player(b, address, slots)
                    val playing = p.status > 0 && q.status > 0
                    if (!playing || p.tempoI != q.tempoI || p.songHeader != q.songHeader) continue
                    val sum = p.tempoC + p.tempoI
                    val ticks = sum / 150
                    val want = listOf(p.clock + ticks, sum - 150 * ticks)
                    assertEquals(want, listOf(q.clock, q.tempoC), "$next")
                    checked++
                }
            }
        }
        assertTrue(checked > 1000, "checked $checked frame steps")
    }

    private companion object {
        val DUMP = Regex("""r(\d+)\.iwram\.bin""")
    }
}
