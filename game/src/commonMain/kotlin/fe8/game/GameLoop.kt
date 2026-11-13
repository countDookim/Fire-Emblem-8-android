package fe8.game

import fe8.game.bm.BmSt
import gbafe.core.time.GameClock
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers

/**
 * The game's main loop, decomp `src/bm.c`: [onMain] (one game step: keys, then proc trees 1, 2, 3,
 * 5, 4) and [onVBlank] (game clock, proc tree 0, display sync once a step has finished), driven one
 * hardware frame at a time by [frame].
 *
 * A step that overruns its frame (on hardware: a screen load with decompression) misses the next
 * VBlank's sync and starts no step on the next frame(s); [overran] tells the loop after each step
 * whether that happened (the original timing is not modelled, so the caller decides from what the
 * step did).
 *
 * Not modelled: `INTR_CHECK`, the `gBmSt.prevVCount` store and `VBlankIntrWait` at the end of
 * `OnMain` (the caller paces the frames). `FlushLCDControl`, `FlushBackgrounds` and `FlushTiles`
 * are left to [flush].
 */
public class GameLoop(
    /** Proc trees run by the loop. */
    public val procs: ProcEngine,
    /** `gKeyStatusPtr`, updated (`UpdateKeyStatus`) at the start of every step. */
    public val keys: KeyStatus,
    /** `gGameClock`, incremented every VBlank. */
    public val clock: GameClock,
    /** Raw pressed keys (`REG_KEYINPUT`, active high) for the step about to run. */
    private val keyInput: () -> Int,
    /** How many VBlanks the step that just ran missed (0: it finished within its frame). */
    private val overran: () -> Int = { 0 },
    /**
     * VBlank display sync after a finished step (`FlushLCDControl`, `FlushBackgrounds`,
     * `FlushTiles`).
     */
    private val flush: () -> Unit = {},
    /** The sprite lists cleared at the start of every step and pushed after trees 5 and 4. */
    public val sprites: SpriteLayers = SpriteLayers(),
    /** `gOam` and hardware OAM: sprites are pushed into it by [onMain] and synced by [onVBlank]. */
    public val oam: OamBuffer = OamBuffer(),
    /** `m4aSoundVSync` (start of the VBlank interrupt, after the clock). */
    private val soundVSync: () -> Unit = {},
    /** `m4aSoundMain` (end of the VBlank interrupt). */
    private val soundMain: () -> Unit = {},
) {
    /** `gBmSt`, the game state struct shared by everything that models its fields. */
    public val bmSt: BmSt = BmSt()

    /** `gBmSt.lock` (`LockGame` / `UnlockGame` count): while not 0, proc tree 2 is not run. */
    public var lock: Int
        get() = bmSt.lock and 0xFF
        private set(value) {
            bmSt.lock = value.toByte().toInt()
        }

    /** `gBmSt.main_loop_ended`: a step finished and its display state waits for the VBlank sync. */
    public var mainLoopEnded: Boolean
        get() = bmSt.mainLoopEnded != 0
        private set(value) {
            bmSt.mainLoopEnded = if (value) 1 else 0
        }

    /** VBlanks left until the overrunning step finishes; 0 when no step is overrunning. */
    private var lagFrames = 0

    /** Callbacks queued by [afterStep] for the end of the current step. */
    private val stepEnd = ArrayList<() -> Unit>(4)

    /**
     * Runs [action] when the current step finishes: at the end of [onMain], or, if the step
     * overruns, after the VBlank of the last frame it overran into (code that ran after a long
     * decompression on hardware).
     */
    public fun afterStep(action: () -> Unit) {
        stepEnd += action
    }

    /** Dispatches and clears all callbacks queued via [afterStep]. */
    private fun finishStep() {
        if (stepEnd.isEmpty()) return
        for (action in stepEnd) action()
        stepEnd.clear()
    }

    /** True if the next [frame] runs no step: the previous step is still overrunning. */
    public val overrunning: Boolean get() = lagFrames > 0

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
     * One game step: updates [keys], clears [sprites], runs proc trees 1, 2 (unless locked), 3,
     * 5 and 4 (pushing sprite layers 0 and 13 after 5 and 4), and marks the step ended.
     *
     * Partially ported from src/bm.c: OnMain (`prevVCount` and `VBlankIntrWait` are not modelled)
     */
    public fun onMain() {
        keys.update(keyInput())
        sprites.clear()
        procs.run(procs.root(1))
        if (gameLock() == 0) procs.run(procs.root(2))
        procs.run(procs.root(3))
        procs.run(procs.root(5))
        sprites.pushLayerObjects(0, pushSprite)
        procs.run(procs.root(4))
        sprites.pushLayerObjects(13, pushSprite)
        mainLoopEnded = true
    }

    /** `CallARM_PushToSecondaryOAM` for one sprite (`PutOamHi`). */
    private val pushSprite: (SpriteLayers.Entry) -> Unit = { entry ->
        oam.putHi(entry.oam1, entry.oam0, entry.obj!!.data, entry.oam2)
    }

    /**
     * The VBlank interrupt (`OnVBlank`, partial): `IncrementGameClock`, `m4aSoundVSync`, proc tree
     * 0, `SyncLoOam`, then, if a step has ended (`main_loop_ended`), [flush] and `SyncHiOam`, then
     * `m4aSoundMain`.
     */
    public fun onVBlank() {
        clock.increment()
        soundVSync()
        procs.run(procs.root(0))
        oam.syncLo()
        if (mainLoopEnded) {
            mainLoopEnded = false
            flush()
            oam.syncHi()
        }
        soundMain()
    }

    /**
     * One hardware frame: the VBlank, then a step unless the previous step overran (then that step
     * is still running during this frame's VBlank, which therefore syncs nothing; it finishes in
     * the last frame it overran into).
     */
    public fun frame() {
        if (lagFrames > 0) {
            lagFrames--
            mainLoopEnded = false
            onVBlank()
            mainLoopEnded = lagFrames == 0
            if (lagFrames == 0) finishStep()
            return
        }
        onVBlank()
        onMain()
        lagFrames = overran()
        if (lagFrames == 0) finishStep()
    }
}
