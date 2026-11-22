package fe8.game.savemenu

import fe8.game.RomView
import fe8.game.mapanim.ApProcs
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
import gbafe.render.BgVerticalScroll
import gbafe.render.LcdControl
import gbafe.render.OamBuffer

/**
 * The drifting fog background of the save / main menu, ported from the
 * `SaveDraw_ScrollFogBG` part of `src/savedraw.c` (the rest of that file, the menu sprites, is
 * not ported here).
 *
 * The fog is background layer BG2 (tile graphics `BGCHR_SAVEMENU_BGFOG` in the decomp). Each
 * frame it drifts diagonally, and each scanline gets its own horizontal offset from `gSinLookup`
 * so the fog ripples.
 */
public object SaveDraw {
    /**
     * Drift position of the fog, in 1/8 pixels on both axes.
     *
     * Ported from `struct SaveDrawProc::bg_x` and `bg_y` (u16 each in decomp).
     */
    public class Fog {
        /**
         * Horizontal position in 1/8 pixels (`bg_x`): +1 per frame, kept to 16 bits. Only the low
         * 12 bits are used for display (0..511 pixels).
         */
        public var bgX: Int = 0

        /**
         * Vertical position in 1/8 pixels (`bg_y`): +2 per frame, kept to 16 bits. Only
         * `(bgY / 8) & 0xFF` is used for display, which also seeds the sine angle.
         */
        public var bgY: Int = 0
    }

    /**
     * Moves the fog one frame: advances [fog], fills the scroll buffer's other half with one
     * horizontal offset per scanline, sets BG2's scroll in [lcd], and flips the buffer so the
     * new table is used from the next frame's HBlank handler.
     *
     * Per scanline the offset is `(sin(angle) / 0x300 + x) & 0x1FF`; `angle` starts at the
     * vertical pixel position and grows by 12 per scanline, so 160 lines cover 1908 angle
     * steps, wrapping the 256-entry table about 7.5 times (the ripple density).
     *
     * Ported from src/savedraw.c: SaveDraw_ScrollFogBG
     *
     * @param fog the persistent subpixel position state of the fog background.
     * @param scroll the per-scanline scroll buffer; register 0 is BG2's horizontal offset.
     * @param lcd receives the whole-frame BG2 position (`BG_SetPosition(BG_2, ...)`).
     * @param sin the first 256 entries of `gSinLookup` (s16, 4.12 fixed point; `SIN(a)` indexes
     *   it with `a & 0xFF`).
     */
    public fun scrollFogBg(fog: Fog, scroll: BgVerticalScroll, lcd: LcdControl, sin: ShortArray) {
        fog.bgX = (fog.bgX + 1) and UINT16_MASK
        fog.bgY = (fog.bgY + 2) and UINT16_MASK
        val pixelX = ((fog.bgX and SUBPIXEL_WRAP_MASK) shr SUBPIXEL_SHIFT).toShort().toInt()
        val bgY = (fog.bgY / SUBPIXELS_PER_PIXEL) and BYTE_MASK
        val bufferBase = scroll.bufferOffset(SCROLL_REGISTER_0, NEXT_FRAME_BUFFER)
        var scanlineAngle = bgY
        for (line in 0 until SCREEN_HEIGHT_LINES) {
            val waveOffset = sin[scanlineAngle and BYTE_MASK] / SINE_AMPLITUDE_DIVISOR
            scroll.buf[bufferBase + line] = ((waveOffset + pixelX) and SCROLL_MASK_9BIT).toShort()
            scanlineAngle += ANGLE_STEP_PER_LINE
        }
        lcd.setBgPosition(BG_LAYER_2, pixelX, bgY)
        scroll.flip()
    }

    /** GBA screen height in scanlines (`DISPLAY_HEIGHT`, include/gba/defines.h). */
    private const val SCREEN_HEIGHT_LINES = 160

    /** Background layer index for the drifting menu fog (`BG_2`). */
    private const val BG_LAYER_2 = 2

    /** Scroll register 0 of the buffer: BG2's horizontal offset (`REG_ADDR_BG2HOFS`). */
    private const val SCROLL_REGISTER_0 = 0

