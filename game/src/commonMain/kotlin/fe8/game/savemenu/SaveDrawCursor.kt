package fe8.game.savemenu

import fe8.game.RomView
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject

/**
 * Parent `SaveDrawProc` (`ProcScr_savedraw`, `src/savedraw.c`) view needed by [SaveDrawCursor].
 *
 * `SaveDrawCursor_Loop` reads byte `+0x29` of its parent proc (written in the decomp via a cast
 * to `SaveMenuProc::scroll_cnt`, which aliases `SaveDrawProc.unk_29`), and `sub_80AB4F4` reads
 * the child cursor proc handle from `SaveDrawProc.unk_34` (`+0x34`).
 */
public interface SaveDrawParent {
    /** `SaveDrawProc.unk_29` (+0x29): non-zero while save drawing and cursor updates run. */
    public val cursorDrawActive: Int

    /** `SaveDrawProc.unk_34` (+0x34): the child `gProcScr_SaveDrawCursor` proc. */
    public val drawCursorProc: Proc?
}

/**
 * Per-proc state of `gProcScr_SaveDrawCursor`: fields of `struct SaveDrawCursorProc` past
 * `PROC_HEADER` (`include/savemenu.h`).
 */
public class SaveDrawCursorProc {
    /** `unk_2a` (+0x2A, `u16`): animation frame counter incremented each active loop step. */
    public var unk2a: Int = 0

    /**
     * `sus_slot` (+0x2C, `u8`): glide/settle frame counter (`0..4`; resets to `0` while
     * [cursorEn] draws, and disables half-step smoothing once it reaches `4`).
     */
    public var susSlot: Int = 0

    /** `unk_2e` (+0x2E, `s16`): target horizontal cursor coordinate (`b - 2` in `sub_80AB4F4`). */
    public var unk2e: Int = 0

    /**
     * `main_options` (+0x30, `s16`): previous horizontal cursor coordinate used for half-step
     * interpolation (`unk_2e` from the prior drawn frame).
     */
    public var mainOptions: Int = 0

    /** `unk_32` (+0x32, `s16`): target vertical cursor coordinate (`c - 12` in `sub_80AB4F4`). */
    public var unk32: Int = 0

    /**
     * `unk_34` (+0x34, `s16`): previous vertical cursor coordinate used for half-step
     * interpolation (`unk_32` from the prior drawn frame).
     */
    public var unk34: Int = 0

    /** `cursor_en` (+0x36, `u8`): non-zero when `sub_80AB4F4` enabled drawing this frame. */
    public var cursorEn: Int = 0

    /** `unk_37` (+0x37, `u8`): Y coordinate for the secondary static cursor (`sub_80AB514`). */
    public var unk37: Int = 0

    /** `unk_38` (+0x38, `u8`): non-zero while the secondary static cursor is enabled. */
    public var unk38: Int = 0

    /** `unk_39` (+0x39, `u8`): clears [unk38] on subsequent frames; untouched by init. */
    public var unk39: Int = 0

    /**
     * `unk_3a` (+0x3A, `u8`): cursor mode (`0` for dual horizontal bracket cursors on the main
     * or extras lists; non-zero for the left-edge save-slot cursor at `x = 4`).
     */
    public var unk3a: Int = 0
}

/**
 * Save menu and save slot cursor drawing proc (`src/savedraw.c`, `gProcScr_SaveDrawCursor`),
 * started as a child of `ProcScr_savedraw` via [startSaveDrawCursor] and fed target coordinates
 * each frame by [sub80AB4F4].
 *
 * `SaveMenuTryMoveSaveSlotCursor`, `SaveMenuModifySaveSlot`, and `sub_80AB514` are not written
 * here because `PC-cap1` never moves away from slot 0.
 *
 * @param engine proc scheduler running `gProcScr_SaveDrawCursor`
 * @param layers sprite layer lists receiving the cursor sprite on layer 4
 * @param cursorSprite `Sprite_08A20570` (`32x32`, tile `0x5C`, priority `1`)
 */
