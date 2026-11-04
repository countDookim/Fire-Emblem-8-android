package fe8.testing

import fe8.game.Fe8Game
import gba.testkit.AllocationMeter
import java.io.File
import kotlin.system.exitProcess

/**
 * JVM frame-time benchmark of the game loop (tick + software present), run in CI on every push.
 *
 * Why on the JVM: it needs no device, so regressions are caught on every push. Absolute numbers on a
 * CI runner say little about a low-end phone; the budgets below are deliberately loose and exist to
 * catch order-of-magnitude regressions (an accidental O(n²), per-frame allocation, logging in a loop).
 * Tighten them as real systems land; record changes in docs/decisions.
 *
 * Writes a JSON report to the path given as the first argument and exits non-zero on budget overrun.
 */
public fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/reports/benchmark/frame_time.json")
    val game = Fe8Game()
    val frames = 20_000
    val samples = LongArray(frames)
    // Warm-up runs the *same* measured function, so JIT/OSR transitions (which allocate a few bytes
    // once) happen before measuring. The authoritative allocation check is AllocationTest.
    repeat(3) { runFrames(game, samples) }
    val allocBefore = AllocationMeter.current()
    runFrames(game, samples)
    val allocated = AllocationMeter.current() - allocBefore
    samples.sort()
    fun pct(p: Double) = samples[((frames - 1) * p).toInt()] / 1000.0
    val p50 = pct(0.50)
    val p99 = pct(0.99)
    val max = samples.last() / 1000.0
    val json = """{"frames":$frames,"p50_us":$p50,"p99_us":$p99,"max_us":$max,"allocated_bytes":$allocated,""" +
        """"budget_p50_us":$BUDGET_P50_US,"budget_p99_us":$BUDGET_P99_US}"""
    out.parentFile.mkdirs()
    out.writeText(json + "\n")
    println("frame-time benchmark: $json")
    val failures = buildList {
        if (p50 > BUDGET_P50_US) add("p50 ${p50}us > ${BUDGET_P50_US}us")
        if (p99 > BUDGET_P99_US) add("p99 ${p99}us > ${BUDGET_P99_US}us")
        if (allocated > 0) add("allocated $allocated bytes during measured frames")
    }
    if (failures.isNotEmpty()) {
        System.err.println("BENCHMARK FAILED: " + failures.joinToString("; "))
        exitProcess(1)
    }
}

private fun runFrames(game: Fe8Game, samples: LongArray) {
    for (i in samples.indices) {
        val t0 = System.nanoTime()
        game.tick()
        game.present(0f)
        samples[i] = System.nanoTime() - t0
    }
}

/** p50 budget per frame in microseconds (GBA frame is 16 743 µs). */
public const val BUDGET_P50_US: Double = 1_000.0

/** p99 budget per frame in microseconds. */
public const val BUDGET_P99_US: Double = 4_000.0
