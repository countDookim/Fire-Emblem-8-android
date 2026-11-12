package fe8.game.worldmap

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.ScriptMemory

/** The callees of the world map unit commands; each defaults to nothing. */
public class WmUnitHooks(
    /** `SetGmClassUnit(id, jid, allegiance, nodeId)`. */
    public val setClassUnit: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> },
    /** `SetGmCharUnit(id, pid, allegiance, nodeId)`. */
    public val setCharUnit: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> },
    /** `RemoveGmUnit(index)`. */
    public val removeUnit: (Int) -> Unit = {},
    /** `ShowGmUnit(index)`. */
    public val showUnit: (Int) -> Unit = {},
    /** `HideGmUnit(index)`. */
    public val hideUnit: (Int) -> Unit = {},
    /** `GmMu_StartFadeIn(GM_MU, index, delay)`. */
    public val fadeIn: (Int, Int) -> Unit = { _, _ -> },
    /** `GmMu_StartFadeOut(GM_MU, index, delay)`. */
    public val fadeOut: (Int, Int) -> Unit = { _, _ -> },
    /** `EndGmapUnitFade()`. */
    public val endUnitFade: () -> Unit = {},
    /** `GmUnitFadeExists()`. */
    public val unitFadeExists: () -> Boolean = { false },
    /** `gGMData.units[unit].location = node`. */
    public val setLocation: (unit: Int, node: Int) -> Unit = { _, _ -> },
    /** `GmMu_SetNode(GM_MU, unit, node)`. */
    public val setNode: (Int, Int) -> Unit = { _, _ -> },
    /** `GmMu_SetPosition(GM_MU, index, x, y)`. */
    public val setPosition: (Int, Int, Int) -> Unit = { _, _, _ -> },
    /** `StartGmapAutoMu_Type1(&input, flag, NULL)`. */
    public val startMoveFree: (WorldMapAutoMu.Type1Input, Int) -> Unit = { _, _ -> },
    /** `StartGmapAutoMu_Type0(&input, flag, NULL)`. */
    public val startMovePaths: (WorldMapAutoMu.Type0Input, Int) -> Unit = { _, _ -> },
    /** `GmMu_PauseMovement(GM_MU, index)`. */
    public val pauseMove: (Int) -> Unit = {},
    /** `GmMu_ResumeMovement(GM_MU, index)`. */
    public val resumeMove: (Int) -> Unit = {},
    /** `IsGmAutoMuActiveFor(unit)`. */
    public val autoMuActive: (Int) -> Boolean = { false },
    /** `EndGmAutoMuFor(unit)`. */
    public val endAutoMu: (Int) -> Unit = {},
)

/**
 * The world map unit commands `Event9E..EventAC` of `src/eventscr_gmap.c`. Register them one by
 * one with `engine.register(code, handler)`; the capture runs 9E, A0, A3, A5, A7, A8 and AC, the
 * others are transcribed.
 *
 * @param memory script words (the command arguments)
 * @param hooks the callees
 */
