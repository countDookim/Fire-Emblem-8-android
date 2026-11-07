package fe8.game.event

import fe8.game.bm.PlaySt
import gbafe.core.flags.FlagStore

/** `struct EventInfo` (eventinfo.h): the cursor and result of an event list search. */
public class EventInfo {
    /** `listScript`: ROM address of the current event list entry. */
    public var listScript: Int = 0

    /** `script`: address of the found script, 0 for none, [EVENT_NOSCRIPT] for a flag only. */
    public var script: Int = 0

    /** `flag`: the flag the entry sets when it is started. */
    public var flag: Int = 0

    /** `commandId` (unused by the checks ported so far). */
    public var commandId: Int = 0

    /** `givenMoney` (unused by the checks ported so far). */
    public var givenMoney: Int = 0

    /** `givenItem` (unused by the checks ported so far). */
    public var givenItem: Int = 0

    /** `xPos` (unused by the checks ported so far). */
    public var xPos: Int = 0

    /** `yPos` (unused by the checks ported so far). */
    public var yPos: Int = 0

    /** `pidA` (unused by the checks ported so far). */
    public var pidA: Int = 0

    /** `pidB` (unused by the checks ported so far). */
    public var pidB: Int = 0
}

/** `EVENT_NOSCRIPT` (eventinfo.h): a list entry that only sets its flag. */
public const val EVENT_NOSCRIPT: Int = 1

/** Hooks of [EventInfos] for code outside this package; the defaults do nothing. */
public interface EventInfoHooks {
    /** `RegisterEventActivation(script, flag)`. */
    public fun registerEventActivation(script: Int, flag: Int) {}

    /** `CallEvent(script, execType)`, normally `EventEngine.callEvent`. */
    public fun callEvent(script: Int, execType: Int) {}

    /** `RefreshAllies`. */
    public fun refreshAllies() {}

    /** `GetBattleMapKind`: 0 story, 1 dungeon, 2 skirmish. */
    public fun getBattleMapKind(): Int = 0

    /** `GetChapterEventDataPointer(chapter)`: ROM address of the `ChapterEventGroup`. */
    public fun chapterEventGroup(chapter: Int): Int =
        error("chapterEventGroup is not wired")

    /** `GetROMChapterStruct(chapter)->goalWindowDataType`. */
    public fun goalWindowDataType(chapter: Int): Int = 0

    /** `AreAnyEnemyUnitDead() == 0` is `!areAnyEnemyUnitDead()`; the default keeps the phase. */
    public fun areAnyEnemyUnitDead(): Boolean = true

    /** `CallEndEvent`. */
    public fun callEndEvent() {}

    /** `ClearActiveEventRegistry`. */
    public fun clearActiveEventRegistry() {}

    /** `RunTutorialEvent(type)`: true if it started an event. */
    public fun runTutorialEvent(type: Int): Boolean = false
}

/**
 * The event list search of `src/eventinfo.c`: [gEventListCmdInfoTable] and the functions that walk
 * and start list entries. List entries are read as 32-bit words from [memory].
 *
 * @param memory script/list words (ROM).
 * @param flags the event flags.
 * @param playSt supplies `chapterTurnNumber` and `faction`.
 * @property hooks callees outside the event list search (defaults: no-ops).
 */
