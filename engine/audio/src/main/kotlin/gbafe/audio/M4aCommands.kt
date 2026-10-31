package gbafe.audio

/** Byte reader over the GBA address space holding song data (ROM in practice). */
public fun interface M4aBus {
    /** The byte (0..255) at GBA [address]. */
    public fun read8(address: Int): Int
}

/**
 * The m4a track commands that only touch track/player state (`ply_*` in m4a_1.s). Each reads its
 * arguments at [M4aTrack.cmdPtr] (already past the command byte), like the original.
 *
 * WIP (M3-13): not yet verified; no `Ported from` until `MPlayMain` steps match the RAM traces.
 * `chk_adr_r2` (BIOS read guard) is not modelled: song data is never in BIOS.
 */
public object M4aCommands {
    private fun arg(t: M4aTrack, bus: M4aBus): Int = bus.read8(t.cmdPtr).also { t.cmdPtr += 1 }

    private fun read32(bus: M4aBus, a: Int): Int =
        bus.read8(a) or (bus.read8(a + 1) shl 8) or (bus.read8(a + 2) shl 16) or (bus.read8(a + 3) shl 24)

    private fun s8(v: Int): Int = (v shl 24) shr 24

    /** `GOTO`: jump to the 32-bit address at cmdPtr. */
    public fun goto(t: M4aTrack, bus: M4aBus) {
        t.cmdPtr = read32(bus, t.cmdPtr)
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

    /** `PEND`: return from a `PATT`. */
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

    /** `TEMPO`: tempoD = 2 * arg, tempoI = tempoD * tempoU >> 8. */
    public fun tempo(p: M4aPlayer, t: M4aTrack, bus: M4aBus) {
        val d = arg(t, bus) * 2
        p.tempoD = d and 0xFFFF
        p.tempoI = ((d * p.tempoU) ushr 8) and 0xFFFF
    }

    /** `KEYSH`. */
    public fun keysh(t: M4aTrack, bus: M4aBus) {
        t.keyShift = s8(arg(t, bus))
        t.flags = t.flags or M4aTrack.FLG_PITCHG
    }

    /** `VOL`. */
    public fun vol(t: M4aTrack, bus: M4aBus) {
        t.vol = arg(t, bus)
        t.flags = t.flags or M4aTrack.FLG_VOLCHG
    }

    /** `PAN` (stored - 0x40). */
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
        t.lfoSpeed = arg(t, bus)
        if (t.lfoSpeed == 0) clearModM(t)
    }

    /** `MOD`; 0 clears the modulation. */
    public fun mod(t: M4aTrack, bus: M4aBus) {
        t.mod = arg(t, bus)
        if (t.mod == 0) clearModM(t)
    }

    /** `VOICE`: copies the 12-byte `ToneData` entry of the player's voice group into the track. */
    public fun voice(p: M4aPlayer, t: M4aTrack, bus: M4aBus) {
        val a = p.tone + arg(t, bus) * 12
        for (i in 0 until 3) t.tone[i] = read32(bus, a + 4 * i)
    }

    /** `clear_modM`: modM = 0, lfoSpeedC = 0, flag the modulated target. */
    public fun clearModM(t: M4aTrack) {
        t.modM = 0
        t.lfoSpeedC = 0
        t.flags = t.flags or if (t.modT == 0) M4aTrack.FLG_PITCHG else M4aTrack.FLG_VOLCHG
    }
}
