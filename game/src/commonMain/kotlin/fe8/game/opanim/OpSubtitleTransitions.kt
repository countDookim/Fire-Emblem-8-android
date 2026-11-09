package fe8.game.opanim

import fe8.game.RomView
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware

/** The opening subtitle's hold timer and first palette fade-in. */
public class OpSubtitleTransitions(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val rom: RomView,
    private val handleStartPress: (Proc) -> Unit,
) {
    private val subtitlePalette: ShortArray = rom.pal(PALETTE, 16)

    /**
     * Decrements the hold timer, breaks its repeat when it reaches zero, and checks Start even
     * on the breaking frame. The proc's state must be [OpSubtitleSlides.State].
     *
     * Ported from src/opsubtitle.c: OpSubtitle_AwaitTimer2a
     */
    public fun awaitHoldTimer(proc: Proc) {
        val state = proc.state as OpSubtitleSlides.State
        state.holdTimer = (state.holdTimer - 1).toShort().toInt()
        if (state.holdTimer < 1) engine.breakProc(proc)
        handleStartPress(proc)
    }

    /**
     * Advances the subtitle palette fade, starts the indexed slide's hold at completion, marks
     * the palette for sync, then checks Start. The proc's state must be [OpSubtitleSlides.State].
     *
     * Ported from src/opsubtitle.c: sub_80C4DA0
     */
    public fun fadeIn(proc: Proc) {
        val state = proc.state as OpSubtitleSlides.State
        state.transitionTimer = (state.transitionTimer + 1).toShort().toInt()
        if (state.transitionTimer < 80) {
            val coeff = (state.transitionTimer shl 12) / 80
            scalePalette(subtitlePalette, hardware.palette, 3 * 16, 16, coeff)
        } else {
            subtitlePalette.copyInto(hardware.palette, 3 * 16)
            engine.breakProc(proc)
            state.holdTimer = rom.u32(LUT + state.index * 12 + 8).toShort().toInt()
            state.transitionTimer = 0
        }
        hardware.enablePaletteSync()
        handleStartPress(proc)
    }

    /**
     * Scales BGR555 channels by a 12-bit fixed-point coefficient into the destination palette.
     *
     * Ported from src/opsubtitle.c: sub_80C4BB4
     */
    private fun scalePalette(
        src: ShortArray, dst: ShortArray, offset: Int, count: Int, coeff: Int
    ) {
        for (i in 0 until count) {
            val color = src[i].toInt() and 0xFFFF
            val r = ((color and 31) * coeff / 0x1000).toShort().toInt()
            val g = (((color shr 5) and 31) * coeff / 0x1000).toShort().toInt()
            val b = (((color shr 10) and 31) * coeff / 0x1000).toShort().toInt()
            dst[offset + i] = (r + (g shl 5) + (b shl 10)).toShort()
        }
    }

    private companion object {
        const val LUT = 0x08206FE4
        const val PALETTE = 0x08AA239C
    }
}
