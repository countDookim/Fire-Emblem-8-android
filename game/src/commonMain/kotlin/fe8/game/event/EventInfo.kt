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

/**
 * `struct BattleTalkExtEnt` (eventinfo.h): one entry in `gBattleTalkList`.
 *
 * @property pidA first character id (`CHAR_EVT_PLAYER_LEADER` for player leader, 0 for any).
 * @property pidB second character id (0 for any).
 * @property chapter chapter index (0xFF for any chapter, 0xFE for triangle attack).
 * @property flag flag id set when this quote is triggered.
 * @property msg message index of the battle quote.
 * @property event event script address to run instead of [msg] when [msg] is 0.
 */
public class BattleTalkExtEnt(
    public val pidA: Int,
    public val pidB: Int,
    public val chapter: Int,
    public val flag: Int,
    public val msg: Int,
    public val event: Int,
)

/**
 * `struct DefeatTalkEnt` (eventinfo.h): one entry in `gDefeatTalkList`.
 *
 * @property pid character id defeated.
 * @property route route / chapter mode index (0xFF for any).
 * @property chapter chapter index (0xFF for any).
 * @property flag flag id set when this defeat quote is triggered.
 * @property msg message index of the defeat quote.
 * @property event event script address to run instead of [msg] when [msg] is 0.
 */
public class DefeatTalkEnt(
    public val pid: Int,
    public val route: Int,
    public val chapter: Int,
    public val flag: Int,
    public val msg: Int,
    public val event: Int,
)

/** `gBattleTalkList` ROM address (0x089EC6BC). */
public const val BATTLE_TALK_LIST_ADDRESS: Int = 0x089EC6BC

/** `gDefeatTalkList` ROM address (0x089ECD4C). */
public const val DEFEAT_TALK_LIST_ADDRESS: Int = 0x089ECD4C

/** `EVENT_NOSCRIPT` (eventinfo.h): a list entry that only sets its flag. */
public const val EVENT_NOSCRIPT: Int = 1

/** Hooks of [EventInfos] for code outside this package; the defaults do nothing. */
public interface EventInfoHooks {
    /** `CallEvent(script, execType)`, normally `EventEngine.callEvent`. */
    public fun callEvent(script: Int, execType: Int) {}

    /** `CallBattleQuoteEventInBattle(msg)`. */
    public fun callBattleQuoteEventInBattle(msg: Int) {}

    /** `EventEngine_CreateBattle(event)`. */
    public fun createBattle(event: Int) {}

    /** `gActionData.unitActionType`: 2 for `UNIT_ACTION_COMBAT`. */
    public fun unitActionType(): Int = 2

    /** `BattleIsTriangleAttack`: true if a triangle attack is occurring in battle. */
    public fun battleIsTriangleAttack(): Boolean = false

    /** `GetROMChapterStruct(chapter)->protectCharacterIndex`. */
    public fun protectCharacterIndex(chapter: Int): Int = 0

    /** `UNIT_FACTION(GetUnitFromCharId(pid))`: unit faction (0 blue, 0x40 green, 0x80 red). */
    public fun unitFaction(pid: Int): Int = 0x80

    /** `StartBgm(song, NULL)`. */
    public fun startBgm(song: Int) {}

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

    /** `gActiveEventRegistry`: the scripts started from the chapter's event lists. */
    public val registry: ActiveEventRegistry = ActiveEventRegistry()

    /**
     * Ported from src/eventinfo.c: ClearActiveEventRegistry
     *
     * Zeroes [registry] (scripts, flags and count).
     */
    public fun clearActiveEventRegistry() {
        registry.clear()
    }

    /**
     * Ported from src/eventinfo.c: RegisterEventActivation
     *
     * Appends [script] and its list [flag] to [registry]. Not seen: the registry already holding
     * ten entries (the C writes past the arrays).
     */
    public fun registerEventActivation(script: Int, flag: Int) {
        registry.register(script, flag)
    }

