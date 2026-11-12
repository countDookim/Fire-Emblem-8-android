package fe8.game.worldmap

import fe8.game.RomView
import fe8.game.bm.ChapterData
import fe8.game.event.EventEngine
import gbafe.proc.Proc

/**
 * Real callees for [WorldMapMain.BeginningHooks] (the Prologue path of
 * `WorldMap_CallBeginningEvent`, src/worldmap_main.c:1969): the world map's beginning event, the
 * scripted scene that plays when the world map opens after a chapter (the Prologue's opening
 * story map).
 *
 * @param rom the ROM, for `Events_WM_Beginning`
 * @param chapters `gChapterDataTable` access (`GetROMChapterStruct`)
 * @param path `ResetGmStoryNode`'s owner
 * @param events the event engine (`CallEvent`)
 * @param fadeOutBgm `Sound_FadeOutBGM`
 */
public class WorldMapBeginning(
    private val rom: RomView,
    private val chapters: ChapterData,
    private val path: WorldMapPath,
    private val events: EventEngine,
    private val fadeOutBgm: (Int) -> Unit,
) {
    /**
     * `Events_WM_Beginning[GetROMChapterStruct(chIndex)->gmapEventId]`: the script address of
     * chapter [chIndex]'s beginning event, or null if the table entry is NULL (the decomp then
     * returns early, without a fade-in or portrait controller). Inline in
     * `WorldMap_CallBeginningEvent`, not a function of its own, so it carries no `@VerifiesPort`.
     */
    public fun beginningEvent(chIndex: Int): Int? {
        val id = rom.u8(chapters.getROMChapterStruct(chIndex) + OFF_GMAP_EVENT_ID)
        val event = rom.u32(EVENTS_WM_BEGINNING + 4 * id)
        return if (event == 0) null else event
    }

    /**
     * The hooks for [proc]. `sub80BA008` (M6-I) stays a stand-in; `startWmFaceCtrl` (D11-wm8d)
     * is one unless [faces] is given and `startGmapMuEntry` (D11-wm3e) unless [muEntry] is given.
     * `clearMergeNextNode` clears the node icons' `merge_next_node` flag, as the decomp does just
     * before it calls the event.
     *
     * @param faces the portrait controller `StartWMFaceCtrl` starts
     * @param muEntry the entry-band controller `StartGmapMuEntry(NULL)` starts
     * @param mainProc the `ProcScr_WorldMapMain` proc, the controller's parent (a root proc when
     *   null)
     */
    public fun hooks(
        proc: WorldMapMainProc,
        faces: WorldMapFace? = null,
        muEntry: WorldMapMuEntry? = null,
        mainProc: () -> Proc? = { null },
    ): WorldMapMain.BeginningHooks =
        WorldMapMain.BeginningHooks(
            fadeOutBgm = fadeOutBgm,
            beginningEvent = ::beginningEvent,
            resetGmStoryNode = path::resetGmStoryNode,
            clearMergeNextNode = { proc.gmIcon.mergeNextNode = false },
            callEvent = { events.callEvent(it, 0) },
            startWmFaceCtrl = { faces?.startWmFaceCtrl(mainProc()) },
            startGmapMuEntry = { muEntry?.startGmapMuEntry() },
        )

    private companion object {
        /** Offset of `gmapEventId` in `struct ROMChapterData` (include/chapterdata.h:85). */
        const val OFF_GMAP_EVENT_ID = 0x75

        /** GBA address of `Events_WM_Beginning` (data/data_8B363C.s:244). */
        const val EVENTS_WM_BEGINNING = 0x088B39EC
    }
}
