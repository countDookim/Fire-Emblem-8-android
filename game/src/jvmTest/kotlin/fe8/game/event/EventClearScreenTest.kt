package fe8.game.event

import java.lang.reflect.Proxy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Call order and state effects of `Event22_ClearScreen` against a recording host. */
class EventClearScreenTest {
    @Test
    fun `clear screen calls the host in source order and unlocks the graphics`() {
        val calls = mutableListOf<String>()
        val host = Proxy.newProxyInstance(
            EventTextHost::class.java.classLoader, arrayOf(EventTextHost::class.java),
        ) { _, method, args ->
            calls += method.name + (args?.firstOrNull()?.takeIf { it is Int }?.let { "($it)" } ?: "")
            if (method.name == "enableMapDisp") {
                val proc = args!![0] as EventEngineProc
                proc.evStateBits = proc.evStateBits and EventConst.EV_STATE_GFXLOCKED.inv()
                EventConst.EVC_ADVANCE_YIELD
            } else {
                null
            }
        } as EventTextHost
        val text = EventText(EventState(), ScriptMemory { 0 }, host)
        val proc = EventEngineProc()
        proc.mapSpritePalIdOverride = 7
        proc.evStateBits = EventConst.EV_STATE_GFXLOCKED
        val result = text.event22ClearScreen.run(proc)
        assertEquals(
            listOf(
                "refreshBMapGraphics", "changeUnitSpritePalette(7)", "clearTalkTilemaps",
                "clearTalkBubble", "resetAllFaces", "enableMapDisp",
            ),
            calls,
        )
        assertEquals(0, proc.evStateBits and EventConst.EV_STATE_GFXLOCKED)
        assertEquals(EventConst.EVC_ADVANCE_YIELD, result)
    }
}
