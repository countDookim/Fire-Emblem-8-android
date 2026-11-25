package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterTitle
import fe8.game.bm.PlaySt
import fe8.game.flags.Fe8EventFlags
import fe8.game.mapanim.ApProcs
import fe8.game.save.BmSaveLib
import fe8.game.save.Fe8SramLayout
import fe8.game.save.NewGameRam
import fe8.game.savemenu.SaveDrawProc
import fe8.game.savemenu.SaveMenuAssembly
import fe8.game.savemenu.SaveMenuNewGame
import fe8.game.savemenu.SaveMenuState
import fe8.game.text.Fe8Text
import fe8.game.worldmap.GmData
import fe8.game.worldmap.WorldMapApHost
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
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
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * PC-9 WIRE: [SaveMenuAssembly.wireNewGame] connects the real `DifficultyMenu` (+ sprites), the
 * select loop's `SaveMenu_NewGame` / `SaveMenuWriteNewGame` hooks and `WriteNewGameSave` in
 * `ProcScr_SaveMenu`. Against `ram_trace_newgame`: the real `inputs.bin` keys (`Down` f1030, `A`
 * f1060) drive the difficulty select from the `Proc_Goto` at f964 to the slot select, the choice
 * is difficulty 1 as in the capture; the select loop's own `A` at the capture's write frame
 * reaches `WriteNewGameSave` for slot 0 (SRAM equals the capture) and the `SaveMenu_NewGame` hook
 * jumps to the new game exit label. B cancel of the difficulty select is not in the capture.
 */
@Timeout(300)
class NewGameSaveMenuWireTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    fun `assembled new game path runs the difficulty select and writes slot 0 as the capture`() {
        assumeTrue(ref.available && romFile.isFile, "mGBA reference data / ROM not present")
        assumeTrue(ProcArrayDump.romNames().isNotEmpty(), "decomp nm file not present")
        val run = ref.run(RUN)
        assumeTrue(run.dumps.containsKey("r00963") && run.hasSram, "$RUN not present")
        val names = ProcArrayDump.romNames()
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val engine = gbafe.proc.ProcEngine()
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
        val sram = CartSram().also { it.copyIn(run.sram("s01128")) }
        val saveLib = BmSaveLib(sram).also { it.sramInit() }
        val keys = KeyStatus()
        val sounds = ArrayList<Int>()
        val bgm = ArrayList<List<Int>>()
        var flushes = 0
        val playSt = PlaySt()
        val ew = run.region("r01129", "ewram")
        playSt.decode(ew, GPLAYST - EWRAM)
        val flags = Fe8EventFlags.create()
        flags.copyIn(run.region("r01129", "iwram"), GFLAGS - IWRAM)
        val convoy = ew.copyOfRange(GCONVOY - EWRAM, GCONVOY - EWRAM + 200)
        val huffmanRoot = u32(romBytes, 0x0815D488 - 0x08000000) and 0x01FFFFFF
        val deps = SaveMenuAssembly.Deps(
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
            playSt = playSt,
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
            writeGameSave = { error("WriteGameSave is not part of the new game path") },
            newKeys = { keys.newKeys },
            playSoundEffect = { sounds += it },
        )
        val built = SaveMenuAssembly.assemble(deps)
        val wired = SaveMenuAssembly.wireNewGame(
            built, deps, NewGameRam(playSt, flags, convoy), keys,
            startBgmVolumeChange = { a, b, c, d -> bgm += listOf(a, b, c, d) },
            afterWrite = { flushes++ },
        )
        assertTrue(built.menu.hooks.saveMenuProcScript === wired.saveMenu.script)
        assertTrue(wired.saveMenu.hooks.saveSlotSelectLoop === built.menu.hooks.saveSlotSelectLoop)

        // ProcScr_SaveMenu from the Proc_Goto at f964 (cursor of the main loop).
        var frame = 0
        val menu = engine.start(wired.saveMenu.script, 3)
        val state = menu.state as SaveMenuState
        val start = ProcArrayDump.ofEwram(run.region("r00963", "ewram"), names)
            .find("ProcScr_SaveMenu")!!
        state.difficulty = start.u8(0x2A)
        state.jumpLabel = start.u8(0x2E)
        state.unk2f = start.u8(0x2F)
        state.unk3d = start.u8(0x3D)
        state.scrollCnt = start.u8(0x29)
        state.savedraw = engine.start(
            ProcScript("ProcScr_savedraw", listOf(block())) { SaveDrawProc() }, menu,
        )
        engine.goto(menu, SaveMenuNewGame.PL_MAIN_LOOP)
        wired.saveMenu.hooks.funcs["SameMenu_CtrlLoop"] = ProcFunc { p ->
            if (frame == 964) engine.goto(p, SaveMenuNewGame.PL_DIFFICULTY_SEL)
        }
        // The slot select is not driven here (its own screen is PC-4's); stop at its label.
        wired.saveMenu.hooks.saveSlotSelectLoop = ProcFunc { frameInSlotSelect = frame }
        while (frame < 1120 && frameInSlotSelect < 0) {
            frame++
            keys.update(run.inputs[frame - 1])
            layers.clear() // the per-frame sprite reset
            engine.run(engine.root(3))
            engine.run(engine.root(4))
        }
        assertTrue(frameInSlotSelect in 1100..1120, "slot select reached at f$frameInSlotSelect")
        val cap = ProcArrayDump.ofEwram(run.region("r01129", "ewram"), names)
            .find("ProcScr_SaveMenu")!!
        assertEquals(cap.u8(0x2A), state.difficulty, "difficulty chosen as the capture")
        assertEquals(1, state.difficulty)
        assertEquals(listOf(0x66, 0x6A), sounds, "cursor and confirm sounds as the capture")
        assertEquals(SaveMenuNewGame.PL_SAVE_SLOT_SEL, state.jumpLabel)

        // The select loop's write hook is the real SaveMenuWriteNewGame + WriteNewGameSave.
        state.susSlot = cap.u8(0x2C)
        built.selectLoop.hooks.writeNewGame(menu)
        assertEquals(1, flushes)
        val got = ByteArray(Fe8SramLayout.SRAM_SIZE).also { sram.copyOut(it) }
        val want = run.sram("s01132")
        val a = Fe8SramLayout.GAME0_OFFSET
        assertArrayEquals(
            want.copyOfRange(a, a + COMPARE), got.copyOfRange(a, a + COMPARE), "slot 0 game block",
        )
        // SaveMenu_NewGame hook: the jump to PL_SAVEMENU_NEW_GAME (capture f1174).
        built.selectLoop.hooks.newGame(menu)
        assertEquals(listOf(listOf(0xC0, 0, 0x10, 0)), bgm)
        val atLabel = ProcArrayDump.ofEwram(run.region("r01174", "ewram"), names)
            .find("ProcScr_SaveMenu")!!.scrCur
        assertEquals(atLabel, menu.scrCur, "cursor at the new game label")
    }

    private var frameInSlotSelect = -1

    private companion object {
        const val RUN = "ram_trace_newgame"
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val GPLAYST = 0x0202BCF0
        const val GCONVOY = 0x0203A81C
        const val GFLAGS = 0x03005250
        const val COMPARE = 0x400

        fun u32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    }
}
