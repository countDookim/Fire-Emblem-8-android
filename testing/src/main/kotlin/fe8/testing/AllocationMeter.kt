package fe8.testing

import java.lang.management.ManagementFactory

/**
 * Counts heap bytes allocated by the current thread, using HotSpot's per-thread allocation counter
 * (`com.sun.management.ThreadMXBean`). This lets us enforce "zero allocations per frame" on the JVM in
 * CI without a device or an agent.
 *
 * Caveat: HotSpot escape analysis may eliminate allocations that ART would perform, so a JVM zero is a
 * necessary but not sufficient condition. Keep hot paths free of `new` by construction; this meter
 * catches regressions such as boxing, iterators, lambdas capturing state or string building.
 */
public object AllocationMeter {
    private val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** True if the JVM supports per-thread allocation accounting. */
    public val supported: Boolean = bean.isThreadAllocatedMemorySupported && bean.isThreadAllocatedMemoryEnabled

    /** Bytes allocated so far by the current thread. */
    public fun current(): Long = bean.currentThreadAllocatedBytes

    /**
     * Runs [block] [warmup] times untimed (JIT warm-up, lazy init), then [iterations] times measured.
     * Returns the bytes allocated during the measured part.
     */
    public inline fun measure(warmup: Int, iterations: Int, block: () -> Unit): Long {
        repeat(warmup) { block() }
        val before = current()
        repeat(iterations) { block() }
        return current() - before
    }
}
