package fe8.game.worldmap

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.ScriptMemory

/**
 * The callees of the world map big map commands ([WorldMapBigMapEvents]); the defaults do
 * nothing. [of] wires the ported [WorldMapRm] and [WorldMapRmBorder].
 */
public class WmBigMapHooks(
    /** `StartGmapRm(x, y, mask, NULL)`: shows the big map ([WorldMapRm.startGmapRm]). */
    public val startGmapRm: (Int, Int, Int) -> Unit = { _, _, _ -> },
    /** `StartGmapRmUpdateExt(x0, y0, x1, y1, speed, delay, NULL)`: scrolls the big map. */
    public val startGmapRmUpdateExt: (Int, Int, Int, Int, Int, Int) -> Unit =
        { _, _, _, _, _, _ -> },
    /** `GmapRm_EndAll()`: ends the big map at once (event skip). */
    public val gmapRmEndAll: () -> Unit = {},
    /** `GmapRm_SetUnblocked()`: lets the big map proc leave its wait. */
    public val gmapRmSetUnblocked: () -> Unit = {},
    /** `GmapRMExists()`: whether the big map is showing. */
    public val gmapRmExists: () -> Boolean = { false },
    /** `WmShowNationHighlightedMap(nation, NULL)`: highlights a nation's border on the big map. */
    public val showNationHighlightedMap: (Int) -> Unit = {},
    /** `EndGmapRmBorder1(index)`: ends the nation highlight [index] (all when negative). */
    public val endGmapRmBorder1: (Int) -> Unit = {},
    /**
     * `sub_80C2BC4(index)` (src/worldmap_rm.c:788): whether the nation highlight proc [index]
     * exists (any, when negative).
     */
    public val sub80C2BC4: (Int) -> Boolean = { false },
    /**
     * `sub_80C2C10(index)` (src/worldmap_rm.c:813): sets `flag` on the nation highlight proc
     * [index] (all when negative), which ends its hold and starts its merge-out.
     */
    public val sub80C2C10: (Int) -> Unit = {},
) {
    /** Factory for hooks backed by the ported world-map services. */
    public companion object {
        /** Hooks that call the ported [rm] and [border] services. */
        public fun of(rm: WorldMapRm, border: WorldMapRmBorder): WmBigMapHooks = WmBigMapHooks(
            startGmapRm = { x, y, c -> rm.startGmapRm(x, y, c, null) },
            startGmapRmUpdateExt = { x0, y0, x1, y1, speed, delay ->
                rm.startGmapRmUpdateExt(x0, y0, x1, y1, speed, delay, null)
            },
            gmapRmEndAll = rm::gmapRmEndAll,
            gmapRmSetUnblocked = rm::gmapRmSetUnblocked,
            gmapRmExists = rm::gmapRmExists,
            showNationHighlightedMap = { border.wmShowNationHighlightedMap(it, null) },
            endGmapRmBorder1 = border::endGmapRmBorder1,
            sub80C2BC4 = border::sub80C2BC4,
            sub80C2C10 = border::sub80C2C10,
        )
    }
}

/**
 * The world map commands `EventB4..BA` of `src/eventscr_gmap.c`. `EventB4` and the first
 * `EventB6` are verified through the event VM in WorldMapEventHostTest (Prologue steps
 * 4907-5495); `EventB6`, `EventB8`, `EventB9` and `EventBA` in WorldMapEventCameraTest (steps
 * 5433-8110); `EventB5` and `EventB7` are not run by the capture and stay transcribed. The tests
 * register the handlers one by one; [registerOn] adds the whole range.
 *
 * @param memory script words (the command arguments)
 * @param hooks the callees
 */
