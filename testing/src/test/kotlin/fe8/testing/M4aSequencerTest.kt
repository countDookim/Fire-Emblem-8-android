package fe8.testing

import gbafe.audio.M4aSequencer
import gbafe.audio.M4aTrack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * WIP (M3-13): one [M4aSequencer.step] from each per-frame dump reproduces the next dump's track
 * state, for tracks that met no unmodelled command (notes etc.) in that step.
 */
class M4aSequencerTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    fun `track state steps like the per-frame RAM traces`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val rom = romFile.readBytes()
        val seq = M4aSequencer { a -> rom[a - 0x08000000].toInt() and 0xFF }
        var checked = 0
        var skipped = 0
        for (dir in ref.root.listFiles().orEmpty().filter { it.isDirectory }) {
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
                    if (p.songHeader != q.songHeader || q.fadeOI != 0 || !seq.step(p)) continue
                    for (i in 0 until p.trackCount) {
                        if (seq.unsupported and (1 shl i) != 0) {
                            skipped++
                            continue
                        }
                        assertEquals(state(q.tracks[i]), state(p.tracks[i]), "$next $address track $i")
                        checked++
                    }
                }
            }
        }
        assertTrue(checked > 1000, "checked $checked tracks ($skipped skipped)")
        println("M4aSequencerTest: checked $checked, skipped $skipped")
    }

    private fun state(t: M4aTrack): List<Int> = listOf(
        t.flags, t.wait, t.patternLevel, t.repN, t.runningStatus, t.keyM, t.pitM, t.keyShift,
        t.tune, t.bend, t.bendRange, t.volMR, t.volML, t.vol, t.volX, t.pan, t.modM, t.mod, t.modT,
        t.lfoSpeed, t.lfoSpeedC, t.lfoDelay, t.lfoDelayC, t.priority, t.cmdPtr,
    ) + t.tone.toList()

    private companion object {
        val DUMP = Regex("""r(\d+)\.iwram\.bin""")
    }
}
