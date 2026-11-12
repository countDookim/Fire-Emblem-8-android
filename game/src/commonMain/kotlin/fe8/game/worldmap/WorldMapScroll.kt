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
 * A word of flag bits shared by pointer between the world map main proc and a camera scroll (the
 * `int *` of `GmScrollInfo.flags`; the main proc's copy is `WorldMapMainProc.scrolling`, offset
 * 0x30). `StartGmScroll` uses bit 0 as "scroll finished or cut short"; see
 * [WorldMapScroll.checkGmScrolling].
 */
public class GmScrollFlags {
    /** The flag bits. */
    public var value: Int = 0
}

/**
 * `struct GmScrollInfo` (include/worldmap.h): the request for one world map camera scroll, the
 * arguments of [WorldMapScroll.startGmScrollManage].
 */
public class GmScrollInfo(
    /** `flags`: the shared flag word the scroll reports through, or null for none. */
    public var flags: GmScrollFlags?,
    /**
     * `lock`: the bit mask of [flags] cleared when the scroll starts and set when it ends; setting
     * it from outside cuts the scroll short (see [GmScrollManageProc.lock]).
     */
    public var lock: Int,
    /** `move_type`: the `power` argument of `_DivArm2`, the ease curve exponent (0 = linear). */
    public var moveType: Int,
    /**
     * `xsrc` (`s16`): the camera x to start from. If either [xsrc] or [ysrc] is negative, both are
     * replaced by the current camera position.
     */
    public var xsrc: Int,
    /** `ysrc` (`s16`): the camera y to start from (see [xsrc]). */
    public var ysrc: Int,
    /** `xdst` (`s16`): the camera x to scroll to; a negative value keeps the start x. */
    public var xdst: Int,
    /** `ydst` (`s16`): the camera y to scroll to. */
    public var ydst: Int,
    /** `duration`: how many frames the scroll takes. */
    public var duration: Int,
    /** `delay`: how many frames to wait before the camera starts moving. */
    public var delay: Int,
)

/**
 * The fields of `struct GMapScrollManageProc` (include/worldmap.h): the state of one running
 * world map camera scroll, `ProcScr_GmScrollManage`.
 */
public class GmScrollManageProc {
    /** `xsrc` (`s16`): the camera x the scroll starts from. */
    public var xsrc: Int = 0

    /** `ysrc` (`s16`): the camera y the scroll starts from. */
    public var ysrc: Int = 0

    /** `xdst` (`s16`): the camera x the scroll ends on. */
    public var xdst: Int = 0

    /** `ydst` (`s16`): the camera y the scroll ends on. */
    public var ydst: Int = 0

    /** `move_type`: the `power` argument of `_DivArm2`, the ease curve exponent. */
    public var moveType: Int = 0

    /** `duration`, in 0x1000 units (`frames << 12`), the same units as [timer]. */
    public var duration: Int = 0

    /** `timer`: elapsed time, +0x1000 per frame. */
    public var timer: Int = 0

    /** `delay`: frames left to wait before the camera starts moving. */
    public var delay: Int = 0

    /** `wm_flag`: the shared flag word the scroll reports through, or null. */
    public var wmFlag: GmScrollFlags? = null

    /**
     * `lock`: the bit mask of [wmFlag] that, once set, makes the scroll jump to its end on the
     * next frame; also set by the scroll itself when it ends.
     */
    public var lock: Int = 0
}

/**
 * The fields of `struct GMapMoveCursorProc` (include/worldmap.h): the state of one running world
 * map cursor slide, `ProcScr_GmMoveCursor`.
 */
public class GmMoveCursorProc {
    /** `timer` (`s16`): frames elapsed. */
    public var timer: Int = 0

    /** `xsrc`: the cursor x the slide starts from, pixels in 24.8 fixed point (`gGMData.ix`). */
    public var xsrc: Int = 0

    /** `ysrc`: the cursor y the slide starts from, 24.8 fixed point (`gGMData.iy`). */
    public var ysrc: Int = 0

    /** `xdst`: the cursor x the slide ends on, 24.8 fixed point. */
    public var xdst: Int = 0

    /** `ydst`: the cursor y the slide ends on, 24.8 fixed point. */
    public var ydst: Int = 0

    /** `xmove` (`xdst - xsrc`): the whole distance in x. */
    public var xmove: Int = 0

    /** `ymove` (`ydst - ysrc`): the whole distance in y. */
    public var ymove: Int = 0

