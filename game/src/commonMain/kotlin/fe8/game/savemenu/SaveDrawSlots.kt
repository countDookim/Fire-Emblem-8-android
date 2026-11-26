package fe8.game.savemenu

import fe8.game.RomView
import fe8.game.mapanim.ApProcs
import fe8.game.ui.UiHand
import gbafe.proc.Proc
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject

/**
 * The `SaveMenuProc` fields (`include/savemenu.h`) that the slot, banner and play-time drawing
 * of `src/savedraw.c` reads, on top of [SaveMenuParent].
 */
public interface SaveDrawSlotsParent : SaveMenuParent {
    /** `jump_label` (+0x2E, `u8`): target proc label (`PL_SAVEMENU_*`). */
    public val jumpLabel: Int

    /** `unk_2f` (+0x2F, `u8`): horizontal menu offset (`0xDC` once the slots are shown). */
    public val unk2f: Int

    /** `main_select` (+0x2B, `u8`): index of the highlighted main menu option. */
    public val mainSelect: Int

    /** `main_options` (+0x30, `u8`): bitfield of the main menu options. */
    public val mainOptions: Int

    /** `unk_31` (+0x31, `u8`): number of main menu options. */
    public val unk31: Int

    /** `cursor_slot` (+0x2D, `u8`): static cursor slot, `0xFF` when none is shown. */
    public val cursorSlot: Int

    /** `cursor_config` (+0x36, `u8`): non-zero while the confirm prompt box is shown. */
    public val cursorConfig: Int

    /** `unk_44` (+0x44, `u16`): suspend bookmark spin angle (`0x100` when at rest). */
    public var unk44: Int

    /** `unk_46` (+0x46, `u16`): horizontal offset of the extras menu. */
    public val unk46: Int

    /** `played_time[slot]` (+0x48, `int`): play time in frames of save slot [slot]. */
    public fun playedTime(slot: Int): Int

    /** `total_time` (+0x54, `int`): total play time in frames. */
    public val totalTime: Int
}

/**
 * Save slot boxes, banner and play-time sprites of the save menu (`PC-4c1`, `src/savedraw.c`).
 *
 * The slot loop [loopMain] calls the fog scroll, palette cycle and particle steps through
 * parameters because they belong to other tasks (`PC-4c2`).
 *
 * @param layers sprite layers receiving the sprites (`PutSpriteExt`)
 * @param rom ROM view holding the sprite objects and their pointer tables
 * @param cursor owner of the cursor proc targets (`sub_80AB4F4`)
 * @param uiHand the pointing hand (`DisplayUiHand`)
 * @param apProcs suspend bookmark animation procs
 * @param clock `GetGameClock()`, passed to [UiHand.displayUiHand]
 */
