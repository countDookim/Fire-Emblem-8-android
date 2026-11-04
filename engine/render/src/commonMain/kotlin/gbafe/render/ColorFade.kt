package gbafe.render

import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.yield

/**
 * The palette colour fade of `src/hardware.c` and `asm/arm.s`: every colour of the 32 palettes in
 * [palette] has three biased components (`gFadeComponents`) that move by a per-palette step
 * (`gFadeComponentStep`) each tick.
 */
public class ColorFade(
    /** `gPaletteBuffer`. */
    public val palette: ShortArray,
) {
    /** `gFadeComponentStep`: per-palette step (s8; 0 leaves the palette alone). */
    public val step: ByteArray = ByteArray(32)

    /** `gFadeComponents`: red, green, blue of each colour, biased by 0x20 (u8). */
    public val components: ByteArray = ByteArray(512 * 3)

    private fun setup(stepValue: Int, bias: Int) {
        for (i in 31 downTo 0) {
            step[i] = stepValue.toByte()
            for (j in 0 until 16) {
                val c = palette[i * 16 + j].toInt()
                val o = (i * 16 + j) * 3
                components[o] = ((c and 0x1F) + bias).toByte()
                components[o + 1] = (((c shr 5) and 0x1F) + bias).toByte()
                components[o + 2] = (((c shr 10) and 0x1F) + bias).toByte()
            }
        }
    }

    /**
     * Starts a fade from the current colours to black ([a] negative).
     *
     * Ported from src/hardware.c: ColorFadeSetupFromColorToBlack
     */
    public fun setupFromColorToBlack(a: Int): Unit = setup(a, 32)

    /**
     * Starts a fade in from black: components start unbiased (black) and rise to the colours.
     *
     * Ported from src/hardware.c: ColorFadeSetupFromBlack
     */
    public fun setupFromBlack(a: Int): Unit = setup(a, 0)

    /**
     * Starts a fade from the current colours to white.
     *
     * Ported from src/hardware.c: ColorFadeSetupFromColorToWhite
     */
    public fun setupFromColorToWhite(a: Int): Unit = setup(a, 32)

    /**
     * Starts a fade in from white: components start at +0x40 (white) and fall to the colours.
     *
     * Ported from src/hardware.c: ColorFadeSetupFromWhite
     */
    public fun setupFromWhite(a: Int): Unit = setup(a, 64)

    /**
     * Adds each palette's step to its components (u8 wrap) and writes the colours, each
     * component minus 0x20 clamped to 0..31.
     *
     * Ported from asm/arm.s: ColorFadeTick
     */
    public fun tick() {
        for (pal in 31 downTo 0) {
            val s = step[pal].toInt()
            if (s == 0) continue
            for (j in 15 downTo 0) {
                val o = (pal * 16 + j) * 3
                var rgb = 0
                for (k in 0..2) {
                    val v = ((components[o + k].toInt() and 0xFF) + s) and 0xFF
                    components[o + k] = v.toByte()
                    rgb = rgb or ((v - 0x20).coerceIn(0, 0x1F) shl (5 * k))
                }
                palette[pal * 16 + j] = rgb.toShort()
            }
        }
    }
}

/**
 * The screen fade procs of `src/bmlib.c` (`ProcScr_FadeCore`): a [ColorFade] ticked at `speed`
 * sixteenths per frame until 0x200 sixteenths have passed.
 */
