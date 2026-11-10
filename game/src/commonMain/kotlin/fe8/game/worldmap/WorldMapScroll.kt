package fe8.game.worldmap

import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventConst.EV_STATE_SKIPPING
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.ScriptMemory
import gbafe.core.math.Spline
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.yield

/**
 * A word of flag bits that a scroll proc updates through a pointer (`WorldMapMainProc.scrolling`).
 */
public class GmScrollFlags {
    /** The flag bits. */
    public var value: Int = 0
}

/** `struct GmScrollInfo`: the arguments of [WorldMapScroll.startGmScrollManage]. */
public class GmScrollInfo(
    /** `flags`: the flag word to update, or null. */
    public var flags: GmScrollFlags?,
    /** `lock`: the bit of [flags] cleared at the start and set at the end. */
    public var lock: Int,
    /** `move_type`: the ease exponent of `_DivArm2`. */
    public var moveType: Int,
    /** `xsrc` (`s16`); negative values start from the current camera. */
    public var xsrc: Int,
    /** `ysrc` (`s16`). */
    public var ysrc: Int,
    /** `xdst` (`s16`); negative values keep the start x. */
    public var xdst: Int,
    /** `ydst` (`s16`). */
    public var ydst: Int,
    /** `duration` in frames. */
    public var duration: Int,
    /** `delay` in frames. */
    public var delay: Int,
)

/** The fields of `struct GMapScrollManageProc`. */
public class GmScrollManageProc {
    /** `xsrc`. */
    public var xsrc: Int = 0

    /** `ysrc`. */
    public var ysrc: Int = 0

    /** `xdst`. */
    public var xdst: Int = 0

    /** `ydst`. */
    public var ydst: Int = 0

    /** `move_type`. */
    public var moveType: Int = 0

    /** `duration`, in 0x1000 units (`frames << 12`). */
    public var duration: Int = 0

    /** `timer`, in 0x1000 units. */
    public var timer: Int = 0

    /** `delay`. */
    public var delay: Int = 0

    /** `wm_flag`: the flag word the scroll reports through, or null. */
    public var wmFlag: GmScrollFlags? = null

    /** `lock`: the bit of [wmFlag] that stops the scroll when set. */
    public var lock: Int = 0
}

/** The fields of `struct GMapMoveCursorProc`. */
public class GmMoveCursorProc {
    /** `timer`. */
    public var timer: Int = 0

    /** `xsrc`, 24.8 fixed point. */
    public var xsrc: Int = 0

    /** `ysrc`. */
    public var ysrc: Int = 0

    /** `xdst`. */
    public var xdst: Int = 0

    /** `ydst`. */
    public var ydst: Int = 0

    /** `xmove` (`xdst - xsrc`). */
    public var xmove: Int = 0

    /** `ymove`. */
    public var ymove: Int = 0

    /** `duration` in frames. */
    public var duration: Int = 0

    /** `move_type`: the ease exponent of `_DivArm1`. */
    public var moveType: Int = 0
}

/**
 * The world map camera and cursor scrolls of `src/worldmap_scrollmanage.c`, the centring helper
 * `GetWMCenteredCameraPosition` and the camera event commands 0x83-0x89 of `src/eventscr_gmap.c`.
 *
 * @param engine the proc scheduler
 * @param gmData `gGMData`
 * @param nodes `gWMNodeData`
 * @param hooks the unported callees
 */
