package fe8.game.worldmap

import fe8.game.GameControl
import fe8.game.bm.BMapMain.Companion.FACTION_BLUE
import fe8.game.bm.BMapMain.Companion.FACTION_GREEN
import fe8.game.bm.BattleMapStart
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import gbafe.proc.Proc
import gbafe.proc.ProcArgFunc
import gbafe.proc.ProcCmd
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.callArg
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.endEach
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.whileExists
import gbafe.proc.ProcScripts.yield

/**
 * The fields of `struct WorldMapMainProc` ported so far. The rest of the struct and the whole
 * `ProcScr_WorldMapMain` script are not ported yet.
 */
public class WorldMapMainProc {
    /** `flags_1` (bit 1 of the byte at +0x29). */
    public var flags1: Int = 0

    /** `timer` (+0x2C). */
    public var timer: Int = 0
}

/**
 * The world map entry wrapper: [wrapperScript] starts [mainScript] through [newWorldMap].
 *
 * @param engine the proc scheduler
 * @param playSt `gPlaySt`
 * @param gmData `gGMData`
 * @param mainScript `ProcScr_WorldMapMain`; its `newState` must create a [WorldMapMainProc]
 * @param fadeInScript `ProcScr_BmFadeIN`
 */
public class WorldMapMain(
    private val engine: ProcEngine,
    private val playSt: PlaySt,
    private val gmData: GmData,
    private val mainScript: ProcScript,
    private val fadeInScript: ProcScript,
) {
    /**
     * Transcribed from src/worldmap_main.c: ProcScr_WorldMapWrapper (script order not yet
     * verified against a reference run).
     */
    public val wrapperScript: ProcScript = ProcScript(
        "ProcScr_WorldMapWrapper",
        listOf(
            sleep(1),
            call(ProcFunc { newWorldMap() }),
            yield(),
            whileExists { mainScript },
            endEach { fadeInScript },
            yield(),
            end(),
        ),
    )

    /**
     * Starts [mainScript] in proc tree 3 and sets its `flags_1` and `timer`: 4 after the
     * postgame flag, else 12 once the monsters are merged, else 16.
     *
     * Ported from src/worldmap_main.c: NewWorldMap
     */
    public fun newWorldMap(): Proc {
        val proc = engine.start(mainScript, 3)
        val state = proc.state as WorldMapMainProc
        state.flags1 = 0
        state.timer = when {
            playSt.chapterStateBits and GameControl.PLAY_FLAG_POSTGAME != 0 -> 4
            gmData.monsterMerged() -> 12
            else -> 16
        }
        return proc
    }

    /**
     * Transcribed from src/worldmap_main.c: DeployEveryUnit (not verified: no unit has
     * `US_NOT_DEPLOYED` set in the prologue trace, so it cannot tell this from a no-op)
     *
     * Clears `US_NOT_DEPLOYED` of every valid unit with id in `FACTION_BLUE + 1 until
     * FACTION_GREEN`.
     */
    public fun deployEveryUnit(units: UnitTable) {
        for (id in FACTION_BLUE + 1 until FACTION_GREEN) {
            if (!units.isValid(id)) continue
            units.setState(id, units.state(id) and BattleMapStart.US_NOT_DEPLOYED.inv())
        }
    }

    /**
     * Ported from src/worldmap_main.c: sub_80B8FD4
     *
     * Sets `gGMData.state.bits.state_0` (bit 0) and clears `state_3` (bit 3). The trace has
     * `state_3` already 0 before, so only the `state_0` write is verified.
     */
    public fun sub80B8FD4() {
        gmData.stateRaw = (gmData.stateRaw or 0x01) and 0x08.inv()
    }

    /**
     * Ported from src/worldmap_main.c: WorldMap_CallBeginningEvent
     *
     * Not ported yet and injected: `Sound_FadeOutBGM`, `sub_80BA008`, the chapter's `gmapEventId`
     * lookup, `Events_WM_Beginning`, `ResetGmStoryNode`, `gm_icon->merge_next_node`, `CallEvent`,
     * `StartWMFaceCtrl` and `StartGmapMuEntry`.
     *
     * @param nodes `gWMNodeData`
     * @param checkFlag `CheckFlag`
     * @param hooks the unported callees
     */
    public fun callBeginningEvent(
        proc: WorldMapMainProc,
        nodes: WmNodeData,
        checkFlag: (Int) -> Boolean,
        hooks: BeginningHooks,
    ) {
        hooks.fadeOutBgm(4)
        val postgame = playSt.chapterStateBits and GameControl.PLAY_FLAG_POSTGAME != 0
        if (gmData.monsterMerged() || postgame) {
            hooks.sub80BA008(proc.timer)
        } else {
            val loc = gmData.unitLocation(0)
            gmData.currentNode = loc
            val nodeNext = nodes.nextLocId(loc, playSt.chapterModeIndex, checkFlag)
            if (nodeNext > -1) {
                val chIndex = nodes.chapterId(nodeNext, playSt.chapterModeIndex)
                playSt.chapterIndex = chIndex
                val event = hooks.beginningEvent(chIndex) ?: return
                hooks.resetGmStoryNode()
                hooks.clearMergeNextNode()
                hooks.callEvent(event)
            }
        }
        hooks.startWmFaceCtrl()
        hooks.startGmapMuEntry()
    }

    /**
     * Transcribed from src/worldmap_main.c: ProcScr_WorldMapMain (prefix, up to and including
     * `PROC_REPEAT(WorldMap_PostBeginningEvent)`; the rest is not transcribed yet).
     *
     * @param nodes `gWMNodeData`
     * @param checkFlag `CheckFlag`
     * @param hooks the unported callees of `WorldMap_CallBeginningEvent`
     * @param mainHooks the unported commands of the script
     */
    public fun mainScriptPrefix(
        nodes: WmNodeData,
        checkFlag: (Int) -> Boolean,
        hooks: BeginningHooks = BeginningHooks(),
        mainHooks: MainHooks = MainHooks(),
    ): List<ProcCmd> = listOf(
        setEndCb(ProcFunc { mainHooks.destruct(it) }),
        call(ProcFunc { sub80B8FD4() }),
        call(ProcFunc { mainHooks.init(it) }),
        callArg(
            ProcArgFunc { arg, _ ->
                mainHooks.fadeBgmOut(arg)
                true
            },
            1,
        ),
        sleep(4),
        call(
            ProcFunc { callBeginningEvent(it.state as WorldMapMainProc, nodes, checkFlag, hooks) },
        ),
        sleep(1),
        label(0),
        label(1),
        repeat(ProcFunc { mainHooks.postBeginningEvent(it) }),
    )

    /** Script commands of `ProcScr_WorldMapMain` whose callees are not ported yet. */
    public class MainHooks(
        /** `WorldMap_Destruct` (`PROC_SET_END_CB`). */
        public val destruct: (Proc) -> Unit = {},
        /** `WorldMap_Init`. */
        public val init: (Proc) -> Unit = {},
        /** `_FadeBgmOut(speed)`. */
        public val fadeBgmOut: (Int) -> Unit = {},
        /** `WorldMap_PostBeginningEvent` (`PROC_REPEAT`; the engine ends the repeat itself). */
        public val postBeginningEvent: (Proc) -> Unit = {},
    )

    /** Callees of `WorldMap_CallBeginningEvent` that are not ported yet. */
    public class BeginningHooks(
        /** `Sound_FadeOutBGM(speed)`. */
        public val fadeOutBgm: (Int) -> Unit = {},
        /** `sub_80BA008(timer)`: the monster-merged/postgame branch. */
        public val sub80BA008: (Int) -> Unit = {},
        /** `Events_WM_Beginning[GetROMChapterStruct(chIndex)->gmapEventId]`, null if NULL. */
        public val beginningEvent: (Int) -> Int? = { null },
        /** `ResetGmStoryNode()`. */
        public val resetGmStoryNode: () -> Unit = {},
        /** `proc->gm_icon->merge_next_node = false`. */
        public val clearMergeNextNode: () -> Unit = {},
        /** `CallEvent(Events_WM_Beginning[...], 0)` with the event id from [beginningEvent]. */
        public val callEvent: (Int) -> Unit = {},
        /** `StartWMFaceCtrl(proc)`. */
        public val startWmFaceCtrl: () -> Unit = {},
        /** `StartGmapMuEntry(NULL)`. */
        public val startGmapMuEntry: () -> Unit = {},
    )
}
