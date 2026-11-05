package fe8.game.event

/**
 * The event engine's IWRAM globals as one contiguous block, laid out as the FE8U linker places
 * them (symbols from the decomp map):
 *
 * | symbol             | address    | size  | C type                        |
 * |--------------------|------------|-------|-------------------------------|
 * | gEventCallQueue    | 0x03000438 | 0x80  | `struct EnqueuedEventCall[16]`|
 * | gEventSlots        | 0x030004B8 | 0x38  | `u32[EVENT_SLOT_COUNT = 14]`  |
 * | gEventSlotQueue    | 0x030004F0 | 0x78  | `u32[30]`                     |
 * | gEventSlotCounter  | 0x03000568 | 4     | `u32`                         |
 * | (alignment)        | 0x0300056C | 4     | padding                       |
 * | gEventActiveQueue  | 0x03000570 | 0x40  | `struct Struct03000570[8]`    |
 *
 * The block ends at 0x030005B0 (`gGenericProc`). Call/active queue entries are kept as raw
 * little-endian words (pointer, then `execType | isUsed << 8` for the call queue) so that
 * [store] after [load] reproduces the bytes exactly.
 */
public class EventState {
    /** `gEventSlots`: slot 0 null, 1-10 variables, 0xB position, 0xC check, 0xD queue pointer. */
    public val slots: IntArray = IntArray(SLOT_COUNT)

    /** Raw words of `gEventCallQueue`: 16 entries of (events pointer, execType/isUsed word). */
    public val callQueue: IntArray = IntArray(CALL_QUEUE_WORDS)

    /** `gEventSlotQueue`: 30 words, indexed by slot 0xD. */
    public val slotQueue: IntArray = IntArray(SLOT_QUEUE_WORDS)

    /** `gEventSlotCounter`. */
    public var slotCounter: Int = 0

    /** Alignment word between [slotCounter] and [activeQueue], preserved for round-trips. */
    public var padding: Int = 0

    /** Raw words of `gEventActiveQueue`: 8 entries of (evt1, evt2) pointers. */
    public val activeQueue: IntArray = IntArray(ACTIVE_QUEUE_WORDS)

    /**
     * Word [i] of the memory following `gEventSlotQueue[0]`, so queue overflow lands where it
     * does in C: 30 is the counter, 31 the padding, 32..47 the active queue. Beyond that the
     * access is outside the block and reads 0.
     */
    private fun wordAt(i: Int): Int = when {
        i < 0 -> 0
        i < SLOT_QUEUE_WORDS -> slotQueue[i]
        i == SLOT_QUEUE_WORDS -> slotCounter
        i == SLOT_QUEUE_WORDS + 1 -> padding
        i < OVERFLOW_LIMIT -> activeQueue[i - SLOT_QUEUE_WORDS - 2]
        else -> 0
    }

    private fun setWordAt(i: Int, value: Int) {
        when {
            i < 0 -> Unit
            i < SLOT_QUEUE_WORDS -> slotQueue[i] = value
            i == SLOT_QUEUE_WORDS -> slotCounter = value
            i == SLOT_QUEUE_WORDS + 1 -> padding = value
            i < OVERFLOW_LIMIT -> activeQueue[i - SLOT_QUEUE_WORDS - 2] = value
            else -> Unit
        }
    }

    /**
     * Ported from src/event.c: SlotQueuePush. Writes [value] at `gEventSlotQueue[slots[0xD]]` and
     * increments slot 0xD. There is no bounds check in C; an index past 29 overwrites the
     * following globals (see [wordAt]) and an index outside the block is dropped.
     */
    public fun slotQueuePush(value: Int) {
        setWordAt(slots[QUEUE_SLOT], value)
        slots[QUEUE_SLOT]++
    }

    /**
     * Ported from src/event.c: SlotQueuePop. Returns `gEventSlotQueue[0]`, decrements slot 0xD and
     * shifts the remaining entries down. In C a pop with slot 0xD == 0 wraps it to 0xFFFFFFFF and
     * then loops (compared unsigned) effectively forever; here the shift is bounded to the block.
     */
    public fun slotQueuePop(): Int {
        val result = slotQueue[0]
        slots[QUEUE_SLOT]--
        val n = slots[QUEUE_SLOT] xor Int.MIN_VALUE
        var i = 0
        while (i < OVERFLOW_LIMIT && (i xor Int.MIN_VALUE) < n) {
            setWordAt(i, wordAt(i + 1))
            i++
        }
        return result
    }

