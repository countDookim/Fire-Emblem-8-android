package fe8.game.event

import fe8.game.SoundWrapper
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import gba.core.VerifiesPort
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Tests `Event12_StartBGM` against the reference state at `ram_trace_newgame_bm` r09539 -> r09540
 * and verifies fallback and skipping branches.
 */
class EventBgmTest {
    private val assets =
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"

    @VerifiesPort("Event12_StartBGM")
    @Test
    fun `event12StartBgm starts song and matches reference sound state at r09540`() {
        val dir = File(assets, "reference/out/ram_trace_newgame_bm")
        val romFile = File(assets, "baserom.gba")
        assumeTrue(romFile.isFile, "ROM not present")
        assumeTrue(File(dir, "r09540.ewram.bin").isFile, "ram_trace_newgame_bm missing")

        val romBytes = romFile.readBytes()
        val mem = ScriptMemory { address ->
            val romOffset = address - 0x08000000
            if (romOffset in 0 until romBytes.size - 1) {
                (romBytes[romOffset].toInt() and 0xFF) or ((romBytes[romOffset + 1].toInt() and 0xFF) shl 8)
            } else {
                0
            }
        }

        val preEwram = File(dir, "r09539.ewram.bin").readBytes()
        val postEwram = File(dir, "r09540.ewram.bin").readBytes()

        val soundStOffset = GSOUNDST - EWRAM_BASE
        // In r09539, previous song (4) is stopped
        assertEquals(0, preEwram[soundStOffset + 6].toInt(), "r09539 song stopped")

        val procOffset = GPROC_EVENT_ENGINE - EWRAM_BASE
        val pEventCurrent39 = (preEwram[procOffset + 0x38].toInt() and 0xFF) or
            ((preEwram[procOffset + 0x39].toInt() and 0xFF) shl 8) or
            ((preEwram[procOffset + 0x3A].toInt() and 0xFF) shl 16) or
            ((preEwram[procOffset + 0x3B].toInt() and 0xFF) shl 24)
        assertEquals(0x089EECD8.toInt(), pEventCurrent39, "pEventCurrent at r09539")

        val sound = SoundWrapper(ProcEngine(), null) { 0 }
        val state = EventState()
        val eventBgm = EventBgm(state, mem, sound)

        val proc = EventEngineProc()
        proc.pEventCurrent = pEventCurrent39
        proc.evStateBits = 0

        val result = eventBgm.event12StartBgm.run(proc)
        assertEquals(EVC_ADVANCE_YIELD, result, "Event12_StartBGM yields")

        val expectedSong = (postEwram[soundStOffset + 4].toInt() and 0xFF) or
            ((postEwram[soundStOffset + 5].toInt() and 0xFF) shl 8)
        assertEquals(0x26, expectedSong, "expected song 0x26 in r09540")
        assertEquals(expectedSong, sound.songId, "sound.songId matches r09540")
        assertEquals(postEwram[soundStOffset + 6].toInt() != 0, sound.isSongPlaying, "sound.isSongPlaying matches r09540")
        assertEquals(postEwram[soundStOffset + 7].toInt(), sound.unk7, "sound.unk7 matches r09540")
        assertEquals(postEwram[soundStOffset + 8].toInt(), sound.maxChannels, "sound.maxChannels matches r09540")
    }

    @Test
    fun `event12StartBgm reads slot 2 when argument is negative`() {
        val state = EventState()
        state.slots[2] = 0x34
        val words = shortArrayOf(0x1220.toShort(), (-1).toShort())
        val mem = ScriptMemory { address ->
            val index = (address ushr 1) and 1
            words[index].toInt() and 0xFFFF
        }
        val sound = SoundWrapper(ProcEngine(), null) { 0 }
        val eventBgm = EventBgm(state, mem, sound)

        val proc = EventEngineProc()
        proc.pEventCurrent = 0
        proc.evStateBits = 0

        val result = eventBgm.event12StartBgm.run(proc)
        assertEquals(EVC_ADVANCE_YIELD, result)
        assertEquals(0x34, sound.songId)
        assertTrue(sound.isSongPlaying)
    }

    @Test
    fun `event12StartBgm continues and does not start song when skipping`() {
        val state = EventState()
        val words = shortArrayOf(0x1220.toShort(), 0x0026.toShort())
        val mem = ScriptMemory { address ->
            val index = (address ushr 1) and 1
            words[index].toInt() and 0xFFFF
        }
        val sound = SoundWrapper(ProcEngine(), null) { 0 }
        val eventBgm = EventBgm(state, mem, sound)

        val proc = EventEngineProc()
        proc.pEventCurrent = 0
        proc.evStateBits = EV_STATE_SKIPPING

        val result = eventBgm.event12StartBgm.run(proc)
        assertEquals(EVC_ADVANCE_CONTINUE, result)
        assertEquals(0, sound.songId)
        assertFalse(sound.isSongPlaying)
    }

    private companion object {
        const val EWRAM_BASE = 0x02000000
        const val GSOUNDST = 0x02024E5C
        const val GPROC_EVENT_ENGINE = 0x02025018
    }
}
