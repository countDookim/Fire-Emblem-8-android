package gba.audio

/** Byte reader over the GBA address space holding song data (ROM in practice). */
public fun interface M4aBus {
    /** The byte (0..255) at GBA [address]. */
    public fun read8(address: Int): Int
}

/**
 * The m4a track commands that only touch track/player state (`ply_*` in m4a_1.s). Each reads its
 * arguments at [M4aTrack.cmdPtr] (already past the command byte), like the original.
 *
 * `ld_r3_tp_adr_i` and `ldrb_r3_r2` go through `chk_adr_r2`, which reads 0 below 0x02000000 (the
 * BIOS guard: the jump table template it allows is in ROM, so nothing below EWRAM passes);
 * `ld_r3_tp_adr_i_unchecked` (`LFOS`, `MOD`) and the other `GOTO`/`REPT` bytes read directly.
 * Functions without a `Ported from` line are not verified by a test yet (no claim, rule 5).
 */
public object M4aCommands {
    /** `ld_r3_tp_adr_i`: next argument byte through `chk_adr_r2`. */
    private fun arg(t: M4aTrack, bus: M4aBus): Int = checked(bus, t.cmdPtr).also { t.cmdPtr += 1 }

    /** `ld_r3_tp_adr_i_unchecked`. */
    private fun argUnchecked(t: M4aTrack, bus: M4aBus): Int =
        bus.read8(t.cmdPtr).also { t.cmdPtr += 1 }

    /** `chk_adr_r2` applied to a byte read: 0 for addresses below 0x02000000. */
    private fun checked(bus: M4aBus, a: Int): Int = if (a ushr 25 == 0) 0 else bus.read8(a)

    private fun read32(bus: M4aBus, a: Int): Int =
        bus.read8(a) or (bus.read8(a + 1) shl 8) or (bus.read8(a + 2) shl 16) or
            (bus.read8(a + 3) shl 24)

    private fun s8(v: Int): Int = (v shl 24) shr 24

    /** `GOTO`: jump to the 32-bit address at cmdPtr (low byte read through `chk_adr_r2`). */
    public fun goto(t: M4aTrack, bus: M4aBus) {
        val a = t.cmdPtr
        t.cmdPtr = checked(bus, a) or (bus.read8(a + 1) shl 8) or (bus.read8(a + 2) shl 16) or
            (bus.read8(a + 3) shl 24)
    }

    /** `PATT`: call; at depth 3 behaves as `FINE` ([fine] is invoked). */
    public fun patt(t: M4aTrack, bus: M4aBus, fine: (M4aTrack) -> Unit) {
        if (t.patternLevel >= 3) {
            fine(t)
            return
        }
        t.patternStack[t.patternLevel] = t.cmdPtr + 4
        t.patternLevel += 1
        goto(t, bus)
    }

    /**
     * `PEND`: return from a `PATT`.
     *
     * Ported from src/m4a_1.s: ply_pend
     */
    public fun pend(t: M4aTrack) {
        if (t.patternLevel == 0) return
        t.patternLevel -= 1
        t.cmdPtr = t.patternStack[t.patternLevel]
    }

    /** `REPT n addr`: loop n times (0 = forever). */
    public fun rept(t: M4aTrack, bus: M4aBus) {
        val n = bus.read8(t.cmdPtr)
        if (n == 0) {
            t.cmdPtr += 1
            goto(t, bus)
            return
        }
        t.repN = (t.repN + 1) and 0xFF
        val count = arg(t, bus)
        if (t.repN < count) {
            goto(t, bus)
        } else {
            t.repN = 0
            t.cmdPtr += 4
        }
    }

    /** `PRIO`. */
    public fun prio(t: M4aTrack, bus: M4aBus) {
        t.priority = arg(t, bus)
    }

    /**
     * `TEMPO`: tempoD = 2 * arg, tempoI = tempoD * tempoU >> 8.
     *
     * Ported from src/m4a_1.s: ply_tempo
     */
    public fun tempo(p: M4aPlayer, t: M4aTrack, bus: M4aBus) {
        val d = arg(t, bus) * 2
        p.tempoD = d and 0xFFFF
        p.tempoI = ((d * p.tempoU) ushr 8) and 0xFFFF
    }

