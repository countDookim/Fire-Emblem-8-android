package fe8.testing

import gbafe.extract.m4a.M4aSong
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M2-18: m4a song → MIDI. `gSongTable` 0x08224470 (decomp/fireemblem8.nm.txt; `sound/song_table.s`, 8-byte
 * `song` entries), song 1 = `song001_agbfe3_bgm_opening`, checked against the decomp's
 * `sound/songs/midi/song001_agbfe3_bgm_opening.mid` (pinned 04d1edb). Dumps `testing/build/extracted/songs/`.
 */
class SongsRomTest {
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    private fun song(id: Int): M4aSong.Song {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val header = (rom[0x224470 + 8 * id].toInt() and 0xFF) or ((rom[0x224471 + 8 * id].toInt() and 0xFF) shl 8) or
            ((rom[0x224472 + 8 * id].toInt() and 0xFF) shl 16) or ((rom[0x224473 + 8 * id].toInt() and 0xFF) shl 24)
        return M4aSong.decode(rom, header - 0x08000000)
    }

    @Test
    fun `song 1 note events match the decomp midi`() {
        val s = song(1)
        // From song001_agbfe3_bgm_opening.mid: notes per track, first keys of track 0, first tempos (µs/quarter
        // 535714, 500000, 416666 = TEMPO 56, 60, 72). A one-off full compare (every note's time, key, velocity,
        // length; tempo map; total 42.36 s) against that file found 0 mismatches.
        assertEquals(listOf(151, 62, 103, 123, 49, 34, 283, 147), s.tracks.map { t -> t.count { it.type == "NOTE" || it.type == "TIE" } })
        assertEquals(listOf(76, 78, 80, 82, 83, 85, 87, 87, 78, 76, 75, 73), s.tracks[0].filter { it.type == "NOTE" }.take(12).map { it.args[0] })
        assertEquals(listOf(56, 60, 72), s.tracks.flatten().filter { it.type == "TEMPO" }.sortedBy { it.time }.take(3).map { it.args[0] })
    }

    @Test
    fun `dump song 1 midi`() {
        val s = song(1)
        val dir = File(System.getProperty("fe8.extractedOut") ?: "build/extracted", "songs")
        dir.mkdirs()
        File(dir, "song001.mid").writeBytes(M4aSong.toMidi(s))
        assertEquals(true, s.tracks.isNotEmpty())
    }
}
