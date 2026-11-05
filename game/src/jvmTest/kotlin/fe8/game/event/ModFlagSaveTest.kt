package fe8.game.event

import fe8.game.bm.PlaySt
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.flags.Fe8EventFlags
import gbafe.core.flags.EventFlags
import gbafe.core.flags.FlagRegistry
import gbafe.core.flags.RoutedFlagStore
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.save.ModSaveSections
import gbafe.save.SaveContainer
import gbafe.script.ModFlagSymbols
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A mod flag set by a `.fe8s` script survives a container save/load; vanilla bytes unchanged. */
class ModFlagSaveTest {
    private val vanillaNames = mapOf("EVFLAG_8" to 8)

    private fun registry(flags: EventFlags) = FlagRegistry(flags, vanillaNames).also {
        it.register("MyMod", "MyMod_Seen")
        it.register("MyMod", "MyMod_Unused")
    }

    private fun bytesOf(f: EventFlags) = ByteArray(f.size).also { f.copyOut(it) }

    @Test
    fun `mod flag set by a script survives a container save and load`() {
        val flags = Fe8EventFlags.create()
        val registry = registry(flags)
        val tools = Fe8ScriptTools(NmSymbolIndex(""), mods = ModFlagSymbols(registry))
        val text = "event Test\n    set_flag 101\n    set_flag MyMod_Seen\n"
        val image = tools.assembler.assemble(tools.parser.parseOrThrow("t.fe8s", text))
        val entry = image.find("Test")!!
        val mem = ScriptMemory { a ->
            val i = image.wordIndexOfAddress(a)
            if (i >= 0) image.words[i] and 0xFFFF else 0
        }
        // A negative u16 flag argument (every mod handle) means "flag id in slot 2" in the vanilla
        // VM, so the handle reaches the flag store through slot 2, as vanilla indirection does.
        val state = EventState()
        state.slots[2] = registry.resolve("MyMod_Seen")
        val core = EventScrCore(
            state, mem, object : EventInput {}, RoutedFlagStore(registry),
            RngService(), PlaySt(), KeyStatus(), object : EventScrHooks {},
        )
        val proc = EventEngineProc()
        proc.pEventStart = entry.baseAddress
        proc.pEventIdk = entry.baseAddress
        proc.pEventCurrent = entry.baseAddress
        assertEquals(EVC_ADVANCE_CONTINUE, core.event02EvBitAndIdMod.run(proc))
        proc.pEventCurrent += 4
        assertEquals(EVC_ADVANCE_CONTINUE, core.event02EvBitAndIdMod.run(proc))

        assertTrue(registry.check(registry.resolve("MyMod_Seen")))
        assertTrue(flags.checkFlag(101))
        val vanillaBytes = bytesOf(flags)
        val plain = Fe8EventFlags.create().also { it.setFlag(101) }
        assertArrayEquals(bytesOf(plain), vanillaBytes)

        val sram = ByteArray(gbafe.save.SaveFormat.CORE_SIZE)
        val saved = ModSaveSections
            .write(SaveContainer.fromSram("fe8", sram), "MyMod", registry, null)
            .toByteArray()

        val flags2 = Fe8EventFlags.create()
        flags2.copyIn(vanillaBytes)
        val registry2 = registry(flags2)
        ModSaveSections.read(SaveContainer.read(saved), "MyMod", registry2, null)
        assertTrue(registry2.check(registry2.resolve("MyMod_Seen")))
        assertFalse(registry2.check(registry2.resolve("MyMod_Unused")))
        assertArrayEquals(vanillaBytes, bytesOf(flags2))
    }
}