    /**
     * `KEYSH`.
     *
     * Ported from src/m4a_1.s: ply_keysh
     */
    public fun keysh(t: M4aTrack, bus: M4aBus) {
        t.keyShift = s8(arg(t, bus))
        t.flags = t.flags or M4aTrack.FLG_PITCHG
    }

    /**
     * `VOL`.
     *
     * Ported from src/m4a_1.s: ply_vol
     */
    public fun vol(t: M4aTrack, bus: M4aBus) {
        t.vol = arg(t, bus)
        t.flags = t.flags or M4aTrack.FLG_VOLCHG
    }

    /**
     * `PAN` (stored - 0x40).
     *
     * Ported from src/m4a_1.s: ply_pan
     */
    public fun pan(t: M4aTrack, bus: M4aBus) {
        t.pan = s8(arg(t, bus) - 0x40)
        t.flags = t.flags or M4aTrack.FLG_VOLCHG
    }

    /** `BEND` (stored - 0x40). */
    public fun bend(t: M4aTrack, bus: M4aBus) {
        t.bend = s8(arg(t, bus) - 0x40)
        t.flags = t.flags or M4aTrack.FLG_PITCHG
    }

    /** `BENDR`. */
    public fun bendr(t: M4aTrack, bus: M4aBus) {
        t.bendRange = arg(t, bus)
        t.flags = t.flags or M4aTrack.FLG_PITCHG
    }

    /** `LFODL`. */
    public fun lfodl(t: M4aTrack, bus: M4aBus) {
        t.lfoDelay = arg(t, bus)
    }

    /** `MODT`: on change, sets all four volume/pitch flags. */
    public fun modt(t: M4aTrack, bus: M4aBus) {
        val v = arg(t, bus)
        if (t.modT != v) {
            t.modT = v
            t.flags = t.flags or M4aTrack.FLG_VOLCHG or M4aTrack.FLG_PITCHG
        }
    }

    /** `TUNE` (stored - 0x40). */
    public fun tune(t: M4aTrack, bus: M4aBus) {
        t.tune = s8(arg(t, bus) - 0x40)
        t.flags = t.flags or M4aTrack.FLG_PITCHG
    }

    /** `LFOS`; 0 clears the modulation. */
    public fun lfos(t: M4aTrack, bus: M4aBus) {
        t.lfoSpeed = argUnchecked(t, bus)
        if (t.lfoSpeed == 0) clearModM(t)
    }

    /** `MOD`; 0 clears the modulation. */
    public fun mod(t: M4aTrack, bus: M4aBus) {
        t.mod = argUnchecked(t, bus)
        if (t.mod == 0) clearModM(t)
    }

    /**
     * `VOICE`: copies the 12-byte `ToneData` entry of the player's voice group into the track (each
     * word through `chk_adr_r2` on the entry address; the index byte is read directly).
     *
     * Ported from src/m4a_1.s: ply_voice
     */
    public fun voice(p: M4aPlayer, t: M4aTrack, bus: M4aBus) {
        val a = p.tone + argUnchecked(t, bus) * 12
        // chk_adr_r2 tests r2 (the entry base) for all three words.
        for (i in 0 until 3) t.tone[i] = if (a ushr 25 == 0) 0 else read32(bus, a + 4 * i)
    }

    /**
     * `ply_note` argument parse: gateTime = `gClockTable[noteIndex]`, then up to
     * three bytes < 0x80 at cmdPtr: key, velocity, extra gate time.
     */
    private fun noteArgs(t: M4aTrack, bus: M4aBus, gate: Int) {
        t.gateTime = gate
        var a = t.cmdPtr
        var b = bus.read8(a)
        if (b >= 0x80) return
        t.key = b
        a += 1
        b = bus.read8(a)
        if (b < 0x80) {
            t.velocity = b
            a += 1
            b = bus.read8(a)
            if (b < 0x80) {
                t.gateTime = (t.gateTime + b) and 0xFF
                a += 1
            }
        }
        t.cmdPtr = a
    }

    /**
     * `ply_note` voice resolution for a key-split (0x40) or rhythm (0x80) track
     * voice: the sub-voice entry address is `tone.wav + 12 * (split ? keySplitTable[key] : key)`.
     * Returns that address, or -1 if the entry is itself split/rhythm (note dropped). For a rhythm
     * voice the caller then plays the entry's key (byte 1) and, if its pan byte 3 has bit 7, rhythm
     * pan `(pan - 0xC0) * 2`.
     */
    private fun noteSubVoice(t: M4aTrack, bus: M4aBus): Int {
        val type = t.tone[0] and 0xFF
        val index = if (type and 0x40 != 0) bus.read8(t.tone[2] + t.key) else t.key
        val entry = t.tone[1] + index * 12
        return if (bus.read8(entry) and 0xC0 != 0) -1 else entry
    }

