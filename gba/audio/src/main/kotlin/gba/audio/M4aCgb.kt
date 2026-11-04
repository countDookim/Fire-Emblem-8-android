package gba.audio

/**
 * The CGB (PSG) half of the m4a driver: `CgbSound` steps the four CGB channels' envelopes once
 * per frame and writes the PSG registers ([M4aPsg]). Channel state is verified against the
 * per-frame RAM traces (`M4aNoteTest`, incl. `ram_trace_menu_cgb`); the dumps hold no IO, so the
 * register writes follow the source but are not compared.
 */
public object M4aCgb {
    private val CGB3_VOL = intArrayOf(
        0x00, 0x00, 0x60, 0x60, 0x60, 0x60, 0x40, 0x40, 0x40, 0x40, 0x80, 0x80, 0x80, 0x80,
        0x20, 0x20,
    )

    // Register offsets per channel 1..4 (index 0..3): NRx0..NRx4.
    private val NRX0 = intArrayOf(0x04000060, 0x04000061, 0x04000070, 0x04000071)
    private val NRX1 = intArrayOf(0x04000062, 0x04000068, 0x04000072, 0x04000078)
    private val NRX2 = intArrayOf(0x04000063, 0x04000069, 0x04000073, 0x04000079)
    private val NRX3 = intArrayOf(0x04000064, 0x0400006C, 0x04000074, 0x0400007C)
    private val NRX4 = intArrayOf(0x04000065, 0x0400006D, 0x04000075, 0x0400007D)

    private const val L_ED0 = 1
    private const val EC_MINUS = 2
    private const val L_F02 = 3
    private const val L_F3A = 4
    private const val L_F5A = 5
    private const val L_F96 = 6
    private const val L_E82 = 7
    private const val L_FD6 = 8
    private const val LAST = 9

    /**
     * `CgbSound`: once per frame (in `SoundMain`, before the mixer), for each active CGB channel:
     * start (registers, envelope attack), release, echo and the attack/decay/sustain envelope
     * stepped every 15th frame (`c15`), then the volume/frequency register updates flagged in
     * `mo`. The original's gotos are a label loop here.
     *
     * Ported from src/m4a.c: CgbSound
     */
    public fun sound(info: M4aSoundInfo, psg: M4aPsg, bus: M4aBus) {
        val chans = info.cgbChans ?: return
        if (info.c15 != 0) info.c15-- else info.c15 = 14
        for (ch in 1..4) {
            val c = chans[ch - 1]
            if (c.status and 0xC7 == 0) continue
            val x = ch - 1
            var prevC15 = info.c15
            var evAdd = psg.read(NRX2[x])
            val at = c.adsr and 0xFF
            val de = (c.adsr ushr 8) and 0xFF
            val su = (c.adsr ushr 16) and 0xFF
            val re = (c.adsr ushr 24) and 0xFF
            var label: Int
            val sf = c.status
            if (sf and 0x80 != 0) {
                if (sf and 0x40 == 0) {
                    c.status = 3
                    c.modify = 3
                    modVol(c)
                    var envStart = true
                    when (ch) {
                        1, 2 -> {
                            if (ch == 1) psg.write(NRX0[x], c.sweep)
                            psg.write(NRX1[x], (c.wav shl 6) + c.length)
                        }
                        3 -> {
                            if (c.wav != c.currentPointer) {
                                psg.write(NRX0[x], 0x40)
                                for (k in 0 until 16) {
                                    psg.write(M4aPsg.WAVE_RAM + k, bus.read8(c.wav + k))
                                }
                                c.currentPointer = c.wav
                            }
                            psg.write(NRX0[x], 0)
                            psg.write(NRX1[x], c.length)
                            c.n4 = if (c.length != 0) 0xC0 else 0x80
                            envStart = false
                        }
                        else -> {
                            psg.write(NRX1[x], c.length)
                            psg.write(NRX3[x], c.wav shl 3)
                        }
                    }
                    if (envStart) {
                        evAdd = at + 8
                        c.n4 = if (c.length != 0) 0x40 else 0
                    }
                    c.envelopeLeft = at
                    if (at != 0) {
                        c.envelopeVolume = 0
                        label = EC_MINUS
                    } else {
                        label = L_F96
                    }
                } else {
                    label = L_E82
                }
            } else if (sf and 0x04 != 0) {
                c.echoLength = (c.echoLength - 1) and 0xFF
                label = if (c.echoLength.toByte() <= 0) L_E82 else L_FD6
            } else if (sf and 0x40 != 0 && sf and 0x03 != 0) {
                c.status = sf and 0xFC
                c.envelopeLeft = re
                if (re != 0) {
                    c.modify = c.modify or 1
                    if (ch != 3) evAdd = re
                    label = EC_MINUS
                } else {
                    label = L_F02
                }
            } else {
                label = L_ED0
            }
            while (label != LAST) {
                label = when (label) {
                    L_ED0 -> if (c.envelopeLeft == 0) envelope(c, ch, re, de) else EC_MINUS
                    L_F02 -> {
                        c.envelopeVolume =
                            ((c.envelopeRight * c.echoVolume + 0xFF) shr 8) and 0xFF
                        if (c.envelopeVolume != 0) {
                            c.status = c.status or 4
                            c.modify = c.modify or 1
                            if (ch != 3) evAdd = 8
                            L_FD6
                        } else {
                            L_E82
                        }
                    }
                    L_F3A -> {
                        c.envelopeVolume = c.sustainGoal
                        c.envelopeLeft = 7
                        EC_MINUS
                    }
                    L_F5A -> if (su == 0) {
                        c.status = c.status and 0xFC
                        L_F02
                    } else {
                        c.status = (c.status - 1) and 0xFF
                        c.modify = c.modify or 1
                        if (ch != 3) evAdd = 8
                        L_F3A
                    }
                    L_F96 -> {
                        c.status = (c.status - 1) and 0xFF
                        c.envelopeLeft = de
                        if (de != 0) {
                            c.modify = c.modify or 1
                            c.envelopeVolume = c.envelopeRight
                            if (ch != 3) evAdd = de
                            EC_MINUS
                        } else {
                            L_F5A
                        }
                    }
                    L_E82 -> {
                        info.cgbOscOff?.invoke(ch)
                        oscOff(psg, ch)
                        c.status = 0
                        LAST
                    }
                    EC_MINUS -> {
                        c.envelopeLeft = (c.envelopeLeft - 1) and 0xFF
                        if (prevC15 == 0) {
                            prevC15--
                            L_ED0
                        } else {
                            L_FD6
                        }
                    }
                    else -> { // L_FD6
                        writeRegs(c, ch, psg, evAdd)
                        LAST
                    }
                }
            }
            c.modify = 0
        }
    }

