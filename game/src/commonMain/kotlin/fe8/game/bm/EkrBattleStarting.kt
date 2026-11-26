package fe8.game.bm

import gbafe.core.math.Interpolate

/**
 * `gProc_ekrBattleDeamon` (src/banim-ekrbattle.c) and `ProcScr_ekrBattleStarting`
 * (src/banim-ekrbattlestarting.c): the entry daemon and map-to-battle transition proc up through
 * `ekrBaStart_ExecEkrBattle6C`, where normal battles (`gBanimBG == 0` or non-normal dragon
 * status) start `gProc_ekrBattle` and end the starting proc.
 *
 * The starting proc is modelled by its script cursor ([cursor], the NAME command being 1, so the
 * n-th repeat command reads as n + 1), the current repeat callback ([callback], null after
 * `Proc_Break`) and the `ProcEkrBattleStarting` fields [timer], [terminator], [x1], [x2], [y1]
 * and [y2]. The daemon proc is modelled by [deamonActive], [deamonCursor], [deamonCallback] and
 * [deamonFlag].
 *
 * @property env the globals the callbacks read.
 * @property hooks the effects the callbacks start, not ported here.
 * @property deamonFlag `gBattleDeamonActive`, shared with callers checking [BattleDeamonFlag].
 */
public class EkrBattleStarting(
    private val env: Env,
    private val hooks: Hooks = object : Hooks {},
    public val deamonFlag: BattleDeamonFlag = BattleDeamonFlag(),
) {
    /** The globals read by the ported callbacks. */
    public interface Env {
        /** `gEkrBmLocation[4]` (s16): x, y of the left unit, then of the right unit, in tiles. */
        public val bmLocation: IntArray

        /** `gEkrDebugModeMaybe` (s16). */
        public val debugMode: Int get() = 0

        /** `gEkrDistanceType` (s16). */
        public val distanceType: Int get() = EKR_DISTANCE_CLOSE

        /** `gBanimValid[2]`: whether the left and right battle animations are valid. */
        public val banimValid: BooleanArray get() = booleanArrayOf(true, true)

        /** `gBanimBG` (s16). */
        public val banimBg: Int get() = 0

        /** `GetBanimDragonStatusType()`. */
        public val dragonStatusType: Int get() = EKRDRGON_TYPE_NORMAL
    }

    /** Effects whose results are not in the control state (VRAM, IO, palettes, other procs). */
    public interface Hooks {
        /** `LockGame()`. */
        public fun lockGame() {}

        /** `CpuFastFill(0, gBG2TilemapBuffer, 0x800)` and `BG_EnableSyncByMask(BG2_SYNC_BIT)`. */
        public fun clearBg2Tilemap() {}

        /** `SetBlendConfig(effect, a, b, y)`. */
        public fun setBlendConfig(effect: Int, a: Int, b: Int, y: Int) {}

        /** `SetBlendTargetA(bg0, bg1, bg2, bg3, obj)`. */
        public fun setBlendTargetA(bg0: Int, bg1: Int, bg2: Int, bg3: Int, obj: Int) {}

        /** `SetWinEnable(win0, win1, objWin)`. */
        public fun setWinEnable(win0: Int, win1: Int, objWin: Int) {}

        /** `SetWin0Box(left, top, right, bottom)`. */
        public fun setWin0Box(left: Int, top: Int, right: Int, bottom: Int) {}

        /** `SetWin0Layers(bg0, bg1, bg2, bg3, obj)`. */
        public fun setWin0Layers(bg0: Int, bg1: Int, bg2: Int, bg3: Int, obj: Int) {}

        /** `SetWOutLayers(bg0, bg1, bg2, bg3, obj)`. */
        public fun setWOutLayers(bg0: Int, bg1: Int, bg2: Int, bg3: Int, obj: Int) {}

        /** `gLCDControlBuffer.wincnt.win0_enableBlend = win0; ...wout_enableBlend = wout`. */
        public fun setWinEnableBlend(win0: Int, wout: Int) {}

        /** `InitOam(lo)`. */
        public fun initOam(lo: Int) {}

        /** `BMapDispSuspend()`. */
        public fun bmapDispSuspend() {}

        /** `EfxPalBlackInOut(gPaletteBuffer, line, length, ref)`. */
        public fun efxPalBlackInOut(line: Int, length: Int, ref: Int) {}

        /** `EnablePaletteSync()`. */
        public fun enablePaletteSync() {}

        /** `EndAllMus()`. */
        public fun endAllMus() {}

        /** `NewEkrGauge()`. */
        public fun newEkrGauge() {}

        /** `NewEkrDispUP()`. */
        public fun newEkrDispUp() {}

        /** `EkrGauge_Set4C()` and `EkrDispUpSet4C()`. */
        public fun setGaugeAndDispUp4C() {}

        /** `EkrGauge_Set50()` and `EkrDispUpSet50()`. */
        public fun setGaugeAndDispUp50() {}

        /** `EfxClearScreenFx()`. */
        public fun efxClearScreenFx() {}

        /** `NewEkrUnitKakudai(arg)`. */
        public fun newEkrUnitKakudai(arg: Int) {}

        /** `NewEkrBaseKaiten(arg)`. */
        public fun newEkrBaseKaiten(arg: Int) {}

        /** `NewEkrWindowAppear(arg, duration)`. */
        public fun newEkrWindowAppear(arg: Int, duration: Int) {}

        /** `NewEkrNamewinAppear(arg, duration, delay)`. */
        public fun newEkrNamewinAppear(arg: Int, duration: Int, delay: Int) {}

        /** `NewEkrBaseAppear(arg, duration)`. */
        public fun newEkrBaseAppear(arg: Int, duration: Int) {}

        /** `NewEkrBattle()`. */
        public fun newEkrBattle() {}
    }

    /**
     * Repeat callbacks of `ProcScr_ekrBattleStarting`; [cursor] is the cursor after reading it.
     */
    public enum class Callback(internal val cursor: Int) {
        /** `ekrBaStart_InitScreen`. */
        INIT_SCREEN(2),

        /** `ekrBaStart_SreenFailIn`. */
        SREEN_FAIL_IN(3),

        /** `ekrBaStart_InitBattleScreen`. */
        INIT_BATTLE_SCREEN(4),

        /** `ekrBaStart_ExecEkrBattle6C`. */
        EXEC_EKR_BATTLE_6C(5),
    }

    /** Repeat callbacks of `gProc_ekrBattleDeamon`; [cursor] is the cursor after reading it. */
    public enum class DeamonCallback(internal val cursor: Int) {
        /** `nullsub_35`. */
        NULLSUB_35(3),
    }

    /** True once [newEkrBattleDeamon] has started `gProc_ekrBattleDeamon`. */
    public var deamonActive: Boolean = false
        private set

    /** `gProc_ekrBattleDeamon` `proc_scrCur` in commands (NAME 1, SET_END_CB 2, REPEAT 3). */
    public var deamonCursor: Int = 0
        private set

    /** `gProc_ekrBattleDeamon` `proc_repeatFunc`. */
    public var deamonCallback: DeamonCallback? = null
        private set

    /** `proc_scrCur` in commands (NAME is command 1); stays on the command that broke. */
    public var cursor: Int = 0
        private set

    /** `proc_repeatFunc`; null before the first command and after `Proc_Break`. */
    public var callback: Callback? = null
        private set

    /** `ProcEkrBattleStarting.timer`. */
    public var timer: Int = 0

    /** `ProcEkrBattleStarting.terminator`. */
    public var terminator: Int = 0

    private var breakPending: Boolean = false

    /** True once `Proc_End` has run: the proc is gone. */
    public var ended: Boolean = false
        private set

    /** `ProcEkrBattleStarting.x1`. */
    public var x1: Int = 0

    /** `ProcEkrBattleStarting.x2`. */
    public var x2: Int = 0

    /** `ProcEkrBattleStarting.y1`. */
    public var y1: Int = 0

    /** `ProcEkrBattleStarting.y2`. */
    public var y2: Int = 0

    /**
     * Ported from src/banim-ekrbattle.c: NewEkrBattleDeamon
     *
     * Starts `gProc_ekrBattleDeamon` on `PROC_TREE_3` (`nullsub_35` at cursor 3), sets
     * `gBattleDeamonActive` to 1, and calls [Hooks.lockGame].
     */
    public fun newEkrBattleDeamon() {
        deamonActive = true
        deamonCursor = DeamonCallback.NULLSUB_35.cursor
        deamonCallback = DeamonCallback.NULLSUB_35
        deamonFlag.gBattleDeamonActive = 1
        hooks.lockGame()
    }

    /** One proc run of `gProc_ekrBattleDeamon`. */
    public fun runDeamonStep() {
        if (!deamonActive) return
        when (deamonCallback) {
            DeamonCallback.NULLSUB_35 -> nullsub35()
            null -> Unit
        }
    }

    /** Ported from src/banim-ekrbattle.c: nullsub_35 */
    private fun nullsub35() = Unit

    /**
     * Ported from src/banim-ekrbattlestarting.c: NewEkrBattleStarting
     *
     * `Proc_Start` reads the NAME command and the first repeat command (`ekrBaStart_InitScreen`,
     * cursor 2), whose callback is set but not yet called.
     */
    public fun start() {
        timer = 0
        terminator = 0
        x1 = 0
        x2 = 0
        y1 = 0
        y2 = 0
        breakPending = false
        ended = false
        cursor = Callback.INIT_SCREEN.cursor
        callback = Callback.INIT_SCREEN
    }

    /**
     * One proc run: when the previous callback broke, the next repeat command is read first and
     * its callback called in the same run.
     */
    public fun runStep() {
        check(!ended) { "proc ended" }
        if (breakPending) {
            // ekrBaStart_InitBattleScreen: heavy calls spill over VBlanks while callback is still
            // set and timer still reads 15; timer resets to 0 and callback clears on completion.
            breakPending = false
            timer = 0
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
            Callback.INIT_SCREEN -> initScreen()
            Callback.SREEN_FAIL_IN -> sreenFailIn()
            Callback.INIT_BATTLE_SCREEN -> initBattleScreen()
            Callback.EXEC_EKR_BATTLE_6C -> execEkrBattle6C()
            null -> Unit
        }
    }

    /**
     * Ported from src/banim-ekrbattlestarting.c: ekrBaStart_InitScreen
     *
     * Centers the zoom box on `gEkrBmLocation`, sets `terminator` to 0xF, configures BG2/blend/WIN0
     * through [Hooks], and breaks.
     */
    private fun initScreen() {
        timer = 0
        terminator = 0xF

        val loc = env.bmLocation
        val cx = (loc[0] + loc[2]) * 8 + 8
        x2 = cx
        x1 = cx
        val cy = (loc[1] + loc[3]) * 8 + 8
        y2 = cy
        y1 = cy

        hooks.clearBg2Tilemap()
        hooks.setBlendConfig(3, 0, 0, 4)
        hooks.setBlendTargetA(0, 0, 0, 1, 0)
        hooks.setWinEnable(1, 0, 0)
        hooks.setWin0Box(0, 0, 0xF0, 0xA0)
        hooks.setWin0Layers(1, 1, 1, 1, 1)
        hooks.setWOutLayers(1, 1, 1, 1, 0)
        hooks.setWinEnableBlend(0, 1)

        procBreak()
    }

    /**
     * Partially ported from src/banim-ekrbattlestarting.c: ekrBaStart_SreenFailIn (the
     * `timer != terminator` guard is never false on entry in the capture; a mutation survives)
     *
     * Shrinks WIN0 from full screen to `(x1, y1, x2, y2)`, then suspends map display, fades
     * palettes, stops MUs, and breaks.
     */
    private fun sreenFailIn() {
        if (timer != terminator) timer++

        val left = Interpolate.interpolate(Interpolate.LINEAR, 0, x1, timer, terminator)
        val top = Interpolate.interpolate(Interpolate.LINEAR, 0, y1, timer, terminator)
        val right = Interpolate.interpolate(Interpolate.LINEAR, 0xF0, x2, timer, terminator)
        val bottom = Interpolate.interpolate(Interpolate.LINEAR, 0xA0, y2, timer, terminator)
        hooks.setWin0Box(left, top, right, bottom)

        if (timer == terminator) {
            hooks.setWOutLayers(1, 1, 1, 1, 1)
            hooks.initOam(0)
            hooks.bmapDispSuspend()
            hooks.setWin0Box(0, 0, 0xF0, 0xA0)
            hooks.efxPalBlackInOut(0x6, 0xA, 0x4)
            hooks.enablePaletteSync()
            hooks.endAllMus()
            procBreak()
        }
    }

    /**
     * Partially ported from src/banim-ekrbattlestarting.c: ekrBaStart_InitBattleScreen (verified:
     * `debugMode == 0` and `EKR_DISTANCE_CLOSE` arm in both windows; not verified: non-zero
     * `debugMode`, `FAR`, `FARFAR`, `MONOCOMBAT`, and `PROMOTION` arms)
     *
     * Starts the battle gauge, disp-up, screen fx, unit zoom, base rotation, and intro window
     * procs through [Hooks], then resets `timer` to 0 and breaks after the multi-frame spillover.
     */
    private fun initBattleScreen() {
        if (env.debugMode == 0) {
            hooks.newEkrGauge()
            hooks.newEkrDispUp()
            when (env.distanceType) {
                EKR_DISTANCE_CLOSE, EKR_DISTANCE_FAR, EKR_DISTANCE_FARFAR -> Unit
                EKR_DISTANCE_MONOCOMBAT -> {
                    if (!env.banimValid[0]) hooks.setGaugeAndDispUp4C()
                    if (!env.banimValid[1]) hooks.setGaugeAndDispUp50()
                }
                EKR_DISTANCE_PROMOTION -> hooks.setGaugeAndDispUp4C()
            }
        }

        hooks.efxClearScreenFx()
        hooks.newEkrUnitKakudai(0)
        hooks.newEkrBaseKaiten(0)
        hooks.newEkrWindowAppear(0, 0xB)
        hooks.newEkrNamewinAppear(0, 0xB, 0)
        hooks.newEkrBaseAppear(0, 0xB)

        // The dumps show callback still set and timer == 15 mid-call before clearing to 0;
        // see [runStep].
        breakPending = true
    }

    /**
     * Partially ported from src/banim-ekrbattlestarting.c: ekrBaStart_ExecEkrBattle6C (verified:
     * `++timer > 0xB` wait and `banimBg == 0` end path in both windows; not verified:
     * `dragonStatusType != 0` and the `banimBg != 0` `TODO("BA-cap4")` path)
     *
     * Waits 12 calls, then starts `gProc_ekrBattle` and ends the proc when `gBanimBG == 0` or
     * dragon status is non-normal.
     */
    private fun execEkrBattle6C() {
        if (++timer > 0xB) {
            if (env.banimBg == 0 || env.dragonStatusType != EKRDRGON_TYPE_NORMAL) {
                hooks.newEkrBattle()
                ended = true
                callback = null
            } else {
                TODO("BA-cap4: ekrBaStart_ExecEkrBattle6C with gBanimBG != 0")
            }
        }
    }

    /** `Proc_Break`: the callback reads 0, the cursor stays on the command that broke. */
    private fun procBreak() {
        callback = null
    }

    /** Constants from `ekrbattle.h` and `ekrdragon.h`. */
    public companion object {
        /** `EKR_DISTANCE_CLOSE`. */
        public const val EKR_DISTANCE_CLOSE: Int = 0

        /** `EKR_DISTANCE_FAR`. */
        public const val EKR_DISTANCE_FAR: Int = 1

        /** `EKR_DISTANCE_FARFAR`. */
        public const val EKR_DISTANCE_FARFAR: Int = 2

        /** `EKR_DISTANCE_MONOCOMBAT`. */
        public const val EKR_DISTANCE_MONOCOMBAT: Int = 3

        /** `EKR_DISTANCE_PROMOTION`. */
        public const val EKR_DISTANCE_PROMOTION: Int = 4

        /** `EKRDRGON_TYPE_NORMAL`. */
        public const val EKRDRGON_TYPE_NORMAL: Int = 0
    }
}
