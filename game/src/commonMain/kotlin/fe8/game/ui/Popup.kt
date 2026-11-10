package fe8.game.ui

import fe8.game.RomView
import fe8.game.bm.ItemInfo
import gbafe.render.BgHardware
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextSystem

/** Popup procs (decomp `src/popup.c`): state of `struct PopupProc` and its small callbacks. */
public object Popup {
    /** ROM address of `ProcScr_Popup`. */
    public const val PROC_SCRIPT: Int = 0x085921C8

    /** ROM address of `ProcScr_PopupUpdateIcon`. */
    public const val UPDATE_ICON_PROC_SCRIPT: Int = 0x08592228

    /** `TEXT_COLOR_SYSTEM_WHITE`. */
    public const val TEXT_COLOR_SYSTEM_WHITE: Int = 0

    private const val INSTRUCTION_SIZE = 8
    private const val DISPCNT_WINDOWS = 0xE000 // win0_on, win1_on, objWin_on
    private const val OP_END = 0x00
    private const val OP_SPACE = 0x01
    private const val OP_ITEM_NAME = 0x02
    private const val OP_ITEM_STR_CAP = 0x03
    private const val OP_ITEM_STR = 0x04
    private const val OP_UNIT_NAME = 0x05
    private const val OP_MSG = 0x06
    private const val OP_STR = 0x07
    private const val OP_COLOR = 0x08
    private const val OP_ITEM_ICON = 0x09
    private const val OP_WTYPE_ICON = 0x0A
    private const val OP_NUM = 0x0B
    private const val OP_SOUND = 0x0C

    /** The `struct PopupProc` fields from offset 0x2C on; integer types as in the struct. */
    public class PopupProc {
        /** `pDefinition` (+0x2C): ROM address of the instruction list. */
        public var pDefinition: Int = 0

        /** `clock` (+0x30): frames left, or negative to wait for a key. */
        public var clock: Int = 0

        /** `xTileParam` (+0x34), s8. */
        public var xTileParam: Int = 0

        /** `yTileParam` (+0x35), s8. */
        public var yTileParam: Int = 0

        /** `winStyle` (+0x36), u8. */
        public var winStyle: Int = 0

        /** `xTileReal` (+0x37), u8. */
        public var xTileReal: Int = 0

        /** `yTileReal` (+0x38), u8. */
        public var yTileReal: Int = 0

        /** `xTileSize` (+0x39), u8. */
        public var xTileSize: Int = 0

        /** `yTileSize` (+0x3A), u8. */
        public var yTileSize: Int = 0

        /** `textColorId` (+0x3B), u8. */
        public var textColorId: Int = 0

        /** `iconId` (+0x3E), u16. */
        public var iconId: Int = 0

        /** `iconObjTileId` (+0x40), u16. */
        public var iconObjTileId: Int = 0

        /** `iconPalId` (+0x42), u8. */
        public var iconPalId: Int = 0

        /** `iconX` (+0x44), u8. */
        public var iconX: Int = 0

        /** `xGfxSize` (+0x46), u16. */
        public var xGfxSize: Int = 0

        /** `soundId` (+0x48), u16. */
        public var soundId: Int = 0

        /** Set when the proc was broken out of its wait loop (`Proc_Break`). */
        public var broken: Boolean = false
    }

    /**
     * Resets the layout, color, icon and sound fields.
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
     * effect, switches the three windows off in `hardware.lcd.dispcnt` and stores the text width
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
     * Dims the BGM when the popup has a sound: [startBgmVolumeChange] stands for
     * `StartBgmVolumeChange(volume, end, time, proc)`, called with 0x100, 0x80, 0x10.
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
     * Plays the popup's sound effect; [playSoundEffect] stands for `PlaySoundEffect`.
     *
     * Transcribed from src/popup.c: PopupProc_PlaySound (not verified: the trace has no record
     * of the sound effect call)
     */
    public fun playSound(proc: PopupProc, playSoundEffect: (songId: Int) -> Unit) {
        if (proc.soundId != 0) playSoundEffect(proc.soundId)
    }

