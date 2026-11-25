package fe8.game.savemenu

import fe8.game.RomView
import gbafe.core.math.Spline
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject

/**
 * Difficulty menu sprites and cursor animation (`src/difficultymenu.c`, `PC-9c`:
 * `gProcScr_DrawDifficultyMenuSprites`, `DrawDifficultySprite_Init`,
 * `DrawDifficultyMenuCursorMaybe`, `sub_80AC4F8`, `DrawDifficultySprites_Loop`, and
 * `StartDrawDifficultyMenuSprites`).
 *
 * @param engine proc scheduler running `gProcScr_DrawDifficultyMenuSprites`
 * @param hardware palette buffer (`gPaletteBuffer`) and sync flag (`EnablePaletteSync`)
 * @param layers sprite layer lists receiving the difficulty option boxes, labels, and cursor
 * @param rom ROM view supplying sprite objects, bobbing LUT, and palette cycle tables
 */
public class DifficultyMenuSprites(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val layers: SpriteLayers,
    private val rom: RomView,
) {
    private val cursorSprite: SpriteObject = rom.objN(SPRITE_CURSOR)
    private val selectModeBgSprite: SpriteObject = rom.objN(SPRITE_SELECT_MODE_BG)
    private val selectModeTextSprite: SpriteObject = rom.objN(SPRITE_SELECT_MODE_TEXT)
    private val optionBgSprite: SpriteObject = rom.objN(SPRITE_OPTION_BG)
    private val optionTextSprites: Array<SpriteObject> = Array(OPTION_COUNT) { i ->
        rom.objN(rom.u32(SPRITE_ARRAY_OPTION_TEXTS + 4 * i))
    }
    private val bobOffsets: IntArray = IntArray(BOB_LUT_SIZE) { i ->
        rom.u8(CURSOR_BOB_LUT + i)
    }
    private val optionStatePalettes: ShortArray = rom.pal(PAL_OPTION_STATES, OPTION_PALETTE_WORDS)
    private val cyclePalette: ShortArray = rom.pal(PAL_CYCLE, CYCLE_PALETTE_COLORS)

    /**
     * `gProcScr_DrawDifficultyMenuSprites` (`src/difficultymenu.c`): calls
     * [drawDifficultySpriteInit], yields one frame, and repeats [drawDifficultySpritesLoop].
     *
     * Transcribed from src/difficultymenu.c: gProcScr_DrawDifficultyMenuSprites (not verified:
     * script order is checked against `ram_trace_newgame` procs, which cannot tag a data array)
     */
    public val script: ProcScript = ProcScript(
        "gProcScr_DrawDifficultyMenuSprites",
        listOf(
            call(ProcFunc { drawDifficultySpriteInit(it) }),
            yield(),
            repeat(ProcFunc { drawDifficultySpritesLoop(it) }),
            end(),
        ),
        newState = { DifficultyMenuSpritesState() },
    )

    /**
     * Starts `gProcScr_DrawDifficultyMenuSprites` as a child of [parent]
     * (`ProcScr_NewGameDifficultySelect`).
     *
     * Ported from src/difficultymenu.c: StartDrawDifficultyMenuSprites
     */
    public fun startDrawDifficultyMenuSprites(parent: Proc): Proc =
        engine.start(script, parent)

    /**
     * Resets `flags_0`, `flags_1`, `unk_2a`, `unk_2e`, and `unk_2c` on [proc].
     *
     * Transcribed from src/difficultymenu.c: DrawDifficultySprite_Init (not verified:
     * `ram_trace_newgame` observes only clearing of `unk2a` when seeded from `r00983` slot 1;
     * `flags0`, `flags1`, and `unk2e` are already zero in `r00983` slot 1, and `unk2c` and
     * `unk2e` are overwritten by `InitDifficultySelectScreen` before `r00984`)
     */
    public fun drawDifficultySpriteInit(proc: Proc) {
        val state = proc.state as DifficultyMenuSpritesState
        state.flags0 = false
        state.flags1 = false
        state.unk2a = 0
        state.unk2e = 0
        state.unk2c = 0
    }

    /**
     * Advances any active cursor glide (`flags_0` / `flags_1` via `_DivArm2` and `DivArm`) and
     * submits `gSprite_08A20A98` on sprite layer 4 with `OAM2_PAL(3)` and bobbing offset
     * `gUnknown_08A20B14[(unk_2a >> 3) & 7]`.
     *
     * Transcribed from src/difficultymenu.c: DrawDifficultyMenuCursorMaybe (not verified: `unk38`
     * stays zero in `ram_trace_newgame` because `unk30 == unk34 == 0`, so horizontal
     * interpolation and negative vertical moves are unexercised, and HW `.oam.bin` exists only at
     * `f1020` rather than `f1030..1087`; vertical interpolation over `f1031..1034`, all 8 bobbing
     * offsets, `sSpritePool`, and `gOam` match `f984..1087`)
     */
    public fun drawDifficultyMenuCursorMaybe(proc: Proc) {
        val state = proc.state as DifficultyMenuSpritesState
        if (state.flags0) {
            state.unk38 = (state.unk34 - state.unk30).toShort().toInt()
            state.unk3a = (state.unk36 - state.unk32).toShort().toInt()
            state.unk2c = state.unk30.toShort().toInt()
            state.unk2e = state.unk32.toShort().toInt()
            state.unk3c = 0
            state.flags0 = false
            state.flags1 = true
        }

        if (state.flags1) {
            state.unk3c = (state.unk3c + 1).toShort().toInt()
            if (state.unk3c < CURSOR_MOVE_FRAMES) {
                val unk = Spline.divArm2(state.unk3c, CURSOR_MOVE_FRAMES, 0)
                state.unk2c = (state.unk30 + Spline.divArm(FIXED_ONE, state.unk38 * unk))
                    .toShort()
                    .toInt()
                state.unk2e = (state.unk32 + Spline.divArm(FIXED_ONE, state.unk3a * unk))
                    .toShort()
                    .toInt()
            } else {
                state.unk2c = state.unk34.toShort().toInt()
                state.unk2e = state.unk36.toShort().toInt()
                state.flags1 = false
            }
        }

        val bob = bobOffsets[(state.unk2a ushr BOB_FRAME_SHIFT) and BOB_LUT_MASK]
        layers.putExt(
            SPRITE_LAYER,
            state.unk2c,
            (state.unk2e + bob) and OAM0_Y_MASK,
            cursorSprite,
            oam2Pal(CURSOR_PAL_BANK),
        )
    }

    /**
     * Updates OBJ highlight palette color 1 for each difficulty row from `gUnknown_08A29498`,
     * cycles the active row's highlight across `Pal_08A28088` via `(frameMaybe % 0x40) / 4`,
     * and requests palette sync.
     *
     * Transcribed from src/difficultymenu.c: sub_80AC4F8 (not verified: `selectedIdx == 2` is
     * unexercised in `ram_trace_newgame`, the active-branch write `PAL_OBJ_COLOR(5 + i * 2, 1)`
     * is immediately overwritten by `Pal_08A28088`, and the inactive-branch write
     * `PAL_OBJ_COLOR(6 + i * 2, 1)` writes the same color already loaded by
     * `InitDifficultySelectScreen`; all 16 cycle colors across selections 0 and 1 match
     * `f984..1087`)
     */
    public fun sub80AC4F8(frameMaybe: Int, selectedIdx: Int) {
        val frame = frameMaybe and BYTE_MASK
        val sel = selectedIdx and BYTE_MASK

        for (i in 0 until OPTION_COUNT) {
            val srcOffset = i * OPTION_PAL_STRIDE
            if (i == sel) {
                hardware.palette[palObjColorOffset(SELECTED_PAL_BASE + i * 2, 1)] =
                    optionStatePalettes[srcOffset + 1]
            } else {
                hardware.palette[palObjColorOffset(UNSELECTED_PAL_BASE + i * 2, 1)] =
                    optionStatePalettes[srcOffset + PALETTE_BANK_COLORS + 1]
            }
        }

        val color = (frame and CYCLE_MASK) ushr CYCLE_SHIFT
        hardware.palette[palObjColorOffset(SELECTED_PAL_BASE + sel * 2, 1)] = cyclePalette[color]
        hardware.enablePaletteSync()
    }

    /**
     * Advances `unk_2a`, draws the difficulty header and three option rows on sprite layer 4,
     * cycles the selected option palette via [sub80AC4F8], and draws/animates the cursor via
     * [drawDifficultyMenuCursorMaybe].
     *
     * `ram_trace_newgame` exercises both branches of `i == currentSelection` (selections `0` and
     * `1`), `sSpritePool` and `gOam` across `f984..1087`, and HW `.oam.bin` at `f1020` (selection
     * `2`, `unk_2a` wrapping past `255`, and post-movement HW `.oam.bin` at `f1030..1087` do not
     * occur in the capture).
     *
     * Ported from src/difficultymenu.c: DrawDifficultySprites_Loop
     */
    public fun drawDifficultySpritesLoop(proc: Proc) {
        val state = proc.state as DifficultyMenuSpritesState
        state.unk2a = (state.unk2a + 1) and BYTE_MASK

        layers.putExt(
            SPRITE_LAYER,
            SELECT_MODE_X,
            SELECT_MODE_Y,
            selectModeBgSprite,
            oam2Pal(SELECT_MODE_BG_PAL_BANK),
        )
        layers.putExt(
            SPRITE_LAYER,
            SELECT_MODE_X,
            SELECT_MODE_Y,
            selectModeTextSprite,
            oam2Pal(TEXT_PAL_BANK),
        )

        for (i in 0 until OPTION_COUNT) {
            val x = OPTION_X
            val y = OPTION_Y_BASE + i * OPTION_Y_STEP
            val bgPal = if (i == state.currentSelection) {
                SELECTED_PAL_BASE + i * 2
            } else {
                UNSELECTED_PAL_BASE + i * 2
            }
            layers.putExt(SPRITE_LAYER, x, y, optionBgSprite, oam2Pal(bgPal))
            layers.putExt(SPRITE_LAYER, x, y, optionTextSprites[i], oam2Pal(TEXT_PAL_BANK))
        }

        sub80AC4F8(state.unk2a, state.currentSelection)
        drawDifficultyMenuCursorMaybe(proc)
    }

    private fun oam2Pal(palBank: Int): Int = (palBank and PAL_BANK_MASK) shl PAL_BANK_SHIFT

    private fun palObjColorOffset(palId: Int, colorNum: Int): Int =
        (palId + OBJ_PAL_BASE) * PALETTE_BANK_COLORS + colorNum

    /** ROM addresses and OAM/palette constants for [DifficultyMenuSprites]. */
    public companion object {
        /** Sprite layer used by `PutSpriteExt(4, ...)` across `src/difficultymenu.c`. */
        public const val SPRITE_LAYER: Int = 4

        /** `gSprite_08A20A98` (`32x32` cursor sprite). */
        public const val SPRITE_CURSOR: Int = 0x08A20A98

        /** `gSprite_DifficultyMenuSelectModeBg` (header background box). */
        public const val SPRITE_SELECT_MODE_BG: Int = 0x08A20AA0

        /** `gSprite_DifficultyMenuSelectModeText` (header text). */
        public const val SPRITE_SELECT_MODE_TEXT: Int = 0x08A20AAE

        /** `gSprite_08A20AC2` (option row background box). */
        public const val SPRITE_OPTION_BG: Int = 0x08A20AC2

        /** `gSpriteArray_08A20B08` (three pointers to `gSprite_08A20AD0`/`AE4`/`AF2`). */
        public const val SPRITE_ARRAY_OPTION_TEXTS: Int = 0x08A20B08

        /** `gUnknown_08A20B14` (8-entry `u8` cursor bobbing offset table). */
        public const val CURSOR_BOB_LUT: Int = 0x08A20B14

        /** `Pal_08A28088` (16-color highlight cycle palette). */
        public const val PAL_CYCLE: Int = 0x08A28088

        /** `gUnknown_08A29498` (`0x60` halfwords: active/inactive palettes for rows `0..2`). */
        public const val PAL_OPTION_STATES: Int = 0x08A29498

        private const val OPTION_COUNT = 3
        private const val CURSOR_MOVE_FRAMES = 4
        private const val FIXED_ONE = 0x1000
        private const val BOB_LUT_SIZE = 8
        private const val BOB_LUT_MASK = 7
        private const val BOB_FRAME_SHIFT = 3
        private const val OAM0_Y_MASK = 0xFF
        private const val BYTE_MASK = 0xFF

        private const val SELECT_MODE_X = 56
        private const val SELECT_MODE_Y = 4
        private const val SELECT_MODE_BG_PAL_BANK = 2
        private const val CURSOR_PAL_BANK = 3
        private const val TEXT_PAL_BANK = 4
        private const val SELECTED_PAL_BASE = 5
        private const val UNSELECTED_PAL_BASE = 6
        private const val OPTION_X = 8
        private const val OPTION_Y_BASE = 48
        private const val OPTION_Y_STEP = 32

        private const val PALETTE_BANK_COLORS = 16
        private const val OPTION_PAL_STRIDE = 0x20
        private const val OPTION_PALETTE_WORDS = 0x60
        private const val CYCLE_PALETTE_COLORS = 16
        private const val CYCLE_MASK = 0x3F
        private const val CYCLE_SHIFT = 2
        private const val OBJ_PAL_BASE = 0x10
        private const val PAL_BANK_MASK = 0xF
        private const val PAL_BANK_SHIFT = 12
    }
}
