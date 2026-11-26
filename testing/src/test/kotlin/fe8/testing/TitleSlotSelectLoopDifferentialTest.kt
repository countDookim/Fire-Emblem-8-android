package fe8.testing

import fe8.game.RomView
import fe8.game.bm.PlaySt
import fe8.game.flags.Fe8EventFlags
import fe8.game.save.BmSaveLib
import fe8.game.save.NewGameRam
import fe8.game.savemenu.SaveMenuNewGame
import fe8.game.savemenu.SaveMenuSelectLoop
import fe8.game.savemenu.SaveMenuState
import fe8.game.text.Fe8Text
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.PaletteFade
import gbafe.text.Font
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextHost
import gbafe.text.Text
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * `PC-11j` against `ram_trace_title_continue` (`f1050..1193`) and `ram_trace_title_resume`
 * (`f1018..1063`): `SaveMenu_SaveSlotSelectLoop` running as the repeat command of
 * `ProcScr_SaveMenu`, driven by the capture's key log. Each frame the `SaveMenuProc` fields of
 * the `procs` watch (`0x29..0x47`) and the script position are compared, plus the
 * `SaveMenu_NewGame` entry / exit dumps.
 *
 * Restart: `A` (`f1094`) opens the confirm sub-box (`ExecSaveMenuMiscOption`,
 * `cursor_config` 2, `SaveMenuHandleHelpBox` sets `hb_en`), `LEFT` (`f1155`) moves it to 1, `A`
 * (`f1176`) confirms (`unk_44` 0xF0, `SaveMenu_NewGame`, `SaveMenuDrawSubSelBox` clear,
 * `SaveMenuHandleHelpBox` close). Resume: `A` (`f1063`) calls `SaveMenu_NewGame` directly. The
 * `unk_44` countdown after `f1176` belongs to `sub_80AACBC` (`PC-11h`), so the capture is
 * compared at `+0x10` on the frame of the confirm.
 */
class TitleSlotSelectLoopDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    private inner class Rig(val run: ReferenceData.Run, first: Int) {
        val names = ProcArrayDump.romNames()
        val romBytes = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        ).readBytes()
        val engine = ProcEngine()
        val keys = KeyStatus()
        val hardware = BgHardware()
        private val fade = ArchivedPaletteFade(PaletteFade(hardware, engine), hardware, engine)
        val lib = BmSaveLib(CartSram()).also { it.sramInit() }
        val bridge = SaveMenuNewGame(
            engine, hardware, lib,
            NewGameRam(PlaySt(), Fe8EventFlags.create(), ByteArray(200)), fade,
        )
        private val host = object : TextHost {
            override fun language(): Int = 1

            override fun applyTextPalette(talk: Boolean, palid: Int) {
                hardware.enablePaletteSync()
            }

            override fun readVram32(addr: Int): Int {
                var v = 0
                for (k in 0..3) {
                    v = v or ((hardware.vram[addr - VRAM_BASE + k].toInt() and 0xFF) shl (8 * k))
                }
                return v
            }

            override fun writeVram32(addr: Int, value: Int) {
                for (k in 0..3) hardware.vram[addr - VRAM_BASE + k] = (value shr (8 * k)).toByte()
            }

            override fun clearVram(dest: Int, byteCount: Int) {
                hardware.vram.fill(0, dest - VRAM_BASE, dest - VRAM_BASE + byteCount)
            }
        }
        private val textSystem = TextSystem(romBytes, host, Fe8Text.CONFIG)
        private val font = Font()
        private val subText = Text()
        private val messages = MsgStrings(
            romBytes,
            MsgHuffman(
                romBytes,
                0x0815A72C - 0x08000000,
                u32(romBytes, 0x0815D488 - 0x08000000) and 0x01FFFFFF,
            ),
            0x0815D48C - 0x08000000,
        )
        val loop = SaveMenuSelectLoop(
            engine, hardware, textSystem, messages, font, subText,
            bitfileToIndex = { bits -> (0 until 8).firstOrNull { (bits shr it) and 1 != 0 } ?: -1 },
        )
        val watch = run.watch("procs")
        val proc: Proc = engine.start(bridge.script, 3)
        val state = proc.state as SaveMenuState
        val inputs = run.inputs
        val events = ArrayList<String>()
        var newGameEntryScr = -1
        var newGameExitScr = -1

        init {
            textSystem.resetText()
            textSystem.initTextFont(font, VRAM_BASE + 0x80 * 0x20, 0x80, 4)
            textSystem.initText(subText, 10)
            engine.run(proc)
            bridge.hooks.saveSlotSelectLoop = ProcFunc { loop.saveMenuSaveSlotSelectLoop(it) }
            loop.hooks.newKeys = { keys.newKeys }
            loop.hooks.playSoundEffect = { events += "se%02X".format(it) }
            loop.hooks.closeHelpBox = { events += "closeHelpBox" }
            loop.hooks.startSuspendLostHelpBox = { events += "startSuspendLost" }
            loop.hooks.newGame = {
                events += "newGame"
                newGameEntryScr = it.scrCur
                bridge.saveMenuNewGame(it)
                newGameExitScr = it.scrCur
            }
            seed(first - 1)
            engine.goto(proc, SaveMenuNewGame.PL_SAVE_SLOT_SEL)
            engine.run(proc)
            assertEquals(row(first - 1).scrCur, proc.scrCur, "scrCur f${first - 1}")
        }

        fun row(step: Int) = ProcArrayDump.ofWatch(watch, step, names).find("ProcScr_SaveMenu")!!

        fun seed(step: Int) {
            val r = row(step)
            state.scrollCnt = r.u8(0x29)
            state.difficulty = r.u8(0x2A)
            state.mainSelect = r.u8(0x2B)
            state.susSlot = r.u8(0x2C)
            state.cursorSlot = r.u8(0x2D)
            state.jumpLabel = r.u8(0x2E)
            state.unk2f = r.u8(0x2F)
            state.mainOptions = r.u8(0x30)
            state.unk31 = r.u8(0x31)
            state.cursorConfig = r.u8(0x36)
            state.hbEn = r.u8(0x3E) != 0
            state.susSlotCur = r.u8(0x3F)
            state.ctrlTimer = r.u8(0x40)
            state.mainSelBitfile = r.u16(0x42)
            state.unk44 = r.u16(0x44)
        }

        /** Runs capture frame [step]: the key log entry before it is the pressed mask. */
        fun frame(step: Int, unk44Offset: Int = 0) {
            keys.newKeys = inputs[step - 1]
            events.clear()
            engine.run(proc)
            keys.newKeys = 0
            val r = row(step)
            assertEquals(r.scrCur, proc.scrCur, "scrCur f$step")
            assertEquals(r.u8(0x29), state.scrollCnt, "scroll_cnt f$step")
            assertEquals(r.u8(0x2B), state.mainSelect, "main_select f$step")
            assertEquals(r.u8(0x2C), state.susSlot, "sus_slot f$step")
            assertEquals(r.u8(0x2D), state.cursorSlot, "cursor_slot f$step")
            assertEquals(r.u8(0x2E), state.jumpLabel, "jump_label f$step")
            assertEquals(r.u8(0x36), state.cursorConfig, "cursor_config f$step")
            assertEquals(r.u8(0x3E) != 0, state.hbEn, "hb_en f$step")
            assertEquals(r.u8(0x3F), state.susSlotCur, "sus_slot_cur f$step")
            assertEquals(r.u8(0x40), state.ctrlTimer, "ctrl_timer f$step")
            assertEquals(r.u16(0x42), state.mainSelBitfile, "main_sel_bitfile f$step")
            assertEquals(r.u16(0x44) + unk44Offset, state.unk44, "unk_44 f$step")
        }
    }

    private fun rig(name: String, first: Int, last: Int): Rig {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(
            File(
                System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
                "baserom.gba",
            ).isFile,
            "ROM not present",
        )
        assumeTrue(ProcArrayDump.romNames().isNotEmpty(), "decomp nm file not present")
        val run = ref.run(name)
        assumeTrue(run.hasWatch("procs"), "$name procs watch not present")
        return Rig(run, first).also { check(last >= first) }
    }

    @Test
    @Timeout(300)
    @VerifiesPort(
        "SaveMenu_SaveSlotSelectLoop", "ExecSaveMenuMiscOption", "SaveMenuHandleHelpBox",
        "SaveMenuWaitHelpBoxAnim", "SaveMenu_NewGame",
    )
    fun `continue run Restart arm opens, moves and confirms the sub-box f1050 to f1176`() {
        val r = rig(CONTINUE, 1050, 1193)
        for (step in 1050..1175) {
            r.frame(step)
            when (step) {
                1094 -> assertEquals(
                    listOf("se6A", "startSuspendLost"), r.events, "events f$step",
                )
                1155 -> assertEquals(listOf("se67"), r.events, "events f$step")
                else -> assertEquals(emptyList<String>(), r.events, "events f$step")
            }
        }
        val entry = ProcArrayDump.ofEwram(r.run.region("call_SaveMenu_NewGame_1_entry", "ewram"), r.names)
            .find("ProcScr_SaveMenu")!!
        val exit = ProcArrayDump.ofEwram(r.run.region("call_SaveMenu_NewGame_1_exit", "ewram"), r.names)
            .find("ProcScr_SaveMenu")!!
        // f1176: the confirm sets unk_44 to 0xF0 and `sub_80AACBC` already took 0x10 off.
        r.frame(1176, unk44Offset = 0x10)
        assertEquals(listOf("se6A", "newGame", "closeHelpBox"), r.events)
        assertEquals(entry.scrCur, r.newGameEntryScr, "SaveMenu_NewGame entry scrCur")
        assertEquals(exit.scrCur, r.newGameExitScr, "SaveMenu_NewGame exit scrCur")
        assertEquals(0xF0, r.state.unk44)
    }

    @Test
    @Timeout(300)
    @VerifiesPort("SaveMenu_SaveSlotSelectLoop", "SaveMenu_NewGame")
    fun `resume run Resume arm calls SaveMenu_NewGame at f1063`() {
        val r = rig(RESUME, 1018, 1063)
        for (step in 1018..1062) {
            r.frame(step)
            assertEquals(emptyList<String>(), r.events, "events f$step")
        }
        val entry = ProcArrayDump.ofEwram(r.run.region("call_SaveMenu_NewGame_1_entry", "ewram"), r.names)
            .find("ProcScr_SaveMenu")!!
        val exit = ProcArrayDump.ofEwram(r.run.region("call_SaveMenu_NewGame_1_exit", "ewram"), r.names)
            .find("ProcScr_SaveMenu")!!
        r.frame(1063)
        assertEquals(listOf("se6A", "newGame"), r.events)
        assertEquals(entry.scrCur, r.newGameEntryScr, "SaveMenu_NewGame entry scrCur")
        assertEquals(exit.scrCur, r.newGameExitScr, "SaveMenu_NewGame exit scrCur")
    }

    private companion object {
        const val CONTINUE = "ram_trace_title_continue"
        const val VRAM_BASE = 0x06000000
        const val RESUME = "ram_trace_title_resume"

        fun u32(b: ByteArray, o: Int): Int =
            (0 until 4).sumOf { (b[o + it].toInt() and 0xFF) shl (8 * it) }
    }
}