public class EventInfos(
    private val memory: ScriptMemory,
    private val flags: FlagStore,
    private val playSt: PlaySt,
    public var hooks: EventInfoHooks = object : EventInfoHooks {},
) {
    /** `struct EventListCmdInfo`: check function and entry length in words. */
    public class CmdInfo(
        /** `func`. */
        public val func: (EventInfo) -> Int,
        /** `length` in 32-bit words. */
        public val length: Int,
    )

    /** `gEventListCmdInfoTable`, indexed by `EVT_LIST_CMD_*` (0x00..0x10). */
    public val gEventListCmdInfoTable: List<CmdInfo> = listOf(
        CmdInfo(::evCheck00Always, 1),
        CmdInfo(::evCheck01Afev, 3),
        CmdInfo(::evCheck02Turn, 3),
        CmdInfo(todo("EvCheck03_CHAR"), 4),
        CmdInfo(todo("EvCheck04_CHARASM"), 4),
        CmdInfo(todo("EvCheck05_LOCA"), 3),
        CmdInfo(todo("EvCheck06_VILL"), 3),
        CmdInfo(todo("EvCheck07_CHES"), 3),
        CmdInfo(todo("EvCheck08_DOOR"), 3),
        CmdInfo(todo("EvCheck09_"), 3),
        CmdInfo(todo("EvCheck0A_SHOP"), 3),
        CmdInfo(todo("EvCheck0B_AREA"), 3),
        CmdInfo(::evCheck0CNever, 3),
        CmdInfo(::evCheck0DNever, 3),
        CmdInfo(todo("EvCheck0E_"), 3),
        CmdInfo(todo("EvCheck0F_"), 4),
        CmdInfo(todo("EvCheck10_"), 4),
    )

    // TODO(M6): port the remaining EvCheck functions; until then reaching one fails loudly.
    private fun todo(name: String): (EventInfo) -> Int = { error("$name is not ported yet") }

    private fun u32(address: Int): Int = memory.u16(address) or (memory.u16(address + 2) shl 16)

    /**
     * Transcribed from src/eventinfo.c: StartEventFromInfo (not verified: the trace has no frame
     *   that starts an event through it that the test compares; see EventInfoRomTest)
     */
    public fun startEventFromInfo(info: EventInfo, execType: Int) {
        if (info.script == 0) return
        flags.setFlag(info.flag)
        if (info.script != EVENT_NOSCRIPT) {
            hooks.registerEventActivation(info.script, info.flag)
            hooks.callEvent(info.script, execType)
            if (flags.checkFlag(EVFLAG_WIN)) {
                hooks.refreshAllies()
                flags.setFlag(EVFLAG_HIDE_BLINKING_ICON)
            }
        }
    }

    /**
     * Ported from src/eventinfo.c: RunPhaseSwitchEvents
     *
     * Returns true if it started events or the end event. The traces compared (EventInfoRomTest)
     * only reach the "nothing found" return; the end event and event start paths follow the C.
     */
    public fun runPhaseSwitchEvents(): Boolean {
        if (hooks.getBattleMapKind() == KIND_SKIRMISH) return false
        val chapter = playSt.chapterIndex
        if (chapter != 0) {
            val type = hooks.goalWindowDataType(chapter)
            if ((type == GOAL_TYPE_DEFEAT_ALL || type == GOAL_TYPE_DEFEAT_BOSS) &&
                !hooks.areAnyEnemyUnitDead()
            ) {
                if (hooks.getBattleMapKind() == KIND_STORY) flags.setFlag(EVFLAG_WIN)
                hooks.callEndEvent()
                return true
            }
        }
        val ret = hooks.runTutorialEvent(TUTORIAL_EVT_TYPE_PHASECHANGE)
        val info = EventInfo()
        info.listScript = u32(hooks.chapterEventGroup(playSt.chapterIndex))
        var found = searchAvailableEvent(info)
        if (found != null) {
            hooks.clearActiveEventRegistry()
            while (found != null) {
                startEventFromInfo(info, EV_EXEC_CUTSCENE)
                found = searchNextAvailableEvent(info)
            }
            return true
        }
        return ret
    }

    /**
     * Ported from src/eventinfo.c: SearchAvailableEvent
     *
     * Walks the list from `info.listScript` and returns [info] with `script`/`flag` of the first
     * entry whose flag is clear and whose check passes with a script, else null.
     */
    public fun searchAvailableEvent(info: EventInfo): EventInfo? {
        info.script = 0
        info.flag = 0
        while (true) {
            val cmd = u32(info.listScript)
            val cmdId = cmd and 0xFFFF
            if (!flags.checkFlag(cmd ushr 16)) {
                if (gEventListCmdInfoTable[cmdId].func(info) == 1) break
            }
            info.listScript += gEventListCmdInfoTable[cmdId].length * 4
        }
        return if (info.script != 0) info else null
    }

    /**
     * Transcribed from src/eventinfo.c: SearchNextAvailableEvent (not verified: only hand-decoded
     *   ROM values check it; no trace reaches a second match)
     *
     * Steps past the entry [info] points at and continues [searchAvailableEvent].
     */
    public fun searchNextAvailableEvent(info: EventInfo?): EventInfo? {
        if (info == null) return null
        val cmdId = u32(info.listScript) and 0xFFFF
        info.listScript += gEventListCmdInfoTable[cmdId].length * 4
        return searchAvailableEvent(info)
    }

    /** Ported from src/eventinfo.c: EvCheck00_Always */
    public fun evCheck00Always(info: EventInfo): Int = 1

    /**
     * Transcribed from src/eventinfo.c: EvCheck01_AFEV (not verified: no type-1 entry is reached)
     */
    public fun evCheck01Afev(info: EventInfo): Int {
        val ls = info.listScript
        val flag = u32(ls + 8)
        if (flag == 0 || flag == 100 || flags.checkFlag(flag)) {
            info.script = u32(ls + 4)
            info.flag = u32(ls) ushr 16
            return 1
        }
        return 0
    }

    /** Ported from src/eventinfo.c: EvCheck02_TURN */
    public fun evCheck02Turn(info: EventInfo): Int {
        val ls = info.listScript
        val w = u32(ls + 8)
        val turn = w and 0xFF
        var maxTurn = (w ushr 8) and 0xFF
        val faction = (w ushr 16) and 0xFFFF
        if (maxTurn == 0) {
            maxTurn = turn
        } else if (maxTurn == 0xFF) {
            maxTurn = Int.MAX_VALUE
        }
        if (turn <= playSt.chapterTurnNumber && playSt.chapterTurnNumber <= maxTurn &&
            playSt.faction == faction
        ) {
            info.script = u32(ls + 4)
            info.flag = u32(ls) ushr 16
            return 1
        }
        return 0
    }

    /** Transcribed from src/eventinfo.c: EvCheck0C_Never (not verified: not reached by a trace) */
    public fun evCheck0CNever(info: EventInfo): Int = 0

    /** Transcribed from src/eventinfo.c: EvCheck0D_Never (not verified: not reached by a trace) */
    public fun evCheck0DNever(info: EventInfo): Int = 0

    private companion object {
        const val KIND_STORY = 0
        const val KIND_SKIRMISH = 2
        const val GOAL_TYPE_DEFEAT_ALL = 1
        const val GOAL_TYPE_DEFEAT_BOSS = 3
        const val TUTORIAL_EVT_TYPE_PHASECHANGE = 0
        const val EV_EXEC_CUTSCENE = 1
        const val EVFLAG_WIN = 3
        const val EVFLAG_HIDE_BLINKING_ICON = 132
    }
}
