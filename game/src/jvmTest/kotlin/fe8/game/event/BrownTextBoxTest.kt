package fe8.game.event

import fe8.game.RomView
import fe8.game.scene.BrownTextBox
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Event 0x3A sub 1 and the brown text box procs (transcribed, no reference trace). */
class BrownTextBoxTest {
    private val engine = ProcEngine()
    private val hw = BgHardware()
    private val sprites = SpriteLayers()

    private fun box(textWidth: Int): BrownTextBox {
        val dir = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val file = File(dir, "baserom.gba")
        assumeTrue(file.isFile, "ROM not present")
        return BrownTextBox(engine, hw, sprites, RomView.ofRom(file.readBytes()), { textWidth })
    }

    /** A parent proc that idles until the box fades blocked it. */
    private fun parent() = engine.start(
        ProcScript("parent", listOf(repeat(ProcFunc { }), end())),
        0,
    )

    @Test
    fun `event 3A sub 1 yields and the blend ramps up, holds, ramps down, then the box ends`() {
        val box = box(0)
        val state = EventState()
        // cmd 0x3A sub 1 at 0x1000: text 0x664, argv[2] = x 8, y 8.
        val words = mapOf(0x1000 to 0x3A01, 0x1002 to 0x0664, 0x1004 to 0, 0x1006 to 0x0808)
        val handler = EventPopup(state, ScriptMemory { words[it] ?: 0 }, box)
        val parent = parent()
        val ev = EventEngineProc()
        ev.proc = parent
        ev.pEventCurrent = 0x1000
        assertEquals(EventConst.EVC_ADVANCE_YIELD, handler.event3ADisplayPopup.run(ev))
        assertNotNull(engine.find(box.boxScript))
        assertEquals(1, parent.lockCnt)

        val alphas = ArrayList<Pair<Int, Int>>()
        var frames = 0
        while (engine.find(box.boxScript) != null && frames++ < 400) {
            sprites.clear()
            engine.run(parent)
            alphas += hw.lcd.blendCoeffA to hw.lcd.blendCoeffB
        }
        assertNull(engine.find(box.boxScript))
        // sub_8011B90 (SetBlendNone) runs the frame after the box ends, then the parent unblocks.
        sprites.clear()
        engine.run(parent)
        assertEquals(0, parent.lockCnt)
        assertEquals(0, (hw.lcd.bldcnt shr 6) and 3)
        // Up 1..16, hold at 16 (sleep 100 and the AF4 frame), down 15..0 (the end frame is 0).
        val ups = alphas.map { it.first }
        assertEquals((1..16).toList(), ups.take(16))
        assertEquals(16, ups.maxOf { it })
        assertEquals(0, ups.last())
        assert(alphas.all { it.first + it.second == 16 })
        assertEquals((15 downTo 0).toList(), ups.takeLast(16))
    }

    @Test
    fun `event 3A sub 0 is not ported`() {
        val box = box(0)
        val handler = EventPopup(EventState(), ScriptMemory { if (it == 0x1000) 0x3A00 else 0 }, box)
        val ev = EventEngineProc()
        ev.proc = parent()
        ev.pEventCurrent = 0x1000
        try {
            handler.event3ADisplayPopup.run(ev)
            throw AssertionError("expected NotImplementedError")
        } catch (_: NotImplementedError) {
        }
    }

    @Test
    fun `one loop frame of a width 7 box puts the frame sprites in C order`() {
        // 16 px text: r4 = 2, width = 7 tiles.
        val box = box(16)
        val parent = parent()
        box.startBrownTextBoxCore(8, 8, 0x664, 0x5000, 9, parent)
        engine.run(engine.find(box.boxScript))
        sprites.clear()
        engine.run(engine.find(box.boxScript))
        val all = ArrayList<List<Int>>()
        sprites.pushLayerObjects(0) { all += listOf(it.oam1, it.oam0, it.oam2) }
        // Layer 0 entries (3) come first, then layer 4; newest first within a layer.
        val got = all.mapIndexed { i, e -> listOf(if (i < 3) 0 else 4) + e }
        val b = (0x5000 / 32) or (9 shl 12)
        val a = ((0x5400) / 32) or (10 shl 12)
        // Layer chains push most recent first, so reverse to call order.
        val layer4 = got.filter { it[0] == 4 }.reversed()
        val layer0 = got.filter { it[0] == 0 }.reversed()
        assertEquals(
            listOf(
                listOf(4, 8, 8, b), listOf(4, 8 + 40, 8, b + 4),
                listOf(4, 8, 32, b + 0xD), listOf(4, 8 + 40, 32, b + 0x11),
                listOf(4, 8, 16, b + 6), listOf(4, 8, 24, b + 11),
                listOf(4, 8 + 48, 16, b + 10), listOf(4, 8 + 48, 24, b + 12),
                // i = 2, 4 (16x8 top), then i = 6 is not < width-1 = 6 -> none
                listOf(4, 8 + 16, 8, b + 2), listOf(4, 8 + 32, 8, b + 2),
                // bottom 16x8 for i = 2, 4
                listOf(4, 8 + 16, 32, b + 15), listOf(4, 8 + 32, 32, b + 15),
                // middle rows: i = 1, 3 (16x8); then i = 5 (8x8)
                listOf(4, 8 + 8, 16, b + 8), listOf(4, 8 + 8, 24, b + 8),
                listOf(4, 8 + 24, 16, b + 8), listOf(4, 8 + 24, 24, b + 8),
                listOf(4, 8 + 40, 16, b + 8), listOf(4, 8 + 40, 24, b + 8),
            ),
            layer4,
        )
        assertEquals(
            listOf(listOf(0, 16, 16, a), listOf(0, 48, 16, a + 4), listOf(0, 80, 16, a + 8)),
            layer0,
        )
    }
}
