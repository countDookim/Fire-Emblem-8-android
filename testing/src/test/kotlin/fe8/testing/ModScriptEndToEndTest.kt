package fe8.testing

import fe8.game.bm.PlaySt
import fe8.game.event.EventConst.EV_EXEC_GAMEPLAY
import fe8.game.event.EventEngine
import fe8.game.event.EventScrCore
import fe8.game.event.EventState
import fe8.game.event.Fe8ScriptTools
import fe8.game.event.NmSymbolIndex
import fe8.game.event.ScriptMemory
import fe8.game.event.ScriptWords
import fe8.game.flags.Fe8EventFlags
import gba.testkit.AllocationMeter
import gbafe.core.flags.EventFlags
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * A `.fe8s` mod script loaded from a file at runtime, assembled into the synthetic address range,
 * run on the event engine and calling a vanilla ROM script (`EventScr_089FFF08`: `set_slot 2 7`,
 * `return`), then continuing after the call. Test-only; not a port check.
 */
class ModScriptEndToEndTest {
    private val assets =
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
    private val romFile = File(assets, "baserom.gba")
    private val nmFile = ProcArrayDump.nmFile()

    private val modText = """
        event ModMain
            sleep 3
            call EventScr_089FFF08
            set_flag 101
            return

        event ModLoop
            label 0
            call EventScr_089FFF08
            sleep 1
            goto 0
    """.trimIndent() + "\n"

    private class Rig(
        val procs: ProcEngine,
        val engine: EventEngine,
        val state: EventState,
        val flags: EventFlags,
        val main: Int,
        val loop: Int,
    )

    private fun rig(): Rig {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        assumeTrue(nmFile != null, "decomp/fireemblem8.nm.txt not present")
        val rom = romFile.readBytes()
        val file = Files.createTempFile("mod", ".fe8s").toFile()
        try {
            file.writeText(modText)
            val tools = Fe8ScriptTools(NmSymbolIndex(nmFile!!.readText()))
            val parsed = tools.parser.parse(file.name, file.readText())
            assertTrue(parsed.ok, parsed.errors.joinToString("\n"))
            val image = tools.assembler.assemble(parsed.lines)
            val main = image.find("ModMain")!!
            val loop = image.find("ModLoop")!!
            assertEquals(ScriptWords.MOD_BASE, main.baseAddress)
            val words = ShortArray(image.words.size) { image.words[it].toShort() }
            val mem = ScriptWords(
                ScriptMemory { a ->
                    val o = a - 0x08000000
                    (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8)
                },
                words,
            )
            val procs = ProcEngine().also { it.init() }
            val state = EventState()
            val flags = Fe8EventFlags.create()
            val engine = EventEngine(procs, state, mem)
            EventScrCore(
                state, mem, rng = RngService(), playSt = PlaySt(), keys = KeyStatus(),
                flags = flags,
            ).registerAll(engine)
            return Rig(procs, engine, state, flags, main.baseAddress, loop.baseAddress)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `mod script calls a vanilla script, returns and ends`() {
        val r = rig()
        val ev = r.engine.create(r.main, EV_EXEC_GAMEPLAY)
        var frames = 0
        var sawCallee = false
        while (r.engine.findStd() != null && frames < 100) {
            r.procs.run(ev.proc)
            frames++
            // The vanilla callee's only effect: slot 2 = 7 (set before the flag, after the sleep).
            if (r.state.slots[2] == 7) sawCallee = true
            if (!sawCallee) assertFalse(r.flags.checkFlag(101))
        }
        assertNull(r.engine.findStd(), "engine did not end")
        assertTrue(sawCallee, "vanilla callee did not run")
        assertEquals(7, r.state.slots[2])
        assertTrue(r.flags.checkFlag(101), "control did not return after the call")
        assertTrue(frames in 4..10, "frames=$frames")
    }

    @Test
    fun `running a mod script that calls vanilla allocates nothing per frame`() {
        assumeTrue(AllocationMeter.supported)
        val r = rig()
        val ev = r.engine.create(r.loop, EV_EXEC_GAMEPLAY)
        repeat(10) { r.procs.run(ev.proc) }
        assertNotNull(r.engine.findStd())
        val bytes = AllocationMeter.measureMin(warmup = 2000, iterations = 1000) {
            r.state.slots[2] = 0
            r.procs.run(ev.proc)
        }
        assertEquals(0L, bytes)
        assertEquals(7, r.state.slots[2])
        assertNotNull(r.engine.findStd())
    }
}
