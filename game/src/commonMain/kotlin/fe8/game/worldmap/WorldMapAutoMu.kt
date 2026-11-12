package fe8.game.worldmap

import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.yield

/**
 * `struct GMapAutoMuProc` past `PROC_HEADER`: one scripted walk of a world map unit (the
 * "auto" movement that event commands `A8` / `A9` start). Unions of `target` are flattened
 * into the `src*` / `dst*` fields.
 */
public class GmapAutoMuProc {
    /** `kind` (+0x29): 0 moves from node to node, 1 moves between two map positions. */
    public var kind: Int = 0

    /**
     * `flags` (+0x2A): bit 0 fade the unit in first, bit 1 fade it out and hide it at the end,
     * bit 2 is passed on to the map unit's move input (`flags_5` of `GMapMuPrimProc`; meaning
     * unknown).
     */
    public var flags: Int = 0

    /** `unitId` (+0x2C, `u16`): the world map unit's index (`wm_uid`). */
    public var unitId: Int = 0

    /** `target.src` (+0x30): the source node, or the x of the source position (`s16`). */
    public var srcA: Int = 0

    /** `target.src.pos.y` (+0x32, `s16`), unused for a node. */
    public var srcY: Int = 0

    /** `target.dst` (+0x34): the destination node, or the x of the destination (`s16`). */
    public var dstA: Int = 0

    /** `target.dst.pos.y` (+0x36, `s16`), unused for a node. */
    public var dstY: Int = 0

    /** `unk_38` (+0x38, `u8`): copied to the map unit move input; meaning unknown. */
    public var unk38: Int = 0

    /** `unk_39` (+0x39, `u8`): as [unk38], used only by kind 1; meaning unknown. */
    public var unk39: Int = 0

    /**
     * `speed` (+0x3A, `s16`): passed to the map unit move; `sub_80BDEB4` multiplies a negative
     * value by a distance-derived factor (`sub_80BD41C`).
     */
    public var speed: Int = 0

    /** `delay` (+0x3C, `s16`): frames to wait before the walk starts. */
    public var delay: Int = 0
}

/**
 * `src/worldmap_automu.c`: the proc that walks one world map unit (a lord or army marker)
 * from node to node or between two map positions, with optional fade in / fade out. Started by
 * the world map unit event commands (`EventA8_WmUnitMoveFree`, `EventA9_WmUnitMovePaths`).
 *
 * @param engine the proc scheduler
 * @param gmData `gGMData`
 * @param hooks the callees of other files
 */
