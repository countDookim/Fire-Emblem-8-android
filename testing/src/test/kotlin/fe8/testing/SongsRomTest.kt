package fe8.testing

import gbafe.extract.m4a.M4aSong
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * m4a song → MIDI. `gSongTable` 0x08224470 (decomp/fireemblem8.nm.txt; `sound/song_table.s`, 8-byte
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
    fun `every song table entry decodes`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        // gSongTable: 0x1F40 bytes = 1000 entries over 594 distinct song labels (sound/song_table.s). A one-off
        // compare of all 589 decomp sound/songs/midi/*.mid with our MIDI (note time/key/velocity/length per
        // track) found 0 mismatches; the other 5 are dummy_song and 4 MML-sourced sound effects.
        val table = M4aSong.readSongTable(rom, 0x224470, 1000)
        assertEquals(594, table.map { it.header }.distinct().size)
        for (h in table.map { it.header }.distinct()) M4aSong.toMidi(M4aSong.decode(rom, h - 0x08000000))
        assertEquals(listOf(1, 1), listOf(table[4].musicPlayer, table[4].unknown)) // song song004_..., 1, 1
    }

    @Test
    fun `direct-sound samples match the decomp`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        // DirectSoundData_k_tubular_c4_13k_s 0x082263B4 (sound/direct_sound_samples/k_tubular_c4_13k_s.aif):
        // looping, loop start 3608, 5571 samples, 13379 Hz. A one-off compare of all 439 extracted samples
        // (every song's voicegroup, keysplits and drum kits followed) with the decomp's 439 .aif files found
        // identical PCM and sample rates.
        val w = M4aSong.readWave(rom, 0x2263B4)
        assertEquals(listOf(0, 0x4000, 3608, 5571, 13379), listOf(w.type, w.status, w.loopStart, w.data.size, w.sampleRate))
        val table = M4aSong.readSongTable(rom, 0x224470, 1000)
        val vgs = table.map { it.header }.distinct().map { M4aSong.decode(rom, it - 0x08000000) }.filter { it.tracks.isNotEmpty() }.map { it.voicegroup - 0x08000000 }.toSet()
        assertEquals(84, vgs.size)
        assertEquals(439, M4aSong.collectWaves(rom, vgs).size)
        val wav = M4aSong.toWav(w)
        assertEquals("RIFF", String(wav, 0, 4))
    }

    @Test
    fun `voicegroup000 matches the decomp`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        // voicegroup000 0x08207470 (sound/voicegroups/voicegroup000.s): 0 = voice_square_1 0, 2, 0, 0, 15, 0;
        // 14 = voice_directsound 60, 0, DirectSoundData_k_tubular_c4_13k_s, 255, 246, 0, 165.
        val v = M4aSong.readVoicegroup(romFile.readBytes(), 0x207470)
        assertEquals(128, v.size)
        assertEquals(listOf(1, 60, 0, 0, 2, 0, 0, 15, 0).map { it.toString() }, listOf("type", "key", "length", "panSweep", "data", "attack", "decay", "sustain", "release").map { v[0][it].toString() })
        assertEquals(listOf(0, 60, 0, 0, 255, 246, 0, 165), listOf("type", "key", "length", "panSweep", "attack", "decay", "sustain", "release").map { v[14][it] })
    }

    @Test
    fun `dump song 1 midi`() {
        val s = song(1)
        val dir = File(System.getProperty("fe8.extractedOut") ?: "build/extracted", "songs")
        dir.mkdirs()
        File(dir, "song001.mid").writeBytes(M4aSong.toMidi(s))
        val vg = M4aSong.readVoicegroup(romFile.readBytes(), s.voicegroup - 0x08000000)
        File(dir, "voicegroup_song001.json").writeText(gba.extract.Json.write(linkedMapOf("format" to "gbafe.voicegroup", "game" to "fe8", "version" to 1, "voices" to vg)))
        assertEquals(true, s.tracks.isNotEmpty())
    }
}
