package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import gbafe.core.rng.RngService
import java.io.File

/**
 * Boots the game headless with the ROM, presses A on the title and New Game stand-ins and runs
 * until the Prologue player phase idles (the beginning events have ended). Keys come from [keys]
 * once the idle phase is reached; before that A is pressed a few frames out of 20.
 */
internal class PrologueBoot(rom: File) {
    /**
     * Raw pressed keys during the idle phase, as a boxed Int created once by the caller: a
     * `() -> Int` lambda returning an `Int` above 127 would allocate an Integer per call, which
     * is the test's own cost (the game reads the value unboxed).
     */
    var keys: Any = 0
    private var idle = false
    private var frame = 0

    private val keySource: () -> Any = { if (idle) keys else if (frame % 20 in 5..8) A else 0 }

    /** The booted game. */
    val boot = Fe8Boot(
        RomView.ofRom(rom.readBytes()),
        @Suppress("UNCHECKED_CAST")
        (keySource as () -> Int),
        RngService(),
    )

    /** Runs to the idle Prologue phase; false if it was not reached. */
    fun reachIdle(): Boolean {
        while (frame < 3000 && boot.engine.find(boot.playerPhaseScript) == null) {
            boot.frame()
            frame++
        }
        idle = true
        var guard = 0
        while (boot.eventEngine.eventEngineExists() && guard++ < 6000) boot.frame()
        return boot.engine.find(boot.playerPhaseScript) != null
    }

    private companion object {
        const val A = 0x0001
    }
}
