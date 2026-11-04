package fe8.testing

import gba.core.VerifiesPort
import gbafe.audio.M4aBus
import gbafe.audio.M4aDriver
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The main menu fades BGM1 out at speed 4 (`ram_trace_menu_fade`, f4908..f4972): from each dump,
 * one `SoundMain` frame (`MPlayMain` -> `FadeOutBody`) gives the next dump's fade counters,
 * player status and track volumes, through the final step that stops and pauses the player.
 * Frames where the game itself changes the fade (`MPlayFadeOut`, verified elsewhere) are skipped.
 */
class M4aFadeTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("FadeOutBody")
    fun `fade steps match the trace`() {
        val dir = File(ref.root, "ram_trace_menu_fade")
        assumeTrue(ref.available && romFile.isFile && dir.isDirectory, "reference data not present")
        val rom = romFile.readBytes()
        val bus = M4aBus { x -> rom[x - 0x08000000].toInt() and 0xFF }
        var steps = 0
        var stops = 0
        for (f in 4900 until 4980) {
            val a = File(dir, "r%05d.iwram.bin".format(f))
            val b = File(dir, "r%05d.iwram.bin".format(f + 1))
            if (!a.isFile || !b.isFile) continue // inside a 2-frame key hold: no dump
            val (players, info) = M4aRam.sound(a.readBytes())
            val (next, _) = M4aRam.sound(b.readBytes())
            val p = players[0]
            val q = next[0]
            if (p.fadeOI == 0 || q.fadeOI !in listOf(0, p.fadeOI)) continue // not fading / game call
            M4aDriver(bus, info, info.chain()).frame()
            val m = "r$f -> r${f + 1}"
            assertEquals(
                listOf(q.fadeOI, q.fadeOC, q.fadeOV, q.status),
                listOf(p.fadeOI, p.fadeOC, p.fadeOV, p.status),
                m,
            )
            for (k in 0 until p.trackCount) {
                val (x, y) = q.tracks[k] to p.tracks[k]
                assertEquals(listOf(x.flags and 0xFC, x.volX), listOf(y.flags and 0xFC, y.volX), "$m track $k")
                assertEquals(x.chan == null, y.chan == null, "$m track $k chain")
            }
            steps++
            if (p.status < 0) stops++
        }
        assertTrue(steps >= 40 && stops == 1, "steps $steps, stops $stops")
        println("M4aFadeTest: $steps frames, $stops stop")
    }
}
