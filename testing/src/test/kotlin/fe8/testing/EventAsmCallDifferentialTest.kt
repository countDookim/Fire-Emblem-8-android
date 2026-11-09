package fe8.testing

import fe8.game.bm.PlaySt
import fe8.game.event.EventAsmCall
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineProc
import fe8.game.event.EventScrCore
import fe8.game.event.EventState
import fe8.game.event.ScriptMemory
import fe8.game.event.bmGuideTextSetAllGreen
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import java.io.File
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Compares the Prologue's guide ASMC with the original's proc and full flag snapshots. */
class EventAsmCallDifferentialTest {
    @VerifiesPort("Event0D_AsmCall")
    @Test
    fun `ASMC dispatches guide callback and yields at the original next command`() {
        val sample = sample()
        val state = EventState()
        sample.before.slots.copyInto(state.slots)
        sample.before.activeQueue.forEachIndexed { index, entry ->
            state.activeQueue[index * 2] = entry.evt1
            state.activeQueue[index * 2 + 1] = entry.evt2
        }
        val flags = Fe8EventFlags.create().apply { copyIn(sample.beforeFlags) }
        val playSt = PlaySt().apply { decode(requireNotNull(sample.before.playSt), 0) }
        val engine = EventEngine(ProcEngine(), state, sample.memory)
        EventScrCore(
            state,
            sample.memory,
            flags = flags,
            rng = RngService(),
            playSt = playSt,
            keys = KeyStatus(),
        ).registerAll(engine)
        val initial = requireNotNull(sample.before.engine)
        val proc = EventEngineProc().apply {
            pEventStart = initial.pEventStart
            pEventIdk = initial.pEventIdk
            pEventCurrent = initial.pEventCurrent
            evStateBits = initial.evStateBits
            evStallTimer = initial.evStallTimer
            execType = initial.execType
        }
        var callbackCalls = 0
        var resultCode: Int? = null
        val asmCall = EventAsmCall(
            sample.memory,
            mapOf(GUIDE_FUNCTION to { callbackProc ->
                callbackCalls++
                assertSame(proc, callbackProc)
                assertEquals(ASMC_COMMAND, callbackProc.pEventCurrent)
                bmGuideTextSetAllGreen(sample.memory, flags)
            }),
            onUnknown = { error("unexpected ASMC address: ${it.toUInt().toString(16)}") },
        )
        engine.register(0x0D) {
            asmCall.event0DAsmCall.run(it).also { resultCode = it }
        }
        engine.onUpdate(proc)

        assertEquals(1, callbackCalls)
        // The next original snapshot is exactly after ASMC, before LABEL/EVBIT can run.
        assertEquals(EVC_ADVANCE_YIELD, resultCode)
        val expected = requireNotNull(sample.after.engine)
        assertEquals(expected.pEventCurrent, proc.pEventCurrent)
        assertEquals(expected.pEventIdk, proc.pEventIdk)
        assertEquals(expected.pEventStart, proc.pEventStart)
        assertEquals(expected.evStateBits, proc.evStateBits)
        assertEquals(expected.evStallTimer, proc.evStallTimer)
        assertEquals(expected.execType, proc.execType)
        assertArrayEquals(sample.afterFlags, ByteArray(flags.size).also { flags.copyOut(it) })
    }

    @Test
    @VerifiesPort("BmGuideTextSetAllGreen")
    fun `guide display flags match original and stop at the ROM title sentinel`() {
        val sample = sample()
        val flags = Fe8EventFlags.create().apply { copyIn(sample.beforeFlags) }
        val reads = mutableListOf<Int>()
        val guideMemory = ScriptMemory { address ->
            // A read beyond the sentinel would consume unrelated ROM data as guide entries.
            assertTrue(address <= sample.sentinelAddress, "read beyond guide title sentinel")
            reads += address
            sample.memory.u16(address)
        }
        bmGuideTextSetAllGreen(guideMemory, flags)

        assertEquals(sample.sentinelAddress, reads.last())
        assertArrayEquals(sample.afterFlags, ByteArray(flags.size).also { flags.copyOut(it) })
    }


