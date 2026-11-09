package fe8.testing

import fe8.game.Fe8Sound
import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.opanim.OpSubtitleSound
import gba.audio.M4aBus
import gba.audio.M4aSequencer
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Checks the subtitle's fade against the original proc and sound captures. */
class OpSubtitleSoundDifferentialTest {
    @Test
    @VerifiesPort("sub_80C5400")
    fun `subtitle end fades the original players at the traced step`() {
        val ref = ReferenceData.fromEnvironment()
        val assets = System.getenv("FE8_ASSETS") ?: ".assets"
        val romFile = File(assets, "baserom.gba")
        assumeTrue(ref.available && romFile.isFile, "reference capture or ROM unavailable")
        val run = ref.run("ram_trace_prologue")
        val trace = run.watch("procs")
        val beforeProc = ProcArrayDump.ofWatch(trace, 4798).find(0x08AA21BC)!!
        val afterProc = ProcArrayDump.ofWatch(trace, 4799).find(0x08AA21BC)!!
        assertEquals(41, beforeProc.scrCur)
        assertEquals(47, afterProc.scrCur)

        val rom = RomView.ofRom(romFile.readBytes())
        // Commands 41..46 execute on step 4799; command 44 is the sound callback.
        assertEquals(0x080C5401, rom.u32(0x08AA21BC + 44 * 8 + 4))
        val sound = Fe8Sound.create(rom)
        val wrapper = SoundWrapper(ProcEngine(), sound) { 0 }
        val soundSt = RamSymbols.fromEnvironment()["gSoundSt"].address
        val beforeEwram = run.region("r04770", "ewram")
        val song = GbaMem.u16(beforeEwram, GbaMem.EWRAM, soundSt + 4)
        assertEquals(1, beforeEwram[soundSt + 6 - GbaMem.EWRAM].toInt())
        wrapper.startBgmCore(song, -1)
        val (players, info) = M4aRam.sound(run.region("r04770", "iwram"))
        @Suppress("UNCHECKED_CAST")
        val livePlayers = sound.players as MutableList<gba.audio.M4aPlayer>
        for (i in players.indices) livePlayers[i] = players[i]
        val sequencer = M4aSequencer(M4aBus { address -> rom.u8(address) }, info)
        for (frame in 4771..4799) {
            for (player in players) sequencer.step(player)
        }
        assertNotEquals(4, players[0].fadeOI)
        OpSubtitleSound(wrapper).fadeOutBgm()
        for (player in players) sequencer.step(player)
        val (expected, _) = M4aRam.sound(run.region("f04800", "iwram"))
        for (i in 0..1) {
            assertEquals(
                listOf(expected[i].fadeOI, expected[i].fadeOC, expected[i].fadeOV),
                listOf(players[i].fadeOI, players[i].fadeOC, players[i].fadeOV),
                "BGM player $i",
            )
        }
        val afterEwram = run.region("f04800", "ewram")
        assertEquals(GbaMem.u16(afterEwram, GbaMem.EWRAM, soundSt + 4), wrapper.songId)
        assertEquals(afterEwram[soundSt + 6 - GbaMem.EWRAM].toInt() != 0, wrapper.isSongPlaying)
    }
}
