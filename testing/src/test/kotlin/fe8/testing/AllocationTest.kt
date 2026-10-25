package fe8.testing

import gbafe.core.event.TickListener
import gbafe.core.time.FixedStepClock
import gbafe.core.time.GameLoop
import fe8.game.Fe8Game
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** Enforces the zero-allocations-per-frame rule for the game loop and its hot services. */
class AllocationTest {
    @Test
    fun `game tick and present allocate nothing`() {
        assumeTrue(AllocationMeter.supported)
        val game = Fe8Game()
        var sum = 0L
        game.events.onTickStart.subscribe(TickListener { sum += it })
        val bytes = AllocationMeter.measure(warmup = 20_000, iterations = 10_000) {
            game.tick()
            game.present(0f)
        }
        assertEquals(0L, bytes, "bytes allocated over 10k frames")
    }

    @Test
    fun `game loop driving by wall clock allocates nothing`() {
        assumeTrue(AllocationMeter.supported)
        val game = Fe8Game()
        val loop = GameLoop(game, FixedStepClock())
        var now = 0L
        val bytes = AllocationMeter.measure(warmup = 20_000, iterations = 10_000) {
            now += 8_333_333 // 120 Hz display
            loop.frame(now)
        }
        assertEquals(0L, bytes)
    }

    @Test
    fun `rng draws allocate nothing`() {
        assumeTrue(AllocationMeter.supported)
        val game = Fe8Game()
        var acc = 0
        val bytes = AllocationMeter.measure(warmup = 50_000, iterations = 50_000) {
            acc += game.rng.next100()
            if (game.rng.roll2(50)) acc++
        }
        assertEquals(0L, bytes)
        assert(acc != Int.MIN_VALUE)
    }
}