public class WorldMapBigMapEvents(
    private val memory: ScriptMemory,
    private val hooks: WmBigMapHooks,
) {
    /** Registers the handlers under their opcodes (0xB4-0xBA) on [engine]. */
    public fun registerOn(engine: EventEngine) {
        engine.register(0xB4, eventB4WmDisplayBigMap)
        engine.register(0xB5, eventB5WmHideBigMap)
        engine.register(0xB6, eventB6WmMoveBigMap)
        engine.register(0xB7, eventB7WmBigMapWait)
        engine.register(0xB8, eventB8WmShowBigMapHighlight)
        engine.register(0xB9, eventB9WmRemoveHighlightNationPart1)
        engine.register(0xBA, eventBAWmRemoveHighlightNationPart2)
    }

    /** `EVENT_IS_SKIPPING(proc)`: the player is skipping the event. */
    private fun skipping(proc: EventEngineProc) = proc.evStateBits and EV_STATE_SKIPPING != 0

    /** The unsigned halfword [index] of the command (0 = opcode); `ARGV[n]` is `arg16(n + 1)`. */
    private fun arg16(proc: EventEngineProc, index: Int) =
        memory.u16(proc.pEventCurrent + 2 * index)

    /** [arg16] as a signed `s16` (the decomp casts the coordinates and speeds to `s16`). */
    private fun argS16(proc: EventEngineProc, index: Int) = arg16(proc, index).toShort().toInt()

    /** The `u32` at halfword [index] of the command. */
    private fun arg32(proc: EventEngineProc, index: Int) =
        arg16(proc, index) or (arg16(proc, index + 1) shl 16)

    /**
     * Ported from src/eventscr_gmap.c: EventB4_WmDisplayBigMap
     *
     * Shows the full-screen big map at scroll `(x, y)` (halfwords `+4`, `+6`) with the flag mask
     * (`u32` at `+8`, see [WorldMapRm.FLAG_0]). The skipping branch (nothing is shown) is not
     * run by the capture.
     */
    public val eventB4WmDisplayBigMap: EventHandler = EventHandler { proc ->
        if (!skipping(proc)) {
            hooks.startGmapRm(argS16(proc, 2), argS16(proc, 3), arg32(proc, 4))
        }
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Transcribed from src/eventscr_gmap.c: EventB5_WmHideBigMap (not verified: no @VerifiesPort
     * test names it).
     *
     * Lets the big map leave (`GmapRm_SetUnblocked`), or ends it at once while skipping.
     */
    public val eventB5WmHideBigMap: EventHandler = EventHandler { proc ->
        if (skipping(proc)) hooks.gmapRmEndAll() else hooks.gmapRmSetUnblocked()
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr_gmap.c: EventB6_WmMoveBigMap
     *
     * Verified through the event VM: the started `GmapRmUpdateExt` fields equal the capture's at
     * every step of all seven scrolls. The skipping branch is not run by the capture.
     *
     * Scrolls the big map from `(x1, y1)` to `(x2, y2)` over `speed` frames after `delay`
     * ([WorldMapRm.startGmapRmUpdateExt]); the six `s16` arguments are at `+4..+14`.
     */
    public val eventB6WmMoveBigMap: EventHandler = EventHandler { proc ->
        if (!skipping(proc)) {
            hooks.startGmapRmUpdateExt(
                argS16(proc, 2), argS16(proc, 3), argS16(proc, 4), argS16(proc, 5),
                argS16(proc, 6), argS16(proc, 7),
            )
        }
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Transcribed from src/eventscr_gmap.c: EventB7_WmBigMapWait (not verified: no @VerifiesPort
     * test names it).
     *
     * Holds the event while the big map is showing; while skipping it ends the map at once.
     */
    public val eventB7WmBigMapWait: EventHandler = EventHandler { proc ->
        if (skipping(proc)) {
            hooks.gmapRmEndAll()
            EVC_ADVANCE_CONTINUE
        } else if (hooks.gmapRmExists()) {
            EVC_STOP_YIELD
        } else {
            EVC_ADVANCE_CONTINUE
        }
    }

    /**
     * Ported from src/eventscr_gmap.c: EventB8_WmShowBigMapHighlight
     *
     * Verified through the event VM (the nation of every highlight; skipping branch not run).
     *
     * Starts the highlight of the nation (`u32` at `+4`, an index into `GfxSet_WmNationMap`,
     * `WM_NATION_*`) on the big map.
     */
    public val eventB8WmShowBigMapHighlight: EventHandler = EventHandler { proc ->
        if (!skipping(proc)) hooks.showNationHighlightedMap(arg32(proc, 2))
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr_gmap.c: EventB9_WmRemoveHighlightNationPart1
     *
     * Verified through the event VM (the border's flag is set by this command). The skipping
     * branch (`EndGmapRmBorder1(-1)`, which ends every highlight) is not run by the capture.
     *
     * Tells the highlight of the given nation to start fading out (`sub_80C2C10`).
     */
    public val eventB9WmRemoveHighlightNationPart1: EventHandler = EventHandler { proc ->
        if (skipping(proc)) hooks.endGmapRmBorder1(-1) else hooks.sub80C2C10(arg32(proc, 2))
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr_gmap.c: EventBA_WmRemoveHighlightNationPart2
     *
     * Verified through the event VM (waits on the nation's border, then advances). The skipping
     * branch is not run by the capture.
     *
     * Holds the event until the nation's highlight has gone (`sub_80C2BC4` false); the command
     * stays while it exists. Uses `EVC_ADVANCE_YIELD` once it is gone.
     */
    public val eventBAWmRemoveHighlightNationPart2: EventHandler = EventHandler { proc ->
        val nation = arg32(proc, 2)
        if (skipping(proc)) {
            if (hooks.sub80C2BC4(nation)) hooks.endGmapRmBorder1(nation)
            EVC_ADVANCE_CONTINUE
        } else if (!hooks.sub80C2BC4(nation)) {
            EVC_ADVANCE_YIELD
        } else {
            EVC_STOP_YIELD
        }
    }
}