    /** The `ec == 0` envelope step of `CgbSound` (label loc_82E0ED0); returns the next label. */
    private fun envelope(c: M4aChannel, ch: Int, re: Int, de: Int): Int {
        if (ch == 3) c.modify = c.modify or 1
        modVol(c)
        when (c.status and 3) {
            0 -> {
                c.envelopeVolume = (c.envelopeVolume - 1) and 0xFF
                if (c.envelopeVolume.toByte() <= 0) return L_F02
                c.envelopeLeft = re
            }
            1 -> return L_F3A
            2 -> {
                c.envelopeVolume = (c.envelopeVolume - 1) and 0xFF
                if (c.envelopeVolume.toByte() <= c.sustainGoal.toByte()) return L_F5A
                c.envelopeLeft = de
            }
            else -> {
                c.envelopeVolume = (c.envelopeVolume + 1) and 0xFF
                if (c.envelopeVolume >= c.envelopeRight) return L_F96
                c.envelopeLeft = c.adsr and 0xFF
            }
        }
        return EC_MINUS
    }

    /** Label loc_82E0FD6 of `CgbSound`: frequency (`mo` bit 1) and volume/pan (bit 0) writes. */
    private fun writeRegs(c: M4aChannel, ch: Int, psg: M4aPsg, evAddIn: Int) {
        val x = ch - 1
        if (c.modify and 2 != 0) {
            if (ch < 4 && c.type and 0x08 != 0) {
                val biasH = psg.soundBiasH
                if (biasH < 64) {
                    c.frequency = (c.frequency + 2) and 0x7FC
                } else if (biasH < 128) {
                    c.frequency = (c.frequency + 1) and 0x7FE
                }
            }
            if (ch != 4) {
                psg.write(NRX3[x], c.frequency)
            } else {
                psg.write(NRX3[x], (psg.read(NRX3[x]) and 0x08) or c.frequency)
            }
            c.n4 = ((c.n4 and 0xC0) + ((c.frequency ushr 8) and 0xFF)) and 0xFF
            psg.write(NRX4[x], c.n4)
        }
        if (c.modify and 1 != 0) {
            val nr51 = psg.read(M4aPsg.NR51)
            psg.write(M4aPsg.NR51, (nr51 and c.panMask.inv()) or c.pan)
            if (ch == 3) {
                psg.write(NRX2[x], CGB3_VOL[c.envelopeVolume and 0x0F])
                if (c.n4 and 0x80 != 0) {
                    psg.write(NRX0[x], 0x80)
                    psg.write(NRX4[x], c.n4)
                    c.n4 = c.n4 and 0x7F
                }
            } else {
                val evAdd = evAddIn and 0x0F
                psg.write(NRX2[x], (c.envelopeVolume shl 4) + evAdd)
                psg.write(NRX4[x], c.n4 or 0x80)
                if (ch == 1 && psg.read(NRX0[x]) and 0x08 == 0) psg.write(NRX4[x], c.n4 or 0x80)
            }
        }
    }

    /** `CgbOscOff`: silences PSG channel [ch] (1..4). */
    public fun oscOff(psg: M4aPsg, ch: Int) {
        val x = ch - 1
        if (ch == 3) {
            psg.write(NRX0[x], 0)
        } else {
            psg.write(NRX2[x], 8)
            psg.write(NRX4[x], 0x80)
        }
    }

    /**
     * `CgbModVol`: hard pan (0x0F right / 0xF0 left) when one side is at most half the other,
     * else both (0xFF); envelope goal `eg` = (right + left) / 16 (capped at 15 when hard-panned),
     * sustain goal `sg` = (eg * su + 15) >> 4; `pan` masked by `panMask`.
     *
     * Ported from src/m4a.c: CgbModVol
     */
    public fun modVol(c: M4aChannel) {
        val r = c.rightVolume and 0xFF
        val l = c.leftVolume and 0xFF
        val hard = if (r >= l) r / 2 >= l else l / 2 >= r
        var eg = (c.rightVolume + c.leftVolume) / 16
        if (hard) {
            c.pan = if (r >= l) 0x0F else 0xF0
            if (eg > 15) eg = 15
        } else {
            c.pan = 0xFF
        }
        eg = eg and 0xFF
        c.envelopeRight = eg // `eg` shares offset 0x0A with envelopeRight
        val su = (c.adsr ushr 16) and 0xFF
        c.sustainGoal = ((eg * su + 15) shr 4) and 0xFF
        c.pan = c.pan and c.panMask
    }
}
