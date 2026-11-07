package fe8.testing

import fe8.game.SoundWrapper
import gba.audio.M4aBus
import gba.audio.M4aDriver
import gba.audio.M4aSongTable
import gba.audio.M4aSound
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * A BGM volume ramp in `ram_trace_newgame` (f1174..1193): [SoundWrapper.startBgmVolumeChange]
 * and its loop, seeded from the f1174 dump, give each next dump's track flags, volX and volMR/volML
 * (`SaveMenu_NewGame`'s call: 0xC0 to 0 over 0x10).
 */
class BgmVolumeChangeTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "StartBgmVolumeChange", "MusicVc_OnLoop", "Sound_SetSEVolume", "m4aSongNumStop",
    )
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
        // The playing song is the one whose header BGM1 holds; gSoundSt.songId is not in the dump.
        val song = (1 until 1000).first { sound.songs.header(it) == dump[0].songHeader }
        wrapper.startBgmCore(song, -1)
        for (i in 0..1) {
            val (to, from) = sound.players[i] to dump[i]
            to.songHeader = from.songHeader
            to.status = from.status
            to.trackCount = from.trackCount
            to.priority = from.priority
            to.unkB = from.unkB
            to.ident = from.ident
            to.clock = from.clock
            to.tempoD = from.tempoD
            to.tempoU = from.tempoU
            to.tempoI = from.tempoI
            to.tempoC = from.tempoC
            to.fadeOI = from.fadeOI
            to.fadeOC = from.fadeOC
            to.fadeOV = from.fadeOV
            to.tone = from.tone
            for (k in 0 until from.trackCount) to.tracks[k] = from.tracks[k]
        }
        // SoundMain runs the wrapper's players, so stopping the song (the ramp's end) shows.
        val chain = info.chain().map { p ->
            dump.indexOf(p).let { if (it in 0..1) sound.players[it] else p }
        }
        wrapper.startBgmVolumeChange(0xC0, 0, 0x10, null) // SaveMenu_NewGame
        for (f in 1174..1192) {
            val (next, _) = M4aRam.sound(File(dir, "r%05d.iwram.bin".format(f + 1)).readBytes())
            M4aDriver(bus, info, chain).frame()
            // f1175 and f1181 run no volume call (flags 128): the game did not run its procs
            // those frames (a lag frame; the trace has no other record of it).
            if (next[0].tracks[0].flags and VOLUME_FLAGS != 0) engine.run(engine.root(3))
            for (i in 0..1) {
                assertEquals(next[i].status, sound.players[i].status, "r$f player $i status")
                for (k in 0 until sound.players[i].trackCount) {
                    val (x, y) = next[i].tracks[k] to sound.players[i].tracks[k]
                    assertEquals(
                        listOf(x.flags, x.volX, x.volMR, x.volML),
                        listOf(y.flags, y.volX, y.volMR, y.volML),
                        "r$f player $i track $k",
                    )
                }
            }
        }
        assertEquals(listOf(false, 0), listOf(wrapper.isSongPlaying, wrapper.songId))
    }

    private companion object {
        const val VOLUME_FLAGS = 3
    }
}
