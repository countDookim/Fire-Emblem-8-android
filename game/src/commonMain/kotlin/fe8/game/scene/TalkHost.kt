package fe8.game.scene

import gbafe.proc.Proc

/**
 * Services the talk window (`src/scene.c`) uses that live outside the functions [Talk] ports:
 * decompression and palettes, background registers, the face and bubble procs, and the
 * background tilemap. Each member names the C function it stands for.
 */
public interface TalkHost {
    /** `GetBackgroundTileDataOffset(bg)`: byte offset of the background's character base. */
    public fun backgroundTileDataOffset(bg: Int): Int

    /** `Decompress(src, dest)`: LZ77 data at ROM address [src] into VRAM address [dest]. */
    public fun decompressToVram(src: Int, dest: Int)

    /** `ApplyPalette(src, palId)`: 16 colours at ROM address [src] into palette bank [palId]. */
    public fun applyPalette(src: Int, palId: Int)

    /** `LoadObjUIGfx`. */
    public fun loadObjUiGfx()

    /** `BG_SetPosition(bg, x, y)`. */
    public fun setBgPosition(bg: Int, x: Int, y: Int)

    /** `Proc_Start(gProcScr_TalkSkipListener, PROC_TREE_3)`. */
    public fun startTalkSkipListener()

    /** `gBG0TilemapBuffer`. */
    public fun bg0Tilemap(): ShortArray

    /** `GetStrTalkLen(str, isBubbleOpen)` for the string at GBA address [str]. */
    public fun getStrTalkLen(str: Int, isBubbleOpen: Boolean): Int

    /** `ClearTalkBubble`. */
    public fun clearTalkBubble()

    /** `StartTalkOpen(faceSlot, parent)`. */
    public fun startTalkOpen(faceSlot: Int, parent: Proc)

    /** `SetTalkFaceLayer(faceSlot, flag)`. */
    public fun setTalkFaceLayer(faceSlot: Int, flag: Int)

    /** `Proc_StartBlocking(gProcScr_TalkShiftClear, parent)`. */
    public fun startTalkShiftClear(parent: Proc)

    /** `TalkBgSync(bg)`. */
    public fun talkBgSync(bg: Int)

    /** `SetTalkFaceMouthMove(faceSlot)`. */
    public fun setTalkFaceMouthMove(faceSlot: Int)
}
