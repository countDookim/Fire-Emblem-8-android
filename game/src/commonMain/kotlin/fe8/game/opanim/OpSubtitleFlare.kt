package fe8.game.opanim

import fe8.game.RomView
import gbafe.core.math.Interpolate
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.render.BgAffin
import gbafe.render.BgHardware
import gbafe.render.BgTilemap

/** The opening subtitle's lens-flare child proc: BG2 zoom, spin and alpha fade. */
public class OpSubtitleFlare(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val rom: RomView,
) {
    /** Proc fields of the flare child. */
    public class State(
        /** `unk_4c` (s16): frame counter. */
        public var frame: Int = 0,
        /** `unk_4e` (s16): rotation angle. */
        public var angle: Int = 0,
    )

    /**
     * Clears the counters, draws the lens-flare TSA into the BG2 map buffer, syncs BG2 and shows
     * BG0 and BG2 only.
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
     * Advances the flare by one frame: BG2 rotation/scale/anchor and blend coefficient from the
     * frame counter; breaks [proc] after frame 59.
     *
     * Ported from src/opsubtitle.c: Subtitle_LightFlareFx_Loop
     */
    public fun loop(proc: Proc, state: State) {
        val frame = state.frame
        var scalePulse: Int
        val alphaCoefficient: Int
        if (frame < 30) {
            scalePulse = Interpolate.interpolate(5, 4, 50, frame, 30)
            alphaCoefficient = Interpolate.interpolate(4, 0, 16, frame, 30)
        } else {
            scalePulse = Interpolate.interpolate(0, 50, 100, frame - 30, 30)
            alphaCoefficient = Interpolate.interpolate(0, 16, 0, frame - 30, 30)
        }
        if (scalePulse > 49) scalePulse = 100 - scalePulse
        val anchorX = Interpolate.interpolate(0, 0, 214, frame, 60)
        val anchorY = Interpolate.interpolate(0, 16, 128, frame, 60)
        val lcd = hardware.lcd
        BgAffin.rotScaling(lcd, 2, state.angle, 0, 0, scalePulse * 5 + 0x80, scalePulse * 5 + 0x80)
        BgAffin.scaling(lcd, 2, 0x100, 0x100)
        BgAffin.anchoring(lcd, 2, anchorX, anchorY, 80, 72)
        state.angle = (state.angle - 64).toShort().toInt()
        lcd.setBlendConfig(1, alphaCoefficient, 0x10, 0)
        state.frame = (state.frame + 1).toShort().toInt()
        if (state.frame > 59) engine.breakProc(proc)
    }

    /**
     * Hides BG2.
     *
     * Ported from src/opsubtitle.c: Subtitle_LightFlareFx_End
     */
    public fun end() {
        hardware.lcd.dispcnt = hardware.lcd.dispcnt and 0x400.inv()
    }

    private companion object {
        const val TSA_LENS_FLARE = 0x08B18D68
    }
}
