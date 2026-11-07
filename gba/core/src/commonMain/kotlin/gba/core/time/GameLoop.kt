package gba.core.time

/**
 * Something that advances in fixed logic ticks and can be presented at any display rate.
 *
 * The split exists so that logic is deterministic (vanilla mode must be bit-exact against mGBA)
 * while presentation can run at 60/90/120 Hz or be skipped entirely in headless tests.
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
 * Call [frame] once per display refresh (e.g. from `Choreographer` or a GL render thread). Headless
 * tests and the differential harness call [Simulation.tick] directly instead.
 */
public class GameLoop(
    private val simulation: Simulation,
    private val clock: FixedStepClock = FixedStepClock(),
) {
    /** Logic ticks run so far. */
    public val ticks: Long get() = clock.ticks

    /** Runs as many ticks as wall-clock time demands, then presents once. Allocation-free. */
    public fun frame(nowNanos: Long) {
        val ticksDue = clock.advance(nowNanos)
        repeat(ticksDue) { simulation.tick() }
        simulation.present(clock.alpha)
    }
}
