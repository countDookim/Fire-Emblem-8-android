package fe8.testing

import gbafe.audio.M4aBus
import gbafe.audio.M4aCgb
import gbafe.audio.M4aChannel
import gbafe.audio.M4aCommands
import gbafe.audio.M4aMixer
import gbafe.audio.M4aPlayer
import gbafe.audio.M4aPsg
import gbafe.audio.M4aSequencer
import gbafe.audio.M4aSongTable
import gbafe.audio.M4aTrack
import gbafe.audio.PsgSynth
import gbafe.core.VerifiesPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Notes, channels and the mixer: from each per-frame dump, every player's `MPlayMain` (in the
 * dump's `intp` chain order, sharing one set of channels) reproduces the next dump's channel
 * chains and the channel fields the mixer does not touch; then [M4aMixer.frame] reproduces each
 * DirectSound channel's status, envelope and sample position, and the whole PCM buffer. Frames
 * with a fade, a song change or an unmodelled command are skipped.
 */
class M4aNoteTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "ply_note", "RealClearChain", "ply_fine", "ply_endtie", "SoundMainRAM",
        "MPlayStart", "m4aMPlayImmInit", "m4aSongNumStart", "m4aSoundVSync", "MPlayMain",
        "CgbSound", "CgbModVol",
    )
    fun `channels step like the per-frame RAM traces`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val rom = romFile.readBytes()
        var frames = 0
        var skipped = 0
        var notes = 0
        val why = IntArray(3)
        val frameRuns = IntArray(64)
        val cmds = IntArray(256)
        var fines = 0
        var ended = 0
        var enveloped = 0
        var pcmFrames = 0
        var immInits = 0
        var vSyncs = 0
        val songNums = sortedSetOf<Int>()
        var fixed = 0
        var looped = 0
        var maxLines = 0
        var pcmFreq = 0
        var endties = 0
        var cgbActive = 0
        // PSG level (M3-14): CgbSound's register writes in ram_trace_menu_cgb f900..f1100 played
        // by PsgSynth, compared with the PSG share of mGBA's audio_menu output.
        val psgRegs = M4aPsg()
        // Channels triggered (NRx4 bit 7) per mGBA step (dump n = step n + 1), f901..f1100.
        val ourTriggers = HashMap<Int, Int>()
        var loadedWave = 0
        var psgStarted = false
        // Envelope timing: square 1, square 2 and wave each played alone (as mGBA's audio_psg1/2/4),
        // peak-to-peak per quarter frame (L + R, mGBA units), f900..f1099.
        val soloPsgs = List(3) { M4aPsg() }
        val solos = List(3) { c -> PsgSynth(soloPsgs[c], 13379).also { it.channelMask = 1 shl c } }
        val soloOut = FloatArray(2 * 224)
        val psgQuarters = Array(3) { DoubleArray(4 * 200) }
        val synth = PsgSynth(psgRegs, 13379)
        // FE8_PSG_MASK=1|2|4|8 solos one channel (compare with mGBA's per-channel output).
        System.getenv("FE8_PSG_MASK")?.let { synth.channelMask = it.toInt() }
        val psgOut = FloatArray(2 * 224)
        var psgSum = 0.0
        var psgMean = 0.0
        var psgSamples = 0
        for (dir in ref.root.listFiles().orEmpty().filter { it.isDirectory }) {
            val dumps = dir.listFiles().orEmpty().mapNotNull { f ->
                DUMP.matchEntire(f.name)?.let { it.groupValues[1].toInt() to f }
            }.toMap().toSortedMap() // in frame order: the PSG synth carries state across frames
            for ((n, file) in dumps) {
                val next = dumps[n + 1] ?: continue
                val a = file.readBytes()
                val b = next.readBytes()
                val (players, info) = M4aRam.sound(a)
                val (qs, infoB) = M4aRam.sound(b)
                val order = chain(a).map { addr ->
                    M4aRam.PLAYERS.values.indexOfFirst { it.first == addr }
                }
                if (order.any { it < 0 }) continue
                val bus = M4aBus { x -> rom[x - 0x08000000].toInt() and 0xFF }
                val seq = M4aSequencer(bus, info)
                var ok = true
                var runs = 0L
                val songs = M4aSongTable(bus, SONG_TABLE, SONG_COUNT)
                val starts = mutableListOf<Pair<Int, Boolean>>()
                val fadeStarts = mutableListOf<Pair<Int, Int>>()
                for (i in order) {
                    val p = players[i]
                    val q = qs[i]
                    // Live fades step in MPlayMain (FadeOutBody); a fade the game starts after
                    // SoundMain (MPlayFadeOut, counter = speed in the next dump) is replayed; other
                    // fade changes on a live player are skipped.
                    val live = p.status >= 0 || q.status >= 0
                    val started = p.fadeOI == 0 && q.fadeOI != 0 && q.fadeOC == q.fadeOI
                    if (live && started) fadeStarts += i to q.fadeOI
                    val fade = live && !started && q.fadeOI != 0 && q.fadeOI != p.fadeOI
                    if (fade) why[1]++
                    if (fade) ok = false
                    if (!ok) break
                    if (p.songHeader != q.songHeader) {
                        why[0]++
                        // START still set in the next dump: the game started the song after this
                        // frame's SoundMain; else before it.
                        val imm = q.clock == 0 && q.status == 0 && q.tracks[0].flags == 0x80
                        // The song number: every entry with this header plays on this player.
                        val ns = (0 until SONG_COUNT).filter { songs.header(it) == q.songHeader }
                        assertTrue(ns.isNotEmpty() && ns.all { songs.player(it) == i }, "song $ns")
                        songNums += ns[0]
                        if (q.tracks[0].flags and 0x40 != 0 || imm) {
                            starts += ns[0] to imm
                        } else if (!seq.songNumStart(players, songs, ns[0])) {
                            ok = false
                        }
                    }
                    seq.step(p)
                    if (seq.unsupported != 0) {
                        ok = false
                        why[2]++
                        for (k in 0 until p.trackCount) {
                            if (seq.unsupported ushr k and 1 != 0) cmds[p.tracks[k].runningStatus]++
                        }
                    }
                    for (c in seq.commandsRun) runs = runs or c
                }
                if (!ok) {
                    skipped++
                    continue
                }
                frames++
                val wasActive = info.chans.map { it.isActive }
                fixed += info.chans.take(info.maxChans).count { it.isActive && it.type and 8 != 0 }
                looped += info.chans.take(info.maxChans).count { it.status and 0x10 != 0 }
                pcmFreq = info.pcmFreq
                maxLines = maxOf(maxLines, a[0x5410 + 0x0C].toInt() and 0xFF)
                notes += info.chans.count { it.status == 0x80 }
                // m4aSoundVSync (VBlank IRQ) steps the DMA counter before SoundMain runs.
                info.vSync()
                assertEquals(infoB.pcmDmaCounter, info.pcmDmaCounter, "pcmDmaCounter r$n")
                vSyncs++
                val cgbRun = dir.name == "ram_trace_menu_cgb"
                val level = cgbRun && n in 900 until 1100
                // One PSG for the whole run: the wave RAM is loaded once, before the window.
                // Channel state comes from each dump (currentPointer already = wav), so load the
                // wave RAM the game loaded from it.
                val wavPtr = info.cgbChans?.get(2)?.currentPointer ?: 0
                if (cgbRun && wavPtr != loadedWave && wavPtr ushr 24 == 0x08) {
                    for (k in 0 until 16) psgRegs.write(M4aPsg.WAVE_RAM + k, bus.read8(wavPtr + k))
                    loadedWave = wavPtr
                }
                M4aCgb.sound(info, if (cgbRun) psgRegs else M4aPsg(), bus)
                if (level) ourTriggers[n + 1] = psgRegs.triggers
                if (cgbRun) {
                    // Dump n's writes sound in mGBA's frame n + 1 output chunk.
                    if (!psgStarted) (solos + synth).forEach { it.startAtFrame(n + 1) }
                    psgStarted = true
                    for ((c, solo) in solos.withIndex()) {
                        val regs = soloPsgs[c]
                        psgRegs.regs.copyInto(regs.regs)
                        for (ch in 0 until 4) {
                            if (psgRegs.lengthLoads ushr ch and 1 != 0) {
                                regs.write(NR_X1[ch], psgRegs.read(NR_X1[ch]))
                            }
                            if (psgRegs.envelopeWrites ushr ch and 1 != 0) {
                                regs.write(NR_X2[ch], psgRegs.read(NR_X2[ch]))
                            }
                            if (psgRegs.triggers ushr ch and 1 != 0) {
                                regs.write(NR_X4[ch], psgRegs.read(NR_X4[ch]) or 0x80)
                            }
                        }
                        soloOut.fill(0f)
                        solo.render(soloOut, 224, 512f)
                        if (level) {
                            for (q in 0 until 4) {
                                val x = DoubleArray(56) { k ->
                                    val i = 2 * (q * 56 + k)
                                    0.75 * (soloOut[i] + soloOut[i + 1])
                                }
                                psgQuarters[c][4 * (n - 900) + q] = peakToPeak(x)
                            }
                        }
                    }
                    psgOut.fill(0f)
                    synth.render(psgOut, 224, 512f) // PcmStream.PSG_GAIN
                }
                if (level) {
                    for (v in psgOut) {
                        psgSum += v.toDouble() * v
                        psgMean += v
                    }
                    psgSamples += psgOut.size
                }
                M4aMixer.frame(info, bus)
                // The game starts songs after SoundMain (mixer) in the frame.
                for ((n, imm) in starts) {
                    if (!seq.songNumStart(players, songs, n)) ok = false
                    if (imm) {
                        seq.immInit(players[songs.player(n)]) // m4aMPlayImmInit right after
                        immInits++
                    }
                }
                for ((i, speed) in fadeStarts) M4aCommands.fadeOut(players[i], speed)
                // Menu traces: the game sets BGM1's volume (a ramp), then stops it.
                if (qs[0].tracks[0].flags and 0x83 == 0x83 && players[0].status >= 0) {
                    M4aCommands.volumeControl(players[0], 0xFFFF, 4 * qs[0].tracks[0].volX)
                }
                for (i in players.indices) {
                    if (qs[i].status < 0 && players[i].status >= 0) {
                        M4aCommands.mPlayStop(players[i], info)
                    }
                }
                var allMixed = true
                for (c in 0 until info.maxChans) {
                    val ch = info.chans[c]
                    val chB = infoB.chans[c]
                    if (ch.status != 0 && chB.status == 0) {
                        ended++ // sample end in the mixing loop (not modelled yet)
                        allMixed = false
                        continue
                    }
                    if (wasActive[c]) enveloped++
                    assertEquals(env(chB), env(ch), "$next channel $c envelope")
                    if (ch.status != 0) {
                        assertEquals(mixState(chB), mixState(ch), "$next channel $c mix")
                    }
                }
                if (allMixed) {
                    val pcm = info.pcmBuffer
                    val diff = pcm.indices.filter { pcm[it] != infoB.pcmBuffer[it] }
                    val m = "$next pcm: counter ${info.pcmDmaCounter}->${infoB.pcmDmaCounter} " +
                        "period ${info.pcmDmaPeriod} spv ${info.pcmSamplesPerVBlank} " +
                        "reverb ${info.reverb} diffs ${diff.size} first ${diff.take(3)}"
                    assertTrue(diff.isEmpty(), m)
                    pcmFrames++
                }
                for (c in 0 until 64) if (runs ushr c and 1L != 0L) frameRuns[c]++
                if (runs and 1L != 0L) fines++
                if (runs ushr (0xCE - 0xB1) and 1L != 0L) endties++
                val all = info.chans + info.cgbChans!!
                val allB = infoB.chans + infoB.cgbChans!!
                for (c in all.indices) {
                    if (c >= info.chans.size && all[c].status == 0x80) notes++ // CGB
                    assertEquals(fields(allB[c]), fields(all[c]), "$next channel $c")
                    if (c >= info.chans.size) {
                        if (all[c].status != 0) cgbActive++
                        assertEquals(cgb(allB[c]), cgb(all[c]), "$next CGB channel $c")
                    }
                }
                for (i in players.indices) {
                    for (t in 0 until players[i].trackCount) {
                        val msg = "$next player $i track $t"
                        assertEquals(state(qs[i].tracks[t]), state(players[i].tracks[t]), msg)
                    }
                    for (t in players[i].tracks.indices) {
                        assertEquals(
                            qs[i].tracks[t].chan?.address ?: 0,
                            players[i].tracks[t].chan?.address ?: 0,
                            "$next player $i track $t chan",
                        )
                    }
                }
            }
        }
        println(
            "M4aNoteTest: frames $frames, skipped $skipped, notes $notes, FINE $fines, " +
                "EOT $endties",
        )
        println("M4aNoteTest: envelopes $enveloped, sample ends $ended, pcm frames $pcmFrames, " +
            "fixed-rate $fixed, looping $looped, maxLines $maxLines, " +
            "pcmFreq $pcmFreq, CGB active $cgbActive")
        println("M4aNoteTest: why ${why.toList()} " + (0 until 256).filter { cmds[it] > 0 }
            .joinToString { "0x%02X=%d".format(it, cmds[it]) })
        println("M4aNoteTest: frames running " + (0 until 64).filter { frameRuns[it] > 0 }
            .joinToString { "0x%02X=%d".format(it + 0xB1, frameRuns[it]) })
        assertTrue(frames > 500 && notes > 100, "frames $frames, notes $notes")
        if (psgSamples > 0) {
            // Output units -> mGBA int16: 48 * 4 per DirectSound byte vs 256 here = 0.75.
            val m = psgMean / psgSamples
            // AC RMS (mean removed), as measured on mGBA's output.
            val psgRms = 0.75 * kotlin.math.sqrt(psgSum / psgSamples - m * m)
            println("M4aNoteTest: PSG RMS $psgRms (mGBA int16 units) over $psgSamples samples")
            // mGBA's PSG mix = sum of the one-channel runs (linear; noise unused here).
            val runs = listOf(1, 2, 4).map { ref.run("audio_psg$it") }
            if (System.getenv("FE8_PSG_MASK") == null && runs.all { it.hasAudio }) {
                val a = runs[0].audioEnd[900] * 2
                val b = runs[0].audioEnd[1100] * 2
                val x = DoubleArray(b - a) { i -> runs.sumOf { it.audio[a + i].toDouble() } }
                val mean = x.average()
                val mgba = kotlin.math.sqrt(x.sumOf { (it - mean) * (it - mean) } / x.size)
                println("M4aNoteTest: mGBA PSG RMS $mgba")
                // Envelope/trigger timing (frame sequencer phase): dump n sounds in mGBA's
                // frame n + 1 chunk [audioEnd[n], audioEnd[n + 1]); compare per quarter frame.
                var bad = 0
                var loud = 0
                for ((c, run) in runs.withIndex()) for (f in 900 until 1099) {
                    val s0 = run.audioEnd[f]
                    val len = run.audioEnd[f + 1] - s0
                    for (q in 0 until 4) {
                        val k0 = s0 + q * len / 4
                        val x = DoubleArray(len / 4) { i ->
                            run.audio[2 * (k0 + i)] + run.audio[2 * (k0 + i) + 1].toDouble()
                        }
                        val ours = psgQuarters[c][4 * (f - 900) + q]
                        val theirs = peakToPeak(x)
                        val big = maxOf(ours, theirs)
                        if (big < 200) continue
                        loud++
                        if (kotlin.math.abs(ours - theirs) > 0.15 * big) {
                            bad++
                            println("M4aNoteTest: PSG off ${run.name} f$f q$q ours $ours mGBA $theirs")
                        }
                    }
                }
                println("M4aNoteTest: PSG quarter frames off vs mGBA: $bad of $loud")
                assertTrue(bad * 50 < loud, "PSG envelope timing: $bad of $loud quarter frames off")
                assertTrue(psgRms / mgba in 0.75..1.2, "PSG RMS $psgRms vs mGBA $mgba")
            }
        }
        // CgbSound's retriggers (NRx4 bit 7) per frame = mGBA's writes (audio_menu --psg-writes):
        // includes the ones MPlayMain flags (`mo`) on track volume / pitch changes.
        val menu = ref.run("audio_menu")
        if (ourTriggers.isNotEmpty() && menu.hasPsgWrites) {
            val theirs = HashMap<Int, Int>()
            for (w in menu.psgWrites) {
                if (w.step !in 901..1100 || w.width != 1 || w.value and 0x80 == 0L) continue
                val bit = when (w.offset) {
                    0x65 -> 1
                    0x6D -> 2
                    0x75 -> 4
                    0x7D -> 8
                    else -> 0
                }
                theirs[w.step] = (theirs[w.step] ?: 0) or bit
            }
            val diff = (901..1100).filter { (ourTriggers[it] ?: 0) != (theirs[it] ?: 0) }
            assertTrue(theirs.size > 40 && diff.isEmpty(), "PSG triggers differ at steps $diff")
        }
        assertTrue(cgbActive > 100, "CGB active channel-frames $cgbActive")
        assertTrue(pcmFrames > 500 && fixed > 0 && looped > 0, "pcm $pcmFrames, fixed $fixed")
        assertTrue(why[0] >= 4 && immInits >= 1 && vSyncs > 0, "song starts ${why[0]} (songs $songNums), ImmInit $immInits")
        assertTrue(fines >= 2 && endties >= 4, "FINE in $fines frames, EOT in $endties")
    }

    private val NR_X1 = intArrayOf(0x04000062, 0x04000068, 0x04000072, 0x04000078)
    private val NR_X4 = intArrayOf(0x04000065, 0x0400006D, 0x04000075, 0x0400007D)
    private val NR_X2 = intArrayOf(0x04000063, 0x04000069, 0x04000073, 0x04000079)

    private fun peakToPeak(x: DoubleArray): Double = x.max() - x.min()

    private fun env(c: M4aChannel): List<Int> = listOf(
        c.status, c.envelopeVolume, c.envelopeRight, c.envelopeLeft, c.echoLength,
    )

    private fun cgb(c: M4aChannel): List<Int> = listOf(
        c.status, c.envelopeVolume, c.envelopeRight, c.envelopeLeft, c.sustainGoal, c.n4, c.pan,
        c.modify, c.frequency, c.echoLength,
    )

    private fun mixState(c: M4aChannel): List<Int> = listOf(c.count, c.fw, c.currentPointer)

    private fun fields(c: M4aChannel): List<Int> = listOf(
        c.type, c.key, c.gateTime, c.midiKey, c.velocity, c.priority, c.rhythmPan, c.frequency,
        c.wav, c.rightVolume, c.leftVolume, c.track?.address ?: 0, c.prev?.address ?: 0,
        c.next?.address ?: 0,
    )

    private fun state(t: M4aTrack): List<Int> = listOf(
        t.flags, t.wait, t.patternLevel, t.repN, t.gateTime, t.key, t.velocity, t.runningStatus,
        t.keyM, t.pitM, t.keyShift, t.tune, t.bend, t.bendRange, t.volMR, t.volML, t.vol, t.volX,
        t.pan, t.modM, t.mod, t.modT, t.lfoSpeed, t.lfoSpeedC, t.lfoDelay, t.lfoDelayC, t.priority,
        t.cmdPtr,
    ) + t.tone.toList()

    /** Player addresses from `gSoundInfo.intp` along `MusicPlayerInfo.intp`. */
    private fun chain(iw: ByteArray): List<Int> {
        val out = mutableListOf<Int>()
        var p = u32(iw, 0x5410 + 0x24)
        while (p ushr 24 == 3 && out.size < 16) {
            out += p
            p = u32(iw, p - 0x03000000 + 0x3C)
        }
        return out
    }

    private fun u32(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or
        ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or
        ((b[o + 3].toInt() and 0xFF) shl 24)

    private companion object {
        /** `gSongTable` (fireemblem8.nm.txt); 1000 entries up to `dummy_song`. */
        const val SONG_TABLE = 0x08224470
        const val SONG_COUNT = 1000
        val DUMP = Regex("""r(\d+)\.iwram\.bin""")
    }
}
