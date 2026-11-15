package fe8.game.mapanim

import fe8.game.RomView
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.setEndCb
import gbafe.render.BgHardware

/**
 * The stat gain labels of the map-animation level-up panel: the label proc that owns the sprite
 * tiles and palettes, and the animations started for each gained stat.
 */
public class MapLevelUpGainLabels(
    private val engine: ProcEngine,
    private val aps: ApProcs,
    private val hardware: BgHardware,
    private val rom: RomView,
) {
    private val color = MapLevelUpLabelColor(hardware, rom)

    /** Fields of `ManimLevelUpStatGainLabelProc`. */
    public class State {
        /** `chr`: first OBJ tile of the label graphics. */
        public var chr: Int = 0

        /** `pal`: first of two OBJ palette banks. */
        public var pal: Int = 0

        /** `sprite_layer`: the object layer the animations draw in. */
        public var spriteLayer: Int = 0
    }

    /** `ProcScr_ManimLevelUpStatGainLabel`. */
    public val script: ProcScript = ProcScript(
        "ProcScr_ManimLevelUpStatGainLabel",
        listOf(setEndCb(ProcFunc { finish(it) }), block()),
        { State() },
    )

    /**
     * Starts the label proc under [parent], loads the label graphics at OBJ tile [chr] and the
     * label palette into banks [pal] and [pal] + 1 and starts the palette cycle child.
     *
     * Verified against `ram_trace_prologue_levelup` (proc fields, OBJ tiles and palette banks).
     *
     * Ported from src/mapanim_lvupfx.c: StartManimLevelUpStatGainLabels
     */
    public fun start(chr: Int, pal: Int, spriteLayer: Int, parent: Proc): Proc {
        val label = engine.start(script, parent)
        val state = label.state as State
        state.chr = chr
        state.pal = pal
        state.spriteLayer = spriteLayer

        rom.decompressInto(IMG_STAT_GAIN, hardware.vram, OBJ_VRAM0 + (chr and 0x3FF) * CHR_SIZE)
        applyPalette(0x10 + pal)
        applyPalette(0x10 + pal + 1)

        val child = engine.start(color.script, label)
        (child.state as MapLevelUpLabelColor.State).pal = pal
        return label
    }

    /**
     * Ends the label proc, which in turn ends every animation proc.
     *
     * Ported from src/mapanim_lvupfx.c: EndManimLevelUpStatGainLabels
     */
    public fun end() {
        engine.endEach(script)
    }

    /**
     * Starts the animation procs of one stat gain at ([x], [y]): the "+" or "-" mark, the digit
     * and, for a gain, the stat-up marker, and loads the digit tiles into OBJ VRAM. [stat] 0 is
     * the level.
     *
     * Verified against `ram_trace_prologue_levelup` for a level and gains of HP, Skl, Lck and Def
     * (animation procs and handles every step, digit tiles at the snapshots). The loss branch
     * (`gain < 0`) is not covered by the capture.
     *
     * Ported from src/mapanim_lvupfx.c: StartManimLevelUpStatGainLabelAnim
     */
    public fun startAnim(x: Int, y: Int, stat: Int, gain: Int) {
        val label = engine.find(script)!!.state as State
        val chrCommon = label.chr
        val chrThisStat = label.chr + (stat - 1) * 2
        val palBits = { pal: Int -> pal shl 12 }
        val layerBits = label.spriteLayer shl 10

        if (stat == 0) {
            aps.create(
                ANIM_STAT_GAIN, x - 18, y - 4,
                palBits(label.pal) + chrCommon + layerBits, 0, 2,
            )
            return
        }
        val loss = if (gain > 0) 0 else 1
        aps.create(
            ANIM_STAT_GAIN, x, y,
            palBits(label.pal + loss) + chrCommon + layerBits, 1 + loss, 2,
        )
        aps.create(
            ANIM_STAT_GAIN, x - 3, y,
            palBits(label.pal) + chrThisStat + layerBits, 3 + loss, 2,
        )
        if (gain > 0) {
            aps.create(
                ANIM_STAT_GAIN, x - 18, y - 4,
                palBits(label.pal) + chrCommon + layerBits, 0, 2,
            )
        }
        if (gain < 0) {
            vramCopy(IMG_DIGITS + 0x20 * CHR_SIZE, (chrThisStat + 0x4C) and 0x3FF)
        }
        val abs = if (gain < 0) -gain else gain
        vramCopy(IMG_DIGITS + (abs and 0x3FF) * CHR_SIZE, (chrThisStat + 0x2D) and 0x3FF)
        vramCopy(IMG_DIGITS + ((abs + 0x20) and 0x3FF) * CHR_SIZE, (chrThisStat + 0x4D) and 0x3FF)
    }

    /**
     * Ends all animation procs when the label proc ends.
     *
     * Ported from src/mapanim_lvupfx.c: ManimLevelUpStatGainLabel_Finish
     */
    public fun finish(@Suppress("UNUSED_PARAMETER") proc: Proc) {
        aps.deleteAll()
    }

    // ApplyPalette(Pal_ManimLevelUp, palId): sixteen colors into the palette buffer.
    private fun applyPalette(palId: Int) {
        for (i in 0 until 16) {
            hardware.palette[palId * 16 + i] = rom.u16(PAL_STAT_GAIN + 2 * i).toShort()
        }
        hardware.enablePaletteSync()
    }

    // VramCopy(src, OBJ_VRAM0 + (tile << 5), CHR_SIZE) from the ROM.
    private fun vramCopy(src: Int, tile: Int) {
        for (i in 0 until CHR_SIZE) {
            hardware.vram[OBJ_VRAM0 + tile * CHR_SIZE + i] = rom.u8(src + i).toByte()
        }
    }

    private companion object {
        const val IMG_STAT_GAIN = 0x089ACA08
        const val IMG_DIGITS = 0x089ACC98
        const val PAL_STAT_GAIN = 0x089AC9A8
        const val ANIM_STAT_GAIN = 0x089A5314
        const val OBJ_VRAM0 = 0x10000
        const val CHR_SIZE = 0x20
    }
}
