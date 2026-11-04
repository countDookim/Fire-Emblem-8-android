package fe8.testing

import gba.core.VerifiesPort
import gbafe.audio.M4aBus
import gbafe.audio.M4aCommands
import gbafe.audio.M4aDriver
import gbafe.audio.M4aPlayer
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The main menu stops the title BGM (`ram_trace_menu_bgm`, f1301): from the f1300 dump, one
 * `SoundMain` frame and then `m4aMPlayStop` on BGM1 give the dumped player (paused, tracks kept)
 * with every track's channel chain emptied and those channels silenced and unlinked. Track flags
 * are compared without VOLCHG/PITCHG: the game sets them after `SoundMain` every frame.
 */
class M4aStopTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("m4aMPlayStop", "TrackStop")
    fun `stopping BGM1 matches the next dump`() {
        val dir = File(ref.root, "ram_trace_menu_bgm")
        val before = File(dir, "r01300.iwram.bin")
        val after = File(dir, "r01301.iwram.bin")
        assumeTrue(ref.available && romFile.isFile && after.isFile, "reference data not present")
        val rom = romFile.readBytes()
        val bus = M4aBus { x -> rom[x - 0x08000000].toInt() and 0xFF }
        val (players, info) = M4aRam.sound(before.readBytes())
        val (dumped, infoB) = M4aRam.sound(after.readBytes())
        val bgm = players[0]
        assertEquals(0, bgm.status and M4aPlayer.STATUS_PAUSE, "BGM1 already paused")
        M4aDriver(bus, info, info.chain()).frame()
        val linked = (0 until bgm.trackCount).flatMap { k ->
            generateSequence(bgm.tracks[k].chan) { it.next }.toList()
        }
        assertTrue(linked.isNotEmpty(), "no BGM1 channel to stop")
        M4aCommands.mPlayStop(bgm, info)
        val d = dumped[0]
        assertEquals(
            listOf(d.status, d.ident, d.songHeader, d.trackCount),
            listOf(bgm.status, bgm.ident, bgm.songHeader, bgm.trackCount),
        )
        for (k in 0 until bgm.trackCount) {
            // VOLCHG/PITCHG (0x03) are set again each frame by the game's volume control.
            assertEquals(d.tracks[k].flags and 0xFC, bgm.tracks[k].flags and 0xFC, "track $k")
            assertEquals(null, d.tracks[k].chan, "dumped track $k chain")
            assertEquals(null, bgm.tracks[k].chan, "track $k chain")
        }
        val byAddress = (infoB.chans + (infoB.cgbChans ?: emptyArray())).associateBy { it.address }
        for (c in linked) {
            val e = byAddress.getValue(c.address)
            assertEquals(listOf(e.status, e.track), listOf(c.status, c.track), "chan ${c.address}")
            assertEquals(0, c.status)
        }
        println("M4aStopTest: ${linked.size} channels stopped")
    }
}