    @Test
    fun `ASMC with subcmd 1 skips when skipping`() {
        var called = false
        val words = shortArrayOf(0x0D11.toShort(), 0, 0, 0)
        val mem = ScriptMemory { address ->
            val index = (address ushr 1) and 3
            words[index].toInt() and 0xFFFF
        }
        val asmCall = EventAsmCall(mem, mapOf(0 to { called = true }))
        val proc = EventEngineProc().apply {
            pEventCurrent = 0
            evStateBits = EV_STATE_SKIPPING
        }
        val result = asmCall.event0DAsmCall.run(proc)
        assertEquals(EVC_ADVANCE_CONTINUE, result)
        assertFalse(called)
    }

    @Test
    fun `ASMC invokes onUnknown when function address is unregistered`() {
        var unknownAddr = -1
        val words = shortArrayOf(0x0D00.toShort(), 0, 0x1234.toShort(), 0x0800.toShort())
        val mem = ScriptMemory { address ->
            val index = (address ushr 1) and 3
            words[index].toInt() and 0xFFFF
        }
        val asmCall = EventAsmCall(mem, emptyMap(), onUnknown = { unknownAddr = it })
        val proc = EventEngineProc().apply {
            pEventCurrent = 0
            evStateBits = 0
        }
        val result = asmCall.event0DAsmCall.run(proc)
        assertEquals(EVC_ADVANCE_CONTINUE, result)
        assertEquals(0x08001234, unknownAddr)
    }

    /** Loads both sides of the update containing the opening's only ASMC, from runtime assets. */
    private fun sample(): GuideSample {
        val assets = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val romFile = File(assets, "baserom.gba")
        val trace = EventTrace.prologue(ReferenceData.fromEnvironment())
        assumeTrue(romFile.isFile && trace != null, "ROM/reference data absent")
        val rom = romFile.readBytes()
        val memory = ScriptMemory { address ->
            val offset = address - 0x08000000
            (rom[offset].toInt() and 0xFF) or ((rom[offset + 1].toInt() and 0xFF) shl 8)
        }
        val before = requireNotNull(trace).at(BEFORE_STEP)
        val after = trace.at(BEFORE_STEP + 1)
        val beforeFlags = requireNotNull(before.permanentFlags) + requireNotNull(before.chapterFlags)
        val afterFlags = requireNotNull(after.permanentFlags) + requireNotNull(after.chapterFlags)
        assertEquals(48, beforeFlags.size)
        assertEquals(48, afterFlags.size)
        assertTrue(!beforeFlags.contentEquals(afterFlags), "trace must observe changed guide flags")
        assertEquals(0x0D, memory.u16(ASMC_COMMAND) ushr 8)
        assertEquals(0, memory.u16(ASMC_COMMAND) and 0xF)
        val function = memory.u16(ASMC_COMMAND + 4) or (memory.u16(ASMC_COMMAND + 6) shl 16)
        assertEquals(GUIDE_FUNCTION, function and 1.inv())
        val commandBytes = ((memory.u16(ASMC_COMMAND) ushr 4) and 0xF) * 2
        assertEquals(ASMC_COMMAND + commandBytes, requireNotNull(after.engine).pEventCurrent)
        val sentinelAddress = (GUIDE_TABLE until GUIDE_TABLE + 0x1000 step 12).first {
            memory.u16(it) and 0xFF == 12
        }
        return GuideSample(before, after, beforeFlags, afterFlags, memory, sentinelAddress)
    }

    private companion object {
        const val BEFORE_STEP = 15877
        const val ASMC_COMMAND = 0x089EEC0C
        const val GUIDE_FUNCTION = 0x080CF460
        const val GUIDE_TABLE = 0x08B19E0C
    }
}

/** Runtime inputs and original flag oracle for the Prologue guide callback. */
private data class GuideSample(
    val before: EventSnapshot,
    val after: EventSnapshot,
    val beforeFlags: ByteArray,
    val afterFlags: ByteArray,
    val memory: ScriptMemory,
    val sentinelAddress: Int,
)
