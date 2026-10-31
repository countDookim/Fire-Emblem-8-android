package fe8.testing

import gbafe.audio.M4aTrack
import gbafe.core.VerifiesPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `MusicPlayerTrack` state against every reference RAM dump: for each existing track with no
 * pending volume/pitch flag, `TrkVolPitSet` recomputed from the dumped inputs reproduces the dumped
 * `volMR`, `volML`, `keyM` and `pitM` (MPlayMain ran it after the last input change).
 */
class M4aTrackTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort("TrkVolPitSet")
    fun `volume and pitch of every settled track match the reference RAM dumps`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        var checked = 0
        val distinct = HashSet<List<Int>>()
        val dumps = ref.root.listFiles().orEmpty().filter { it.isDirectory }
            .flatMap { d -> d.listFiles().orEmpty().filter { it.name.endsWith(".iwram.bin") } }
        for (f in dumps) {
            val iw = f.readBytes()
            for ((address, size) in TRACK_ARRAYS) {
                for (t in 0 until size / TRACK_SIZE) {
                    val o = address - IWRAM + t * TRACK_SIZE
                    fun u8(i: Int) = iw[o + i].toInt() and 0xFF
                    fun s8(i: Int) = iw[o + i].toInt()
                    val flags = u8(0)
                    if (flags and M4aTrack.FLG_EXIST == 0 || flags and 0x0F != 0) continue
                    val track = M4aTrack().apply {
                        this.flags = flags or M4aTrack.FLG_VOLSET or M4aTrack.FLG_PITSET
                        keyShift = s8(0x0A)
                        keyShiftX = s8(0x0B)
                        tune = s8(0x0C)
                        pitX = u8(0x0D)
                        bend = s8(0x0E)
                        bendRange = u8(0x0F)
                        vol = u8(0x12)
                        volX = u8(0x13)
                        pan = s8(0x14)
                        panX = s8(0x15)
                        modM = s8(0x16)
                        modT = u8(0x18)
                    }
                    track.volPitSet()
                    val got = listOf(track.volMR, track.volML, track.keyM, track.pitM, track.flags)
                    val want = listOf(u8(0x10), u8(0x11), u8(0x08), u8(0x09), flags)
                    assertEquals(want, got, "${f.path} $o")
                    distinct += got
                    checked++
                }
            }
        }
        val msg = "checked $checked tracks, ${distinct.size} states"
        assertTrue(checked > 10_000 && distinct.size > 100, msg)
    }

    private companion object {
        const val IWRAM = 0x03000000
        const val TRACK_SIZE = 0x50

        /** `gMPlayTrack_*` (address, size) from fireemblem8.nm.txt: BGM1, BGM2, SE1..SE7. */
        val TRACK_ARRAYS = listOf(
            0x03001E38 to 0x280, 0x030020B8 to 0x280, 0x03002338 to 0xA0, 0x030023D8 to 0xA0,
            0x03002478 to 0x140, 0x030025B8 to 0x140, 0x030026F8 to 0x190, 0x03002888 to 0x140,
            0x030029C8 to 0xA0,
        )
    }
}
