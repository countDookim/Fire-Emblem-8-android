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
     * Word [wordIndex] of the memory following `gEventSlotQueue[0]`, so queue overflow lands where it
     * does in C: 30 is the counter, 31 the padding, 32..47 the active queue. Beyond that the
     * access is outside the block and reads 0.
     */
    private fun wordAt(wordIndex: Int): Int = when {
        wordIndex < 0 -> 0
        wordIndex < SLOT_QUEUE_WORDS -> slotQueue[wordIndex]
        wordIndex == SLOT_QUEUE_WORDS -> slotCounter
        wordIndex == SLOT_QUEUE_WORDS + 1 -> padding
        wordIndex < OVERFLOW_LIMIT -> activeQueue[wordIndex - SLOT_QUEUE_WORDS - 2]
        else -> 0
    }

    /** Writes a queue word, including the adjacent globals reached by overflow. */
    private fun setWordAt(wordIndex: Int, value: Int) {
        when {
            wordIndex < 0 -> Unit
            wordIndex < SLOT_QUEUE_WORDS -> slotQueue[wordIndex] = value
            wordIndex == SLOT_QUEUE_WORDS -> slotCounter = value
            wordIndex == SLOT_QUEUE_WORDS + 1 -> padding = value
            wordIndex < OVERFLOW_LIMIT -> activeQueue[wordIndex - SLOT_QUEUE_WORDS - 2] = value
            else -> Unit
        }
    }

    /**
     * Ported from src/event.c: SlotQueuePush
     *
     * Writes [value] at `gEventSlotQueue[slots[0xD]]` and increments slot 0xD. There is no bounds
     * check in C; an index past 29 overwrites the following globals (see [wordAt]) and an index
     * outside the block is dropped.
     */
    public fun slotQueuePush(value: Int) {
        setWordAt(slots[QUEUE_SLOT], value)
        slots[QUEUE_SLOT]++
    }

    /**
     * Ported from src/event.c: SlotQueuePop
     *
     * Returns `gEventSlotQueue[0]`, decrements slot 0xD and shifts the remaining entries down. In C
     * a pop with slot 0xD == 0 wraps it to 0xFFFFFFFF and then loops (compared unsigned)
     * effectively forever; here the shift is bounded to the block.
     */
    public fun slotQueuePop(): Int {
        val result = slotQueue[0]
        slots[QUEUE_SLOT]--
        val queueLenUnsigned = slots[QUEUE_SLOT] xor Int.MIN_VALUE
        var i = 0
        while (i < OVERFLOW_LIMIT && (i xor Int.MIN_VALUE) < queueLenUnsigned) {
            setWordAt(i, wordAt(i + 1))
            i++
        }
        return result
    }

    /**
     * Transcribed from src/event.c: SetEventSlotCounter (not verified: the only reference value
     * is 0, so a no-op survives)
     */
    public fun setEventSlotCounter(value: Int) {
        slotCounter = value
    }

    /**
     * Transcribed from src/event.c: GetEventSlotCounter (not verified: no test checks it against a
     *   trace yet).
     */
    public fun getEventSlotCounter(): Int = slotCounter

    /**
     * Ported from src/event.c: SetEventSlotC
     *
     * Stores [value] in slot 0xC.
     */
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

    /** Decodes one little-endian word from a saved event-global block. */
    private fun readWord(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    /** Encodes one little-endian word into a saved event-global block. */
    private fun writeWord(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
        bytes[offset + 2] = (value shr 16).toByte()
        bytes[offset + 3] = (value shr 24).toByte()
    }

    /** Loads an array of words from the event-global block. */
    private fun readWords(bytes: ByteArray, offset: Int, destination: IntArray) {
        for (i in destination.indices) destination[i] = readWord(bytes, offset + i * 4)
    }

    /** Stores an array of words in the event-global block. */
    private fun writeWords(bytes: ByteArray, offset: Int, source: IntArray) {
        for (i in source.indices) writeWord(bytes, offset + i * 4, source[i])
    }

    /** Block layout: addresses, sizes and byte offsets. */
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
