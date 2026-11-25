package fe8.testing

import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.flags.Fe8EventFlags
import fe8.game.save.BmSaveLib
import fe8.game.save.NewGameRam
import fe8.game.savemenu.SaveMenuNewGame
import fe8.game.savemenu.SaveMenuPostChapter
import fe8.game.savemenu.SaveMenuState
import fe8.game.worldmap.GmData
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.LcdControl
import gbafe.render.PaletteFade
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `PC-9d` against `ram_trace_newgame`: `ProcScr_SaveMenu` from the `Proc_Goto` into the difficulty
 * select (f964) through the return to the slot select (f1116), with the difficulty select child
 * replaced by a stand-in that sets the choice at f1060 and ends at f1088 as in the capture;
 * `SaveMenuWriteNewGame` on the pre-write state at f1129; `SaveMenu_NewGame` at f1173.
 */
class NewGameSaveMenuDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    private class Rig(val run: ReferenceData.Run, val sram: CartSram = CartSram()) {
        val engine = ProcEngine()
        val hardware = BgHardware()
        val playSt = PlaySt()
        val fade = ArchivedPaletteFade(PaletteFade(hardware, engine), hardware, engine)
        val lib = BmSaveLib(sram)
        val flags = Fe8EventFlags.create()
        val convoy = ByteArray(200)
        val ram = NewGameRam(playSt, flags, convoy)
        val bridge = SaveMenuNewGame(engine, hardware, lib, ram, fade)
        val postChapter = SaveMenuPostChapter(
            engine,
            hardware,
            playSt,
            BmSt(),
            GmData(ByteArray(0x8000), RngService()),
            lib,
            fade,
        ).also { it.hooks.saveMenuProcScript = bridge.script }
        var frame = 0
        val bgm = ArrayList<List<Int>>()

        init {
            bridge.hooks.startBgmVolumeChange = { a, b, c, d -> bgm += listOf(a, b, c, d) }
        }

