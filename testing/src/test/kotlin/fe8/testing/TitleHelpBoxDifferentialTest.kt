package fe8.testing

import fe8.game.RomView
import fe8.game.savemenu.SaveMenuSelectLoop
import fe8.game.savemenu.SaveMenuState
import fe8.game.text.Fe8Text
import fe8.game.ui.HelpBox
import fe8.game.ui.HelpBoxState
import fe8.game.ui.HelpPromptSprite
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers
import gbafe.text.Font
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * `PC-11e` against `ram_trace_title_continue`: the confirm help box of the Restart Chapter
 * option, `gProcScr_HelpBox` from its start at `f1094` (`SaveMenuHandleHelpBox`, `hb_en` set) to
 * its end at `f1183` (closed by `SaveMenuHandleHelpBox` at `f1176`).
 *
 * Per step: the proc's row (script position, parent, `HelpBoxProc` fields), the prompt proc's
 * `proc_lockCnt`, `SaveMenuProc.hb_en`, and the box objects of the layer 0 sprite pool. At
 * `f1154` and `f1175` also the hardware OAM, the box tiles in OBJ VRAM and `Pal_HelpBox`.
 *
 * The content size comes from the ported `GetStringTextBox` (PC-11f), checked through `wBoxFinal` /
 * `hBoxFinal` of every step; the text procs are checked by [TitleHelpBoxTextDifferentialTest].
 * Capture steps that repeat the previous
 * step's box proc row while the box is animating are lag frames (the game loop did not run the
 * proc) and are not run.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(300)
class TitleHelpBoxDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private var rom: RomView? = null

    @AfterAll
    fun releaseReferenceData() {
        rom = null
    }

    @Test
    @VerifiesPort(
        "StartHelpBoxExt_NoHelpSprite", "HelpBox_OnOpen", "HelpBox_OnLoop", "HelpBox_OnClose",
        "HelpBox_WaitClose", "CloseHelpBox", "LoadHelpBoxGfx", "DisplayHelpBoxObj",
        "GetStringTextBox",
    )
    fun `continue run confirm help box matches f1094 to f1183`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(romFile.isFile, "ROM not present")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val run = ref.run(RUN)
        assumeTrue(run.dumps.containsKey("f%05d".format(1154)), "$RUN f1154 not present")
        val view = rom ?: RomView.ofRom(romFile.readBytes()).also { rom = it }
        val romBytes = view.wholeRomBytes()

        val engine = ProcEngine()
        val hardware = BgHardware()
        val layers = SpriteLayers()
        val oam = OamBuffer()
        val textSystem = TextSystem(romBytes, TextVram(), Fe8Text.CONFIG)
        val prompt = HelpPromptSprite(engine, layers, hardware, view)
        val messages = MsgStrings(
            romBytes,
            MsgHuffman(
                romBytes,
                0x0815A72C - 0x08000000,
                u32(romBytes, 0x0815D488 - 0x08000000) and 0x01FFFFFF,
            ),
            0x0815D48C - 0x08000000,
        )
        textSystem.resetText()
        val box = HelpBox(
            engine, layers, hardware, view, textSystem, prompt, messages, textSpeed = { 2 },
        )
        val parent = engine.start(
            ProcScript("ProcScr_SaveMenu", listOf(block())) { Any() },
            3,
        )
        val loop = SaveMenuSelectLoop(
            engine, hardware, textSystem, messages, Font(), Text(),
            bitfileToIndex = { bits -> (0 until 8).firstOrNull { (bits shr it) and 1 != 0 } ?: -1 },
        )
        val watch = run.watch("procs")

        val first = ProcArrayDump.ofWatch(watch, FIRST, names).find(PROC)
        assertNotNull(first, "box proc row f$FIRST")
        loop.hooks.closeHelpBox = box::closeHelpBox
        loop.hooks.startSuspendLostHelpBox = {
            box.loadHelpBoxGfx(
                HelpBox.OBJ_VRAM0 +
                    HelpBox.OBJCHR_SAVEMENU_SLOTSEL_HELPBOX * HelpBox.TILE_SIZE_4BPP,
                HelpBox.OBJPAL_SAVEMENU_SLOTSEL_HELPBOX,
            )
            box.startHelpBoxExtNoHelpSprite(48, 48, MSG_882)
        }

        // The prompt proc exists since f1049; settle its script.
        val promptProc = prompt.startHelpPromptSprite(0xC0, 8, 8, parent)
        engine.run(engine.root(3))
        engine.run(engine.root(3))
        assertNull(ProcArrayDump.ofWatch(watch, FIRST - 1, names).find(PROC), "no box at f1093")

        val menu = SaveMenuState()
        var previous: List<Int>? = null
        var previousPool: List<List<Int>> = emptyList()
        for (step in FIRST..LAST) {
            val dump = ProcArrayDump.ofWatch(watch, step, names)
            val row = dump.find(PROC)
            val sm = dump.find("ProcScr_SaveMenu")
            assertNotNull(sm, "save menu row f$step")
            val bytes = row?.let { r -> (0x28..0x53).map { r.u8(it) } + r.scrCur }
            val lag = bytes != null && bytes == previous && row!!.u16(0x48) != row.u16(0x4A)
            previous = bytes

            // SaveMenuHandleHelpBox, from the save menu proc's fields before the A press / close.
            fun handleHelpBox() {
                menu.cursorConfig = sm!!.u8(SM_CURSOR_CONFIG)
                menu.susSlotCur = sm.u8(SM_SUS_SLOT_CUR)
                menu.mainSelBitfile = sm.u16(SM_MAIN_SEL_BITFILE)
                menu.hbEn = ProcArrayDump.ofWatch(watch, step - 1, names)
                    .find("ProcScr_SaveMenu")!!.u8(SM_HB_EN) != 0
                loop.saveMenuHandleHelpBox(menu)
            }
            // The close runs before the box proc's turn of f1176 (the shrink starts that frame).
            if (step == CLOSE) handleHelpBox()

            if (!lag) {
                layers.clear()
                oam.syncHi()
                oam.hiPut = 0
                engine.run(engine.root(3))
                layers.pushLayerObjects(0) { entry ->
                    oam.putHi(entry.oam1, entry.oam0, entry.obj!!.data, entry.oam2)
                }
            }
            // The open runs after the box proc's turn: f1094 shows the proc just started.
            if (step == FIRST) handleHelpBox()
            assertEquals(sm!!.u8(SM_HB_EN) != 0, menu.hbEn, "hb_en f$step")

            if (row == null) {
                assertNull(engine.find(box.script), "box proc ended at f$step")
                break
            }
            val proc = engine.find(box.script)
            assertNotNull(proc, "box proc f$step")
            assertEquals(row.scrCur, proc!!.scrCur, "scrCur f$step")
            assertEquals(3, row.u32(0x14), "parent root f$step")
            val s = proc.state as HelpBoxState
            val fields = listOf(
                s.xBoxInit, s.yBoxInit, s.xBoxFinal, s.yBoxFinal, s.wBoxInit, s.hBoxInit,
                s.wBoxFinal, s.hBoxFinal, s.timer, s.timerMax, s.msgId, s.item,
                if (s.noHelpSprite) 1 else 0,
            )
            assertEquals(
                listOf(0x38, 0x3A, 0x3C, 0x3E, 0x40, 0x42, 0x44, 0x46, 0x48, 0x4A, 0x4C, 0x4E, 0x52)
                    .map { row.u16(it) },
                fields.map { it and 0xFFFF },
                "proc fields f$step",
            )
            // xBox..hBox hold uninitialised memory until the first OnLoop.
            if (row.scrCur > 1) {
                assertEquals(
                    listOf(0x30, 0x32, 0x34, 0x36).map { row.u16(it) },
                    listOf(s.xBox, s.yBox, s.wBox, s.hBox).map { it and 0xFFFF },
                    "box rectangle f$step",
                )
            }
            val promptRow = dump.find("gProcScr_HelpPromptSpr")
            assertEquals(promptRow!!.u8(0x28), promptProc.lockCnt, "prompt lockCnt f$step")

            val pool = if (lag) previousPool else ownPool(layers)
            previousPool = pool
            val dumpName = listOf("r%05d", "f%05d").map { it.format(step) }
                .firstOrNull { run.dumps.containsKey(it) }
            if (dumpName != null) {
                val ew = run.region(dumpName, "ewram")
                val iw = run.region(dumpName, "iwram")
                assertEquals(capturePool(ew, iw), pool, "layer 0 box sprites f$step")
            }
            if (step == 1154 || step == 1175) checkSnapshot(run, step, box, hardware, oam, view)
        }
    }

    private fun checkSnapshot(
        run: ReferenceData.Run,
        step: Int,
        box: HelpBox,
        hardware: BgHardware,
        oam: OamBuffer,
        view: RomView,
    ) {
        val dump = "f%05d".format(step)
        val ew = run.region(dump, "ewram")
        for (i in 0 until 16) {
            val idx = (HelpBox.OBJPAL_SAVEMENU_SLOTSEL_HELPBOX + 0x10) * 16 + i
            assertEquals(
                u16(ew, GPALETTE - GbaMem.EWRAM + idx * 2),
                hardware.palette[idx].toInt() and 0xFFFF,
                "palette entry $idx f$step",
            )
        }
        val hw = run.region(dump, "oam")
        val hwBox = (0 until OamBuffer.OBJ_COUNT).map {
            listOf(u16(hw, it * 8), u16(hw, it * 8 + 2), u16(hw, it * 8 + 4))
        }.filter { isBoxAttr(it[2]) }
        val own = (0 until oam.hiPut / 4).map {
            listOf(
                oam.buffer[it * 4].toInt() and 0xFFFF,
                oam.buffer[it * 4 + 1].toInt() and 0xFFFF,
                oam.buffer[it * 4 + 2].toInt() and 0xFFFF,
            )
        }.filter { isBoxAttr(it[2]) }
        assertEquals(hwBox, own, "hardware OAM box objects f$step")
        assertEquals(true, own.isNotEmpty(), "box objects present f$step")
        val vram = run.region(dump, "vram")
        val scratch = ByteArray(0x2000)
        val base = HelpBox.OBJCHR_SAVEMENU_SLOTSEL_HELPBOX * HelpBox.TILE_SIZE_4BPP + OBJ_VRAM
        for ((i, gfx) in GFX_BLOCKS.withIndex()) {
            val length = view.decompressInto(gfx, scratch, 0)
            val at = base + GFX_OFFSETS[i]
            for (k in 0 until length) {
                assertEquals(
                    vram[at + k].toInt() and 0xFF,
                    hardware.vram[at + k].toInt() and 0xFF,
                    "OBJ VRAM block $i byte $k f$step",
                )
            }
        }
        assertEquals(0x9200, box.oam2Base)
    }

    private fun isBoxAttr(a2: Int): Boolean =
        a2 and 0xF000 == 0x9000 && a2 and 0x3FF in 0x200..0x27F

    /** Box entries of layer 0 in the capture's `sSpritePool`: oam1, oam0, oam2, object. */
    private fun capturePool(ewram: ByteArray, iwram: ByteArray): List<List<Int>> {
        val allocIt = GbaMem.u32(iwram, GbaMem.IWRAM, ADDR_G_SPRITE_ALLOC_IT)
        var addr = u32(ewram, ADDR_S_SPRITE_LAYERS - GbaMem.EWRAM)
        val out = ArrayList<List<Int>>()
        while (addr in ADDR_S_SPRITE_POOL until allocIt) {
            val base = addr - GbaMem.EWRAM
            if (isBoxAttr(u16(ewram, base + 8))) {
                out += listOf(
                    s16(ewram, base + 4), s16(ewram, base + 6), u16(ewram, base + 8),
                    u32(ewram, base + 0x0C),
                )
            }
            addr = u32(ewram, base)
        }
        return out
    }

    private fun ownPool(layers: SpriteLayers): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var e = layers.layers[0].next
        while (e != null && e in layers.pool) {
            if (isBoxAttr(e.oam2)) out += listOf(e.oam1, e.oam0, e.oam2, e.obj!!.id)
            e = e.next
        }
        return out
    }

    private companion object {
        const val RUN = "ram_trace_title_continue"
        const val PROC = "gProcScr_HelpBox"
        const val FIRST = 1094
        const val CLOSE = 1176
        const val LAST = 1184
        const val MSG_882 = 0x882
        const val OBJ_VRAM = 0x10000
        const val GPALETTE = 0x020228A8
        const val ADDR_S_SPRITE_POOL = 0x02029D8C
        const val ADDR_S_SPRITE_LAYERS = 0x0202A58C
        const val ADDR_G_SPRITE_ALLOC_IT = 0x03004970
        const val SM_CURSOR_CONFIG = 0x36
        const val SM_HB_EN = 0x3E
        const val SM_SUS_SLOT_CUR = 0x3F
        const val SM_MAIN_SEL_BITFILE = 0x42
        val GFX_BLOCKS = intArrayOf(0x08A0285C, 0x08A02884, 0x08A028AC, 0x08A02914, 0x08A02980)
        val GFX_OFFSETS = intArrayOf(0x360, 0x760, 0xB60, 0xF60, 0x1360)

        fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
        fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
        fun s16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()
        fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}
