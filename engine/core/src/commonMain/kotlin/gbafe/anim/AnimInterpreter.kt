package gbafe.anim

/** Reads one 32 bit little-endian script word at a GBA address (ROM or RAM scripts alike). */
public fun interface ScriptReader {
    /** The word at [address]. */
    public fun word(address: Int): Int
}

/** `Anim::state` bits (anime.h `Anim_state`) used by the driver loop. */
private const val ANIM_BIT_HIDDEN = 1 shl 1
private const val ANIM_BIT_FROZEN = 1 shl 3

/** `ANIM_BIT2_*` (anime.h `Anim_state2`): the low 12 bits survive an instruction. */
public const val ANIM_BIT2_COMMAND: Int = 1 shl 12

/** `ANIM_BIT2_FRAME`. */
public const val ANIM_BIT2_FRAME: Int = 1 shl 13

/** `ANIM_BIT2_STOP`. */
public const val ANIM_BIT2_STOP: Int = 1 shl 14

/** Wait command ids that make `AnimInterpret` re-read the same word (`pScrCurrent--`). */
private val WAIT_COMMAND_IDS =
    intArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x13, 0x18, 0x2D, 0x39, 0x52)

/**
 * Ported from src/animedrv.c: AnimInterpret
 *
 * Runs one script instruction of [anim]; script words come from [reader]. Returns true when the
 * pool needs a re-sort (the END arm; never reached by a capture). Only the FRAME, STOP and
 * COMMAND arms are exercised by a capture (BA-i3) and verified; the forced-sprite, pointer
 * instruction, END, LOOP, WAIT and MOVE arms are `TODO()` because no capture reaches them.
 */
public fun animInterpret(anim: Anim, reader: ScriptReader): Boolean {
    val instruction = reader.word(anim.pScrCurrent)
    anim.pScrCurrent += 4

    // ANINS_IS_NOT_FORCESPRITE: bit 31.
    if (instruction >= 0) {
        TODO("AnimInterpret forced sprite arm: no capture reaches it")
    }
    // ANINS_IS_PTRINS: bit 30.
    if (instruction and 0x40000000 != 0) {
        TODO("AnimInterpret pointer instruction arm: no capture reaches it")
    }
    when ((instruction ushr 24) and 0x3F) {
        ANIM_INS_TYPE_STOP -> {
            anim.pScrCurrent -= 4
            anim.timer = 1
            anim.state2 = (anim.state2 and 0xFFF) or ANIM_BIT2_STOP
        }
        ANIM_INS_TYPE_END -> TODO("AnimInterpret END arm: no capture reaches it")
        ANIM_INS_TYPE_LOOP -> TODO("AnimInterpret LOOP arm: no capture reaches it")
        ANIM_INS_TYPE_WAIT -> TODO("AnimInterpret WAIT arm: no capture reaches it")
        ANIM_INS_TYPE_MOVE -> TODO("AnimInterpret MOVE arm: no capture reaches it")
        ANIM_INS_TYPE_COMMAND -> {
            anim.state2 = (anim.state2 and 0xFFF) or ANIM_BIT2_COMMAND
            val id = instruction and 0xFF
            anim.commandQueue[anim.commandQueueSize] = id
            anim.commandQueueSize++
            anim.timer = 1
            if (id in WAIT_COMMAND_IDS) anim.pScrCurrent -= 4
        }
        ANIM_INS_TYPE_FRAME -> {
            anim.timer = instruction and 0xFFFF
            anim.unk13 = (instruction ushr 16) and 0xFF
            anim.pImgSheet = reader.word(anim.pScrCurrent)
            anim.pScrCurrent += 4
            // "???????": the original adds the sprite data pool base to the second word.
            anim.pSpriteData = reader.word(anim.pScrCurrent) + anim.pSpriteDataPool
            anim.pScrCurrent += 4
            anim.state2 = (anim.state2 and 0xFFF) or ANIM_BIT2_FRAME
        }
    }
    return false
}

private const val ANIM_INS_TYPE_STOP = 0
private const val ANIM_INS_TYPE_END = 1
private const val ANIM_INS_TYPE_LOOP = 2
private const val ANIM_INS_TYPE_MOVE = 3
private const val ANIM_INS_TYPE_WAIT = 4
private const val ANIM_INS_TYPE_COMMAND = 5
private const val ANIM_INS_TYPE_FRAME = 6

/**
 * Ported from src/animedrv.c: AnimUpdateAll
 *
 * Steps every enabled, non-frozen anim of the priority list: the timer is decremented and
 * [animInterpret] runs while it is 0. [display] stands in for `AnimDisplayPrivate` (called for
 * every enabled, non-hidden anim) and [sort] for `AnimSort` (called once when an instruction
 * asked for a re-sort); neither is ported, both default to no-ops. Deviation: the original
 * follows `pNext` of a disabled tail entry without a null check; this stops at the tail.
 */
public fun AnimPool.updateAll(
    reader: ScriptReader,
    display: (Anim) -> Unit = {},
    sort: () -> Unit = {},
) {
    var anim = first ?: return
    var needsSort = false
    while (true) {
        var skip = anim.state == 0
        if (!skip) {
            if (anim.state and ANIM_BIT_FROZEN == 0) {
                if (anim.timer == 0 || --anim.timer == 0) {
                    do {
                        if (animInterpret(anim, reader)) needsSort = true
                    } while (anim.timer == 0)
                }
                skip = anim.state == 0
            }
            if (!skip) {
                if (anim.state and ANIM_BIT_HIDDEN == 0) display(anim)
                if (anim.pNext == null) break
            }
        }
        anim = anim.pNext ?: break
    }
    if (needsSort) sort()
}
