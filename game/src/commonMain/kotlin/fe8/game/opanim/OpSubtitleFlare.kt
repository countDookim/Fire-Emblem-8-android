package fe8.game.opanim

import fe8.game.RomView
import gbafe.core.math.Interpolate
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.render.BgAffin
import gbafe.render.BgHardware
import gbafe.render.BgTilemap

/**
 * The lens-flare child proc (`gProcScr_OpSubtitle_LightFlareFx`) of the opening monologue's
 * flare scene: a 60-frame light flare on BG2 that grows and shrinks, spins and sweeps across the
 * screen while fading in and out. The decomp names its image `Img_ChapterIntro_LensFlare` and
 * its map `Tsa_ClassReel_LensFlare`, so both are shared with other screens.
 */
public class OpSubtitleFlare(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val rom: RomView,
) {
    /** Proc fields of the flare child. */
    public class State(
        /** `unk_4c` (s16) in the decomp: frame counter, 0..59. */
        public var frame: Int = 0,
        /** `unk_4e` (s16) in the decomp: BG2 rotation angle (0x1000 = one turn), -64 per frame. */
        public var angle: Int = 0,
    )

    /**
     * Clears the counters, draws the lens-flare map (a byte TSA with 32 entries per row, tile base
     * 0) into the BG2 map buffer, syncs BG2 and shows BG0 and BG2 only (OBJ off).
     *
     * Ported from src/opsubtitle.c: Subtitle_LightFlareFx_Init
     */
    public fun init(state: State) {
        state.frame = 0
        state.angle = 0
        val tsa = rom.blob(TSA_LENS_FLARE)
        BgTilemap.applyByteTsa(hardware.mapBuffer(2), tsa.data, tsa.offset, 0, 5)
        hardware.enableBgSyncByMask(4)
        hardware.lcd.setDispEnable(1, 0, 1, 0, 0)
    }

    /**
     * Advances the flare by one frame; breaks [proc] after frame 59. The scale pulse grows from
     * 4 to 50 over frames 0..29 and then falls back towards 0, giving BG2 a scale of
     * `pulse * 5 + 0x80` (8.8, 0x100 = 1x, so about 0.6x to 1.45x and back to 0.5x). The angle
     * turns by -64 each frame. The screen anchor of texture point (80, 72) sweeps from (0, 16)
     * to (214, 128) over the 60 frames. BG2 is the first blend target, so its coefficient (0 to
     * 16 with an ease-out square over frames 0..29, then 16 to 0 linearly) fades the flare in and
     * out against the second coefficient 16.
     *
     * Ported from src/opsubtitle.c: Subtitle_LightFlareFx_Loop
     */
    public fun loop(proc: Proc, state: State) {
        val frame = state.frame
        var scalePulse: Int
        val alphaCoefficient: Int
        if (frame < 30) {
            scalePulse = Interpolate.interpolate(Interpolate.RCUBIC, 4, 50, frame, 30)
            alphaCoefficient = Interpolate.interpolate(Interpolate.RSQUARE, 0, 16, frame, 30)
        } else {
            scalePulse = Interpolate.interpolate(Interpolate.LINEAR, 50, 100, frame - 30, 30)
            alphaCoefficient = Interpolate.interpolate(Interpolate.LINEAR, 16, 0, frame - 30, 30)
        }
        // From 50 on, the pulse mirrors back down (50 -> 100 becomes 50 -> 0).
        if (scalePulse > 49) scalePulse = 100 - scalePulse
        val anchorX = Interpolate.interpolate(Interpolate.LINEAR, 0, 214, frame, 60)
        val anchorY = Interpolate.interpolate(Interpolate.LINEAR, 16, 128, frame, 60)
        val lcd = hardware.lcd
        BgAffin.rotScaling(lcd, 2, state.angle, 0, 0, scalePulse * 5 + 0x80, scalePulse * 5 + 0x80)
        BgAffin.scaling(lcd, 2, 0x100, 0x100) // 0x100 = 1x: no further change
        BgAffin.anchoring(lcd, 2, anchorX, anchorY, 80, 72)
        state.angle = (state.angle - 64).toShort().toInt()
        lcd.setBlendConfig(1, alphaCoefficient, 0x10, 0)
        state.frame = (state.frame + 1).toShort().toInt()
        if (state.frame > 59) engine.breakProc(proc)
    }

    /**
     * Hides BG2, the flare layer, when the flare child ends normally.
     *
     * Ported from src/opsubtitle.c: Subtitle_LightFlareFx_End
     */
    public fun end() {
        hardware.lcd.dispcnt = hardware.lcd.dispcnt and 0x400.inv()
    }

    private companion object {
        /** `Tsa_ClassReel_LensFlare`. */
        const val TSA_LENS_FLARE = 0x08B18D68
    }
}