    /**
     * Transcribed from src/eventinfo.c: CheckTutorialEvent (not verified: not reached by a
     *   capture; its caller is CheckBattleForecastTutorialEvent, EP-35)
     *
     * True when a tutorial event is queued (`tutorial_counter` is not 0) for [type]
     * (`tutorial_exec_type`).
     */
    public fun checkTutorialEvent(type: Int): Boolean =
        playSt.tutorialCounter != 0 && playSt.tutorialExecType == type

    /**
     * Ported from src/eventinfo.c: RunTutorialEvent
     *
     * If a tutorial event is queued for [type], runs entry `tutorial_counter - 1` of the chapter's
     * `tutorialEvents` list as a cutscene event, clears the queue and returns true; else false.
     * Verified on the nothing-queued path of the boss turn event's start (f2964): no
     * event is called and the queue is unchanged. The queued branch is not seen in any capture
     * (a break there survives).
     */
    public fun runTutorialEvent(type: Int): Boolean {
        if (playSt.tutorialCounter != 0 && playSt.tutorialExecType == type) {
            val counter = playSt.tutorialCounter
            val list = u32(hooks.chapterEventGroup(playSt.chapterIndex) + TUTORIAL_EVENTS)
            hooks.callEvent(u32(list + 4 * (counter - 1)), EV_EXEC_CUTSCENE)
            playSt.tutorialCounter = 0
            playSt.tutorialExecType = 0
            return true
        }
        return false
    }

    /**
     * Transcribed from src/eventinfo.c: CheckForCharacterEvents (not verified: no captured call finds an event or a queued tutorial, so the result equals a check that finds nothing; the f4130..4240 test only shows that reading a wrong list would start one)
     *
     * True when the chapter's `characterBasedEvents` list has an entry for the character pair
     * ([pidA], [pidB]); false on skirmish maps.
     */
    public fun checkForCharacterEvents(pidA: Int, pidB: Int): Boolean {
        if (hooks.getBattleMapKind() == KIND_SKIRMISH) return false
        val info = EventInfo()
        info.listScript = u32(hooks.chapterEventGroup(playSt.chapterIndex) + CHARACTER_EVENTS)
        info.pidA = pidA
        info.pidB = pidB
        return searchAvailableEvent(info) != null
    }

    /**
     * Transcribed from src/eventinfo.c: GetAvailableTileEventCommand (not verified: no captured call finds an event or a queued tutorial, so the result equals a check that finds nothing; the f4130..4240 test only shows that reading a wrong list would start one)
     *
     * The `commandId` (`TILE_COMMAND_*`) of the entry of the chapter's `locationBasedEvents` list
     * that matches the tile ([x], [y]); 0 for none and on skirmish maps.
     */
    public fun getAvailableTileEventCommand(x: Int, y: Int): Int {
        val info = EventInfo()
        info.listScript = u32(hooks.chapterEventGroup(playSt.chapterIndex) + LOCATION_EVENTS)
        info.xPos = x
        info.yPos = y
        if (searchAvailableEvent(info) != null && hooks.getBattleMapKind() != KIND_SKIRMISH) {
            return info.commandId
        }
        return 0
    }

    /**
     * Transcribed from src/eventinfo.c: StartDestSelectedEvent (not verified: no captured call finds an event or a queued tutorial, so the result equals a check that finds nothing; the f4130..4240 test only shows that reading a wrong list would start one)
     *
     * After the player picked a destination: runs the queued `DESTSELECTED` tutorial event and
     * the first matching entry of the chapter's `specialEventsWhenDestSelected` list (true when
     * that one started); else true when the tutorial event ran. False on skirmish maps.
     */
    public fun startDestSelectedEvent(): Boolean =
        startSpecialEvent(TUTORIAL_EVT_TYPE_DESTSELECTED, DEST_SELECTED_EVENTS)

