package fe8.game.worldmap

import fe8.game.GameControl
import fe8.game.bm.PlaySt
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.endEach
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

    /** Callees of `WorldMap_CallBeginningEvent` that are not ported yet. */
    public class BeginningHooks(
        public val fadeOutBgm: (Int) -> Unit = {},
        public val sub80BA008: (Int) -> Unit = {},
        /** `Events_WM_Beginning[GetROMChapterStruct(chIndex)->gmapEventId]`, null if NULL. */
        public val beginningEvent: (Int) -> Int? = { null },
        public val resetGmStoryNode: () -> Unit = {},
        public val clearMergeNextNode: () -> Unit = {},
        public val callEvent: (Int) -> Unit = {},
        public val startWmFaceCtrl: () -> Unit = {},
        public val startGmapMuEntry: () -> Unit = {},
    )
}
