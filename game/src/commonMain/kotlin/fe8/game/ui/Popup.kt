package fe8.game.ui

import fe8.game.RomView
import fe8.game.bm.ItemInfo
import gbafe.render.BgHardware
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextSystem

/**
 * The popup banner of `src/popup.c`: the small framed message that appears over the map when
 * an item or gold is received or stolen, a weapon breaks, a weapon rank rises or a new ally can
 * be used (the `PopupScr_*` lists). A popup is a list of `struct PopupInstruction` (sound,
 * colour, message, item name, icon, number ...) that is measured, centred and drawn; this
 * object holds the state of `struct PopupProc` and the steps of `ProcScr_Popup`. [PopupScript]
 * runs them as the proc.
 */
public object Popup {
    /** ROM address of `ProcScr_Popup`. */
    public const val PROC_SCRIPT: Int = 0x085921C8

    /** ROM address of `ProcScr_PopupUpdateIcon`. */
    public const val UPDATE_ICON_PROC_SCRIPT: Int = 0x08592228

    /** `TEXT_COLOR_SYSTEM_WHITE` (= `TEXT_COLOR_0123`, 0): the default text palette of a popup. */
    public const val TEXT_COLOR_SYSTEM_WHITE: Int = 0

    /**
     * Bytes per `struct PopupInstruction`: a u8 opcode, 3 bytes padding, a u32 operand (`data`).
     */
    private const val INSTRUCTION_SIZE = 8

    /**
     * DISPCNT bits 13..15: window 0, window 1 and the OBJ window enable. `PopupProc_PrepareGfx`
     * clears them every time; the decomp gives no reason.
     */
    private const val DISPCNT_WINDOWS = 0xE000 // win0_on, win1_on, objWin_on

    // Opcodes of a popup instruction list (`enum popup_opcode_index`, include/popup.h). The
    // operand is the instruction's `data` word.

    /** `POPUP_OP_END`: ends the list. */
    private const val OP_END = 0x00

    /** `POPUP_OP_SPACE`: a gap of `data` pixels. */
    private const val OP_SPACE = 0x01

    /** `POPUP_OP_ITEM_NAME`: the name of `gPopupItem`. */
    private const val OP_ITEM_NAME = 0x02

    /**
     * `POPUP_OP_ITEM_STR_CAP`: the item name with its article (a special one for Junafruit;
     * none for gold, the Metis tome and the legendary weapons), capitalised:
     * `GetItemNameWithArticle(item, 1)`.
     */
    private const val OP_ITEM_STR_CAP = 0x03

    /**
     * `POPUP_OP_ITEM_STR`: the item name with its article as for [OP_ITEM_STR_CAP], lower case:
     * `GetItemNameWithArticle(item, 0)`.
     */
    private const val OP_ITEM_STR = 0x04

    /** `POPUP_OP_UNIT_NAME`: the name of `gpPopupUnit`. */
    private const val OP_UNIT_NAME = 0x05

    /** `POPUP_OP_MSG`: the message with text id `data`. */
    private const val OP_MSG = 0x06

    /** `POPUP_OP_STR`: the 0-terminated string at ROM address `data`. */
    private const val OP_STR = 0x07

    /** `POPUP_OP_COLOR`: switches the text colour to `data` (a `TEXT_COLOR_*`); takes no width. */
    private const val OP_COLOR = 0x08

    /** `POPUP_OP_ITEM_ICON`: the icon of `gPopupItem`, 16 pixels wide. */
    private const val OP_ITEM_ICON = 0x09

    /**
     * `POPUP_OP_WTYPE_ICON`: the weapon-type icon (icon id `gPopupItem + 0x70`), 16 pixels wide.
     */
    private const val OP_WTYPE_ICON = 0x0A

    /** `POPUP_OP_NUM`: `gPopupNumber` in decimal, 8 pixels per digit. */
    private const val OP_NUM = 0x0B

    /** `POPUP_OP_SOUND`: the sound effect (song id `data`) to play when the popup appears. */
    private const val OP_SOUND = 0x0C

    /**
     * The `struct PopupProc` fields from offset 0x2C on; integer types as in the struct. The
     * `x/y` fields are in 8x8 tiles of the BG0 tilemap (32 cells per row).
     */
    public class PopupProc {
        /** `pDefinition` (+0x2C): ROM address of the instruction list. */
        public var pDefinition: Int = 0

