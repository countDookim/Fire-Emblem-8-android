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
    /** A check function for the entry kind [name] that is not ported: fails when it is reached. */
    private fun todo(name: String): (EventInfo) -> Int = { error("$name is not ported yet") }

    /** The little-endian 32-bit word at [address] (list entries are read as words). */
    private fun u32(address: Int): Int = memory.u16(address) or (memory.u16(address + 2) shl 16)

    /**
     * Ported from src/eventinfo.c: StartEventFromInfo
     *
     * The `EVENT_NOSCRIPT` and `EVFLAG_WIN` branches are (not verified: the captured turn event
     * has a script and does not set the win flag).
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
     * Returns true if it started events or the end event. Verified on the "nothing found" return
     * (EventInfoRomTest) and the event start (the enemy phase turn event of the Prologue,
     * PhaseChangeDifferentialTest); the end event and the tutorial event branches are (not
     * verified: no capture reaches them).
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
        val tutorialStarted = hooks.runTutorialEvent(TUTORIAL_EVT_TYPE_PHASECHANGE)
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
        return tutorialStarted
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
        val entry = info.listScript
        val requiredFlag = u32(entry + 8)
        if (requiredFlag == 0 || requiredFlag == 100 || flags.checkFlag(requiredFlag)) {
            info.script = u32(entry + 4)
            info.flag = u32(entry) ushr 16
            return 1
        }
        return 0
    }

    /** Ported from src/eventinfo.c: EvCheck02_TURN */
    public fun evCheck02Turn(info: EventInfo): Int {
        val entry = info.listScript
        // Third word of the entry: first turn, last turn and faction.
        val turnWord = u32(entry + 8)
        val turn = turnWord and 0xFF
        var maxTurn = (turnWord ushr 8) and 0xFF
        val faction = (turnWord ushr 16) and 0xFFFF
        // A last turn of 0 means "only [turn]", 0xFF means "no end".
        if (maxTurn == 0) {
            maxTurn = turn
        } else if (maxTurn == 0xFF) {
            maxTurn = Int.MAX_VALUE
        }
        if (turn <= playSt.chapterTurnNumber && playSt.chapterTurnNumber <= maxTurn &&
            playSt.faction == faction
        ) {
            info.script = u32(entry + 4)
            info.flag = u32(entry) ushr 16
            return 1
        }
        return 0
    }

    /** Transcribed from src/eventinfo.c: EvCheck0C_Never (not verified: not reached by a trace) */
    public fun evCheck0CNever(info: EventInfo): Int = 0

    /** Transcribed from src/eventinfo.c: EvCheck0D_Never (not verified: not reached by a trace) */
    public fun evCheck0DNever(info: EventInfo): Int = 0

    private companion object {
        /** `GetBattleMapKind` results: story chapter and skirmish. */
        const val KIND_STORY = 0
        const val KIND_SKIRMISH = 2

        /** `goalWindowDataType` values that end the chapter when no enemy is left. */
        const val GOAL_TYPE_DEFEAT_ALL = 1
        const val GOAL_TYPE_DEFEAT_BOSS = 3

        /** `RunTutorialEvent` type for a phase change. */
        const val TUTORIAL_EVT_TYPE_PHASECHANGE = 0

        /** `EV_EXEC_CUTSCENE`: the exec type listed events run with. */
        const val EV_EXEC_CUTSCENE = 1

        /** `EVFLAG_WIN`: set when the chapter is won. */
        const val EVFLAG_WIN = 3

        /** `EVFLAG_HIDE_BLINKING_ICON`. */
        const val EVFLAG_HIDE_BLINKING_ICON = 132
    }
}