    /**
     * Transcribed from src/eventinfo.c: StartAfterUnitMovedEvent (not verified: no captured call finds an event or a queued tutorial, so the result equals a check that finds nothing; the f4130..4240 test only shows that reading a wrong list would start one)
     *
     * After a unit moved: as [startDestSelectedEvent] for the `AFTERMOVE` tutorial event and the
     * chapter's `specialEventsAfterUnitMoved` list.
     */
    public fun startAfterUnitMovedEvent(): Boolean =
        startSpecialEvent(TUTORIAL_EVT_TYPE_AFTERMOVE, AFTER_UNIT_MOVED_EVENTS)

    /** The shared body of [startDestSelectedEvent] and [startAfterUnitMovedEvent]. */
    private fun startSpecialEvent(tutorialType: Int, listOffset: Int): Boolean {
        if (hooks.getBattleMapKind() == KIND_SKIRMISH) return false
        val tutorialStarted = runTutorialEvent(tutorialType)
        val info = EventInfo()
        info.listScript = u32(hooks.chapterEventGroup(playSt.chapterIndex) + listOffset)
        if (searchAvailableEvent(info) != null) {
            clearActiveEventRegistry()
            startEventFromInfo(info, EV_EXEC_CUTSCENE)
            return true
        }
        return tutorialStarted
    }

    /**
     * Transcribed from src/eventinfo.c: CheckBattleForecastTutorialEvent (not verified: no captured call finds an event or a queued tutorial, so the result equals a check that finds nothing; the f4130..4240 test only shows that reading a wrong list would start one)
     *
     * True when the `FORECAST` tutorial event is queued ([checkTutorialEvent]); false on skirmish
     * maps.
     */
    public fun checkBattleForecastTutorialEvent(): Boolean {
        if (hooks.getBattleMapKind() == KIND_SKIRMISH) return false
        return checkTutorialEvent(TUTORIAL_EVT_TYPE_FORECAST)
    }

