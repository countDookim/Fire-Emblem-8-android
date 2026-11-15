package fe8.game.worldmap

import fe8.game.RomView
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers

/**
 * Per-proc state of the world map cursor `gProcScr_GmapCursor`: the fields of
 * `struct GmapCursorProc` (include/worldmap.h:152).
 */
public class GmapCursorProc {
    /** `chr` (+0x2C): byte offset of the cursor tiles in OBJ VRAM (0x12C0 from the main proc). */
    public var chr: Int = 0

    /** `pal` (+0x30): the OBJ palette bank of the cursor sprite (4 from the main proc). */
    public var pal: Int = 0

    /** `unk_31` (+0x31): "set to 0 on init and unreferenced" (decomp comment). */
    public var unk31: Int = 0

    /** `unk_32` (+0x32, `s8`): the last `gGMData.sprite_disp` (the cursor is shown while > 0). */
    public var unk32: Int = 0

    /**
     * `frameIdx` (+0x33, `s8`): index into `gUnknown_08206498`, whose two entries differ only in
     * tile offset (0 or 2). Only `GmapCursor_Init` writes it (0), so the cursor always uses the
     * first.
     */
    public var frameIdx: Int = 0

    /** `unk_34` (+0x34): index (0..15) into the blink palette `gPal_08A97ACC`. */
    public var unk34: Int = 0

    /** `unk_35` (+0x35): frames until the blink color steps (reset to 4). */
    public var unk35: Int = 0

    /** `unk_38` (+0x38): the last `gGMData.ix`. */
    public var unk38: Int = 0

    /** `unk_3c` (+0x3C): the last `gGMData.iy`. */
    public var unk3c: Int = 0

    /** `unk_40` (+0x40): `unk_38` as of the last frame the cursor was drawn; never read. */
    public var unk40: Int = 0

    /** `unk_44` (+0x44): `unk_3c` as of the last frame the cursor was drawn; never read. */
    public var unk44: Int = 0

    /** `unk_48` (+0x48, `u16`): set to 0x300 on init and never read (meaning unknown). */
    public var unk48: Int = 0

    /** `pScreenProc` (+0x4C): the world map screen proc; its scroll offsets the cursor. */
    public var screen: GmScreenProc? = null
}

/**
 * The world map cursor (`src/worldmap_cursor.c`, `gProcScr_GmapCursor`): the blinking sprite
 * that marks the cursor's position (`gGMData.ix`, `iy`) on the world map while
 * `sprite_disp` is above 0.
 *
 * @param engine the proc scheduler
 * @param hardware the OBJ palette buffer
 * @param rom the ROM (blink palette, sprite object)
 * @param gmData `gGMData`
 * @param layers the sprite lists the cursor is drawn into
 */