public class SaveDrawCursor(
    private val engine: ProcEngine,
    private val layers: SpriteLayers,
    private val cursorSprite: SpriteObject = DEFAULT_CURSOR_SPRITE,
) {
    /**
     * Creates a [SaveDrawCursor] loading `Sprite_08A20570` from [rom].
     *
     * @param engine proc scheduler running `gProcScr_SaveDrawCursor`
     * @param layers sprite layer lists receiving the cursor sprite on layer 4
     * @param rom ROM view holding `Sprite_08A20570` at [SPRITE_CURSOR_ADDRESS]
     */
    public constructor(
        engine: ProcEngine,
        layers: SpriteLayers,
        rom: RomView,
    ) : this(engine, layers, rom.objN(SPRITE_CURSOR_ADDRESS))

    /**
     * `gProcScr_SaveDrawCursor` (proc name `"savedrawcursor"`): calls [saveDrawCursorInit], then
     * repeats [saveDrawCursorLoop] each frame.
     *
     * Transcribed from src/savedraw.c: gProcScr_SaveDrawCursor (not verified: script order
     * checked against the procs watch in SaveDrawCursorDifferentialTest, which cannot tag a
     * script)
     */
    public val script: ProcScript = ProcScript(
        "gProcScr_SaveDrawCursor",
        listOf(
            name("savedrawcursor"),
            call(ProcFunc { saveDrawCursorInit(it.state as SaveDrawCursorProc) }),
            repeat(ProcFunc { saveDrawCursorLoop(it) }),
            end(),
        ),
    ) { SaveDrawCursorProc() }

    /**
     * Starts `gProcScr_SaveDrawCursor` as a child of [parent] (`ProcScr_savedraw`).
     *
     * Ported from src/savedraw.c: StartSaveDrawCursor
     */
    public fun startSaveDrawCursor(parent: Proc): Proc = engine.start(script, parent)

    /**
     * Resets the cursor proc fields except `unk_39` (`SaveDrawCursor_Init`).
     *
     * Transcribed from src/savedraw.c: SaveDrawCursor_Init (not verified: PC-cap1 observes only
     * the clearing of unk2a, susSlot, mainOptions, unk34 and unk38; cursorEn, unk2e, unk32 and
     * unk3a are overwritten by sub_80AB4F4 before the first dump, and unk37 is already zero)
     */
    public fun saveDrawCursorInit(proc: SaveDrawCursorProc) {
        proc.cursorEn = 0
        proc.unk2a = 0
        proc.unk2e = 0
        proc.mainOptions = 0
        proc.unk32 = 0
        proc.unk34 = 0
        proc.unk37 = 0
        proc.unk38 = 0
        proc.unk3a = 0
        proc.susSlot = 0
    }

    /**
     * Per-frame cursor animation and sprite submission on sprite layer 4.
     *
     * Checks byte `+0x29` of the parent `SaveDrawProc` ([SaveDrawParent.cursorDrawActive]),
     * advances the bobbing counter [SaveDrawCursorProc.unk2a], interpolates halfway from the
     * previous position while [SaveDrawCursorProc.susSlot] is below `4`, and puts [cursorSprite]
     * on sprite layer 4 with `oam2 = 0x3000`.
     *
     * Transcribed from src/savedraw.c: SaveDrawCursor_Loop (not verified: PC-cap1 exercises only
     * parent cursorDrawActive != 0, susSlot < 4, cursorEn != 0, unk3a == 1, and unk38 == 0;
     * unexercised branches are parent cursorDrawActive == 0 early return, susSlot >= 4 settled
     * motion, cursorEn == 0 idle branch, unk3a == 0 dual horizontal bracket cursors, and
     * unk38 != 0 static copy cursor)
     */
    public fun saveDrawCursorLoop(proc: Proc) {
        val parent = checkNotNull(proc.parent) { "SaveDrawCursor requires a SaveDrawProc parent" }
        val parentState = parent.state as SaveDrawParent
        if (parentState.cursorDrawActive.toByte().toInt() == 0) return

        val state = proc.state as SaveDrawCursorProc
        state.unk2a = (state.unk2a + 1) and 0xFFFF

        if (state.susSlot < SETTLE_FRAMES) {
            state.susSlot = (state.susSlot + 1) and 0xFF
        }

        if (state.cursorEn != 0) {
            var yOam0 = state.unk32.toShort().toInt()
            var xOam1 = state.unk2e.toShort().toInt()

            if (state.susSlot < SETTLE_FRAMES) {
                yOam0 = ((state.unk32 - state.unk34) / 2 + state.unk34).toShort().toInt()
                xOam1 = ((state.unk2e - state.mainOptions) / 2 + state.mainOptions)
                    .toShort()
                    .toInt()
            }

            val xOam1Alt = if (state.unk3a == 0) {
                (xOam1 + MAIN_CURSOR_RIGHT_OFFSET) and 0xFFFF
            } else {
                (xOam1 + ALT_CURSOR_RIGHT_OFFSET) and 0xFFFF
            }

            state.unk34 = state.unk32.toShort().toInt()
            state.mainOptions = state.unk2e.toShort().toInt()

            val bobY = Y_OFFSET_LUT[(state.unk2a shr 3) and 7]
            if (state.unk3a == 0) {
                layers.putExt(
                    CURSOR_SPRITE_LAYER,
                    xOam1 and OAM1_X_MASK,
                    (yOam0 + bobY) and OAM0_Y_MASK,
                    cursorSprite,
                    CURSOR_OAM2,
                )
                xOam1 = xOam1Alt.toShort().toInt()
                layers.putExt(
                    CURSOR_SPRITE_LAYER,
                    (xOam1 and OAM1_X_MASK) or OAM1_HFLIP,
                    (yOam0 + bobY) and OAM0_Y_MASK,
                    cursorSprite,
                    CURSOR_OAM2,
                )
            } else {
                layers.putExt(
                    CURSOR_SPRITE_LAYER,
                    SLOT_CURSOR_X,
                    (yOam0 + bobY) and OAM0_Y_MASK,
                    cursorSprite,
                    CURSOR_OAM2,
                )
            }

            state.susSlot = 0
        } else if (state.susSlot == SETTLE_FRAMES) {
            state.cursorEn = 0
        }

        if (state.unk38 != 0) {
            layers.putExt(
                CURSOR_SPRITE_LAYER,
                STATIC_COPY_CURSOR_X,
                state.unk37 and OAM0_Y_MASK,
                cursorSprite,
                CURSOR_OAM2,
            )
        }

        if (state.unk39 != 0) {
            state.unk38 = 0
        }

        state.cursorEn = 0
        state.unk39 = 1
    }

    /**
     * Updates the cursor target coordinates and enables drawing for the current frame.
     *
     * Writes `unk_32 = c - 12`, `unk_2e = b - 2`, `cursor_en = 1`, and `unk_3a = a` on the child
     * cursor proc referenced by [saveDrawProc]. In `PC-cap1` only the slot-0 call `(1, 12, 32)`
     * is exercised.
     *
     * Ported from src/savedraw.c: sub_80AB4F4
     */
    public fun sub80AB4F4(a: Int, b: Int, c: Int, saveDrawProc: Proc) {
        val cursorProc = checkNotNull((saveDrawProc.state as SaveDrawParent).drawCursorProc) {
            "SaveDrawProc has no drawCursorProc"
        }
        val state = cursorProc.state as SaveDrawCursorProc
        state.unk32 = (c.toShort().toInt() - CURSOR_Y_OFFSET).toShort().toInt()
        state.unk2e = (b.toShort().toInt() - CURSOR_X_OFFSET).toShort().toInt()
        state.cursorEn = 1
        state.unk3a = a and 0xFF
    }

    /** ROM addresses and OAM/animation constants for [SaveDrawCursor]. */
    public companion object {
        /** ROM address of `Sprite_08A20570` (`src/savedraw.c`). */
        public const val SPRITE_CURSOR_ADDRESS: Int = 0x08A20570

        /** Sprite layer used by `PutSpriteExt(4, ...)` in `SaveDrawCursor_Loop`. */
        public const val CURSOR_SPRITE_LAYER: Int = 4

        /** `OAM2_PAL(3)` passed as `oam2` in `SaveDrawCursor_Loop`. */
        public const val CURSOR_OAM2: Int = 0x3000

        /** Fixed screen X for the left-edge save-slot cursor (`unk_3a != 0`). */
        private const val SLOT_CURSOR_X = 4

        /** Fixed screen X for the secondary static cursor (`unk_38 != 0`). */
        private const val STATIC_COPY_CURSOR_X = 6

        /** Glide interpolation limit (`sus_slot < 4`). */
        private const val SETTLE_FRAMES = 4

        /** Horizontal offset from target `b` to `unk_2e` in `sub_80AB4F4`. */
        private const val CURSOR_X_OFFSET = 2

        /** Vertical offset from target `c` to `unk_32` in `sub_80AB4F4`. */
        private const val CURSOR_Y_OFFSET = 12

        /** Right-hand bracket X delta when `unk_3a == 0`. */
        private const val MAIN_CURSOR_RIGHT_OFFSET = 0x9C

        /** Right-hand bracket X delta when `unk_3a != 0`. */
        private const val ALT_CURSOR_RIGHT_OFFSET = 0xB0

        /** `OAM0_SHAPE_32x32` (`include/ctc.h`). */
        private const val OAM0_SHAPE_32X32 = 0x0000

        /** `OAM1_SIZE_32x32` (`include/ctc.h`). */
        private const val OAM1_SIZE_32X32 = 0x8000

        /** `OAM1_X` 9-bit mask. */
        private const val OAM1_X_MASK = 0x01FF

        /** `OAM1_HFLIP` horizontal flip bit (`include/ctc.h`). */
        private const val OAM1_HFLIP = 0x1000

        /** `OAM0_Y` 8-bit mask. */
        private const val OAM0_Y_MASK = 0x00FF

        /** `OAM2_CHR(0x5C) + OAM2_LAYER(1)` (`src/savedraw.c: Sprite_08A20570`). */
        private const val SPRITE_CURSOR_OAM2 = 0x005C or (1 shl 10)

        /** Vertical bobbing offsets (`yOffsetLut` in `SaveDrawCursor_Loop`, 8 frames each). */
        private val Y_OFFSET_LUT = intArrayOf(0, 1, 2, 3, 3, 2, 1, 0)

        /**
         * `Sprite_08A20570` (`src/savedraw.c`): one 32x32 object at tile `0x5C`, priority `1`.
         */
        public val DEFAULT_CURSOR_SPRITE: SpriteObject = SpriteObject(
            SPRITE_CURSOR_ADDRESS,
            shortArrayOf(
                1,
                OAM0_SHAPE_32X32.toShort(),
                OAM1_SIZE_32X32.toShort(),
                SPRITE_CURSOR_OAM2.toShort(),
            ),
        )
    }
}
