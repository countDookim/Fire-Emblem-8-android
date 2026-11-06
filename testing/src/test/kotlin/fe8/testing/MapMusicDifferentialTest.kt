package fe8.testing

import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.bm.BattleMapKind
import fe8.game.bm.ChapterData
import fe8.game.bm.MapMusic
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import fe8.game.flags.Fe8EventFlags
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * [MapMusic.getCurrentMapMusicIndex] against `ram_trace_prologue` and `ram_trace_first_phase`:
 * at every dump where `gSoundSt.songId` changes to a song the map music chooses (the red and
 * green phase songs 0x13 and the player phase song 9 of the Prologue), the port, fed `gPlaySt`
 * and the unit arrays of that dump, returns that song. The fade and delayed start of
 * [MapMusic.startMapSongBgm] (SoundWrapper) are not compared, so it is not tagged.
 * Event flag 4 (`EVFLAG_BGM_CHANGE`) is taken as clear.
 */
class MapMusicDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    // Not @VerifiesPort: red and green both give 0x13 in chapter 0 (swapping them survives), the
    // victory song and the EVFLAG_BGM_CHANGE alternates never occur in the trace. Breaking the
    // blue slot, the unit threshold or the flag read fails.
    @Test
    fun `map music index follows the song changes of the trace`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val chapters = ChapterData(rom)
        val sound = SoundWrapper(ProcEngine(), null) { 0 }
        val units = UnitTable(rom)
        val playSt = PlaySt()
        val music = MapMusic(
            playSt, chapters, units, Fe8EventFlags.create(), sound,
        ) { BattleMapKind.STORY }
        val soundSt = sym["gSoundSt"].address - GbaMem.EWRAM
        var checked = 0
        val songs = mutableSetOf<Int>()
        for (runName in listOf("ram_trace_prologue", "ram_trace_first_phase")) {
            val dir = File(ref.root, runName)
            val dumps = dir.list { _, n -> n.endsWith(".ewram.bin") }!!
                .map { it.substringBefore('.') }.sortedBy { it.substring(1).toInt() }
            val run = ref.run(runName)
            var prev = -1
            for (d in dumps) {
                val ew = run.region(d, "ewram")
                val song = GbaMem.u16(ew, GbaMem.EWRAM, sym["gSoundSt"].address + 4)
                val playing = ew[soundSt + 6].toInt() != 0
                if (prev != -1 && song != prev && playing && song in MAP_SONGS) {
                    playSt.decode(ew, sym["gPlaySt"].address - GbaMem.EWRAM)
                    units.loadFrom(ew)
                    assertEquals(song, music.getCurrentMapMusicIndex(), "$runName $d")
                    songs += song
                    checked++
                }
                prev = song
            }
        }
        assertTrue(checked >= 2, "only $checked map song changes compared")
        assertTrue(0x13 in songs && 9 in songs, "songs compared: $songs")
    }

    private companion object {
        val MAP_SONGS = setOf(0x13, 9)
    }
}