    /**
     * `ply_note` (commands 0xCF..0xFF; [index] = command - 0xCF): parses key/velocity/gate time,
     * resolves key-split/rhythm voices, takes a free or lower-priority channel (CGB: the voice's
     * fixed channel; DirectSound: free, else the lowest-priority releasing, else lowest-priority
     * playing one, ties to the higher track address), links it at the head of the track's chain
     * and starts it (status 0x80); then clears the track's low flags. Dropped notes change only
     * the parsed track fields.
     *
     * Ported from src/m4a_1.s: ply_note
     */
    public fun note(info: M4aSoundInfo, p: M4aPlayer, t: M4aTrack, bus: M4aBus, index: Int) {
        noteArgs(t, bus, CLOCK_TABLE[index])
        var rhythmPan = 0
        val type: Int
        val wav: Int
        val adsr: Int
        val voice: Int // sub-voice entry address, or -1 for the track's own tone
        var key = t.key
        val trackType = t.tone[0] and 0xFF
        if (trackType and 0xC0 != 0) {
            voice = noteSubVoice(t, bus)
            if (voice < 0) return
            if (trackType and 0x80 != 0) {
                val pan = bus.read8(voice + 3)
                if (pan and 0x80 != 0) rhythmPan = (((pan - 0xC0) * 2) shl 24) shr 24
                key = bus.read8(voice + 1)
            }
            type = bus.read8(voice)
            wav = read32(bus, voice + 4)
            adsr = read32(bus, voice + 8)
        } else {
            voice = -1
            type = trackType
            wav = t.tone[1]
            adsr = t.tone[2]
        }
        val priority = minOf(255, p.priority + t.priority)
        val cgb = type and 7
        val c: M4aChannel
        if (cgb != 0) {
            val cc = info.cgbChans ?: return
            c = cc[cgb - 1]
            if (c.isActive && c.status and 0x40 == 0) {
                if (c.priority > priority) return
                if (c.priority == priority && trackAddress(c).toUInt() < t.address.toUInt()) {
                    return
                }
            }
        } else {
            c = pickDirect(info, t, priority) ?: return
        }
        c.clearChain()
        c.prev = null
        c.next = t.chan
        t.chan?.prev = c
        t.chan = c
        c.track = t
        t.lfoDelayC = t.lfoDelay
        if (t.lfoDelay != 0) clearModM(t)
        t.volPitSet()
        c.gateTime = t.gateTime
        c.midiKey = t.key
        c.velocity = t.velocity
        c.priority = priority
        c.key = key
        c.rhythmPan = rhythmPan
        c.type = type
        c.wav = wav
        c.adsr = adsr
        c.echoVolume = t.echoVolume
        c.echoLength = t.echoLength
        c.volSet(t)
        val k = maxOf(0, c.key + s8(t.keyM))
        if (cgb != 0) {
            val tone0 = if (voice < 0) t.tone[0] else read32(bus, voice)
            c.length = (tone0 ushr 16) and 0xFF
            val sweep = tone0 ushr 24
            c.sweep = if (sweep and 0x80 != 0 || sweep and 0x70 == 0) 8 else sweep
            c.frequency = M4aPitch.midiKeyToCgbFreq(cgb, k, t.pitM)
        } else {
            c.frequency = M4aPitch.midiKeyToFreq(read32(bus, wav + 4), k, t.pitM)
        }
        c.status = 0x80
        t.flags = t.flags and 0xF0
    }

    private fun pickDirect(info: M4aSoundInfo, t: M4aTrack, priority: Int): M4aChannel? {
        var bestPriority = priority
        var bestTrack = t.address
        var releasing = false
        var best: M4aChannel? = null
        for (i in 0 until info.maxChans) {
            val c = info.chans[i]
            if (!c.isActive) return c
            if (c.status and 0x40 != 0) {
                if (!releasing) {
                    releasing = true
                    bestPriority = c.priority
                    bestTrack = trackAddress(c)
                    best = c
                    continue
                }
            } else if (releasing) {
                continue
            }
            if (c.priority < bestPriority) {
                bestPriority = c.priority
                bestTrack = trackAddress(c)
                best = c
            } else if (c.priority == bestPriority &&
                trackAddress(c).toUInt() >= bestTrack.toUInt()
            ) {
                bestTrack = trackAddress(c)
                best = c
            }
        }
        return best
    }

