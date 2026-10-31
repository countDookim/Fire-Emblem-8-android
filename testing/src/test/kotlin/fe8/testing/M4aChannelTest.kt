package fe8.testing

import gbafe.audio.M4aChannel
import gbafe.audio.M4aTrack
import gbafe.core.VerifiesPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Channel volumes against every reference RAM dump: for each active DirectSound or CGB channel that
 * heads its track's channel chain (`track->chan`) on a track with no pending flag, `ChnVolSetAsm`
 * from the dumped inputs reproduces the dumped `rightVolume`/`leftVolume`.
 *
 * Only the head channel is checked: `ply_note` clears all `MPT_FLG_*CHG` bits after starting a
 * note, so older (releasing) channels keep the volume from before a `VOL` change in that tick.
 */
class M4aChannelTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort("ChnVolSetAsm")
    fun `head channel volumes match the reference RAM dumps`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        var direct = 0
        var cgb = 0
        val dumps = ref.root.listFiles().orEmpty().filter { it.isDirectory }
            .flatMap { d -> d.listFiles().orEmpty().filter { it.name.endsWith(".iwram.bin") } }
        for (f in dumps) {
            val iw = f.readBytes()
            val ew = File(f.path.replace(".iwram.bin", ".ewram.bin")).readBytes()
            for ((base, count) in listOf(DIRECT_CHANS to 12, CGB_CHANS to 4)) {
                for (c in 0 until count) {
                    val o = base - IWRAM + c * CHANNEL_SIZE
                    val trackAddress = u32(iw, o + 0x2C)
                    if (iw[o].toInt() == 0 || trackAddress == 0) continue
                    val (tb, to) = if (trackAddress ushr 24 == 3) {
                        iw to trackAddress - IWRAM
                    } else {
                        ew to trackAddress - EWRAM
                    }
                    if (tb[to].toInt() and 0x0F != 0 || u32(tb, to + 0x20) != o + IWRAM) continue
                    val track = M4aTrack().apply {
                        volMR = tb[to + 0x10].toInt() and 0xFF
                        volML = tb[to + 0x11].toInt() and 0xFF
                    }
                    val chan = M4aChannel().apply {
                        velocity = iw[o + 0x12].toInt() and 0xFF
                        rhythmPan = iw[o + 0x14].toInt()
                    }
                    chan.volSet(track)
                    val want = listOf(iw[o + 2].toInt() and 0xFF, iw[o + 3].toInt() and 0xFF)
                    assertEquals(want, listOf(chan.rightVolume, chan.leftVolume), "${f.path} $o")
                    if (base == DIRECT_CHANS) direct++ else cgb++
                }
            }
        }
        assertTrue(direct > 10_000 && cgb > 100, "checked $direct DirectSound, $cgb CGB channels")
    }

    private fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val IWRAM = 0x03000000
        const val EWRAM = 0x02000000
        const val CHANNEL_SIZE = 0x40
        const val DIRECT_CHANS = 0x03005460 // gSoundInfo.chans (gSoundInfo + 0x50)
        const val CGB_CHANS = 0x03006510 // gCgbChans
    }
}
