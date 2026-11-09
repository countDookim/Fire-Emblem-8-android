package fe8.testing

import fe8.game.Fe8Game
import gba.testkit.AllocationMeter
import java.io.File
import kotlin.system.exitProcess

/**
 * JVM frame-time benchmark of the game loop (tick + software present), run in CI on every push.
 *
 * Why on the JVM: it needs no device, so regressions are caught on every push. Absolute numbers on
 * a CI runner say little about a low-end phone; the budgets below are deliberately loose and exist
 * to catch order-of-magnitude regressions (accidental O(n²), per-frame allocation, logging in a
 * loop). Tighten them as real systems land; record changes in docs/decisions.
 *
 * Writes a JSON report to the path given as the first argument and exits non-zero on budget
 * overrun.
 */
public fun main(args: Array<String>) {
    val reportFile = File(args.firstOrNull() ?: "build/reports/benchmark/frame_time.json")
    val game = Fe8Game()
    val frameCount = 20_000
    val sampleDurationsNano = LongArray(frameCount)
    // Warm-up runs the *same* measured function, so JIT/OSR transitions (which allocate a few bytes
    // once) happen before measuring. The authoritative allocation check is AllocationTest.
    repeat(3) { runFrames(game, sampleDurationsNano) }
    val allocatedBytesBefore = AllocationMeter.current()
    runFrames(game, sampleDurationsNano)
    val allocatedBytes = AllocationMeter.current() - allocatedBytesBefore
    sampleDurationsNano.sort()
    fun percentileMicros(percentile: Double): Double =
        sampleDurationsNano[((frameCount - 1) * percentile).toInt()] / 1_000.0
    val p50Micros = percentileMicros(0.50)
    val p99Micros = percentileMicros(0.99)
    val maxMicros = sampleDurationsNano.last() / 1_000.0
    val json = """{"frames":$frameCount,"p50_us":$p50Micros,"p99_us":$p99Micros,""" +
        """"max_us":$maxMicros,"allocated_bytes":$allocatedBytes,""" +
        """"budget_p50_us":$BUDGET_P50_US,"budget_p99_us":$BUDGET_P99_US}"""
    reportFile.parentFile.mkdirs()
    reportFile.writeText(json + "\n")
    println("frame-time benchmark: $json")
    val failures = buildList {
        if (p50Micros > BUDGET_P50_US) add("p50 ${p50Micros}us > ${BUDGET_P50_US}us")
        if (p99Micros > BUDGET_P99_US) add("p99 ${p99Micros}us > ${BUDGET_P99_US}us")
        if (allocatedBytes > 0) add("allocated $allocatedBytes bytes during measured frames")
    }
    if (failures.isNotEmpty()) {
        System.err.println("BENCHMARK FAILED: " + failures.joinToString("; "))
        exitProcess(1)
    }
}

/**
 * Runs [samples.size] game frames (calling [Fe8Game.tick] and [Fe8Game.present]), recording
 * each frame's wall duration in nanoseconds into [samples].
 */
private fun runFrames(game: Fe8Game, samples: LongArray) {
    for (frameIndex in samples.indices) {
        val frameStartNano = System.nanoTime()
        game.tick()
        game.present(0f)
        samples[frameIndex] = System.nanoTime() - frameStartNano
    }
}

/** p50 budget per frame in microseconds (GBA frame is 16 743 µs). */
public const val BUDGET_P50_US: Double = 1_000.0

/** p99 budget per frame in microseconds. */
public const val BUDGET_P99_US: Double = 4_000.0