    /** Ported from src/event.c: SetEventSlotCounter. */
    public fun setEventSlotCounter(value: Int) {
        slotCounter = value
    }

    /** Ported from src/event.c: GetEventSlotCounter. */
    public fun getEventSlotCounter(): Int = slotCounter

    /** Ported from src/event.c: SetEventSlotC. Stores [value] in slot 0xC. */
    public fun setEventSlotC(value: Int) {
        slots[0xC] = value
    }

    /** Reads the block from [mem] at [offset] (little-endian, [BLOCK_SIZE] bytes). */
    public fun load(mem: ByteArray, offset: Int) {
        readWords(mem, offset + CALL_QUEUE_OFFSET, callQueue)
        readWords(mem, offset + SLOTS_OFFSET, slots)
        readWords(mem, offset + SLOT_QUEUE_OFFSET, slotQueue)
        slotCounter = readWord(mem, offset + COUNTER_OFFSET)
        padding = readWord(mem, offset + COUNTER_OFFSET + 4)
        readWords(mem, offset + ACTIVE_QUEUE_OFFSET, activeQueue)
    }

    /** Writes the block into [mem] at [offset]; the inverse of [load]. */
    public fun store(mem: ByteArray, offset: Int) {
        writeWords(mem, offset + CALL_QUEUE_OFFSET, callQueue)
        writeWords(mem, offset + SLOTS_OFFSET, slots)
        writeWords(mem, offset + SLOT_QUEUE_OFFSET, slotQueue)
        writeWord(mem, offset + COUNTER_OFFSET, slotCounter)
        writeWord(mem, offset + COUNTER_OFFSET + 4, padding)
        writeWords(mem, offset + ACTIVE_QUEUE_OFFSET, activeQueue)
    }

    private fun readWord(m: ByteArray, o: Int): Int =
        (m[o].toInt() and 0xFF) or ((m[o + 1].toInt() and 0xFF) shl 8) or
            ((m[o + 2].toInt() and 0xFF) shl 16) or ((m[o + 3].toInt() and 0xFF) shl 24)

    private fun writeWord(m: ByteArray, o: Int, v: Int) {
        m[o] = v.toByte()
        m[o + 1] = (v shr 8).toByte()
        m[o + 2] = (v shr 16).toByte()
        m[o + 3] = (v shr 24).toByte()
    }

    private fun readWords(m: ByteArray, o: Int, dst: IntArray) {
        for (i in dst.indices) dst[i] = readWord(m, o + i * 4)
    }

    private fun writeWords(m: ByteArray, o: Int, src: IntArray) {
        for (i in src.indices) writeWord(m, o + i * 4, src[i])
    }

    public companion object {
        /** `EVENT_SLOT_COUNT`. */
        public const val SLOT_COUNT: Int = 0xE

        /** Slot index used as the slot-queue pointer ("QP"). */
        public const val QUEUE_SLOT: Int = 0xD

        /** Words in `gEventSlotQueue` (0x78 bytes). */
        public const val SLOT_QUEUE_WORDS: Int = 30

        /** Words in `gEventCallQueue` (16 entries of 8 bytes). */
        public const val CALL_QUEUE_WORDS: Int = 32

        /** Words in `gEventActiveQueue` (8 entries of 8 bytes). */
        public const val ACTIVE_QUEUE_WORDS: Int = 16

        /** GBA bus address of `gEventCallQueue`, the first byte of the block. */
        public const val BASE_ADDRESS: Int = 0x03000438

        /** Block size in bytes (to `gGenericProc` at 0x030005B0). */
        public const val BLOCK_SIZE: Int = 0x178

        /** Byte offset of `gEventCallQueue` (0x03000438). */
        public const val CALL_QUEUE_OFFSET: Int = 0x00

        /** Byte offset of `gEventSlots` (0x030004B8). */
        public const val SLOTS_OFFSET: Int = 0x80

        /** Byte offset of `gEventSlotQueue` (0x030004F0). */
        public const val SLOT_QUEUE_OFFSET: Int = 0xB8

        /** Byte offset of `gEventSlotCounter` (0x03000568); 4 padding bytes follow. */
        public const val COUNTER_OFFSET: Int = 0x130

        /** Byte offset of `gEventActiveQueue` (0x03000570). */
        public const val ACTIVE_QUEUE_OFFSET: Int = 0x138

        private const val OVERFLOW_LIMIT: Int = SLOT_QUEUE_WORDS + 2 + ACTIVE_QUEUE_WORDS
    }
}
