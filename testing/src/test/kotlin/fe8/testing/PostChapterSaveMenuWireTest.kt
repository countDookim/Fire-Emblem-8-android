package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterTitle
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.flags.Fe8EventFlags
import fe8.game.save.BmGameSave
import fe8.game.save.Fe8SramLayout as L
import fe8.game.save.GameSaveRam
import gbafe.core.time.GameClock
import fe8.game.mapanim.ApProcs
import fe8.game.save.BmSaveLib
import fe8.game.savemenu.PostSaveMenu
import fe8.game.savemenu.SaveDrawSlotsParent
import fe8.game.savemenu.SaveMenuAssembly
import fe8.game.savemenu.SaveMenuState
import fe8.game.text.Fe8Text
import fe8.game.worldmap.GmData
import fe8.game.worldmap.WorldMapApHost
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.label
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.BgVerticalScroll
import gbafe.render.OamBuffer
import gbafe.render.PaletteFade
import gbafe.render.SpriteAnimPlayer
import gbafe.render.SpriteLayers
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextHost
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * PC-4 WIRE: the production assembly [SaveMenuAssembly.assemble] (the one `Fe8Boot` uses) applies
 * `wireScreen`, `wireSlotFlip` and `wireSelectLoop`, so the second `A` press of `PC-cap1`
 * (`ram_trace_prologue_end`, f9799) reaches the slot-0 `WriteGameSave` through the menu's own
 * [fe8.game.savemenu.SaveMenuPostChapter.Hooks], not through test-set hooks. `WriteGameSave`
 * itself is a recorder here (its SRAM bytes are checked by the BmGameSave tests).
 */
class PostChapterSaveMenuWireTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    fun `assembled menu wires slot flip, select loop and save draw and reaches the slot 0 write`() {
        assumeTrue(ref.available && romFile.isFile, "mGBA reference data / ROM not present")
        assumeTrue(ProcArrayDump.romNames().isNotEmpty(), "decomp nm file not present")
        val run = ref.run(RUN)
        assumeTrue(run.hasWatch("procs") && run.hasSram, "$RUN lacks procs watch or SRAM")
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val engine = ProcEngine()
        val hw = BgHardware()
        val oam = OamBuffer()
        val layers = SpriteLayers()
        val rng = RngService()
        val host = object : TextHost {
            override fun language(): Int = 1
            override fun applyTextPalette(talk: Boolean, palid: Int) = Unit
            override fun readVram32(addr: Int): Int = 0
            override fun writeVram32(addr: Int, value: Int) = Unit
            override fun clearVram(dest: Int, byteCount: Int) = Unit
        }
        val sram = CartSram().also { it.copyIn(run.sram("s09799")) }
        val saveLib = BmSaveLib(sram).also {
            it.sramInit()
        }
        // The real WriteGameSave of Fe8Boot, on the RAM of PC-cap1 r09798 (before the second A).
        val ew = run.region("r09798", "ewram")
        val iw = run.region("r09798", "iwram")
        val playSt = PlaySt().also { it.decode(ew, GPLAYST - EWRAM) }
        val units = UnitTable(rom)
        ew.copyInto(units.bytes, 0, UNITS - EWRAM, UNITS - EWRAM + UnitTable.TOTAL_SIZE)
        val flags = Fe8EventFlags.create()
        flags.copyIn(iw.copyOfRange(GFLAGS - IWRAM, GFLAGS - IWRAM + flags.size))
        // The write stamps time_saved from the clock; the capture's value is in s09800's playSt.
        val clock = GameClock().also { it.set(10669L) }
        val convoy = ew.copyOfRange(GCONVOY - EWRAM, GCONVOY - EWRAM + 200)
        val ram = GameSaveRam(playSt, BmSt(), units, flags, clock, convoy)
        ew.copyInto(saveLib.pidStatsData, 0, GPIDSTATS - EWRAM, GPIDSTATS - EWRAM + saveLib.pidStatsData.size)
        ew.copyInto(
            saveLib.chapterStatsData, 0, GCHAPSTATS - EWRAM,
            GCHAPSTATS - EWRAM + saveLib.chapterStatsData.size,
        )
        saveLib.setBonusContentClaimFlags(u32(ew, GBONUS - EWRAM))
        val loader = UnitLoader(units, rom, { 0 }, rng, { false })
        val gameSave = BmGameSave(saveLib, loader, rom)
        val written = ArrayList<Int>()
        val sounds = ArrayList<Int>()
        var keys = 0
        val huffmanRoot = u32(romBytes, 0x0815D488 - 0x08000000) and 0x01FFFFFF
        val built = SaveMenuAssembly.assemble(
            SaveMenuAssembly.Deps(
                engine = engine,
                hardware = hw,
                rom = rom,
                oam = oam,
                layers = layers,
                scroll = BgVerticalScroll(),
                apProcs = ApProcs(
                    engine,
                    SpriteAnimPlayer({ rom.u16(it) }, layers, WorldMapApHost(rom, hw, oam)),
                ),
                playSt = PlaySt(),
                bmSt = BmSt(),
                gmData = GmData(ByteArray(0x8000), rng),
                saveLib = saveLib,
                archivedFade = ArchivedPaletteFade(PaletteFade(hw, engine), hw, engine),
                chapterTitle = ChapterTitle(
                    rom, hw, ChapterData(rom),
                    getPlayChapterId = { error("not run") },
                    getNextUnclearedNode = { error("not run") },
                ),
                text = TextSystem(romBytes, host, Fe8Text.CONFIG),
                messages = MsgStrings(
                    romBytes,
                    MsgHuffman(romBytes, 0x0815A72C - 0x08000000, huffmanRoot),
                    0x0815D48C - 0x08000000,
                ),
                lcg = rng.lcg,
                loadObjUiGfx = {},
                clock = { 0 },
                setNextGameActionId = {},
                writeGameSave = { slot ->
                    written += slot
                    gameSave.writeGameSave(ram, slot)
                },
                newKeys = { keys },
                playSoundEffect = { sounds += it },
            ),
        )

