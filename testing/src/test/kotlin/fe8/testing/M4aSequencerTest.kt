package fe8.testing

import gbafe.audio.M4aSequencer
import gbafe.audio.M4aTrack
import gbafe.core.VerifiesPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * One [M4aSequencer.step] from each per-frame dump reproduces the next dump's track
 * state, for tracks that met no unmodelled command (notes etc.) in that step. The sequencer itself
 * stays WIP (M3-13); the complete `ply_*` handlers that ran in matching steps are verified.
 */
class M4aSequencerTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("ply_pend", "ply_tempo", "ply_keysh", "ply_voice", "ply_vol", "ply_pan")
    fun `track state steps like the per-frame RAM traces`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val rom = romFile.readBytes()
        val seq = M4aSequencer({ a -> rom[a - 0x08000000].toInt() and 0xFF })
        var checked = 0
        var skipped = 0
        val runs = IntArray(64)
        val skipCmd = IntArray(256) // last command byte (runningStatus) of skipped tracks
        // Menu traces: the game re-flags BGM track volume after SoundMain every frame (not
        // modelled here); M4aStopTest and M4aFadeTest cover them.
        val dirs = ref.root.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith("ram_trace_menu") }
        for (dir in dirs) {
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
                            skipCmd[p.tracks[i].runningStatus]++
                            continue
                        }
                        val msg = "$next $address track $i"
                        assertEquals(state(q.tracks[i]), state(p.tracks[i]), msg)
                        checked++
                        for (c in 0 until 64) {
                            if (seq.commandsRun[i] ushr c and 1L != 0L) runs[c]++
                        }
                    }
                }
            }
        }
        assertTrue(checked > 1000, "checked $checked tracks ($skipped skipped)")
        // Each claimed handler ran in matching steps (indices: command - 0xB1).
        for (cmd in listOf(0xB4, 0xBB, 0xBC, 0xBD, 0xBE, 0xBF)) {
            val n = runs[cmd - 0xB1]
            assertTrue(n >= 4, "command 0x%02X ran %d times".format(cmd, n))
        }
        println("M4aSequencerTest: checked $checked, skipped $skipped")
        println("M4aSequencerTest: commands " + (0 until 64).filter { runs[it] > 0 }
            .joinToString { "0x%02X=%d".format(it + 0xB1, runs[it]) })
        val skips = (0 until 256).filter { skipCmd[it] > 0 }
            .joinToString { "0x%02X=%d".format(it, skipCmd[it]) }
        println("M4aSequencerTest: skipped by last status $skips")
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
