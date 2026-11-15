package fe8.testing

import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.bm.BattleMapKind
import fe8.game.bm.ChapterData
import fe8.game.bm.MapMusic
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** The boss phase's already-playing map song at f3797, and the preceding song state. */
class EventMusicDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val symbols = RamSymbols.fromEnvironment()
    private val romFile = File(System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    @Test
    @VerifiesPort("GetCurrentBgmSong")
    fun `map music call preserves the song already playing in the capture`() {
        assumeTrue(ref.available && romFile.isFile)
        val run = ref.run("ram_trace_prologue_clear")
        val soundAddress = symbols["gSoundSt"].address
        val rom = RomView.ofRom(romFile.readBytes())
        val engine = ProcEngine()
        val sound = SoundWrapper(engine, null) { 0 }
        fun song(frame: Int): Int = GbaMem.u16(
            run.region("r%05d".format(frame), "ewram"), GbaMem.EWRAM, soundAddress + 4,
        )
        // The previous and current songs give the getter two different captured values.
        sound.startBgmCore(song(2963), -1)
        assertEquals(song(2963), sound.getCurrentBgmSong())
        sound.startBgmCore(song(3796), -1)
        assertEquals(song(3796), sound.getCurrentBgmSong())

        val before = run.region("r03796", "ewram")
        val playSt = PlaySt().apply { decode(before, symbols["gPlaySt"].address - GbaMem.EWRAM) }
        val units = UnitTable(rom).apply { loadFrom(before) }
        val music = MapMusic(playSt, ChapterData(rom), units, Fe8EventFlags.create(), sound) {
            BattleMapKind.STORY
        }
        music.startMapSongBgm()
        val after = run.region("r03797", "ewram")
        assertEquals(GbaMem.u16(after, GbaMem.EWRAM, soundAddress + 4), sound.getCurrentBgmSong())
        assertEquals(after[soundAddress + 6 - GbaMem.EWRAM].toInt() != 0, sound.isSongPlaying)
        assertNull(engine.find(sound.delayedSongScript))
    }
}
