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
 * [palette] has three components (`gFadeComponents`) that move by a per-palette step
 * (`gFadeComponentStep`) each tick. A component is the 5-bit channel plus a bias that the setup
 * chooses (0, 0x20 or 0x40); the colour shown is `component - 0x20` clamped to 0..31, so a
 * bias of 0 starts black, 0x20 starts at the colour and 0x40 starts white.
 */
public class ColorFade(
    /** `gPaletteBuffer`. */
    public val palette: ShortArray,
) {
    /** `gFadeComponentStep`: per-palette step (s8; 0 leaves the palette alone). */
    public val step: ByteArray = ByteArray(32)

    /** `gFadeComponents`: red, green, blue of each colour plus the setup's bias (u8, wrapping). */
    public val components: ByteArray = ByteArray(512 * 3)

    /** The `ColorFadeSetup*` bodies: all palettes get [stepValue]; component = channel + [bias]. */
    private fun setup(stepValue: Int, bias: Int) {
        for (i in 31 downTo 0) {
            step[i] = stepValue.toByte()
            for (j in 0 until 16) {
                val color = palette[i * 16 + j].toInt()
                val offset = (i * 16 + j) * 3
                components[offset] = ((color and 0x1F) + bias).toByte()
                components[offset + 1] = (((color shr 5) and 0x1F) + bias).toByte()
                components[offset + 2] = (((color shr 10) and 0x1F) + bias).toByte()
            }
        }
    }

    /**
     * Starts a fade from the current colours to black ([step] negative: components start at the
     * colour + 0x20 and fall until the clamp shows black).
     *
     * Ported from src/hardware.c: ColorFadeSetupFromColorToBlack
     */
    public fun setupFromColorToBlack(step: Int): Unit = setup(step, 32)

    /**
     * Starts a fade in from black: components start unbiased (black) and rise to the colours.
     *
     * Ported from src/hardware.c: ColorFadeSetupFromBlack
     */
    public fun setupFromBlack(step: Int): Unit = setup(step, 0)

    /**
     * Starts a fade from the current colours to white ([step] positive). The decomp body is a
     * duplicate of `ColorFadeSetupFromColorToBlack`; only the step's sign differs in use.
     *
     * Transcribed from src/hardware.c: ColorFadeSetupFromColorToWhite (not verified: no trace
     *   reaches it)
     */
    public fun setupFromColorToWhite(step: Int): Unit = setup(step, 32)

    /**
     * Starts a fade in from white: components start at +0x40 (white) and fall to the colours.
     *
     * Transcribed from src/hardware.c: ColorFadeSetupFromWhite (not verified: no trace reaches it)
     */
    public fun setupFromWhite(step: Int): Unit = setup(step, 64)

    /**
     * Adds each palette's step to its components (stored as u8, wrapping) and writes the colours,
     * each component minus 0x20 clamped to 0..31. Note: `ColorFadeTick` in `asm/arm.s` clamps
     * the unwrapped sum (`ldrb`, `add`, `strb`, then `subs` on the register that was not
     * wrapped), this port clamps the wrapped byte; they differ only if a component leaves
     * 0..255, which the speeds of `src/bmlib.c` (0x04..0x40) do not do.
     *
     * Ported from asm/arm.s: ColorFadeTick
     */
    public fun tick() {
        for (pal in 31 downTo 0) {
            val stepVal = step[pal].toInt()
            if (stepVal == 0) continue
            for (j in 15 downTo 0) {
                val offset = (pal * 16 + j) * 3
                var rgb = 0
                for (k in 0..2) {
                    val comp = ((components[offset + k].toInt() and 0xFF) + stepVal) and 0xFF
                    components[offset + k] = comp.toByte()
                    rgb = rgb or ((comp - 0x20).coerceIn(0, 0x1F) shl (5 * k))
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
        /** `on_end` (+0x4C): called when the fade is done. */
        public var onEnd: (() -> Unit)? = null

        /** `speed` (+0x54): sixteenths of a [ColorFade] tick per frame. */
        public var speed: Int = 0

        /** `looper` (+0x58): sixteenths accumulated towards the next colour tick (0x10 = one). */
        public var looper: Int = 0

        /** `counter` (+0x5C): total sixteenths so far; the fade ends at 0x200. */
        public var counter: Int = 0
    }

    /** `ProcScr_FadeCore`: init, one tick, then tick every frame until done (`PROC_REPEAT`). */
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
        /** `PROC_MARK_EFX_BGCOL`: the proc mark of fades on the BG colours. */
        const val PROC_MARK_EFX_BGCOL = 10

        /** `gUnknown_080D7964[].unit`: sign of the component step per fade kind 0..7. */
        val UNIT = intArrayOf(1, -1, 1, -1, -1, 1, -1, 1)
    }
}