public class SaveDrawSlots(
    private val layers: SpriteLayers,
    private val rom: RomView,
    private val cursor: SaveDrawCursor,
    private val uiHand: UiHand,
    private val apProcs: ApProcs,
    private val clock: () -> Int,
) {
    /**
     * Stand-ins for the part of `sub_80AAF6C` that no capture runs (the extras option list,
     * which needs `sub_80AAC40`) and for `sub_80AB514`; each fails loudly until ported.
     */
    public class Hooks(
        /** `sub_80AAF6C` past its early exit (extras option boxes). */
        public var extraOptions: (Proc) -> Unit = { error("sub_80AAF6C body not ported") },
        /** `sub_80AB514(1, y, proc)`: the static second cursor. */
        public var staticCursor: (Int, Proc) -> Unit = { _, _ -> error("sub_80AB514 not ported") },
    )

    /** Replaceable not-yet-ported parts, see [Hooks]. */
    public val hooks: Hooks = Hooks()

    private val sprites = HashMap<Int, SpriteObject>()

    private fun sprite(address: Int): SpriteObject = sprites.getOrPut(address) { rom.objN(address) }

    private fun sprite(table: Int, index: Int): SpriteObject = sprite(rom.u32(table + 4 * index))

    private fun parent(proc: Proc): SaveDrawSlotsParent =
        checkNotNull(proc.parent?.state as? SaveDrawSlotsParent) {
            "SaveDrawProc requires a SaveDrawSlotsParent parent"
        }

    /**
     * Index of the lowest set bit of the low 8 bits of [bitfile], or `0xFF` (the original's
     * `u8` `-1`) if none.
     *
     * Ported from src/savemenu.c: BitfileToIndex (checked on the single captured input, the
     * label of the chosen main option; the no-bit-set result is not captured)
     */
    public fun bitfileToIndex(bitfile: Int): Int {
        for (i in 0 until BITS_PER_BYTE) {
            if (((bitfile shr i) and 1) != 0) return i
        }
        return 0xFF
    }

    /**
     * Draws the play time (hours, minutes and seconds) above the slot boxes on layer 13.
     *
     * Partially ported from src/savedraw.c: sub_80AA7EC. Verified (`PC-11h`, title continue and
     * resume runs): the `total_time` branch (`main_sel_bitfile == 1`), the `played_time` branch,
     * and the slide-in y with the one-digit hours, minutes and seconds sprites. Unverified: the
     * hours > 9 and hours > 99 digits (no captured play time is that long); `FormatTime` is
     * inlined.
     */
    public fun sub80AA7EC(proc: Proc) {
        val menu = parent(proc)
        // ((a << 24) + 0x92000000) >> 24: the unsigned literal makes it a logical shift, so y is
        // the unsigned byte of (a + 0x92).
        val a = 0x20 - (menu.unk2f * 0x20) / 220
        val y = (a + 0x92) and 0xFF
        val x = 143

        val frames = if (menu.mainSelBitfile == 1) {
            menu.totalTime
        } else {
            menu.playedTime(menu.susSlot)
        }
        var hours = (frames.toUInt() / FRAMES_PER_HOUR).toInt() and 0xFFFF
        val minutes = ((frames.toUInt() / FRAMES_PER_MINUTE) % 60u).toInt()
        val seconds = ((frames.toUInt() / FRAMES_PER_SECOND) % 60u).toInt()

        layers.putExt(13, x + 6, y - 14, sprite(SPRITE_TIME_LABEL), pal(2))
        if (hours > 99) {
            layers.putExt(13, x + 10, y - 8, sprite(DIGITS_LARGE, hours / 100), pal(7))
            hours -= (hours / 100) * 100
        }
        if (hours > 9) {
            layers.putExt(13, x + 18, y - 8, sprite(DIGITS_LARGE, hours / 10), pal(7))
        }
        layers.putExt(13, x + 26, y - 8, sprite(DIGITS_LARGE, hours % 10), pal(7))
        layers.putExt(13, x + 34, y - 7, sprite(DIGITS_LARGE, 10), pal(7))
        layers.putExt(13, x + 42, y - 8, sprite(DIGITS_LARGE, minutes / 10), pal(7))
        layers.putExt(13, x + 50, y - 8, sprite(DIGITS_LARGE, minutes % 10), pal(7))
        layers.putExt(13, x + 58, y + 1, sprite(DIGITS_SMALL, 10), pal(7))
        layers.putExt(13, x + 66, y, sprite(DIGITS_SMALL, seconds / 10), pal(7))
        layers.putExt(13, x + 74, y, sprite(DIGITS_SMALL, seconds % 10), pal(7))
    }

    /**
     * Draws the banner box and the label of the chosen main (or extras) option on layer 4.
     *
     * Partially ported from src/savedraw.c: sub_80AADE0. Verified (`PC-11g`, title resume run
     * `f1003..f1062`, sprite output per frame): the banner position while scrolling and settled,
     * and the main option label for `unk_46 == 0`. Unverified: the `unk_46 != 0` extras label
     * (no run scrolls into the extras menu).
     */
    public fun sub80AADE0(proc: Proc) {
        val menu = parent(proc)
        val y = ((menu.unk2f * 48) / 0xDC + 0x1D0) and 0xFF

        layers.putExt(4, 48, y, sprite(SPRITE_BANNER), pal(2))
        val label = if (menu.unk46 != 0) {
            if ((menu.extraSelBitfile and EXTRA_MENU_OPTION_MAP) != 0) 10 else 8
        } else {
            bitfileToIndex(menu.mainSelBitfile and 0xFF) and 0xFF
        }
        layers.putExt(4, 56, y + 8, sprite(LABEL_TABLE, label), pal(4))
    }

    /**
     * Draws one main menu option box: the box frame on layer 4 at ([x], [y]) with palette
     * [palIdA], then the option label [spriteIdx] 8 pixels right and down with palette [palIdB].
     *
     * Ported from src/savedraw.c: sub_80AABC4
     */
    public fun sub80AABC4(x: Int, y: Int, spriteIdx: Int, palIdA: Int, palIdB: Int) {
        layers.putExt(4, x and OAM1_X_MASK, y, sprite(SPRITE_BANNER), pal(palIdA))
        layers.putExt(
            4, (x + 8) and OAM1_X_MASK, y + 8, sprite(LABEL_TABLE, spriteIdx and 0xFF), pal(palIdB),
        )
    }

    /**
     * Draws the main menu option boxes, which exist only while the menu is not fully scrolled to
     * the slot list, and moves the cursor onto the highlighted one while the main loop runs.
     *
     * Ported from src/savedraw.c: sub_80AAE90
     */
    public fun sub80AAE90(proc: Proc) {
        val menu = parent(proc)
        val xOffset = menu.unk2f + menu.unk46
        if (xOffset >= 220) return
        var y = 68 - ((menu.unk31 * 25) shr 1)
        if (y < 2) y = 2
        for (i in 0 until menu.unk31) {
            val spriteIdx =
                bitfileToIndex(SaveMenuChoice.saveMenuGetBitfile(menu.mainOptions, i))
            val palA = if (i == menu.mainSelect) 1 else 6
            sub80AABC4(48 - xOffset, y + i * 25, spriteIdx, palA, 4)
        }
        if (menu.jumpLabel == PL_SAVEMENU_MAIN_LOOP) {
            cursor.sub80AB4F4(0, 28, y + menu.mainSelect * 25, proc)
        }
    }

    /**
     * Draws the extras option boxes, which exist only while the extras menu is scrolled in.
     *
     * Transcribed from src/savedraw.c: sub_80AAF6C (not verified: early exit only,
     * `unk_46 == 0` is the only case PC-cap1 runs; the rest runs [Hooks.extraOptions])
     */
    public fun sub80AAF6C(proc: Proc) {
        val menu = parent(proc)
        if (((menu.unk46 - 1) and 0xFFFF) > 438) return
        hooks.extraOptions(proc)
    }

    /**
     * Draws the play time, the banner, the three slot boxes, the suspend bookmark, the confirm
     * prompt with its hand, and queues the slot cursor targets.
     *
     * Partially ported from src/savedraw.c: sub_80AB05C. Verified (`PC-11h`, title continue and
     * resume runs, sprite output per frame): the slot boxes and labels, the play time, the
     * banner, the bookmark sprite for `unk_44 != 0x100` and for the `main_sel_bitfile != 1`
     * resting case, and the confirm box. Unverified: the effect of `APProc_SetParameters` /
     * `APProc_Delete` on the bookmark sprite proc (its own sprites are not compared), the
     * `cursor_slot` static cursor hook and `DisplayUiHand`.
     */
    public fun sub80AB05C(proc: Proc) {
        val menu = parent(proc)

        if (menu.unk2f != 0) {
            sub80AA7EC(proc)
            sub80AADE0(proc)

            for (i in 0 until SLOT_COUNT) {
                val y = if (menu.jumpLabel == PL_SAVEMENU_SLOT_SELECTED && menu.susSlot == i) {
                    0x100
                } else {
                    0
                }
                val x = (248 - menu.unk2f) and 0x1FF
                layers.putExt(4, x, y + 32 + i * 32, sprite(SLOT_BOX_TABLE, i), pal(i * 2 + 10))
                layers.putExt(
                    4, x, y + 32 + i * 32 + 8, sprite(SLOT_LABEL_TABLE, i), pal(i * 2 + 11),
                )
            }

            if (menu.susSlotCur != NO_SLOT) {
                val bookmarkY = menu.susSlotCur * 32
                if (menu.unk44 != 0x100) {
                    menu.approc?.let { apProcs.delete(it) }
                    menu.approc = null
                    layers.putExt(4, 202, bookmarkY + 0x1E, sprite(SPRITE_BOOKMARK), 0x3000)
                } else if (menu.mainSelBitfile == 1) {
                    apProcs.setParameters(
                        checkNotNull(menu.approc), 436 - menu.unk2f, bookmarkY + 48, 0x3140,
                    )
                } else {
                    apProcs.setParameters(checkNotNull(menu.approc), 320, bookmarkY + 48, 0x3140)
                    layers.putExt(
                        4, 422 - menu.unk2f, bookmarkY + 30, sprite(SPRITE_BOOKMARK), 0x3000,
                    )
                }
            }
        }

        if (menu.jumpLabel == PL_SAVEMENU_SAVE_SLOT_SEL ||
            menu.jumpLabel == PL_SAVEMENU_SLOT_SELECTED
        ) {
            if (menu.cursorConfig != 0) {
                layers.putExt(4, 44, 128, sprite(SPRITE_CONFIRM_BOX), pal(2))
                uiHand.displayUiHand(((menu.cursorConfig - 1) % 2) * 44 + 52, 136, clock())
                cursor.sub80AB4F4(1, 12, menu.susSlot * 32 + 32, proc)
            } else if (menu.susSlot != NO_SLOT) {
                cursor.sub80AB4F4(1, 12, menu.susSlot * 32 + 32, proc)
            }

            if (menu.cursorSlot != NO_SLOT) {
                hooks.staticCursor(menu.cursorSlot * 32 + 32, proc)
            }
        }
    }

    /**
     * One frame of `ProcScr_savedraw`: while [SaveDrawProc.unk29] is set, remembers the chosen
     * option bitfile and draws the menu, then scrolls the fog.
     *
     * @param proc the `ProcScr_savedraw` proc
     * @param paletteCycle `sub_80AACBC` (`PC-4c2`)
     * @param scrollFog `SaveDraw_ScrollFogBG`
     * @param particles `sub_80AB56C` (`PC-4c2`), given [SaveDrawProc.unk2a]
     *
     * Partially ported from src/savedraw.c: SaveDraw_Loop_Main. Verified (`PC-11g`, title
     * menu_items, continue and resume runs): the `unk_29 != 0` path with
     * `main_sel_bitfile < 0x100` and not `0x20` (`unk_33` against the captured proc, the `unk_2a`
     * argument of `sub_80AB56C`, each step called once, and the sprites of `sub_80AAE90` and
     * `sub_80AB05C`; the order of the steps is not observable). Unverified:
     * `unk_29 == 0` and the `0x20` extras arm (no run reaches them); the effects behind the
     * [paletteCycle], [scrollFog] and [particles] parameters are not run by these tests.
     */
    public fun loopMain(
        proc: Proc,
        paletteCycle: (Proc) -> Unit,
        scrollFog: (Proc) -> Unit,
        particles: (Int) -> Unit,
    ) {
        val state = proc.state as SaveDrawProc
        if (state.unk29 != 0) {
            val menu = parent(proc)
            if (menu.mainSelBitfile < 0x100) {
                val chosen = if (menu.mainSelBitfile == 0x20) {
                    menu.extraSelBitfile
                } else {
                    menu.mainSelBitfile
                }
                state.unk33 = chosen and 0xFF
            }
            sub80AAE90(proc)
            sub80AAF6C(proc)
            sub80AB05C(proc)
            paletteCycle(proc)
        }
        scrollFog(proc)
        particles(state.unk2a)
    }

    private fun pal(index: Int): Int = (index and 0xF) shl 12

    private companion object {
        const val BITS_PER_BYTE = 8
        const val SLOT_COUNT = 3
        const val NO_SLOT = 0xFF
        const val FRAMES_PER_SECOND = 60u
        const val FRAMES_PER_MINUTE = 3600u
        const val FRAMES_PER_HOUR = 216000u
        const val EXTRA_MENU_OPTION_MAP = 1 shl 4
        const val OAM1_X_MASK = 0x1FF
        const val PL_SAVEMENU_MAIN_LOOP = 2
        const val PL_SAVEMENU_SAVE_SLOT_SEL = 5
        const val PL_SAVEMENU_SLOT_SELECTED = 6

        /** `Sprite_08A20590`: play time label. */
        const val SPRITE_TIME_LABEL = 0x08A20590

        /** `Sprite_08A2051C`: banner box. */
        const val SPRITE_BANNER = 0x08A2051C

        /** `Sprite_08A20588`: suspend bookmark mark. */
        const val SPRITE_BOOKMARK = 0x08A20588

        /** `Sprite_08A20536`: confirm prompt box. */
        const val SPRITE_CONFIRM_BOX = 0x08A20536

        /** `SpriteArray_08A2067C`: large digits (hours, minutes), index 10 is the colon. */
        const val DIGITS_LARGE = 0x08A2067C

        /** `SpriteArray_08A20650`: small digits (seconds), index 10 is the colon. */
        const val DIGITS_SMALL = 0x08A20650

        /** `SpriteArray_08A209B8`: banner option labels. */
        const val LABEL_TABLE = 0x08A209B8

        /** `SpriteArray_08A209F0`: slot box tops. */
        const val SLOT_BOX_TABLE = 0x08A209F0

        /** `SpriteArray_08A209E4`: slot box labels. */
        const val SLOT_LABEL_TABLE = 0x08A209E4
    }
}