    private fun trackAddress(c: M4aChannel): Int = c.track?.address ?: 0

    /**
     * `FINE`: releases every active channel of [t], unlinks the whole chain and stops the track
     * (flags = 0).
     *
     * Ported from src/m4a_1.s: ply_fine
     */
    public fun fine(t: M4aTrack) {
        var c = t.chan
        while (c != null) {
            if (c.isActive) c.status = c.status or 0x40
            c.clearChain()
            c = c.next
        }
        t.flags = 0
    }

    /**
     * `EOT`: optional key argument (else the track key); releases the newest playing, not yet
     * releasing channel of [t] whose `midiKey` is that key.
     *
     * Ported from src/m4a_1.s: ply_endtie
     */
    public fun endtie(t: M4aTrack, bus: M4aBus) {
        val b = bus.read8(t.cmdPtr)
        val key = if (b < 0x80) {
            t.key = b
            t.cmdPtr += 1
            b
        } else {
            t.key
        }
        var c = t.chan
        while (c != null) {
            if (c.status and 0x83 != 0 && c.status and 0x40 == 0 && c.midiKey == key) {
                c.status = c.status or 0x40
                return
            }
            c = c.next
        }
    }

    /**
     * `XCMD`: sub-command byte `n`, then `gXcmdTable[n]`: 0/3 `FINE`, 1 `wav` (4 bytes), 2 `type`,
     * 4..7 attack/decay/sustain/release, 8/9 echo volume/length, 10 `length`, 11 `pan_sweep`.
     * Returns false for `n` >= 12 (out of the table: undefined in the original). From src/m4a.c
     * `ply_xcmd`; not verified yet (no trace runs an `XCMD`).
     */
    public fun xcmd(t: M4aTrack, bus: M4aBus): Boolean {
        val n = bus.read8(t.cmdPtr)
        t.cmdPtr += 1
        val b = bus.read8(t.cmdPtr)
        fun setByte(word: Int, shift: Int) {
            t.tone[word] = (t.tone[word] and (0xFF shl shift).inv()) or (b shl shift)
            t.cmdPtr += 1
        }
        when (n) {
            0, 3 -> fine(t)
            1 -> {
                t.tone[1] = read32(bus, t.cmdPtr)
                t.cmdPtr += 4
            }
            2 -> setByte(0, 0)
            4, 5, 6, 7 -> setByte(2, 8 * (n - 4))
            8 -> {
                t.echoVolume = b
                t.cmdPtr += 1
            }
            9 -> {
                t.echoLength = b
                t.cmdPtr += 1
            }
            10 -> setByte(0, 16)
            11 -> setByte(0, 24)
            else -> return false
        }
        return true
    }

    /**
     * Silences every channel of [t] (status 0; CGB ones also through [M4aSoundInfo.cgbOscOff]),
     * drops their track link and empties the chain. No-op for a track not in use.
     *
     * Ported from src/m4a_1.s: TrackStop
     */
    public fun trackStop(info: M4aSoundInfo?, t: M4aTrack) {
        if (t.flags and M4aTrack.FLG_EXIST == 0) return
        var c = t.chan
        while (c != null) {
            if (c.status != 0) {
                val cgb = c.type and 7
                if (cgb != 0) info?.cgbOscOff?.invoke(cgb)
                c.status = 0
            }
            c.track = null
            c = c.next
        }
        t.chan = null
    }

    /**
     * `m4aMPlayVolumeControl`: each live track of [p] selected by [trackBits] gets
     * `volX = volume / 4` and a volume change. Ignored while [p] is locked.
     *
     * Ported from src/m4a.c: m4aMPlayVolumeControl
     */
    public fun volumeControl(p: M4aPlayer, trackBits: Int, volume: Int) {
        if (p.ident != M4aPlayer.ID_NUMBER) return
        p.ident++
        for (i in 0 until p.trackCount) {
            val t = p.tracks[i]
            if (trackBits ushr i and 1 != 0 && t.flags and M4aTrack.FLG_EXIST != 0) {
                t.volX = (volume and 0xFFFF) / 4
                t.flags = t.flags or M4aTrack.FLG_VOLCHG
            }
        }
        p.ident = M4aPlayer.ID_NUMBER
    }

