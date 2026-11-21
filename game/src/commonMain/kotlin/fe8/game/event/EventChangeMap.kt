package fe8.game.event

import fe8.game.bm.BmSt
import fe8.game.bm.Camera
import fe8.game.bm.PlaySt
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_STATE_GFXLOCKED
import gbafe.render.BgHardware

/**
 * The map change command of `src/eventscr.c` (`EV_CMD_LOMA` 0x25, `Event25_ChangeMap`,
 * `EvtLoadMap(chapter)`): switches to the map of another chapter during an event and centres the
 * camera on the position in event slot 0xB.
 *
 * @param state the event globals (`gEventSlots`).
 * @param memory script words (the command arguments).
 * @param playSt `gPlaySt`.
 * @param bmSt `gBmSt`.
 * @param hardware the BG hardware state (`gBG0TilemapBuffer`, `gBG1TilemapBuffer`, sync bits).
 * @param restartBattleMap `RestartBattleMap` (`BattleMapStart.restartBattleMap`).
 * @param refreshEntityBmMaps `RefreshEntityBmMaps`.
 * @param renderBmMap `RenderBmMap`.
 * @param resumeMapDisplay `BMapDispResume` and `ReleaseMus`, the unlock `Event24_EnableMapDisp`
 *   runs while the graphics are locked; the default does nothing.
 * @param refreshUnitSprites `RefreshUnitSprites`; the default does nothing.
 * @param refreshBMapGraphics `RefreshBMapGraphics`; the default does nothing.
 * @param changeUnitSpritePalette `ChangeUnitSpritePalette`; the default does nothing.
 */
public class EventChangeMap(
    private val state: EventState,
    private val memory: ScriptMemory,
    private val playSt: PlaySt,
    private val bmSt: BmSt,
    private val hardware: BgHardware,
    private val restartBattleMap: () -> Unit,
    private val refreshEntityBmMaps: () -> Unit,
    private val renderBmMap: () -> Unit,
    private val resumeMapDisplay: () -> Unit = {},
    private val refreshUnitSprites: () -> Unit = {},
    private val refreshBMapGraphics: () -> Unit = {},
    private val changeUnitSpritePalette: (Int) -> Unit = {},
) {
    /** Registers 0x25 on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x25, event25ChangeMap)
    }

    /**
     * Ported from src/eventscr.c: Event25_ChangeMap
     *
     * Takes the position from event slot 0xB (two u16, truncated to u8) and the chapter from the
     * command argument (slot 2 when negative), unlocks the map display (`Event24_EnableMapDisp`),
     * sets `gPlaySt.chapterIndex`, restarts the battle map, centres the camera on the position,
     * refreshes the entity maps, renders the map, refreshes unit sprites and map graphics, applies
     * `proc.mapSpritePalIdOverride` via `ChangeUnitSpritePalette`, and clears and requests sync
     * for BG0 and BG1. Always yields.
     *
     * Unexercised in `ram_trace_newgame_bm` (`load_map 16`): negative command chapter falling back
     * to `gEventSlots[2]`, `EV_STATE_GFXLOCKED` set on entry (`r09508` has `0x0102`; checked with
     * synthetic `EV_STATE_GFXLOCKED` in `EventChangeMapDifferentialTest`), and non-zero
     * `mapSpritePalIdOverride` (`UnitSprites.changeUnitSpritePalette` stays transcribed).
     */
    public val event25ChangeMap: EventHandler = EventHandler { proc ->
        val x = state.slots[0xB] and 0xFF
        val y = (state.slots[0xB] ushr 16) and 0xFF
        var chapterIndex = memory.u16(proc.pEventCurrent + 2).toShort().toInt()
        if (chapterIndex < 0) chapterIndex = state.slots[2].toShort().toInt()

        // The decomp calls `Event24_EnableMapDisp` here: unlock the graphics if locked.
        if (proc.evStateBits and EV_STATE_GFXLOCKED != 0) resumeMapDisplay()
        proc.evStateBits = proc.evStateBits and EV_STATE_GFXLOCKED.inv()

        playSt.chapterIndex = chapterIndex

        restartBattleMap()

        // Tile coordinates to pixels (16 per tile).
        bmSt.cameraX = Camera.getCameraCenteredX(bmSt, x * 16)
        bmSt.cameraY = Camera.getCameraCenteredY(bmSt, y * 16)

        refreshEntityBmMaps()
        renderBmMap()
        refreshUnitSprites()
        refreshBMapGraphics()

        changeUnitSpritePalette(proc.mapSpritePalIdOverride)

        hardware.fill(hardware.mapBuffer(0), 0)
        hardware.fill(hardware.mapBuffer(1), 0)
        hardware.enableBgSyncByMask(BG0_SYNC_BIT)
        hardware.enableBgSyncByMask(BG1_SYNC_BIT)

        EVC_ADVANCE_YIELD
    }

    private companion object {
        /** `BG0_SYNC_BIT` / `BG1_SYNC_BIT` (`include/hardware.h`). */
        const val BG0_SYNC_BIT = 1 shl 0
        const val BG1_SYNC_BIT = 1 shl 1
    }
}
