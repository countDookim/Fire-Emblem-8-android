package gbafe.core

import gbafe.core.time.GameClock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GameClockTest {
    @Test
    fun `clock wraps from 999h back to 990h`() {
        val c = GameClock()
        c.set(215999999L)
        c.increment()
        assertEquals(213840000L, c.get())
    }
}
