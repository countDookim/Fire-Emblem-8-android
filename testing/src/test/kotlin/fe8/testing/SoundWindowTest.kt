package fe8.testing

import fe8.game.Fe8Sound
import fe8.game.RomView
import gbafe.audio.M4aSound
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
        val a = play(Fe8Sound.create(window))
        val b = play(Fe8Sound.create(full))
        assertEquals(b.size, a.size)
        assertEquals(b.contentHashCode(), a.contentHashCode())
        assertTrue(a.distinct().size > 1000, "mostly silent: ${a.distinct().size} distinct buffers")
        println("SoundWindowTest: ${a.size / FRAMES} songs")
    }

    /** Starts each song in turn for [FRAMES] frames; returns the hashes of every frame's buffer. */
    private fun play(s: M4aSound): IntArray {
        val driver = s.driver(null)
        val hashes = mutableListOf<Int>()
        for (n in 0 until Fe8Sound.SONGS) {
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
        const val FRAMES = 30
    }
}
