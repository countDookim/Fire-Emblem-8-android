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
    private val cardPalette: ShortArray = rom.pal(CARD_PALETTE, 16)
    private val paletteSnapshot = ShortArray(PALETTE_COLORS)

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
     * Fades the subtitle palette out; at completion clears it and advances the slide index. The
     * first two slides then hold for 80 frames, later ones turn every layer off and jump to
     * label 1. The proc's state must be [OpSubtitleSlides.State].
     *
     * Ported from src/opsubtitle.c: sub_80C4E18
     */
    public fun fadeOutFirstCards(proc: Proc) {
        val state = proc.state as OpSubtitleSlides.State
        state.transitionTimer = (state.transitionTimer + 1).toShort().toInt()
        if (state.transitionTimer < 80) {
            val coeff = 0x1000 - ((state.transitionTimer shl 12) / 80)
            scalePalette(subtitlePalette, hardware.palette, 3 * 16, 16, coeff)
        } else {
            hardware.palette.fill(0, 3 * 16, 4 * 16)
            state.transitionTimer = 0
            state.index = (state.index + 1).toByte().toInt()
            if (state.index < 2) {
                state.holdTimer = 80
                engine.breakProc(proc)
            } else {
                hardware.lcd.setDispEnable(0, 0, 0, 0, 0)
                engine.goto(proc, 1)
            }
        }
        hardware.enablePaletteSync()
        handleStartPress(proc)
    }

    /**
     * Fades the subtitle palette out; at completion clears it, advances the slide index, holds
     * for 90 frames with only BG2 shown and breaks. The proc's state must be
     * [OpSubtitleSlides.State].
     *
     * Ported from src/opsubtitle.c: sub_80C4EC4
     */
    public fun fadeOutToCard(proc: Proc) {
        val state = proc.state as OpSubtitleSlides.State
        state.transitionTimer = (state.transitionTimer + 1).toShort().toInt()
        if (state.transitionTimer < 80) {
            val coeff = 0x1000 - ((state.transitionTimer shl 12) / 80)
            scalePalette(subtitlePalette, hardware.palette, 3 * 16, 16, coeff)
        } else {
            hardware.palette.fill(0, 3 * 16, 4 * 16)
            state.transitionTimer = 0
            state.index = (state.index + 1).toByte().toInt()
            state.holdTimer = 90
            hardware.lcd.setDispEnable(0, 0, 1, 0, 0)
            engine.breakProc(proc)
        }
        hardware.enablePaletteSync()
        handleStartPress(proc)
    }

    /**
     * Blends the card layer in. At completion slides before index 5 start their hold and break;
     * later ones start the hold, advance the index and jump to label 4. The proc's state must be
     * [OpSubtitleSlides.State].
     *
     * Ported from src/opsubtitle.c: sub_80C4F60
     */
    public fun blendIn(proc: Proc) {
        val state = proc.state as OpSubtitleSlides.State
        state.transitionTimer = (state.transitionTimer + 1).toShort().toInt()
        if (state.transitionTimer < 80) {
            val coeff = (state.transitionTimer shl 12) / 80
            val value = ((coeff shl 4) / 0x1000).toShort().toInt()
            hardware.lcd.setBlendConfig(1, 0x10 - value, value, 0)
        } else {
            hardware.lcd.setBlendConfig(1, 0, 0x10, 0)
            if (state.index < 5) {
                engine.breakProc(proc)
                state.holdTimer = rom.u32(LUT + state.index * 12 + 8).toShort().toInt()
                state.transitionTimer = 0
            } else {
                state.holdTimer = rom.u32(LUT + state.index * 12 + 8).toShort().toInt()
                state.index = (state.index + 1).toByte().toInt()
                engine.goto(proc, 4)
            }
        }
        hardware.enablePaletteSync()
        handleStartPress(proc)
    }

    /**
     * Blends the card layer out. At completion advances the slide index, holds for 80 frames and
     * breaks; Start is checked twice on that frame, as in the original. The proc's state must be
     * [OpSubtitleSlides.State].
     *
     * Ported from src/opsubtitle.c: sub_80C501C
     */
    public fun blendOut(proc: Proc) {
        val state = proc.state as OpSubtitleSlides.State
        state.transitionTimer = (state.transitionTimer + 1).toShort().toInt()
        if (state.transitionTimer < 80) {
            val coeff = (state.transitionTimer shl 12) / 80
            val value = (((0x1000 - coeff) shl 4) / 0x1000).toShort().toInt()
            hardware.lcd.setBlendConfig(1, 0x10 - value, value, 0)
        } else {
            hardware.lcd.setBlendConfig(1, 0x10, 0, 0)
            state.transitionTimer = 0
            state.index = (state.index + 1).toByte().toInt()
            state.holdTimer = 80
            engine.breakProc(proc)
            handleStartPress(proc)
        }
        hardware.enablePaletteSync()
        handleStartPress(proc)
    }

    /**
     * Fades the card palette into BG palette 15 over 46 frames, showing BG0 to BG2 on the first
     * frame; at completion starts the slide's hold and breaks. The proc's state must be
     * [OpSubtitleSlides.State].
     *
     * Ported from src/opsubtitle.c: sub_80C5104
     */
    public fun fadeInCardPalette(proc: Proc) {
        val state = proc.state as OpSubtitleSlides.State
        if (state.transitionTimer == 0) hardware.lcd.setDispEnable(1, 1, 1, 0, 0)
        state.transitionTimer = (state.transitionTimer + 1).toShort().toInt()
        if (state.transitionTimer < 46) {
            val coeff = (state.transitionTimer shl 12) / 46
            scalePalette(cardPalette, hardware.palette, 15 * 16, 16, coeff)
        } else {
            cardPalette.copyInto(hardware.palette, 15 * 16)
            engine.breakProc(proc)
            state.holdTimer = rom.u32(LUT + state.index * 12 + 8).toShort().toInt()
            state.transitionTimer = 0
        }
        hardware.enablePaletteSync()
        handleStartPress(proc)
    }

    /**
     * Clears the first 0x20 bytes of VRAM, marks BG1 for sync and shows BG0, BG1 and BG3.
     *
     * Ported from src/opsubtitle.c: sub_80C5328
     */
    public fun showFinalLayers() {
        hardware.vram.fill(0, 0, 0x20)
        hardware.enableBgSyncByMask(1 shl 1)
        hardware.lcd.setDispEnable(1, 1, 0, 1, 0)
    }

    /**
     * Resets the proc's transition timer and saves the BG half of the palette (0x200 bytes) for
     * the final fade. The proc's state must be [OpSubtitleSlides.State].
     *
     * Ported from src/opsubtitle.c: sub_80C5370
     */
    public fun snapshotPalette(proc: Proc) {
        val state = proc.state as OpSubtitleSlides.State
        state.transitionTimer = 0
        hardware.palette.copyInto(paletteSnapshot, 0, 0, BG_PALETTE_COLORS)
    }

    /**
     * Fades all 512 colours from the saved buffer over 60 frames; at completion clears the BG
     * half (0x200 bytes), starts a 60-frame hold and breaks. The original reads the OBJ half from
     * EWRAM past its 256-colour copy; here that half of the buffer stays zero (unverified, OBJ is
     * off in this stage). The proc's state must be [OpSubtitleSlides.State].
     *
     * Ported from src/opsubtitle.c: sub_80C538C
     */
    public fun fadeOutAll(proc: Proc) {
        val state = proc.state as OpSubtitleSlides.State
        state.transitionTimer = (state.transitionTimer + 1).toShort().toInt()
        if (state.transitionTimer < 60) {
            val coeff = 0x1000 - ((state.transitionTimer shl 12) / 60)
            scalePalette(paletteSnapshot, hardware.palette, 0, PALETTE_COLORS, coeff)
        } else {
            hardware.palette.fill(0, 0, BG_PALETTE_COLORS)
            state.transitionTimer = 0
            state.holdTimer = 60
            engine.breakProc(proc)
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
        source: ShortArray, destination: ShortArray, offset: Int, count: Int, coefficient: Int
    ) {
        for (i in 0 until count) {
            val color = source[i].toInt() and 0xFFFF
            val red = ((color and 31) * coefficient / 0x1000).toShort().toInt()
            val green = (((color shr 5) and 31) * coefficient / 0x1000).toShort().toInt()
            val blue = (((color shr 10) and 31) * coefficient / 0x1000).toShort().toInt()
            destination[offset + i] = (red + (green shl 5) + (blue shl 10)).toShort()
        }
    }

    private companion object {
        const val LUT = 0x08206FE4
        const val PALETTE = 0x08AA239C
        const val PALETTE_COLORS = 0x200
        const val BG_PALETTE_COLORS = 0x100
        const val CARD_PALETTE = 0x08B1756C
    }
}
