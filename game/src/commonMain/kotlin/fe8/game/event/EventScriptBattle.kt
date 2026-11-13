package fe8.game.event

import fe8.game.bm.BattleGenerate
import fe8.game.bm.UnitTable
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_ERROR
import fe8.game.event.EventConst.EV_STATE_FADEDIN
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.repeat

/**
 * The game-side effects the script battle command (`src/eventscr.c`) reaches: unit lookup, the
 * game lock the deamon waits on, the battle animation and the map refreshes after the fight.
 */
public interface EventScriptBattleHost {
    /**
     * `GetUnitStructFromEventParameter`: the unit id of event character parameter [pid], or -1
     * for NULL.
     */
    public fun getUnitStructFromEventParameter(pid: Int): Int

    /**
     * `GetGameLock`: the counter of game locks. A fight animation holds a lock; the deamon waits
     * for it to return to the value saved at the start.
     */
    public fun gameLock(): Int

    /**
     * The animation part of `StartEventBattle` (`StartScriptBattleAnim` and friends), run after
     * the battle is generated. [scripted] is true when the command waits for the battle to end,
     * [useMapAnims] is the `useMapAnims` argument.
     */
    public fun startScriptBattleAnim(scripted: Boolean, useMapAnims: Boolean) {}

    /** `AiRefreshMap`: rebuilds the AI's movement/range maps after the fight. */
    public fun aiRefreshMap() {}

    /** `RefreshEntityBmMaps`: rebuilds the unit maps (a unit may have died in the fight). */
    public fun refreshEntityBmMaps() {}

    /** `RefreshUnitSprites`: redraws the map sprites of the units. */
    public fun refreshUnitSprites() {}
}

/**
 * The script battle command of `src/eventscr.c` (0x3F, `EV_CMD_SCRIPT_BATTLE`: a fight between
 * two units played in a scene; sub-commands FIGHT, FIGHT_MAP (map animation) and FIGHT_SCRIPT
 * (only set the scripted hits for the next fight)), its `ProcScr_ScriptBattleDeamon` and the hit
 * queue it reads. `Fe8Boot` registers it (the host there leaves the animation start and the
 * AI/sprite refreshes at their no-op defaults). Left out: the item override (`weaponId != 0`,
 * an error here) and everything [EventScriptBattleHost] stands in for.
 *
 * @param procs the proc engine the deamon runs on.
 * @param state the event slots (`gEventSlots[0xD]` and the slot queue hold the scripted hits).
 * @param memory script words (the command arguments).
 * @param table the unit arrays (units are looked up by id).
 * @param battle the battle generation (`StartEventBattle` and the hit queue).
 * @param host the game-side effects the command stands in for.
 */