        /**
         * `clock` (+0x30): frames left before the popup closes (the callers use 0x60); negative
         * waits for any new key press; 0 never closes (see [waitForPress]).
         */
        public var clock: Int = 0

        /** `xTileParam` (+0x34), s8: requested frame column; -1 (the default) centres the popup. */
        public var xTileParam: Int = 0

        /** `yTileParam` (+0x35), s8: requested frame row; -1 (the default) is row 8. */
        public var yTileParam: Int = 0

        /** `winStyle` (+0x36), u8: which of the four UI frame styles surrounds the text. */
        public var winStyle: Int = 0

        /** `xTileReal` (+0x37), u8: the column the frame was drawn at. */
        public var xTileReal: Int = 0

        /** `yTileReal` (+0x38), u8: the row the frame was drawn at. */
        public var yTileReal: Int = 0

        /** `xTileSize` (+0x39), u8: frame width in tiles (text tiles + 2); [gfxClear] erases it. */
        public var xTileSize: Int = 0

        /** `yTileSize` (+0x3A), u8: set to 3 when drawn; the frame itself is 4 tiles tall. */
        public var yTileSize: Int = 0

        /** `textColorId` (+0x3B), u8: the text colour the list starts with (a `TEXT_COLOR_*`). */
        public var textColorId: Int = 0

        /**
         * `iconId` (+0x3E), u16: the icon to show (item icon id, or weapon type + 0x70); 0xFFFF
         * for none.
         */
        public var iconId: Int = 0

        /**
         * `iconObjTileId` (+0x40), u16: the OBJ tile the icon graphics are loaded to (0x240 for
         * `NewPopup_Simple`).
         */
        public var iconObjTileId: Int = 0

        /** `iconPalId` (+0x42), u8: OBJ palette of the icon (palette base + 0x10). */
        public var iconPalId: Int = 0

        /** `iconX` (+0x44), u8: pixel offset of the icon inside the text strip. */
        public var iconX: Int = 0

        /** `xGfxSize` (+0x46), u16: width of the text in pixels. */
        public var xGfxSize: Int = 0

        /** `soundId` (+0x48), u16: song id of the sound effect; 0 for none. */
        public var soundId: Int = 0

        /** Set when the proc was broken out of its wait loop (`Proc_Break`). */
        public var broken: Boolean = false
    }

    /**
     * Puts the proc in its default state: frame position automatic (-1, -1), white text, no icon
     * (`iconId` 0xFFFF, the C assigns -1 to a u16) and no sound.
     *
     * Ported from src/popup.c: PopupProc_Init
     */
    public fun init(proc: PopupProc) {
        proc.xTileParam = -1
        proc.yTileParam = -1
        proc.textColorId = TEXT_COLOR_SYSTEM_WHITE
        proc.iconId = 0xFFFF
        proc.iconX = 0
        proc.soundId = 0
    }

    /**
     * Prepares the popup's graphics: initialises the default font at the BG0 character base plus
     * 0x2000 (tile 0x100, palette 0), clears the icon tables ([drawnIconLookupTable],
     * [iconGfxIdLookupTable]), loads the UI frame graphics into [hardware], removes the colour
     * effect (`SetDefaultColorEffects` is `SetBlendNone`), switches the two windows and the OBJ
     * window off in `hardware.lcd.dispcnt` and stores the text width
     * from [parsePopupInstAndGetLen] in [PopupProc.xGfxSize]. The other parameters are as for
     * [parsePopupInstAndGetLen].
     *
     * Ported from src/popup.c: PopupProc_PrepareGfx
     */
    public fun prepareGfx(
        proc: PopupProc,
        rom: RomView,
        messages: MsgStrings,
        text: TextSystem,
        hardware: BgHardware,
        drawnIconLookupTable: ByteArray,
        iconGfxIdLookupTable: ByteArray,
        popupItem: Int,
        popupNumber: Int,
        unitNameTextId: Int,
        loadIconPalette: (slot: Int, palId: Int) -> Unit,
    ) {
        val lcd = hardware.lcd
        val charBase = ((lcd.bgCnt[0] shr 2) and 3) shl 14
        text.initTextFont(null, 0x06000000 + charBase + 0x2000, 0x100, 0)
        ItemIcon.resetIconGraphics(drawnIconLookupTable, iconGfxIdLookupTable)
        UiFrame.loadUiFrameGraphics(hardware, rom)
        lcd.setBlendConfig(0, 0x10, 0, 0) // SetDefaultColorEffects: SetBlendNone
        lcd.dispcnt = lcd.dispcnt and DISPCNT_WINDOWS.inv()
        proc.xGfxSize = parsePopupInstAndGetLen(
            proc, rom, messages, text, popupItem, popupNumber, unitNameTextId, loadIconPalette,
        )
    }

