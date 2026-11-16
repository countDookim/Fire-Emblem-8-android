package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.UnitSprites
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventState
import fe8.game.event.ScriptMemory
import fe8.game.ui.ItemIcon
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The UI restore of the end of an event (EP-11e) in `ram_trace_prologue_clear`: the boss turn event
 * ends at step 3721 (`EventEngine_OnEnd` with `EV_EXEC_UNK5`: `ReadGameSaveCoreGfx` loads the icon
 * palettes into BG palettes 4 and 5, `ChangeUnitSpritePalette`, `UnlockGame`, `ResumeMenu`,
 * `ResetBkselPalette`), compared through `gPaletteBuffer` of the per-step EWRAM dumps (f3720
 * before, r3721 after). The start of the clear event at step 4894 (`_FreezeMenu`) is also checked.
 *
 * Only `LoadIconPalettes` has an effect that differs from doing nothing there. No menu, battle
 * forecast or box dialogue proc exists in either window, the unit sprite palettes are already in
 * place before the call and the icon tables are already empty, so `FreezeMenu`, `ResumeMenu`,
 * `_FreezeMenu`, `ResetBkselPalette`, `sub_808BB74`, `ChangeUnitSpritePalette` and
 * `ResetIconGraphics_` stay transcribed; the tests below pin those preconditions.
 */
class EventEndUiRestoreDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("LoadIconPalettes")
    fun `the icon palettes of f3721 are the item icon palettes in BG palettes 4 and 5`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val run = ref.run(RUN)
        val rom = RomView.ofRom(romFile.readBytes())
        val hardware = hardwareAt(run.region(dumpName(BEFORE), "ewram"))
        val want = run.region(dumpName(AFTER), "ewram")

        val before = bank(hardware, ICON_BANK) + bank(hardware, ICON_BANK + 1)
        ItemIcon.loadIconPalettes(hardware, rom, ICON_BANK)

        assertNotEquals(before, bank(hardware, ICON_BANK) + bank(hardware, ICON_BANK + 1), "seed")
        for (b in ICON_BANK..ICON_BANK + 1) {
            assertEquals(captured(want, b), bank(hardware, b), "bank $b at f$AFTER")
        }
    }

    @Test
    fun `the end of the boss turn event leaves the palette buffer as captured at f3721`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val run = ref.run(RUN)
        val rom = RomView.ofRom(romFile.readBytes())
        val hardware = hardwareAt(run.region(dumpName(BEFORE), "ewram"))
        val want = run.region(dumpName(AFTER), "ewram")
        val sprites = UnitSprites(rom, hardware, BmSt())

        val engineProc = run.watch("procs")
        val override = engineProc.u16(AFTER, EVENT_SLOT * PROC_SIZE + MAP_SPRITE_PAL_OVERRIDE)
        assertEquals(5, engineProc.u8(AFTER, EVENT_SLOT * PROC_SIZE + EXEC_TYPE), "EV_EXEC_UNK5")
        val calls = ArrayList<String>()
        val hooks = object : EventEngineHooks {
            // `ReadGameSaveCoreGfx` (icon palettes; the rest is EP-11f), `ChangeUnitSpritePalette`.
            override fun restoreAfterEvent5(mapSpritePalIdOverride: Int) {
                calls += "restore"
                ItemIcon.loadIconPalettes(hardware, rom, ICON_BANK)
                sprites.changeUnitSpritePalette(mapSpritePalIdOverride)
            }
        }
        val procs = ProcEngine().also { it.init() }
        val engine = EventEngine(procs, EventState(), ScriptMemory { 0 }, hooks)
        val proc = engine.create(0, 5)
        proc.mapSpritePalIdOverride = override
        engine.onEnd(proc)

        assertEquals(listOf("restore"), calls)
        // Bank 0 holds the UI frame palette (`LoadUiFrameGraphics`, EP-11f).
        for (b in 1 until BANKS) {
            assertEquals(captured(want, b), bank(hardware, b), "bank $b at f$AFTER")
        }
    }

    @Test
    fun `no menu, forecast or box dialogue proc exists and the icon tables are empty`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val run = ref.run(RUN)
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp symbol table not present")
        val procs = run.watch("procs")
        val absent = listOf(
            "sProc_Menu", "sProc_MenuMain", "gProcScr_BKSEL", "gProcScr_BoxDialogue",
            "ProcScr_TalkBoxIdle", "ProcScr_MergeBoxDialogue", "ProcScr_BoxDialogueDrawTextExt",
        )
        for (step in (3706..3722) + (4894..4912)) {
            val dump = ProcArrayDump.ofWatch(procs, step, names)
            for (name in absent) assertNull(dump.find(name), "$name at step $step")
        }
        assertNull(ProcArrayDump.ofWatch(procs, 3722, names).find("ProcScr_StdEventEngine"))
        assertTrue(
            ProcArrayDump.ofWatch(procs, 4894, names).find("ProcScr_StdEventEngine") != null,
            "the clear event starts at step 4894",
        )
        for (dump in listOf(dumpName(BEFORE), dumpName(AFTER))) {
            val ewram = run.region(dump, "ewram")
            val tables = (ICON_LOOKUP until ICON_LOOKUP + ICON_LOOKUP_SIZE) +
                (ICON_GFX_ID until ICON_GFX_ID + 0x20)
            assertTrue(tables.all { ewram[it] == 0.toByte() }, "icon tables empty in $dump")
        }
    }

    private fun dumpName(step: Int): String =
        ref.run(RUN).dumps.entries.first { it.value == step }.key

    private fun hardwareAt(ewram: ByteArray): BgHardware = BgHardware().also {
        for (i in 0 until BANKS * 16) it.palette[i] = u16(ewram, PALETTE + 2 * i).toShort()
    }

    private fun bank(hardware: BgHardware, b: Int): List<Int> =
        (0 until 16).map { hardware.palette[b * 16 + it].toInt() and 0xFFFF }

    private fun captured(ewram: ByteArray, b: Int): List<Int> =
        (0 until 16).map { u16(ewram, PALETTE + 2 * (b * 16 + it)) }

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private companion object {
        const val RUN = "ram_trace_prologue_clear"
        const val BEFORE = 3720
        const val AFTER = 3721
        const val BANKS = 32
        const val ICON_BANK = 4

        /** `gPaletteBuffer` as an offset into the EWRAM dump (0x020228A8). */
        const val PALETTE = 0x228A8

        /** `DrawnIconLookupTable` (0x02026A90, 0x380 bytes) and `IconGFXIDLookupTable`. */
        const val ICON_LOOKUP = 0x26A90
        const val ICON_LOOKUP_SIZE = 0x380
        const val ICON_GFX_ID = 0x26E10

        /** The event engine proc's slot in the `procs` watch at step 3721 and its fields. */
        const val EVENT_SLOT = 13
        const val PROC_SIZE = 0x6C
        const val MAP_SPRITE_PAL_OVERRIDE = 0x2C
        const val EXEC_TYPE = 0x41
    }
}
