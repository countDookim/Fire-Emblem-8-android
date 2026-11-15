package fe8.testing

import fe8.game.bm.Mu
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The MU step sounds (EP-8b, src/mu.c `ProcScr_MuStepSe`, src/bmlib.c `PlaySeSpacial`) on the
 * `CpPerformSim` walks: the boss f3805..3829 (`ram_trace_prologue_clear`), Soldier 1 f1615..1647
 * and Soldier 2 f2040..2080 (`ram_trace_prologue_enemy_phase`, engine frame f is step f + 1).
 * Per step: the `ProcScr_MuStepSe` proc (existence, script position, `song1`, `song2`, `unk60`, `x1`, `x2`). In the frame
 * `PlaySeA` plays a song, the capture's music player for that song (IWRAM
 * `MusicPlayerInfo`) holds the song's header and, on every track, the external pan of
 * `Screen2Pan(x)`. The sim's players start from a clean `m4aSoundInit`, not the capture's.
 */
class MuStepSeDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun walk(s: CpPerformSim, first: Int, last: Int, shift: Int = 0): Int {
        s.logSe = true
        val names = ProcArrayDump.romNames()
        val players = M4aRam.PLAYERS.values.toList()
        var played = 0
        for (f in first..last) {
            val before = s.seLog.size
            s.frame()
            val step = f + shift
            val at = "f$step"
            val dump = ProcArrayDump.ofWatch(s.data.watch("procs"), step, names)
            val want = dump.find(PROC_SCR_MU_STEP_SE)
            val ours = s.engine.find(s.mu.stepSeScript)
            assertEquals(want != null, ours != null, "ProcScr_MuStepSe exists $at")
            if (want != null) {
                val st = ours!!.state as Mu.StepSeState
                assertEquals(want.scrCur, ours.scrCur, "ProcScr_MuStepSe script position $at")
                assertEquals(want.u32(0x58), st.song1, "MuStepSe song1 $at")
                assertEquals(want.u32(0x5C), st.song2, "MuStepSe song2 $at")
                assertEquals(want.u32(0x60), st.unk60, "MuStepSe unk60 $at")
                assertEquals(want.u16(0x64).toShort().toInt(), st.x1, "MuStepSe x1 $at")
                assertEquals(want.u16(0x66).toShort().toInt(), st.x2, "MuStepSe x2 $at")
            }
            for (k in before until s.seLog.size) {
                val (_, song, x) = s.seLog[k].split(" ").map { it.removePrefix("f").toInt() }
                val iwram = s.iwram(step)
                val index = s.m4a.songs.player(song)
                val (address, slots) = players[index]
                val captured = M4aRam.player(iwram, address, slots)
                val ours2 = s.m4a.players[index]
                assertEquals(
                    captured.songHeader, ours2.songHeader, "SE player $index song header $at",
                )
                for (t in 0 until captured.trackCount) {
                    assertEquals(
                        captured.tracks[t].panX, ours2.tracks[t].panX,
                        "SE player $index track $t panX (song $song, x $x) $at",
                    )
                }
                played++
            }
        }
        return played
    }

    @Test
    @VerifiesPort(
        "MuStepSe_PlaySeA", "MuStepSe_PlaySeB", "PlayMuStepSe",
        "StartPlayMuStepSe", "PlaySeSpacial", "Screen2Pan", "m4aMPlayPanpotControl",
    )
    fun `the boss's step sounds match the capture f3805 to f3829`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val s = CpPerformSim.create(ref, romFile, "ram_trace_prologue_clear", 3803, 0x81)
        assertTrue(walk(s, 3805, 3829) >= 4, "sounds played")
    }

    @Test
    @VerifiesPort(
        "MuStepSe_PlaySeA", "MuStepSe_PlaySeB", "PlayMuStepSe",
        "StartPlayMuStepSe", "PlaySeSpacial", "Screen2Pan", "m4aMPlayPanpotControl",
    )
    fun `Soldier 1's step sounds match the capture f1615 to f1647`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val s = CpPerformSim.create(
            ref, romFile, "ram_trace_prologue_enemy_phase", 1613, 0x82, 1614,
        )
        assertTrue(walk(s, 1615, 1647) >= 2, "sounds played")
    }

    @Test
    @VerifiesPort(
        "MuStepSe_PlaySeA", "MuStepSe_PlaySeB", "PlayMuStepSe",
        "StartPlayMuStepSe", "PlaySeSpacial", "Screen2Pan", "m4aMPlayPanpotControl",
    )
    fun `Soldier 2's step sounds match the capture f2040 to f2080`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val s = CpPerformSim.create(
            ref, romFile, "ram_trace_prologue_enemy_phase", 2038, 0x83, 2038,
        )
        // Soldier 2: the capture's step 2039 is a snapshot inside the frame that starts the walk
        // (MU and step proc are already there, as at 2040), so engine frame f is step f + 1.
        assertTrue(walk(s, 2039, 2079, shift = 1) >= 2, "sounds played")
    }

    private companion object {
        const val PROC_SCR_MU_STEP_SE = 0x089A2938
    }
}