    /**
     * Restores the BGM volume when the popup has a sound: [startBgmVolumeChange] is as for
     * [maybeSetVolume], called with 0x80, 0x100, 0x10.
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
     * u8 opcode, u32 data at +4), sets [PopupProc.soundId], [PopupProc.iconX] and
     * [PopupProc.iconId] and returns the popup text width in pixels. [loadIconPalette] stands for
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
        var len = 0
        var inst = proc.pDefinition
        while (rom.u8(inst) != OP_END) {
            val data = rom.u32(inst + 4)
            when (rom.u8(inst)) {
                OP_SOUND -> proc.soundId = data
                OP_NUM -> len += numberToStringAsciiLength(popupNumber) * 8
                OP_ITEM_ICON -> {
                    proc.iconX = len
                    proc.iconId = items.getItemIconId(popupItem)
                    loadIconPalette(0, proc.iconPalId)
                    len += 0x10
                }
                OP_WTYPE_ICON -> {
                    proc.iconX = len
                    proc.iconId = popupItem + 0x70
                    loadIconPalette(1, proc.iconPalId)
                    len += 0x10
                }
                OP_MSG -> len += text.getStringTextLen(messages.getStringFromIndex(data))
                OP_STR -> len += text.getStringTextLen(rom.bytesAt(data))
                OP_UNIT_NAME ->
                    len += text.getStringTextLen(messages.getStringFromIndex(unitNameTextId))
                OP_ITEM_NAME ->
                    len += text.getStringTextLen(items.getItemName(popupItem, messages))
                OP_ITEM_STR_CAP -> len += text.getStringTextLen(
                    items.getItemNameWithArticle(popupItem, true, messages),
                )
                OP_ITEM_STR -> len += text.getStringTextLen(
                    items.getItemNameWithArticle(popupItem, false, messages),
                )
                OP_SPACE -> len += data
                else -> Unit
            }
            inst += INSTRUCTION_SIZE
        }
        return len
    }

    /**
     * Lays the popup out and draws it: parses the instruction list, draws the frame into
     * [hardware]'s BG0/BG1, draws the text strip into [text]'s font and BG0, and loads the icon
     * graphics into [hardware] when [PopupProc.iconId] is not 0xFFFF. [loadIconPalette],
     * [popupItem], [popupNumber] and [unitNameTextId] are as for [parsePopupInstAndGetLen].
     * `ResetText` is `InitTextFont(&gDefaultFont, VRAM + 0x1000, 0x80, 0)` on [text].
     *
     * Returns the fields `unk_2C`, `unk_30` and `unk_4A` of the `PopupIconUpdateProc` child the
     * original starts (see [iconChildFields]), or null when there is no icon.
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
        val len = parsePopupInstAndGetLen(
            proc, rom, messages, text, popupItem, popupNumber, unitNameTextId, loadIconPalette,
        )
        val tileLen = gfxDrawLayout(proc, len)
        val iconPos = (tileLen * 8 - proc.xGfxSize) shr 1
        UiFrame.drawUiFrame2(
            hardware, rom, proc.xTileReal, proc.yTileReal, tileLen + 2, 4, proc.winStyle,
        )
        val th = Text()
        text.initText(th, tileLen)
        th.colorId = proc.textColorId
        th.x = iconPos
        generatePopupText(
            proc.pDefinition, th, rom, messages, text, popupItem, popupNumber, unitNameTextId,
        )
        if (proc.iconId != 0xFFFF) {
            ItemIcon.loadIconObjectGraphics(hardware, rom, proc.iconId, proc.iconObjTileId)
        }
        text.putText(
            th, hardware.mapBuffer(0), (proc.yTileReal + 1) * 32 + proc.xTileReal + 1,
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
                    val idx = (proc.yTileReal + row) * 32 + proc.xTileReal + col
                    if (idx in buffer.indices) buffer[idx] = 0
                }
            }
        }
    }

    /**
     * Sets the layout fields from the text width [len] (as [parsePopupInstAndGetLen] returns it)
     * and returns the text width in tiles. Helper of [gfxDraw].
     */
    public fun gfxDrawLayout(proc: PopupProc, len: Int): Int {
        proc.xGfxSize = len and 0xFFFF
        var tileLen = (len shl 16) shr 19
        if (len and 7 != 0) tileLen++
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
     * Returns `unk_2C`, `unk_30` and `unk_4A` of the `PopupIconUpdateProc` child that [gfxDraw]
     * starts when [PopupProc.iconId] is not 0xFFFF.
     */
    public fun iconChildFields(proc: PopupProc): IntArray = intArrayOf(
        (proc.xTileReal + 1) * 8 + proc.iconX,
        (proc.yTileReal + 1) * 8,
        (proc.iconObjTileId or ((proc.iconPalId and 0xF) shl 12)) and 0xFFFF,
    )

    /**
     * Draws the popup text described by the instruction list at [definition] into a copy of
     * [th] (C passes `struct Text` by value, so the caller's pen is untouched). [text] and
     * [messages] supply the drawing and the message strings; [popupItem], [popupNumber] and
     * [unitNameTextId] are the popup globals. The `BG_EnableSyncByMask` at the end is a no-op
     * here.
     *
     * Ported from src/popup.c: GeneratePopupText
     */
    public fun generatePopupText(
        definition: Int,
        th: Text,
        rom: RomView,
        messages: MsgStrings,
        text: TextSystem,
        popupItem: Int,
        popupNumber: Int,
        unitNameTextId: Int,
    ) {
        val items = ItemInfo(rom)
        val t = Text()
        t.chrPosition = th.chrPosition
        t.x = th.x
        t.colorId = th.colorId
        t.tileWidth = th.tileWidth
        t.dbEnabled = th.dbEnabled
        t.dbId = th.dbId
        t.isPrinting = th.isPrinting
        var inst = definition
        while (rom.u8(inst) != OP_END) {
            val data = rom.u32(inst + 4)
            when (rom.u8(inst)) {
                OP_NUM -> text.drawString(t, numberToStringAscii(popupNumber))
                OP_WTYPE_ICON, OP_ITEM_ICON -> t.x += 0x10
                OP_COLOR -> t.colorId = data
                OP_MSG -> text.drawString(t, messages.getStringFromIndex(data))
                OP_STR -> text.drawString(t, rom.bytesAt(data))
                OP_UNIT_NAME ->
                    text.drawString(t, messages.getStringFromIndex(unitNameTextId))
                OP_ITEM_NAME -> text.drawString(t, items.getItemName(popupItem, messages))
                OP_ITEM_STR_CAP -> text.drawString(
                    t, items.getItemNameWithArticle(popupItem, true, messages),
                )
                OP_ITEM_STR -> text.drawString(
                    t, items.getItemNameWithArticle(popupItem, false, messages),
                )
                OP_SPACE -> t.x += data
                else -> Unit
            }
            inst += INSTRUCTION_SIZE
        }
    }

    // NumberToStringAscii (src/bmlib.c) as a 0-terminated byte string.
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

    // Digit count NumberToStringAscii (src/bmlib.c) returns: no dash, at most six digits.
    private fun numberToStringAsciiLength(number: Int): Int {
        var n = if (number < 0) -number else number
        var digits = 1
        while (n > 9) {
            n /= 10
            digits++
        }
        return minOf(digits, 6)
    }

    // A 0-terminated string in ROM at [at] (the POPUP_OP_STR operand).
    private fun RomView.bytesAt(at: Int): ByteArray {
        var end = at
        while (u8(end) != 0) end++
        return ByteArray(end - at + 1) { u8(at + it).toByte() }
    }

    /**
     * Counts the clock down, or with a negative clock waits for [newKeys] to be nonzero; sets
     * [PopupProc.broken] where the original calls `Proc_Break`.
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
     * not modelled).
     *
     * Field setup of `NewPopupCore`; the port including the proc start is [PopupScript.start].
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
     * `NewPopupCore` fields for `NewPopup_Simple` (icon tile 0x240, palette base 4).
     *
     * Field setup of `NewPopup_Simple`; see [PopupScript.start].
     */
    public fun setupSimple(proc: PopupProc, definition: Int, clock: Int, winStyle: Int) {
        setupCore(proc, definition, clock, winStyle, 0x240, 4)
    }
}
