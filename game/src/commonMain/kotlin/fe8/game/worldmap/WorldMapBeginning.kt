package fe8.game.worldmap

import fe8.game.RomView
import fe8.game.bm.ChapterData
import fe8.game.event.EventEngine
import gbafe.proc.Proc

/**
 * Real callees for [WorldMapMain.BeginningHooks] (the Prologue path of
 * `WorldMap_CallBeginningEvent`).
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
     * `Events_WM_Beginning[GetROMChapterStruct(chIndex)->gmapEventId]`: the script address, or
     * null if the table entry is NULL. Inline in `WorldMap_CallBeginningEvent`, not a function
     * of its own, so it carries no `@VerifiesPort`.
     */
    public fun beginningEvent(chIndex: Int): Int? {
        val id = rom.u8(chapters.getROMChapterStruct(chIndex) + OFF_GMAP_EVENT_ID)
        val event = rom.u32(EVENTS_WM_BEGINNING + 4 * id)
        return if (event == 0) null else event
    }

    /**
     * The hooks for [proc]. `sub80BA008` and `startGmapMuEntry` stay stand-ins; so does
     * `startWmFaceCtrl` unless [faces] is given.
     *
     * @param faces the portrait controller `StartWMFaceCtrl` starts
     * @param mainProc the `ProcScr_WorldMapMain` proc, the controller's parent (a root proc when
     *   null)
     */
    public fun hooks(
        proc: WorldMapMainProc,
        faces: WorldMapFace? = null,
        mainProc: () -> Proc? = { null },
    ): WorldMapMain.BeginningHooks =
        WorldMapMain.BeginningHooks(
            fadeOutBgm = fadeOutBgm,
            beginningEvent = ::beginningEvent,
            resetGmStoryNode = path::resetGmStoryNode,
            clearMergeNextNode = { proc.gmIcon.mergeNextNode = false },
            callEvent = { events.callEvent(it, 0) },
            startWmFaceCtrl = { faces?.startWmFaceCtrl(mainProc()) },
        )

    private companion object {
        /** `gmapEventId` in `struct ROMChapterData`. */
        const val OFF_GMAP_EVENT_ID = 0x75

        /** GBA address of `Events_WM_Beginning`. */
        const val EVENTS_WM_BEGINNING = 0x088B39EC
    }
}
