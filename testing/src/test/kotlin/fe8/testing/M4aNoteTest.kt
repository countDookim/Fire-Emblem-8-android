package fe8.testing

import gbafe.audio.M4aChannel
import gbafe.audio.M4aSequencer
import gbafe.audio.M4aTrack
import gbafe.core.VerifiesPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Notes and channel bookkeeping: from each per-frame dump, every player's `MPlayMain` (in the
 * dump's `intp` chain order, sharing one set of channels) reproduces the next dump's channel
 * chains and the channel fields the mixer does not touch. Frames with a fade, a song change or an
 * unmodelled command are skipped.
 */
class M4aNoteTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("ply_note", "RealClearChain")
    fun `channels step like the per-frame RAM traces`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present")
        val rom = romFile.readBytes()
        var frames = 0
        var skipped = 0
        var notes = 0
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
                val order = chain(a).map { addr -> M4aRam.PLAYERS.values.indexOfFirst { it.first == addr } }
                if (order.any { it < 0 }) continue
                val seq = M4aSequencer({ x -> rom[x - 0x08000000].toInt() and 0xFF }, info)
                var ok = true
                for (i in order) {
                    val p = players[i]
                    val q = qs[i]
                    if (p.songHeader != q.songHeader || q.fadeOI != 0 || p.fadeOI != 0) ok = false
                    if (!ok) break
                    seq.step(p)
                    if (seq.unsupported != 0) ok = false
                }
                if (!ok) {
                    skipped++
                    continue
                }
                frames++
                val all = info.chans + info.cgbChans!!
                val allB = infoB.chans + infoB.cgbChans!!
                for (c in all.indices) {
                    if (all[c].status == 0x80) notes++
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
        println("M4aNoteTest: frames $frames, skipped $skipped, notes started $notes")
        assertTrue(frames > 500 && notes > 100, "frames $frames, notes $notes")
    }

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
