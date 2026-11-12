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
 * `gProcScr_GmapMuEntry2` (the shaded top and bottom bands of the world map text box). The hide
 * transition (`unk_2c`..`unk_2f` set to the reverse of the show values) is not run by any
 * capture. The decomp names are kept because tests read these fields.
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
 * effect and an alpha blend. The decomp names the event commands that drive it
 * `EventAF_WmShowTextBox`, `EventB0_WmHideTextBox_Bugged` and `EventB1_WmTextBoxWait`, so the
 * bands are the backdrop of world map text. In the Prologue it is started by
 * `WorldMap_CallBeginningEvent`, runs `GmMuEntry_Init`, yields once, waits blocked at
 * label 0, runs one show transition (`GmMuEntryStartShow(40, 0x100)`, 40 frames) and waits again
 * until `EndGmMuEntry` ends it. (The other `gProcScr_GmapMuEntry1` of the same file fades a
 * single map unit in and out and is not ported here.)
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
    /** `gGenericBuffer`: scratch for the decompressed band tile arrangement. */
    private val genericBuffer = ByteArray(GENERIC_BUFFER_SIZE)

    /**
     * `gProcScr_GmapMuEntry2`: init, wait blocked at label 0, and on each show command run
     * [gmMuEntryStartShow]'s transition (label 1), then wait again.
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
            call(ProcFunc { startShowTransition(it.state as GmapMuEntryProc) }),
            repeat(ProcFunc { stepShowTransition(it) }),
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
     * `flags_0` of the running proc (a transition is in progress); `EventB1_WmTextBoxWait` waits
     * until it is clear.
     */
    public fun getGmMuEntryFlag(): Boolean =
        (checkNotNull(engine.find(script)).state as GmapMuEntryProc).flags0

    /** Ported from src/worldmap_entry.c: EndGmMuEntry: removes the bands' proc. */
    public fun endGmMuEntry() {
        engine.endEach(script)
    }

    /** Ported from src/worldmap_entry.c: GmMuEntry_Init: no transition running, no bands. */
    public fun gmMuEntryInit(proc: GmapMuEntryProc) {
        proc.flags0 = false
        proc.unk2b = 0
        proc.unk2a = 0
    }

    /**
     * Ported from src/worldmap_entry.c: GmMuEntryStartShow (`EventAF_WmShowTextBox`)
     *
     * Starts the transition from no bands to the full ones (16 lines at the top and 56 at the
     * bottom) and jumps to label 1. Returns 1 without doing anything if a transition is running.
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
     * Ported from src/worldmap_entry.c: GmMuEntry_80BFEF8 (`startShowTransition` here)
     *
     * Start of a show transition: loads the band graphics, clears the transition counter, sets
     * both band heights to their start values, clears both scanline lines, points them at
     * `0x04000012` (`REG_BG0VOFS`) and `0x05000054` (palette RAM colour 42: BG palette 2, colour
     * 10), enables the HBlank copy and
     * sets BG0 as the first and the other layers as the second blend target with alpha 0/16.
     *
     * (not verified: the `0x10` fill of line 0 and the zero fill of line 1; the same frame's
     * [stepShowTransition] rewrites both lines before any dump, so a wrong fill value is not
     * visible in the capture.)
     */
    public fun startShowTransition(proc: GmapMuEntryProc) {
        loadBandGraphics()
        proc.unk38 = 0
        proc.unk2a = proc.unk2c
        proc.unk2b = proc.unk2e
        proc.unk3c = proc.unk2d - proc.unk2c
        proc.unk40 = proc.unk2f - proc.unk2e
        val line0 = hblank.lineBufferIndex(0, 1)
        hblank.buffer.fill(0x10, line0, line0 + LINE)
        hblank.setLineRegister(0, BG0VOFS_REGISTER)
        hblank.maskLineFlags(0)
        val line1 = hblank.lineBufferIndex(1, 1)
        hblank.buffer.fill(0, line1, line1 + LINE)
        hblank.setLineRegister(1, PALETTE_COLOR_REGISTER)
        hblank.maskLineFlags(1)
        hblank.status = hblank.status or 4
        hardware.lcd.setBlendTargetA(1, 0, 0, 0, 0)
        hardware.lcd.setBlendTargetB(0, 1, 1, 1, 1)
        hardware.lcd.setBlendConfig(BLEND_EFFECT_ALPHA, 0, 0x10, 0)
    }

    /**
     * Ported from src/worldmap_entry.c: GmMuEntry_80C0080 (`stepShowTransition` here)
     *
     * One frame of a show transition (`PROC_REPEAT`): advances the counter by 0x1000, eases the
     * band heights and the blend alpha, and at the end sets the final heights, turns the alpha
     * blend off, clears the transition flag and jumps to label 0. Always redraws the bands.
     */
    public fun stepShowTransition(proc: Proc) {
        val state = proc.state as GmapMuEntryProc
        val unk: Int
        state.unk38 += 0x1000
        if (state.unk38 < state.unk34) {
            unk = Spline.divArm1(state.unk38, state.unk34, state.unk30)
            state.unk2a = (state.unk2c + Spline.divArm(0x1000, state.unk3c * unk)) and 0xFF
            state.unk2b = (state.unk2e + Spline.divArm(0x1000, state.unk40 * unk)) and 0xFF
            hardware.lcd.setBlendConfig(BLEND_EFFECT_ALPHA, unk / 256, 0x10 - (unk / 256), 0)
        } else {
            hblank.setLineRegister(0, 0)
            unk = 0x1000
            hardware.lcd.setBlendConfig(BLEND_EFFECT_NONE, 0, 0x10, 0)
            state.unk2a = state.unk2d
            state.unk2b = state.unk2f
            state.flags0 = false
            engine.goto(proc, 0)
        }
        drawBands(state)
    }

    /**
     * Ported from src/worldmap_entry.c: sub_80BFDA0 (`drawBands` here)
     *
     * Rewrites the off-screen scanline page. Line 0 (BG0VOFS) is `0x40 - y` on every scanline `y`
     * (so each of them shows BG0 pixel row 0x40; what that row holds is not checked) and line 1
     * (the palette colour) is 0, except within the top band (`unk_2a` lines from the top) and the
     * bottom band (`unk_2b` lines from the bottom). There the vertical offset changes and the
     * colour ramps (in 5-bit RGB, the decomp's literals) up to `(13, 17, 23)`. Then swaps the
     * pages. The decomp's unused second parameter is dropped.
     */
    public fun drawBands(proc: GmapMuEntryProc) {
        val palA = hblank.lineBufferIndex(0, 1)
        val palB = hblank.lineBufferIndex(1, 1)
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
        hblank.swapPages()
    }

    /**
     * Ported from src/worldmap_entry.c: sub_80BFD28 (`loadBandGraphics` here)
     *
     * Loads the band graphics: palette bank 2, tiles at VRAM `0x06004C00`, and the tile
     * arrangement into BG0 with tile entry base 0x2260 (tile 0x260 = `0x4C00 / CHR_SIZE`, palette
     * 2).
     */
    public fun loadBandGraphics() {
        rom.pal(PAL_BAND, 16).copyInto(hardware.palette, 2 * 16)
        hardware.enablePaletteSync()
        rom.decompressInto(IMG_BAND, hardware.vram, BAND_TILES_VRAM)
        rom.decompressInto(TSA_BAND, genericBuffer, 0)
        BgTilemap.applyTsa(hardware.mapBuffer(0), 0, genericBuffer, 0, 0x2260)
        hardware.enableBgSyncByMask(BG0_SYNC_BIT)
    }

    /** Stands in for the hide commands of [script], which no capture reaches. */
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

        /** `PROC_MARK_WMSTUFF`. */
        private const val PROC_MARK_WMSTUFF = 8

        /** `PROC_TREE_3`: the root the proc starts in without a parent. */
        private const val ROOT_TREE = 3

        /** Size of `gGenericBuffer` used here. */
        private const val GENERIC_BUFFER_SIZE = 0x2000

        /** `0x06004C00` as a VRAM offset. */
        private const val BAND_TILES_VRAM = 0x4C00

        /** `BG0_SYNC_BIT`. */
        private const val BG0_SYNC_BIT = 1

        /** `REG_BG0VOFS`, the register the scanline effect's line 0 feeds. */
        private const val BG0VOFS_REGISTER = 0x04000012

        /** Palette RAM colour 42 (BG palette 2, colour 10), the register of the effect's line 1. */
        private const val PALETTE_COLOR_REGISTER = 0x05000054

        /** Entries of one scanline-effect line: one per screen row (`DISPLAY_HEIGHT * 2` bytes). */
        private const val LINE = 160

        /** `DISPLAY_HEIGHT`. */
        private const val SCREEN_HEIGHT = 160

        /** `BLEND_EFFECT_NONE`. */
        private const val BLEND_EFFECT_NONE = 0

        /** `BLEND_EFFECT_ALPHA`. */
        private const val BLEND_EFFECT_ALPHA = 1
    }
}