public class EventScriptBattle(
    private val procs: ProcEngine,
    private val state: EventState,
    private val memory: ScriptMemory,
    private val table: UnitTable,
    private val battle: BattleGenerate,
    private val host: EventScriptBattleHost,
) {
    /**
     * The fields of `struct ScriptedBattleProc` past the header: the event proc to mark again
     * (`evtproc`) and the game lock value at the start (`lock`).
     */
    private class DeamonState(var evtProc: Proc? = null, var lock: Int = 0)

    /** The deamon's per-frame function: [scriptBattleDeamon] (`PROC_REPEAT`). */
    private val deamonFn = ProcFunc { scriptBattleDeamon(it) }

    /**
     * `ProcScr_ScriptBattleDeamon` (src/eventscr.c:3644): the blocking proc that holds the event
     * until the fight has ended.
     */
    public val deamonScript: ProcScript = ProcScript(
        "ProcScr_ScriptBattleDeamon",
        listOf(repeat(deamonFn), end()),
        { DeamonState() },
    )

    /** Registers 0x3F on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x3F, event3FScriptBattle)
    }

    /**
     * Ported from src/eventscr.c: GenerateScriptBattleHitFormEventQueue (the hit building itself
     *   is [BattleGenerate.generateScriptBattleHitFormEventQueue])
     *
     * The scripted hits (side, hp change, attributes) the script queued in the slot queue
     * (`gEventSlotQueue`): up to `gEventSlots[0xD]` entries, ending at an entry whose first byte
     * is 0xFF. The queue words are split into bytes (little-endian) for the hit builder.
     */
    public fun generateScriptBattleHits(): IntArray {
        val q = state.slotQueue
        val bytes = ByteArray(4 * q.size)
        for (i in q.indices) {
            for (k in 0 until 4) bytes[4 * i + k] = (q[i] ushr (8 * k)).toByte()
        }
        return battle.generateScriptBattleHitFormEventQueue(state.slots[0xD], bytes, 0)
    }

    /**
     * Ported from src/eventscr.c: ScriptBattleDeamon
     *
     * Once the game lock is back at the value saved when the battle started: ends the event
     * battle ([BattleGenerate.eventBattleReloadBmStatus]), marks the event proc as
     * `PROC_MARK_EVENT` and breaks the deamon.
     */
    internal fun scriptBattleDeamon(proc: Proc) {
        val s = proc.state as DeamonState
        if (s.lock == host.gameLock()) {
            battle.eventBattleReloadBmStatus(
                table, host::aiRefreshMap, host::refreshEntityBmMaps, host::refreshUnitSprites,
            )
            s.evtProc?.let { procs.setMark(it, EventEngine.PROC_MARK_EVENT) }
            procs.breakProc(proc)
        }
    }

    /**
     * Ported from src/eventscr.c: Event3F_ScriptBattle
     *
     * Arguments: attacker and defender character parameters (`EVT_CMD_ARGV[0]`, `[1]`; a negative
     * one becomes -2, `CHAR_EVT_POSITION_AT_SLOTB`, the unit at the position in slot 0xB), then
     * the item override (low byte) and the ballista flag (high byte). Sub-command 0 / 1
     * (FIGHT / FIGHT_MAP) starts an event battle between the two units with the hits of the
     * slot queue (an empty queue means no scripted hits: the fight is generated normally), and
     * yields; FIGHT_MAP is the map-animation form. Unless skipping or faded in (then the fight
     * is not "scripted": nothing waits for it) it first starts the blocking deamon above and
     * marks the event proc `PROC_MARK_EVENT_ANIM` (the deamon sets it back to
     * `PROC_MARK_EVENT`). Sub-command 2 (FIGHT_SCRIPT) only sets the scripted hits and
     * continues.
     *
     * NOTE: a NULL unit stops with an error here; the decomp would pass NULL on to
     * `StartEventBattle` (src/eventscr.c:3677-3695). A different sub-command is an error as in C.
     */
    public val event3FScriptBattle: EventHandler = EventHandler { proc ->
        val cur = proc.pEventCurrent
        val subcmd = memory.u16(cur) and 0xF
        var charIdA = memory.u16(cur + 2).toShort().toInt()
        var charIdB = memory.u16(cur + 4).toShort().toInt()
        val arg2 = memory.u16(cur + 6)
        val weaponId = arg2 and 0xFF
        val isBallista = (arg2 ushr 8) != 0
        val hits = if (state.slots[0xD] == 0) null else generateScriptBattleHits()
        when (subcmd) {
            SUBCMD_FIGHT, SUBCMD_FIGHT_MAP -> {
                if (charIdA < 0) charIdA = -2
                if (charIdB < 0) charIdB = -2
                val a = table.unitOffset(host.getUnitStructFromEventParameter(charIdA))
                val b = table.unitOffset(host.getUnitStructFromEventParameter(charIdB))
                if (a < 0 || b < 0) return@EventHandler EVC_ERROR
                check(weaponId == 0) { "StartEventBattle item override is not ported" }
                val scripted = proc.evStateBits and (EV_STATE_SKIPPING or EV_STATE_FADEDIN) == 0
                val parent = proc.proc
                if (scripted && parent != null) {
                    procs.startBlocking(deamonScript, parent)?.let {
                        val s = it.state as DeamonState
                        s.evtProc = parent
                        s.lock = host.gameLock()
                    }
                    procs.setMark(parent, PROC_MARK_EVENT_ANIM)
                }
                battle.startEventBattle(table.bytes, a, b, isBallista, hits)
                host.startScriptBattleAnim(scripted, subcmd != 0)
                EVC_ADVANCE_YIELD
            }
            SUBCMD_FIGHT_SCRIPT -> {
                battle.setScriptedBattle(hits)
                EVC_ADVANCE_CONTINUE
            }
            else -> EVC_ERROR
        }
    }

    private companion object {
        /** `EVSUBCMD_FIGHT`. */
        const val SUBCMD_FIGHT = 0

        /** `EVSUBCMD_FIGHT_MAP`. */
        const val SUBCMD_FIGHT_MAP = 1

        /** `EVSUBCMD_FIGHT_SCRIPT`. */
        const val SUBCMD_FIGHT_SCRIPT = 2

        /** `PROC_MARK_EVENT_ANIM`: the mark of an event proc waiting for a battle animation. */
        const val PROC_MARK_EVENT_ANIM = 7
    }
}
