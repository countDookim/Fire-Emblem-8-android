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

/** `struct GMapAutoMuProc` past `PROC_HEADER`. */
public class GmapAutoMuProc {
    /** `kind` (+0x29): 0 moves along nodes, 1 moves between positions. */
    public var kind: Int = 0

    /** `flags` (+0x2A): bit 0 fade in first, bit 1 fade out at the end, bit 2 a MU flag. */
    public var flags: Int = 0

    /** `unitId` (+0x2C, `u16`). */
    public var unitId: Int = 0

    /** `target.src` (+0x30): the source node, or the x of the source position (`s16`). */
    public var srcA: Int = 0

    /** `target.src.pos.y` (+0x32, `s16`), unused for a node. */
    public var srcY: Int = 0

    /** `target.dst` (+0x34): the destination node, or the x of the destination (`s16`). */
    public var dstA: Int = 0

    /** `target.dst.pos.y` (+0x36, `s16`), unused for a node. */
    public var dstY: Int = 0

    /** `unk_38` (+0x38). */
    public var unk38: Int = 0

    /** `unk_39` (+0x39). */
    public var unk39: Int = 0

    /** `speed` (+0x3A, `s16`). */
    public var speed: Int = 0

    /** `delay` (+0x3C, `s16`). */
    public var delay: Int = 0
}