    /**
     * Lowers the BGM to half volume while a popup with a sound is shown, so the jingle is heard:
     * [startBgmVolumeChange] stands for `StartBgmVolumeChange(volumeInit, volumeEnd, duration,
     * proc)`, called with 0x100 (full), 0x80 (half) and 0x10 (the duration).
     *
     * Ported from src/popup.c: PopupProc_MaybeSetVolume
     */
    public fun maybeSetVolume(
        proc: PopupProc,
        startBgmVolumeChange: (volume: Int, end: Int, time: Int) -> Unit,
    ) {
        if (proc.soundId != 0) startBgmVolumeChange(0x100, 0x80, 0x10)
    }

    /**
     * Plays the popup's sound effect (the `POPUP_SOUND` song id: 0x5A in
     * the received / stole / rank-up / new-ally popups, 0x5C in the pilfered / stolen / broken
     * ones). [playSoundEffect] stands for `PlaySoundEffect`.
     *
     * Transcribed from src/popup.c: PopupProc_PlaySound (not verified: the trace has no record
     * of the sound effect call)
     */
    public fun playSound(proc: PopupProc, playSoundEffect: (songId: Int) -> Unit) {
        if (proc.soundId != 0) playSoundEffect(proc.soundId)
    }

    /**
     * Brings the BGM back from half to full volume when the popup had a sound:
     * [startBgmVolumeChange] is as for [maybeSetVolume], called with 0x80, 0x100, 0x10.
     *
     * Ported from src/popup.c: PopupProc_MaybeResetVolume
     */
    public fun maybeResetVolume(
        proc: PopupProc,
        startBgmVolumeChange: (volume: Int, end: Int, time: Int) -> Unit,
    ) {
        if (proc.soundId != 0) startBgmVolumeChange(0x80, 0x100, 0x10)
    }

    /**
     * Walks the instruction list at [PopupProc.pDefinition] (`struct PopupInstruction`, 8 bytes:
     * u8 opcode, u32 operand at +4), sets [PopupProc.soundId], [PopupProc.iconX] and
     * [PopupProc.iconId] and returns the popup text width in pixels: strings are measured with the
     * font, an icon is 16 pixels, a number 8 pixels per digit, `OP_SPACE` its operand and a colour
     * change nothing. [loadIconPalette] stands for
     * `LoadIconPalette(slot, proc->iconPalId)`; [popupItem], [popupNumber] and [unitNameTextId]
     * (`gpPopupUnit->pCharacterData->nameTextId`) are the popup globals. The active font of
     * [text] measures the strings.
     *
     * Ported from src/popup.c: ParsePopupInstAndGetLen
     */
    public fun parsePopupInstAndGetLen(
        proc: PopupProc,
        rom: RomView,
        messages: MsgStrings,
        text: TextSystem,
        popupItem: Int,
        popupNumber: Int,
        unitNameTextId: Int,
        loadIconPalette: (slot: Int, palId: Int) -> Unit,
    ): Int {
        val items = ItemInfo(rom)
        var widthPx = 0
        var instAddr = proc.pDefinition
        while (rom.u8(instAddr) != OP_END) {
            val operand = rom.u32(instAddr + 4)
            when (rom.u8(instAddr)) {
                OP_SOUND -> proc.soundId = operand
                OP_NUM -> widthPx += numberToStringAsciiLength(popupNumber) * 8
                OP_ITEM_ICON -> {
                    proc.iconX = widthPx
                    proc.iconId = items.getItemIconId(popupItem)
                    loadIconPalette(0, proc.iconPalId)
                    widthPx += 0x10
                }
                OP_WTYPE_ICON -> {
                    proc.iconX = widthPx
                    proc.iconId = popupItem + 0x70
                    loadIconPalette(1, proc.iconPalId)
                    widthPx += 0x10
                }
                OP_MSG -> widthPx += text.getStringTextLen(messages.getStringFromIndex(operand))
                OP_STR -> widthPx += text.getStringTextLen(rom.bytesAt(operand))
                OP_UNIT_NAME ->
                    widthPx += text.getStringTextLen(messages.getStringFromIndex(unitNameTextId))
                OP_ITEM_NAME ->
                    widthPx += text.getStringTextLen(items.getItemName(popupItem, messages))
                OP_ITEM_STR_CAP -> widthPx += text.getStringTextLen(
                    items.getItemNameWithArticle(popupItem, true, messages),
                )
                OP_ITEM_STR -> widthPx += text.getStringTextLen(
                    items.getItemNameWithArticle(popupItem, false, messages),
                )
                OP_SPACE -> widthPx += operand
                else -> Unit
            }
            instAddr += INSTRUCTION_SIZE
        }
        return widthPx
    }

