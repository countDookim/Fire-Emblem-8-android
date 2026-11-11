package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import gbafe.core.rng.RngService
import java.io.File

/** Functional interface determining whether button A should be pressed on [frame]. */
fun interface Pulse {
    /** Whether A should be pressed on [frame] (from 0). */
    fun shouldPressA(frame: Int): Boolean
}

/**
 * Boots the game headless with the ROM, presses A on the title and New Game stand-ins and runs
 * until the Prologue player phase idles (the beginning events have ended). Keys come from [keys]
 * once the idle phase is reached; before that A is pressed a few frames out of 20.
 */
internal class PrologueBoot(
    rom: File,
    /** Whether A is pressed on [frame] (from 0) before the idle phase. */
    private val pulse: Pulse = Pulse { it % 20 in 5..8 },
) {
    /**
     * Raw pressed keys during the idle phase, as a boxed Int created once by the caller: a
     * `() -> Int` lambda returning an `Int` above 127 would allocate an Integer per call, which
     * is the test's own cost (the game reads the value unboxed).
     */
    var keys: Any = ZERO
    /** Called after every frame run by [reachIdle], with the frame number (from 1). */
    var onFrame: ((Int) -> Unit)? = null
    var idle: Boolean = false
    var frame: Int = 0

    private val keySource: () -> Any = { if (idle) keys else if (pulse.shouldPressA(frame)) A else ZERO }

    /** The booted game. */
    val boot = Fe8Boot(
        RomView.ofRom(rom.readBytes()),
        @Suppress("UNCHECKED_CAST")
        (keySource as () -> Int),
        RngService(),
    )

    /** Advances the game by [frames] ticks. */
    fun advance(frames: Int = 1) {
        repeat(frames) {
            boot.frame()
            frame++
            onFrame?.invoke(frame)
        }
    }

    /**
     * Runs to the idle Prologue phase; false if it was not reached or the event engine has not
     * ended.
     */
    fun reachIdle(): Boolean {
        // The beginning events show their dialogue now: A keeps paging it until they end.
        while (frame < 12000 && boot.engine.find(boot.playerPhaseScript) == null) {
            advance()
        }
        var guard = 0
        while (boot.eventEngine.eventEngineExists() && guard++ < 12000) {
            advance()
        }
        idle = true
        return !boot.eventEngine.eventEngineExists() &&
            boot.engine.find(boot.playerPhaseScript) != null
    }

    companion object {
        val A: Any = 0x0001
        val ZERO: Any = 0
    }
}
