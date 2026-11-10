package fe8.testing

import fe8.game.ui.GotItemPopup
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The got-item proc against fakes (no ROM); not a ground-truth test. */
class GotItemPopupTest {
    private val engine = ProcEngine()
    private val log = ArrayList<String>()
    private var popupProc: Proc? = null
    private var faction = 0
    private val waitScript = ProcScript(
        "fakePopup",
        listOf(ProcScripts.repeat { }, ProcScripts.end()),
    )
    private val rootScript =
        ProcScript("root", listOf(ProcScripts.repeat { }, ProcScripts.end()))
    private val deps = GotItemPopup.Dependencies(
        newPopupSimple = { parent, def, clock, style ->
            log.add("popup ${def.toString(16)} $clock $style")
            popupProc = engine.startBlocking(waitScript, parent)
        },
        setPopupItem = { log.add("item $it") },
        unitFaction = { faction },
        setUnitDropItem = { log.add("drop $it") },
        makeNewItem = { it + 0x1000 },
        handleNewItemGetFromDrop = { u, i, _ -> log.add("get $u ${i.toString(16)}") },
    )
    private val popup = GotItemPopup(engine, deps)

    private fun frame(proc: Proc) = engine.run(proc)

    @Test
    fun `blue unit shows got item popup then receives the item`() {
        faction = 0x00
        val root = engine.start(rootScript, 0)
        val proc = popup.start(root, unit = 5, item = 0x21)!!
        assertEquals(1, root.lockCnt)
        assertTrue(log.isEmpty())
        frame(proc)
        assertEquals(listOf("item 33", "popup 8592230 96 0"), log)
        assertEquals(1, proc.lockCnt)
        frame(proc)
        assertEquals(2, log.size)
        engine.end(popupProc)
        assertEquals(0, proc.lockCnt)
        frame(proc)
        assertEquals("get 5 1021", log[2])
        assertEquals(1, root.lockCnt)
        frame(proc)
        assertEquals(0, root.lockCnt)
        assertEquals(3, log.size)
    }

    @Test
    fun `red unit gets the pilfered popup and drops the item`() {
        faction = 0x80
        val root = engine.start(rootScript, 0)
        val proc = popup.start(root, unit = 7, item = 0x05)!!
        assertEquals(listOf("drop 7"), log)
        frame(proc)
        assertEquals("popup 8592288 96 0", log.last())
        assertTrue("item 5" in log)
    }

    @Test
    fun `null parent starts a root proc`() {
        faction = 0x00
        val proc = popup.start(null, 1, 2)!!
        assertNull(proc.parent)
        assertEquals(3, proc.rootIndex)
    }
}
