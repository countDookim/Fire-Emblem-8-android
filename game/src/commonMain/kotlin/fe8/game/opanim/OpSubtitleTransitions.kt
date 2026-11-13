package fe8.game.opanim

import fe8.game.RomView
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware

/**
 * The timed steps of the opening monologue: the hold timer, the slide palette fades, the blends
 * of the text layers over the background image and the final fade of the whole palette. Each is
 * a repeating proc step that counts [OpSubtitleSlides.State.transitionTimer] and breaks (or
 * jumps to a label) when its fade is complete. All of them call [handleStartPress].
 */
public class OpSubtitleTransitions(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val rom: RomView,
    private val handleStartPress: (Proc) -> Unit,
) {
    /** `gPal_OpSubtitle`: the slides' text palette (BG palette 3). */
    private val subtitlePalette: ShortArray = rom.pal(PALETTE, 16)

    /** `Pal_08B1756C`: the full-screen background image's palette (BG palette 15). */
    private val cardPalette: ShortArray = rom.pal(CARD_PALETTE, 16)

    /** `gUnk_OpSubtitle_0201CDD4`: the palette copy that [fadeOutAll] fades from. */
    private val paletteSnapshot = ShortArray(PALETTE_COLORS)

    /**
     * The pause between steps: decrements the hold timer, breaks its repeat when it reaches
     * zero, and checks Start even on the breaking frame. The proc's state must be
     * [OpSubtitleSlides.State].
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
     * Fades the slide text in from black over 80 frames by scaling `gPal_OpSubtitle` into BG
     * palette 3 (see [scalePalette]); at completion sets the full palette, breaks, and starts the
     * slide's own hold from the `gOpSubtitleGfxLut` entry. Then checks Start. The proc's state
     * must be [OpSubtitleSlides.State].
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
     * Fades the slide text out to black over 80 frames; at completion clears BG palette 3 and
     * advances the slide index. After slide 0 it holds 80 frames and breaks (the next slide
     * loads); after slide 1 (index now 2) it turns every layer off and jumps to label 1, the
     * flare scene. The proc's state must be [OpSubtitleSlides.State].
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
     * Fades the last slide's text out to black over 80 frames; at completion clears BG palette 3,
     * advances the slide index, holds for 90 frames with only BG2 shown and breaks (towards the
     * natural end, label 5). The proc's state must be [OpSubtitleSlides.State].
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
     * Fades a slide's text layers (BG0 and BG1, second blend targets) in over the background
     * (BG3, first target) over 80 frames: the blend coefficients go 16/0 to 0/16. At completion
     * slides 3 and 4 start their hold and break; slide 5, the last of this group, starts its hold,
     * advances the index and jumps to label 4. The proc's state must be [OpSubtitleSlides.State].
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
     * Fades the slide's text layers out again over 80 frames (the mirror of [blendIn], ending
     * with the background fully shown). At completion advances the slide index, holds for 80
     * frames and breaks; Start is checked twice on that frame, as in the original. The proc's
     * state must be [OpSubtitleSlides.State].
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
     * Fades the full-screen background image (BG1) in from black over 46 frames by scaling
     * `Pal_08B1756C` into BG palette 15, showing BG0 to BG2 on the first frame; at completion
     * sets the full palette, starts the current slide's hold and breaks. The proc's state must be
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
     * Clears the first tile of VRAM, so that the empty entries (tile 0) of BG1's map show nothing,
     * marks BG1 for sync and shows BG0, BG1 and BG3, ready for slides 3 to 5.
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
     * [fadeOutAll]. The proc's state must be [OpSubtitleSlides.State].
     *
     * Ported from src/opsubtitle.c: sub_80C5370
     */
    public fun snapshotPalette(proc: Proc) {
        val state = proc.state as OpSubtitleSlides.State
        state.transitionTimer = 0
        hardware.palette.copyInto(paletteSnapshot, 0, 0, BG_PALETTE_COLORS)
    }

    /**
     * Fades the whole screen to black: all 512 colours from the saved buffer over 60 frames; at
     * completion clears the BG
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
     * Writes [count] colours of [source] into [destination] at [offset], each BGR555 channel
     * scaled by [coefficient] / 0x1000 (0 is black, 0x1000 the original colour), which is how
     * palettes fade in and out here.
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
        /** `gOpSubtitleGfxLut`: 12-byte entries; the hold time is the u32 at +8. */
        const val LUT = 0x08206FE4

        /** `gPal_OpSubtitle`. */
        const val PALETTE = 0x08AA239C

        /** BG and OBJ palette colours together. */
        const val PALETTE_COLORS = 0x200

        /** BG palette colours (the first half of the palette buffer). */
        const val BG_PALETTE_COLORS = 0x100

        /** `Pal_08B1756C`. */
        const val CARD_PALETTE = 0x08B1756C
    }
}