    /**
     * Transcribed from src/eventinfo.c: sub_80832D4 (not verified: returns the constant 0; its
     *   caller `sub_8015434` is written without the call, see its KDoc)
     */
    public fun sub80832D4(): Int = 0

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
            registerEventActivation(info.script, info.flag)
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
        val tutorialStarted = runTutorialEvent(TUTORIAL_EVT_TYPE_PHASECHANGE)
        val info = EventInfo()
        info.listScript = u32(hooks.chapterEventGroup(playSt.chapterIndex))
        var found = searchAvailableEvent(info)
        if (found != null) {
            clearActiveEventRegistry()
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
            val tutorial = checkTutorialEvent(TUTORIAL_EVT_TYPE_POSTACTION)
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
        runTutorialEvent(TUTORIAL_EVT_TYPE_POSTACTION)
        val info = EventInfo()
        info.listScript = u32(hooks.chapterEventGroup(playSt.chapterIndex) + MISC_EVENTS)
        info.xPos = x
        info.yPos = y
        var found = searchAvailableEvent(info)
        if (found == null) return
        clearActiveEventRegistry()
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

    /**
     * Returns true if event flag [triggerId] is set, false otherwise.
     *
     * Ported from src/event.c: GetEventTriggerState
     */
    public fun getEventTriggerState(triggerId: Int): Boolean = flags.checkFlag(triggerId)

    /**
     * Searches `gBattleTalkList` for a battle quote entry matching [pidA] and [pidB] in the
     * current chapter, whose flag is not yet set.
     *
     * Ported from src/eventinfo.c: GetBattleQuoteEntry
     */
    public fun getBattleQuoteEntry(pidA: Int, pidB: Int): BattleTalkExtEnt? {
        var addr = BATTLE_TALK_LIST_ADDRESS
        while (true) {
            val entryPidA = memory.u16(addr)
            if (entryPidA == 0xFFFF) break
            val entryPidB = memory.u16(addr + 2)
            val chapter = memory.u16(addr + 4)
            val flag = memory.u16(addr + 6)
            val msg = memory.u16(addr + 8)
            val event = u32(addr + 12)
            addr += 16

            if (chapter != 0xFF && chapter != playSt.chapterIndex) {
                if (chapter != 0xFE || !hooks.battleIsTriangleAttack()) {
                    continue
                }
            }

            if (getEventTriggerState(flag)) {
                continue
            }

            if (entryPidA != 0) {
                if (entryPidB == 0) {
                    if (pidA == entryPidA) {
                        return BattleTalkExtEnt(entryPidA, entryPidB, chapter, flag, msg, event)
                    }
                    continue
                }
            } else {
                if (entryPidB == 0) {
                    continue
                }
                if (pidB == entryPidB) {
                    return BattleTalkExtEnt(entryPidA, entryPidB, chapter, flag, msg, event)
                }
                continue
            }

            if (pidA == entryPidA && pidB == entryPidB) {
                return BattleTalkExtEnt(entryPidA, entryPidB, chapter, flag, msg, event)
            }

            if (pidB == entryPidA && pidA == entryPidB) {
                return BattleTalkExtEnt(entryPidA, entryPidB, chapter, flag, msg, event)
            }
        }
        return null
    }

    /**
     * Checks whether a battle quote should be shown for combatants [pidA] and [pidB], starts the
     * quote or event on proc tree 3 if found, and sets its event flag.
     *
     * Ported from src/eventinfo.c: CallBattleQuoteEventsIfAny
     */
    public fun callBattleQuoteEventsIfAny(pidA: Int, pidB: Int) {
        if (hooks.getBattleMapKind() == KIND_SKIRMISH) return
        if (hooks.unitActionType() != UNIT_ACTION_COMBAT) return
        val ent = getBattleQuoteEntry(pidA, pidB)
            ?: getBattleQuoteEntry(pidA, 0)
            ?: getBattleQuoteEntry(0, pidB)
            ?: return

        if (ent.msg != 0) {
            hooks.callBattleQuoteEventInBattle(ent.msg)
        } else if (ent.event != 0) {
            hooks.createBattle(ent.event)
        }
        flags.setFlag(ent.flag)
    }

    /**
     * Checks if a battle quote should be called for combatants [pidA] and [pidB].
     *
     * Transcribed from src/eventinfo.c: ShouldCallBattleQuote (not verified: no capture checks it)
     */
    public fun shouldCallBattleQuote(pidA: Int, pidB: Int): Boolean {
        if (hooks.getBattleMapKind() == KIND_SKIRMISH) return false
        if (hooks.unitActionType() != UNIT_ACTION_COMBAT) return false
        if (getBattleQuoteEntry(pidA, pidB) != null) return true
        if (getBattleQuoteEntry(pidA, 0) != null) return true
        if (getBattleQuoteEntry(0, pidB) != null) return true
        return false
    }

    /**
     * Sets the defeat event flag [flag] for character [pid], unless on a non-story map where
     * [pid] is the protected character and [flag] is 0x65.
     *
     * Ported from src/eventinfo.c: SetPidDefeatedFlag
     */
    public fun setPidDefeatedFlag(pid: Int, flag: Int) {
        if (hooks.getBattleMapKind() == KIND_STORY ||
            pid != hooks.protectCharacterIndex(playSt.chapterIndex) ||
            flag != EVFLAG_GAMEOVER
        ) {
            flags.setFlag(flag)
        }
    }

    /**
     * Searches `gDefeatTalkList` for a defeat quote entry matching [pid] in the current route
     * and chapter, whose flag is not yet set.
     *
     * Ported from src/eventinfo.c: GetDefeatTalkEntry
     */
    public fun getDefeatTalkEntry(pid: Int): DefeatTalkEnt? {
        var addr = DEFEAT_TALK_LIST_ADDRESS
        while (true) {
            val entryPid = memory.u16(addr)
            if (entryPid == 0xFFFF) break
            val word = memory.u16(addr + 2)
            val route = word and 0xFF
            val chapter = (word ushr 8) and 0xFF
            val flag = memory.u16(addr + 4)
            val msg = memory.u16(addr + 6)
            val event = u32(addr + 8)
            addr += 12

            if (route != 0xFF && route != playSt.chapterModeIndex) {
                continue
            }

            if (chapter != 0xFF && chapter != playSt.chapterIndex) {
                continue
            }

            if (getEventTriggerState(flag)) {
                continue
            }

            if (pid != entryPid) {
                continue
            }

            return DefeatTalkEnt(entryPid, route, chapter, flag, msg, event)
        }
        return null
    }

    /**
     * Checks if character [pid] has a defeat talk entry. If an entry exists but has no message
     * or event, sets its defeat flag immediately and returns false; returns true if a quote or
     * event should be displayed.
     *
     * Ported from src/eventinfo.c: CheckBattleDefeatTalk
     */
    public fun checkBattleDefeatTalk(pid: Int): Boolean {
        val ent = getDefeatTalkEntry(pid) ?: return false
        if (ent.msg == 0 && ent.event == 0) {
            setPidDefeatedFlag(pid, ent.flag)
            return false
        }
        return true
    }

    /**
     * Displays the defeat dialogue or runs the defeat event for character [pid], plays death BGM
     * if applicable, and sets the defeat event flag.
     *
     * Ported from src/eventinfo.c: DisplayDefeatTalkForPid
     */
    public fun displayDefeatTalkForPid(pid: Int) {
        val ent = getDefeatTalkEntry(pid) ?: return
        if (ent.route == 1 && ent.flag == EVFLAG_GAMEOVER) {
            hooks.startBgm(SONG_GAME_OVER)
            playSt.disableBgm = 1
        } else if (hooks.unitFaction(pid) == FACTION_BLUE) {
            hooks.startBgm(SONG_IN_SORROWS_SHROUD)
        }
        if (ent.msg != 0) {
            hooks.callBattleQuoteEventInBattle(ent.msg)
        } else if (ent.event != 0) {
            hooks.createBattle(ent.event)
        }
        setPidDefeatedFlag(pid, ent.flag)
    }

    private companion object {
        /** `UNIT_ACTION_COMBAT`. */
        const val UNIT_ACTION_COMBAT = 2

        /** `SONG_GAME_OVER` and `SONG_IN_SORROWS_SHROUD`. */
        const val SONG_GAME_OVER = 0x3E
        const val SONG_IN_SORROWS_SHROUD = 0x3F

        /** `FACTION_BLUE`. */
        const val FACTION_BLUE = 0x00
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

        /** `RunTutorialEvent` types for a chosen destination, after a move and the forecast. */
        const val TUTORIAL_EVT_TYPE_DESTSELECTED = 3
        const val TUTORIAL_EVT_TYPE_AFTERMOVE = 4
        const val TUTORIAL_EVT_TYPE_FORECAST = 5

        /** Offsets of `characterBasedEvents`, `locationBasedEvents` in `ChapterEventGroup`. */
        const val CHARACTER_EVENTS = 0x04
        const val LOCATION_EVENTS = 0x08

        /** Offsets of `specialEventsWhenDestSelected` and `specialEventsAfterUnitMoved`. */
        const val DEST_SELECTED_EVENTS = 0x14
        const val AFTER_UNIT_MOVED_EVENTS = 0x18

        /** `RunTutorialEvent` type for after a unit action. */
        const val TUTORIAL_EVT_TYPE_POSTACTION = 1

        /** `EVFLAG_GAMEOVER` and `EVFLAG_DEFEAT_ALL`. */
        const val EVFLAG_GAMEOVER = 0x65
        const val EVFLAG_DEFEAT_ALL = 6

        /** Offset of `tutorialEvents` in `ChapterEventGroup`. */
        const val TUTORIAL_EVENTS = 0x1C

        /** Offset of `miscBasedEvents` (searched by the wait events) in `ChapterEventGroup`. */
        const val MISC_EVENTS = 0x0C

        /** `EVFLAG_WIN`: set when the chapter is won. */
        const val EVFLAG_WIN = 3

        /** `EVFLAG_HIDE_BLINKING_ICON`. */
        const val EVFLAG_HIDE_BLINKING_ICON = 132
    }
}
