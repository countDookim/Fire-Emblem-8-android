package gba.core.time

/**
 * Fixed-timestep clock that converts wall-clock time into a whole number of logic ticks at the GBA
 * refresh rate (2^24 Hz / 280 896 cycles per frame ≈ 59.7275 Hz), independent of the display rate.
 *
 * Why exact rational arithmetic: storing the step as rounded nanoseconds (16 742 706 ns) would
 * drift by ~0.3 ns per frame. Instead the accumulator is kept in units of `ns × 2^24`, and one
 * tick costs `280 896 × 10^9` of those units, so the long-run tick rate is exactly the hardware
 * rate.
 *
 * Why a catch-up cap: after a stall (app backgrounded, GC, debugger) we must not run hundreds of
 * ticks in one display frame ("spiral of death"). At most [maxStepsPerAdvance] ticks are produced
 * per call and the excess time is dropped. Vanilla determinism is unaffected because game logic
 * only ever sees ticks, never wall-clock time.
 *
 * Allocation-free: all state is primitive.
 *
 * @param maxStepsPerAdvance upper bound on ticks returned by one [advance] call.
 */
public class FixedStepClock(private val maxStepsPerAdvance: Int = 4) {
    /** Monotonic time of the previous [advance] call, or [NOT_STARTED] before the first. */
    private var lastNanos: Long = NOT_STARTED
    /** Elapsed time not yet turned into ticks, in `ns × 2^24` units (see class doc). */
    private var accumulator: Long = 0L

    /** Total ticks produced since construction (the logic frame counter). */
    public var ticks: Long = 0L
        private set

    /**
     * Feeds the current monotonic time and returns how many logic ticks should run now.
     * The first call only establishes the time base and returns 0.
     */
    public fun advance(nowNanos: Long): Int {
        if (lastNanos == NOT_STARTED) {
            lastNanos = nowNanos
            return 0
        }
        var elapsedNanos = nowNanos - lastNanos
        lastNanos = nowNanos
        // Ignore a clock that went backwards; clamp long stalls to the catch-up cap.
        if (elapsedNanos < 0) elapsedNanos = 0
        val maxElapsedNanos = maxStepsPerAdvance * NANOS_PER_TICK_CEIL
        if (elapsedNanos > maxElapsedNanos) elapsedNanos = maxElapsedNanos
        accumulator += elapsedNanos * TIME_SCALE
        var stepsDue = 0
        while (accumulator >= UNITS_PER_TICK && stepsDue < maxStepsPerAdvance) {
            accumulator -= UNITS_PER_TICK
            stepsDue++
        }
        // Capped: drop whole ticks still owed, keep only the sub-tick remainder.
        if (stepsDue == maxStepsPerAdvance && accumulator >= UNITS_PER_TICK) {
            accumulator %= UNITS_PER_TICK
        }
        ticks += stepsDue
        return stepsDue
    }

    /** Fraction (0..1) of the next tick already elapsed; renderers may use it to interpolate. */
    public val alpha: Float get() = (accumulator.toDouble() / UNITS_PER_TICK).toFloat()

    /** GBA timing constants that define the fixed logic rate (~59.73 Hz). */
    public companion object {
        /** GBA master clock, Hz (2^24). */
        public const val CPU_HZ: Long = 16_777_216L

        /** CPU cycles per video frame (228 lines × 1232 cycles). */
        public const val CYCLES_PER_FRAME: Long = 280_896L

        /** Logic rate in Hz (≈ 59.7275), for diagnostics only; ticking uses exact integers. */
        public const val TICK_HZ: Double = CPU_HZ.toDouble() / CYCLES_PER_FRAME

        /** Accumulator units per nanosecond (the CPU frequency, so ticks stay exact). */
        private const val TIME_SCALE: Long = CPU_HZ
        /** Accumulator units that make up one logic tick. */
        private const val UNITS_PER_TICK: Long = CYCLES_PER_FRAME * 1_000_000_000L
        /** Nanoseconds per tick, rounded up; only used to size the catch-up cap. */
        private const val NANOS_PER_TICK_CEIL: Long = (UNITS_PER_TICK + TIME_SCALE - 1) / TIME_SCALE
        /** Sentinel for "[advance] has not been called yet". */
        private const val NOT_STARTED: Long = Long.MIN_VALUE
    }
}
