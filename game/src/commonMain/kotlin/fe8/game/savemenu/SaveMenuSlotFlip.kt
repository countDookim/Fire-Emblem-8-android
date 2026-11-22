package fe8.game.savemenu

import fe8.game.RomView
import fe8.game.bm.ChapterTitle
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.render.OamBuffer

/**
 * The step after a save slot was chosen in the post-chapter save menu (`PC-4e2`,
 * `src/savemenu.c`): [saveMenuRegisterSlotSelected] starts the slot box flip and
 * [saveMenuWaitSlotBoxScrolling] drives it: refreshes slot 0 metadata, flips OBJ affine matrix
 * `sus_slot`, rebuilds the option bitfields ([SaveMenuChoice]) and picks the next proc label.
 *
 * @param engine proc scheduler, for `Proc_Goto`
 * @param rom ROM view supplying the sine table
 * @param oam OAM shadow buffer (`gOam`) whose affine matrix `sus_slot` is flipped
 * @param chapterTitle chapter title loader (`PutChapterTitleGfx`)
 * @param slotData slot metadata and palette loader
 * @param choice option bitfield builder (`InitSaveMenuChoice`)
 */
public class SaveMenuSlotFlip(
    private val engine: ProcEngine,
    private val rom: RomView,
    private val oam: OamBuffer,
    private val chapterTitle: ChapterTitle,
    private val slotData: SaveSlotData,
    private val choice: SaveMenuChoice,
    /** Callbacks for the parts that other tasks own. */
    public val hooks: Hooks = Hooks(),
) {
    /** External callbacks. Defaults are no-ops until their owning tasks are ported. */
    public class Hooks(
        /** `PlaySoundEffect(songId)`. */
        public var playSoundEffect: (songId: Int) -> Unit = {},
        /** `StartBgmVolumeChange(volume, end, time, proc)`. */
        public var startBgmVolumeChange: (Int, Int, Int, Proc?) -> Unit = { _, _, _, _ -> },
        /** `SaveMenuModifySaveSlot(slot, valid, position)`. Stand-in (not ported). */
        public var modifySaveSlot: (slot: Int, valid: Boolean, position: Int) -> Int =
            { slot, _, _ -> slot },
    )

    /**
     * Starts the slot flip: jump target [SaveMenuPostChapter.PL_SAVEMENU_SLOT_SELECTED] and a
     * zeroed frame counter.
     *
     * Ported from src/savemenu.c: SaveMenuRegisterSlotSelected
     */
    public fun saveMenuRegisterSlotSelected(proc: Proc) {
        val state = proc.state as SaveMenuState
        state.jumpLabel = SaveMenuPostChapter.PL_SAVEMENU_SLOT_SELECTED
        state.scrollCnt = 0
    }

    /**
     * One frame of the slot box flip: at frame 8 refreshes the data, title image and palette of
     * slot `sus_slot` and of slot 4; at frame `0x20` rebuilds the options and jumps on; at
     * `0x30` returns to the main menu. Frames 0..0xF set affine matrix `sus_slot` (a vertical
     * squash that reopens).
     *
     * Transcribed from src/savemenu.c: SaveMenuWaitSlotBoxScrolling (not verified:
     * `ram_trace_prologue_end` runs the frame 8 refresh, the affine flip, the frame `0x20` branch
     * with `main_sel_bitfile == MAIN_MENU_OPTION_INVALID` and no `0x30` frame; the `NEW_GAME`
     * and has-options branches, the `0x30` branch and the non-zero `sus_slot` and non-empty
     * title branches are not observed; frames 0..0x20 match f9804..9844 otherwise)
     */
    public fun saveMenuWaitSlotBoxScrolling(proc: Proc) {
        val state = proc.state as SaveMenuState
        if (state.scrollCnt == 8) {
            slotData.saveMenuInitSaveSlotData(state.susSlot, state.slots)
            slotData.saveMenuInitSaveSlotData(4, state.slots)
            val chapterIdx = state.slots.chapterIdx[state.susSlot]
            val titleBase = (OBJ_PRIORITY_2 + OBJCHR_SAVEMENU_TITLEGFX) * TILE_SIZE_4BPP
            val chr = ((state.susSlot * TITLE_SLOT_BYTES + titleBase) and VRAM_OFFSET_MASK) /
                TILE_SIZE_4BPP
            chapterTitle.putChapterTitleGfx(chr, if (chapterIdx != 0xFF) chapterIdx else -1)
            slotData.saveMenuInitSlotPalette(state.susSlot)
        } else if (state.scrollCnt == 0x20) {
            choice.initSaveMenuChoice(state)

            if (state.mainSelBitfile == PostSaveMenu.MAIN_MENU_OPTION_NEW_GAME) {
                engine.goto(proc, PL_SAVEMENU_NEW_GAME)
                hooks.startBgmVolumeChange(0xC0, 0, 0x10, null)
            } else if (state.mainSelBitfile == PostSaveMenu.MAIN_MENU_OPTION_INVALID) {
                engine.goto(proc, SaveMenuPostChapter.PL_SAVEMENU_EXIT_FADE)
            } else if (state.mainSelBitfile and state.mainOptions != 0) {
                if (state.cursorSlot != 0xFF) {
                    state.susSlot = state.cursorSlot
                    state.cursorSlot = 0xFF
                } else {
                    state.susSlot = hooks.modifySaveSlot(state.susSlot, true, 1)
                }
                engine.goto(proc, SaveMenuPostChapter.PL_SAVEMENU_SAVE_SLOT_SEL)
            }
        } else if (state.scrollCnt == 0x30) {
            state.susSlot = 0
            state.cursorSlot = 0xFF
            state.scrollCnt = 0
            state.mainSelect = 0
            state.mainSelBitfile = SaveMenuChoice.saveMenuGetBitfile(state.mainOptions, 0)
            hooks.playSoundEffect(SONG_SE_SYS_WINDOW_CANSEL1)
            engine.goto(proc, PL_SAVEMENU_BACK_TO_MAIN)
            return
        }

        val cnt = state.scrollCnt
        if (cnt == 0x10) {
            setAffine(state.susSlot, UNIT)
        } else if (cnt <= 7) {
            setAffine(state.susSlot, 0x100 - cnt * 0x20)
        } else if (cnt < 0x10) {
            setAffine(state.susSlot, cnt * 0x20 - 0xE0)
        }
        state.scrollCnt = (cnt + 1) and 0xFF
    }

    /** `SetObjAffine(slot, COS, -SIN, SIN, COS)` of angle 0 with the y scale [yScale]. */
    private fun setAffine(slot: Int, yScale: Int) {
        val sin0 = rom.u16(SIN_LOOKUP).toShort().toInt()
        val cos0 = rom.u16(SIN_LOOKUP + COS_OFFSET * 2).toShort().toInt()
        oam.setObjAffine(
            slot,
            cos0 * 16 / UNIT,
            -sin0 * 16 / yScale,
            sin0 * 16 / UNIT,
            cos0 * 16 / yScale,
        )
    }

    private companion object {
        const val PL_SAVEMENU_BACK_TO_MAIN = 4
        const val PL_SAVEMENU_NEW_GAME = 18
        const val SONG_SE_SYS_WINDOW_CANSEL1 = 0x6B
        const val SIN_LOOKUP = SaveMenuScreen.SIN_LOOKUP_ADDRESS
        const val COS_OFFSET = 0x40
        const val UNIT = 0x100
        const val OBJ_PRIORITY_2 = 2 shl 10
        const val TILE_SIZE_4BPP = 0x20
        const val TITLE_SLOT_BYTES = 0x800
        const val VRAM_OFFSET_MASK = 0x1FFFF
        const val OBJCHR_SAVEMENU_TITLEGFX = SaveMenuScreen.OBJCHR_SAVEMENU_TITLEGFX
    }
}
