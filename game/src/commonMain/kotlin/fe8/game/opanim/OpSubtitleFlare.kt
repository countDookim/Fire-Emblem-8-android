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
        val t = state.frame
        var a: Int
        val b: Int
        if (t < 30) {
            a = Interpolate.interpolate(5, 4, 50, t, 30)
            b = Interpolate.interpolate(4, 0, 16, t, 30)
        } else {
            a = Interpolate.interpolate(0, 50, 100, t - 30, 30)
            b = Interpolate.interpolate(0, 16, 0, t - 30, 30)
        }
        if (a > 49) a = 100 - a
        val c = Interpolate.interpolate(0, 0, 214, t, 60)
        val d = Interpolate.interpolate(0, 16, 128, t, 60)
        val lcd = hardware.lcd
        BgAffin.rotScaling(lcd, 2, state.angle, 0, 0, a * 5 + 0x80, a * 5 + 0x80)
        BgAffin.scaling(lcd, 2, 0x100, 0x100)
        BgAffin.anchoring(lcd, 2, c, d, 80, 72)
        state.angle = (state.angle - 64).toShort().toInt()
        lcd.setBlendConfig(1, b, 0x10, 0)
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
