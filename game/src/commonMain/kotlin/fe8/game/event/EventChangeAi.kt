package fe8.game.event

import fe8.game.bm.UnitTable
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE

/** Game state outside the unit arrays that [EventChangeAi] reaches. */
public interface EventChangeAiHooks {
    /** `gBmMapUnit[y][x]`: the id of the unit standing on the tile, or 0. */
    public fun unitAt(x: Int, y: Int): Int = 0
}

/**
 * The AI change command of `src/eventscr.c`, `Event39_ChangeAiScript` (`EV_CMD_CHANGEAI` 0x39),
 * and the two `src/eventscr3.c` functions it calls, on the unit arrays of [table].
 *
 * @param state the event slots (slot 1 holds the new AI bytes, slot 2 and 0xB the "from slot"
 *   character and tile).
 * @param memory script words (the command arguments).
 * @param table the unit arrays the command changes.
 * @param hooks the map lookup of the "at position" form.
 */
public class EventChangeAi(
    private val state: EventState,
    private val memory: ScriptMemory,
    private val table: UnitTable,
    private val hooks: EventChangeAiHooks = object : EventChangeAiHooks {},
) {
    /** Registers 0x39 on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x39, event39ChangeAiScript)
    }

    /**
     * Ported from src/eventscr.c: Event39_ChangeAiScript
     *
     * Splits slot 1 into the new `ai1` (byte 0), `ai2` (byte 1) and an unused byte and applies
     * them to every unit of the character in the command's argument (CHAI, the character from
     * slot 2 when the argument is negative) or to the unit on a tile (CHAI_AT). Always
     * continues. The CHAI form is verified on the capture's boss turn event; CHAI_AT is not seen
     * (a break there survives). CHAI_AT is the shipped (non `BUGFIX`) form: x is the low and y
     * the high byte of the argument, each taken as `s8`; with a negative coordinate the tile
     * comes from the two halves of slot 0xB. With no unit on the tile the C passes NULL to
     * `ChangeUnitAi` and reads through it; this port does nothing.
     */
    public val event39ChangeAiScript: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        val slot1 = state.slots[1]
        val ai1 = slot1 and 0xFF
        val ai2 = (slot1 shr 8) and 0xFF
        val unused = (slot1 shr 16) and 0xFF
        val arg = memory.u16(cur + 2)
        when (memory.u16(cur) and 0xF) {
            SUB_CHAI -> {
                val pid = if (arg.toShort() < 0) state.slots[2] else arg.toShort().toInt()
                changeAiForCharacter(pid and 0xFF, ai1, ai2, unused)
            }
            SUB_CHAI_AT -> {
                var x = arg.toByte().toInt()
                var y = (arg shr 8).toByte().toInt()
                if (x < 0 || y < 0) {
                    x = state.slots[0xB].toByte().toInt()
                    y = (state.slots[0xB] ushr 16).toByte().toInt()
                }
                val unit = if (x >= 0 && y >= 0) hooks.unitAt(x, y) else 0
                if (unit != 0 && table.unitOffset(unit) >= 0) changeUnitAi(unit, ai1, ai2, unused)
            }
        }
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr3.c: ChangeAiForCharacter
     *
     * Applies [changeUnitAi] to every valid unit with ids 0x41 to 0xBF (the green and red
     * armies) whose character number is [pid]. The loop skips none of the capture's red units.
     */
    public fun changeAiForCharacter(pid: Int, ai1: Int, ai2: Int, unused: Int) {
        for (id in FIRST_AI_UNIT until END_AI_UNITS) {
            if (!table.isValid(id)) continue
            if (table.charId(id) == pid) changeUnitAi(id, ai1, ai2, unused)
        }
    }

    /**
     * Ported from src/eventscr3.c: ChangeUnitAi
     *
     * Unless the unit [id] is hidden or dead: a new [ai1] (not `AI_A_INVALID`) is stored and its
     * script position reset; a new [ai2] (not `AI_B_INVALID`) likewise, and `AI_B_0C` also sets
     * `AI_UNIT_FLAG_3`. Verified with ai1 and ai2 set (the capture's values 0); the hidden or
     * dead skip, the invalid values and the `AI_B_0C` flag are not seen (breaks there survive).
     */
    @Suppress("UNUSED_PARAMETER")
    public fun changeUnitAi(id: Int, ai1: Int, ai2: Int, unused: Int) {
        if (table.state(id) and (US_HIDDEN or US_DEAD) != 0) return
        val o = table.unitOffset(id)
        if (ai1 != AI_A_INVALID) {
            table.bytes[o + OFF_AI1] = ai1.toByte()
            table.setAiAPc(id, 0)
        }
        if (ai2 != AI_B_INVALID) {
            table.bytes[o + OFF_AI2] = ai2.toByte()
            table.setAiBPc(id, 0)
            if (ai2 == AI_B_0C) table.setAiFlags(id, table.aiFlags(id) or AI_UNIT_FLAG_3)
        }
    }

    private companion object {
        /** `EVTSUBCMD_CHAI` and `EVTSUBCMD_CHAI_AT`. */
        const val SUB_CHAI = 0
        const val SUB_CHAI_AT = 1

        /** `FACTION_BLUE + 1` and `FACTION_PURPLE`: the id range of `ChangeAiForCharacter`. */
        const val FIRST_AI_UNIT = 0x41
        const val END_AI_UNITS = 0xC0

        /** `AI_A_INVALID` and `AI_B_INVALID` (include/cp_common.h): one past the last script. */
        const val AI_A_INVALID = 0x15
        const val AI_B_INVALID = 0x13

        /** `AI_B_0C` and `AI_UNIT_FLAG_3`. */
        const val AI_B_0C = 0x0C
        const val AI_UNIT_FLAG_3 = 1 shl 3

        /** `US_HIDDEN` and `US_DEAD`. */
        const val US_HIDDEN = 1 shl 0
        const val US_DEAD = 1 shl 2

        /** Offsets of `ai1` and `ai2` in `struct Unit`. */
        const val OFF_AI1 = 0x42
        const val OFF_AI2 = 0x44
    }
}