    /** `flip` argument true: the buffer half not currently shown, filled before the flip. */
    private const val NEXT_FRAME_BUFFER = 1

    /** Fog position units per screen pixel. */
    private const val SUBPIXELS_PER_PIXEL = 8

    /** Shift converting 1/8 pixels to pixels (`>> 3`); same as dividing by 8. */
    private const val SUBPIXEL_SHIFT = 3

    /** Keeps `bgX` in 0..4095, i.e. 0..511 pixels, the 9-bit scroll range (`& 0xfff`). */
    private const val SUBPIXEL_WRAP_MASK = 0xFFF

    /** Sine angle increment per scanline (`angle += 12`). */
    private const val ANGLE_STEP_PER_LINE = 12

    /**
     * Divisor turning the 4.12 sine into a pixel offset (`/ 0x300`): with a peak of 0x1000 the
     * offset ranges about -5..5 pixels (4096 / 768 = 5 1/3, truncated toward zero).
     */
    private const val SINE_AMPLITUDE_DIVISOR = 0x300

    /** Wrap of a horizontal scroll value to the hardware's 9 bits (`& 0x1ff`, 0..511). */
    private const val SCROLL_MASK_9BIT = 0x1FF

    /** Wrap to 0..255, used for the vertical pixel position and the sine index (`& 0xff`). */
    private const val BYTE_MASK = 0xFF

    /** Wrap of `bgX` / `bgY` to the decomp's u16 fields. */
    private const val UINT16_MASK = 0xFFFF
}

/**
 * Parent `SaveMenuProc` view read and updated by `SaveDraw_Init` and `SaveDraw_Loop_Main`
 * (`SAVE_MENU_PARENT(proc)` in `src/savedraw.c`). Implemented by [SaveMenuState].
 */
public interface SaveMenuParent {
    /** `sus_slot` (+0x2C, `u8`): selected save slot index (`0..2`). */
    public val susSlot: Int

    /** `sus_slot_cur` (+0x3F, `u8`): game slot of the active suspend save, or `0xFF` if none. */
    public val susSlotCur: Int

    /** `main_sel_bitfile` (+0x42, `u16`): chosen main menu option bitmask. */
    public val mainSelBitfile: Int

    /** `extra_sel_bitfile` (+0x35, `u8`): chosen extras option bitmask. */
    public val extraSelBitfile: Int

    /** `approc` (+0x60): suspend bookmark `ProcScr_ApProc` proc on tree 3, or null. */
    public var approc: Proc?
}

/**
 * Per-proc state of `ProcScr_savedraw` (`struct SaveDrawProc` past `PROC_HEADER`,
 * `include/savemenu.h`), implementing [SaveDrawParent] for [SaveDrawCursor].
 */
public class SaveDrawProc : SaveDrawParent {
    /** `unk_29` (+0x29, `bool`): non-zero while save drawing and cursor updates run. */
    public var unk29: Int = 0

    /** `unk_2a` (+0x2A, `u16`): animation frame counter incremented in `SaveDraw_Loop_Main`. */
    public var unk2a: Int = 0

    /** `unk_2c` (+0x2C, `u16`). */
    public var unk2c: Int = 0

    /** `unk_2e` (+0x2E, `u16`): initialized to `0x100` in `SaveDraw_Init`. */
    public var unk2e: Int = 0

    /** `unk_30` (+0x30, `u16`). */
    public var unk30: Int = 0

    /** `unk_32` (+0x32, `s8`). */
    public var unk32: Int = 0

    /** `unk_33` (+0x33, `u8`): option bitfile updated by `SaveDraw_Loop_Main` (`PC-4c1`). */
    public var unk33: Int = 0

    /** `unk_34` (+0x34, `ProcPtr`): child `gProcScr_SaveDrawCursor` proc. */
    public var unk34: Proc? = null

    /** `unk_38` (+0x38, `u8`): untouched by `SaveDraw_Init`. */
    public var unk38: Int = 0

    /** `unk_39` (+0x39, `u8`). */
    public var unk39: Int = 0

