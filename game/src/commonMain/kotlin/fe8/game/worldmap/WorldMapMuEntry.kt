package fe8.game.worldmap

import fe8.game.RomView
import gbafe.core.math.Spline
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware
import gbafe.render.BgTilemap

/**
 * The fields of `struct GmapMuEntryProc` (`src/worldmap_entry.c:31`), the state of
 * `gProcScr_GmapMuEntry2`. The hide transition (`unk_2c`..`unk_2f` set to the reverse of the show
 * values) is not run by any capture.
 */
public class GmapMuEntryProc {
    /** `flags_0` (+0x29, bit 0): a show/hide transition is running. */
    public var flags0: Boolean = false

    /** `unk_2a` (+0x2A): height of the top band in scanlines. */
    public var unk2a: Int = 0

    /** `unk_2b` (+0x2B): height of the bottom band in scanlines. */
    public var unk2b: Int = 0

    /** `unk_2c` (+0x2C): top band height at the start of a transition. */
    public var unk2c: Int = 0

    /** `unk_2d` (+0x2D): top band height at the end of a transition. */
    public var unk2d: Int = 0

    /** `unk_2e` (+0x2E): bottom band height at the start of a transition. */
    public var unk2e: Int = 0

    /** `unk_2f` (+0x2F): bottom band height at the end of a transition. */
    public var unk2f: Int = 0

    /** `unk_30` (+0x30, `s16`): the easing power passed to `_DivArm1`. */
    public var unk30: Int = 0

    /** `unk_34` (+0x34): transition length, `speed << 12`. */
    public var unk34: Int = 0

    /** `unk_38` (+0x38): elapsed transition time, in steps of 0x1000. */
    public var unk38: Int = 0

    /** `unk_3c` (+0x3C): `unk_2d - unk_2c`. */
    public var unk3c: Int = 0

    /** `unk_40` (+0x40): `unk_2f - unk_2e`. */
    public var unk40: Int = 0
}

/**
 * The "Gmap Mu Entry" proc of the world map (`src/worldmap_entry.c`, `gProcScr_GmapMuEntry2`):
 * it fades in (or out) two shaded bands at the top and bottom of the map with a scanline
 * effect and an alpha blend while the map units are shown or hidden. In the Prologue it is
 * started by `WorldMap_CallBeginningEvent`, runs `GmMuEntry_Init`, yields once, waits blocked at
 * label 0, runs one show transition (`GmMuEntryStartShow(40, 0x100)`, 40 frames) and waits again
 * until `EndGmMuEntry` ends it.
 *
 * Limits: no capture calls `GmMuEntryStartHide`, so `GmMuEntry_80BFFD0`, `GmMuEntry_80C0144`,
 * `GmMuEntryStartHide`, the flag accessors and `GmMuEntryExists` are not written, and the
 * hide commands of the script fail loudly. The callers of `GmMuEntryStartShow` and
 * `EndGmMuEntry` (event and world-map exit code, not ported) are fed from the trace in the test.
 * `GmMuEntry_OnEnd_Null` does nothing and is written as an empty callback.
 *
 * @param engine the proc scheduler
 * @param hardware BG0 tilemap, VRAM, palette and `gLCDControlBuffer`
 * @param rom the ROM the bands' graphics are read from
 * @param hblank the scanline-effect state the bands are drawn through
 */
