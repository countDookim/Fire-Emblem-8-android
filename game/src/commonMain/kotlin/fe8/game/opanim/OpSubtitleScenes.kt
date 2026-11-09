package fe8.game.opanim

import fe8.game.RomView
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.sleep
import gbafe.render.BgHardware

/**
 * The opening subtitle's scene set-up callbacks (lens-flare card and final scene). They build on
 * [setup] and [slides], which must share [hardware] and [rom].
 */
public class OpSubtitleScenes(
    private val hardware: BgHardware,
    private val rom: RomView,
    private val setup: OpSubtitleSetup,
    private val slides: OpSubtitleSlides,
) {
    /**
     * Prepares the card scene: common screen and indexed map on BG1, palette bank 0xF cleared,
     * VRAM 0x5000..0x6000 cleared, BG0 map filled with 0x280, then a 22-frame hold with the
     * transition timer cleared. The proc's state must be [OpSubtitleSlides.State].
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
     * Loads slide 2 at the alternate offset and the subtitle palette into BG palette 3.
     *
     * Ported from src/opsubtitle.c: sub_80C51A8
     */
    public fun loadFinalCard() {
        slides.loadAtAlternateOffset(2)
        rom.pal(PALETTE, 16).copyInto(hardware.palette, 3 * 16)
        hardware.enablePaletteSync()
    }

    /**
     * The flare's child proc: yields once, sleeps 36 frames, then runs [loadFinalCard].
     *
     * Transcribed from src/opsubtitle.c: gProcScr_08AA2184
     */
    public val finalCardScript: ProcScript = ProcScript(
        "gProcScr_08AA2184",
        listOf(sleep(0), sleep(36), call(ProcFunc { loadFinalCard() }), end()),
    )

    /**
     * Sets up the final scene: backgrounds, mode 0 with BG priorities 1, 2, 3, 0, blending, BG1
     * offset (-3, -3), the common screen and indexed map on BG3, empty BG1 and BG2 maps, slide 2,
     * BG0 and BG3 shown, slide index 2 and a 120-frame hold. The proc's state must be
     * [OpSubtitleSlides.State].
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
        const val PALETTE = 0x08AA239C
    }

    /**
     * Resets the BG1 scroll offset to (0, 0) once the final scene has finished.
     *
     * Ported from src/opsubtitle.c: sub_80C540C
     */
    public fun resetCardOffset() {
        hardware.lcd.setBgPosition(1, 0, 0)
    }
}
