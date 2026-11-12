package gbafe.render

/**
 * An HBlank interrupt handler (`void (*)(void)` in the decomp): runs once per scanline and may
 * rewrite the I/O registers in [io]. The decomp handlers read `REG_VCOUNT`; here it is passed.
 */
public fun interface HBlankHandler {
    /** Handles the HBlank after line [vcount] (`REG_VCOUNT`, the line just drawn) of the frame. */
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
     * Transcribed from src/hardware.c: ExecBothHBlankHandlers (not verified: no test checks it
     *   against a trace yet)
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
     * Transcribed from src/hardware.c: SetSecondaryHBlankHandler (not verified: no
     * reference dump has a secondary handler set)
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
        val prim = primary
        val sec = secondary
        if (prim == null && sec == null) {
            lcd.dispstat = lcd.dispstat and DISPSTAT_HBLANK_IRQ.inv()
            irqEnabled = false
            return
        }
        lcd.dispstat = lcd.dispstat or DISPSTAT_HBLANK_IRQ
        active = if (sec == null) prim else if (prim == null) sec else both
        irqEnabled = true
    }

    private companion object {
        /** `DISPSTAT_HBLANK_INTR`: DISPSTAT bit 4, HBlank interrupt enabled. */
        const val DISPSTAT_HBLANK_IRQ = 0x10
    }
}
