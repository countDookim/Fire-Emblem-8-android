package fe8.game.opanim

import fe8.game.RomView
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.sleep
import gbafe.render.BgHardware

/**
 * The opening monologue's scene set-up callbacks: the lens-flare scene with the title card
 * (slide 2) and the final scene that hosts slides 3 to 5. They build on [setup] and [slides],
 * which must share [hardware] and [rom].
 */
public class OpSubtitleScenes(
    private val hardware: BgHardware,
    private val rom: RomView,
    private val setup: OpSubtitleSetup,
    private val slides: OpSubtitleSlides,
) {
    /**
     * Prepares the lens-flare scene: the full-screen background image and its indexed map on
     * BG1 (palette bank 15, cleared to black so it can be faded in), VRAM 0x5000..0x6000 cleared
     * (room for the title card's graphics), BG0's map filled with tile 0x280 (the blank tile) to
     * empty it, then a 22-frame hold with the transition timer cleared. The proc's state must be
     * [OpSubtitleSlides.State].
     *
     * Ported from src/opsubtitle.c: sub_80C50A0
     */
    public fun clearForFlare(state: OpSubtitleSlides.State) {
        setup.loadCommonScreen(1)
        hardware.palette.fill(0, 0xF * 16, 0x10 * 16)
        setup.fillIndexedMap(1)
        hardware.enableBgSyncByMask(1 shl 1)
        hardware.vram.fill(0, 0x5000, 0x6000)
        hardware.fill(hardware.mapBuffer(0), 0x280)
        hardware.enableBgSyncByMask(1 shl 0)
        state.holdTimer = 22
        state.transitionTimer = 0
    }

    /**
     * Shows the title card: loads slide 2 at the alternate offset (VRAM 0x5000, BG0) and the
     * slide palette `gPal_OpSubtitle` into BG palette 3. Run by the child proc 36 frames into
     * the flare scene and again by [setupFinalScene].
     *
     * Ported from src/opsubtitle.c: sub_80C51A8
     */
    public fun loadFinalCard() {
        slides.loadAtAlternateOffset(2)
        rom.pal(PALETTE, 16).copyInto(hardware.palette, 3 * 16)
        hardware.enablePaletteSync()
    }

    /**
     * The title card's child proc: yields once, sleeps 36 frames, then runs [loadFinalCard],
     * so the card appears during the flare.
     *
     * Transcribed from src/opsubtitle.c: gProcScr_08AA2184 (not verified: no @VerifiesPort test
     * names it)
     */
    public val finalCardScript: ProcScript = ProcScript(
        "gProcScr_08AA2184",
        listOf(sleep(0), sleep(36), call(ProcFunc { loadFinalCard() }), end()),
    )

    /**
     * Sets up the final scene that hosts slides 3 to 5: the [OpSubtitleSetup.setupBackgrounds]
     * layout, mode 0 with BG priorities 1, 2, 3, 0 for BG0..BG3 (BG3, the background image, is
     * in front), alpha blend with BG3 as first target and BG0 and BG1 as second targets (the
     * slides' text layers show through as BG3's weight drops), no backdrop targets, BG1
     * scrolled to (-3, -3) (the BG1 copy of a slide is drawn offset from BG0's; see
     * [OpSubtitleSlides.loadDouble]), the background image and indexed map on BG3, empty BG1 and
     * BG2 maps, the title card (slide 2), BG0 and BG3 shown, slide index 2 and a 120-frame hold.
     * The proc's state must be [OpSubtitleSlides.State].
     *
     * Ported from src/opsubtitle.c: sub_80C5218
     */
    public fun setupFinalScene(state: OpSubtitleSlides.State) {
        val lcd = hardware.lcd
        setup.setupBackgrounds()
        lcd.dispcnt = lcd.dispcnt and 7.inv()
        val priorities = intArrayOf(1, 2, 3, 0)
        for (bg in 0..3) lcd.bgCnt[bg] = (lcd.bgCnt[bg] and 3.inv()) or priorities[bg]
        lcd.setBlendConfig(1, 0, 0x10, 0)
        lcd.setBlendTargetA(0, 0, 0, 1, 0)
        lcd.setBlendTargetB(1, 1, 0, 0, 0)
        lcd.setBlendBackdropA(0)
        lcd.setBlendBackdropB(0)
        lcd.setBgPosition(0, 0, 0)
        lcd.setBgPosition(1, -3, -3)
        lcd.setBgPosition(2, 0, 0)
        lcd.setBgPosition(3, 0, 0)
        setup.loadCommonScreen(3)
        setup.fillIndexedMap(3)
        hardware.fill(hardware.mapBuffer(1), 0)
        hardware.fill(hardware.mapBuffer(2), 0)
        hardware.enableBgSyncByMask((1 shl 1) or (1 shl 2) or (1 shl 3))
        slides.loadAtAlternateOffset(2)
        lcd.setDispEnable(1, 0, 0, 1, 0)
        state.index = 2
        state.holdTimer = 120
    }

    private companion object {
        /** `gPal_OpSubtitle`. */
        const val PALETTE = 0x08AA239C
    }

    /**
     * Resets the BG1 scroll offset from the final scene's (-3, -3) to (0, 0) once the monologue
     * has finished (label 7).
     *
     * Ported from src/opsubtitle.c: sub_80C540C
     */
    public fun resetCardOffset() {
        hardware.lcd.setBgPosition(1, 0, 0)
    }
}
