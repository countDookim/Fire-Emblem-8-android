package fe8.testing

import fe8.game.ui.GotItemPopup
import fe8.game.ui.Popup
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** `ProcScr_GotItem` against the item popup capture, frame by frame. */
class GotItemPopupDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort("ItemGot_DisplayLePopup", "ItemGot_GotLeItem")
    fun `got item proc lifetime tree and fields match the traced run`() {
        assumeTrue(ref.available, "reference capture unavailable")
        val run = ref.run("ram_trace_item_popup")
        val frames = (FIRST..LAST).map { it to ProcArrayDump.ofEwram(dump(run, it)) }
        val got = frames.mapNotNull { (s, d) ->
            d.find(GotItemPopup.PROC_SCR_GOT_ITEM)?.let { s to it }
        }
        assertTrue(got.isNotEmpty(), "ProcScr_GotItem never appears in the trace")
        val firstStep = got.first().first
        val lastStep = got.last().first
        val popupSteps = frames.filter { it.second.find(Popup.PROC_SCRIPT) != null }.map { it.first }
        val lastPopupStep = popupSteps.last()
        val first = got.first().second
        val parent = frames.first { it.first == firstStep }.second.live
            .single { it.address == first.u32(0x14) }
        val unit = first.u32(0x54)
        val item = first.u32(0x58)
        val iwram = try {
            run.region("r$firstStep", "iwram")
        } catch (e: java.io.IOException) {
            run.region("f$firstStep", "iwram")
        }
        val popupItem = (iwram[GPOPUPITEM].toInt() and 0xFF) or
            ((iwram[GPOPUPITEM + 1].toInt() and 0xFF) shl 8)
        assertEquals(popupItem, item, "traced proc item equals gPopupItem")
        assertTrue(unit != 0, "traced unit")

        // Mine: an event-engine stand-in blocked by the got-item proc, and a fake popup child
        // that (like Popup) ends itself on the traced frame.
        val engine = ProcEngine()
        var step = firstStep
        var popup: Proc? = null
        val calls = ArrayList<List<Int>>()
        val popupScript = ProcScript(
            "fakePopup",
            listOf(
                ProcScripts.repeat { if (step > lastPopupStep) engine.end(it) },
                ProcScripts.end(),
            ),
        )
        val deps = GotItemPopup.Dependencies(
            newPopupSimple = { p, def, clock, style ->
                calls.add(listOf(def, clock, style))
                popup = engine.startBlocking(popupScript, p)
            },
            setPopupItem = { calls.add(listOf(-1, it)) },
            unitFaction = { 0 },
            setUnitDropItem = { },
            makeNewItem = { it },
            handleNewItemGetFromDrop = { u, i, _ -> calls.add(listOf(-2, u, i)) },
        )
        val gotItem = GotItemPopup(engine, deps)
        val root = engine.start(
            ProcScript("event", listOf(ProcScripts.repeat { }, ProcScripts.end())), 0,
        )
        val mine = gotItem.start(root, unit, item)!!
        // The trace first shows the proc after its second frame (popup child already there).
        engine.run(root)
        engine.run(root)
        for ((s, frame) in frames.filter { it.first in firstStep..lastStep + 1 }) {
            step = s
            if (s > firstStep) engine.run(root)
            val original = frame.find(GotItemPopup.PROC_SCR_GOT_ITEM)
            val tag = "step $s"
            if (original == null) {
                assertNull(mine.script, "$tag ended")
                assertEquals(0, root.lockCnt, "$tag parent lock")
                continue
            }
            assertNotNull(mine.script, "$tag alive")
            assertEquals(original.scrCur, mine.scrCur, "$tag scrCur")
            assertEquals(original.u8(0x28), mine.lockCnt, "$tag lockCnt")
            assertEquals(frame.live.single { it.address == original.u32(0x14) }.u8(0x28),
                root.lockCnt, "$tag parent lockCnt")
            val child = frame.find(Popup.PROC_SCRIPT)
            assertEquals(child != null, popup?.script != null, "$tag popup alive")
            if (child != null) assertEquals(original.address, child.u32(0x14), "$tag popup parent")
        }
        assertEquals(parent.address, first.u32(0x14))
        assertEquals(
            listOf(listOf(-1, item), listOf(GotItemPopup.POPUP_SCR_GOT_ITEM, 0x60, 0),
                listOf(-2, unit, item)),
            calls,
        )
        val tracedPopup = frames.first { it.first == firstStep }.second.find(Popup.PROC_SCRIPT)!!
        assertEquals(GotItemPopup.POPUP_SCR_GOT_ITEM, tracedPopup.u32(0x2C), "traced pDefinition")
        assertEquals(lastStep + 1, got.size + firstStep, "contiguous lifetime")
    }

    private fun dump(run: ReferenceData.Run, step: Int): ByteArray =
        try {
            run.region("r$step", "ewram")
        } catch (e: java.io.IOException) {
            run.region("f$step", "ewram")
        }

    private companion object {
        const val FIRST = 18570
        const val LAST = 18750

        /** `gPopupItem` (0x030005F4) in the IWRAM dump. */
        const val GPOPUPITEM = 0x5F4
    }
}