public class FadeCore(
    /** Proc engine. */
    public val engine: ProcEngine,
    /** Display hardware (its palette buffer is faded). */
    public val hardware: BgHardware,
    /** The colour fade state. */
    public val colorFade: ColorFade = ColorFade(hardware.palette),
) {
    /** `struct FadeCoreProc` fields. */
    public class State {
        /** `on_end`. */
        public var onEnd: (() -> Unit)? = null

        /** `speed` (sixteenths of a tick per frame). */
        public var speed: Int = 0

        /** `looper`. */
        public var looper: Int = 0

        /** `counter`. */
        public var counter: Int = 0
    }

    /** `ProcScr_FadeCore`. */
    public val script: ProcScript = ProcScript(
        "ProcScr_FadeCore",
        listOf(
            mark(PROC_MARK_EFX_BGCOL),
            call(ProcFunc { init(it) }),
            yield(),
            call(ProcFunc { tick(it) }),
            repeat(ProcFunc { loop(it) }),
            end(),
        ),
        newState = { State() },
    )

    /**
     * Starts a fade of [kind] (0/2 from black, 1/3 to black, 4/6 from white, 5/7 to white; 2, 3,
     * 6 and 7 block [parent]) at [speed], calling [onEnd] when done.
     *
     * Ported from src/bmlib.c: StartFadeCore
     */
    public fun start(kind: Int, speed: Int, parent: Proc, onEnd: (() -> Unit)? = null) {
        val proc = (if ((kind and 2) != 0) engine.startBlocking(script, parent) else engine.start(script, parent))!!
        val st = proc.state as State
        st.speed = speed
        st.onEnd = onEnd
        val step = (st.speed shr 4).coerceAtLeast(1) * UNIT[kind]
        when (kind) {
            0, 2 -> colorFade.setupFromBlack(step)
            1, 3 -> colorFade.setupFromColorToBlack(step)
            4, 6 -> colorFade.setupFromWhite(step)
            else -> colorFade.setupFromColorToWhite(step)
        }
    }

    private val darkenEndCallback: () -> Unit = { darkenEnd() }

    /**
     * Starts a blocking fade to black at speed 0x20 that leaves the screen dark ([darkenEnd]).
     *
     * Ported from src/bmlib.c: sub_8013F40
     */
    public fun startFadeToDark(parent: Proc): Unit = start(3, 0x20, parent, darkenEndCallback)

    /**
     * Ported from src/bmlib.c: FadeCore_Init
     */
    public fun init(proc: Proc) {
        val st = proc.state as State
        st.looper = 0
        st.counter = 0
        st.onEnd = null
    }

    /**
     * Advances the fade: ticks the colours each time `looper` passes 16 sixteenths (and on the
     * first call), with a black backdrop. Returns false once 0x200 sixteenths have passed.
     *
     * Ported from src/bmlib.c: FadeCore_Tick
     */
    public fun tick(proc: Proc): Boolean {
        val st = proc.state as State
        st.looper += st.speed
        st.counter += st.speed
        if (st.looper < 0x10) {
            if (st.counter != st.speed) return true
        } else {
            st.looper -= 0x10
        }
        colorFade.tick()
        hardware.palette[0] = 0 // SetBackdropColor
        hardware.enablePaletteSync()
        return st.counter < 0x200
    }

    /**
     * Ticks; when the fade is done calls `on_end` and breaks.
     *
     * Ported from src/bmlib.c: FadeCore_Loop
     */
    public fun loop(proc: Proc) {
        if (!tick(proc)) {
            (proc.state as State).onEnd?.invoke()
            engine.breakProc(proc)
        }
    }

    /**
     * End callback of the fades to black: screen kept dark (darken 16 on all layers and the
     * backdrop), black backdrop, all layers off.
     *
     * Ported from src/bmlib.c: sub_80141B0
     */
    public fun darkenEnd() {
        val lcd = hardware.lcd
        lcd.setBlendConfig(3, 0, 0, 0x10)
        lcd.setBlendTargetA(1, 1, 1, 1, 1)
        lcd.bldcnt = lcd.bldcnt or 0x20 // SetBlendBackdropA(1)
        hardware.palette[0] = 0
        hardware.enablePaletteSync()
        lcd.setDispEnable(0, 0, 0, 0, 0)
    }

    private companion object {
        const val PROC_MARK_EFX_BGCOL = 10

        /** `gUnknown_080D7964[].unit`. */
        val UNIT = intArrayOf(1, -1, 1, -1, -1, 1, -1, 1)
    }
}
