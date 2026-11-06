package gbafe.input

/**
 * The game's per-frame button state: a field-for-field port of `struct KeyStatusBuffer`
 * (`include/hardware.h`, 0x14 bytes; FE8 keeps one instance, `sKeyStatusBuffer`, at EWRAM 0x02024CC0
 * reached via `gKeyStatusPtr`), plus the ignore mask `gKeyStatusIgnoredSt` (IWRAM 0x03000010).
 *
 * All masks are GBA key bits in `KEYINPUT` order (A=0, B=1, Select=2, Start=3, Right=4, Left=5, Up=6,
 * Down=7, R=8, L=9), i.e. the same bits `VanillaButtonMap` uses. Every field keeps the C width: `u8`
 * fields wrap at 0x100 and `u16` fields at 0x10000, so the state stays byte-identical to the original
 * (checked against mGBA RAM by `KeyStatusDifferentialTest`). [writeTo]/[readFrom] convert to and from
 * the original 0x14-byte layout. Allocation-free.
 *
 * Game code should consume `GameAction`s via `InputFrame`; this class exists because original menus
 * and the auto-repeat timing are defined on these exact fields.
 */
public class KeyStatus {
    /** `repeatDelay` (u8): frames a key combination must be held before auto-repeat starts. */
    public var repeatDelay: Int = 0

    /** `repeatInterval` (u8): frames between auto-repeat presses once repeating. */
    public var repeatInterval: Int = 0

    /** `repeatTimer` (u8): counts down while the same keys stay held; reloaded on change or repeat. */
    public var repeatTimer: Int = 0

    /** `heldKeys` (u16): keys down this frame. */
    public var heldKeys: Int = 0

    /** `repeatedKeys` (u16): [newKeys] plus, on auto-repeat frames, all held keys. Menus scroll on this. */
    public var repeatedKeys: Int = 0

    /** `newKeys` (u16): keys that went down this frame. */
    public var newKeys: Int = 0

    /** `prevKeys` (u16): [heldKeys] of the previous update. */
    public var prevKeys: Int = 0

    /** `LastPressState` (u16): the full held set at the last frame where any key went down. */
    public var lastPressState: Int = 0

    /** `ABLRPressed` (bool16): on the frame all keys are released, the released A/B/L/R set if it equals
     * [lastPressState] (a clean press-and-release of only those buttons); 0 otherwise. */
    public var ablrPressed: Int = 0

    /** `newKeys2` (u16): toggled-and-masked held keys (`newKeys2 = (newKeys2 ^ held) & held`), i.e.
     * keys held now that were not set last update; the game uses it as a second "new press" view. */
    public var newKeys2: Int = 0

    /** `TimeSinceStartSelect` (u16): updates since a button other than Start/Select was down
     * (saturates at 0xFFFF). */
    public var timeSinceStartSelect: Int = 0

    /** `gKeyStatusIgnoredSt` (u16): keys masked out by [update] unless A+B+Start+Select are all held. */
    public var ignoreMask: Int = 0

    /**
     * `SetKeyStatus_IgnoreMask`: sets [ignoreMask].
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
        val k = keys and 0xFFFF // the C parameter is s16, stored into u16 fields
        prevKeys = heldKeys
        heldKeys = k
        newKeys = heldKeys and prevKeys.inv() and 0xFFFF
        repeatedKeys = newKeys
        if (newKeys != 0) lastPressState = k
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
        if (k and NON_START_SELECT != 0) {
            timeSinceStartSelect = 0
        } else if (timeSinceStartSelect < 0xFFFF) {
            timeSinceStartSelect++
        }
    }

    /**
     * Per-frame entry point: takes the raw pressed keys (the complement of `REG_KEYINPUT`, as a
     * front end reads them), applies [ignoreMask] unless the A+B+Start+Select reset combo is held,
     * then calls [updateWith].
     *
     * Ported from src/hardware.c: UpdateKeyStatus
     */
    public fun update(pressedKeys: Int) {
        var keys = pressedKeys and KEYS_MASK
        if (keys and RESET_COMBO != RESET_COMBO) keys = keys and ignoreMask.inv()
        updateWith(keys)
    }

    /**
     * Restores the boot defaults: 12-frame repeat delay, 4-frame repeat interval, no keys, timers and
     * ignore mask cleared. Like the original it leaves [repeatedKeys], [lastPressState], [ablrPressed]
     * and [newKeys2] untouched.
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

    /** Writes the 0x14-byte `struct KeyStatusBuffer` image (little endian, byte 3 = padding 0) to [out]
     * at [offset]. [ignoreMask] is not part of the struct. */
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

    private fun put16(out: ByteArray, at: Int, v: Int) {
        out[at] = v.toByte()
        out[at + 1] = (v ushr 8).toByte()
    }

    /** Loads all struct fields from a 0x14-byte `struct KeyStatusBuffer` image in [src] at [offset]
     * (e.g. a RAM dump). [ignoreMask] is not part of the struct and is left unchanged. */
    public fun readFrom(src: ByteArray, offset: Int = 0) {
        fun u16(i: Int) = (src[offset + i].toInt() and 0xFF) or ((src[offset + i + 1].toInt() and 0xFF) shl 8)
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

    /** Key-bit constants and the defaults from `ResetKeyStatus`. */
    public companion object {
        /** Size of `struct KeyStatusBuffer` in bytes. */
        public const val STRUCT_SIZE: Int = 0x14

        /** `START_BUTTON`: the Start key bit (bit 3) of the key masks. */
        public const val START_BUTTON: Int = 0x8

        /** `KEYS_MASK`: the ten GBA key bits. */
        public const val KEYS_MASK: Int = 0x3FF

        /** `ResetKeyStatus` repeat delay (frames). */
        public const val DEFAULT_REPEAT_DELAY: Int = 12

        /** `ResetKeyStatus` repeat interval (frames). */
        public const val DEFAULT_REPEAT_INTERVAL: Int = 4

        /** A + B + Select + Start: when all are held the ignore mask does not apply. */
        public const val RESET_COMBO: Int = 0x00F

        /** L | R | B | A, the buttons `ABLRPressed` reports. */
        public const val ABLR: Int = 0x303

        /** A | B | D-pad | R | L: any button other than Start and Select. */
        public const val NON_START_SELECT: Int = 0x3F3
    }
}
