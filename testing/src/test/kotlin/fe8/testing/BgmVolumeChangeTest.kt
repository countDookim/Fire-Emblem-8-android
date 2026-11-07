package fe8.testing

import fe8.game.SoundWrapper
import gba.audio.M4aBus
import gba.audio.M4aDriver
import gba.audio.M4aSongTable
import gba.audio.M4aSound
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * A BGM volume ramp in `ram_trace_newgame` (f1174..1193): [SoundWrapper.startBgmVolumeChange]
 * and its loop, seeded from the f1174 dump, give each next dump's track flags and volMR/volML.
 */
class BgmVolumeChangeTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    // Disabled: track flags alternate 131/128 per dump (trace frame/MPlayMain timing unresolved),
    // and the ramp args (0xC0 -> 0 over 0x13) are guessed, not the save menu's.
    @Disabled
    @Test
    fun `volume ramp matches the trace`() {
        val dir = File(ref.root, "ram_trace_newgame")
        assumeTrue(ref.available && romFile.isFile && dir.isDirectory, "reference data not present")
        val rom = romFile.readBytes()
        val bus = M4aBus { x -> rom[x - 0x08000000].toInt() and 0xFF }
        val sound = M4aSound(bus, 0x08224404, 9, M4aSongTable(bus, 0x08224470, 1000))
        val engine = ProcEngine().apply { init() }
        val wrapper = SoundWrapper(engine, sound) { 0 }
        val at = File(dir, "r01174.iwram.bin").readBytes()
        val (dump, info) = M4aRam.sound(at)
        for (i in 0..1) {
            sound.players[i].trackCount = dump[i].trackCount
            for (k in 0 until dump[i].trackCount) sound.players[i].tracks[k] = dump[i].tracks[k]
        }
        wrapper.startBgmVolumeChange(0xC0, 0, TIME, null)
        for (f in 1174..1192) {
            val (next, _) = M4aRam.sound(File(dir, "r%05d.iwram.bin".format(f + 1)).readBytes())
            M4aDriver(bus, info, info.chain()).frame()
            engine.run(engine.root(3))
            for (i in 0..1) {
                for (k in 0 until sound.players[i].trackCount) {
                    val (x, y) = next[i].tracks[k] to sound.players[i].tracks[k]
                    assertEquals(
                        listOf(x.flags, x.volMR, x.volML),
                        listOf(y.flags, y.volMR, y.volML),
                        "r$f player $i track $k",
                    )
                }
            }
        }
    }

    private companion object {
        const val TIME = 0x13
    }
}
