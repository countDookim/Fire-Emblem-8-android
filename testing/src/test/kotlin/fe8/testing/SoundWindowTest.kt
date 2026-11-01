package fe8.testing

import fe8.game.RomView
import gbafe.audio.M4aBus
import gbafe.audio.M4aSongTable
import gbafe.audio.M4aSound
import gbafe.audio.M4aSoundInfo
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * [RomView.SOUND_WINDOWS] holds every byte the m4a driver reads: each song of `gSongTable`,
 * started by number and run for a while, mixes from the extracted window alone (a read outside
 * it throws) and gives the same PCM as with the whole ROM.
 */
class SoundWindowTest {
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    fun `every song plays from the sound window`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val rom = romFile.readBytes()
        val window = RomView.ofSoundWindows { name ->
            val start = RomView.SOUND_WINDOWS.first { RomView.windowFile(it.first) == name }
            rom.copyOfRange(start.first - RomView.ROM_BASE, start.second - RomView.ROM_BASE)
        }
        val full = RomView.ofRom(rom)
        val a = play(M4aBus { window.u8(it) })
        val b = play(M4aBus { full.u8(it) })
        assertEquals(b.size, a.size)
        assertEquals(b.contentHashCode(), a.contentHashCode())
        assertTrue(a.distinct().size > 1000, "mostly silent: ${a.distinct().size} distinct buffers")
        println("SoundWindowTest: ${a.size / FRAMES} songs")
    }

    /** Starts each song in turn for [FRAMES] frames; returns the hashes of every frame's buffer. */
    private fun play(bus: M4aBus): IntArray {
        val s = M4aSound(bus, M_PLAY_TABLE, PLAYERS, M4aSongTable(bus, SONG_TABLE, SONGS))
        s.init(MODE)
        val driver = s.driver(null)
        val hashes = mutableListOf<Int>()
        for (n in 0 until SONGS) {
            if (s.songs.header(n) == 0) continue
            s.songNumStart(n)
            repeat(FRAMES) {
                driver.frame()
                hashes += s.info.pcmBuffer.contentHashCode()
            }
        }
        return hashes.toIntArray()
    }

    private companion object {
        const val M_PLAY_TABLE = 0x08224404
        const val SONG_TABLE = 0x08224470
        const val PLAYERS = 9
        const val SONGS = 1000
        const val FRAMES = 30
        const val MODE = 0x900000 or M4aSoundInfo.FREQ_13379 or (13 shl 12) or (7 shl 8)
    }
}