    /** `unk_3a` (+0x3A, `u8`). */
    public var unk3a: Int = 0

    /** `unk_3b` (+0x3B, `u8`): initialized to `40` in `SaveDraw_Init`. */
    public var unk3b: Int = 0

    /** `unk_3c` (+0x3C, `u8`): last `SaveMenuProc::sus_slot` seen by `sub_80AACBC`. */
    public var unk3c: Int = 0

    /** `bg_x` / `bg_y` (+0x3E, +0x40, `u16` each): fog background drift state. */
    public val fog: SaveDraw.Fog = SaveDraw.Fog()

    override val cursorDrawActive: Int
        get() = unk29

    override val drawCursorProc: Proc?
        get() = unk34
}

/**
 * One particle entry of `gUnknown_020007E0[0x14]` (`struct Unknown020007E0`,
 * `include/savemenu.h`), reset by [SaveDrawLifecycle.sub80AB548] and stepped by `sub_80AB56C`
 * (`PC-4c2`).
 */
public class SaveDrawParticle {
    /** `unk_00` (+0x00, `u32`): fixed-point horizontal position (`1/12` pixel). */
    public var unk00: Int = 0

    /** `unk_04` (+0x04, `int`): fixed-point vertical position (`1/12` pixel). */
    public var unk04: Int = 0

    /** `unk_08` (+0x08, `s16`): horizontal velocity. */
    public var unk08: Int = 0

    /** `unk_0a` (+0x0A, `s16`): vertical velocity. */
    public var unk0a: Int = 0

    /** `unk_0c` (+0x0C, `s8`): horizontal acceleration (untouched by `sub_80AB548`). */
    public var unk0c: Int = 0

    /** `unk_0d` (+0x0D, `s8`): vertical acceleration (untouched by `sub_80AB548`). */
    public var unk0d: Int = 0

    /** `unk_0e` (+0x0E, `s8`): active flag (`0` = inactive, `1` = active). */
    public var unk0e: Int = 0
}

/**
 * Lifecycle owner for `ProcScr_savedraw` (`src/savedraw.c`, `PC-4b`): starts and tears down
 * the save background proc, its child `gProcScr_SaveDrawCursor`, the suspend bookmark
 * `ProcScr_ApProc`, the HBlank [BgVerticalScroll] handler on `REG_BG2HOFS`, and the fog particle
 * array `gUnknown_020007E0`.
 *
 * @param engine proc scheduler running `ProcScr_savedraw`
 * @param hardware display hardware holding `hblank` and `lcd`
 * @param scroll per-scanline vertical scroll buffer (`gpBgVerticalScrollSt`)
 * @param oam OAM shadow buffer whose affine matrices `0..3` are initialized
 * @param cursor save menu cursor proc owner (`StartSaveDrawCursor`)
 * @param apProcs animation proc pool (`APProc_Create` for the suspend bookmark)
 * @param sin 256-entry `gSinLookup` table passed to [SaveDraw.scrollFogBg]
 * @param hooks optional `PC-4c1`/`PC-4c2` loop override
 */
