package fe8.game.ui

/** Popup procs (decomp `src/popup.c`): state of `struct PopupProc` and its small callbacks. */
public object Popup {
    /** ROM address of `ProcScr_Popup`. */
    public const val PROC_SCRIPT: Int = 0x085921C8

    /** `TEXT_COLOR_SYSTEM_WHITE`. */
    public const val TEXT_COLOR_SYSTEM_WHITE: Int = 0

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
     * Transcribed from src/popup.c: NewPopupCore
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
     * Transcribed from src/popup.c: NewPopup_Simple
     */
    public fun setupSimple(proc: PopupProc, definition: Int, clock: Int, winStyle: Int) {
        setupCore(proc, definition, clock, winStyle, 0x240, 4)
    }
}