        fun ewram(step: Int) = run.region("r%05d".format(step), "ewram")
        fun iwram(step: Int) = run.region("r%05d".format(step), "iwram")
    }

    private fun rig(): Rig {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run(RUN)
        assumeTrue(run.dumps.containsKey("r00963"), "$RUN not present")
        assumeTrue(ProcArrayDump.romNames().isNotEmpty(), "decomp nm file not present")
        return Rig(run)
    }

    private fun capSaveMenu(r: Rig, step: Int, names: Map<Int, String>) =
        ProcArrayDump.ofEwram(r.ewram(step), names).find("ProcScr_SaveMenu")

    @Test
    @VerifiesPort(
        "SaveMenu_ResetLcdFormDifficulty",
        "SaveMenu_PostDifficultHandler",
        "SaveMenu_SetDifficultyChoice",
    )
    fun `save menu script runs the difficulty select and returns to the slot select`() {
        val r = rig()
        val names = ProcArrayDump.romNames()
        r.hardware.lcd.readFrom(r.iwram(963), GLCD_CONTROL - IWRAM)
        val menu = r.engine.start(r.bridge.script, 3)
        r.engine.goto(menu, SaveMenuNewGame.PL_MAIN_LOOP)
        r.bridge.hooks.funcs["SameMenu_CtrlLoop"] = ProcFunc { p ->
            if (r.frame == 964) r.engine.goto(p, SaveMenuNewGame.PL_DIFFICULTY_SEL)
        }
        val child = ProcScript(
            "NewGameDifficultySelect stand-in",
            listOf(
                repeat(ProcFunc { p ->
                    if (r.frame == 1060) r.postChapter.saveMenuSetDifficultyChoice(1, 0)
                    if (r.frame >= STAND_IN_END) r.engine.breakProc(p)
                }),
                end(),
            ),
        )
        r.bridge.hooks.newNewGameDifficultySelect = ProcFunc { r.engine.startBlocking(child, it) }

        val state = menu.state as SaveMenuState
        val start = capSaveMenu(r, 963, names)!!
        state.difficulty = start.u8(0x2A)
        state.jumpLabel = start.u8(0x2E)
        state.unk2f = start.u8(0x2F)
        state.unk3d = start.u8(0x3D)
        state.scrollCnt = start.u8(0x29)
        // The capture's SaveMenu_ReloadScreenFormDifficulty (decompression of the whole menu
        // graphics) spans f1089..f1097 (cursor already past it, jump_label not yet written, as the
        // call lags over several video frames); the port runs it in one frame, so port frame n
        // from f1089 on corresponds to capture frame n + LAG.
        for (step in 964..1105) {
            r.frame = step
            r.engine.run(r.engine.root(3))
            r.engine.run(r.engine.root(4))
            val capStep = if (step >= 1089) step + RELOAD_LAG else step
            if (step == 1089) {
                for (lagged in 1089..1097) {
                    val c = capSaveMenu(r, lagged, names)!!
                    assertEquals(24, c.scrCur, "cursor during the reload call at f$lagged")
                    assertEquals(SaveMenuNewGame.PL_MAIN_LOOP, c.u8(0x2E), "jump_label at f$lagged")
                }
            }
            if (!r.run.dumps.containsKey("r%05d".format(capStep))) continue // f1020, f1100
            val cap = capSaveMenu(r, capStep, names)!!
            assertEquals(cap.scrCur, menu.scrCur, "script cursor at f$capStep")
            assertEquals(cap.u8(0x2A), state.difficulty, "difficulty at f$capStep")
            assertEquals(cap.u8(0x3D), state.unk3d, "unk_3d at f$capStep")
            assertEquals(cap.u8(0x2E), state.jumpLabel, "jump_label at f$capStep")
            assertEquals(cap.u8(0x2F), state.unk2f, "unk_2f at f$capStep")
            assertEquals(cap.u8(0x29), state.scrollCnt, "scroll_cnt at f$capStep")
            if (step == 965 || step == 1089) {
                // SaveMenu_ResetLcdFormDifficulty: window enables off, WIN0 on all layers.
                val want = LcdControl().also { it.readFrom(r.iwram(capStep), GLCD_CONTROL - IWRAM) }
                assertEquals(
                    want.dispcnt and WIN_ENABLE_MASK,
                    r.hardware.lcd.dispcnt and WIN_ENABLE_MASK,
                    "window enables at f$capStep",
                )
                assertEquals(want.wincnt, r.hardware.lcd.wincnt, "wincnt at f$capStep")
            }
        }
        // The slot select is entered (cursor 31, jump_label 5) one frame after f1115 in the
        // capture; the port's fade-in ends at f1115 (after the 9-frame reload lag), one early.
        assertEquals(26, capSaveMenu(r, 1115, names)!!.scrCur)
        assertEquals(31, capSaveMenu(r, 1116, names)!!.scrCur)
        r.frame = 1106
        r.engine.run(r.engine.root(3))
        assertEquals(31, menu.scrCur, "slot select cursor after the fade-in")
        assertEquals(SaveMenuNewGame.PL_SAVE_SLOT_SEL, state.jumpLabel)
    }

    @Test
    @VerifiesPort("SaveMenuWriteNewGame")
    fun `write new game of difficulty 1 writes slot 0 as the reference`() {
        val r = rig()
        val names = ProcArrayDump.romNames()
        r.sram.copyIn(r.run.sram("s01128"))
        r.lib.sramInit()
        val before = r.ewram(1129)
        r.playSt.decode(before, GPLAYST - EWRAM)
        r.flags.copyIn(r.run.region("r01129", "iwram"), GFLAGS - IWRAM)
        before.copyInto(r.convoy, 0, GCONVOY - EWRAM, GCONVOY - EWRAM + 200)

        val menu = r.engine.start(r.bridge.script, 3)
        val state = menu.state as SaveMenuState
        val cap = capSaveMenu(r, 1129, names)!!
        state.difficulty = cap.u8(0x2A)
        state.susSlot = cap.u8(0x2C)
        assertEquals(1, state.difficulty)
        r.bridge.saveMenuWriteNewGame(menu)

        // The same write through the reference-verified WriteNewGameSave(0, 0, 1, 1).
        val direct = Rig(r.run)
        direct.sram.copyIn(r.run.sram("s01128"))
        direct.lib.sramInit()
        direct.playSt.decode(before, GPLAYST - EWRAM)
        direct.flags.copyIn(r.run.region("r01129", "iwram"), GFLAGS - IWRAM)
        before.copyInto(direct.convoy, 0, GCONVOY - EWRAM, GCONVOY - EWRAM + 200)
        direct.lib.writeNewGameSave(direct.ram, 0, false, 1, 1)
        assertArrayEquals(
            ByteArray(SRAM_SIZE).also { direct.sram.copyOut(it) },
            ByteArray(SRAM_SIZE).also { r.sram.copyOut(it) },
            "sram image",
        )
        // And the capture: gPlaySt after the write (f1132) and the slot's block bytes.
        val after = r.ewram(1132)
        val gotPlaySt = ByteArray(PlaySt.SIZE).also { r.playSt.encode(it, 0) }
        assertArrayEquals(
            after.copyOfRange(GPLAYST - EWRAM, GPLAYST - EWRAM + PlaySt.SIZE), gotPlaySt, "gPlaySt",
        )
        val want = r.run.sram("s01132")
        val got = ByteArray(SRAM_SIZE).also { r.sram.copyOut(it) }
        assertArrayEquals(
            want.copyOfRange(GAME0_START, GAME0_START + GAME0_COMPARE),
            got.copyOfRange(GAME0_START, GAME0_START + GAME0_COMPARE),
            "slot 0 first bytes of the game block",
        )
    }

    @Test
    @VerifiesPort("SaveMenu_NewGame")
    fun `new game jumps to the new game exit as the reference at f1174`() {
        val r = rig()
        val names = ProcArrayDump.romNames()
        val menu = r.engine.start(r.bridge.script, 3)
        r.engine.goto(menu, SaveMenuNewGame.PL_SLOT_SELECTED)
        r.bridge.saveMenuNewGame(menu)
        assertEquals(listOf(listOf(0xC0, 0, 0x10, 0)), r.bgm)
        // The capture's cursor 82 is the command after label PL_SAVEMENU_NEW_GAME at f1174.
        val atLabel = capSaveMenu(r, 1174, names)!!.scrCur
        assertEquals(atLabel, menu.scrCur, "cursor at the new game label")
    }

    private companion object {
        const val RUN = "ram_trace_newgame"
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val GPLAYST = 0x0202BCF0
        const val GCONVOY = 0x0203A81C
        const val GFLAGS = 0x03005250
        const val GLCD_CONTROL = 0x03003080
        const val RELOAD_LAG = 9

        // The stand-in ends one frame before the real child (f1088) as the parent resumes a frame
        // after the child breaks (the real DifficultyMenu proc ends in its own post-fade step).
        const val STAND_IN_END = 1087
        const val WIN_ENABLE_MASK = 0xE000
        const val SRAM_SIZE = 0x8000
        val GAME0_START = fe8.game.save.Fe8SramLayout.GAME0_OFFSET
        const val GAME0_COMPARE = 0x400
    }
}