public class SaveDrawLifecycle(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val scroll: BgVerticalScroll,
    private val oam: OamBuffer,
    private val cursor: SaveDrawCursor,
    private val apProcs: ApProcs,
    private val sin: ShortArray,
    public val hooks: Hooks = Hooks(),
) {
    /**
     * Creates a [SaveDrawLifecycle] loading the 256-entry `gSinLookup` sine table from [rom].
     */
    public constructor(
        engine: ProcEngine,
        hardware: BgHardware,
        scroll: BgVerticalScroll,
        oam: OamBuffer,
        cursor: SaveDrawCursor,
        apProcs: ApProcs,
        rom: RomView,
        hooks: Hooks = Hooks(),
    ) : this(
        engine,
        hardware,
        scroll,
        oam,
        cursor,
        apProcs,
        rom.pal(SIN_LOOKUP_ADDRESS, SIN_TABLE_SIZE),
        hooks,
    )

    /**
     * Callbacks for `PC-4c1` / `PC-4c2` per-frame drawing not yet ported here.
     */
    public class Hooks(
        /**
         * `SaveDraw_Loop_Main` (`PC-4c1`). Stand-in (PC-4c1): when null, [SaveDrawLifecycle]
         * runs [SaveDraw.scrollFogBg] each frame so BG2 fog ripples and scrolls via HBlank.
         */
        public var loopMain: ProcFunc? = null,
    )

    /**
     * `gUnknown_020007E0[0x14]`: the 20 save background fog particles reset by [sub80AB548].
     */
    public val particles: Array<SaveDrawParticle> = Array(PARTICLE_COUNT) { SaveDrawParticle() }

    /**
     * `ProcScr_savedraw` (`src/savedraw.c:679`): marks `PROC_MARK_SAVEDRAW`, registers
     * [saveDrawOnEnd], calls [saveDrawInit], and repeats [saveDrawLoopMain].
     *
     * Transcribed from src/savedraw.c: ProcScr_savedraw (not verified: script order and mark are
     * checked against the procs watch in SaveDrawLifecycleDifferentialTest, which cannot tag a
     * data array)
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_savedraw",
        listOf(
            name("savedraw"),
            mark(PROC_MARK_SAVEDRAW),
            setEndCb(ProcFunc { saveDrawOnEnd() }),
            call(ProcFunc { saveDrawInit(it) }),
            repeat(ProcFunc { saveDrawLoopMain(it) }),
            end(),
        ),
        newState = { SaveDrawProc() },
    )

    /**
     * Starts `ProcScr_savedraw` as a child of [parent] (`SaveMenuProc`).
     *
     * Ported from src/savedraw.c: StartSaveDraw
     */
    public fun startSaveDraw(parent: Proc): Proc = engine.start(script, parent)

    /**
     * Initializes [SaveDrawProc], sets OAM affine matrices `0..3` to identity, starts the child
     * cursor proc and (when `susSlotCur != 0xFF`) the suspend bookmark `ProcScr_ApProc` on tree 3,
     * configures [BgVerticalScroll] for `REG_BG2HOFS`, runs the first fog scroll step, enables
     * HBlank scrolling, and clears [particles] via [sub80AB548].
     *
     * Transcribed from src/savedraw.c: SaveDraw_Init (not verified: the `susSlotCur == 0xFF`
     * branch is unexercised in PC-cap1 where `susSlotCur == 0`, `SetObjAffine(0..3)` writes
     * identity over an already-identity OAM matrix buffer from `SaveMenu_InitScreen`, and
     * `sub_8014EF4(0)` masks already-zeroed `mode[0]`; `SaveDrawProc` fields, child cursor and
     * AP proc creation, `StartBgVerticalScroll`/`SetBgVerticalScrollPosition`,
     * `SaveDraw_ScrollFogBG`, `scrollEnabled = true`, and `sub_80AB548` match `r09459`)
     */
    public fun saveDrawInit(proc: Proc) {
        val state = proc.state as SaveDrawProc
        val menuParent = checkNotNull(proc.parent?.state as? SaveMenuParent) {
            "SaveDrawProc requires a SaveMenuParent parent"
        }

        state.unk29 = 1
        state.unk2c = 0
        state.unk2e = 0x100
        state.unk3a = 0
        state.unk3b = INITIAL_UNK_3B
        state.unk30 = 0
        state.unk32 = 0

        for (i in 0 until AFFINE_COUNT) {
            oam.setObjAffine(i, AFFINE_IDENTITY, 0, 0, AFFINE_IDENTITY)
        }

        state.unk2a = 0
        state.unk34 = cursor.startSaveDrawCursor(proc)
        state.unk39 = 0

        if (menuParent.susSlotCur == NO_SUSPEND_SLOT) {
            menuParent.approc = null
        } else {
            menuParent.approc = apProcs.create(
                SUSPEND_BOOKMARK_AP_ADDR,
                SUSPEND_BOOKMARK_X,
                menuParent.susSlotCur * SUSPEND_BOOKMARK_Y_STEP + SUSPEND_BOOKMARK_Y_BASE,
                SUSPEND_BOOKMARK_TILE_BASE,
                0,
                SUSPEND_BOOKMARK_OBJ_LAYER,
            )
        }

        state.unk3c = menuParent.susSlot and 0xFF
        state.fog.bgY = 0
        state.fog.bgX = 0

        scroll.start(hardware.hblank)
        scroll.setPosition(SCROLL_REGISTER_0, REG_BG2HOFS)
        scroll.sub8014EF4(SCROLL_REGISTER_0)

        SaveDraw.scrollFogBg(state.fog, scroll, hardware.lcd, sin)
        scroll.scrollEnabled = true

        sub80AB548()
    }

    /**
     * Ends the HBlank vertical scroll when `ProcScr_savedraw` terminates (`SaveDraw_OnEnd`).
     *
     * Ported from src/savedraw.c: SaveDraw_OnEnd
     */
    public fun saveDrawOnEnd() {
        scroll.end(hardware.hblank)
    }

    /**
     * Resets all 20 entries of [particles] (`gUnknown_020007E0[0..19]`), leaving `unk_0c` and
     * `unk_0d` untouched.
     *
     * Ported from src/savedraw.c: sub_80AB548
     */
    public fun sub80AB548() {
        for (i in 0 until PARTICLE_COUNT) {
            val particle = particles[i]
            particle.unk0e = 0
            particle.unk04 = INITIAL_PARTICLE_POS
            particle.unk00 = INITIAL_PARTICLE_POS
            particle.unk0a = 0
            particle.unk08 = 0
        }
    }

    private fun saveDrawLoopMain(proc: Proc) {
        val custom = hooks.loopMain
        if (custom != null) {
            custom.invoke(proc)
        } else {
            SaveDraw.scrollFogBg((proc.state as SaveDrawProc).fog, scroll, hardware.lcd, sin)
        }
    }

    /** ROM addresses, proc mark, and particle constants from `src/savedraw.c` and `proc.h`. */
    public companion object {
        /** `PROC_MARK_SAVEDRAW` (`include/proc.h`). */
        public const val PROC_MARK_SAVEDRAW: Int = 12

        /** I/O register offset of `REG_ADDR_BG2HOFS` (`0x04000018`). */
        public const val REG_BG2HOFS: Int = 0x18

        /** Number of fog particle entries in `gUnknown_020007E0`. */
        public const val PARTICLE_COUNT: Int = 20

        /** ROM address of `gUnknown_08A280A8` (suspend bookmark AP definition). */
        public const val SUSPEND_BOOKMARK_AP_ADDR: Int = 0x08A280A8

        /** Initial horizontal coordinate passed to `APProc_Create` in `SaveDraw_Init`. */
        public const val SUSPEND_BOOKMARK_X: Int = 320

        /** Base vertical coordinate passed to `APProc_Create` in `SaveDraw_Init`. */
        public const val SUSPEND_BOOKMARK_Y_BASE: Int = 48

        /** Vertical step per slot passed to `APProc_Create` in `SaveDraw_Init`. */
        public const val SUSPEND_BOOKMARK_Y_STEP: Int = 32

        /** `tileBase` (`OAM2`) passed to `APProc_Create` in `SaveDraw_Init`. */
        public const val SUSPEND_BOOKMARK_TILE_BASE: Int = 0x3140

        /** `objLayer` passed to `APProc_Create` in `SaveDraw_Init`. */
        public const val SUSPEND_BOOKMARK_OBJ_LAYER: Int = 4

        /** ROM address of `gSinLookup` (`data/trig_rodata.s`). */
        public const val SIN_LOOKUP_ADDRESS: Int = 0x080D751C

        private const val SIN_TABLE_SIZE = 256
        private const val SCROLL_REGISTER_0 = 0
        private const val INITIAL_UNK_3B = 40
        private const val AFFINE_COUNT = 4
        private const val AFFINE_IDENTITY = 0x100
        private const val NO_SUSPEND_SLOT = 0xFF
        private const val INITIAL_PARTICLE_POS = 0xA0
    }
}