public class WorldMapAutoMu(
    private val engine: ProcEngine,
    private val gmData: GmData,
    private val hooks: Hooks = Hooks(),
) {
    /**
     * The callees outside `worldmap_automu.c` (the map unit manager `GM_MU` is the caller's);
     * default to nothing.
     */
    public class Hooks(
        /** `sub_80BE12C(GM_MU, unit)`: whether the unit's movement is running. */
        public val muMoving: (unit: Int) -> Boolean = { false },
        /** `sub_80BE330(GM_MU, unit)`: stops the unit's movement. */
        public val muStop: (unit: Int) -> Unit = {},
        /** `EndGmapUnitFade()`. */
        public val endUnitFade: () -> Unit = {},
        /** `GmMu_SetPosition(GM_MU, unit, x, y)`. */
        public val muSetPosition: (unit: Int, x: Int, y: Int) -> Unit = { _, _, _ -> },
        /** `HideGmUnit(unit)`. */
        public val hideUnit: (unit: Int) -> Unit = {},
        /** `ShowGmUnit(unit)`. */
        public val showUnit: (unit: Int) -> Unit = {},
        /** `GmMu_StartFadeIn(GM_MU, unit, duration)`. */
        public val muFadeIn: (unit: Int, duration: Int) -> Unit = { _, _ -> },
        /** `GmMu_StartFadeOut(GM_MU, unit, duration)`. */
        public val muFadeOut: (unit: Int, duration: Int) -> Unit = { _, _ -> },
        /** `GmUnitFadeExists()`. */
        public val unitFadeExists: () -> Boolean = { false },
        /** `sub_80BDEB4(GM_MU, &input)`: starts a node-to-node move. */
        public val muMoveNodes: (WorldMapMu.MoveInput) -> Unit = {},
        /** `sub_80BDFA4(GM_MU, &input)`: starts a position-to-position move. */
        public val muMovePositions: (WorldMapMu.MoveInput2) -> Unit = {},
    )

    /** `struct Sub80C3378`: the input of [startType0] (the walk from node to node). */
    public class Type0Input(
        /** `wm_uid`: the unit's index. */
        public val unitId: Int,
        /** `unk_01`: meaning unknown ([GmapAutoMuProc.unk38]). */
        public val unk01: Int,
        /** `srcNode`: the node the walk starts at. */
        public val srcNode: Int,
        /** `dstNode`: the node the walk ends at. */
        public val dstNode: Int,
        /** `delay`: frames before the walk starts. */
        public val delay: Int,
        /** `speed`: see [GmapAutoMuProc.speed]. */
        public val speed: Int,
    )

    /** `struct Sub80C33D4`: the input of [startType1] (the walk between two positions). */
    public class Type1Input(
        /** `wm_uid`: the unit's index. */
        public val unitId: Int,
        /**
         * `unk_01`: meaning unknown ([GmapAutoMuProc.unk39]); `EventA8` passes the value its
         * decomp calls `acceleration`.
         */
        public val unk01: Int,
        /** `unk_02`: meaning unknown ([GmapAutoMuProc.unk38]; `EventA8` passes 0). */
        public val unk02: Int,
        /** `x1`: the start position, x. */
        public val x1: Int,
        /** `y1`: the start position, y. */
        public val y1: Int,
        /** `x2`: the end position, x. */
        public val x2: Int,
        /** `y2`: the end position, y. */
        public val y2: Int,
        /** `delay`: frames before the walk starts. */
        public val delay: Int,
        /** `speed`: see [GmapAutoMuProc.speed]. */
        public val speed: Int,
    )

    /**
     * `ProcScr_GmapAutoMu`: waits out `delay`; optionally fades the unit in (label 0); starts
     * the node walk (label 1) or the position walk (label 2); waits until the unit stops
     * (label 3); optionally fades it out (label 4); ends (label 5, where the end callback
     * places the unit at the destination).
     *
     * Transcribed from src/worldmap_automu.c: ProcScr_GmapAutoMu (not verified: no @VerifiesPort
     * test names it)
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_GmapAutoMu",
        listOf(
            name("Gmap Auto Mu"),
            mark(PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { gmapAutoMuOnEnd(it) }),
            yield(),
            repeat(ProcFunc { gmapAutoMuWaitInitialDelay(it) }),
            call(ProcFunc { gmapAutoMuGotoFadeInIfFlagged(it) }),
            call(ProcFunc { gmapAutoMuGotoMoveForKind(it) }),
            label(0),
            call(ProcFunc { gmapAutoMuStartFadeIn(it) }),
            repeat(ProcFunc { gmapAutoMuWaitForFadeEnd(it) }),
            call(ProcFunc { gmapAutoMuGotoMoveForKind(it) }),
            label(1),
            call(ProcFunc { gmapAutoMuStartNodeMove(it) }),
            goto(3),
            label(2),
            call(ProcFunc { gmapAutoMuStartPositionMove(it) }),
            label(3),
            repeat(ProcFunc { gmapAutoMuWaitForMoveEnd(it) }),
            call(ProcFunc { gmapAutoMuGotoFadeOutIfFlagged(it) }),
            goto(5),
            label(4),
            call(ProcFunc { gmapAutoMuStartFadeOut(it) }),
            repeat(ProcFunc { gmapAutoMuWaitForFadeEnd(it) }),
            label(5),
            end(),
        ),
    ) { GmapAutoMuProc() }

    /** The walk state of a [script] proc. */
    private fun state(proc: Proc): GmapAutoMuProc = proc.state as GmapAutoMuProc

    /**
     * Ported from src/worldmap_automu.c: GmapAutoMu_OnEnd
     *
     * Runs when the proc ends, whether it finished or `EndGmAutoMuFor` ended it: stops a movement
     * still running, ends any unit fade, puts the unit at its destination (node location or map
     * position) and hides it when flag bit 1 is set.
     *
     * Checked: the position runs' end position and hide. Not run: the movement still running at
     * the end and the node runs.
     */
    public fun gmapAutoMuOnEnd(proc: Proc) {
        val s = state(proc)
        if (hooks.muMoving(s.unitId)) {
            hooks.muStop(s.unitId)
        }
        hooks.endUnitFade()
        when (s.kind) {
            1 -> hooks.muSetPosition(s.unitId, s.dstA, s.dstY)
            else -> gmData.setUnitLocation(s.unitId, s.dstA and 0xFF)
        }
        if ((s.flags and 2) != 0) {
            hooks.hideUnit(s.unitId)
        }
    }

    /** Ported from src/worldmap_automu.c: GmapAutoMu_WaitInitialDelay: counts `delay` down. */
    public fun gmapAutoMuWaitInitialDelay(proc: Proc) {
        val s = state(proc)
        s.delay = (s.delay - 1).toShort().toInt()
        if (s.delay < 1) {
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/worldmap_automu.c: GmapAutoMu_80C31C4 (`gmapAutoMuGotoFadeInIfFlagged`
     * here)
     *
     * Jumps to label 0 (fade the unit in first) when flag bit 0 is set.
     */
    public fun gmapAutoMuGotoFadeInIfFlagged(proc: Proc) {
        if ((state(proc).flags and 1) != 0) {
            engine.goto(proc, 0)
        }
    }

    /**
     * Ported from src/worldmap_automu.c: GmapAutoMu_80C31E0 (`gmapAutoMuGotoFadeOutIfFlagged`
     * here)
     *
     * Jumps to label 4 (fade the unit out) when flag bit 1 is set.
     */
    public fun gmapAutoMuGotoFadeOutIfFlagged(proc: Proc) {
        if ((state(proc).flags and 2) != 0) {
            engine.goto(proc, 4)
        }
    }

    /**
     * Ported from src/worldmap_automu.c: GmapAutoMu_80C31FC (`gmapAutoMuGotoMoveForKind` here)
     *
     * Jumps to label 1 (node walk) for kind 0 and to label 2 (position walk) for kind 1.
     */
    public fun gmapAutoMuGotoMoveForKind(proc: Proc) {
        when (state(proc).kind) {
            1 -> engine.goto(proc, 2)
            else -> engine.goto(proc, 1)
        }
    }

    /**
     * Ported from src/worldmap_automu.c: GmapAutoMu_StartFadeIn: starts a 30 frame fade-in of the
     * unit and shows it.
     */
    public fun gmapAutoMuStartFadeIn(proc: Proc) {
        val unitId = state(proc).unitId
        hooks.muFadeIn(unitId, FADE_FRAMES)
        hooks.showUnit(unitId)
    }

    /** Ported from src/worldmap_automu.c: GmapAutoMu_StartFadeOut: a 30 frame fade-out. */
    public fun gmapAutoMuStartFadeOut(proc: Proc) {
        hooks.muFadeOut(state(proc).unitId, FADE_FRAMES)
    }

    /** Ported from src/worldmap_automu.c: GmapAutoMu_WaitForFadeEnd: waits for the unit fade. */
    public fun gmapAutoMuWaitForFadeEnd(proc: Proc) {
        if (!hooks.unitFadeExists()) {
            engine.breakProc(proc)
        }
    }

    /**
     * Transcribed from src/worldmap_automu.c: GmapAutoMu_80C3280 (`gmapAutoMuStartNodeMove`
     * here; not verified: no node run)
     *
     * Starts the map unit's node-to-node move: the literals 0xFF and 4 are `unk_03` / `unk_04`
     * of `struct UnknownSub80BDEB4`, meaning unknown.
     */
    public fun gmapAutoMuStartNodeMove(proc: Proc) {
        val s = state(proc)
        hooks.muMoveNodes(
            WorldMapMu.MoveInput(
                unk00 = s.unitId,
                unk01 = s.unk38,
                unk02 = (s.flags shr 2) and 1,
                unk03 = 0xFF,
                unk04 = 4,
                unk06 = s.srcA,
                unk08 = s.dstA,
                unk0a = 0,
                unk0c = s.speed,
            ),
        )
    }

    /**
     * Ported from src/worldmap_automu.c: GmapAutoMu_80C32E4 (`gmapAutoMuStartPositionMove`
     * here)
     *
     * Starts the map unit's position-to-position move; 0xFF and 4 are `unk_04` / `unk_05` of
     * `struct UnknownSub80BDFA4`, meaning unknown.
     */
    public fun gmapAutoMuStartPositionMove(proc: Proc) {
        val s = state(proc)
        hooks.muMovePositions(
            WorldMapMu.MoveInput2(
                unk00 = s.unitId,
                unk01 = s.unk39,
                unk02 = s.unk38,
                unk03 = (s.flags shr 2) and 1,
                unk04 = 0xFF,
                unk05 = 4,
                unk06 = s.srcA,
                unk08 = s.srcY,
                unk0a = s.dstA,
                unk0c = s.dstY,
                unk0e = 0,
                unk10 = s.speed,
            ),
        )
    }

    /**
     * Ported from src/worldmap_automu.c: GmapAutoMu_80C3350 (`gmapAutoMuWaitForMoveEnd` here):
     * waits until the map unit's movement has stopped.
     */
    public fun gmapAutoMuWaitForMoveEnd(proc: Proc) {
        if (!hooks.muMoving(state(proc).unitId)) {
            engine.breakProc(proc)
        }
    }

    /** The `parent != NULL` split shared by both starters: blocks [parent] or starts in tree 3. */
    private fun startProc(parent: Proc?): Proc? =
        if (parent != null) {
            engine.startBlocking(script, parent)
        } else {
            engine.start(script, PROC_TREE_3)
        }

    /**
     * Transcribed from src/worldmap_automu.c: StartGmapAutoMu_Type0 (not verified: no node run)
     *
     * Starts a node-to-node walk; blocks [parent] when it is not null. [flag] becomes
     * [GmapAutoMuProc.flags].
     */
    public fun startType0(input: Type0Input, flag: Int, parent: Proc?): Proc? {
        val proc = startProc(parent) ?: return null
        val s = state(proc)
        s.kind = 0
        s.flags = flag and 0xFF
        s.unitId = input.unitId
        s.unk38 = input.unk01
        s.srcA = input.srcNode
        s.dstA = input.dstNode
        s.delay = input.delay
        s.speed = input.speed
        return proc
    }

    /**
     * Transcribed from src/worldmap_automu.c: StartGmapAutoMu_Type1 (not verified: unk_38/unk_39
     * hold equal values in the capture, so a swap is invisible)
     *
     * Starts a position-to-position walk; blocks [parent] when it is not null. [flag] becomes
     * [GmapAutoMuProc.flags].
     */
    public fun startType1(input: Type1Input, flag: Int, parent: Proc?): Proc? {
        val proc = startProc(parent) ?: return null
        val s = state(proc)
        s.kind = 1
        s.flags = flag and 0xFF
        s.unitId = input.unitId
        s.unk39 = input.unk01
        s.unk38 = input.unk02
        s.srcA = input.x1
        s.srcY = input.y1
        s.dstA = input.x2
        s.dstY = input.y2
        s.delay = input.delay
        s.speed = input.speed
        return proc
    }

    /**
     * Transcribed from src/worldmap_automu.c: EndGmAutoMuFor (not verified: no @VerifiesPort test
     * names it)
     *
     * Ends the walk of unit [index]; [index] < 0 ends all.
     */
    public fun endFor(index: Int) {
        if (index < 0) {
            engine.endEach(script)
            return
        }
        var proc = engine.find(script)
        while (proc != null) {
            if (state(proc).unitId == index) {
                engine.end(proc)
                return
            }
            proc = engine.findAfter(script, proc)
        }
    }

    /**
     * Ported from src/worldmap_automu.c: IsGmAutoMuActiveFor
     *
     * Whether unit [index] is walking; [index] < 0 asks for any (the form the capture runs); a
     * unit index is not run.
     */
    public fun isActiveFor(index: Int): Boolean {
        if (index < 0) return engine.find(script) != null
        var proc = engine.find(script)
        while (proc != null) {
            if (state(proc).unitId == index) return true
            proc = engine.findAfter(script, proc)
        }
        return false
    }

    private companion object {
        /** `PROC_MARK_WMSTUFF`. */
        const val PROC_MARK_WMSTUFF = 8

        /** `PROC_TREE_3`: the root used without a parent. */
        const val PROC_TREE_3 = 3

        /** The literal 30 the fade-in and fade-out pass to `GmMu_StartFadeIn` / `Out`. */
        const val FADE_FRAMES = 30
    }
}