public class WorldMapMuEntry(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val rom: RomView,
    private val hblank: WorldMapHblank,
) {
    private val genericBuffer = ByteArray(GENERIC_BUFFER_SIZE)

    /**
     * `gProcScr_GmapMuEntry2`.
     *
     * Transcribed from src/worldmap_entry.c: gProcScr_GmapMuEntry2 (not verified: the commands
     * of the second, hide transition fail loudly because their callbacks are not written; the
     * decomp's second `PROC_LABEL(1)` is kept as printed, so `Proc_Goto(proc, 1)` and
     * `Proc_Goto(proc, 2)` both reach the first one. The script position is checked per step in
     * WorldMapMuEntryTest, which cannot tag a script.)
     */
    public val script: ProcScript = ProcScript(
        "gProcScr_GmapMuEntry2",
        listOf(
            name("Gmap Mu Entry"),
            mark(PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { }),
            call(ProcFunc { gmMuEntryInit(it.state as GmapMuEntryProc) }),
            yield(),
            label(0),
            block(),
            label(1),
            call(ProcFunc { gmMuEntry80BFEF8(it.state as GmapMuEntryProc) }),
            repeat(ProcFunc { gmMuEntry80C0080(it) }),
            goto(0),
            label(1),
            call(ProcFunc { notRun() }),
            repeat(ProcFunc { notRun() }),
            goto(0),
            label(3),
            end(),
        ),
    ) { GmapMuEntryProc() }

    /**
     * Ported from src/worldmap_entry.c: StartGmapMuEntry
     *
     * Only the `parent == NULL` branch is taken in the Prologue. The original does not return the
     * proc (its `return` is commented out as a bug); this returns it for the test's benefit.
     *
     * @param parent the proc to block, or null to start in root tree 3
     */
    public fun startGmapMuEntry(parent: Proc? = null): Proc? =
        if (parent != null) {
            // (not verified: the Prologue passes NULL)
            engine.startBlocking(script, parent)
        } else {
            engine.start(script, ROOT_TREE)
        }

    /**
     * Ported from src/worldmap_entry.c: GetGmMuEntryFlag
     *
     * `flags_0` of the running proc (a transition is in progress).
     */
    public fun getGmMuEntryFlag(): Boolean =
        (checkNotNull(engine.find(script)).state as GmapMuEntryProc).flags0

    /**
     * Ported from src/worldmap_entry.c: EndGmMuEntry
     */
    public fun endGmMuEntry() {
        engine.endEach(script)
    }

    /**
     * Ported from src/worldmap_entry.c: GmMuEntry_Init
     */
    public fun gmMuEntryInit(proc: GmapMuEntryProc) {
        proc.flags0 = false
        proc.unk2b = 0
        proc.unk2a = 0
    }

    /**
     * Ported from src/worldmap_entry.c: GmMuEntryStartShow
     *
     * Starts the transition from no bands to the full ones (16 and 56 lines) and jumps to
     * label 1. Returns 1 without doing anything if a transition is running.
     *
     * @param speed the number of frames the transition takes
     * @param blend the easing power passed to `_DivArm1`
     */
    public fun gmMuEntryStartShow(speed: Int, blend: Int): Int {
        val proc = checkNotNull(engine.find(script))
        val state = proc.state as GmapMuEntryProc
        if (state.flags0) return 1
        state.unk2c = 0
        state.unk2d = 16
        state.unk2e = 0
        state.unk2f = 56
        state.unk30 = blend.toShort().toInt()
        state.unk34 = speed shl 12
        state.flags0 = true
        engine.goto(proc, 1)
        return 0
    }

    /**
     * Ported from src/worldmap_entry.c: GmMuEntry_80BFEF8
     *
     * Start of a show transition: loads the band graphics, clears the transition counter, sets
     * both band heights to their start values, clears both scanline lines, points them at
     * `0x04000012` (BG0VOFS) and `0x05000054` (a palette color), enables the HBlank copy and
     * sets BG0 as the first and the other layers as the second blend target with alpha 0/16.
     *
     * (not verified: the `0x10` fill of line 0 and the zero fill of line 1; the same frame's
     * [gmMuEntry80C0080] rewrites both lines before any dump, so a wrong fill value is not
     * visible in the capture.)
     */
    public fun gmMuEntry80BFEF8(proc: GmapMuEntryProc) {
        sub80BFD28()
        proc.unk38 = 0
        proc.unk2a = proc.unk2c
        proc.unk2b = proc.unk2e
        proc.unk3c = proc.unk2d - proc.unk2c
        proc.unk40 = proc.unk2f - proc.unk2e
        val line0 = hblank.sub80C1DA0(0, 1)
        hblank.buffer.fill(0x10, line0, line0 + LINE)
        hblank.sub80C1DD8(0, BG0VOFS_REGISTER)
        hblank.sub80C1DE8(0)
        val line1 = hblank.sub80C1DA0(1, 1)
        hblank.buffer.fill(0, line1, line1 + LINE)
        hblank.sub80C1DD8(1, PALETTE_COLOR_REGISTER)
        hblank.sub80C1DE8(1)
        hblank.status = hblank.status or 4
        hardware.lcd.setBlendTargetA(1, 0, 0, 0, 0)
        hardware.lcd.setBlendTargetB(0, 1, 1, 1, 1)
        hardware.lcd.setBlendConfig(BLEND_EFFECT_ALPHA, 0, 0x10, 0)
    }

    /**
     * Ported from src/worldmap_entry.c: GmMuEntry_80C0080
     *
     * One frame of a show transition (`PROC_REPEAT`): advances the counter by 0x1000, eases the
     * band heights and the blend alpha, and at the end sets the final heights, turns the alpha
     * blend off, clears the transition flag and jumps to label 0. Always redraws the bands.
     */
    public fun gmMuEntry80C0080(proc: Proc) {
        val state = proc.state as GmapMuEntryProc
        val unk: Int
        state.unk38 += 0x1000
        if (state.unk38 < state.unk34) {
            unk = Spline.divArm1(state.unk38, state.unk34, state.unk30)
            state.unk2a = (state.unk2c + Spline.divArm(0x1000, state.unk3c * unk)) and 0xFF
            state.unk2b = (state.unk2e + Spline.divArm(0x1000, state.unk40 * unk)) and 0xFF
            hardware.lcd.setBlendConfig(BLEND_EFFECT_ALPHA, unk / 256, 0x10 - (unk / 256), 0)
        } else {
            hblank.sub80C1DD8(0, 0)
            unk = 0x1000
            hardware.lcd.setBlendConfig(BLEND_EFFECT_NONE, 0, 0x10, 0)
            state.unk2a = state.unk2d
            state.unk2b = state.unk2f
            state.flags0 = false
            engine.goto(proc, 0)
        }
        sub80BFDA0(state)
    }

    /**
     * Ported from src/worldmap_entry.c: sub_80BFDA0
     *
     * Rewrites the off-screen scanline page: line 0 (BG0VOFS) is `0x40 - y` everywhere, which
     * keeps BG0 in place, and line 1 (the palette color) is 0, except within the top band
     * (`unk_2a` lines from the top) and the bottom band (`unk_2b` lines from the bottom) where
     * the vertical offset shifts and the color ramps toward `(13, 17, 23)`. Then swaps the pages.
     */
    public fun sub80BFDA0(proc: GmapMuEntryProc) {
        val palA = hblank.sub80C1DA0(0, 1)
        val palB = hblank.sub80C1DA0(1, 1)
        for (i in 0 until SCREEN_HEIGHT) {
            hblank.buffer[palA + i] = ((0x40 - i) and 0x1FF).toShort()
            hblank.buffer[palB + i] = 0
        }
        for (i in 0 until proc.unk2a) {
            hblank.buffer[palA + i] = (0x10 - proc.unk2a).toShort()
            val r = ((proc.unk2a - i) * -6 / proc.unk2a) + 13
            val g = ((proc.unk2a - i) * -10 / proc.unk2a) + 17
            val b = ((proc.unk2a - i) * -7 / proc.unk2a) + 23
            hblank.buffer[palB + i] = ((b shl 10) + (g shl 5) + r).toShort()
        }
        for (i in 0 until proc.unk2b) {
            val j = SCREEN_HEIGHT - (proc.unk2b - i)
            hblank.buffer[palA + j] = (proc.unk2b + 200).toShort()
            val r = (i * -6 / proc.unk2b) + 13
            val g = (i * -10 / proc.unk2b) + 17
            val b = (i * -7 / proc.unk2b) + 23
            hblank.buffer[palB + j] = ((b shl 10) + (g shl 5) + r).toShort()
        }
        hblank.sub80C1DC8()
    }

    /**
     * Ported from src/worldmap_entry.c: sub_80BFD28
     *
     * Loads the band graphics: palette bank 2, tiles at VRAM `0x06004C00`, and the tile
     * arrangement into BG0 with tile base 0x2260 (palette 2).
     */
    public fun sub80BFD28() {
        rom.pal(PAL_BAND, 16).copyInto(hardware.palette, 2 * 16)
        hardware.enablePaletteSync()
        rom.decompressInto(IMG_BAND, hardware.vram, BAND_TILES_VRAM)
        rom.decompressInto(TSA_BAND, genericBuffer, 0)
        BgTilemap.applyTsa(hardware.mapBuffer(0), 0, genericBuffer, 0, 0x2260)
        hardware.enableBgSyncByMask(BG0_SYNC_BIT)
    }

    private fun notRun(): Nothing =
        error("not verified: no capture runs GmMuEntryStartHide")

    /** ROM addresses and layout constants. */
    public companion object {
        /** `gImg_08A9E544`: the band tiles. */
        public const val IMG_BAND: Int = 0x08A9E544

        /** `gPal_08A9E5BC`: the band palette. */
        public const val PAL_BAND: Int = 0x08A9E5BC

        /** `gTsa_08A9E5DC`: the band tile arrangement. */
        public const val TSA_BAND: Int = 0x08A9E5DC

        private const val PROC_MARK_WMSTUFF = 8
        private const val ROOT_TREE = 3
        private const val GENERIC_BUFFER_SIZE = 0x2000
        private const val BAND_TILES_VRAM = 0x4C00
        private const val BG0_SYNC_BIT = 1
        private const val BG0VOFS_REGISTER = 0x04000012
        private const val PALETTE_COLOR_REGISTER = 0x05000054
        private const val LINE = 160
        private const val SCREEN_HEIGHT = 160
        private const val BLEND_EFFECT_NONE = 0
        private const val BLEND_EFFECT_ALPHA = 1
    }
}
