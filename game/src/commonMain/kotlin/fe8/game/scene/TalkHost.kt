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

    /**
     * `LoadObjUIGfx` (`src/bm.c`): the OBJ UI tiles and palettes (they hold the press-key arrow).
     */
    public fun loadObjUiGfx()

    /** `BG_SetPosition(bg, x, y)`. */
    public fun setBgPosition(bg: Int, x: Int, y: Int)

    /**
     * `Proc_Start(gProcScr_TalkSkipListener, PROC_TREE_3)`: the proc that watches B/START (skip
     * the dialogue) and D-pad/A/B (print instantly) while the talk runs.
     */
    public fun startTalkSkipListener()

    /** `gBG0TilemapBuffer`: the tilemap the dialogue text is put on. */
    public fun bg0Tilemap(): ShortArray

    /**
     * `GetStrTalkLen(str, isBubbleOpen)` for the string at GBA address [str]: the pixel width of
     * the widest line (at least 24) of the text that fits the current speaker's bubble.
     */
    public fun getStrTalkLen(str: Int, isBubbleOpen: Boolean): Int

    /** `ClearTalkBubble`: closes the speech bubble and blanks the text. */
    public fun clearTalkBubble()

    /** `StartTalkOpen(faceSlot, parent)`: opens a bubble for the face in slot [faceSlot]. */
    public fun startTalkOpen(faceSlot: Int, parent: Proc)

    /**
     * `SetTalkFaceLayer(faceSlot, toBack)`: gives the face of [faceSlot] sprite layer 5 and the
     * other faces of its group (slots 0..2 or 3..5) layer 6; [flag] is `toBack` (`TALK_FLAG_4`),
     * and a non-zero value changes nothing.
     */
    public fun setTalkFaceLayer(faceSlot: Int, flag: Int)

    /** `Proc_StartBlocking(gProcScr_TalkShiftClear, parent)`: scrolls the text up one line. */
    public fun startTalkShiftClear(parent: Proc)

    /**
     * `TalkBgSync(bg)`: syncs BG mask [bg] to VRAM, except for sprite talks (`TALK_FLAG_SPRITE`).
     */
    public fun talkBgSync(bg: Int)

    /** `SetTalkFaceMouthMove(faceSlot)`: the face's mouth starts moving (`FACE_DISP_TALK_1`). */
    public fun setTalkFaceMouthMove(faceSlot: Int)

    /** The array behind GBA address [addr] of a message string (EWRAM buffer, ROM or IWRAM). */
    public fun stringArray(addr: Int): ByteArray

    /** Offset of GBA address [addr] inside [stringArray]. */
    public fun stringOffset(addr: Int): Int

    /** `IsTalkFaceMoving`: a `gProcScr_TalkFaceMove` proc exists. */
    public fun isTalkFaceMoving(): Boolean

    /** `SetTalkFaceNoMouthMove(faceSlot)`: the face's mouth stops moving. */
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

    /**
     * `sub_80064D4(face, mode)`: asks the face's blink proc for an eye state: 0 normal blinking,
     * 2 eyes closed, 3 eyes half closed, 4 one wink (from codes `0x80 0x1C..0x1F`).
     */
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

    /**
     * `Proc_StartBlocking(gProcScr_TalkShiftClearAll, parent)`: scrolls all text away (`[2NL]`).
     */
    public fun startTalkShiftClearAll(parent: Proc)

    /** `PAL_COLOR(bank, index) = color` (the palette buffer, then synced); a 15-bit BGR colour. */
    public fun setPaletteColor(bank: Int, index: Int, color: Int)

    /** `TalkFlushAllLine`: clears both lines of a sprite talk (`[2NL]` with `TALK_FLAG_7`). */
    public fun talkFlushAllLine()

    /** `Proc_EndEach(gProcScr_TalkSkipListener)`. */
    public fun endTalkSkipListener()

    /** `Proc_EndEach(gProcScr_TalkShiftClearAll)`. */
    public fun endTalkShiftClearAll()

    /** `NumberToStringAscii(number, buf)`. */
    public fun numberToStringAscii(number: Int, buf: ByteArray)

    /** `GetTacticianName`: GBA address of the player's tactician name (`[Tact]`, `0x80 0x20`). */
    public fun tacticianName(): Int

    /**
     * `GetTextDisplaySpeed`: frames per printed character from the text-speed option (8, 4, 1 or
     * 0).
     */
    public fun textDisplaySpeed(): Int

    /** `GetGameClock`: the frame counter that drives the arrow animation and the print blip. */
    public fun gameClock(): Int

    /** `gKeyStatusPtr->newKeys`: the buttons newly pressed this frame. */
    public fun newKeys(): Int

    /** `PlaySoundEffect(song)`. */
    public fun playSoundEffect(song: Int)

    /**
     * `PutSprite(layer, x, y, gPressKeyArrowSpriteLut[lutIndex], oam2)`: the bobbing "press a key"
     * arrow; the table has 16 entries, three distinct poses.
     */
    public fun putSprite(layer: Int, x: Int, y: Int, lutIndex: Int, oam2: Int)
}
