package gbafe.audio

/**
 * WIP (M3-13): `SoundMainRAM`, the DirectSound mixer run once per VBlank after the sequencers.
 * So far only its per-channel envelope stage ([envelope]).
 */
public object M4aMixer {
    /**
     * Envelope stage of DirectSound channel [c] for one frame (`SoundMainRAM_ChanLoop` up to the
     * sample loop): starts a new note (sample pointer/count from its WaveData, loop flag 0x10),
     * steps attack (3) / decay (2) / sustain (1) / release (0x40) / echo (4) and sets
     * `ev`/`er`/`el`. Returns false if the channel is (now) silent and is not mixed.
     */
    public fun envelope(c: M4aChannel, info: M4aSoundInfo, bus: M4aBus): Boolean {
        var s = c.status
        if (s and 0xC7 == 0) return false
        var ev: Int
        var attack = false
        if (s and 0x80 != 0) {
            if (s and 0x40 != 0) return stop(c)
            s = 3
            c.currentPointer = c.wav + 0x10
            c.count = read32(bus, c.wav + 0x0C)
            ev = 0
            c.fw = 0
            if (bus.read8(c.wav + 3) and 0xC0 != 0) s = s or 0x10
            c.status = s
            attack = true
        } else {
            ev = c.envelopeVolume
            if (s and 4 != 0) {
                c.echoLength = (c.echoLength - 1) and 0xFF
                if (c.echoLength == 0xFF || c.echoLength == 0) return stop(c)
            } else if (s and 0x40 != 0) {
                ev = (ev * release(c)) ushr 8
                if (ev <= c.echoVolume) {
                    ev = c.echoVolume
                    if (ev == 0) return stop(c)
                    s = s or 4
                    c.status = s
                }
            } else if (s and 3 == 2) {
                ev = (ev * ((c.adsr ushr 8) and 0xFF)) ushr 8
                val sustain = (c.adsr ushr 16) and 0xFF
                if (ev <= sustain) {
                    ev = sustain
                    if (ev == 0) {
                        ev = c.echoVolume
                        if (ev == 0) return stop(c)
                        s = s or 4
                    } else {
                        s -= 1
                    }
                    c.status = s
                }
            } else if (s and 3 == 3) {
                attack = true
            }
        }
        if (attack) {
            ev += c.adsr and 0xFF
            if (ev >= 0xFF) {
                ev = 0xFF
                s -= 1
                c.status = s
            }
        }
        c.envelopeVolume = ev and 0xFF
        val v = ((info.masterVolume + 1) * c.envelopeVolume) ushr 4
        c.envelopeRight = (c.rightVolume * v) ushr 8
        c.envelopeLeft = (c.leftVolume * v) ushr 8
        return true
    }

    private fun release(c: M4aChannel): Int = c.adsr ushr 24

    private fun stop(c: M4aChannel): Boolean {
        c.status = 0
        return false
    }

    private fun read32(bus: M4aBus, a: Int): Int = bus.read8(a) or (bus.read8(a + 1) shl 8) or
        (bus.read8(a + 2) shl 16) or (bus.read8(a + 3) shl 24)
}
