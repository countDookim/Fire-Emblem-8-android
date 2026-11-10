package fe8.testing

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.event.EventEngineProc
import fe8.game.event.ScriptMemory
import fe8.game.worldmap.WmBigMapHooks
import fe8.game.worldmap.WorldMapBigMapEvents
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorldMapBigMapEventsTest {
    private val words = IntArray(8)
    private val calls = mutableListOf<String>()
    private var exists = false
    private var borderExists = false
    private val proc = EventEngineProc().also { it.pEventCurrent = BASE }
    private val events = WorldMapBigMapEvents(
        ScriptMemory { a -> words[(a - BASE) / 2] },
        WmBigMapHooks(
            startGmapRm = { x, y, c -> calls += "rm $x $y $c" },
            startGmapRmUpdateExt = { a, b, c, d, e, f -> calls += "ext $a $b $c $d $e $f" },
            gmapRmEndAll = { calls += "endAll" },
            gmapRmSetUnblocked = { calls += "unblock" },
            gmapRmExists = { exists },
            showNationHighlightedMap = { calls += "show $it" },
            endGmapRmBorder1 = { calls += "end $it" },
            sub80C2BC4 = { borderExists },
            sub80C2C10 = { calls += "flag $it" },
        ),
    )

    private fun skip() {
        proc.evStateBits = EV_STATE_SKIPPING
    }

    @Test
    fun `B4 decodes signed positions and the little endian mask`() {
        words[2] = 0xFFF0
        words[3] = 5
        words[4] = 0x1234
        words[5] = 0x0001
        assertEquals(EVC_ADVANCE_CONTINUE, events.eventB4WmDisplayBigMap.run(proc))
        assertEquals(listOf("rm -16 5 ${0x11234}"), calls)
    }

    @Test
    fun `B4 does nothing while skipping`() {
        skip()
        assertEquals(EVC_ADVANCE_CONTINUE, events.eventB4WmDisplayBigMap.run(proc))
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `B6 decodes six signed words`() {
        for (i in 2..7) words[i] = 0xFFFF - (i - 2)
        events.eventB6WmMoveBigMap.run(proc)
        assertEquals(listOf("ext -1 -2 -3 -4 -5 -6"), calls)
    }

    @Test
    fun `B5 and B7 follow the skipping and exists paths`() {
        events.eventB5WmHideBigMap.run(proc)
        exists = true
        assertEquals(EVC_STOP_YIELD, events.eventB7WmBigMapWait.run(proc))
        exists = false
        assertEquals(EVC_ADVANCE_CONTINUE, events.eventB7WmBigMapWait.run(proc))
        skip()
        events.eventB5WmHideBigMap.run(proc)
        exists = true
        assertEquals(EVC_ADVANCE_CONTINUE, events.eventB7WmBigMapWait.run(proc))
        assertEquals(listOf("unblock", "endAll", "endAll"), calls)
    }

    @Test
    fun `B8 B9 and BA read the nation and honour skipping`() {
        words[2] = 3
        events.eventB8WmShowBigMapHighlight.run(proc)
        events.eventB9WmRemoveHighlightNationPart1.run(proc)
        assertEquals(EVC_ADVANCE_YIELD, events.eventBAWmRemoveHighlightNationPart2.run(proc))
        borderExists = true
        assertEquals(EVC_STOP_YIELD, events.eventBAWmRemoveHighlightNationPart2.run(proc))
        skip()
        events.eventB8WmShowBigMapHighlight.run(proc)
        events.eventB9WmRemoveHighlightNationPart1.run(proc)
        assertEquals(EVC_ADVANCE_CONTINUE, events.eventBAWmRemoveHighlightNationPart2.run(proc))
        assertEquals(listOf("show 3", "flag 3", "end -1", "end 3"), calls)
    }

    private companion object {
        const val BASE = 0x1000
    }
}
