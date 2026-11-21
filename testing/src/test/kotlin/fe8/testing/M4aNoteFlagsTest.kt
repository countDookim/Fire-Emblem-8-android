package fe8.testing

import gba.audio.M4aBus
import gba.audio.M4aChannel
import gba.audio.M4aCommands
import gba.audio.M4aPlayer
import gba.audio.M4aSoundInfo
import gba.audio.M4aTrack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `ply_note` ends with `track->flags &= 0xF0` (m4a_1.s, `_081DDCDC`): a pending volume or pitch
 * change of the track is dropped, so the track's older, still playing channels never take it.
 * Real RAM shows it (`ram_trace_prologue_end` f6620: vibrato ended and a new note started in
 * one `MPlayMain`; the released channel keeps its pitch from before).
 */
class M4aNoteFlagsTest {
    @Test
    fun `a new note drops the pending pitch change of the older channels`() {
        val chans = Array(4) { M4aChannel(0x03005460 + it * 0x40) }
        val info = M4aSoundInfo(chans, null).also { it.maxChans = 4 }
        val t = M4aTrack().also {
            it.address = 0x03002000
            it.cmdPtr = 0x0900_0000
            it.tone[1] = 0x0900_0100 // wave: freq word at +4
            it.flags = M4aTrack.FLG_EXIST
        }
        val p = M4aPlayer(arrayOf(t)).also { it.trackCount = 1 }
        val bus = M4aBus { a ->
            when (a) {
                0x0900_0000 -> 60 // key of the first note
                0x0900_0001 -> 0x80 // end of arguments
                0x0900_0105 -> 0x34 // wave freq 0x00344300
                0x0900_0106 -> 0x43
                else -> 0
            }
        }
        t.pitM = 16
        M4aCommands.note(info, p, t, bus, 0)
        val old = t.chan!!
        val oldFreq = old.frequency
        // The vibrato ends: pitM back to 0, channels flagged; then the next note starts.
        t.pitM = 0
        t.flags = t.flags or M4aTrack.FLG_PITCHG or M4aTrack.FLG_VOLCHG
        M4aCommands.note(info, p, t, bus, 0)
        assertEquals(M4aTrack.FLG_EXIST, t.flags, "pending flags dropped")
        assertEquals(oldFreq, old.frequency, "older channel keeps its frequency")
        assertEquals(old, t.chan!!.next)
    }
}