    /**
     * Lays the popup out and draws it (the frame centred on the screen, the text inside it, the
     * icon as a sprite): parses the instruction list, draws the frame into
     * [hardware]'s BG0/BG1, draws the text strip into [text]'s font and BG0, and loads the icon
     * graphics into [hardware] when [PopupProc.iconId] is not 0xFFFF. [loadIconPalette],
     * [popupItem], [popupNumber] and [unitNameTextId] are as for [parsePopupInstAndGetLen].
     * `ResetText` is `InitTextFont(&gDefaultFont, VRAM + 0x1000, 0x80, 0)` on [text].
     *
     * Returns the fields `unk_2C` (x), `unk_30` (y) and `unk_4A` (OAM attribute 2) of the
     * `PopupIconUpdateProc` child the original starts (see [iconChildFields]), or null when there
     * is no icon.
     *
     * Ported from src/popup.c: PopupProc_GfxDraw
     */
    public fun gfxDraw(
        proc: PopupProc,
        rom: RomView,
        messages: MsgStrings,
        text: TextSystem,
        hardware: BgHardware,
        popupItem: Int,
        popupNumber: Int,
        unitNameTextId: Int,
        loadIconPalette: (slot: Int, palId: Int) -> Unit,
    ): IntArray? {
        val widthPx = parsePopupInstAndGetLen(
            proc, rom, messages, text, popupItem, popupNumber, unitNameTextId, loadIconPalette,
        )
        val tileLen = gfxDrawLayout(proc, widthPx)
        val iconPos = (tileLen * 8 - proc.xGfxSize) shr 1
        UiFrame.drawUiFrame2(
            hardware, rom, proc.xTileReal, proc.yTileReal, tileLen + 2, 4, proc.winStyle,
        )
        val popupText = Text()
        text.initText(popupText, tileLen)
        popupText.colorId = proc.textColorId
        popupText.x = iconPos
        generatePopupText(
            proc.pDefinition, popupText, rom, messages, text, popupItem, popupNumber,
            unitNameTextId,
        )
        if (proc.iconId != 0xFFFF) {
            ItemIcon.loadIconObjectGraphics(hardware, rom, proc.iconId, proc.iconObjTileId)
        }
        text.putText(
            popupText, hardware.mapBuffer(0), (proc.yTileReal + 1) * 32 + proc.xTileReal + 1,
        )
        text.initTextFont(null, 0x06001000, 0x80, 0)
        return if (proc.iconId != 0xFFFF) iconChildFields(proc) else null
    }

    /**
     * End callback of the popup proc: zeroes the popup's rectangle in the BG0 and BG1 tilemap
     * buffers [bg0] and [bg1]. The original's ARM `TmFillRect` counts both sizes inclusively, so
     * it clears `xTileSize + 1` by `yTileSize + 1` cells starting at (`xTileReal`, `yTileReal`),
     * with a row stride of 32 cells. The host requests the BG sync after this helper returns.
     *
     * Ported from src/popup.c: PopupProc_GfxClear
     */
    public fun gfxClear(proc: PopupProc, bg0: ShortArray, bg1: ShortArray) {
        for (buffer in arrayOf(bg0, bg1)) {
            for (row in 0..proc.yTileSize) {
                for (col in 0..proc.xTileSize) {
                    val cellIndex = (proc.yTileReal + row) * 32 + proc.xTileReal + col
                    if (cellIndex in buffer.indices) buffer[cellIndex] = 0
                }
            }
        }
    }

