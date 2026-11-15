package fe8.game.bm

import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.endIfDuplicate
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.render.BgHardware

/** `struct BmxfadeProc` past `PROC_HEADER` (src/bmxfade.c): what the fade keeps. */
public class BmxFadeState {
    /** `counter` (s16, 0x4C): 16 at the start, counts down to -1 (the end of the fade). */
    public var counter: Int = 0

    /** `game_lock` (s16, 0x4E): non-zero when the fade locked the game and must unlock it. */
    public var gameLock: Int = 0
}

/**
 * The map fade of src/bmxfade.c: `sProcScr_BMXFADE` (a duplicate ends at once). BG2 holds the map
 * as it was before a change ([BmMapRender.renderBmMapOnBg2]) and BG3 the new one; the fade blends
 * BG2 (first target) into BG3 (second target) over 17 frames, then clears BG2 and the blend.
 * [newBmxFade] starts it, [doesBmxFadeExist] is what the procs that wait for it poll. Nothing
 * allocates per frame.
 */
public class BmxFade(
    private val engine: ProcEngine,
    /** `gBmSt`: `lock`. */
    private val bm: BmSt,
    /** `SetAllUnitNotBackSprite` at the end of the fade. */
    private val units: UnitTable,
    /** `gLCDControlBuffer` (blend, BG priorities), BG2's tilemap buffer, tile data and sync. */
    private val hardware: BgHardware,
    /** `chapterWeatherId` for `InitBmBgLayers`. */
    private val playSt: PlaySt,
) {
    private val initFn = ProcFunc { bmxfadeInit(it) }
    private val loopFn = ProcFunc { bmxfadeLoop(it) }
    private val destructFn = ProcFunc { destruct6CBMXFADE(it) }

    /** `sProcScr_BMXFADE` (tree 3). */
    public val script: ProcScript = ProcScript(
        "sProcScr_BMXFADE",
        listOf(
            name("BMXFADE"),
            endIfDuplicate(),
            setEndCb(destructFn),
            call(initFn),
            call(loopFn),
            repeat(loopFn),
            end(),
        ),
        ::BmxFadeState,
    )

    /**
     * Ported from src/bmxfade.c: bmxfade_init
     *
     * Starts the countdown at 16, puts the BG layers in the map's priority order and sets BG2 as
     * the blend's first target and BG3 and the sprites as its second.
     */
    public fun bmxfadeInit(proc: Proc) {
        (proc.state as BmxFadeState).counter = START_COUNT
        hardware.initBmBgLayers(playSt.chapterWeatherId == WEATHER_CLOUDS)
        hardware.lcd.setBlendTargetA(0, 0, 1, 0, 0)
        hardware.lcd.setBlendTargetB(0, 0, 0, 1, 1)
    }

    /**
     * Ported from src/bmxfade.c: bmxfade_loop
     *
     * Alpha blend with BG2's weight the counter and BG3's the rest, then counts down. Below 0:
     * breaks, drops the blend, resets BG2's tile data offset, clears BG2's tilemap and requests
     * its sync.
     */
    public fun bmxfadeLoop(proc: Proc) {
        val state = proc.state as BmxFadeState
        hardware.lcd.setBlendConfig(1, state.counter, START_COUNT - state.counter, 0)
        state.counter--
        if (state.counter >= 0) return
        engine.breakProc(proc)
        hardware.lcd.setBlendConfig(0, 0x10, 0, 0) // SetDefaultColorEffects
        hardware.setTileDataOffset(BG2, 0)
        hardware.fill(hardware.mapBuffer(BG2), 0)
        hardware.enableBgSyncByMask(BG2_SYNC_BIT)
    }

    /**
     * Ported from src/bmxfade.c: Destruct6CBMXFADE
     *
     * Clears the "seen" bit of every unit ([UnitTable.setAllUnitNotBackSprite]) and unlocks the
     * game if the fade locked it.
     */
    public fun destruct6CBMXFADE(proc: Proc) {
        units.setAllUnitNotBackSprite()
        if ((proc.state as BmxFadeState).gameLock != 0) unlockGame()
    }

    /**
     * Ported from src/bmxfade.c: NewBMXFADE
     *
     * Starts the fade in tree 3; with [lockGame] set it locks the game until the fade ends.
     */
    public fun newBmxFade(lockGame: Boolean) {
        val proc = engine.start(script, FADE_TREE)
        (proc.state as BmxFadeState).gameLock = if (lockGame) 1 else 0
        if (lockGame) lockGame()
    }

    /**
     * Ported from src/bmxfade.c: DoesBMXFADEExist
     *
     * True while a fade proc exists.
     */
    public fun doesBmxFadeExist(): Boolean = engine.find(script) != null

    private fun lockGame() {
        bm.lock = (bm.lock + 1).toByte().toInt()
    }

    private fun unlockGame() {
        bm.lock = (bm.lock - 1).toByte().toInt()
    }

    private companion object {
        const val START_COUNT = 0x10
        const val FADE_TREE = 3
        const val BG2 = 2
        const val BG2_SYNC_BIT = 4
        const val WEATHER_CLOUDS = 4
    }
}