    /**
     * `m4aMPlayStop`: pauses [p] and stops each of its tracks ([trackStop]). Ignored while [p]
     * is locked (`ident` is not `ID_NUMBER`).
     *
     * Ported from src/m4a.c: m4aMPlayStop
     */
    public fun mPlayStop(p: M4aPlayer, info: M4aSoundInfo?) {
        if (p.ident != M4aPlayer.ID_NUMBER) return
        p.ident++
        p.status = p.status or M4aPlayer.STATUS_PAUSE
        for (i in 0 until p.trackCount) trackStop(info, p.tracks[i])
        p.ident = M4aPlayer.ID_NUMBER
    }

    /**
     * `m4aMPlayFadeOut` / `MPlayFadeOut`: fades [p] out from full volume, one step every [speed]
     * frames (see [fadeOutBody]). Ignored while [p] is locked (`ident` is not `ID_NUMBER`).
     *
     * Ported from src/m4a.c: MPlayFadeOut
     */
    public fun fadeOut(p: M4aPlayer, speed: Int) {
        if (p.ident != M4aPlayer.ID_NUMBER) return
        p.fadeOC = speed and 0xFFFF
        p.fadeOI = speed and 0xFFFF
        p.fadeOV = 64 shl 2
    }

    /**
     * One fade step of [p] every `fadeOI` frames: fade-in raises `fadeOV` by 4 << 2 up to 64 << 2;
     * fade-out lowers it and at 0 stops all tracks (keeping them if `TEMPORARY_FADE`) and pauses
     * the player; otherwise every live track gets `volX = fadeOV >> 2` and a volume change.
     * Verified on a fade-out to stop (`M4aFadeTest`); the fade-in and `TEMPORARY_FADE` branches
     * have no trace yet.
     *
     * Ported from src/m4a.c: FadeOutBody
     */
    public fun fadeOutBody(p: M4aPlayer, info: M4aSoundInfo?) {
        if (p.fadeOI == 0) return
        p.fadeOC = (p.fadeOC - 1) and 0xFFFF
        if (p.fadeOC != 0) return
        p.fadeOC = p.fadeOI
        if (p.fadeOV and FADE_IN != 0) {
            p.fadeOV = (p.fadeOV + (4 shl 2)) and 0xFFFF
            if (p.fadeOV >= 64 shl 2) {
                p.fadeOV = 64 shl 2
                p.fadeOI = 0
            }
        } else {
            p.fadeOV = (p.fadeOV - (4 shl 2)) and 0xFFFF
            if (((p.fadeOV shl 16) shr 16) <= 0) {
                for (i in 0 until p.trackCount) {
                    val t = p.tracks[i]
                    trackStop(info, t)
                    if (p.fadeOV and TEMPORARY_FADE == 0) t.flags = 0
                }
                p.status = if (p.fadeOV and TEMPORARY_FADE != 0) {
                    p.status or M4aPlayer.STATUS_PAUSE
                } else {
                    M4aPlayer.STATUS_PAUSE
                }
                p.fadeOI = 0
                return
            }
        }
        for (i in 0 until p.trackCount) {
            val t = p.tracks[i]
            if (t.flags and M4aTrack.FLG_EXIST != 0) {
                t.volX = p.fadeOV shr 2
                t.flags = t.flags or M4aTrack.FLG_VOLCHG
            }
        }
    }

    private const val FADE_IN = 0x1
    private const val TEMPORARY_FADE = 0x2

    /** `clear_modM`: modM = 0, lfoSpeedC = 0, flag the modulated target. */
    public fun clearModM(t: M4aTrack) {
        t.modM = 0
        t.lfoSpeedC = 0
        t.flags = t.flags or if (t.modT == 0) M4aTrack.FLG_PITCHG else M4aTrack.FLG_VOLCHG
    }

    /** `gClockTable`: wait lengths of commands 0x80..0xB0. */
    internal val CLOCK_TABLE = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
        24, 28, 30, 32, 36, 40, 42, 44, 48, 52, 54, 56, 60, 64, 66, 68, 72, 76, 78, 80, 84, 88,
        90, 92, 96,
    )
}
