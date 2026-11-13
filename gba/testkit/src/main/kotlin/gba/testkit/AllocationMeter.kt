package gba.testkit

import java.lang.management.ManagementFactory

/**
 * Counts heap bytes allocated by the calling thread, using HotSpot's per-thread allocation counter
 * (`com.sun.management.ThreadMXBean`), so tests can assert "zero allocations per frame" on the JVM
 * in CI without a device or an agent. Only the calling thread is counted: the measured code must
 * run on it. Tests skip (`assumeTrue(AllocationMeter.supported)`) where the counter is missing.
 *
 * Two ways to use it. For a loop, [measureMin] (or [measure]) wraps a block. For a long
 * deterministic run (a boot replayed frame by frame, as in WorldMapBootTest and
 * PlayerPhaseAllocationTest) a test reads [current] around each frame, runs the whole boot twice
 * and counts a frame as allocating only if it allocates in both passes: a one-off JIT
 * deoptimization re-creates objects that HotSpot had scalar-replaced (88..96 bytes in one frame
 * in WorldMapBootTest) and does not repeat in the second pass, while real allocation does.
 *
 * Caveat (not checked here: ART behaviour): HotSpot escape analysis may eliminate allocations that
 * ART would perform, so a JVM zero is necessary but not sufficient. This meter catches
 * regressions such as boxing, iterators, capturing lambdas or string building.
 */
public object AllocationMeter {
    /** Default number of measured rounds for [measureMin]. */
    public const val DEFAULT_MEASURE_ROUNDS: Int = 3

    /** HotSpot's thread MXBean; the cast fails on a JVM without the `com.sun.management` API. */
    private val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** True if the JVM supports and has enabled per-thread allocated-bytes accounting. */
    public val supported: Boolean = bean.isThreadAllocatedMemorySupported && bean.isThreadAllocatedMemoryEnabled

    /** Total bytes allocated so far by the calling thread; subtract two readings for a span. */
    public fun current(): Long = bean.currentThreadAllocatedBytes

    /**
     * Runs [block] [warmup] times unmeasured (JIT warm-up, lazy init), then [iterations] times
     * measured. Returns the bytes the calling thread allocated during the measured part.
     */
    public inline fun measure(warmup: Int, iterations: Int, block: () -> Unit): Long {
        repeat(warmup) { block() }
        val before = current()
        repeat(iterations) { block() }
        return current() - before
    }

    /**
     * Like [measure], but repeats the measured part [rounds] times and returns the smallest count.
     * An allocation the code really makes shows up in every round; a one-off runtime allocation
     * (e.g. a JIT/OSR transition while the code is being compiled) only in one, so a
     * zero-allocation check does not flake.
     */
    public inline fun measureMin(
        warmup: Int,
        iterations: Int,
        rounds: Int = DEFAULT_MEASURE_ROUNDS,
        block: () -> Unit,
    ): Long {
        repeat(warmup) { block() }
        var best = Long.MAX_VALUE
        repeat(rounds) {
            val before = current()
            repeat(iterations) { block() }
            best = minOf(best, current() - before)
        }
        return best
    }
}
