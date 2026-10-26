package gbafe.input

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UiElementsTest {
    @Test
    fun `hit test returns the top-most containing element`() {
        val ui = UiElements(capacity = 3)
        assertEquals(0, ui.add(10, 10, 100, 16, GameAction.SelectItem.of(0), id = 7))
        assertEquals(1, ui.add(10, 26, 100, 16, GameAction.SelectItem.of(1)))
        assertEquals(2, ui.add(0, 0, 240, 160, GameAction.Cancel)) // full-screen element on top
        assertEquals(-1, ui.add(0, 0, 1, 1, GameAction.Confirm)) // over capacity: dropped
        assertEquals(2, ui.hitTest(20, 30))
        ui.clear()
        ui.add(10, 10, 100, 16, GameAction.SelectItem.of(0), id = 7)
        ui.add(10, 26, 100, 16, GameAction.SelectItem.of(1))
        assertEquals(0, ui.hitTest(10, 10))
        assertEquals(1, ui.hitTest(109, 41))
        assertEquals(-1, ui.hitTest(110, 30)) // right edge is exclusive
        assertEquals(-1, ui.hitTest(5, 5))
        assertEquals(GameAction.SelectItem.of(1), ui.action[1])
        assertEquals(7, ui.id[0])
        ui.clear()
        assertEquals(0, ui.count)
        assertNull(ui.action[0])
    }
}
