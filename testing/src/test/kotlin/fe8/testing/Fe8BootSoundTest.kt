package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.Fe8Sound
import fe8.game.RomView
import gba.core.VerifiesPort
import gbafe.audio.M4aPlayer
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The boot with sound ([Fe8Boot] with [Fe8Sound]): with the `ram_trace_logo_skip` inputs (A at
 * f181, B at f470) the game's sound calls (H&S / logo / op anim fade-outs, the op anim BGM, the
 * title's BGM change and sound effects) give the traced m4a players and `gSoundSt` at every
 * dumped frame up to the title (f3600).
 */
class Fe8BootSoundTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val assetsDir =
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
    private val romFile = File(assetsDir, "baserom.gba")

    @Test
    @VerifiesPort(
        "Sound_FadeOutBGM", "StartBgmCore", "StartOrChangeBgm", "StartBgmExt", "StartSongDelayed",
        "DelaySong_OnLoop", "Sound_SetDefaultMaxNumChannels", "Sound_SetMaxNumChannels",
        "sub_80028FC", "OpAnimInit", "SetupOpAnimWorldMapfx",
    )
    fun `boot sound state matches the traces`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val rom = RomView.ofRom(romFile.readBytes())
        var f = 0
        val keys = { if (f == A_FRAME) A_BUTTON else if (f == B_FRAME) B_BUTTON else 0 }
        val boot = Fe8Boot(rom, keyInput = keys, colorMath = MgbaColorMath, m4a = Fe8Sound.create(rom))
        val clockAt = sym["gGameClock"].address
        val iwram20 = ref.run("ram_trace_boot").region("r00020", "iwram")
        val clock20 = GbaMem.u32(iwram20, GbaMem.IWRAM, clockAt).toLong() and 0xFFFFFFFFL
        while (boot.clock.get() < clock20) boot.frame()
        val soundSt = sym["gSoundSt"].address
        val m4a = boot.m4a!!
        val wrapper = boot.control.sound
        var compared = 0
        val songs = mutableSetOf<Int>()
        for (frame in 21..LAST) {
            f = frame
            boot.frame()
            val run = traceRun(frame)
            val name = dumpName(run, frame) ?: continue
            val iw = run.region(name, "iwram")
            val ew = run.region(name, "ewram")
            val (players, _) = M4aRam.sound(iw)
            for (i in players.indices) {
                assertEquals(state(players[i]), state(m4a.players[i]), "$name player $i")
            }
            val traced = listOf(
                GbaMem.u16(ew, GbaMem.EWRAM, soundSt + 4),
                ew[soundSt + 6 - GbaMem.EWRAM].toInt(),
                ew[soundSt + 7 - GbaMem.EWRAM].toInt(),
                ew[soundSt + 8 - GbaMem.EWRAM].toInt(),
            )
            val ours = listOf(
                wrapper.songId, if (wrapper.isSongPlaying) 1 else 0, wrapper.unk7,
                wrapper.maxChannels,
            )
            assertEquals(traced, ours, "$name gSoundSt (songId, playing, unk7, maxChannels)")
            assertEquals(iw[MAX_CHANS - GbaMem.IWRAM].toInt(), m4a.info.maxChans, "$name maxChans")
            if (wrapper.isSongPlaying) songs += wrapper.songId
            compared++
        }
        println("Fe8BootSoundTest: $compared dumps, BGM songs $songs")
        assertTrue(compared > 1000, "compared $compared")
        assertEquals(setOf(0x01, 0x02), songs)
    }

    private fun state(p: M4aPlayer): List<Int> = listOf(
        p.songHeader, p.status, p.clock, p.tempoD, p.tempoI, p.fadeOI, p.fadeOC, p.fadeOV,
        p.priority,
    )

    private fun traceRun(frame: Int) = ref.run(
        when {
            frame <= 520 -> "ram_trace_logo_skip"
            frame <= 1100 -> "ram_trace_opanim"
            frame <= 1600 -> "ram_trace_opanim2"
            frame <= 2600 -> "ram_trace_opanim3"
            frame <= 3000 -> "ram_trace_opanim4"
            else -> "ram_trace_title1"
        },
    )

    private fun dumpName(run: ReferenceData.Run, frame: Int): String? =
        listOf("r%05d", "f%05d").map { it.format(frame) }
            .firstOrNull { File(ref.root, "${run.name}/$it.iwram.bin").isFile }

    private companion object {
        const val A_FRAME = 181
        const val A_BUTTON = 1
        const val B_FRAME = 470
        const val B_BUTTON = 2
        const val LAST = 3600

        /** `gSoundInfo.maxChans`. */
        const val MAX_CHANS = 0x03005410 + 6
    }
}
