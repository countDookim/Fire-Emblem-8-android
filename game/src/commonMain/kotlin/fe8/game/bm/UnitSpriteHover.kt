package fe8.game.bm

/**
 * The unit sprite hover controller of src/bmudisp.c: the hover timer ([hoverTimer],
 * `gMapSpriteSwitchHoverTimer`), resetting it ([resetUnitSpriteHover]), the frame update
 * ([unitSpriteHoverUpdate]) which starts the unit's MU on hover and cleans up on move, and
 * the hover predicate ([isUnitSpriteHoverEnabledAt]).
 */
public class UnitSpriteHover(
    private val bm: BmSt,
    private val map: BmMap,
    private val units: UnitTable,
    /** Callbacks for external MU and sprite visibility actions. */
    public val hooks: Hooks = Hooks(),
) {
    /** Callbacks for external MU and sprite visibility actions. */
    public class Hooks {
        /** `StartMu(unit)`. */
        public var startMu: (unitId: Int) -> Unit = {}

        /** `HideUnitSprite(unit)`. */
        public var hideUnitSprite: (unitId: Int) -> Unit = {}

        /** `EndAllMus()`. */
        public var endAllMus: () -> Unit = {}

        /** `ShowUnitSprite(unit)`. */
        public var showUnitSprite: (unitId: Int) -> Unit = {}
    }

    /** `gMapSpriteSwitchHoverTimer` in EWRAM (0x0203A4D0). */
    public var hoverTimer: Int = 0

    /**
     * Ported from src/bmudisp.c: ResetUnitSpriteHover
     *
     * Resets the unit sprite hover timer (`gMapSpriteSwitchHoverTimer`) to 0.
     */
    public fun resetUnitSpriteHover() {
        hoverTimer = 0
    }

    /**
     * Ported from src/bmudisp.c: UnitSpriteHoverUpdate
     *
     * Advances the hover timer (`gMapSpriteSwitchHoverTimer`) while hovering a selectable player
     * unit, starting its MU and hiding the standing map sprite when the timer reaches 5 frames.
     * When the cursor moves to a different tile, resets the hover timer to 0, ends all active MUs,
     * and shows the standing sprite of the unit at the previous cursor position.
     */
    public fun unitSpriteHoverUpdate() {
        val curX = bm.playerCursorX
        val curY = bm.playerCursorY
        if (isUnitSpriteHoverEnabledAt(curX, curY)) {
            val unitId = map.unit[curY, curX]
            hoverTimer++
            if (hoverTimer == 5) {
                hooks.startMu(unitId)
                hooks.hideUnitSprite(unitId)
                return
            }
        }

        if (bm.cursorPreviousX != curX || bm.cursorPreviousY != curY) {
            hoverTimer = 0
            val prevX = bm.cursorPreviousX
            val prevY = bm.cursorPreviousY
            if (prevX in 0 until map.sizeX && prevY in 0 until map.sizeY) {
                val prevUnitId = map.unit[prevY, prevX]
                if (prevUnitId != 0 && units.isValid(prevUnitId)) {
                    hooks.endAllMus()
                    hooks.showUnitSprite(prevUnitId)
                }
            }
        }
    }

    /**
     * Ported from src/bmudisp.c: IsUnitSpriteHoverEnabledAt
     *
     * True if tile ([x], [y]) has a player unit that is selectable (not unavailable, not asleep,
     * not berserk).
     */
    public fun isUnitSpriteHoverEnabledAt(x: Int, y: Int): Boolean {
        if (x !in 0 until map.sizeX || y !in 0 until map.sizeY) return false
        val unitId = map.unit[y, x]
        if (unitId == 0 || !units.isValid(unitId)) return false
        if ((units.state(unitId) and US_UNSELECTABLE) != 0) return false
        if ((units.index(unitId) and 0xC0) != FACTION_BLUE) return false
        val status = units.statusIndex(unitId)
        return status != UNIT_STATUS_BERSERK && status != UNIT_STATUS_SLEEP
    }

    /** Loads `gMapSpriteSwitchHoverTimer` from [ewram] (the 256 KiB at 0x02000000). */
    public fun loadFrom(ewram: ByteArray) {
        hoverTimer = StructCodec.u32(ewram, HOVER_TIMER_ADDRESS - EWRAM_BASE)
    }

    /** Stores `gMapSpriteSwitchHoverTimer` into [ewram]. */
    public fun storeTo(ewram: ByteArray) {
        StructCodec.put32(ewram, HOVER_TIMER_ADDRESS - EWRAM_BASE, hoverTimer)
    }

    /** Memory layout addresses and constant masks for unit sprite hover. */
    public companion object {
        /** GBA base address of EWRAM (0x02000000). */
        public const val EWRAM_BASE: Int = 0x02000000

        /** GBA address of `gMapSpriteSwitchHoverTimer` in EWRAM. */
        public const val HOVER_TIMER_ADDRESS: Int = 0x0203A4D0

        /** `US_UNSELECTABLE` unit state mask (1 shl 1). */
        public const val US_UNSELECTABLE: Int = 1 shl 1

        /** `FACTION_BLUE` unit index faction bits (0x00). */
        public const val FACTION_BLUE: Int = 0x00

        /** `UNIT_STATUS_SLEEP` status index (2). */
        public const val UNIT_STATUS_SLEEP: Int = 2

        /** `UNIT_STATUS_BERSERK` status index (4). */
        public const val UNIT_STATUS_BERSERK: Int = 4
    }
}
