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

/** `struct BmxfadeProc` past `PROC_HEADER` (src/bmxfade.c): what the map fade keeps. */
public class BmxFadeState {
    /** `counter` (s16, 0x4C): 16 at the start, counts down to -1 (the end of the fade). */
    public var counter: Int = 0

    /** `game_lock` (s16, 0x4E): non-zero when the fade locked the game and must unlock it. */
    public var gameLock: Int = 0
}

/**
 * The map cross-fade of src/bmxfade.c: `sProcScr_BMXFADE` (a duplicate ends at once). BG2 holds
 * the map as it was before a change ([BmMapRender.renderBmMapOnBg2]) and BG3 the new one (the
 * map render targets BG3, per the decomp's `RenderBmMapOnBg2` / `RenderBmMap` pair); the fade
 * alpha-blends BG2 (first target) into BG3 and the sprites (second target) over 17 frames, then
 * clears BG2 and the blend. [newBmxFade] starts it, [doesBmxFadeExist] is what the procs that
 * wait for it poll. Used by [CpPerform.aiRefreshMap] after an AI unit's action. Nothing
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

    /**
     * `sProcScr_BMXFADE` (tree 3): ends itself if one already runs, sets [destruct6CBMXFADE] as
     * its end callback, runs [bmxfadeInit] and then [bmxfadeLoop] every frame.
     */
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
     * Starts the countdown at 16 (`0x10`), puts the BG layers in the map's priority order
     * (`InitBmBgLayers`: BG3 level with BG2 under `WEATHER_CLOUDS`) and sets BG2 as the blend's
     * first target and BG3 and the sprites (OBJ) as its second.
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
     * Alpha blend with BG2's weight the counter and BG3's the rest (so the old map fades out
     * from 16/16 to 0/16 over 17 frames), then counts down. Below 0: breaks the proc, drops the
     * blend (`SetDefaultColorEffects`), resets BG2's tile data offset, clears BG2's tilemap and
     * requests its sync (bit 2).
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
     * Clears `US_BIT8` of every valid unit (ids 1 to 0xBF; [UnitTable.setAllUnitNotBackSprite]).
     * The decomp comments the bit as "has been seen?". Then unlocks the game if the fade locked
     * it. Runs as the proc's end callback (`PROC_SET_END_CB`).
     */
    public fun destruct6CBMXFADE(proc: Proc) {
        units.setAllUnitNotBackSprite()
        if ((proc.state as BmxFadeState).gameLock != 0) unlockGame()
    }

    /**
     * Ported from src/bmxfade.c: NewBMXFADE
     *
     * Starts the fade in tree 3; with [lockGame] set it also locks the game (`LockGame`,
     * `gBmSt.lock` + 1) and the end callback unlocks it. The decomp's blocking variant
     * `MakeNew6CBMXFADE2` is not ported.
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

    /** `LockGame`: counts one more lock on the map. */
    private fun lockGame() {
        bm.lock = (bm.lock + 1).toByte().toInt()
    }

    /** `UnlockGame`: releases one lock. */
    private fun unlockGame() {
        bm.lock = (bm.lock - 1).toByte().toInt()
    }

    private companion object {
        /** The initial `counter` (`0x10`) and the full blend weight. */
        const val START_COUNT = 0x10

        /** `PROC_TREE_3`. */
        const val FADE_TREE = 3

        /** The BG index of `gBG2TilemapBuffer`. */
        const val BG2 = 2

        /** `BG_EnableSyncByMask(4)`: the BG2 bit. */
        const val BG2_SYNC_BIT = 4

        /**
         * Meant to be `WEATHER_CLOUDS` (types.h: 7, see [ChapterMapGfx.WEATHER_CLOUDS]); the
         * decomp enum has `WEATHER_RAIN` = 4. Left as is: this pass does not change behaviour.
         */
        const val WEATHER_CLOUDS = 4
    }
}
