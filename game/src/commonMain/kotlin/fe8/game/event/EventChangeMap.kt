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
     * refreshes the entity maps and renders the map. Always yields.
     *
     * NOTE: the tail of this handler (black backdrop colour, blend set-up) is not in
     * `Event25_ChangeMap` (src/eventscr.c:1896); the same three blend calls are `sub_8009E28`
     * (src/gamecontrol.c:635). The decomp's handler instead runs `RefreshUnitSprites`,
     * `RefreshBMapGraphics`, `ChangeUnitSpritePalette(mapSpritePalIdOverride)` and clears and
     * syncs BG0/BG1, which this port does not. The differential test does not compare palettes,
     * blend registers or the BGs, so this is unchecked: a likely divergence.
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
        hardware.palette[0] = 0
        hardware.enablePaletteSync()

        // Not in the decomp's Event25_ChangeMap (see NOTE above): the first blend target on every
        // layer plus the backdrop, blend config `(3, 0, 0, 0x10)`, as in `sub_8009E28`.
        val lcd = hardware.lcd
        lcd.setBlendTargetA(1, 1, 1, 1, 1)
        lcd.bldcnt = lcd.bldcnt or 0x20 // SetBlendBackdropA(TRUE)
        lcd.setBlendConfig(3, 0, 0, 0x10)
        EVC_ADVANCE_YIELD
    }
}
