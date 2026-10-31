package fe8.testing

import gbafe.audio.M4aPlayer
import gbafe.audio.M4aTrack

/**
 * Reads m4a state (`MusicPlayerInfo`, `MusicPlayerTrack`) out of a reference IWRAM dump, for
 * differential tests of the sequencer. Addresses are GBA addresses in IWRAM.
 */
public object M4aRam {
    /** `gMPlayInfo_*` / `gMPlayTrack_*` pairs (address, track slots) from fireemblem8.nm.txt. */
    public val PLAYERS: Map<String, Pair<Int, Int>> = mapOf(
        "BGM1" to (0x03006440 to 8), "BGM2" to (0x03006650 to 8),
        "SE1_SYS1" to (0x03006690 to 2), "SE2_SYS2" to (0x03006760 to 2),
        "SE3_BMP1" to (0x030066D0 to 4), "SE4_BMP2" to (0x030063C0 to 4),
        "SE5_BMP3" to (0x03006400 to 5), "SE6_BMP4" to (0x03006610 to 4),
        "SE7_EVT" to (0x03006720 to 2),
    )

    private const val IWRAM = 0x03000000
    private const val TRACK_SIZE = 0x50

    /** The player at [address] with its [slots] tracks, read from [iwram]. */
    public fun player(iwram: ByteArray, address: Int, slots: Int): M4aPlayer {
        val o = address - IWRAM
        val tracksAt = u32(iwram, o + 0x2C)
        val p = M4aPlayer(Array(slots) { track(iwram, tracksAt + it * TRACK_SIZE) })
        p.songHeader = u32(iwram, o)
        p.status = u32(iwram, o + 4)
        p.trackCount = u8(iwram, o + 8)
        p.priority = u8(iwram, o + 9)
        p.clock = u32(iwram, o + 0x0C)
        p.tempoD = u16(iwram, o + 0x1C)
        p.tempoU = u16(iwram, o + 0x1E)
        p.tempoI = u16(iwram, o + 0x20)
        p.tempoC = u16(iwram, o + 0x22)
        p.fadeOI = u16(iwram, o + 0x24)
        p.fadeOC = u16(iwram, o + 0x26)
        p.fadeOV = u16(iwram, o + 0x28)
        return p
    }

    /** The track at [address], read from [iwram]. */
    public fun track(iwram: ByteArray, address: Int): M4aTrack {
        val o = address - IWRAM
        fun u(i: Int) = u8(iwram, o + i)
        fun s(i: Int) = iwram[o + i].toInt()
        return M4aTrack().apply {
            flags = u(0)
            wait = u(1)
            patternLevel = u(2)
            repN = u(3)
            gateTime = u(4)
            key = u(5)
            velocity = u(6)
            runningStatus = u(7)
            keyM = u(8)
            pitM = u(9)
            keyShift = s(0x0A)
            keyShiftX = s(0x0B)
            tune = s(0x0C)
            pitX = u(0x0D)
            bend = s(0x0E)
            bendRange = u(0x0F)
            volMR = u(0x10)
            volML = u(0x11)
            vol = u(0x12)
            volX = u(0x13)
            pan = s(0x14)
            panX = s(0x15)
            modM = s(0x16)
            mod = u(0x17)
            modT = u(0x18)
            lfoSpeed = u(0x19)
            lfoSpeedC = u(0x1A)
            lfoDelay = u(0x1B)
            lfoDelayC = u(0x1C)
            priority = u(0x1D)
            cmdPtr = u32(iwram, o + 0x40)
            for (i in 0 until 3) patternStack[i] = u32(iwram, o + 0x44 + 4 * i)
        }
    }

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF

    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)

    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
}