public class WorldMapCursor(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val rom: RomView,
    private val gmData: GmData,
    private val layers: SpriteLayers,
) {
    /**
     * `gProcScr_GmapCursor` (proc name "GmapCursor"): init, then [gmapCursorLoop] every frame.
     *
     * Transcribed from src/worldmap_cursor.c: gProcScr_GmapCursor (not verified: script order
     * checked against the `procs` capture in WorldMapNodeIconCursorTest, which cannot tag a
     * script; the end callback `nullsub_59` does nothing)
     */
    public val script: ProcScript = ProcScript(
        "gProcScr_GmapCursor",
        listOf(
            name("GmapCursor"),
            mark(PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc {}),
            call(ProcFunc { gmapCursorInit(it.state as GmapCursorProc) }),
            repeat(ProcFunc { gmapCursorLoop(it.state as GmapCursorProc) }),
            end(),
        ),
    ) { GmapCursorProc() }

    /**
     * Ported from src/worldmap_cursor.c: NewGmapCursor
     *
     * Starts the cursor proc in root tree [rootIndex] (the decomp's `parent` argument;
     * the world map's `WorldMap_Init` passes `PROC_TREE_5`).
     *
     * @param chr byte offset of the cursor tiles in OBJ VRAM
     * @param pal OBJ palette bank
     * @param screen the world map screen proc whose scroll the cursor subtracts
     */
    public fun newGmapCursor(rootIndex: Int, chr: Int, pal: Int, screen: GmScreenProc): Proc {
        val proc = engine.start(script, rootIndex)
        val state = proc.state as GmapCursorProc
        state.chr = chr
        state.pal = pal
        state.screen = screen
        return proc
    }

    /**
     * Ported from src/worldmap_cursor.c: GmapCursor_Init
     *
     * Clears the fields (`unk_48` to 0x300, the blink timer `unk_35` to 4) and sets the blink
     * color (`gPal_08A97ACC[0]`) in OBJ palette 4, color 14.
     */
    public fun gmapCursorInit(proc: GmapCursorProc) {
        proc.unk31 = 0
        proc.unk32 = 0
        proc.frameIdx = 0
        proc.unk3c = 0
        proc.unk38 = 0
        proc.unk44 = 0
        proc.unk40 = 0
        proc.unk48 = 0x300
        proc.unk34 = 0
        proc.unk35 = 4
        setBlinkColor(0)
    }

    /**
     * Ported from src/worldmap_cursor.c: GmapCursor_Loop
     *
     * Copies `gGMData.sprite_disp`, `ix` and `iy` into the proc. While `sprite_disp > 0` it
     * draws the cursor (`gObject_16x16` in sprite layer 4, at the world map position minus the
     * screen scroll and 16 pixels up, tile offset from `frameIdx`, OAM priority 2) and every 4
     * frames steps the blink color through the 16 colors of `gPal_08A97ACC`. The drawing and
     * blinking never run in the Prologue capture (`sprite_disp` stays 0) and are not verified.
     */
    public fun gmapCursorLoop(proc: GmapCursorProc) {
        proc.unk32 = gmData.spriteDisp.toByte().toInt()
        proc.unk38 = gmData.ix
        proc.unk3c = gmData.iy
        if (proc.unk32 <= 0) return
        val screen = checkNotNull(proc.screen)
        val x = screen.x
        val y = screen.y
        // gUnknown_08206498[frameIdx] (worldmap_cursor.c): (tile offset 0 or 2, gObject_16x16,
        // x 0, y 16). The last argument is OAM attribute 2: tile number + OAM2_PAL + OAM2_LAYER(2).
        layers.put(
            4,
            (gmData.ix shr 8) - x,
            (gmData.iy shr 8) - y - SPRITE_Y_OFFSET,
            rom.objN(OBJECT_16X16),
            (proc.chr / CHR_SIZE) + (proc.frameIdx * 2) + (proc.pal shl 12) + (2 shl 10),
        )
        proc.unk35--
        if (proc.unk35 == 0) {
            proc.unk34++
            if (proc.unk34 > 15) proc.unk34 = 0
            setBlinkColor(proc.unk34)
            proc.unk35 = 4
        }
        proc.unk40 = proc.unk38
        proc.unk44 = proc.unk3c
    }

    /**
     * Sets the blinking color, `PAL_OBJ_COLOR(4, 14) = gPal_08A97ACC[index]`, and requests a
     * palette sync (`EnablePaletteSync`).
     */
    private fun setBlinkColor(index: Int) {
        hardware.palette[OBJ_PALETTE + 4 * 16 + 14] = rom.u16(BLINK_PALETTE + 2 * index).toShort()
        hardware.enablePaletteSync()
    }

    /** ROM addresses and layout constants. */
    public companion object {
        /** `gPal_08A97ACC`: the 16 blink colors. */
        public const val BLINK_PALETTE: Int = 0x08A97ACC

        /** `gObject_16x16`. */
        public const val OBJECT_16X16: Int = 0x08590F4C

        /** `gmapCursorLoop`: the cursor sits 16 pixels above its position (`y` of the table). */
        private const val SPRITE_Y_OFFSET = 16

        /** Palette buffer index of the OBJ palettes (`PAL_OBJ_COLOR`). */
        private const val OBJ_PALETTE = 0x100

        /** Bytes per 4bpp tile (`CHR_SIZE`). */
        private const val CHR_SIZE = 0x20

        /** `PROC_MARK_WMSTUFF`. */
        private const val PROC_MARK_WMSTUFF = 8
    }
}
