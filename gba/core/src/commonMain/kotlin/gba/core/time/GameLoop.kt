package gba.core.time

/**
 * Something that advances in fixed logic ticks and can be presented at any display rate.
 *
 * The split exists so that logic is deterministic (vanilla mode is compared bit-for-bit against
 * mGBA reference data, `docs/decisions/0006-verification-policy.md`) while presentation can run at
 * 60/90/120 Hz or be skipped entirely in headless tests.
 */
public interface Simulation {
    /** Advances game logic by exactly one GBA frame. Must not allocate. */
    public fun tick()

    /**
     * Presents the current state. [alpha] is the fraction of the next tick already elapsed
     * (vanilla presentation ignores it). Must not allocate.
     */
    public fun present(alpha: Float)
}

/**
 * Drives a [Simulation] from a monotonic time source using a [FixedStepClock].
 *
 * Call [frame] once per display refresh, from whatever vsync-paced callback the host platform has
 * (nothing in this repo calls it from an Android `Choreographer` yet). Headless tests and the
 * differential harness call [Simulation.tick] directly instead.
 */
public class GameLoop(
    private val simulation: Simulation,
    private val clock: FixedStepClock = FixedStepClock(),
) {
    /** Logic ticks run so far (one tick = one GBA frame of game logic). */
    public val ticks: Long get() = clock.ticks

    /**
     * Runs as many ticks as [nowNanos] (monotonic time) demands, at most the clock's catch-up cap,
     * then presents once with the leftover fraction of a tick. Allocation-free.
     */
    public fun frame(nowNanos: Long) {
        val ticksDue = clock.advance(nowNanos)
        repeat(ticksDue) { simulation.tick() }
        simulation.present(clock.alpha)
    }
}
