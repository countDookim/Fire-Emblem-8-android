package fe8.testing

import fe8.game.GameControl
import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.flags.Fe8EventFlags
import fe8.game.save.BmSaveLib
import fe8.game.save.NewGameRam
import fe8.game.save.SaveExtras
import fe8.game.savemenu.SaveMenuChoice
import fe8.game.savemenu.SaveMenuNewGame
import fe8.game.savemenu.SaveMenuPostChapter
import fe8.game.savemenu.SaveMenuStart
import fe8.game.savemenu.SaveMenuState
import fe8.game.worldmap.GmData
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.LcdControl
import gbafe.render.PaletteFade
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * `PC-11a` against `ram_trace_title_continue`: the setup of the title save menu. `sub_8009AD8`
 * (the `sMusicProc2Script` starter, f921), `StartSaveMenu` (f921), `SaveMenu_Init` (LCD registers
 * and `gSaveMenuRTextData` at f921), `SaveMenu_LoadExtraMenuGraphics` (seeded at f936, compared at
 * f943, VRAM at the full dump f1000), `SaveMenu_JumpToTarget` (f962..f963) and
 * `SaveMenuGetBitfile`. The capture's calls span several frames, so the fields are compared at the
 * frame where the capture shows the call finished.
 */
class TitleSaveMenuSetupDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private class Rig(val run: ReferenceData.Run, romBytes: ByteArray) {
        val engine = ProcEngine()
        val hardware = BgHardware()
        val playSt = PlaySt()
        val rom = RomView.ofRom(romBytes)
        val fade = ArchivedPaletteFade(PaletteFade(hardware, engine), hardware, engine)
        val lib = BmSaveLib(CartSram()).also { it.sramInit() }
        val ram = NewGameRam(playSt, Fe8EventFlags.create(), ByteArray(200))
        val bridge = SaveMenuNewGame(engine, hardware, lib, ram, fade)
        val menu = SaveMenuPostChapter(
            engine, hardware, playSt, BmSt(), GmData(ByteArray(0x8000), RngService()), lib, fade,
        )
        val choice = SaveMenuChoice(extras = SaveExtras(lib))
        init {
            lib.sram.copyIn(run.sram("s01000"))
            lib.sramInit()
        }

        val start = SaveMenuStart(
            engine, hardware, rom, playSt, bridge.script, menu, choice,
        )