        // wireScreen -> wireSaveDraw -> wireLoopMain; wireSlotFlip (fade-out hooks).
        assertNotNull(built.lifecycle.hooks.loopMain, "SaveDraw_Loop_Main wired")
        assertTrue(!built.menu.hooks.fadeOutExists(), "no fade yet")
        built.menu.hooks.newFadeOut(2, null)
        assertTrue(built.menu.hooks.fadeOutExists(), "wireSlotFlip: NewFadeOut/FadeOutExists live")
        // SaveMenuState implements SaveDrawSlotsParent (PC-4c1).
        val parentView: SaveDrawSlotsParent = SaveMenuState()
        assertEquals(0, parentView.cursorConfig)

        // wireSelectLoop: the menu's own hook; second A press of PC-cap1 f9799 writes slot 0.
        val dump = ProcArrayDump.ofWatch(run.watch("procs"), 9798, ProcArrayDump.romNames())
            .find("gProcScr_SaveMenuPostChapter")!!
        val parent = engine.start(ProcScript("parent", listOf(block())), 3)
        val proc = engine.start(
            ProcScript(
                "child",
                listOf(block(), label(5), block(), label(6), block(), label(17), block()),
                newState = { SaveMenuState() },
            ),
            parent,
        )!!
        val s = proc.state as SaveMenuState
        s.susSlot = dump.u8(0x2C)
        s.cursorSlot = dump.u8(0x2D)
        s.cursorConfig = dump.u8(0x36)
        s.hbEn = dump.u8(0x3E) != 0
        s.susSlotCur = dump.u8(0x3F)
        s.ctrlTimer = dump.u8(0x40)
        s.mainSelBitfile = dump.u16(0x42)
        s.scrollCnt = 7
        assertEquals(1, s.cursorConfig)
        keys = 1
        built.menu.hooks.saveSlotSelectLoop.invoke(proc)
        assertEquals(listOf(0), written)
        assertEquals(listOf(0x60), sounds)
        assertEquals(0, s.cursorConfig)
        assertEquals(PostSaveMenu.MAIN_MENU_OPTION_INVALID, s.mainSelBitfile)

        // The real write's SRAM against the capture (s09802: the write is complete).
        // Known, reported differences (not forced):
        // - 0x778..0x77F: monster RN state (gmMonsterRnState stand-in, zero here), 0x84B:
        //   uninitialised byte of WriteSupplyItems, 0xD8C..0xDAF: wmStuff (stand-in, zero here);
        //   so the block checksum32 and the header checksum (0x60) differ too.
        // - Block infos 3 and 4 (invalidated suspend save): the port zeroes bytes of the info
        //   that the capture keeps (e.g. 0x94: 00 vs 11).
        val out = ByteArray(L.SRAM_SIZE).also { sram.copyOut(it) }
        val want = run.sram("s09802")
        val dest = L.GAME0_OFFSET
        val masked = (0x778..0x77F) + 0x84B + (0xD8C until 0xDB0)
        val bad = (0 until L.GAME_SIZE).filter { it !in masked && out[dest + it] != want[dest + it] }
        assertTrue(bad.isEmpty(), "game block diff at " + bad.joinToString { "0x%X".format(it) })
        val info = L.blockInfoOffset(0)
        val badInfo = (0 until L.INFO_CHECKSUM32).filter { out[info + it] != want[info + it] }
        assertTrue(badInfo.isEmpty(), "block info 0 diff at $badInfo")
        // charKnownFlags of the global info (header 0x40..0x5F): an empty blue slot marks nothing.
        val known = (L.HEADER_CHAR_KNOWN_FLAGS until L.HEADER_CHECKSUM)
            .filter { out[it] != want[it] }
        assertTrue(known.isEmpty(), "charKnownFlags diff at " + known.joinToString { "0x%X".format(it) })
    }

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val GPLAYST = 0x0202BCF0
        const val UNITS = 0x0202BE4C
        const val GCONVOY = 0x0203A81C
        const val GPIDSTATS = 0x0203E894
        const val GCHAPSTATS = 0x0203ECF4
        const val GBONUS = 0x0203EDB4
        const val GFLAGS = 0x03005250

        fun u32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    }
}
