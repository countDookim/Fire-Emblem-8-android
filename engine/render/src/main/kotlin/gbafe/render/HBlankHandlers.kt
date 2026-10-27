package gbafe.render

/** An HBlank interrupt handler: runs once per scanline [vcount] and may rewrite the I/O registers in [io]. */
public fun interface HBlankHandler {
    /** Handles the HBlank before line [vcount]. */
    public fun onHBlank(vcount: Int, io: ByteArray)
}

/**
 * The games' two HBlank handler slots (`sHBlankHandler1` / `sHBlankHandler2`, src/hardware.c) and the HBlank
 * interrupt state they drive: the IRQ handler in slot 1 of `gIRQHandlers` ([active]), the `REG_IE` HBlank bit
 * ([irqEnabled]) and DISPSTAT's HBlank IRQ enable bit (bit 4) in [lcd].
 */
public class HBlankHandlers(
    /** `gLCDControlBuffer`, whose `dispstat` gets the HBlank IRQ enable bit. */
    public val lcd: LcdControl,
) {
    /** `sHBlankHandler1`. */
    public var primary: HBlankHandler? = null
        private set

    /** `sHBlankHandler2`. */
    public var secondary: HBlankHandler? = null
        private set

    /** `gIRQHandlers[1]`: the handler the HBlank interrupt calls ([primary], [secondary] or [both]). */
    public var active: HBlankHandler? = null
        private set

    /** `REG_IE & INTR_FLAG_HBLANK`. */
    public var irqEnabled: Boolean = false
        private set

    /**
     * `ExecBothHBlankHandlers`: [primary] then [secondary], each if set.
     *
     * Ported from src/hardware.c: ExecBothHBlankHandlers
     */
    public val both: HBlankHandler = HBlankHandler { vcount, io ->
        primary?.onHBlank(vcount, io)
        secondary?.onHBlank(vcount, io)
    }

    /**
     * `SetPrimaryHBlankHandler`.
     *
     * Ported from src/hardware.c: SetPrimaryHBlankHandler
     */
    public fun setPrimary(handler: HBlankHandler?) {
        primary = handler
        update()
    }

    /**
     * `SetSecondaryHBlankHandler`.
     *
     * Ported from src/hardware.c: SetSecondaryHBlankHandler
     */
    public fun setSecondary(handler: HBlankHandler?) {
        secondary = handler
        update()
    }

    /**
     * `UpdateHBlankHandlerState`: with no handler, clears the DISPSTAT and `REG_IE` HBlank bits (the IRQ slot
     * keeps its old handler); otherwise sets both and installs the one handler, or [both].
     *
     * Ported from src/hardware.c: UpdateHBlankHandlerState
     */
    public fun update() {
        val p = primary
        val s = secondary
        if (p == null && s == null) {
            lcd.dispstat = lcd.dispstat and DISPSTAT_HBLANK_IRQ.inv()
            irqEnabled = false
            return
        }
        lcd.dispstat = lcd.dispstat or DISPSTAT_HBLANK_IRQ
        active = if (s == null) p else if (p == null) s else both
        irqEnabled = true
    }

    private companion object {
        const val DISPSTAT_HBLANK_IRQ = 0x10
    }
}
