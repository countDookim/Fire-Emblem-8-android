package fe8.game.bm

import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `GetCurrentMapMusicIndex` against reference RAM dumps taken while the map BGM plays: the song
 * the game started (`gSoundSt.songId`) must equal what the port picks from the dump's `gPlaySt`,
 * unit arrays and chapter flags. Phases in the traces: Prologue (blue, green), chapter index 7
 * (blue, green). Not covered: the enemy phase (no dump), the victory song and the BGM_CHANGE
 * alternates. The Prologue green song equals its red slot, so it does not tell the two apart. Later
 * blue/green dumps (Prologue r19313, chapter 6 f01620) play event-started songs, which the
 * function does not choose, and are left out.
 */
class MapMusicReferenceTest {
    private val assets =
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"

    private fun songFor(rom: RomView, run: String, stem: String): Pair<Int, Int> {
        val dir = File(assets, "reference/out/$run")
        val ewram = File(dir, "$stem.ewram.bin").readBytes()
        val iwram = File(dir, "$stem.iwram.bin").readBytes()
        val playSt = PlaySt().also { it.decode(ewram, GPLAYST - EWRAM_BASE) }
        val units = UnitTable(rom).also { it.loadFrom(ewram) }
        val flags = Fe8EventFlags.create()
        flags.copyInChapter(iwram, CHAPTER_FLAGS - IWRAM_BASE)
        val sound = SoundWrapper(ProcEngine(), null) { 0 }
        val music = MapMusic(playSt, ChapterData(rom), units, flags, sound) { BattleMapKind.STORY }
        val soundSt = GSOUNDST - EWRAM_BASE
        val playing = (ewram[soundSt + 4].toInt() and 0xFF) or
            ((ewram[soundSt + 5].toInt() and 0xFF) shl 8)
        return music.getCurrentMapMusicIndex() to playing
    }

    @VerifiesPort("GetCurrentMapMusicIndex")
    @Test
    fun `blue and green phase songs equal the song playing in the reference`() {
        val rom = File(assets, "baserom.gba")
        assumeTrue(rom.isFile, "ROM not present")
        val view = RomView.ofRom(rom.readBytes())
        val cases = listOf(
            Triple("ram_trace_first_phase", "f19430", "Prologue blue"),
            Triple("ram_trace_first_phase", "r19280", "Prologue green"),
            Triple("ram_trace_ch6_phase_start", "f01760", "chapter 6 blue"),
        )
        for ((run, stem, label) in cases) {
            assumeTrue(File(assets, "reference/out/$run/$stem.iwram.bin").isFile, "$run missing")
            val (got, expected) = songFor(view, run, stem)
            assertEquals(expected, got, "$label ($run $stem)")
        }
    }

    private companion object {
        const val EWRAM_BASE = 0x02000000
        const val IWRAM_BASE = 0x03000000
        const val GSOUNDST = 0x02024E5C
        const val GPLAYST = 0x0202BCF0
        const val CHAPTER_FLAGS = 0x03005270
    }
}