    /**
     * Sets the layout fields from the text width [widthPx] (as [parsePopupInstAndGetLen] returns
     * it) and returns the text width in tiles (rounded up). Unless [PopupProc.xTileParam] says
     * otherwise the frame is centred on the 30-tile screen (`(0x1E - tiles) / 2 - 1`; the frame
     * is `tiles + 2` wide), at row 8 unless [PopupProc.yTileParam] is set; the text is centred in
     * its tile-rounded strip and [PopupProc.iconX] moves by the same margin. Helper of [gfxDraw];
     * the C does this inline in `PopupProc_GfxDraw`.
     */
    public fun gfxDrawLayout(proc: PopupProc, widthPx: Int): Int {
        proc.xGfxSize = widthPx and 0xFFFF
        var tileLen = (widthPx shl 16) shr 19
        if (widthPx and 7 != 0) tileLen++
        val iconPos = (tileLen * 8 - proc.xGfxSize) shr 1
        val xPos = if (proc.xTileParam == -1) ((0x1E - tileLen) shr 1) - 1 else proc.xTileParam
        val yPos = if (proc.yTileParam != -1) proc.yTileParam else 8
        proc.xTileReal = xPos and 0xFF
        proc.yTileReal = yPos and 0xFF
        proc.xTileSize = (tileLen + 2) and 0xFF
        proc.yTileSize = 3
        proc.iconX = (proc.iconX + iconPos) and 0xFF
        return tileLen
    }

    /**
     * Returns `unk_2C` (the icon's x pixel), `unk_30` (its y pixel) and `unk_4A` (OAM attribute 2:
     * OBJ tile id in the low bits, palette in bits 12..15) of the `PopupIconUpdateProc` child that
     * [gfxDraw] starts when [PopupProc.iconId] is not 0xFFFF. The icon sits one tile inside the
     * frame, at [PopupProc.iconX] along the text.
     */
    public fun iconChildFields(proc: PopupProc): IntArray = intArrayOf(
        (proc.xTileReal + 1) * 8 + proc.iconX,
        (proc.yTileReal + 1) * 8,
        (proc.iconObjTileId or ((proc.iconPalId and 0xF) shl 12)) and 0xFFFF,
    )

    /**
     * Draws the popup text described by the instruction list at [definition] into a copy of
     * [startText] (C passes `struct Text` by value, so the caller's pen is untouched). [text] and
     * [messages] supply the drawing and the message strings; [popupItem], [popupNumber] and
     * [unitNameTextId] are the popup globals. Icon opcodes only leave a 16-pixel gap (the icon is
     * a sprite); `OP_SPACE` leaves its operand in pixels. The `BG_EnableSyncByMask` at the end is
     * a no-op here.
     *
     * Ported from src/popup.c: GeneratePopupText
     */
    public fun generatePopupText(
        definition: Int,
        startText: Text,
        rom: RomView,
        messages: MsgStrings,
        text: TextSystem,
        popupItem: Int,
        popupNumber: Int,
        unitNameTextId: Int,
    ) {
        val items = ItemInfo(rom)
        val pen = Text()
        pen.chrPosition = startText.chrPosition
        pen.x = startText.x
        pen.colorId = startText.colorId
        pen.tileWidth = startText.tileWidth
        pen.dbEnabled = startText.dbEnabled
        pen.dbId = startText.dbId
        pen.isPrinting = startText.isPrinting
        var instAddr = definition
        while (rom.u8(instAddr) != OP_END) {
            val operand = rom.u32(instAddr + 4)
            when (rom.u8(instAddr)) {
                OP_NUM -> text.drawString(pen, numberToStringAscii(popupNumber))
                OP_WTYPE_ICON, OP_ITEM_ICON -> pen.x += 0x10
                OP_COLOR -> pen.colorId = operand
                OP_MSG -> text.drawString(pen, messages.getStringFromIndex(operand))
                OP_STR -> text.drawString(pen, rom.bytesAt(operand))
                OP_UNIT_NAME ->
                    text.drawString(pen, messages.getStringFromIndex(unitNameTextId))
                OP_ITEM_NAME -> text.drawString(pen, items.getItemName(popupItem, messages))
                OP_ITEM_STR_CAP -> text.drawString(
                    pen, items.getItemNameWithArticle(popupItem, true, messages),
                )
                OP_ITEM_STR -> text.drawString(
                    pen, items.getItemNameWithArticle(popupItem, false, messages),
                )
                OP_SPACE -> pen.x += operand
                else -> Unit
            }
            instAddr += INSTRUCTION_SIZE
        }
    }

