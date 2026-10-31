package fe8.testing

import gbafe.audio.M4aChannel
import gbafe.audio.M4aPlayer
import gbafe.audio.M4aSoundInfo
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
        p.tone = u32(iwram, o + 0x30)
        p.ident = u32(iwram, o + 0x34)
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
            echoVolume = u(0x1E)
            echoLength = u(0x1F)
            this.address = address
            for (i in 0 until 3) tone[i] = u32(iwram, o + 0x24 + 4 * i)
            cmdPtr = u32(iwram, o + 0x40)
            for (i in 0 until 3) patternStack[i] = u32(iwram, o + 0x44 + 4 * i)
        }
    }

    /** All [PLAYERS] (same order) and the shared channels, linked as in [iwram]. */
    public fun sound(iwram: ByteArray): Pair<List<M4aPlayer>, M4aSoundInfo> {
        val players = PLAYERS.values.map { (a, n) -> player(iwram, a, n) }
        val tracks = players.flatMap { it.tracks.toList() }.associateBy { it.address }
        val direct = Array(12) { channel(iwram, DIRECT_CHANS + it * CHANNEL_SIZE) }
        val cgb = Array(4) { channel(iwram, CGB_CHANS + it * CHANNEL_SIZE) }
        val chans = (direct + cgb).associateBy { it.address }
        for (c in direct + cgb) {
            val o = c.address - IWRAM
            c.track = tracks[u32(iwram, o + 0x2C)]
            c.prev = chans[u32(iwram, o + 0x30)]
            c.next = chans[u32(iwram, o + 0x34)]
        }
        for (t in tracks.values) t.chan = chans[u32(iwram, t.address - IWRAM + 0x20)]
        val info = M4aSoundInfo(direct, cgb)
        info.maxChans = u8(iwram, SOUND_INFO - IWRAM + 6)
        info.masterVolume = u8(iwram, SOUND_INFO - IWRAM + 7)
        info.pcmDmaCounter = u8(iwram, SOUND_INFO - IWRAM + 4)
        info.reverb = u8(iwram, SOUND_INFO - IWRAM + 5)
        info.pcmDmaPeriod = u8(iwram, SOUND_INFO - IWRAM + 0x0B)
        info.pcmSamplesPerVBlank = u32(iwram, SOUND_INFO - IWRAM + 0x10)
        info.divFreq = u32(iwram, SOUND_INFO - IWRAM + 0x18)
        val pcm = PCM_BUFFER - IWRAM
        iwram.copyInto(info.pcmBuffer, 0, pcm, pcm + info.pcmBuffer.size)
        return players to info
    }

    /** The channel at [address] (DirectSound or CGB; shared offsets), unlinked. */
    public fun channel(iwram: ByteArray, address: Int): M4aChannel {
        val o = address - IWRAM
        return M4aChannel(address).apply {
            status = u8(iwram, o)
            type = u8(iwram, o + 1)
            rightVolume = u8(iwram, o + 2)
            leftVolume = u8(iwram, o + 3)
            adsr = u32(iwram, o + 4)
            key = u8(iwram, o + 8)
            envelopeVolume = u8(iwram, o + 9)
            envelopeRight = u8(iwram, o + 0x0A)
            envelopeLeft = u8(iwram, o + 0x0B)
            count = u32(iwram, o + 0x18)
            fw = u32(iwram, o + 0x1C)
            currentPointer = u32(iwram, o + 0x28)
            echoVolume = u8(iwram, o + 0x0C)
            echoLength = u8(iwram, o + 0x0D)
            gateTime = u8(iwram, o + 0x10)
            midiKey = u8(iwram, o + 0x11)
            velocity = u8(iwram, o + 0x12)
            priority = u8(iwram, o + 0x13)
            rhythmPan = iwram[o + 0x14].toInt()
            length = u8(iwram, o + 0x1E)
            sweep = u8(iwram, o + 0x1F)
            frequency = u32(iwram, o + 0x20)
            wav = u32(iwram, o + 0x24)
        }
    }

    private const val SOUND_INFO = 0x03005410
    private const val PCM_BUFFER = SOUND_INFO + 0x350
    private const val DIRECT_CHANS = 0x03005460
    private const val CGB_CHANS = 0x03006510
    private const val CHANNEL_SIZE = 0x40

    private fun u8(b: ByteArray, o: Int) = b[o].toInt() and 0xFF

    private fun u16(b: ByteArray, o: Int) = u8(b, o) or (u8(b, o + 1) shl 8)

    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
}
