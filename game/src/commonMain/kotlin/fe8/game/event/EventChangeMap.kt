package fe8.game.event

import fe8.game.bm.BmSt
import fe8.game.bm.Camera
import fe8.game.bm.PlaySt
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_STATE_GFXLOCKED
import gbafe.render.BgHardware

/**
 * The map change command of `src/eventscr.c` (0x25 `load_map`, Event25_ChangeMap).
 *
 * @param state the event globals (`gEventSlots`).
 * @param memory script words (the command arguments).
 * @param playSt `gPlaySt`.
 * @param bmSt `gBmSt`.
 * @param hardware the BG hardware state (palette buffer, blend registers).
 * @param restartBattleMap `RestartBattleMap` (`BattleMapStart.restartBattleMap`).
 * @param refreshEntityBmMaps `RefreshEntityBmMaps`.
 * @param renderBmMap `RenderBmMap`.
 * @param resumeMapDisplay `BMapDispResume` and `ReleaseMus`, the unlock `Event24_EnableMapDisp`
 *   runs while the graphics are locked. Stand-in until M6-E: nothing by default.
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
) {
    /** Registers 0x25 on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0x25, event25ChangeMap)
    }

    /**
     * Ported from src/eventscr.c: Event25_ChangeMap
     *
     * Takes the position from event slot 0xB (two u16, truncated to u8) and the chapter from the
     * command argument (slot 2 when negative), unlocks the map display, sets
     * `gPlaySt.chapterIndex`, restarts the battle map, centres the camera on the position,
     * refreshes the entity maps, renders the map and sets the backdrop and blend registers.
     * Always yields.
     */
    public val event25ChangeMap: EventHandler = EventHandler { proc ->
        val x = state.slots[0xB] and 0xFF
        val y = (state.slots[0xB] ushr 16) and 0xFF
        var chapterIndex = memory.u16(proc.pEventCurrent + 2).toShort().toInt()
        if (chapterIndex < 0) chapterIndex = state.slots[2].toShort().toInt()

        // Event24_EnableMapDisp
        if (proc.evStateBits and EV_STATE_GFXLOCKED != 0) resumeMapDisplay()
        proc.evStateBits = proc.evStateBits and EV_STATE_GFXLOCKED.inv()

        playSt.chapterIndex = chapterIndex

        restartBattleMap()

        // Tile coordinates to pixels (16 per tile).
        bmSt.cameraX = Camera.getCameraCenteredX(bmSt, x * 16)
        bmSt.cameraY = Camera.getCameraCenteredY(bmSt, y * 16)

        refreshEntityBmMaps()
        renderBmMap()
        hardware.palette[0] = 0
        hardware.enablePaletteSync()

        // Blend set-up of the C: first target on every layer plus the backdrop, blend config
        // `(3, 0, 0, 0x10)`.
        val lcd = hardware.lcd
        lcd.setBlendTargetA(1, 1, 1, 1, 1)
        lcd.bldcnt = lcd.bldcnt or 0x20 // SetBlendBackdropA(TRUE)
        lcd.setBlendConfig(3, 0, 0, 0x10)
        EVC_ADVANCE_YIELD
    }
}
