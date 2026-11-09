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
 * alternates. The Prologue green song equals its red slot, and which call started it is unchecked,
 * so only blue counts as verified. Prologue r19313 and chapter 6 f01620 are left out: they are
 * pre-switch states still playing the previous song (the blue songs start at r19387 / r01754).
 */
class MapMusicReferenceTest {
    private val assets =
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"

    @VerifiesPort("StartMapSongBgm")
    @Test
    fun `starting the map song leaves the sound state of the reference`() {
        val rom = File(assets, "baserom.gba")
        assumeTrue(rom.isFile, "ROM not present")
        val view = RomView.ofRom(rom.readBytes())
        val cases = listOf(
            Triple("ram_trace_first_phase", "r19386", "r19387"),
            Triple("ram_trace_ch6_phase_start", "r01753", "r01754"),
        )
        for ((run, before, after) in cases) {
            val dir = File(assets, "reference/out/$run")
            assumeTrue(File(dir, "$after.ewram.bin").isFile, "$run missing")
            val pre = File(dir, "$before.ewram.bin").readBytes()
            assertEquals(0, pre[GSOUNDST - EWRAM_BASE + 6].toInt(), "$before song stopped")
            val sound = SoundWrapper(ProcEngine(), null) { 0 }
            val playSt = PlaySt().also { it.decode(pre, GPLAYST - EWRAM_BASE) }
            val iw = File(dir, "$before.iwram.bin").readBytes()
            val flags = Fe8EventFlags.create()
                .also { it.copyInChapter(iw, CHAPTER_FLAGS - IWRAM_BASE) }
            val units = UnitTable(view).also { it.loadFrom(pre) }
            MapMusic(playSt, ChapterData(view), units, flags, sound) { BattleMapKind.STORY }
                .startMapSongBgm()
            val post = File(dir, "$after.ewram.bin").readBytes()
            val o = GSOUNDST - EWRAM_BASE
            val song = (post[o + 4].toInt() and 0xFF) or ((post[o + 5].toInt() and 0xFF) shl 8)
            assertEquals(song, sound.songId, "songId $after")
            assertEquals(post[o + 6].toInt() != 0, sound.isSongPlaying, "is_song_playing $after")
            assertEquals(post[o + 7].toInt(), sound.unk7, "unk7 $after")
            assertEquals(post[o + 8].toInt(), sound.maxChannels, "maxChannels $after")
        }
    }

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