public class WorldMapUnitEvents(
    private val memory: ScriptMemory,
    private val hooks: WmUnitHooks,
) {
    private fun skipping(proc: EventEngineProc) = proc.evStateBits and EV_STATE_SKIPPING != 0

    private fun arg16(proc: EventEngineProc, index: Int) =
        memory.u16(proc.pEventCurrent + 2 * index)

    private fun argS16(proc: EventEngineProc, index: Int) = arg16(proc, index).toShort().toInt()

    /** `EVT_CMD_ARG32_LE`: the word at halfword 2. */
    private fun arg32(proc: EventEngineProc) = arg16(proc, 2) or (arg16(proc, 3) shl 16)

    /** Ported from src/eventscr_gmap.c: Event9E_WmSetClassUnit */
    public val event9EWmSetClassUnit: EventHandler = EventHandler { proc ->
        hooks.setClassUnit(arg16(proc, 2), arg16(proc, 3), arg16(proc, 4), arg16(proc, 5))
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Transcribed from src/eventscr_gmap.c: Event9F_WmSetCharUnit (not verified: the window sets
     * no character unit)
     */
    public val event9FWmSetCharUnit: EventHandler = EventHandler { proc ->
        hooks.setCharUnit(arg16(proc, 2), arg16(proc, 3), arg16(proc, 4), arg16(proc, 5))
        EVC_ADVANCE_CONTINUE
    }

    /** Ported from src/eventscr_gmap.c: EventA0_WmRemoveUnit */
    public val eventA0WmRemoveUnit: EventHandler = EventHandler { proc ->
        hooks.removeUnit(arg32(proc))
        EVC_ADVANCE_CONTINUE
    }

    /** Transcribed from src/eventscr_gmap.c: EventA1_WmShowUnit (not verified: not run) */
    public val eventA1WmShowUnit: EventHandler = EventHandler { proc ->
        hooks.showUnit(arg32(proc))
        EVC_ADVANCE_CONTINUE
    }

    /** Transcribed from src/eventscr_gmap.c: EventA2_WmHideUnit (not verified: not run) */
    public val eventA2WmHideUnit: EventHandler = EventHandler { proc ->
        hooks.hideUnit(arg32(proc))
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr_gmap.c: EventA3_WmShowUnitFaded
     *
     * The skipping branch is not run by the capture.
     */
    public val eventA3WmShowUnitFaded: EventHandler = EventHandler { proc ->
        val index = arg16(proc, 2)
        val delay = argS16(proc, 3)
        if (skipping(proc)) hooks.showUnit(index) else hooks.fadeIn(index, delay)
        EVC_ADVANCE_CONTINUE
    }

    /** Transcribed from src/eventscr_gmap.c: EventA4_WmHideUnitFaded (not verified: not run) */
    public val eventA4WmHideUnitFaded: EventHandler = EventHandler { proc ->
        val index = arg16(proc, 2)
        val delay = argS16(proc, 3)
        if (skipping(proc)) hooks.hideUnit(index) else hooks.fadeOut(index, delay)
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr_gmap.c: EventA5_WmUnitFadeWait
     *
     * The skipping branch is not run by the capture.
     */
    public val eventA5WmUnitFadeWait: EventHandler = EventHandler { proc ->
        if (skipping(proc)) hooks.endUnitFade()
        if (!hooks.unitFadeExists()) EVC_ADVANCE_YIELD else EVC_STOP_YIELD
    }

    /** Transcribed from src/eventscr_gmap.c: EventA6_WmUnitSetOnNode (not verified: not run) */
    public val eventA6WmUnitSetOnNode: EventHandler = EventHandler { proc ->
        val unit = arg16(proc, 2)
        val node = arg16(proc, 3)
        hooks.setLocation(unit, node)
        hooks.setNode(unit, node)
        EVC_ADVANCE_CONTINUE
    }

    /** Ported from src/eventscr_gmap.c: EventA7_WmUnitSetPosition */
    public val eventA7WmUnitSetPosition: EventHandler = EventHandler { proc ->
        hooks.setPosition(argS16(proc, 2), argS16(proc, 4), argS16(proc, 5))
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr_gmap.c: EventA8_WmUnitMoveFree
     *
     * The skipping branch is not run by the capture.
     */
    public val eventA8WmUnitMoveFree: EventHandler = EventHandler { proc ->
        val index = argS16(proc, 2)
        val acceleration = arg16(proc, 3)
        val x1 = arg16(proc, 4)
        val y1 = arg16(proc, 5)
        val x2 = arg16(proc, 6)
        val y2 = arg16(proc, 7)
        val time = arg16(proc, 8)
        val flag = arg16(proc, 9)
        val delay = arg16(proc, 10)
        if (skipping(proc)) {
            hooks.setPosition(index, x2, y2)
            if (flag and 2 != 0) hooks.hideUnit(index)
        } else {
            hooks.startMoveFree(
                WorldMapAutoMu.Type1Input(
                    unitId = index, unk01 = acceleration, unk02 = 0, x1 = x1, y1 = y1,
                    x2 = x2, y2 = y2, delay = delay, speed = time,
                ),
                flag,
            )
        }
        EVC_ADVANCE_CONTINUE
    }

    /** Transcribed from src/eventscr_gmap.c: EventA9_WmUnitMovePaths (not verified: not run) */
    public val eventA9WmUnitMovePaths: EventHandler = EventHandler { proc ->
        val unit = argS16(proc, 2)
        val conf = arg16(proc, 3)
        val node1 = arg16(proc, 4)
        val node2 = arg16(proc, 5)
        val speed = arg16(proc, 6)
        val delay = arg16(proc, 7)
        if (skipping(proc)) {
            hooks.setLocation(unit, node2)
            if (conf and 2 != 0) hooks.hideUnit(unit)
        } else {
            hooks.startMovePaths(
                WorldMapAutoMu.Type0Input(
                    unitId = unit, unk01 = 0, srcNode = node1, dstNode = node2, delay = delay,
                    speed = speed,
                ),
                conf,
            )
        }
        EVC_ADVANCE_CONTINUE
    }

    /** Transcribed from src/eventscr_gmap.c: EventAA_WmUnitPauseMove (not verified: not run) */
    public val eventAAWmUnitPauseMove: EventHandler = EventHandler { proc ->
        if (!skipping(proc)) hooks.pauseMove(argS16(proc, 2))
        EVC_ADVANCE_CONTINUE
    }

    /** Transcribed from src/eventscr_gmap.c: EventAB_WmUnitResumeMove (not verified: not run) */
    public val eventABWmUnitResumeMove: EventHandler = EventHandler { proc ->
        if (!skipping(proc)) hooks.resumeMove(argS16(proc, 2))
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr_gmap.c: EventAC_WmUnitMoveWait
     *
     * The skipping branch is not run by the capture.
     */
    public val eventACWmUnitMoveWait: EventHandler = EventHandler { proc ->
        val unit = argS16(proc, 2)
        if (skipping(proc)) {
            if (hooks.autoMuActive(unit)) hooks.endAutoMu(unit)
            EVC_ADVANCE_CONTINUE
        } else if (!hooks.autoMuActive(unit)) {
            EVC_ADVANCE_YIELD
        } else {
            EVC_STOP_YIELD
        }
    }
}

/**
 * The world map point commands `EventBB..EventBE` of `src/eventscr_gmap.c` (marked points,
 * [WorldMapDot]); the capture runs BC, BD and BE.
 *
 * @param memory script words (the command arguments)
 * @param dots the points
 * @param nodes `gWMNodeData`
 */
public class WorldMapDotEvents(
    private val memory: ScriptMemory,
    private val dots: WorldMapDot,
    private val nodes: WmNodeData,
) {
    private fun skipping(proc: EventEngineProc) = proc.evStateBits and EV_STATE_SKIPPING != 0

    private fun arg16(proc: EventEngineProc, index: Int) =
        memory.u16(proc.pEventCurrent + 2 * index)

    private fun argS16(proc: EventEngineProc, index: Int) = arg16(proc, index).toShort().toInt()

    private fun arg32(proc: EventEngineProc, index: Int) =
        arg16(proc, index) or (arg16(proc, index + 1) shl 16)

    /** Transcribed from src/eventscr_gmap.c: EventBB_ (not verified: not run) */
    public val eventBB: EventHandler = EventHandler { proc ->
        if (!skipping(proc)) {
            dots.startWmPlaceDot(
                argS16(proc, 2), argS16(proc, 3), argS16(proc, 4), argS16(proc, 5),
                arg32(proc, 6), null,
            )
        }
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr_gmap.c: EventBC_MarkPoint
     *
     * The skipping branch is not run by the capture.
     */
    public val eventBCMarkPoint: EventHandler = EventHandler { proc ->
        val a = argS16(proc, 2)
        val pal = argS16(proc, 3)
        val nodeId = argS16(proc, 4)
        val eff = argS16(proc, 5)
        if (!skipping(proc)) {
            dots.startWmPlaceDot(a, pal, nodes.x(nodeId) / 2, nodes.y(nodeId) / 2, eff, null)
        }
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr_gmap.c: EventBD_
     *
     * The skipping branch is not run by the capture.
     */
    public val eventBD: EventHandler = EventHandler { proc ->
        if (skipping(proc)) {
            dots.endWmPlaceDotByIndex(-1)
        } else {
            dots.setWmPlaceDotFlagForIndex(arg32(proc, 2))
        }
        EVC_ADVANCE_CONTINUE
    }

    /**
     * Ported from src/eventscr_gmap.c: EventBE_
     *
     * The skipping branch is not run by the capture.
     */
    public val eventBE: EventHandler = EventHandler { proc ->
        val a = arg32(proc, 2)
        if (skipping(proc)) {
            if (dots.isWmPlaceDotActiveAtIndex(a)) dots.endWmPlaceDotByIndex(a)
            EVC_ADVANCE_CONTINUE
        } else if (!dots.isWmPlaceDotActiveAtIndex(a)) {
            EVC_ADVANCE_YIELD
        } else {
            EVC_STOP_YIELD
        }
    }
}
