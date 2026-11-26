package fe8.testing

import fe8.game.RomView
import fe8.game.text.Fe8Text
import fe8.game.ui.HelpBox
import fe8.game.ui.HelpBoxIntroState
import fe8.game.ui.HelpBoxScrollState
import fe8.game.ui.HelpPromptSprite
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * `PC-11f` against `ram_trace_title_continue`: the text of the Restart Chapter confirm box.
 * `ProcScr_HelpBoxIntro` (`f1094..1103`), `gProcScr_HelpBoxTextScroll` (`f1104..1128`), the
 * three text strips `gHelpBoxSt.text[]` and the font in EWRAM at every step, the text tiles in
 * OBJ VRAM at `f1154` and `f1175`, and their clearing by `ClearHelpBoxText` at `f1176`.
 *
 * The box is started as the save menu does after the procs of `f1094` ran; capture steps that
 * repeat the previous box proc row while the box animates are lag frames and are not run (see
 * `TitleHelpBoxDifferentialTest`).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(300)
class TitleHelpBoxTextDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private var rom: RomView? = null

    @AfterAll
    fun releaseReferenceData() {
        rom = null
    }

    @Test
    @VerifiesPort(
        "ProcScr_HelpBoxIntro", "HelpBoxSetupstringLines", "HelpBoxDrawstring",
        "HelpBoxIntroDrawTexts", "StartHelpBoxTextInit", "ClearHelpBoxText",
        "HelpBoxTextScroll_OnLoop", "gProcScr_HelpBoxTextScroll", "SpriteText_DrawBackground",
    )
    fun `continue run confirm box text matches f1094 to f1176`() {
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
        val vram = TextVram()
        val textSystem = TextSystem(romBytes, vram, Fe8Text.CONFIG)
        textSystem.resetText()
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
        val box = HelpBox(
            engine, layers, hardware, view, textSystem, prompt, messages, textSpeed = { 2 },
        )
        engine.start(ProcScript("ProcScr_SaveMenu", listOf(block())) { Any() }, 3)
        val watch = run.watch("procs")

        var previous: List<Int>? = null
        var stringBase = -1
        var lastScroll: HelpBoxScrollState? = null
        var scrollSteps = 0
        for (step in FIRST..CLOSE) {
            val dump = ProcArrayDump.ofWatch(watch, step, names)
            val row = dump.find(BOX)
            val bytes = row?.let { r -> (0x28..0x53).map { r.u8(it) } + r.scrCur }
            val lag = bytes != null && bytes == previous && row!!.u16(0x48) != row.u16(0x4A)
            previous = bytes

            if (step == CLOSE) box.closeHelpBox()
            if (!lag) {
                layers.clear()
                engine.run(engine.root(3))
            }
            if (step == FIRST) {
                // SaveMenuStartSuspendLostHelpBox, after the box proc's turn of f1094.
                box.loadHelpBoxGfx(
                    HelpBox.OBJ_VRAM0 +
                        HelpBox.OBJCHR_SAVEMENU_SLOTSEL_HELPBOX * HelpBox.TILE_SIZE_4BPP,
                    HelpBox.OBJPAL_SAVEMENU_SLOTSEL_HELPBOX,
                )
                box.startHelpBoxExtNoHelpSprite(48, 48, MSG_882)
            }

            val introRow = dump.find(INTRO)
            val intro = engine.find(box.introScript)
            if (introRow == null) {
                assertNull(intro, "intro proc ended at f$step")
            } else {
                assertNotNull(intro, "intro proc f$step")
                assertEquals(introRow.scrCur, intro!!.scrCur, "intro scrCur f$step")
                assertEquals(3, introRow.u32(0x14), "intro parent root f$step")
                val state = intro.state as HelpBoxIntroState
                assertEquals(introRow.u16(0x5C), state.msg, "msg f$step")
                assertEquals(0, state.item, "item f$step")
            }

            val scrollRow = dump.find(SCROLL)
            val scroll = engine.find(box.textScrollScript)
            if (scrollRow == null) {
                assertNull(scroll, "scroll proc ended at f$step")
            } else {
                assertNotNull(scroll, "scroll proc f$step")
                val s = scroll!!.state as HelpBoxScrollState
                lastScroll = s
                scrollSteps++
                if (stringBase < 0) stringBase = scrollRow.u32(0x2C)
                assertEquals(scrollRow.scrCur, scroll.scrCur, "scroll scrCur f$step")
                assertEquals(3, scrollRow.u32(0x14), "scroll parent root f$step")
                assertEquals(stringBase + s.offset, scrollRow.u32(0x2C), "string offset f$step")
                assertEquals(
                    listOf(0x5C, 0x5E, 0x60, 0x62).map { scrollRow.u16(it) },
                    listOf(s.pretextLines, s.step, s.speed, s.charsPerStep).map { it and 0xFFFF },
                    "pretext_lines, step, speed, chars_per_step f$step",
                )
            }

            val dumpName = listOf("r%05d", "f%05d").map { it.format(step) }
                .firstOrNull { run.dumps.containsKey(it) }
            if (dumpName != null) {
                val ew = run.region(dumpName, "ewram")
                val own = ByteArray(0x18)
                box.font.serialize(own, 0)
                assertEquals(
                    (0 until 0x18).map { u8(ew, G_HELP_BOX_ST - EWRAM + it) },
                    (0 until 0x18).map { u8(own, it) },
                    "gHelpBoxSt.font f$step",
                )
                for ((i, strip) in box.texts.withIndex()) {
                    val at = G_HELP_BOX_ST - EWRAM + 0x18 + i * 8
                    strip.serialize(own, 0)
                    assertEquals(
                        (0 until 8).map { u8(ew, at + it) },
                        (0 until 8).map { u8(own, it) },
                        "gHelpBoxSt.text[$i] f$step",
                    )
                }
            }
            if (step == 1154 || step == 1175 || step == CLOSE) {
                val vramDump = run.region("f%05d".format(step), "vram")
                checkStrips(vramDump, vram.vram, step)
            }
            if (step == 1154) {
                val ew = run.region("f%05d".format(step), "ewram")
                val str = lastScroll!!.string
                var n = 0
                while (str[n].toInt() != 0) {
                    assertEquals(
                        str[n].toInt() and 0xFF, u8(ew, stringBase - EWRAM + n),
                        "message byte $n in the captured string buffer",
                    )
                    n++
                }
                assertTrue(n > 0)
            }
        }
        assertEquals(25, scrollSteps, "the scroll proc rows f1104..f1128")
    }

    private fun checkStrips(capture: ByteArray, own: ByteArray, step: Int) {
        val base = HelpBox.OBJCHR_SAVEMENU_SLOTSEL_HELPBOX * HelpBox.TILE_SIZE_4BPP + OBJ_VRAM
        var nonZero = 0
        for (strip in 0 until 3) {
            for (half in 0..1) {
                val length = 0x360
                val at = base + strip * 0x800 + half * 0x400
                for (k in 0 until length) {
                    val c = capture[at + k].toInt() and 0xFF
                    assertEquals(
                        c, own[at + k].toInt() and 0xFF, "strip $strip half $half +$k f$step",
                    )
                    if (c != 0) nonZero++
                }
            }
        }
        assertTrue(nonZero > 100, "strip tiles present f$step")
    }

    private companion object {
        const val RUN = "ram_trace_title_continue"
        const val BOX = "gProcScr_HelpBox"
        const val INTRO = "ProcScr_HelpBoxIntro"
        const val SCROLL = "gProcScr_HelpBoxTextScroll"
        const val FIRST = 1094
        const val CLOSE = 1176
        const val MSG_882 = 0x882
        const val OBJ_VRAM = 0x10000
        const val EWRAM = 0x02000000
        const val G_HELP_BOX_ST = 0x0203E794

        fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
        fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
        fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}
