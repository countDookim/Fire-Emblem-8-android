package fe8.testing

import gbafe.audio.M4aBus
import gbafe.audio.M4aCommands
import gbafe.audio.M4aSongTable
import gbafe.audio.M4aSound
import gbafe.audio.M4aSoundInfo
import gbafe.audio.PcmStream
import gbafe.core.VerifiesPort
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * [M4aSound.init] (`m4aSoundInit`) from the ROM's `gMPlayTable` gives the first boot dump with
 * sound set up (f20): the `SoundMain` chain, idle players and the `SoundInfo` rate fields; then a
 * song the op anim plays, started by number, mixes into the stream.
 */
class M4aSoundTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun sound(rom: ByteArray): M4aSound {
        val bus = M4aBus { x -> rom[x - ROM].toInt() and 0xFF }
        return M4aSound(bus, M_PLAY_TABLE, 9, M4aSongTable(bus, SONG_TABLE, 1000))
    }

    @Test
    @VerifiesPort("m4aSoundInit")
    fun `init gives the traced chain, idle players and rates`() {
        val dump = File(ref.root, "ram_trace_boot/r00020.iwram.bin")
        assumeTrue(ref.available && romFile.isFile && dump.isFile, "reference data not present")
        val iw = dump.readBytes()
        val s = sound(romFile.readBytes())
        s.init(MODE)
        M4aCommands.fadeOut(s.players[0], 1) // sub_8002AC8
        M4aCommands.fadeOut(s.players[1], 1)
        val addresses = M4aRam.PLAYERS.values.map { it.first }
        assertEquals(M4aRam.PLAYERS.values.map { it.second }, s.players.map { it.tracks.size })
        val traced = mutableListOf<Int>()
        var a = u32(iw, SOUND_INFO_INTP)
        while (a ushr 24 == 3 && traced.size < 16) {
            traced += a
            a = u32(iw, a - IWRAM + 0x3C)
        }
        assertEquals(traced, s.info.chain().map { addresses[s.players.indexOf(it)] })
        val (dumped, d) = M4aRam.sound(iw)
        var idle = 0
        for (i in s.players.indices) {
            if (dumped[i].songHeader != 0) continue
            idle++
            val (p, q) = dumped[i] to s.players[i]
            assertEquals(
                listOf(p.status, p.trackCount, p.unkB, p.ident, p.clock, p.fadeOI, p.fadeOC),
                listOf(q.status, q.trackCount, q.unkB, q.ident, q.clock, q.fadeOI, q.fadeOC),
                "player $i",
            )
        }
        assertTrue(idle >= 1, "no idle player")
        val i = s.info
        assertEquals(
            listOf(d.pcmSamplesPerVBlank, d.pcmDmaPeriod, d.pcmFreq, d.maxChans, d.masterVolume),
            listOf(i.pcmSamplesPerVBlank, i.pcmDmaPeriod, i.pcmFreq, i.maxChans, i.masterVolume),
        )
    }

    @Test
    fun `a song started by number plays`() {
        val dump = File(ref.root, "ram_trace_opanim/r00600.iwram.bin")
        assumeTrue(ref.available && romFile.isFile && dump.isFile, "reference data not present")
        val (dumped, _) = M4aRam.sound(dump.readBytes())
        val s = sound(romFile.readBytes())
        val header = dumped[0].songHeader
        val n = (0 until 1000).first { s.songs.header(it) == header && s.songs.player(it) == 0 }
        s.init(MODE)
        val pcm = PcmStream(s.info.pcmFreq, 48000, capacity = 16384)
        val driver = s.driver(pcm)
        s.songNumStart(n)
        assertEquals(header, s.players[0].songHeader)
        val out = ShortArray(2 * 800)
        var loud = false
        repeat(300) {
            driver.frame()
            pcm.render(out, 800) // ~one frame at 48 kHz
            loud = loud || out.any { it.toInt() != 0 }
        }
        assertTrue(loud, "silence (song $n)")
        assertEquals(0, s.sequencer.unsupported)
        println("M4aSoundTest: song $n")
    }

    private fun u32(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or
        ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or
        ((b[o + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val ROM = 0x08000000
        const val IWRAM = 0x03000000
        const val M_PLAY_TABLE = 0x08224404
        const val SONG_TABLE = 0x08224470
        const val SOUND_INFO_INTP = 0x03005410 + 0x24 - IWRAM

        /** FE8 `m4aSoundInit`: DA_BIT_8 | FREQ_13379 | 13 << MASVOL | 7 << MAXCHN. */
        const val MODE = 0x900000 or M4aSoundInfo.FREQ_13379 or (13 shl 12) or (7 shl 8)
    }
}
