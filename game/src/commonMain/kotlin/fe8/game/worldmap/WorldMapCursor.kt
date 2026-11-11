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

/** The fields of `struct GmapCursorProc` (`gProcScr_GmapCursor`). */
public class GmapCursorProc {
    /** `chr` (+0x2C): VRAM offset of the cursor tiles. */
    public var chr: Int = 0

    /** `pal` (+0x30): the OBJ palette bank. */
    public var pal: Int = 0

    /** `unk_31` (+0x31), unreferenced. */
    public var unk31: Int = 0

    /** `unk_32` (+0x32, `s8`): the last `gGMData.sprite_disp`. */
    public var unk32: Int = 0

    /** `frameIdx` (+0x33, `s8`): index into the cursor sprite table. */
    public var frameIdx: Int = 0

    /** `unk_34` (+0x34): the palette color index of the blink. */
    public var unk34: Int = 0

    /** `unk_35` (+0x35): frames until the next blink step. */
    public var unk35: Int = 0

    /** `unk_38` (+0x38): the last `gGMData.ix`. */
    public var unk38: Int = 0

    /** `unk_3c` (+0x3C): the last `gGMData.iy`. */
    public var unk3c: Int = 0

    /** `unk_40` (+0x40). */
    public var unk40: Int = 0

    /** `unk_44` (+0x44). */
    public var unk44: Int = 0

    /** `unk_48` (+0x48, `u16`). */
    public var unk48: Int = 0

    /** `pScreenProc` (+0x4C). */
    public var screen: GmScreenProc? = null
}

/**
 * The world map cursor (`src/worldmap_cursor.c`, `gProcScr_GmapCursor`).
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
     * `gProcScr_GmapCursor`.
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
     * Starts the proc in root tree [rootIndex].
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
     * Clears the fields and sets the blink color (`gPal_08A97ACC[0]`) in OBJ palette 4, color
     * 14.
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
     * Follows `gGMData.sprite_disp`, `ix` and `iy`. The drawing and blinking (`sprite_disp > 0`)
     * never run in the prologue capture (`sprite_disp` stays 0) and are not verified.
     */
    public fun gmapCursorLoop(proc: GmapCursorProc) {
        proc.unk32 = gmData.spriteDisp.toByte().toInt()
        proc.unk38 = gmData.ix
        proc.unk3c = gmData.iy
        if (proc.unk32 <= 0) return
        val screen = checkNotNull(proc.screen)
        val x = screen.x
        val y = screen.y
        // gUnknown_08206498[frameIdx]: (tile offset 0 or 2, gObject_16x16, x 0, y 16).
        layers.put(
            4,
            (gmData.ix shr 8) - x,
            (gmData.iy shr 8) - y - 16,
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

    /** `PAL_OBJ_COLOR(4, 14) = gPal_08A97ACC[index]` and `EnablePaletteSync`. */
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

        private const val OBJ_PALETTE = 0x100
        private const val CHR_SIZE = 0x20
        private const val PROC_MARK_WMSTUFF = 8
    }
}
