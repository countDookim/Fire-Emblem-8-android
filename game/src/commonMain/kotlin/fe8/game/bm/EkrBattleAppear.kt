package fe8.game.bm

import gbafe.core.math.Interpolate

/**
 * Intro and exit slide furniture procs from `src/banim-ekrbattleintro.c`:
 * `ProcScr_ekrWindowAppear` (`NewEkrWindowAppear`, `EkrWindowAppearMain`),
 * `ProcScr_ekrNamewinAppear` (`NewEkrNamewinAppear`, `EkrNamewinAppearDelay`,
 * `EkrNamewinAppearMain`), and `ProcScr_ekrBaseAppear` (`NewEkrBaseAppear`,
 * `EkrBaseAppearMain`).
 *
 * Each proc is modelled by its script cursor ([EkrWindowAppear.cursor] /
 * [EkrNamewinAppear.cursor] / [EkrBaseAppear.cursor], `PROC_NAME` being 1 so the n-th repeat
 * command reads as n + 1), current repeat callback (`null` after `Proc_Break`), `ended` once
 * `PROC_END` runs, `exist` (`gEkrWindowAppearExist`, `gEkrNamewinAppearExist`,
 * `gProcEkrBaseAppearExist`), and its `ProcEkrIntroWindow` fields.
 *
 * @property env the globals the callbacks read.
 * @property hooks the side effects on `ekrGauge`, `ekrDispUP`, and `BG_2`.
 */
