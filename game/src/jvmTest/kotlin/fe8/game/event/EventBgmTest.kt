package fe8.game.event

import fe8.game.SoundWrapper
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.proc.ProcEngine.Companion.FLAG_BLOCKING
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Tests `Event12_StartBGM` against the reference state at `ram_trace_newgame_bm` r09539 -> r09540
 * and verifies fallback and skipping branches. `Event15_BgmVolume` subcode 0 is checked against
 * the `ram_trace_prologue` proc array before and after the one 0x15 command in the trace.
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

    @VerifiesPort("Event15_BgmVolume")
    @Test
    fun `event15BgmVolume duck matches the prologue proc array`() {
        val ref = ReferenceData.fromEnvironment()
        val romFile = File(assets, "baserom.gba")
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val run = ref.run("ram_trace_prologue")
        assumeTrue(run.hasWatch("procs"), "ram_trace_prologue has no procs watch")
        val trace = run.watch("procs")
        val romBytes = romFile.readBytes()
        val rom = ScriptMemory { address ->
            val o = address - 0x08000000
            (romBytes[o].toInt() and 0xFF) or ((romBytes[o + 1].toInt() and 0xFF) shl 8)
        }
        val before = trace.step(trace.firstStep + CMD_RECORD)
        val after = trace.step(trace.firstStep + CMD_RECORD + 1)

        fun slot(rec: ByteArray, script: Int): Int? =
            (0 until 64).firstOrNull { u32(rec, it * PROC_SIZE) == script }
        fun field(rec: ByteArray, slot: Int, offset: Int) = u32(rec, slot * PROC_SIZE + offset)

        val engBefore = slot(before, STD_EVENT_ENGINE)!!
        val engAfter = slot(after, STD_EVENT_ENGINE)!!
        val vcAfter = slot(after, MUSIC_VC_SCRIPT)!!
        assertEquals(null, slot(before, MUSIC_VC_SCRIPT), "no volume proc before the command")
        val pCurrent = field(before, engBefore, 0x38)
        assertEquals(0x1520, rom.u16(pCurrent), "the traced command is 0x15, subcode 0")
        val evStateBits = field(before, engBefore, 0x3C) and 0xFFFF
        assertEquals(0, evStateBits and EV_STATE_SKIPPING, "not skipping")

        val procs = ProcEngine().also { it.init() }
        val sound = SoundWrapper(procs, null) { 0 }
        val eventEngine = EventEngine(procs, EventState(), rom)
        val bgm = EventBgm(eventEngine.state, rom, sound)
        val state = eventEngine.create(pCurrent, 2)
        state.pEventCurrent = pCurrent
        state.evStateBits = evStateBits
        val parent = state.proc!!
        val lockBefore = field(before, engBefore, 0x28) and 0xFF

        assertEquals(EVC_ADVANCE_YIELD, bgm.event15BgmVolume.run(state))

        // The trace is one frame later: the loop has run once (clock 1), so the clock is not
        // compared; the volumes and time are the call's arguments.
        val vc = sound.bgmVolumeChange!!
        assertEquals(field(after, vcAfter, 0x64) and 0xFFFF, vc.initVolume, "init volume")
        assertEquals(field(after, vcAfter, 0x64) ushr 16, vc.endVolume, "end volume")
        assertEquals(field(after, vcAfter, 0x68) ushr 16, vc.timeEnd, "time")
        val child = procs.find(sound.bgmVolumeChangeScript)!!
        assertEquals(field(after, vcAfter, 0x14), PROC_ARRAY + engAfter * PROC_SIZE, "parent address")
        assertEquals(parent, child.parent, "child of the event proc")
        assertEquals(field(after, vcAfter, 0x24) ushr 24, child.flags, "proc flags")
        assertEquals(FLAG_BLOCKING, child.flags and FLAG_BLOCKING, "blocking")
        assertEquals(field(after, engAfter, 0x28) and 0xFF, parent.lockCnt, "event proc lockCnt")
        assertEquals(lockBefore + 1, parent.lockCnt, "the event proc is blocked")
    }

    @Test
    fun `event15BgmVolume subcode 1 restores and skipping branches`() {
        val state = EventState()
        val words = shortArrayOf(0x1521.toShort(), 0)
        val mem = ScriptMemory { address -> words[(address ushr 1) and 1].toInt() and 0xFFFF }
        val procs = ProcEngine().also { it.init() }
        val sound = SoundWrapper(procs, null) { 0 }
        val bgm = EventBgm(state, mem, sound)
        val engine = EventEngine(procs, state, mem)
        val proc = engine.create(0, 2)
        proc.pEventCurrent = 0
        assertEquals(EVC_ADVANCE_YIELD, bgm.event15BgmVolume.run(proc))
        assertEquals(SoundWrapper.BgmVolumeChange(0x90, 0x100, 0, 10), sound.bgmVolumeChange)

        proc.evStateBits = EV_STATE_SKIPPING
        procs.end(procs.find(sound.bgmVolumeChangeScript))
        assertEquals(EVC_ADVANCE_CONTINUE, bgm.event15BgmVolume.run(proc))
        assertEquals(null, sound.bgmVolumeChange, "skipping starts no proc")
        words[0] = 0x1520.toShort()
        assertEquals(EVC_ADVANCE_CONTINUE, bgm.event15BgmVolume.run(proc))
        assertEquals(null, sound.bgmVolumeChange, "skipping starts no proc")
    }

    private fun u32(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or
        ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or
        ((b[o + 3].toInt() and 0xFF) shl 24)

    private companion object {
        /** Record before the only 0x15 command of ram_trace_prologue (the next record is after). */
        const val CMD_RECORD = 16053
        const val PROC_SIZE = 0x6C
        const val PROC_ARRAY = 0x02024E68
        const val STD_EVENT_ENGINE = 0x08591AC0
        const val MUSIC_VC_SCRIPT = 0x08587970
        const val EWRAM_BASE = 0x02000000
        const val GSOUNDST = 0x02024E5C
        const val GPROC_EVENT_ENGINE = 0x02025018
    }
}
