package gbafe.audio

/**
 * WIP (M3-13): `MPlayMain`'s tempo/tick/command loop, gate countdown and channel volume/pitch
 * updates. With [info], notes (`ply_note`, incl. `TIE`) are played; `PORT`, `MEMACC`
 * (and notes without [info]) are not modelled: a track reaching one is reported
 * in [unsupported] and its state is then undefined. Fades (`FadeOutBody`) are unverified.
 * Not yet `Ported from`.
 */
public class M4aSequencer(
    private val bus: M4aBus,
    /** Shared channels; null leaves notes unmodelled (reported in [unsupported]). */
    private val info: M4aSoundInfo? = null,
) {
    /** Bit per track slot that hit an unmodelled command in the last [step]. */
    public var unsupported: Int = 0
        private set

    /** Per track slot: bit `cmd - 0xB1` for each jump-table command run in the last [step]. */
    public val commandsRun: LongArray = LongArray(16)

    /** One `MPlayMain` call (one VBlank) for [p]. Returns false if the player was skipped. */
    public fun step(p: M4aPlayer): Boolean {
        unsupported = 0
        commandsRun.fill(0)
        if (p.ident != M4aPlayer.ID_NUMBER || p.status < 0) return false
        M4aCommands.fadeOutBody(p, info)
        if (p.status < 0) return true
        p.tempoC = (p.tempoC + p.tempoI) and 0xFFFF
        while (p.tempoC >= TICK) {
            var live = 0
            for (i in 0 until p.trackCount) {
                val t = p.tracks[i]
                if (t.flags and M4aTrack.FLG_EXIST == 0) continue
                live = live or (1 shl i)
                if (info != null) gate(t)
                if (t.flags and M4aTrack.FLG_START != 0) clearTrack(t)
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
            if (info != null) updateChannels(t)
            t.flags = t.flags and 0xF0
        }
        return true
    }

    /**
     * `MPlayStart`: starts the song at [header] on [p] unless a higher-priority song is playing
     * (only if `unk_B`): resets tempo/fade, stops all tracks, arms the song's tracks with
     * `EXIST | START` at their part pointers and clears the rest. A song with `reverb & 0x80` sets
     * the mixer reverb (`m4aSoundMode`); returns false if that needs [info] and there is none.
     *
     * Ported from src/m4a.c: MPlayStart
     */
    public fun start(p: M4aPlayer, header: Int): Boolean {
        if (p.ident != M4aPlayer.ID_NUMBER) return true
        val trackCount = bus.read8(header)
        val priority = bus.read8(header + 2)
        val reverb = bus.read8(header + 3)
        val busy = (p.songHeader != 0 && p.tracks[0].flags and M4aTrack.FLG_START != 0) ||
            (p.status and 0xFFFF != 0 && p.status >= 0)
        if (p.unkB != 0 && busy && p.priority > priority) return true
        p.status = 0
        p.songHeader = header
        p.tone = read32(header + 4)
        p.priority = priority
        p.clock = 0
        p.tempoD = 150
        p.tempoI = 150
        p.tempoU = 0x100
        p.tempoC = 0
        p.fadeOI = 0
        for (i in 0 until p.trackCount) {
            val t = p.tracks[i]
            M4aCommands.trackStop(info, t)
            if (i < trackCount) {
                t.flags = M4aTrack.FLG_EXIST or M4aTrack.FLG_START
                t.chan = null
                t.cmdPtr = read32(header + 8 + 4 * i)
            } else {
                t.flags = 0
            }
        }
        if (reverb and 0x80 == 0) return true
        val i = info ?: return false
        i.reverb = reverb and 0x7F // m4aSoundMode(reverb): only the reverb bits fit in a byte
        return true
    }

    /**
     * `m4aSongNumStart`: starts song [n] of [songs] on its player (`ms`) via [start].
     * [players] are the game's `gMPlayTable` players, in table order.
     *
     * Ported from src/m4a.c: m4aSongNumStart
     */
    public fun songNumStart(players: List<M4aPlayer>, songs: M4aSongTable, n: Int): Boolean =
        start(players[songs.player(n and 0xFFFF)], songs.header(n and 0xFFFF))

    /**
     * `m4aMPlayImmInit`: resets each starting track (`EXIST | START`) to the `Clear64byte`
     * defaults at once, as `MPlayMain` would on its next tick.
     *
     * Ported from src/m4a.c: m4aMPlayImmInit
     */
    public fun immInit(p: M4aPlayer) {
        for (i in 0 until p.trackCount) {
            val t = p.tracks[i]
            if (t.flags and M4aTrack.FLG_EXIST != 0 && t.flags and M4aTrack.FLG_START != 0) {
                clearTrack(t)
            }
        }
    }

    /** Gate countdown of [t]'s channels: release at 0, unlink inactive ones. */
    private fun gate(t: M4aTrack) {
        var c = t.chan
        while (c != null) {
            if (!c.isActive) {
                c.clearChain()
            } else if (c.gateTime != 0) {
                c.gateTime -= 1
                if (c.gateTime == 0) c.status = c.status or 0x40
            }
            c = c.next
        }
    }

    /** Volume / pitch changes of [t] pushed to its channels (end of `MPlayMain`). */
    private fun updateChannels(t: M4aTrack) {
        var c = t.chan
        while (c != null) {
            if (!c.isActive) {
                c.clearChain()
                c = c.next
                continue
            }
            val cgb = c.type and 7
            if (t.flags and M4aTrack.FLG_VOLCHG != 0) c.volSet(t)
            if (t.flags and M4aTrack.FLG_PITCHG != 0) {
                val k = maxOf(0, c.key + ((t.keyM shl 24) shr 24))
                c.frequency = if (cgb != 0) {
                    M4aPitch.midiKeyToCgbFreq(cgb, k, t.pitM)
                } else {
                    M4aPitch.midiKeyToFreq(read32(c.wav + 4), k, t.pitM)
                }
            }
            c = c.next
        }
    }

    private fun read32(a: Int): Int = bus.read8(a) or (bus.read8(a + 1) shl 8) or
        (bus.read8(a + 2) shl 16) or (bus.read8(a + 3) shl 24)

    /** The `Clear64byte` + defaults of a starting track. */
    private fun clearTrack(t: M4aTrack) {
        t.chan = null
        t.echoVolume = 0
        t.echoLength = 0
        t.flags = M4aTrack.FLG_EXIST
        t.wait = 0
        t.patternLevel = 0
        t.repN = 0
        t.gateTime = 0
        t.key = 0
        t.velocity = 0
        t.runningStatus = 0
        t.keyM = 0
        t.pitM = 0
        t.keyShift = 0
        t.keyShiftX = 0
        t.tune = 0
        t.pitX = 0
        t.bend = 0
        t.bendRange = 2
        t.volMR = 0
        t.volML = 0
        t.vol = 0
        t.volX = 64
        t.pan = 0
        t.panX = 0
        t.modM = 0
        t.mod = 0
        t.modT = 0
        t.lfoSpeed = 22
        t.lfoSpeedC = 0
        t.lfoDelay = 0
        t.lfoDelayC = 0
        t.priority = 0
        t.tone[0] = 1
        t.tone[1] = 0
        t.tone[2] = 0
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
                cmd >= 0xCF -> {
                    val i = info ?: return unsupported(slot)
                    M4aCommands.note(i, p, t, bus, cmd - 0xCF)
                }
                cmd > 0xB0 -> {
                    if (!command(p, t, cmd)) return unsupported(slot)
                    commandsRun[slot] = commandsRun[slot] or (1L shl (cmd - 0xB1))
                    if (t.flags == 0) return false
                }
                else -> t.wait = M4aCommands.CLOCK_TABLE[cmd - 0x80]
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
            0xB3 -> M4aCommands.patt(t, bus, M4aCommands::fine)
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
            0xB1, 0xB6, 0xB7, 0xB8, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB -> M4aCommands.fine(t)
            0xCD -> return M4aCommands.xcmd(t, bus)
            0xCE -> M4aCommands.endtie(t, bus)
            else -> return false // MEMACC, PORT
        }
        return true
    }

    private companion object {
        const val TICK = 150

    }
}
