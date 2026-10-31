package gbafe.audio

/**
 * WIP (M3-13): `MPlayMain`'s tempo/tick/command loop at track level. Notes, `FINE`, `EOT`/`TIE`,
 * `PORT`, `MEMACC`, `XCMD` and channel bookkeeping are not modelled yet: a track reaching one is
 * reported in [unsupported] and its state is then undefined. Not yet `Ported from`.
 */
public class M4aSequencer(private val bus: M4aBus) {
    /** Bit per track slot that hit an unmodelled command in the last [step]. */
    public var unsupported: Int = 0
        private set

    /** One `MPlayMain` call (one VBlank) for [p]. Returns false if the player was skipped. */
    public fun step(p: M4aPlayer): Boolean {
        unsupported = 0
        if (p.ident != M4aPlayer.ID_NUMBER || p.status < 0 || p.fadeOI != 0) return false
        p.tempoC = (p.tempoC + p.tempoI) and 0xFFFF
        while (p.tempoC >= TICK) {
            var live = 0
            for (i in 0 until p.trackCount) {
                val t = p.tracks[i]
                if (t.flags and M4aTrack.FLG_EXIST == 0) continue
                live = live or (1 shl i)
                if (t.flags and M4aTrack.FLG_START != 0) start(t)
                if (!tick(p, t, i)) continue
            }
            p.clock += 1
            if (live == 0) {
                p.status = M4aPlayer.STATUS_PAUSE
                return true
            }
            p.status = live
            p.tempoC -= TICK
        }
        for (i in 0 until p.trackCount) {
            val t = p.tracks[i]
            if (t.flags and M4aTrack.FLG_EXIST == 0 || t.flags and 0x0F == 0) continue
            t.volPitSet()
            t.flags = t.flags and 0xF0
        }
        return true
    }

    /** The `Clear64byte` + defaults of a starting track. */
    private fun start(t: M4aTrack) {
        t.flags = M4aTrack.FLG_EXIST
        t.wait = 0; t.patternLevel = 0; t.repN = 0; t.gateTime = 0; t.key = 0; t.velocity = 0
        t.runningStatus = 0; t.keyM = 0; t.pitM = 0; t.keyShift = 0; t.keyShiftX = 0; t.tune = 0
        t.pitX = 0; t.bend = 0; t.bendRange = 2; t.volMR = 0; t.volML = 0; t.vol = 0; t.volX = 64
        t.pan = 0; t.panX = 0; t.modM = 0; t.mod = 0; t.modT = 0; t.lfoSpeed = 22; t.lfoSpeedC = 0
        t.lfoDelay = 0; t.lfoDelayC = 0; t.priority = 0
        t.tone[0] = 1; t.tone[1] = 0; t.tone[2] = 0
    }

    /** Commands until a wait, then the wait countdown and LFO. False if the track stopped early. */
    private fun tick(p: M4aPlayer, t: M4aTrack, slot: Int): Boolean {
        while (t.wait == 0) {
            var cmd = bus.read8(t.cmdPtr)
            if (cmd < 0x80) {
                cmd = t.runningStatus
            } else {
                t.cmdPtr += 1
                if (cmd >= 0xBD) t.runningStatus = cmd
            }
            when {
                cmd >= 0xCF -> return unsupported(slot)
                cmd > 0xB0 -> {
                    if (!command(p, t, cmd)) return unsupported(slot)
                    if (t.flags == 0) return false
                }
                else -> t.wait = CLOCK_TABLE[cmd - 0x80]
            }
        }
        t.wait -= 1
        if (t.lfoSpeed == 0 || t.mod == 0) return true
        if (t.lfoDelayC != 0) {
            t.lfoDelayC -= 1
            return true
        }
        t.lfoSpeedC = (t.lfoSpeedC + t.lfoSpeed) and 0xFF
        val c = t.lfoSpeedC
        val phase = if (((c - 0x40) and 0x80) == 0) 128 - c else (c shl 24) shr 24
        val m = (t.mod * phase) shr 6
        if ((t.modM xor m) and 0xFF != 0) {
            t.modM = (m shl 24) shr 24
            t.flags = t.flags or if (t.modT == 0) M4aTrack.FLG_PITCHG else M4aTrack.FLG_VOLCHG
        }
        return true
    }

    private fun unsupported(slot: Int): Boolean {
        unsupported = unsupported or (1 shl slot)
        return false
    }

    /** Jump-table commands 0xB1..0xCE; false for the unmodelled ones. */
    private fun command(p: M4aPlayer, t: M4aTrack, cmd: Int): Boolean {
        when (cmd) {
            0xB2 -> M4aCommands.goto(t, bus)
            0xB3 -> if (t.patternLevel < 3) M4aCommands.patt(t, bus) {} else return false
            0xB4 -> M4aCommands.pend(t)
            0xB5 -> M4aCommands.rept(t, bus)
            0xBA -> M4aCommands.prio(t, bus)
            0xBB -> M4aCommands.tempo(p, t, bus)
            0xBC -> M4aCommands.keysh(t, bus)
            0xBD -> M4aCommands.voice(p, t, bus)
            0xBE -> M4aCommands.vol(t, bus)
            0xBF -> M4aCommands.pan(t, bus)
            0xC0 -> M4aCommands.bend(t, bus)
            0xC1 -> M4aCommands.bendr(t, bus)
            0xC2 -> M4aCommands.lfos(t, bus)
            0xC3 -> M4aCommands.lfodl(t, bus)
            0xC4 -> M4aCommands.mod(t, bus)
            0xC5 -> M4aCommands.modt(t, bus)
            0xC8 -> M4aCommands.tune(t, bus)
            else -> return false // FINE (and aliases), MEMACC, PORT, XCMD, EOT
        }
        return true
    }

    private companion object {
        const val TICK = 150

        /** `gClockTable`: wait lengths of commands 0x80..0xB0. */
        val CLOCK_TABLE = intArrayOf(
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
            28, 30, 32, 36, 40, 42, 44, 48, 52, 54, 56, 60, 64, 66, 68, 72, 76, 78, 80, 84, 88, 90, 92, 96,
        )
    }
}
