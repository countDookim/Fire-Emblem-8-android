package fe8.game.bm

import gbafe.core.math.Interpolate

/**
 * `ProcScr_ekrBattleEnding` (src/banim-ekrbattleending.c): the proc that returns from the
 * battle animation to the map, as far as its `PROC_REPEAT` commands are ported
 * (`ekrBattleEnding_80560F0` to `ekrBattleEnding_8056484`, then `PROC_END`). The proc is modelled by its script
 * cursor ([cursor], the NAME command being 1, so the n-th repeat command reads as n + 1), the
 * current repeat callback ([callback], null after `Proc_Break`) and the `ProcEkrBattleEnding`
 * fields [timer], [x1], [x2], [y1] and [y2].
 *
 * `EkrMainEndExec` (src/banim-ekrbattlestarting.c) calls `NewEkrbattleending`; it is not ported,
 * so the owner constructs this class at that point (see [start]). [runStep] is one proc run.
 *
 * @property env the globals the callbacks read.
 * @property hooks the effects the callbacks start, not ported here.
 */
public class EkrBattleEnding(
    private val env: Env,
    private val hooks: Hooks = object : Hooks {},
) {
    /** The globals read by the ported callbacks. */
    public interface Env {
        /** `gBanimBG`. */
        public val banimBg: Int

        /** `gEkrBmLocation[4]` (s16): x, y of the left unit, then of the right unit, in tiles. */
        public val bmLocation: IntArray

        /** `GetBattleAnimArenaFlag() == 1`. */
        public val battleAnimArenaFlag: Boolean get() = false

        /** `GetBanimLinkArenaFlag()`. */
        public val banimLinkArenaFlag: Int get() = 0
    }

    /** Effects whose results are not in the control state (VRAM, IO, palettes, other procs). */
    public interface Hooks {
        /** `AnimClearAll()`. */
        public fun animClearAll() {}

        /** `NewEkrUnitKakudai(1)`. */
        public fun newEkrUnitKakudai(arg: Int) {}

        /** `NewEkrBaseKaiten(1)`. */
        public fun newEkrBaseKaiten(arg: Int) {}

        /** `NewEkrWindowAppear(arg, duration)`; sets `gEkrWindowAppearExist`. */
        public fun newEkrWindowAppear(arg: Int, duration: Int) {}

        /** `NewEkrBaseAppear(arg, duration)`. */
        public fun newEkrBaseAppear(arg: Int, duration: Int) {}

        /** `EndEkrGauge()`; ends the gauge, base-kaiten, window and base-appear procs. */
        public fun endEkrGauge() {}

        /** `InitBmBgLayers()`. */
        public fun initBmBgLayers() {}

        /** `SetWinEnable(win0, win1, objwin)`. */
        public fun setWinEnable(win0: Int, win1: Int, objWin: Int) {}

        /** `SetWin0Box(left, top, right, bottom)`. */
        public fun setWin0Box(left: Int, top: Int, right: Int, bottom: Int) {}

        /** `SetWin0Layers(bg0, bg1, bg2, bg3, obj)`. */
        public fun setWin0Layers(bg0: Int, bg1: Int, bg2: Int, bg3: Int, obj: Int) {}

        /** `SetWOutLayers(bg0, bg1, bg2, bg3, obj)`. */
        public fun setWOutLayers(bg0: Int, bg1: Int, bg2: Int, bg3: Int, obj: Int) {}

        /** `ResetUnitSprites()`. */
        public fun resetUnitSprites() {}

        /** `BMapDispResume_FromBattleDelayed()`; starts the `ProcScr_Mu` and the delayed resume. */
        public fun bmapDispResumeFromBattleDelayed() {}

        /** `RefreshUnitSprites()`. */
        public fun refreshUnitSprites() {}

        /** `ForceSyncUnitSpriteSheet()`. */
        public fun forceSyncUnitSpriteSheet() {}

        /** `ApplyUnitSpritePalettes()`. */
        public fun applyUnitSpritePalettes() {}

        /** `SetBlendConfig(effect, a, b, y)`. */
        public fun setBlendConfig(effect: Int, a: Int, b: Int, y: Int) {}

        /** `SetBlendTargetA(bg0, bg1, bg2, bg3, obj)`. */
        public fun setBlendTargetA(bg0: Int, bg1: Int, bg2: Int, bg3: Int, obj: Int) {}

        /** `gLCDControlBuffer.wincnt.win0_enableBlend = win0; ...wout_enableBlend = wout`. */
        public fun setWinEnableBlend(win0: Int, wout: Int) {}

        /** `UnpackChapterMapPalette()`. */
        public fun unpackChapterMapPalette() {}

        /** `LoadLinkArenaFogPlaceholder()`. */
        public fun loadLinkArenaFogPlaceholder() {}

        /** `CpuFastFill16(0, gBG2TilemapBuffer, 0x800)` and `BG_EnableSyncByMask(BG2_SYNC_BIT)`. */
        public fun clearBg2Tilemap() {}

        /** `EnablePaletteSync()`. */
        public fun enablePaletteSync() {}

        /** `EndEkrBattleDeamon()`. */
        public fun endEkrBattleDeamon() {}

        /** `RefreshBMapDisplay_FromBattle()`. */
        public fun refreshBMapDisplayFromBattle() {}
    }

    /** The repeat callbacks of the script; [Callback.cursor] is the cursor after reading it. */
    public enum class Callback(internal val cursor: Int) {
        /** `ekrBattleEnding_80560F0`. */
        C_80560F0(2),

        /** `ekrBattleEnding_8056170`. */
        C_8056170(3),

        /** `ekrBattleEnding_80561C8`. */
        C_80561C8(4),

        /** `ekrBattleEnding_8056228`. */
        C_8056228(5),

        /** `ekrBattleEnding_8056288`. */
        C_8056288(6),

        /** `ekrBattleEnding_8056310`. */
        C_8056310(7),

        /** `ekrBattleEnding_8056390`. */
        C_8056390(8),

        /** `ekrBattleEnding_8056484`. */
        C_8056484(9),
    }

    /** `proc_scrCur` in commands (NAME is command 1); stays on the command that broke. */
    public var cursor: Int = 0
        private set

    /** `proc_repeatFunc`; null before the first command and after `Proc_Break`. */
    public var callback: Callback? = null
        private set

    /** `ProcEkrBattleEnding.timer`. */
    public var timer: Int = 0

    /** `ProcEkrBattleEnding.terminator`. */
    public var terminator: Int = 0

    private var breakPending = false

    /** True once `PROC_END` has run: the proc is gone. */
    public var ended: Boolean = false
        private set

    /** `ProcEkrBattleEnding.x1`. */
    public var x1: Int = 0

    /** `ProcEkrBattleEnding.x2`. */
    public var x2: Int = 0

    /** `ProcEkrBattleEnding.y1`. */
    public var y1: Int = 0

    /** `ProcEkrBattleEnding.y2`. */
    public var y2: Int = 0

    /**
     * Ported from src/banim-ekrbattleending.c: NewEkrbattleending
     *
     * `Proc_Start` reads the NAME command and the first repeat command, whose callback is set
     * but not yet called; `timer` is 0.
     */
    public fun start() {
        timer = 0
        cursor = Callback.C_80560F0.cursor
        callback = Callback.C_80560F0
    }

    /**
     * One proc run: when the previous callback broke, the next repeat command is read first and
     * its callback called in the same run; after the last command `PROC_END` ends the proc.
     */
    public fun runStep() {
        check(!ended) { "proc ended" }
        if (breakPending) {
            // ekrBattleEnding_8056310: the callback still reads set after its run (f2010) and
            // clears one proc run later (f2011).
            breakPending = false
            callback = null
            return
        }
        if (callback == null) {
            val next = Callback.entries.firstOrNull { it.cursor == cursor + 1 }
            if (next == null) {
                ended = true
                return
            }
            cursor = next.cursor
            callback = next
        }
        when (callback) {
            Callback.C_80560F0 -> endingStep80560F0()
            Callback.C_8056170 -> endingStep8056170()
            Callback.C_80561C8 -> endingStep80561C8()
            Callback.C_8056228 -> endingStep8056228()
            Callback.C_8056288 -> endingStep8056288()
            Callback.C_8056310 -> endingStep8056310()
            Callback.C_8056390 -> endingStep8056390()
            Callback.C_8056484 -> endingStep8056484()
            null -> Unit
        }
    }

    /**
     * Partially ported from src/banim-ekrbattleending.c: ekrBattleEnding_80560F0
     *
     * Only the `gBanimBG == 0` break arm is captured; the dragon-status arms and the palette
     * fade (`Interpolate`, `PutBanimBgPAL`, `EfxPalBlackInOut`) throw
     * (`TODO("BA-cap4")`).
     */
    private fun endingStep80560F0() {
        if (env.banimBg == 0) {
            procBreak()
            return
        }
        TODO("BA-cap4: ekrBattleEnding_80560F0 with gBanimBG != 0")
    }

    /**
     * Partially ported from src/banim-ekrbattleending.c: ekrBattleEnding_8056170
     *
     * Only the `gBanimBG == 0` break arm is captured; the rest
     * (`UnpackChapterMapGraphics`, fade, `RenderBmMap`, dragon arms) throws (`TODO("BA-cap4")`).
     */
    private fun endingStep8056170() {
        if (env.banimBg == 0) {
            procBreak()
            return
        }
        TODO("BA-cap4: ekrBattleEnding_8056170 with gBanimBG != 0")
    }

    /**
     * Partially ported from src/banim-ekrbattleending.c: ekrBattleEnding_80561C8
     *
     * Only the `gBanimBG == 0` break arm is captured; the `EfxChapterMapFadeOUT` loop and the
     * dragon arms throw (`TODO("BA-cap4")`).
     */
    private fun endingStep80561C8() {
        if (env.banimBg == 0) {
            procBreak()
            return
        }
        TODO("BA-cap4: ekrBattleEnding_80561C8 with gBanimBG != 0")
    }

    /**
     * Ported from src/banim-ekrbattleending.c: ekrBattleEnding_8056228
     *
     * The box is the middle of the two units' tiles (`gEkrBmLocation`) in pixels; the calls
     * after it go to [Hooks].
     */
    private fun endingStep8056228() {
        timer = 0
        val loc = env.bmLocation
        x1 = (loc[0] + loc[2]) * 8 + 8
        x2 = x1
        y1 = (loc[1] + loc[3]) * 8 + 8
        y2 = y1
        hooks.animClearAll()
        hooks.newEkrUnitKakudai(1)
        hooks.newEkrBaseKaiten(1)
        hooks.newEkrWindowAppear(1, 0xB)
        hooks.newEkrBaseAppear(1, 0xB)
        procBreak()
    }

    /**
     * Ported from src/banim-ekrbattleending.c: ekrBattleEnding_8056288
     *
     * Waits 13 runs, then ends the gauge and sets the window registers through [Hooks].
     */
    private fun endingStep8056288() {
        timer++
        if (timer > 0xC) {
            hooks.endEkrGauge()
            procBreak()
            hooks.initBmBgLayers()
            hooks.setWinEnable(1, 0, 0)
            hooks.setWin0Box(0, 0, 0, 0)
            hooks.setWin0Layers(1, 1, 1, 1, 1)
            hooks.setWOutLayers(1, 1, 1, 1, 0)
        }
    }

    /**
     * Partially ported from src/banim-ekrbattleending.c: ekrBattleEnding_8056310
     *
     * Verified (f2010) for the arena flag 0 and link-arena flag 0 arms; the arena and link-arena
     * arms are Transcribed (no capture).
     */
    private fun endingStep8056310() {
        timer = 0
        terminator = 0xF
        hooks.resetUnitSprites()
        hooks.bmapDispResumeFromBattleDelayed()
        hooks.refreshUnitSprites()
        hooks.forceSyncUnitSpriteSheet()
        hooks.applyUnitSpritePalettes()
        hooks.setBlendConfig(3, 0, 0, 4)
        hooks.setBlendTargetA(0, 0, 0, 1, 0)
        hooks.setWinEnableBlend(0, 1)
        if (!env.battleAnimArenaFlag) hooks.unpackChapterMapPalette()
        if (env.banimLinkArenaFlag == 1) hooks.loadLinkArenaFogPlaceholder()
        // The dumps show the callback still set after this run; see [runStep].
        breakPending = true
    }

    /**
     * Partially ported from src/banim-ekrbattleending.c: ekrBattleEnding_8056390 (the
     * `timer != terminator` guard is never false on entry in the capture; a mutation survives)
     *
     * The window box shrinks from the box set by `ekrBattleEnding_8056228` to the screen; the
     * box, tilemap clear and palette sync go to [Hooks].
     */
    private fun endingStep8056390() {
        if (timer != terminator) timer++
        val left = Interpolate.interpolate(Interpolate.LINEAR, x1, 0, timer, terminator)
        val top = Interpolate.interpolate(Interpolate.LINEAR, y1, 0, timer, terminator)
        val right = Interpolate.interpolate(Interpolate.LINEAR, x2, 0xF0, timer, terminator)
        val bottom = Interpolate.interpolate(Interpolate.LINEAR, y2, 0xA0, timer, terminator)
        hooks.setWin0Box(left, top, right, bottom)
        hooks.clearBg2Tilemap()
        if (timer == terminator) {
            timer = 0
            hooks.setWin0Box(0, 0, 0xF0, 0xA0)
            hooks.enablePaletteSync()
            procBreak()
        }
    }

    /** Ported from src/banim-ekrbattleending.c: ekrBattleEnding_8056484 */
    private fun endingStep8056484() {
        hooks.endEkrBattleDeamon()
        hooks.refreshBMapDisplayFromBattle()
        procBreak()
    }

    /** `Proc_Break`: the callback reads 0, the cursor stays on the command that broke. */
    private fun procBreak() {
        callback = null
    }
}
