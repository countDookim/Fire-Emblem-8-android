package gbafe.input

/**
 * The game's per-frame button state: a field-for-field port of `struct KeyStatusBuffer`
 * (`include/hardware.h`, 0x14 bytes; the game keeps one instance, `sKeyStatusBuffer` in
 * `src/hardware.c`, reached via `gKeyStatusPtr`; in FE8U it is at EWRAM 0x02024CC0, where the
 * differential tests read it), plus the ignore mask `gKeyStatusIgnoredSt` (a separate global).
 *
 * All masks are GBA key bits in `KEYINPUT` order (A=0, B=1, Select=2, Start=3, Right=4, Left=5,
 * Up=6, Down=7, R=8, L=9), i.e. the same bits `VanillaButtonMap` uses. Every field keeps the C width:
 * `u8` fields wrap at 0x100 and `u16` fields at 0x10000, so the state stays byte-identical to the
 * original (checked against mGBA RAM by `KeyStatusDifferentialTest`). [writeTo]/[readFrom]
 * convert to and from the original 0x14-byte layout. Allocation-free.
 *
 * Game code should consume `GameAction`s via `InputFrame`; this class exists because original menus
 * and the auto-repeat timing are defined on these exact fields.
 */
public class KeyStatus {
    /**
     * `repeatDelay` (u8): "initial delay before generating auto-repeat presses", in frames;
     * reloaded into [repeatTimer] whenever the held set changes.
     */
    public var repeatDelay: Int = 0

    /** `repeatInterval` (u8): frames between auto-repeat presses once repeating. */
    public var repeatInterval: Int = 0

    /**
     * `repeatTimer` (u8): counts down while the same keys stay held; reloaded on change or repeat.
     */
    public var repeatTimer: Int = 0

    /** `heldKeys` (u16): keys down this frame. */
    public var heldKeys: Int = 0

    /**
     * `repeatedKeys` (u16): [newKeys] plus, on auto-repeat frames, all held keys. Menus scroll on
     * this.
     */
    public var repeatedKeys: Int = 0

    /** `newKeys` (u16): keys that went down this frame. */
    public var newKeys: Int = 0

    /** `prevKeys` (u16): [heldKeys] of the previous update. */
    public var prevKeys: Int = 0

    /**
     * `LastPressState` (u16): the full held set at the last frame where any key went down
     * (undocumented in the decomp; used only to compute [ablrPressed]).
     */
    public var lastPressState: Int = 0

    /**
     * `ABLRPressed` (bool16, "1 for Release (A B L R Only), 0 Otherwise" in the decomp header): on
     * the frame all keys are released, the previous held set ([prevKeys]) if [lastPressState] is
     * non-zero and equals its A/B/L/R part (a press-and-release of only those buttons); 0
     * otherwise. (Despite the bool16 type the value is the key mask, not 0/1.)
     */
    public var ablrPressed: Int = 0

    /**
     * `newKeys2` (u16): `newKeys2 = (newKeys2 ^ held) & held`, i.e. held keys that were not in
     * `newKeys2` last update, so a key held for several frames alternates in and out of it. The
     * decomp has no comment; it is read for map-cursor movement while B is held
     * (`HandlePlayerCursorMovement`) and for L+D-pad in the unit list screen.
     */
    public var newKeys2: Int = 0

    /**
     * `TimeSinceStartSelect` (u16): updates since a button other than Start/Select was last
     * down; 0 while one is held (saturates at 0xFFFF).
     */
    public var timeSinceStartSelect: Int = 0

    /**
     * `gKeyStatusIgnoredSt`: keys masked out of the raw input by [update] unless A+B+Start+Select
     * are all held (the soft-reset combo always gets through).
     */
    public var ignoreMask: Int = 0

    /**
     * Makes the keys in [mask] invisible to [update] until changed or [reset] (for example the
     * trade screen, `src/bmtrade.c`, masks A/Start/Up/Down while it runs).
     *
     * Ported from src/hardware.c: SetKeyStatus_IgnoreMask
     */
    public fun applyIgnoreMask(mask: Int) {
        ignoreMask = mask
    }

    /**
     * Folds this frame's held [keys] into the buffer: new presses, auto-repeat, A/B/L/R release
     * detection and the Start/Select idle counter.
     *
     * Ported from src/hardware.c: _UpdateKeyStatus
     */
    public fun updateWith(keys: Int) {
        // The C parameter is s16, stored into u16 fields.
        val heldNow = keys and 0xFFFF
        prevKeys = heldKeys
        heldKeys = heldNow
        newKeys = heldKeys and prevKeys.inv() and 0xFFFF
        repeatedKeys = newKeys
        if (newKeys != 0) lastPressState = heldNow
        ablrPressed = 0
        if (heldKeys == 0) {
            if (lastPressState != 0 && lastPressState == (prevKeys and ABLR)) ablrPressed = prevKeys
        }
        if (heldKeys != 0 && heldKeys == prevKeys) {
            repeatTimer = (repeatTimer - 1) and 0xFF
            if (repeatTimer == 0) {
                repeatedKeys = heldKeys
                repeatTimer = repeatInterval
            }
        } else {
            repeatTimer = repeatDelay
        }
        newKeys2 = (newKeys2 xor heldKeys) and heldKeys
        if (heldNow and NON_START_SELECT != 0) {
            timeSinceStartSelect = 0
        } else if (timeSinceStartSelect < 0xFFFF) {
            timeSinceStartSelect++
        }
    }

