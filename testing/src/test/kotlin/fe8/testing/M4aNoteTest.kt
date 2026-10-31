package fe8.testing

import gbafe.audio.M4aBus
import gbafe.audio.M4aChannel
import gbafe.audio.M4aMixer
import gbafe.audio.M4aSequencer
import gbafe.audio.M4aTrack
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
    @VerifiesPort("ply_note", "RealClearChain", "ply_fine", "ply_endtie", "SoundMainRAM")
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
        var fixed = 0
        var looped = 0
        var maxLines = 0
        var endties = 0
        for (dir in ref.root.listFiles().orEmpty().filter { it.isDirectory }) {
            val dumps = dir.listFiles().orEmpty().mapNotNull { f ->
                DUMP.matchEntire(f.name)?.let { it.groupValues[1].toInt() to f }
            }.toMap()
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
                for (i in order) {
                    val p = players[i]
                    val q = qs[i]
                    // A live player's fade (FadeOutBody) is not modelled; paused ones skip it.
                    val fade = (p.status >= 0 && p.fadeOI != 0) || (q.status >= 0 && q.fadeOI != 0)
                    if (p.songHeader != q.songHeader) why[0]++
                    if (fade) why[1]++
                    if (p.songHeader != q.songHeader || fade) ok = false
                    if (!ok) break
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
                maxLines = maxOf(maxLines, a[0x5410 + 0x0C].toInt() and 0xFF)
                notes += info.chans.count { it.status == 0x80 }
                // m4aSoundVSync (VBlank IRQ) steps the DMA counter before SoundMain runs.
                info.pcmDmaCounter = infoB.pcmDmaCounter
                M4aMixer.frame(info, bus)
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
            "fixed-rate $fixed, looping $looped, maxLines $maxLines")
        println("M4aNoteTest: why ${why.toList()} " + (0 until 256).filter { cmds[it] > 0 }
            .joinToString { "0x%02X=%d".format(it, cmds[it]) })
        println("M4aNoteTest: frames running " + (0 until 64).filter { frameRuns[it] > 0 }
            .joinToString { "0x%02X=%d".format(it + 0xB1, frameRuns[it]) })
        assertTrue(frames > 500 && notes > 100, "frames $frames, notes $notes")
        assertTrue(pcmFrames > 500 && fixed > 0 && looped > 0, "pcm $pcmFrames, fixed $fixed")
        assertTrue(fines >= 2 && endties >= 4, "FINE in $fines frames, EOT in $endties")
    }

    private fun env(c: M4aChannel): List<Int> = listOf(
        c.status, c.envelopeVolume, c.envelopeRight, c.envelopeLeft, c.echoLength,
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
        val DUMP = Regex("""r(\d+)\.iwram\.bin""")
    }
}
