package fe8.testing

import gba.audio.M4aBus
import gba.audio.M4aCommands
import gba.audio.M4aDriver
import gba.core.VerifiesPort
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * On the main menu the game sets BGM1's volume after `SoundMain` every frame
 * (`ram_trace_menu_bgm`, f1290..f1300, a ramp down): one frame, then `m4aMPlayVolumeControl` with
 * the volume the next dump shows, gives that dump's track flags and `volX` on every track, and
 * the `volMR`/`volML` that frame's `MPlayMain` derived from the previous call.
 */
class M4aVolumeTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("m4aMPlayVolumeControl")
    fun `per-frame volume control matches the trace`() {
        val dir = File(ref.root, "ram_trace_menu_bgm")
        assumeTrue(ref.available && romFile.isFile && dir.isDirectory, "reference data not present")
        val rom = romFile.readBytes()
        val bus = M4aBus { x -> rom[x - 0x08000000].toInt() and 0xFF }
        var frames = 0
        for (f in 1290 until 1300) {
            val (players, info) = M4aRam.sound(File(dir, "r%05d.iwram.bin".format(f)).readBytes())
            val (next, _) = M4aRam.sound(File(dir, "r%05d.iwram.bin".format(f + 1)).readBytes())
            val p = players[0]
            // The game's volume ramps down (a music fade proc); take the value it set from the dump.
            val volume = 4 * next[0].tracks[0].volX
            assertTrue(volume in 0..0x100, "volume $volume")
            M4aDriver(bus, info, info.chain()).frame()
            M4aCommands.volumeControl(p, 0xFFFF, volume)
            for (k in 0 until p.trackCount) {
                val (x, y) = next[0].tracks[k] to p.tracks[k]
                assertEquals(
                    listOf(x.flags, x.volX, x.volMR, x.volML),
                    listOf(y.flags, y.volX, y.volMR, y.volML),
                    "r$f track $k",
                )
            }
            frames++
        }
        println("M4aVolumeTest: $frames frames")
    }
}
