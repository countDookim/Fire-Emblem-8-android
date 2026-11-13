package fe8.game.event

import fe8.game.bm.PlaySt
import gbafe.core.flags.FlagStore

/**
 * `struct EventInfo` (eventinfo.h): the cursor and result of a search through a chapter event list
 * (turn events, misc "wait" events and so on). The caller sets [listScript] (and, for the lists
 * that match on a position or a unit pair, [xPos]/[yPos]/[pidA]/[pidB]); the check functions fill
 * in [script] and [flag] for the entry that matched.
 */
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

    /** `xPos`: the active unit's column, set for the misc list; no ported check reads it yet. */
    public var xPos: Int = 0

    /** `yPos`: the active unit's row, set for the misc list; no ported check reads it yet. */
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
    /** `RegisterEventActivation(script, flag)`: records which list flag started [script]. */
    public fun registerEventActivation(script: Int, flag: Int) {}

    /** `CallEvent(script, execType)`, normally `EventEngine.callEvent`. */
    public fun callEvent(script: Int, execType: Int) {}

    /**
 * `RefreshAllies`: after a won chapter, clears every unit's unselectable, rescuing and rescued
 * state and its status, then redraws the map units.
 */
    public fun refreshAllies() {}

    /** `GetBattleMapKind`: 0 story, 1 dungeon, 2 skirmish. */
    public fun getBattleMapKind(): Int = 0

    /** `GetChapterEventDataPointer(chapter)`: ROM address of the `ChapterEventGroup`. */
    public fun chapterEventGroup(chapter: Int): Int =
        error("chapterEventGroup is not wired")

    /**
 * `GetROMChapterStruct(chapter)->goalWindowDataType`: the chapter goal kind (`GOAL_TYPE_*`:
 * 0 seize, 1 defeat all, 2 defense, 3 defeat boss, 4 special).
 */
    public fun goalWindowDataType(chapter: Int): Int = 0

    /**
 * `AreAnyEnemyUnitDead`. Despite its name the decomp function returns 1 while at least one
 * enemy unit is still alive and 0 once none is left, so `AreAnyEnemyUnitDead() == 0` ("all
 * enemies defeated") is `!areAnyEnemyUnitDead()`. The default (true) means an enemy is alive.
 */
    public fun areAnyEnemyUnitDead(): Boolean = true

    /**
     * `CallEndEvent`: runs the chapter's ending scene (the common skirmish ending on skirmish
     * maps), refreshes the allies and sets `EVFLAG_HIDE_BLINKING_ICON`.
     */
    public fun callEndEvent() {}

    /** `ClearActiveEventRegistry`: forgets the script/flag pairs of earlier list events. */
    public fun clearActiveEventRegistry() {}

    /**
 * `RunTutorialEvent(type)`: runs the queued tutorial event if it is of [type] and returns
 * true. NOTE: when none is queued for [type] the decomp returns whether a forecast tutorial
 * event is queued (`CheckTutorialEvent(TUTORIAL_EVT_TYPE_FORECAST)`), so true does not
 * always mean an event started.
 */
    public fun runTutorialEvent(type: Int): Boolean = false

    /** `CheckTutorialEvent(type)`: true if `RunTutorialEvent(type)` would start an event. */
    public fun checkTutorialEvent(type: Int): Boolean = false

    /**
 * `CountAvailableBlueUnits`: player units (ids 1 to 0x3F) that are valid and lack every
 * `US_UNAVAILABLE` state (dead, not deployed, `US_BIT16`).
 */
    public fun countAvailableBlueUnits(): Int = 1

    /**
 * `sub_80837B0`: starts game over: sets `EVFLAG_GAMEOVER`, starts the game over song, turns the
 * BGM config off and creates the game over event.
 */
    public fun startGameOver() {}
}