/**
 * `src/worldmap_automu.c`: the proc that walks one world map unit along a node path or between
 * two positions, with optional fade in / fade out.
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
    /** The callees outside `worldmap_automu.c` (`GM_MU` is the caller's); default to nothing. */
    public class Hooks(
        /** `sub_80BE12C(GM_MU, unit)`: the unit's movement is running. */
        public val muMoving: (unit: Int) -> Boolean = { false },
        /** `sub_80BE330(GM_MU, unit)`: stop the movement. */
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
        /** `sub_80BDEB4(GM_MU, &input)`. */
        public val muMoveNodes: (WorldMapMu.MoveInput) -> Unit = {},
        /** `sub_80BDFA4(GM_MU, &input)`. */
        public val muMovePositions: (WorldMapMu.MoveInput2) -> Unit = {},
    )

    /** `struct Sub80C3378`: the input of [startType0]. */
    public class Type0Input(
        /** `wm_uid`. */
        public val unitId: Int,
        /** `unk_01`. */
        public val unk01: Int,
        /** `srcNode`. */
        public val srcNode: Int,
        /** `dstNode`. */
        public val dstNode: Int,
        /** `delay`. */
        public val delay: Int,
        /** `speed`. */
        public val speed: Int,
    )

    /** `struct Sub80C33D4`: the input of [startType1]. */
    public class Type1Input(
        /** `wm_uid`. */
        public val unitId: Int,
        /** `unk_01`. */
        public val unk01: Int,
        /** `unk_02`. */
        public val unk02: Int,
        /** `x1`. */
        public val x1: Int,
        /** `y1`. */
        public val y1: Int,
        /** `x2`. */
        public val x2: Int,
        /** `y2`. */
        public val y2: Int,
        /** `delay`. */
        public val delay: Int,
        /** `speed`. */
        public val speed: Int,
    )

    /**
     * `ProcScr_GmapAutoMu`.
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
            call(ProcFunc { gmapAutoMu80C31C4(it) }),
            call(ProcFunc { gmapAutoMu80C31FC(it) }),
            label(0),
            call(ProcFunc { gmapAutoMuStartFadeIn(it) }),
            repeat(ProcFunc { gmapAutoMuWaitForFadeEnd(it) }),
            call(ProcFunc { gmapAutoMu80C31FC(it) }),
            label(1),
            call(ProcFunc { gmapAutoMu80C3280(it) }),
            goto(3),
            label(2),
            call(ProcFunc { gmapAutoMu80C32E4(it) }),
            label(3),
            repeat(ProcFunc { gmapAutoMu80C3350(it) }),
            call(ProcFunc { gmapAutoMu80C31E0(it) }),
            goto(5),
            label(4),
            call(ProcFunc { gmapAutoMuStartFadeOut(it) }),
            repeat(ProcFunc { gmapAutoMuWaitForFadeEnd(it) }),
            label(5),
            end(),
        ),
    ) { GmapAutoMuProc() }

    private fun state(proc: Proc): GmapAutoMuProc = proc.state as GmapAutoMuProc

    /**
     * Transcribed from src/worldmap_automu.c: GmapAutoMu_OnEnd (not verified: no @VerifiesPort test
     * names it)
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

    /** Ported from src/worldmap_automu.c: GmapAutoMu_WaitInitialDelay */
    public fun gmapAutoMuWaitInitialDelay(proc: Proc) {
        val s = state(proc)
        s.delay = (s.delay - 1).toShort().toInt()
        if (s.delay < 1) {
            engine.breakProc(proc)
        }
    }

    /** Ported from src/worldmap_automu.c: GmapAutoMu_80C31C4 */
    public fun gmapAutoMu80C31C4(proc: Proc) {
        if ((state(proc).flags and 1) != 0) {
            engine.goto(proc, 0)
        }
    }

    /** Ported from src/worldmap_automu.c: GmapAutoMu_80C31E0 */
    public fun gmapAutoMu80C31E0(proc: Proc) {
        if ((state(proc).flags and 2) != 0) {
            engine.goto(proc, 4)
        }
    }

    /** Ported from src/worldmap_automu.c: GmapAutoMu_80C31FC */
    public fun gmapAutoMu80C31FC(proc: Proc) {
        when (state(proc).kind) {
            1 -> engine.goto(proc, 2)
            else -> engine.goto(proc, 1)
        }
    }

    /** Transcribed from src/worldmap_automu.c: GmapAutoMu_StartFadeIn (not verified: ShowGmUnit
     * is not observable) */
    public fun gmapAutoMuStartFadeIn(proc: Proc) {
        val unitId = state(proc).unitId
        hooks.muFadeIn(unitId, FADE_FRAMES)
        hooks.showUnit(unitId)
    }

    /** Ported from src/worldmap_automu.c: GmapAutoMu_StartFadeOut */
    public fun gmapAutoMuStartFadeOut(proc: Proc) {
        hooks.muFadeOut(state(proc).unitId, FADE_FRAMES)
    }

    /** Ported from src/worldmap_automu.c: GmapAutoMu_WaitForFadeEnd */
    public fun gmapAutoMuWaitForFadeEnd(proc: Proc) {
        if (!hooks.unitFadeExists()) {
            engine.breakProc(proc)
        }
    }

    /** Transcribed from src/worldmap_automu.c: GmapAutoMu_80C3280 (not verified: no node run) */
    public fun gmapAutoMu80C3280(proc: Proc) {
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
     * Transcribed from src/worldmap_automu.c: GmapAutoMu_80C32E4 (not verified: no @VerifiesPort
     * test names it)
     */
    public fun gmapAutoMu80C32E4(proc: Proc) {
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

    /** Ported from src/worldmap_automu.c: GmapAutoMu_80C3350 */
    public fun gmapAutoMu80C3350(proc: Proc) {
        if (!hooks.muMoving(state(proc).unitId)) {
            engine.breakProc(proc)
        }
    }

    private fun startProc(parent: Proc?): Proc? =
        if (parent != null) {
            engine.startBlocking(script, parent)
        } else {
            engine.start(script, PROC_TREE_3)
        }

    /**
     * Transcribed from src/worldmap_automu.c: StartGmapAutoMu_Type0 (not verified: no node run)
     *
     * Starts a node-to-node walk; blocks [parent] when it is not null.
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
     * Starts a position-to-position walk; blocks [parent] when it is not null.
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
     * names it); [index] < 0 ends all.
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
     * Transcribed from src/worldmap_automu.c: IsGmAutoMuActiveFor (not verified: no @VerifiesPort
     * test names it); [index] < 0 asks for any.
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
        const val PROC_MARK_WMSTUFF = 8
        const val PROC_TREE_3 = 3
        const val FADE_FRAMES = 30
    }
}
