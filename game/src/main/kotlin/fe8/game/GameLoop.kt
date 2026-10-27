package fe8.game

import gbafe.core.time.GameClock
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine

/**
 * The game's main loop, decomp `src/bm.c`: [onMain] (one game step: keys, then proc trees 1, 2, 3, 5, 4) and
 * [onVBlank] (game clock, proc tree 0, display sync once a step has finished), driven one hardware frame at a time
 * by [frame].
 *
 * A step that overruns its frame (on hardware: a screen load with decompression) misses the next VBlank's sync and
 * starts no step on the next frame; [overran] tells the loop after each step whether that happened (the original
 * timing is not modelled, so the caller decides from what the step did).
 *
 * Not modelled yet: sound (`m4aSoundVSync`, `m4aSoundMain`), sprites (`ClearSprites`, `PushSpriteLayerObjects`,
 * `SyncLoOam`, `SyncHiOam`), `FlushTiles`, `FlushLCDControl`.
 */
public class GameLoop(
    /** Proc trees run by the loop. */
    public val procs: ProcEngine,
    /** `gKeyStatusPtr`, updated at the start of every step. */
    public val keys: KeyStatus,
    /** `gGameClock`, incremented every VBlank. */
    public val clock: GameClock,
    /** Raw pressed keys (`REG_KEYINPUT`, active high) for the step about to run. */
    private val keyInput: () -> Int,
    /** True if the step that just ran overran its frame. */
    private val overran: () -> Boolean = { false },
    /** VBlank display sync after a finished step (`FlushLCDControl`, `FlushBackgrounds`, ...). */
    private val flush: () -> Unit = {},
) {
    /** `gBmSt.lock`: while not 0, proc tree 2 is not run. */
    public var lock: Int = 0
        private set

    /** `gBmSt.main_loop_ended`: a step finished and its display state waits for the VBlank sync. */
    public var mainLoopEnded: Boolean = false
        private set

    private var lagPending = false

    /**
     * Increments [lock].
     *
     * Ported from src/bm.c: LockGame
     */
    public fun lockGame() {
        lock = (lock + 1) and 0xFF
    }

    /**
     * Decrements [lock].
     *
     * Ported from src/bm.c: UnlockGame
     */
    public fun unlockGame() {
        lock = (lock - 1) and 0xFF
    }

    /**
     * The current [lock] count.
     *
     * Ported from src/bm.c: GetGameLock
     */
    public fun gameLock(): Int = lock

    /**
     * One game step: updates [keys], runs proc trees 1, 2 (unless locked), 3, 5 and 4, and marks the step ended.
     * Sprite layer pushes are not modelled.
     *
     * Ported from src/bm.c: OnMain
     */
    public fun onMain() {
        keys.update(keyInput())
        procs.run(procs.root(1))
        if (gameLock() == 0) procs.run(procs.root(2))
        procs.run(procs.root(3))
        procs.run(procs.root(5))
        procs.run(procs.root(4))
        mainLoopEnded = true
    }

    /** The VBlank interrupt (`OnVBlank`, partial): clock, proc tree 0, then [flush] if a step has ended. */
    public fun onVBlank() {
        clock.increment()
        procs.run(procs.root(0))
        if (mainLoopEnded) {
            mainLoopEnded = false
            flush()
        }
    }

    /**
     * One hardware frame: the VBlank, then a step unless the previous step overran (then that step only finishes
     * now, after this frame's VBlank, which therefore syncs nothing).
     */
    public fun frame() {
        if (lagPending) {
            lagPending = false
            mainLoopEnded = false
            onVBlank()
            mainLoopEnded = true
            return
        }
        onVBlank()
        onMain()
        lagPending = overran()
    }
}
