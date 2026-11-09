package fe8.testing

import fe8.game.ui.Popup
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Popup proc state compared with the item popup capture (NewPopup_Simple(PopupScr_GotItem)). */
class PopupProcDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort("PopupProc_Init")
    fun `init fields match the popup proc before its graphics are drawn`() {
        assumeTrue(ref.available, "reference capture unavailable")
        val run = ref.run("ram_trace_item_popup")
        // Script index 3 is the first command after PopupProc_Init and the 10 frame sleep; the
        // icon id is still the Init value there. NewPopup_Simple(PopupScr_GotItem, 0x60, 0).
        val original = ProcArrayDump.ofEwram(dump(run, 18598)).find(Popup.PROC_SCRIPT)!!
        assertEquals(3, original.scrCur)
        val mine = Popup.PopupProc()
        Popup.setupSimple(mine, GOT_ITEM, 0x60, 0)
        Popup.init(mine)
        assertEquals(original.u32(0x2C), mine.pDefinition, "pDefinition")
        assertEquals(original.u32(0x30), mine.clock, "clock")
        assertEquals(original.u8(0x34), mine.xTileParam and 0xFF, "xTileParam")
        assertEquals(original.u8(0x35), mine.yTileParam and 0xFF, "yTileParam")
        assertEquals(original.u8(0x36), mine.winStyle, "winStyle")
        assertEquals(original.u8(0x3B), mine.textColorId, "textColorId")
        assertEquals(original.u16(0x3E), mine.iconId, "iconId")
        assertEquals(original.u16(0x40), mine.iconObjTileId, "iconObjTileId")
        assertEquals(original.u8(0x42), mine.iconPalId, "iconPalId")
        assertEquals(original.u8(0x44), mine.iconX, "iconX")
        assertEquals(original.u16(0x48), mine.soundId, "soundId")
    }

    @Test
    @VerifiesPort("PopupProc_WaitForPress")
    fun `positive clock counts down and breaks like the original`() {
        assumeTrue(ref.available, "reference capture unavailable")
        val run = ref.run("ram_trace_item_popup")
        // Only the positive-clock path is observable (the capture has no key wait). Align on
        // the first snapshot inside the wait loop (script index 9): its clock seeds the Kotlin
        // proc, then every later snapshot is one frame, i.e. one callback.
        val steps = (18570..18750).map { it to ProcArrayDump.ofEwram(dump(run, it)) }
            .mapNotNull { (step, dump) -> dump.find(Popup.PROC_SCRIPT)?.let { step to it } }
        val waitStart = steps.indexOfFirst { it.second.scrCur == 9 }
        assertTrue(waitStart >= 0)
        val mine = Popup.PopupProc()
        mine.clock = steps[waitStart].second.u32(0x30)
        var breakStep = -1
        var firstZero = -1
        for ((step, original) in steps.drop(waitStart + 1)) {
            if (mine.broken) {
                firstZero = if (original.scrCur != 9) step else -1
                break
            }
            Popup.waitForPress(mine) { 0 }
            if (mine.broken) breakStep = step
            if (original.scrCur == 9) {
                assertEquals(original.u32(0x30), mine.clock, "step $step clock")
            }
        }
        assertTrue(breakStep > 0, "never broke")
        assertEquals(0, mine.clock)
        // The original leaves the wait loop on the frame its callback breaks (index 9 -> 11
        // once the next frame runs the script), so it must have left by the step after.
        assertTrue(firstZero == breakStep + 1, "break $breakStep, left at $firstZero")
    }

    private fun dump(run: ReferenceData.Run, step: Int): ByteArray =
        try {
            run.region("r$step", "ewram")
        } catch (e: java.io.IOException) {
            run.region("f$step", "ewram")
        }

    private companion object {
        const val GOT_ITEM = 0x08592230
    }
}