public class EkrBattleAppear(
    private val env: Env = object : Env {},
    private val hooks: Hooks = object : Hooks {},
) {
    /** The globals read by the ported callbacks. */
    public interface Env {
        /** `gEkrBg0QuakeVec.x` (s16). */
        public val quakeX: Int get() = 0

        /** `gEkrBg0QuakeVec.y` (s16). */
        public val quakeY: Int get() = 0
    }

    /** Effects whose results live in `ekrGauge`, `ekrDispUP`, or BG scroll registers. */
    public interface Hooks {
        /** `EkrGauge_Clr323A(x, y)`. */
        public fun ekrGaugeClr323A(x: Int, y: Int) {}

        /** `EkrGauge_ClrInitFlag()`. */
        public fun ekrGaugeClrInitFlag() {}

        /** `EkrGauge_SetInitFlag()`. */
        public fun ekrGaugeSetInitFlag() {}

        /** `EkrDispUP_SetPositionUnsync(x, y)`. */
        public fun ekrDispUpSetPositionUnsync(x: Int, y: Int) {}

        /** `UnsyncEkrDispUP()`. */
        public fun unsyncEkrDispUp() {}

        /** `SyncEkrDispUP()`. */
        public fun syncEkrDispUp() {}

        /** `EndEkrDispUP()`. */
        public fun endEkrDispUp() {}

        /** `BG_SetPosition(bg, x, y)`. */
        public fun bgSetPosition(bg: Int, x: Int, y: Int) {}
    }

    /** `ProcScr_ekrWindowAppear` state. */
    public val windowAppear: EkrWindowAppear = EkrWindowAppear(env, hooks)

    /** `ProcScr_ekrNamewinAppear` state. */
    public val namewinAppear: EkrNamewinAppear = EkrNamewinAppear(env, hooks)

    /** `ProcScr_ekrBaseAppear` state. */
    public val baseAppear: EkrBaseAppear = EkrBaseAppear(env, hooks)

    /**
     * `ProcScr_ekrWindowAppear`: slides the gauge window vertically (`ymax = 0x39` to `0` on
     * `type == 0`) via `EkrGauge_Clr323A`.
     *
     * @property env the globals read by the callbacks.
     * @property hooks the gauge effects.
     */
    public class EkrWindowAppear(
        private val env: Env = object : Env {},
        private val hooks: Hooks = object : Hooks {},
    ) {
        /** Repeat callbacks of `ProcScr_ekrWindowAppear`; [cursor] is after reading it. */
        public enum class Callback(internal val cursor: Int) {
            /** `EkrWindowAppearMain`. */
            MAIN(2),
        }

        /** `ProcEkrIntroWindow.type` (0x44). */
        public var type: Int = 0

        /** `ProcEkrIntroWindow.timer` (0x2C, s16). */
        public var timer: Int = 0

        /** `ProcEkrIntroWindow.terminator` (0x2E, s16). */
        public var terminator: Int = 0

        /** `ProcEkrIntroWindow.ymax` (0x30, s16). */
        public var ymax: Int = 0

        /** `gEkrWindowAppearExist`: true while the window appear proc is active. */
        public var exist: Boolean = false

        /** `proc_scrCur` in commands (`PROC_NAME` is 1); stays on the command that broke. */
        public var cursor: Int = 0
            private set

        /** `proc_repeatFunc`; null after `Proc_Break` or before [start]. */
        public var callback: Callback? = null
            private set

        /** True once `PROC_END` has run: the proc is gone. */
        public var ended: Boolean = false
            private set

        /**
         * Partially ported from src/banim-ekrbattleintro.c: NewEkrWindowAppear (verified:
         * `identifier == 0` arm in both windows; not verified: `identifier != 0` arm)
         *
         * Starts `ProcScr_ekrWindowAppear` at `EkrWindowAppearMain` (cursor 2), sets initial
         * gauge position, marks `gEkrWindowAppearExist`, and clears the gauge init flag.
         */
        public fun start(identifier: Int, duration: Int) {
            type = identifier
            timer = 0
            terminator = duration
            ymax = WINDOW_YMAX
            ended = false
            cursor = Callback.MAIN.cursor
            callback = Callback.MAIN

            val iy = if (identifier == 0) WINDOW_YMAX else 0
            hooks.ekrGaugeClr323A(
                env.quakeX.toShort().toInt(),
                ((env.quakeY and 0xFFFF) + iy).toShort().toInt(),
            )
            exist = true
            hooks.ekrGaugeClrInitFlag()
        }

        /** One proc run of `ProcScr_ekrWindowAppear`. */
        public fun runStep() {
            check(!ended) { "proc ended" }
            if (callback == null) {
                ended = true
                return
            }
            main()
        }

        /**
         * Partially ported from src/banim-ekrbattleintro.c: EkrWindowAppearMain (verified:
         * `type == 0` arm in both windows; not verified: `type != 0` arm)
         */
        private fun main() {
            runAppearMain(
                type = type,
                timer = timer,
                terminator = terminator,
                edge = ymax,
                setTimer = { timer = it },
                onEnd = {
                    exist = false
                    hooks.ekrGaugeSetInitFlag()
                    callback = null
                },
                onFrame = { iy ->
                    hooks.ekrGaugeClr323A(
                        env.quakeX.toShort().toInt(),
                        ((env.quakeY and 0xFFFF) + iy).toShort().toInt(),
                    )
                },
            )
        }
    }

    /**
     * `ProcScr_ekrNamewinAppear`: optionally delays, then slides the name/unit display panel
     * vertically (`ymaxName = -49` to `0` on `type == 0`) via `EkrDispUP_SetPositionUnsync`.
     *
     * @property hooks the disp-up effects.
     */
    public class EkrNamewinAppear(
        @Suppress("unused") private val env: Env = object : Env {},
        private val hooks: Hooks = object : Hooks {},
    ) {
        /** Repeat callbacks of `ProcScr_ekrNamewinAppear`; [cursor] is after reading it. */
        public enum class Callback(internal val cursor: Int) {
            /** `EkrNamewinAppearDelay`. */
            DELAY(2),

            /** `EkrNamewinAppearMain`. */
            MAIN(3),
        }

        /** `ProcEkrIntroWindow.type` (0x44). */
        public var type: Int = 0

        /** `ProcEkrIntroWindow.timer` (0x2C, s16). */
        public var timer: Int = 0

        /** `ProcEkrIntroWindow.terminator` (0x2E, s16). */
        public var terminator: Int = 0

        /** `ProcEkrIntroWindow.ymax` (0x30, s16): delay frames before sliding. */
        public var ymax: Int = 0

        /** `ProcEkrIntroWindow.ymax_name` (0x48). */
        public var ymaxName: Int = 0

        /** `gEkrNamewinAppearExist`: true while the namewin appear proc is active. */
        public var exist: Boolean = false

        /** `proc_scrCur` in commands (`PROC_NAME` is 1); stays on the command that broke. */
        public var cursor: Int = 0
            private set

        /** `proc_repeatFunc`; null after `Proc_Break` or before [start]. */
        public var callback: Callback? = null
            private set

        /** True once `PROC_END` has run: the proc is gone. */
        public var ended: Boolean = false
            private set

        /**
         * Partially ported from src/banim-ekrbattleintro.c: NewEkrNamewinAppear (verified:
         * `identifier == 0` arm in both windows; not verified: `identifier != 0` arm)
         *
         * Starts `ProcScr_ekrNamewinAppear` at `EkrNamewinAppearDelay` (cursor 2), positions the
         * disp-up panel unsynced, marks `gEkrNamewinAppearExist`, and unsyncs `ekrDispUP`.
         */
        public fun start(identifier: Int, duration: Int, delay: Int) {
            type = identifier
            timer = 0
            terminator = duration
            ymax = delay
            ymaxName = NAMEWIN_YMAX
            ended = false
            cursor = Callback.DELAY.cursor
            callback = Callback.DELAY

            hooks.ekrDispUpSetPositionUnsync(0, if (identifier == 0) ymaxName else 0)
            exist = true
            hooks.unsyncEkrDispUp()
        }

        /**
         * One proc run of `ProcScr_ekrNamewinAppear`: after [delayStep] breaks, the next run
         * reads `EkrNamewinAppearMain` and calls it in the same step.
         */
        public fun runStep() {
            check(!ended) { "proc ended" }
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
                Callback.DELAY -> delayStep()
                Callback.MAIN -> main()
                null -> Unit
            }
        }

        /**
         * Partially ported from src/banim-ekrbattleintro.c: EkrNamewinAppearDelay (verified:
         * `delay == 0` break arm in both windows; not verified: `delay > 0` `timer++` wait arm)
         */
        private fun delayStep() {
            if (timer == ymax) {
                timer = 0
                callback = null
                return
            }
            timer++
        }

        /**
         * Partially ported from src/banim-ekrbattleintro.c: EkrNamewinAppearMain (verified:
         * `type == 0` arm in both windows; not verified: `type != 0` and `type == 2`
         * `EndEkrDispUP` arms)
         */
        private fun main() {
            runAppearMain(
                type = type,
                timer = timer,
                terminator = terminator,
                edge = ymaxName,
                setTimer = { timer = it },
                onEnd = {
                    exist = false
                    hooks.syncEkrDispUp()
                    if (type == 2) hooks.endEkrDispUp()
                    callback = null
                },
                onFrame = { iy -> hooks.ekrDispUpSetPositionUnsync(0, iy) },
            )
        }
    }

    /**
     * `ProcScr_ekrBaseAppear`: slides `BG_2` vertically (`-0x58` initial, `-0x50` to `0` on
     * `type == 0`) via `BG_SetPosition`.
     *
     * @property hooks the BG scroll effect.
     */
    public class EkrBaseAppear(
        @Suppress("unused") private val env: Env = object : Env {},
        private val hooks: Hooks = object : Hooks {},
    ) {
        /** Repeat callbacks of `ProcScr_ekrBaseAppear`; [cursor] is after reading it. */
        public enum class Callback(internal val cursor: Int) {
            /** `EkrBaseAppearMain`. */
            MAIN(2),
        }

        /** `ProcEkrIntroWindow.type` (0x44). */
        public var type: Int = 0

        /** `ProcEkrIntroWindow.timer` (0x2C, s16). */
        public var timer: Int = 0

        /** `ProcEkrIntroWindow.terminator` (0x2E, s16). */
        public var terminator: Int = 0

        /** `gProcEkrBaseAppearExist`: true while the base appear proc is active. */
        public var exist: Boolean = false

        /** `proc_scrCur` in commands (`PROC_NAME` is 1); stays on the command that broke. */
        public var cursor: Int = 0
            private set

        /** `proc_repeatFunc`; null after `Proc_Break` or before [start]. */
        public var callback: Callback? = null
            private set

        /** True once `PROC_END` has run: the proc is gone. */
        public var ended: Boolean = false
            private set

        /**
         * Partially ported from src/banim-ekrbattleintro.c: NewEkrBaseAppear (verified:
         * `identifier == 0` arm in both windows; not verified: `identifier != 0` arm)
         *
         * Starts `ProcScr_ekrBaseAppear` at `EkrBaseAppearMain` (cursor 2), positions `BG_2`, and
         * marks `gProcEkrBaseAppearExist`.
         */
        public fun start(identifier: Int, duration: Int) {
            type = identifier
            timer = 0
            terminator = duration
            ended = false
            cursor = Callback.MAIN.cursor
            callback = Callback.MAIN

            hooks.bgSetPosition(BG_2, 0, if (identifier == 0) BASE_INIT_Y else 0)
            exist = true
        }

        /** One proc run of `ProcScr_ekrBaseAppear`. */
        public fun runStep() {
            check(!ended) { "proc ended" }
            if (callback == null) {
                ended = true
                return
            }
            main()
        }

        /**
         * Partially ported from src/banim-ekrbattleintro.c: EkrBaseAppearMain (verified:
         * `type == 0` arm in both windows; not verified: `type != 0` arm)
         */
        private fun main() {
            runAppearMain(
                type = type,
                timer = timer,
                terminator = terminator,
                edge = BASE_MAIN_Y,
                setTimer = { timer = it },
                onEnd = {
                    exist = false
                    callback = null
                },
                onFrame = { iy -> hooks.bgSetPosition(BG_2, 0, iy) },
            )
        }
    }

    /** Constants from `banim-ekrbattleintro.c` and `hardware.h`. */
    public companion object {
        /** `BG_2` (hardware.h): background layer index 2 for the battle base. */
        public const val BG_2: Int = 2

        private const val WINDOW_YMAX = 0x39
        private const val NAMEWIN_YMAX = -49
        private const val BASE_INIT_Y = -0x58
        private const val BASE_MAIN_Y = -0x50

        private fun interpolateAppear(type: Int, edge: Int, timer: Int, terminator: Int): Int =
            if (type == 0) {
                Interpolate.interpolate(Interpolate.SQUARE, edge, 0, timer, terminator)
            } else {
                Interpolate.interpolate(Interpolate.RSQUARE, 0, edge, timer, terminator)
            }

        private inline fun runAppearMain(
            type: Int,
            timer: Int,
            terminator: Int,
            edge: Int,
            setTimer: (Int) -> Unit,
            onEnd: () -> Unit,
            onFrame: (Int) -> Unit,
        ) {
            if (timer >= terminator) {
                onEnd()
                return
            }
            val nextTimer = timer + 1
            setTimer(nextTimer)
            onFrame(interpolateAppear(type, edge, nextTimer, terminator))
        }
    }
}