        fun ewram(step: Int) = run.region("r%05d".format(step), "ewram")
        fun iwram(step: Int) = run.region("r%05d".format(step), "iwram")
    }

    private fun rig(): Rig {
        assumeTrue(
            ref.available && romFile.isFile,
            "ROM / mGBA reference data not present (run tools/setup.sh)",
        )
        assumeTrue(ProcArrayDump.romNames().isNotEmpty(), "decomp nm file not present")
        val run = ref.run(CONTINUE)
        assumeTrue(run.dumps.containsKey("r00921"), "$CONTINUE not present")
        return Rig(run, romFile.readBytes())
    }

    private fun procs(r: Rig, step: Int) =
        ProcArrayDump.ofEwram(r.ewram(step), ProcArrayDump.romNames())

    private fun capMenu(r: Rig, step: Int) = procs(r, step).find("ProcScr_SaveMenu")!!

    private fun lcdOf(iwram: ByteArray) =
        LcdControl().also { it.readFrom(iwram, GLCD_CONTROL - IWRAM) }

    private fun lcdFields(lcd: LcdControl): List<Any> = listOf(
        lcd.dispcnt, lcd.dispstat, lcd.bgCnt.toList(), lcd.bgHofs.toList(), lcd.bgVofs.toList(),
        lcd.winH.toList(), lcd.winV.toList(), lcd.wincnt, lcd.mosaic, lcd.bldcnt, lcd.blendCoeffA,
        lcd.blendCoeffB, lcd.blendY, lcd.colorAddition,
    )

    @Test
    @Timeout(300)
    @VerifiesPort("sub_8009AD8")
    fun `title to save menu volume change matches the sMusicProc2Script proc at f921`() {
        val r = rig()
        assertNull(procs(r, 920).find("sMusicProc2Script"))
        val control = GameControl(ProcEngine(), KeyStatus(), { STAND_IN }, { STAND_IN })
        control.sub8009AD8()
        val cap = procs(r, 921).find("sMusicProc2Script")!!
        val change = control.sound.bgmVolumeChange!!
        assertEquals(cap.u16(VC_INIT), change.initVolume)
        assertEquals(cap.u16(VC_END), change.endVolume)
        assertEquals(cap.u16(VC_CLOCK), change.clock)
        assertEquals(cap.u16(VC_TIME_END), change.timeEnd)
        // The clock reaches the end at f975 and the proc is gone at f976.
        assertEquals(change.timeEnd, procs(r, 975).find("sMusicProc2Script")!!.u16(VC_CLOCK))
        assertNull(procs(r, 976).find("sMusicProc2Script"))
    }

    @Test
    @Timeout(300)
    @VerifiesPort("StartSaveMenu")
    fun `start save menu creates the blocking proc with the start bitfile at f921`() {
        val r = rig()
        val cap = capMenu(r, 921)
        val gameControl = procs(r, 921).find("gProcScr_GameControl")!!
        val parent = r.engine.start(STAND_IN, 3)
        r.playSt.decode(r.ewram(920), GPLAYST - EWRAM)
        r.playSt.textSpeed = 0
        val proc = r.start.startSaveMenu(parent)!!
        val state = proc.state as SaveMenuState
        assertEquals(cap.u16(MAIN_SEL_BITFILE), state.mainSelBitfile)
        assertEquals(cap.u8(EXTRA_SEL_BITFILE), state.extraSelBitfile)
        assertEquals(gameControl.u8(LOCK_CNT), parent.lockCnt, "blocked parent")
        val config = r.run.watch("gplayst").u8(921, PLAYST_CONFIG)
        assertEquals((config shr 5) and 3, r.playSt.textSpeed)
    }

    @Test
    @Timeout(300)
    @VerifiesPort("SaveMenu_Init")
    fun `save menu init reproduces the LCD registers and help text state at f921`() {
        val r = rig()
        val pre = lcdOf(r.iwram(920))
        r.hardware.lcd.readFrom(r.iwram(920), GLCD_CONTROL - IWRAM)
        r.menu.rTextData.pid = 7
        r.menu.rTextData.level = 3
        r.menu.rTextData.nodeId = 5
        r.start.saveMenuInit()
        val want = lcdOf(r.iwram(921))
        // The title leaves different registers, so Init is what changes them.
        assertEquals(false, lcdFields(pre) == lcdFields(want))
        assertEquals(lcdFields(want), lcdFields(r.hardware.lcd))
        val rText = r.ewram(921)
        assertEquals(r.menu.rTextData.pid, rText[RTEXT - EWRAM].toInt() and 0xFF)
        assertEquals(r.menu.rTextData.level, rText[RTEXT - EWRAM + 1].toInt())
        assertEquals(r.menu.rTextData.nodeId, rText[RTEXT - EWRAM + 2].toInt())
    }

    @Test
    @Timeout(300)
    @VerifiesPort("SaveMenu_LoadExtraMenuGraphics")
    fun `load extra menu graphics builds the option lists and the first target at f943`() {
        val r = rig()
        val seed = capMenu(r, 936)
        val menu = r.engine.start(r.bridge.script, 3)
        val state = menu.state as SaveMenuState
        for (i in 0 until 3) state.slots.chapterIdx[i] = seed.u8(CHAPTER_IDX + i)
        state.slots.unk44 = seed.u16(UNK_44)
        state.mainSelBitfile = 0x100
        // Non-zero so that each write of the original is visible.
        state.susSlot = 9
        state.mainSelect = 9
        state.extraSelect = 9
        state.unk46 = 9
        state.unk2f = 9
        state.jumpLabel = 9
        r.start.saveMenuLoadExtraMenuGraphics(menu)
        val cap = capMenu(r, 943)
        assertEquals(cap.u8(JUMP_LABEL), state.jumpLabel)
        assertEquals(cap.u8(SUS_SLOT), state.susSlot)
        assertEquals(cap.u8(MAIN_SELECT), state.mainSelect)
        assertEquals(cap.u8(EXTRA_SELECT), state.extraSelect)
        assertEquals(cap.u16(UNK_46), state.unk46)
        assertEquals(cap.u8(UNK_2F), state.unk2f)
        assertEquals(cap.u8(MAIN_OPTIONS), state.mainOptions)
        assertEquals(cap.u8(MENU_COUNT), state.unk31)
        assertEquals(cap.u8(EXTRA_OPTIONS), state.extraOptions)
        assertEquals(cap.u16(MAIN_SEL_BITFILE), state.mainSelBitfile)
        // The decompressed object graphics sit in the full VRAM dump at f1000.
        val scratch = ByteArray(0x8000)
        val size = r.rom.decompressInto(IMG_GAME_MAIN_MENU_OBJS, scratch, 0)
        val vram = r.run.region("f01000", "vram")
        assertArrayEquals(
            vram.copyOfRange(CHOICE_VRAM, CHOICE_VRAM + size),
            r.hardware.vram.copyOfRange(CHOICE_VRAM, CHOICE_VRAM + size),
        )
    }

    @Test
    @Timeout(300)
    @VerifiesPort("SaveMenu_JumpToTarget")
    fun `jump to target moves the script to the main loop as the reference at f963`() {
        val r = rig()
        val menu = r.engine.start(r.bridge.script, 3)
        (menu.state as SaveMenuState).jumpLabel = capMenu(r, 962).u8(JUMP_LABEL)
        r.start.saveMenuJumpToTarget(menu)
        r.engine.run(menu)
        assertEquals(capMenu(r, 963).scrCur, menu.scrCur)
    }

    @Test
    @Timeout(300)
    @VerifiesPort("SaveMenuGetBitfile")
    fun `bitfile selection picks the numbered set bit as in the title run`() {
        val r = rig()
        // Main options 0x2B (resume, restart, erase, extras): item 0 at f943, A on item 1 at f1033.
        assertEquals(0x2B, capMenu(r, 943).u8(MAIN_OPTIONS))
        assertEquals(
            capMenu(r, 943).u16(MAIN_SEL_BITFILE),
            SaveMenuChoice.saveMenuGetBitfile(0x2B, 0),
        )
        assertEquals(
            capMenu(r, 1033).u16(MAIN_SEL_BITFILE),
            SaveMenuChoice.saveMenuGetBitfile(0x2B, capMenu(r, 1033).u8(MAIN_SELECT)),
        )
    }

    private companion object {
        const val CONTINUE = "ram_trace_title_continue"
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val GLCD_CONTROL = 0x03003080
        const val GPLAYST = 0x0202BCF0
        const val RTEXT = 0x0203EF64
        const val PLAYST_CONFIG = 0x40
        const val VC_INIT = 0x64
        const val VC_END = 0x66
        const val VC_CLOCK = 0x68
        const val VC_TIME_END = 0x6A
        const val LOCK_CNT = 0x28
        const val MAIN_SELECT = 0x2B
        const val SUS_SLOT = 0x2C
        const val JUMP_LABEL = 0x2E
        const val UNK_2F = 0x2F
        const val MAIN_OPTIONS = 0x30
        const val MENU_COUNT = 0x31
        const val EXTRA_OPTIONS = 0x32
        const val EXTRA_SELECT = 0x34
        const val EXTRA_SEL_BITFILE = 0x35
        const val CHAPTER_IDX = 0x37
        const val MAIN_SEL_BITFILE = 0x42
        const val UNK_44 = 0x44
        const val UNK_46 = 0x46
        const val IMG_GAME_MAIN_MENU_OBJS = 0x08A2812C
        const val CHOICE_VRAM = 0x10000 + 0x200 * 0x20
        val STAND_IN = ProcScript("stand-in", listOf(end()))
    }
}