    /** `duration` (`s16`): how many frames the slide takes. */
    public var duration: Int = 0

    /** `move_type` (`u8`): the `power` argument of `_DivArm1`, the ease curve exponent. */
    public var moveType: Int = 0
}

/**
 * The world map camera and cursor motion of `src/worldmap_scrollmanage.c`: the eased camera scroll
 * (`ProcScr_GmScrollManage`, used by the camera event commands) and the eased cursor slide
 * (`ProcScr_GmMoveCursor`). It also holds the centring helper `GetWMCenteredCameraPosition`
 * and the world map camera event commands 0x83-0x89 of `src/eventscr_gmap.c`
 * (`EV_CMD_WM_SETCAM` to `EV_CMD_WM_WAITFORCAM`).
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
    /** Callees of `StartGmScroll` and friends that are not ported yet. */
    public class Hooks(
        /**
         * `GM_MAIN` (`Proc_Find(ProcScr_WorldMapMain)`), or null when the world map is not
         * running. [checkNotNull] makes the callers fail in that case, where the decomp would
         * read through a null pointer.
         */
        public val mainProc: () -> Proc? = { null },
        /**
         * `GM_CURSOR->unk_32--` (`GmapCursorProc.unk_32`). `GmapCursor_Loop` overwrites that
         * field from `gGMData.sprite_disp` every frame and draws the cursor sprite only while it
         * is positive; what the decrement is for is unknown.
         */
        public val decrementCursorUnk32: () -> Unit = {},
    )

    /**
     * `ProcScr_GmMoveCursor`: slides the world map cursor (`gGMData.ix`/`iy`) from one point to
     * another over a number of frames, ending on the exact target.
     *
     * Transcribed from src/worldmap_scrollmanage.c: ProcScr_GmMoveCursor (not verified: no
     * reference capture of the prologue moves the cursor)
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
     * `ProcScr_GmScrollManage`: scrolls the world map camera (`gGMData.xCamera`/`yCamera`) between
     * two points with an eased curve; it ends by breaking out of its loop, which runs
     * [gmScrollManageOnEnd] to land exactly on the target.
     *
     * Transcribed from src/worldmap_scrollmanage.c: ProcScr_GmScrollManage (not verified: no
     * reference capture of the prologue runs a camera scroll; its camera is constant)
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
     * End callback of the cursor slide: puts the cursor on the target, whether the slide finished
     * or was ended early.
     *
     * Transcribed from src/worldmap_scrollmanage.c: GmMoveCursor_OnEnd (not verified: see
     * [moveCursorScript]).
     */
    public fun gmMoveCursorOnEnd(proc: Proc) {
        val s = proc.state as GmMoveCursorProc
        gmData.ix = s.xdst
        gmData.iy = s.ydst
    }

    /**
     * Starts the cursor slide's frame counter at 0.
     *
     * Transcribed from src/worldmap_scrollmanage.c: GmMoveCursor_OnInit (not verified: see
     * [moveCursorScript]).
     */
    public fun gmMoveCursorOnInit(proc: Proc) {
        (proc.state as GmMoveCursorProc).timer = 0
    }

    /**
     * One frame of the cursor slide: moves the cursor along the straight line to the target by
     * the eased fraction `_DivArm1` gives, and ends the proc on the last frame.
     *
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
     * Starts a cursor slide on the world map (the decomp's `struct Vec2` arguments are the
     * [Pair]s here).
     *
     * Transcribed from src/worldmap_scrollmanage.c: StartGmMoveCursor (not verified: see
     * [moveCursorScript])
     *
     * @param src start position in pixels as `(x, y)`, or null for the current cursor
     * @param dst end position in pixels, or null for pixel (0, 0)
     * @param duration how many frames the slide takes
     * @param moveType the ease curve exponent passed to `_DivArm1` (0 = linear)
     * @param parent the parent proc, or null for a `PROC_TREE_3` root
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
     * Whether a cursor slide is running.
     *
     * Transcribed from src/worldmap_scrollmanage.c: GmMoveCursorExists (not verified: see
     * [moveCursorScript]).
     */
    public fun gmMoveCursorExists(): Boolean = engine.find(moveCursorScript) != null

    /**
     * End callback of the camera scroll: puts the camera on the target and sets the lock bit in
     * the shared flag word, so a waiter (see [checkGmScrolling]) sees the scroll is over. Runs
     * when the scroll finishes and also when it is ended early ([endGmScroll]).
     *
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
     * Starts the camera scroll's timer at 0.
     *
     * Transcribed from src/worldmap_scrollmanage.c: GmScrollManage_OnInit (not verified: see
     * [scrollManageScript]).
     */
    public fun gmScrollManageOnInit(proc: Proc) {
        (proc.state as GmScrollManageProc).timer = 0
    }

    /**
     * One frame of the camera scroll: waits out the delay, then advances the timer by 0x1000 and
     * moves the camera by the eased fraction `_DivArm2` gives, breaking out of the loop once the
     * timer reaches the duration. If the lock bit of the shared flag word is set from outside, the
     * timer jumps to the duration and the scroll finishes this frame.
     *
     * The flag word is read without a null check in the decomp too, so a scroll started without
     * one would crash there; here it fails the [checkNotNull].
     *
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
     * Starts a camera scroll as a child of [parent] (a `PROC_TREE_3` root when null) and puts the
     * camera on its start. A negative source x or y in [input] is replaced by the current camera,
     * in [input] itself as in the decomp, and a negative target x keeps the start x.
     *
     * Transcribed from src/worldmap_scrollmanage.c: StartGmScrollManage (not verified: see
     * [scrollManageScript])
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
     * Whether a camera scroll is running (the event command that waits for the camera polls it).
     *
     * Transcribed from src/worldmap_scrollmanage.c: GmScrollManageExist (not verified: see
     * [scrollManageScript]).
     */
    public fun gmScrollManageExist(): Boolean = engine.find(scrollManageScript) != null

    /**
     * Starts a world map camera scroll under the world map main proc, as the scroll event
     * commands do: the finished bit is bit 0 of the main proc's `scrolling` word (`lock` = 1), and
     * the ease exponent is 1 (`move_type`).
     *
     * @param xStart the camera x to start from (negative: the current camera)
     * @param yStart the camera y to start from
     * @param xEnd the camera x to scroll to
     * @param yEnd the camera y to scroll to
     * @param speed how many frames the scroll takes (`GmScrollInfo.duration`)
     * @param delay how many frames to wait before the camera starts moving
     *
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
     * Whether a scroll started by [startGmScroll] is still running: bit 0 of the main proc's
     * `scrolling` word is clear until the scroll ends or [endGmScroll] cuts it short.
     *
     * Transcribed from src/worldmap_scrollmanage.c: CheckGmScrolling (not verified: see
     * [scrollManageScript]).
     */
    public fun checkGmScrolling(): Boolean {
        val main = checkNotNull(hooks.mainProc())
        return (main.state as WorldMapMainProc).scrolling.value and 1 == 0
    }

    /**
     * Cuts the running camera scroll short: ends every scroll proc, which jumps the camera to the
     * target through [gmScrollManageOnEnd], and sets bit 0 of the main proc's `scrolling` word.
     * Used when the player skips the world map event.
     *
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
     * The camera position that centres the 240x160 screen on the world map point `(xIn, yIn)`: x
     * and y are each clamped to 0..`DISPLAY_WIDTH` and 0..`DISPLAY_HEIGHT` (the decomp reuses the
     * screen size as the upper bound; why those values is unknown).
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

    /**
     * The world map camera event commands 0x83-0x89 (`Event83_WmSetCamera` to
     * `Event89_WmScrollWait`), to be registered on the event engine's high table.
     *
     * @param memory the script memory the event commands read their arguments from
     */
    public inner class CameraEvents(private val memory: ScriptMemory) {
        /**
         * The unsigned halfword [index] of the running command; index 0 is the command word, so
         * `EVT_CMD_ARGV(cmd)[n]` is index `n + 1`.
         */
        private fun arg(proc: EventEngineProc, index: Int): Int =
            memory.u16(proc.pEventCurrent + 2 * index)

        /** The same halfword as [arg] read as `s16`. */
        private fun sArg(proc: EventEngineProc, index: Int): Int =
            arg(proc, index).toShort().toInt()

        /** `EVENT_IS_SKIPPING`: the player is skipping the event. */
        private fun skipping(proc: EventEngineProc): Boolean =
            proc.evStateBits and EV_STATE_SKIPPING != 0

        /**
         * The centred camera for the node a world map unit stands on (`gGMData.units[unit]`'s
         * `location` looked up in `gWMNodeData`).
         */
        private fun unitCamera(unit: Int): Pair<Int, Int> {
            val node = gmData.unitLocation(unit)
            return getWmCenteredCameraPosition(nodes.x(node), nodes.y(node))
        }

        /**
         * The tail the three scroll commands share: with a skipped event, or when both [speed] and
         * [delay] are 0, the camera jumps to [end] and the event goes on; otherwise a scroll
         * starts and the event yields.
         *
         * @return the event command result code (`EVC_*`)
         */
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
         * `EV_CMD_WM_SETCAM`: puts the world map camera on the given x, y.
         *
         * Transcribed from src/eventscr_gmap.c: Event83_WmSetCamera (not verified: the prologue
         * never runs it)
         */
        public val event83SetCamera: EventHandler = EventHandler { proc ->
            gmData.xCamera = arg(proc, 2).toShort().toInt()
            gmData.yCamera = arg(proc, 3).toShort().toInt()
            EVC_ADVANCE_CONTINUE
        }

        /**
         * `EV_CMD_WM_84`: puts the world map camera on a node (32-bit node id argument), centred.
         *
         * Transcribed from src/eventscr_gmap.c: Event84_WmSetCameraOntoNode (not verified: the
         * prologue never runs it)
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
         * `EV_CMD_WM_CENTERCAMONLORD`: puts the camera on the node where the given world map unit
         * (32-bit `gGMData.units` slot argument) stands, centred.
         */
        public val event85SetCameraOntoUnit: EventHandler = EventHandler { proc ->
            val (x, y) = unitCamera(arg(proc, 2) or (arg(proc, 3) shl 16))
            gmData.xCamera = x
            gmData.yCamera = y
            EVC_ADVANCE_CONTINUE
        }

        /**
         * `EV_CMD_WM_MOVECAM`: scrolls the camera from a start x, y to an end x, y over `speed`
         * frames after `delay` frames. It does not wait for the scroll to end (the event only
         * yields for one frame); [event89ScrollWait] does the waiting.
         *
         * Transcribed from src/eventscr_gmap.c: Event86_WmScrollCamera (not verified: the prologue
         * never runs it)
         */
        public val event86ScrollCamera: EventHandler = EventHandler { proc ->
            scrollTo(
                proc, sArg(proc, 2), sArg(proc, 3), sArg(proc, 4) to sArg(proc, 5),
                sArg(proc, 6), sArg(proc, 7),
            )
        }

        /**
         * `EV_CMD_WM_MOVECAMTO`: like [event86ScrollCamera], but the end is the centred camera for
         * a node id.
         *
         * Transcribed from src/eventscr_gmap.c: Event87_WmScrollCameraOntoNode (not verified: the
         * prologue never runs it)
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
         * `EV_CMD_WM_88`: like [event86ScrollCamera], but the end is the centred camera for the
         * node a world map unit stands on.
         *
         * Transcribed from src/eventscr_gmap.c: Event88_WmScrollCameraOntoUnit (not verified: the
         * prologue never runs it)
         */
        public val event88ScrollCameraOntoUnit: EventHandler = EventHandler { proc ->
            scrollTo(
                proc, sArg(proc, 2), sArg(proc, 3), unitCamera(arg(proc, 4)),
                sArg(proc, 5), sArg(proc, 6),
            )
        }

        /**
         * `EV_CMD_WM_WAITFORCAM`: waits for the camera scroll to finish. While one runs it stops
         * the event on this command with a yield (`EVC_STOP_YIELD`) so it is polled again next
         * frame; with a skipped event it ends the scroll and goes on.
         *
         * Transcribed from src/eventscr_gmap.c: Event89_WmScrollWait (not verified: the prologue
         * never runs it)
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

    /** Constants this file takes from the decomp headers. */
    public companion object {
        /** `PROC_MARK_WMSTUFF` (include/proc.h): the proc mark of all world map procs. */
        private const val PROC_MARK_WMSTUFF = 8

        /** `PROC_TREE_3` (include/proc.h): the root a parentless proc is attached to. */
        private const val PROC_TREE_3 = 3

        /** `DISPLAY_WIDTH` (include/gba/defines.h): the GBA screen width in pixels. */
        private const val DISPLAY_WIDTH = 240

        /** `DISPLAY_HEIGHT` (include/gba/defines.h): the GBA screen height in pixels. */
        private const val DISPLAY_HEIGHT = 160
    }
}
