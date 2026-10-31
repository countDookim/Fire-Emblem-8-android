package gbafe.audio

/** `SoundMainRAM`, the DirectSound mixer run once per VBlank after the sequencers. */
public object M4aMixer {
    /**
     * One `SoundMainRAM` pass (after the sequencers and `CgbSound`): reverbs (or clears) this
     * frame's [M4aSoundInfo.pcmSamplesPerVBlank] samples of both PCM halves at the slot given by
     * `pcmDmaCounter`, then runs each DirectSound channel's [envelope] and mixes it in (fixed-rate
     * voices 1:1, others linearly interpolated at `divFreq * freq`, looping or stopping at the
     * wave end). The `maxLines` VCOUNT cut-off is not modelled (FE8 sets 0).
     *
     * Ported from src/m4a_1.s: SoundMainRAM
     */
    public fun frame(info: M4aSoundInfo, bus: M4aBus) {
        val spv = info.pcmSamplesPerVBlank
        val buf = info.pcmBuffer
        val n = M4aSoundInfo.PCM_DMA_BUF_SIZE
        val r7 = info.pcmDmaCounter - 1
        val at = if (r7 == 0) 0 else (info.pcmDmaPeriod - r7) * spv
        if (info.reverb != 0) {
            var other = if (info.pcmDmaCounter == 2) 0 else at + spv
            for (i in at until at + spv) {
                var v = buf[i + n] + buf[i] + buf[other + n] + buf[other]
                other++
                v = (v * info.reverb) shr 9
                if (v and 0x80 != 0) v += 1
                buf[i + n] = v.toByte()
                buf[i] = v.toByte()
            }
        } else {
            buf.fill(0, at, at + spv)
            buf.fill(0, at + n, at + n + spv)
        }
        for (c in 0 until info.maxChans) {
            val ch = info.chans[c]
            if (envelope(ch, info, bus)) mix(ch, info, bus, at)
        }
    }

    private fun mix(c: M4aChannel, info: M4aSoundInfo, bus: M4aBus, at: Int) {
        val buf = info.pcmBuffer
        val left = M4aSoundInfo.PCM_DMA_BUF_SIZE
        val loop = c.status and 0x10 != 0
        val loopStart = if (loop) c.wav + 0x10 + read32(bus, c.wav + 8) else 0
        val loopLen = if (loop) read32(bus, c.wav + 0x0C) - read32(bus, c.wav + 8) else 0
        val er = c.envelopeRight
        val el = c.envelopeLeft
        fun put(i: Int, s: Int) {
            buf[at + i] = (buf[at + i] + ((er * s) shr 8)).toByte()
            buf[at + i + left] = (buf[at + i + left] + ((el * s) shr 8)).toByte()
        }
        fun s8(a: Int): Int = (bus.read8(a) shl 24) shr 24
        var ct = c.count
        var p = c.currentPointer
        val spv = info.pcmSamplesPerVBlank
        if (c.type and 8 != 0) {
            for (i in 0 until spv) {
                put(i, s8(p))
                p++
                ct--
                if (ct == 0) {
                    if (loopLen == 0) {
                        c.status = 0
                        return
                    }
                    ct = loopLen
                    p = loopStart
                }
            }
            c.count = ct
            c.currentPointer = p
            return
        }
        var fw = c.fw
        val step = info.divFreq * c.frequency
        var cur = s8(p)
        p += 1
        var diff = s8(p) - cur
        for (i in 0 until spv) {
            put(i, cur + ((fw * diff) shr 23))
            fw += step
            var adv = fw ushr 23
            if (adv == 0) continue
            fw = fw and 0x3F800000.inv()
            ct -= adv
            if (ct <= 0) {
                if (loopLen == 0) {
                    c.status = 0
                    return
                }
                p = loopStart
                adv = -ct
                while (true) {
                    ct += loopLen
                    if (ct > 0) break
                    adv -= loopLen
                }
                p += adv
                cur = s8(p)
            } else {
                adv -= 1
                if (adv == 0) {
                    cur += diff
                } else {
                    p += adv
                    cur = s8(p)
                }
            }
            p += 1
            diff = s8(p) - cur
        }
        c.fw = fw
        c.count = ct
        c.currentPointer = p - 1
    }

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