/**
 * The event list search of `src/eventinfo.c`: [gEventListCmdInfoTable] and the functions that walk
 * and start list entries. A list entry starts with a word holding its command id (low half, an
 * `EVT_LIST_CMD_*`) and the flag that marks it done (high half); the rest depends on the
 * command. Lists end with an `EVT_LIST_CMD_END` entry. Words are read from [memory].
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
    /** `struct EventListCmdInfo`: how to test one kind of list entry, and how long it is. */
    public class CmdInfo(
        /** `func`: the check; returns 1 when the entry matches (and fills in the [EventInfo]). */
        public val func: (EventInfo) -> Int,
        /** `length` in 32-bit words. */
        public val length: Int,
    )

    /**
 * `gEventListCmdInfoTable`, indexed by `EVT_LIST_CMD_*` (0x00..0x10): the check and the entry
 * length in words of each list entry kind. Only END, FLAG, TURN and the two NEVER kinds are
 * ported; the others fail loudly if a list reaches them.
 */
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
     * Starts the found entry: sets its flag and runs its script as a cutscene-type event. If the
     * script won the chapter (`EVFLAG_WIN`) the allies are refreshed and the blinking icon is
     * hidden. The `EVENT_NOSCRIPT` and `EVFLAG_WIN` branches are not verified: the captured turn
     * event has a script and does not set the win flag.
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
     * At a phase change: ends the chapter if the goal is defeat-all/defeat-boss and no enemy is
     * left (chapter 0 skips this check), else runs the queued phase-change tutorial event and the
     * chapter's turn events (`turnBasedEvents`, offset 0 of the event group). Nothing runs on
     * skirmish maps. Returns true if it started events or the end event.
     *
     * Verified on the "nothing found" return (EventInfoRomTest) and the event start (the enemy
     * phase turn event of the Prologue, PhaseChangeDifferentialTest); the end event and the
     * tutorial event branches are not verified: no capture reaches them.
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
     * entry whose flag is clear and whose check passes with a script, else null. The END entry
     * always passes its check without a script, which is what stops the walk at the list end.
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
     * Transcribed from src/eventinfo.c: SearchNextAvailableEvent (not verified: the Prologue's
     *   misc list reaches it, but skipping the step past the entry changes nothing there, as the
     *   started entry's flag is set and the entry is skipped anyway)
     *
     * Steps past the entry [info] points at and continues [searchAvailableEvent].
     */
    public fun searchNextAvailableEvent(info: EventInfo?): EventInfo? {
        if (info == null) return null
        val cmdId = u32(info.listScript) and 0xFFFF
        info.listScript += gEventListCmdInfoTable[cmdId].length * 4
        return searchAvailableEvent(info)
    }

    /**
     * Ported from src/eventinfo.c: ShouldCallEndEvent
     *
     * False on skirmish maps, otherwise whether the chapter is won
     * (`CheckWin` is `CheckFlag(EVFLAG_WIN)`).
     */
    public fun shouldCallEndEvent(): Boolean {
        if (hooks.getBattleMapKind() == KIND_SKIRMISH) return false
        return flags.checkFlag(EVFLAG_WIN)
    }

    /**
     * Ported from src/eventinfo.c: CheckForWaitEvents
     *
     * After a unit action at ([x], [y]) (the decomp reads the active unit's position), true if
     * [runWaitEvents] has something to start: the chapter's misc event list has a matching
     * entry, a post action tutorial event is queued, or a skirmish map has no enemy left, or
     * the game is already over. As a side effect it sets `EVFLAG_DEFEAT_ALL` while no enemy is
     * left and clears it otherwise, as the decomp does.
     */
    public fun checkForWaitEvents(x: Int, y: Int): Boolean {
        if (!hooks.areAnyEnemyUnitDead()) {
            flags.setFlag(EVFLAG_DEFEAT_ALL)
            if (hooks.getBattleMapKind() == KIND_SKIRMISH) return true
        } else {
            flags.clearFlag(EVFLAG_DEFEAT_ALL)
        }
        if (!flags.checkFlag(EVFLAG_GAMEOVER) && hooks.countAvailableBlueUnits() != 0) {
            if (hooks.getBattleMapKind() == KIND_SKIRMISH) return false
            val tutorial = hooks.checkTutorialEvent(TUTORIAL_EVT_TYPE_POSTACTION)
            val info = EventInfo()
            info.listScript = u32(hooks.chapterEventGroup(playSt.chapterIndex) + MISC_EVENTS)
            info.xPos = x
            info.yPos = y
            if (searchAvailableEvent(info) == null) return tutorial
        }
        return true
    }

    /**
     * Ported from src/eventinfo.c: RunWaitEvents
     *
     * After a unit action at ([x], [y]) (the active unit's position), sets or clears
     * `EVFLAG_DEFEAT_ALL`; ends a skirmish map once no enemy is left; starts the game over when
     * `EVFLAG_GAMEOVER` is set or no player unit is available; otherwise runs the post action
     * tutorial event and every matching entry of the chapter's misc event list.
     */
    public fun runWaitEvents(x: Int, y: Int) {
        if (!hooks.areAnyEnemyUnitDead()) {
            flags.setFlag(EVFLAG_DEFEAT_ALL)
            if (hooks.getBattleMapKind() == KIND_SKIRMISH) {
                hooks.callEndEvent()
                return
            }
        } else {
            flags.clearFlag(EVFLAG_DEFEAT_ALL)
        }
        if (flags.checkFlag(EVFLAG_GAMEOVER) || hooks.countAvailableBlueUnits() == 0) {
            hooks.startGameOver()
            return
        }
        if (hooks.getBattleMapKind() == KIND_SKIRMISH) return
        hooks.runTutorialEvent(TUTORIAL_EVT_TYPE_POSTACTION)
        val info = EventInfo()
        info.listScript = u32(hooks.chapterEventGroup(playSt.chapterIndex) + MISC_EVENTS)
        info.xPos = x
        info.yPos = y
        var found = searchAvailableEvent(info)
        if (found == null) return
        hooks.clearActiveEventRegistry()
        while (found != null) {
            startEventFromInfo(info, EV_EXEC_CUTSCENE)
            found = searchNextAvailableEvent(info)
        }
    }

    /**
 * Ported from src/eventinfo.c: EvCheck00_Always
 *
 * The check of `EVT_LIST_CMD_END`, the list terminator: always passes and leaves `script` 0.
 */
    public fun evCheck00Always(info: EventInfo): Int = 1

    /**
     * Ported from src/eventinfo.c: EvCheck01_AFEV
     *
     * The check of `EVT_LIST_CMD_FLAG`: the entry (script in word 1, extra required flag in
     * word 2) matches when the required flag is 0 (none), 100 (not named in the decomp's
     * event-flags.h; meaning unknown) or set.
     *
     * Verified for an entry whose required flag is clear (no match) and one that needs no flag
     * (matches); the entry that needs flag 100 or a set flag is not in a trace.
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

    /**
 * Ported from src/eventinfo.c: EvCheck02_TURN
 *
 * The check of `EVT_LIST_CMD_TURN`: the entry (script in word 1) matches when the chapter turn
 * is within its turn range and the current phase's faction (`FACTION_BLUE` 0x00 player,
 * `FACTION_GREEN` 0x40 ally, `FACTION_RED` 0x80 enemy) equals the entry's.
 */
    public fun evCheck02Turn(info: EventInfo): Int {
        val entry = info.listScript
        // Third word of the entry: first turn, last turn and faction.
        val turnWord = u32(entry + 8)
        val turn = turnWord and 0xFF
        var maxTurn = (turnWord ushr 8) and 0xFF
        val faction = (turnWord ushr 16) and 0xFFFF
        // The range is [turn, maxTurn]: a last turn of 0 means "only [turn]", 0xFF "no end".
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

    /**
     * Transcribed from src/eventinfo.c: EvCheck0C_Never (not verified: not reached by a trace)
     *
     * The check of `EVT_LIST_CMD_NEVER_C`: never matches.
     */
    public fun evCheck0CNever(info: EventInfo): Int = 0

    /**
     * Transcribed from src/eventinfo.c: EvCheck0D_Never (not verified: not reached by a trace)
     *
     * The check of `EVT_LIST_CMD_NEVER_D`: never matches.
     */
    public fun evCheck0DNever(info: EventInfo): Int = 0

    private companion object {
        /** `GetBattleMapKind` results (`BATTLEMAP_KIND_*`): story chapter and skirmish. */
        const val KIND_STORY = 0
        const val KIND_SKIRMISH = 2

        /** `goalWindowDataType` values that end the chapter when no enemy is left. */
        const val GOAL_TYPE_DEFEAT_ALL = 1
        const val GOAL_TYPE_DEFEAT_BOSS = 3

        /** `RunTutorialEvent` type for a phase change. */
        const val TUTORIAL_EVT_TYPE_PHASECHANGE = 0

        /** `EV_EXEC_CUTSCENE`: the exec type listed events run with. */
        const val EV_EXEC_CUTSCENE = 1

        /** `RunTutorialEvent` type for after a unit action. */
        const val TUTORIAL_EVT_TYPE_POSTACTION = 1

        /** `EVFLAG_GAMEOVER` and `EVFLAG_DEFEAT_ALL`. */
        const val EVFLAG_GAMEOVER = 0x65
        const val EVFLAG_DEFEAT_ALL = 6

        /** Offset of `miscBasedEvents` (searched by the wait events) in `ChapterEventGroup`. */
        const val MISC_EVENTS = 0x0C

        /** `EVFLAG_WIN`: set when the chapter is won. */
        const val EVFLAG_WIN = 3

        /** `EVFLAG_HIDE_BLINKING_ICON`. */
        const val EVFLAG_HIDE_BLINKING_ICON = 132
    }
}
