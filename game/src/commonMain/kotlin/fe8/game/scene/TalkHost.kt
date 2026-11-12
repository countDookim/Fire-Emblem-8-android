package fe8.game.scene

import gbafe.proc.Proc
import gbafe.text.Text

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

    /** The array behind GBA address [addr] of a message string (EWRAM buffer, ROM or IWRAM). */
    public fun stringArray(addr: Int): ByteArray

    /** Offset of GBA address [addr] inside [stringArray]. */
    public fun stringOffset(addr: Int): Int

    /** `IsTalkFaceMoving`: a `gProcScr_TalkFaceMove` proc exists. */
    public fun isTalkFaceMoving(): Boolean

    /** `SetTalkFaceNoMouthMove(faceSlot)`. */
    public fun setTalkFaceNoMouthMove(faceSlot: Int)

    /**
     * `TalkLoadFace(proc)`: loads the face whose id is at the string cursor ([TalkState.str]) into
     * the active slot and updates `faces` (face procs belong to the face port).
     */
    public fun talkLoadFace(proc: Proc)

    /** `StartFaceFadeOut(face)`. */
    public fun startFaceFadeOut(face: Int)

    /** `StartTemporaryLock(proc, frames)`. */
    public fun startTemporaryLock(proc: Proc, frames: Int)

    /**
     * `MoveTalkFace(from, to)`: slides the face of slot [from] to slot [to], swapping `faces`.
     */
    public fun moveTalkFace(from: Int, to: Int)

    /** `SetFaceBlinkControl(face, mode)`. */
    public fun setFaceBlinkControl(face: Int, mode: Int)

    /** `sub_80064D4(face, mode)`: the eye state of a face. */
    public fun setFaceEyes(face: Int, mode: Int)

    /**
     * `StartTalkChoice` with the yes/no ([buySell] false) or buy/sell choice at tilemap [offset].
     */
    public fun startTalkChoice(
        buySell: Boolean,
        text: Text,
        tm: ShortArray,
        offset: Int,
        defaultChoice: Int,
        color: Int,
        parent: Proc,
    )

    /** `Proc_StartBlocking(gProcScr_TalkShiftClearAll, parent)`. */
    public fun startTalkShiftClearAll(parent: Proc)

    /** `PAL_COLOR(bank, index) = color` (the palette buffer, then synced). */
    public fun setPaletteColor(bank: Int, index: Int, color: Int)

    /** `TalkFlushAllLine` (sprite text). */
    public fun talkFlushAllLine()

    /** `Proc_EndEach(gProcScr_TalkSkipListener)`. */
    public fun endTalkSkipListener()

    /** `Proc_EndEach(gProcScr_TalkShiftClearAll)`. */
    public fun endTalkShiftClearAll()

    /** `NumberToStringAscii(number, buf)`. */
    public fun numberToStringAscii(number: Int, buf: ByteArray)

    /** `GetTacticianName`: GBA address of the player name. */
    public fun tacticianName(): Int

    /** `GetTextDisplaySpeed`. */
    public fun textDisplaySpeed(): Int

    /** `GetGameClock`. */
    public fun gameClock(): Int

    /** `gKeyStatusPtr->newKeys`. */
    public fun newKeys(): Int

    /** `PlaySoundEffect(song)`. */
    public fun playSoundEffect(song: Int)

    /** `PutSprite(layer, x, y, gPressKeyArrowSpriteLut[lutIndex], oam2)`. */
    public fun putSprite(layer: Int, x: Int, y: Int, lutIndex: Int, oam2: Int)
}
