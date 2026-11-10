package fe8.game.event

import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** `Event37_GiveItem` subcommand 0 with and without the item-got popup hook. */
class EventGiveItemTest {
    private val state = EventState().also { it.slots[3] = 0x21 }
    private val memory = ScriptMemory { address -> if (address == CMD) 0x0030 else 0x0005 }

    private fun proc() = EventEngineProc().also { it.pEventCurrent = CMD }

    @Test
    fun `hook is called with the event proc, unit and slot 3 and the item is not added`() {
        val engine = ProcEngine()
        val parent = engine.start(ProcScript("p", listOf(ProcScripts.end())), 0)
        val calls = mutableListOf<List<Any>>()
        val added = mutableListOf<Int>()
        val give = EventGiveItem(
            state, memory, { it }, { _, i -> added += i; true }, { it }, { 0 }, { },
            { p, u, i -> calls += listOf(p, u, i) },
        )
        val ev = proc().also { it.proc = parent }
        assertEquals(EventConst.EVC_ADVANCE_YIELD, give.event37GiveItem.run(ev))
        assertEquals(listOf<List<Any>>(listOf(parent, 5, 0x21)), calls)
        assertEquals(emptyList<Int>(), added)
    }

    @Test
    fun `without hook the item is added at once`() {
        val added = mutableListOf<Int>()
        val give = EventGiveItem(
            state, memory, { it }, { _, i -> added += i; true }, { it + 1 }, { 0 }, { },
        )
        give.event37GiveItem.run(proc())
        assertEquals(listOf(0x22), added)
    }

    private companion object {
        const val CMD = 0x1000
    }
}