public class WorldMapScroll(
    private val engine: ProcEngine,
    private val gmData: GmData,
    private val nodes: WmNodeData,
    private val hooks: Hooks = Hooks(),
) {
    /** Callees of `StartGmScroll` that are not ported yet. */
    public class Hooks(
        /**
         * `GM_MAIN` (`Proc_Find(ProcScr_WorldMapMain)`), or null when the world map is not running.
         */
        public val mainProc: () -> Proc? = { null },
        /** `GM_CURSOR->unk_32--`. */
        public val decrementCursorUnk32: () -> Unit = {},
    )

    /**
     * `ProcScr_GmMoveCursor`.
     *
     * Transcribed from src/worldmap_scrollmanage.c: ProcScr_GmMoveCursor (not verified: no
     * reference
     * capture of the prologue moves the cursor)
     */
    public val moveCursorScript: ProcScript = ProcScript(
        "ProcScr_GmMoveCursor",
        listOf(
            name("Gmap Move Cursor"),
            mark(PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { gmMoveCursorOnEnd(it) }),
            call(ProcFunc { gmMoveCursorOnInit(it) }),
            yield(),
            repeat(ProcFunc { gmMoveCursorOnLoop(it) }),
            end(),
        ),
    ) { GmMoveCursorProc() }

    /**
     * `ProcScr_GmScrollManage`.
     *
     * Transcribed from src/worldmap_scrollmanage.c: ProcScr_GmScrollManage (not verified: no
     * reference
     * capture of the prologue runs a camera scroll; its camera is constant)
     */
    public val scrollManageScript: ProcScript = ProcScript(
        "ProcScr_GmScrollManage",
        listOf(
            name("Gmap Scroll Manage"),
            mark(PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { gmScrollManageOnEnd(it) }),
            call(ProcFunc { gmScrollManageOnInit(it) }),
            repeat(ProcFunc { gmScrollManageOnLoop(it) }),
            end(),
        ),
    ) { GmScrollManageProc() }

    /**
     * Transcribed from src/worldmap_scrollmanage.c: GmMoveCursor_OnEnd (not verified: see
     * [moveCursorScript]).
     */
    public fun gmMoveCursorOnEnd(proc: Proc) {
        val s = proc.state as GmMoveCursorProc
        gmData.ix = s.xdst
        gmData.iy = s.ydst
    }

    /**
     * Transcribed from src/worldmap_scrollmanage.c: GmMoveCursor_OnInit (not verified: see
     * [moveCursorScript]).
     */
    public fun gmMoveCursorOnInit(proc: Proc) {
        (proc.state as GmMoveCursorProc).timer = 0
    }

    /**
     * Transcribed from src/worldmap_scrollmanage.c: GmMoveCursor_OnLoop (not verified: see
     * [moveCursorScript]).
     */
    public fun gmMoveCursorOnLoop(proc: Proc) {
        val s = proc.state as GmMoveCursorProc
        s.timer++
        if (s.timer < s.duration) {
            val coeff = Spline.divArm1(s.timer, s.duration, s.moveType)
            gmData.ix = s.xsrc + Spline.divArm(0x1000, s.xmove * coeff)
            gmData.iy = s.ysrc + Spline.divArm(0x1000, s.ymove * coeff)
        } else {
            gmData.ix = s.xdst
            gmData.iy = s.ydst
            engine.end(proc)
        }
    }

    /**
     * Transcribed from src/worldmap_scrollmanage.c: StartGmMoveCursor (not verified: see
     * [moveCursorScript])
     *
     * @param src start position in pixels as `(x, y)`, or null for the current cursor
     * @param dst end position in pixels, or null for the origin
     * @param parent the parent proc, or null for a tree 3 root
     */
    public fun startGmMoveCursor(
        src: Pair<Int, Int>?,
        dst: Pair<Int, Int>?,
        duration: Int,
        moveType: Int,
        parent: Proc?,
    ): Proc {
        val proc = if (parent != null) {
            engine.start(moveCursorScript, parent)
        } else {
            engine.start(moveCursorScript, PROC_TREE_3)
        }
        val s = proc.state as GmMoveCursorProc
        if (src == null) {
            s.xsrc = gmData.ix
            s.ysrc = gmData.iy
        } else {
            s.xsrc = src.first shl 8
            s.ysrc = src.second shl 8
        }
        if (dst == null) {
            s.xdst = 0
            s.ydst = 0
        } else {
            s.xdst = dst.first shl 8
            s.ydst = dst.second shl 8
        }
        s.xmove = s.xdst - s.xsrc
        s.ymove = s.ydst - s.ysrc
        s.duration = duration
        s.moveType = moveType
        return proc
    }

    /**
     * Transcribed from src/worldmap_scrollmanage.c: GmMoveCursorExists (not verified: see
     * [moveCursorScript]).
     */
    public fun gmMoveCursorExists(): Boolean = engine.find(moveCursorScript) != null

    /**
     * Transcribed from src/worldmap_scrollmanage.c: GmScrollManage_OnEnd (not verified: see
     * [scrollManageScript])
     */
    public fun gmScrollManageOnEnd(proc: Proc) {
        val s = proc.state as GmScrollManageProc
        gmData.xCamera = s.xdst
        gmData.yCamera = s.ydst
        s.wmFlag?.let { it.value = it.value or s.lock }
    }

    /**
     * Transcribed from src/worldmap_scrollmanage.c: GmScrollManage_OnInit (not verified: see
     * [scrollManageScript]).
     */
    public fun gmScrollManageOnInit(proc: Proc) {
        (proc.state as GmScrollManageProc).timer = 0
    }

    /**
     * Transcribed from src/worldmap_scrollmanage.c: GmScrollManage_OnLoop (not verified: see
     * [scrollManageScript]).
     */
    public fun gmScrollManageOnLoop(proc: Proc) {
        val s = proc.state as GmScrollManageProc
        if (s.delay > 0) {
            s.delay--
            return
        }
        val flags = checkNotNull(s.wmFlag)
        if (flags.value and s.lock != 0) s.timer = s.duration else s.timer += 0x1000
        if (s.timer < s.duration) {
            val coeff = Spline.divArm2(s.timer, s.duration, s.moveType)
            gmData.xCamera = (s.xsrc + Spline.divArm(0x1000, (s.xdst - s.xsrc) * coeff))
                .toShort().toInt()
            gmData.yCamera = (s.ysrc + Spline.divArm(0x1000, (s.ydst - s.ysrc) * coeff))
                .toShort().toInt()
        } else {
            engine.breakProc(proc)
        }
    }

    /**
     * Transcribed from src/worldmap_scrollmanage.c: StartGmScrollManage (not verified: see
     * [scrollManageScript])
     *
     * Starts a camera scroll as a child of [parent] and puts the camera on its start.
     */
    public fun startGmScrollManage(input: GmScrollInfo, parent: Proc?): Proc {
        val proc = if (parent != null) {
            engine.start(scrollManageScript, parent)
        } else {
            engine.start(scrollManageScript, PROC_TREE_3)
        }
        val s = proc.state as GmScrollManageProc
        if (input.xsrc < 0 || input.ysrc < 0) {
            input.xsrc = gmData.xCamera
            input.ysrc = gmData.yCamera
        }
        val src = input.xsrc and 0xFFFF
        s.xsrc = src
        s.ysrc = input.ysrc
        gmData.xCamera = src
        gmData.yCamera = s.ysrc
        s.xdst = if (input.xdst < 0) src else input.xdst
        s.ydst = input.ydst
        s.moveType = input.moveType
        s.duration = input.duration shl 12
        s.delay = input.delay
        s.wmFlag = input.flags
        s.wmFlag?.let {
            it.value = it.value and input.lock.inv()
            s.lock = input.lock
        }
        return proc
    }

    /**
     * Transcribed from src/worldmap_scrollmanage.c: GmScrollManageExist (not verified: see
     * [scrollManageScript]).
     */
    public fun gmScrollManageExist(): Boolean = engine.find(scrollManageScript) != null

    /**
     * Transcribed from src/worldmap_scrollmanage.c: StartGmScroll (not verified: see
     * [scrollManageScript])
     */
    public fun startGmScroll(
        xStart: Int,
        yStart: Int,
        xEnd: Int,
        yEnd: Int,
        speed: Int,
        delay: Int,
    ) {
        val main = checkNotNull(hooks.mainProc())
        val info = GmScrollInfo(
            flags = (main.state as WorldMapMainProc).scrolling,
            lock = 1,
            moveType = 1,
            xsrc = xStart,
            ysrc = yStart,
            xdst = xEnd,
            ydst = yEnd,
            duration = speed,
            delay = delay,
        )
        startGmScrollManage(info, main)
        hooks.decrementCursorUnk32()
    }

    /**
     * Transcribed from src/worldmap_scrollmanage.c: CheckGmScrolling (not verified: see
     * [scrollManageScript]).
     */
    public fun checkGmScrolling(): Boolean {
        val main = checkNotNull(hooks.mainProc())
        return (main.state as WorldMapMainProc).scrolling.value and 1 == 0
    }

    /**
     * Transcribed from src/worldmap_scrollmanage.c: EndGmScroll (not verified: see
     * [scrollManageScript]).
     */
    public fun endGmScroll() {
        val main = checkNotNull(hooks.mainProc())
        engine.endEach(scrollManageScript)
        val flags = (main.state as WorldMapMainProc).scrolling
        flags.value = flags.value or 1
    }

    /**
     * Ported from src/worldmap_80C0834.c: GetWMCenteredCameraPosition
     *
     * The camera that centres the 240x160 screen on `(xIn, yIn)`, x clamped to 0..240 and y to
     * 0..160.
     *
     * @return the camera as `(x, y)`
     */
    public fun getWmCenteredCameraPosition(xIn: Int, yIn: Int): Pair<Int, Int> {
        var x = (xIn - DISPLAY_WIDTH / 2).toShort().toInt()
        if (x < 0) x = 0 else if (x > DISPLAY_WIDTH) x = DISPLAY_WIDTH
        var y = (yIn - DISPLAY_HEIGHT / 2).toShort().toInt()
        if (y < 0) y = 0 else if (y > DISPLAY_HEIGHT) y = DISPLAY_HEIGHT
        return x to y
    }

    /** Camera commands 0x83-0x89, to be registered on the event engine's high table. */
    public inner class CameraEvents(private val memory: ScriptMemory) {
        private fun arg(proc: EventEngineProc, index: Int): Int =
            memory.u16(proc.pEventCurrent + 2 * index)

        private fun sArg(proc: EventEngineProc, index: Int): Int =
            arg(proc, index).toShort().toInt()

        private fun skipping(proc: EventEngineProc): Boolean =
            proc.evStateBits and EV_STATE_SKIPPING != 0

        private fun unitCamera(unit: Int): Pair<Int, Int> {
            val node = gmData.unitLocation(unit)
            return getWmCenteredCameraPosition(nodes.x(node), nodes.y(node))
        }

        private fun scrollTo(proc: EventEngineProc, xStart: Int, yStart: Int, end: Pair<Int, Int>,
            speed: Int, delay: Int): Int {
            if (skipping(proc) || (speed == 0 && delay == 0)) {
                gmData.xCamera = end.first
                gmData.yCamera = end.second
                return EVC_ADVANCE_CONTINUE
            }
            startGmScroll(xStart, yStart, end.first, end.second, speed, delay)
            return EVC_ADVANCE_YIELD
        }

        /**
         * Transcribed from src/eventscr_gmap.c: Event83_WmSetCamera (not verified: the prologue
         * never
         * runs it)
         */
        public val event83SetCamera: EventHandler = EventHandler { proc ->
            gmData.xCamera = arg(proc, 2).toShort().toInt()
            gmData.yCamera = arg(proc, 3).toShort().toInt()
            EVC_ADVANCE_CONTINUE
        }

        /**
         * Transcribed from src/eventscr_gmap.c: Event84_WmSetCameraOntoNode (not verified: the
         * prologue never
         * runs it)
         */
        public val event84SetCameraOntoNode: EventHandler = EventHandler { proc ->
            val node = arg(proc, 2) or (arg(proc, 3) shl 16)
            val (x, y) = getWmCenteredCameraPosition(nodes.x(node), nodes.y(node))
            gmData.xCamera = x
            gmData.yCamera = y
            EVC_ADVANCE_CONTINUE
        }

        /**
         * Ported from src/eventscr_gmap.c: Event85_WmSetCameraOntoUnit
         *
         * Puts the camera on the node where unit slot `arg` stands.
         */
        public val event85SetCameraOntoUnit: EventHandler = EventHandler { proc ->
            val (x, y) = unitCamera(arg(proc, 2) or (arg(proc, 3) shl 16))
            gmData.xCamera = x
            gmData.yCamera = y
            EVC_ADVANCE_CONTINUE
        }

        /**
         * Transcribed from src/eventscr_gmap.c: Event86_WmScrollCamera (not verified: the prologue
         * never
         * runs it)
         */
        public val event86ScrollCamera: EventHandler = EventHandler { proc ->
            scrollTo(
                proc, sArg(proc, 2), sArg(proc, 3), sArg(proc, 4) to sArg(proc, 5),
                sArg(proc, 6), sArg(proc, 7),
            )
        }

        /**
         * Transcribed from src/eventscr_gmap.c: Event87_WmScrollCameraOntoNode (not verified: the
         * prologue
         * never runs it)
         */
        public val event87ScrollCameraOntoNode: EventHandler = EventHandler { proc ->
            val node = arg(proc, 4)
            scrollTo(
                proc, sArg(proc, 2), sArg(proc, 3),
                getWmCenteredCameraPosition(nodes.x(node), nodes.y(node)),
                sArg(proc, 5), sArg(proc, 6),
            )
        }

        /**
         * Transcribed from src/eventscr_gmap.c: Event88_WmScrollCameraOntoUnit (not verified: the
         * prologue
         * never runs it)
         */
        public val event88ScrollCameraOntoUnit: EventHandler = EventHandler { proc ->
            scrollTo(
                proc, sArg(proc, 2), sArg(proc, 3), unitCamera(arg(proc, 4)),
                sArg(proc, 5), sArg(proc, 6),
            )
        }

        /**
         * Transcribed from src/eventscr_gmap.c: Event89_WmScrollWait (not verified: the prologue
         * never
         * runs it)
         */
        public val event89ScrollWait: EventHandler = EventHandler { proc ->
            if (skipping(proc)) {
                if (gmScrollManageExist()) endGmScroll()
                EVC_ADVANCE_CONTINUE
            } else if (!gmScrollManageExist()) {
                EVC_ADVANCE_YIELD
            } else {
                EVC_STOP_YIELD
            }
        }
    }

    /** Constants of `worldmap_scrollmanage.c`. */
    public companion object {
        private const val PROC_MARK_WMSTUFF = 8
        private const val PROC_TREE_3 = 3
        private const val DISPLAY_WIDTH = 240
        private const val DISPLAY_HEIGHT = 160
    }
}