    /**
     * Decimal text of [value] as a 0-terminated byte string, like `NumberToStringAscii` in
     * src/bmlib.c. A minus sign leads negative values. The C writes digits downwards from its
     * highest one, so a value of seven or more digits would write before the buffer; here only
     * the six lowest digits are kept.
     */
    private fun numberToStringAscii(value: Int): ByteArray {
        var number = value
        val out = ArrayList<Byte>()
        if (number == 0) {
            out.add('0'.code.toByte())
        } else {
            if (number < 0) {
                out.add('-'.code.toByte())
                number = -number
            }
            val digits = minOf(number.toString().length, 6)
            val buf = ByteArray(digits)
            for (i in digits - 1 downTo 0) {
                buf[i] = ('0'.code + number % 10).toByte()
                number /= 10
            }
            buf.forEach { out.add(it) }
        }
        out.add(0)
        return out.toByteArray()
    }

    /**
     * Digit count [numberToStringAscii] writes for [number]: no minus sign, at most six
     * digits. It is the return value of `NumberToStringAscii` (`numStart + 1`), which
     * `ParsePopupInstAndGetLen` multiplies by 8 pixels.
     */
    private fun numberToStringAsciiLength(number: Int): Int {
        var magnitude = if (number < 0) -number else number
        var digits = 1
        while (magnitude > 9) {
            magnitude /= 10
            digits++
        }
        return minOf(digits, 6)
    }

    /**
     * The 0-terminated string at ROM address [at], terminator included: the operand of
     * `POPUP_OP_STR`.
     */
    private fun RomView.bytesAt(at: Int): ByteArray {
        var end = at
        while (u8(end) != 0) end++
        return ByteArray(end - at + 1) { u8(at + it).toByte() }
    }

    /**
     * Counts the clock down, or with a negative clock waits for [newKeys] to be nonzero; sets
     * [PopupProc.broken] where the original calls `Proc_Break`. A clock of exactly 0 does nothing,
     * so such a popup never closes. [newKeys] is `gKeyStatusPtr->newKeys`, the buttons pressed
     * this frame.
     *
     * Ported from src/popup.c: PopupProc_WaitForPress
     */
    public fun waitForPress(proc: PopupProc, newKeys: () -> Int) {
        if (proc.clock < 0) {
            if (newKeys() != 0) proc.broken = true
        } else if (proc.clock != 0) {
            proc.clock--
            if (proc.clock == 0) proc.broken = true
        }
    }

    /**
     * Sets the fields `NewPopupCore` assigns after starting the proc (the proc start itself is
     * not modelled): the instruction list at [definition], [clock], [winStyle], the icon's OBJ
     * tile [iconObjTileId] and the icon palette [palBase] + 0x10 (the decomp comments the
     * parameter `pal_base` as `proc->iconPalId - 0x10`). The port including the proc start is
     * [PopupScript.start].
     */
    public fun setupCore(
        proc: PopupProc, definition: Int, clock: Int, winStyle: Int, iconObjTileId: Int,
        palBase: Int,
    ) {
        proc.clock = clock
        proc.pDefinition = definition
        proc.winStyle = winStyle
        proc.iconObjTileId = iconObjTileId
        proc.iconPalId = palBase + 0x10
    }

    /**
     * `NewPopupCore` fields for `NewPopup_Simple` (icon OBJ tile 0x240, palette base 4); the
     * game's popups pass a clock of 0x60 frames and window style 0.
     *
     * Field setup of `NewPopup_Simple`; see [PopupScript.start].
     */
    public fun setupSimple(proc: PopupProc, definition: Int, clock: Int, winStyle: Int) {
        setupCore(proc, definition, clock, winStyle, 0x240, 4)
    }
}