    /**
     * Per-frame entry point (`UpdateKeyStatus` reads `~REG_KEYINPUT` itself): takes the raw
     * pressed keys (the complement of `REG_KEYINPUT`, as a front end reads them), applies
     * [ignoreMask] unless the A+B+Start+Select reset combo is held, then calls [updateWith].
     *
     * Ported from src/hardware.c: UpdateKeyStatus
     */
    public fun update(pressedKeys: Int) {
        var keys = pressedKeys and KEYS_MASK
        if (keys and RESET_COMBO != RESET_COMBO) keys = keys and ignoreMask.inv()
        updateWith(keys)
    }

    /**
     * Restores the boot defaults (called when the game starts): 12-frame repeat delay, 4-frame
     * repeat interval, no keys, timers and ignore mask cleared. Like the original it leaves
     * [repeatedKeys], [lastPressState], [ablrPressed] and [newKeys2] untouched.
     *
     * Ported from src/hardware.c: ResetKeyStatus
     */
    public fun reset() {
        repeatDelay = DEFAULT_REPEAT_DELAY
        repeatInterval = DEFAULT_REPEAT_INTERVAL
        prevKeys = 0
        heldKeys = 0
        newKeys = 0
        repeatTimer = 0
        timeSinceStartSelect = 0
        ignoreMask = 0
    }

    /**
     * Writes the 0x14-byte `struct KeyStatusBuffer` image (little endian, byte 3 = padding 0) to
     * [out] at [offset]. [ignoreMask] is not part of the struct.
     */
    public fun writeTo(out: ByteArray, offset: Int = 0) {
        out[offset] = repeatDelay.toByte()
        out[offset + 1] = repeatInterval.toByte()
        out[offset + 2] = repeatTimer.toByte()
        out[offset + 3] = 0
        put16(out, offset + 4, heldKeys)
        put16(out, offset + 6, repeatedKeys)
        put16(out, offset + 8, newKeys)
        put16(out, offset + 10, prevKeys)
        put16(out, offset + 12, lastPressState)
        put16(out, offset + 14, ablrPressed)
        put16(out, offset + 16, newKeys2)
        put16(out, offset + 18, timeSinceStartSelect)
    }

    /** Stores the low 16 bits of [value] into [out] at [byteOffset], low byte first. */
    private fun put16(out: ByteArray, byteOffset: Int, value: Int) {
        out[byteOffset] = value.toByte()
        out[byteOffset + 1] = (value ushr 8).toByte()
    }

    /**
     * Loads all struct fields from a 0x14-byte `struct KeyStatusBuffer` image in [src] at [offset]
     * (e.g. a RAM dump). [ignoreMask] is not part of the struct and is left unchanged.
     */
    public fun readFrom(src: ByteArray, offset: Int = 0) {
        // Reads the little-endian u16 at [fieldOffset] bytes into the struct image.
        fun u16(fieldOffset: Int): Int {
            val low = src[offset + fieldOffset].toInt() and 0xFF
            val high = src[offset + fieldOffset + 1].toInt() and 0xFF
            return low or (high shl 8)
        }
        repeatDelay = src[offset].toInt() and 0xFF
        repeatInterval = src[offset + 1].toInt() and 0xFF
        repeatTimer = src[offset + 2].toInt() and 0xFF
        heldKeys = u16(4)
        repeatedKeys = u16(6)
        newKeys = u16(8)
        prevKeys = u16(10)
        lastPressState = u16(12)
        ablrPressed = u16(14)
        newKeys2 = u16(16)
        timeSinceStartSelect = u16(18)
    }

    /** Key-bit masks (`include/gba/io_reg.h`) and the defaults that `ResetKeyStatus` installs. */
    public companion object {
        /** Size of `struct KeyStatusBuffer` in bytes. */
        public const val STRUCT_SIZE: Int = 0x14

        /** `START_BUTTON` (`include/gba/io_reg.h`): the Start key bit (bit 3) of the key masks. */
        public const val START_BUTTON: Int = 0x8

        /** `KEYS_MASK`: the ten GBA key bits (A, B, Select, Start, D-pad, R, L). */
        public const val KEYS_MASK: Int = 0x3FF

        /** `ResetKeyStatus` repeat delay (frames). */
        public const val DEFAULT_REPEAT_DELAY: Int = 12

        /** `ResetKeyStatus` repeat interval (frames). */
        public const val DEFAULT_REPEAT_INTERVAL: Int = 4

        /** A + B + Select + Start: when all are held the ignore mask does not apply. */
        public const val RESET_COMBO: Int = 0x00F

        /**
         * L | R | B | A, the only buttons `ABLRPressed` reports (the mask used in
         * `_UpdateKeyStatus`).
         */
        public const val ABLR: Int = 0x303

        /** A | B | D-pad | R | L: any button other than Start and Select. */
        public const val NON_START_SELECT: Int = 0x3F3
    }
}
