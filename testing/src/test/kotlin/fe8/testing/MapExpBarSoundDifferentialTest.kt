package fe8.testing

import fe8.game.Fe8Sound
import gba.audio.M4aBus
import gba.audio.M4aPlayer
import gba.audio.M4aSongTable
import gba.audio.M4aSound
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The exp bar's tick (`SONG_74`, player 3) in `ram_trace_prologue_levelup`: started by
 * `ProcMAExpBar_PlaySound74` (player status 0 at f6303, playing from f6304) and stopped by
 * `m4aSongNumStop` at f6384 (status gains the pause bit, the header stays, f6384 on). The replay
 * starts the song from a fresh sound state, runs the same frames, stops it and compares the
 * player's status each step; the stop must also silence the song's PSG channel (`CgbOscOff`),
 * which the capture's CGB channel shows the song uses.
 */
class MapExpBarSoundDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("m4aSongNumStop", "TrackStop")
    fun `SONG_74 starts at f6303 and its stop at f6384 silences the PSG`() {
        assumeTrue(ref.available && romFile.isFile, "reference data not present")
        val run = ref.run("ram_trace_prologue_levelup")
        val rom = romFile.readBytes()
        val bus = M4aBus { x -> rom[x - 0x08000000].toInt() and 0xFF }
        val sound = M4aSound(
            bus, Fe8Sound.M_PLAY_TABLE, Fe8Sound.PLAYERS,
            M4aSongTable(bus, Fe8Sound.SONG_TABLE, Fe8Sound.SONGS),
        ).also { it.init(Fe8Sound.MODE) }
        val driver = sound.driver(null)
        val player = sound.players[3]

        fun captured(step: Int): M4aPlayer {
            val dump = "%s%05d".format(if (step % 10 == 0) "f" else "r", step)
            return M4aRam.sound(run.region(dump, "iwram")).first[3]
        }
        assertEquals(0, captured(START).status)
        assertEquals(sound.songs.header(SONG_74), captured(START).songHeader)
        sound.songNumStart(SONG_74)
        assertEquals(captured(START).status, player.status, "f$START status")
        var cgbSeen = false
        for (step in START + 1 until STOP) {
            driver.frame()
            val original = captured(step)
            assertEquals(original.songHeader, player.songHeader, "f$step header")
            assertEquals(original.status, player.status, "f$step status")
            cgbSeen = cgbSeen || generateSequence(original.tracks[0].chan) { it.next }
                .any { it.type and 7 != 0 }
        }
        assertTrue(cgbSeen, "the capture's SONG_74 plays on a CGB (PSG) channel")
        val volumeBefore = driver.psg.read(0x04000063) or driver.psg.read(0x04000069)
        assertTrue(volumeBefore ushr 4 != 0, "a PSG note sounds before the stop")

        sound.songNumStop(SONG_74)
        driver.frame()
        val stopped = captured(STOP)
        assertEquals(stopped.songHeader, player.songHeader, "f$STOP header")
        assertEquals(stopped.status, player.status, "f$STOP status")
        assertEquals(null, player.tracks[0].chan, "the stop empties the chain")
        for (reg in listOf(0x04000063, 0x04000069)) {
            assertEquals(0, driver.psg.read(reg) ushr 4, "PSG NRx2 volume after the stop")
        }
    }

    private companion object {
        const val SONG_74 = 0x74
        const val START = 6303
        const val STOP = 6384
    }
}
