package fe8.testing

import gba.audio.M4aChannel
import gba.audio.M4aPlayer
import gba.audio.M4aSoundInfo
import gba.audio.M4aTrack

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
        val offset = address - IWRAM
        val tracksAddress = u32(iwram, offset + 0x2C)
        val player = M4aPlayer(Array(slots) { track(iwram, tracksAddress + it * TRACK_SIZE) })
        player.songHeader = u32(iwram, offset)
        player.status = u32(iwram, offset + 4)
        player.trackCount = u8(iwram, offset + 8)
        player.priority = u8(iwram, offset + 9)
        player.unkB = u8(iwram, offset + 0x0B)
        player.clock = u32(iwram, offset + 0x0C)
        player.tempoD = u16(iwram, offset + 0x1C)
        player.tempoU = u16(iwram, offset + 0x1E)
        player.tempoI = u16(iwram, offset + 0x20)
        player.tempoC = u16(iwram, offset + 0x22)
        player.fadeOI = u16(iwram, offset + 0x24)
        player.fadeOC = u16(iwram, offset + 0x26)
        player.fadeOV = u16(iwram, offset + 0x28)
        player.tone = u32(iwram, offset + 0x30)
        player.ident = u32(iwram, offset + 0x34)
        return player
    }

    /** The track at [address], read from [iwram]. */
    public fun track(iwram: ByteArray, address: Int): M4aTrack {
        val offset = address - IWRAM
        fun readU8(offsetDelta: Int) = u8(iwram, offset + offsetDelta)
        fun readS8(offsetDelta: Int) = iwram[offset + offsetDelta].toInt()
        return M4aTrack().apply {
            flags = readU8(0)
            wait = readU8(1)
            patternLevel = readU8(2)
            repN = readU8(3)
            gateTime = readU8(4)
            key = readU8(5)
            velocity = readU8(6)
            runningStatus = readU8(7)
            keyM = readU8(8)
            pitM = readU8(9)
            keyShift = readS8(0x0A)
            keyShiftX = readS8(0x0B)
            tune = readS8(0x0C)
            pitX = readU8(0x0D)
            bend = readS8(0x0E)
            bendRange = readU8(0x0F)
            volMR = readU8(0x10)
            volML = readU8(0x11)
            vol = readU8(0x12)
            volX = readU8(0x13)
            pan = readS8(0x14)
            panX = readS8(0x15)
            modM = readS8(0x16)
            mod = readU8(0x17)
            modT = readU8(0x18)
            lfoSpeed = readU8(0x19)
            lfoSpeedC = readU8(0x1A)
            lfoDelay = readU8(0x1B)
            lfoDelayC = readU8(0x1C)
            priority = readU8(0x1D)
            echoVolume = readU8(0x1E)
            echoLength = readU8(0x1F)
            this.address = address
            for (i in 0 until 3) tone[i] = u32(iwram, offset + 0x24 + 4 * i)
            cmdPtr = u32(iwram, offset + 0x40)
            for (i in 0 until 3) patternStack[i] = u32(iwram, offset + 0x44 + 4 * i)
        }
    }

    /** All [PLAYERS] (same order) and the shared channels, linked as in [iwram]. */
    public fun sound(iwram: ByteArray): Pair<List<M4aPlayer>, M4aSoundInfo> {
        val players = PLAYERS.values.map { (address, slots) -> player(iwram, address, slots) }
        val tracks = players.flatMap { it.tracks.toList() }.associateBy { it.address }
        val directChannels = Array(12) { channel(iwram, DIRECT_CHANS + it * CHANNEL_SIZE) }
        val cgbChannels = Array(4) { channel(iwram, CGB_CHANS + it * CHANNEL_SIZE) }
        val allChannels = (directChannels + cgbChannels).associateBy { it.address }
        for (channel in directChannels + cgbChannels) {
            val offset = channel.address - IWRAM
            channel.track = tracks[u32(iwram, offset + 0x2C)]
            channel.prev = allChannels[u32(iwram, offset + 0x30)]
            channel.next = allChannels[u32(iwram, offset + 0x34)]
        }
        for (track in tracks.values) {
            track.chan = allChannels[u32(iwram, track.address - IWRAM + 0x20)]
        }
        val info = M4aSoundInfo(directChannels, cgbChannels)
        info.ident = u32(iwram, SOUND_INFO - IWRAM)
        val byAddress = PLAYERS.values.map { it.first }.zip(players).toMap()
        info.intp = byAddress[u32(iwram, SOUND_INFO - IWRAM + 0x24)]
        for ((address, player) in byAddress) {
            player.intp = byAddress[u32(iwram, address - IWRAM + 0x3C)]
        }
        info.maxChans = u8(iwram, SOUND_INFO - IWRAM + 6)
        info.masterVolume = u8(iwram, SOUND_INFO - IWRAM + 7)
        info.pcmDmaCounter = u8(iwram, SOUND_INFO - IWRAM + 4)
        info.reverb = u8(iwram, SOUND_INFO - IWRAM + 5)
        info.pcmDmaPeriod = u8(iwram, SOUND_INFO - IWRAM + 0x0B)
        info.c15 = u8(iwram, SOUND_INFO - IWRAM + 0x0A)
        info.pcmSamplesPerVBlank = u32(iwram, SOUND_INFO - IWRAM + 0x10)
        info.pcmFreq = u32(iwram, SOUND_INFO - IWRAM + 0x14)
        info.divFreq = u32(iwram, SOUND_INFO - IWRAM + 0x18)
        val pcmOffset = PCM_BUFFER - IWRAM
        iwram.copyInto(info.pcmBuffer, 0, pcmOffset, pcmOffset + info.pcmBuffer.size)
        return players to info
    }

    /** The channel at [address] (DirectSound or CGB; shared offsets), unlinked. */
    public fun channel(iwram: ByteArray, address: Int): M4aChannel {
        val offset = address - IWRAM
        return M4aChannel(address).apply {
            status = u8(iwram, offset)
            type = u8(iwram, offset + 1)
            rightVolume = u8(iwram, offset + 2)
            leftVolume = u8(iwram, offset + 3)
            adsr = u32(iwram, offset + 4)
            key = u8(iwram, offset + 8)
            envelopeVolume = u8(iwram, offset + 9)
            envelopeRight = u8(iwram, offset + 0x0A)
            envelopeLeft = u8(iwram, offset + 0x0B)
            count = u32(iwram, offset + 0x18)
            fw = u32(iwram, offset + 0x1C)
            currentPointer = u32(iwram, offset + 0x28)
            echoVolume = u8(iwram, offset + 0x0C)
            echoLength = u8(iwram, offset + 0x0D)
            gateTime = u8(iwram, offset + 0x10)
            midiKey = u8(iwram, offset + 0x11)
            velocity = u8(iwram, offset + 0x12)
            priority = u8(iwram, offset + 0x13)
            rhythmPan = iwram[offset + 0x14].toInt()
            sustainGoal = u8(iwram, offset + 0x19)
            n4 = u8(iwram, offset + 0x1A)
            pan = u8(iwram, offset + 0x1B)
            panMask = u8(iwram, offset + 0x1C)
            modify = u8(iwram, offset + 0x1D)
            length = u8(iwram, offset + 0x1E)
            sweep = u8(iwram, offset + 0x1F)
            frequency = u32(iwram, offset + 0x20)
            wav = u32(iwram, offset + 0x24)
        }
    }

    private const val SOUND_INFO = 0x03005410
    private const val PCM_BUFFER = SOUND_INFO + 0x350
    private const val DIRECT_CHANS = 0x03005460
    private const val CGB_CHANS = 0x03006510
    private const val CHANNEL_SIZE = 0x40

    /** Reads an unsigned 8-bit integer from [bytes] at [offset]. */
    private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF

    /** Reads a little-endian unsigned 16-bit integer from [bytes] at [offset]. */
    private fun u16(bytes: ByteArray, offset: Int): Int =
        u8(bytes, offset) or (u8(bytes, offset + 1) shl 8)

    /** Reads a little-endian unsigned 32-bit integer from [bytes] at [offset]. */
    private fun u32(bytes: ByteArray, offset: Int): Int =
        u16(bytes, offset) or (u16(bytes, offset + 2) shl 16)
}
