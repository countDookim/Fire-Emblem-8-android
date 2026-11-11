package fe8.game.bm

import fe8.game.RomView
import fe8.game.ui.ItemIcon
import gbafe.proc.Proc
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcCmd
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.whileExists
import gbafe.proc.ProcScripts.whileTrue
import gbafe.proc.ProcScripts.yield
import gbafe.render.BgHardware
import gbafe.render.LcdControl
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextSystem

/**
 * The scratch tilemaps `gUiTmScratchA` and `gUiTmScratchB` (0x500 bytes each, 32 entries per row)
 * the player-phase side windows draw into before they copy a part to BG0 and BG1.
 *
 * The three windows (terrain, unit, goal) share the pair: one [PlayerInterface] owns it and later
 * window ports take the same instance.
 */
public class UiTmScratch {
    /** `gUiTmScratchA`: staging for the BG0 side. */
    public val a: ShortArray = ShortArray(SIZE)

    /** `gUiTmScratchB`: staging for the BG1 side. */
    public val b: ShortArray = ShortArray(SIZE)

    /** Sizes. */
    public companion object {
        /** Entries per buffer: 0x500 bytes of `u16`. */
        public const val SIZE: Int = 0x280
    }
}

/**
 * `struct PlayerInterfaceProc` past `PROC_HEADER`, restricted to the members the terrain window
 * uses (src/player_interface.c; the burst display's members are not modelled).
 *
 * The three side windows run the same struct. A fresh state is all zero; the original's
 * `Proc_Start` leaves the old contents of the proc slot in place, which matters only for members
 * the first pass reads before they are set (see [PlayerInterface.terrainOnSideChange]).
 */
public class PlayerInterfaceState {
    /** `texts[0]` (0x2C): the window's text strip (the terrain window draws the name here). */
    public val text: Text = Text()

    /** `xCursorPrev` (u8, 0x4C). */
    public var xCursorPrev: Int = 0

    /** `yCursorPrev` (u8, 0x4D). */
    public var yCursorPrev: Int = 0

    /** `xCursor` (u8, 0x4E). */
    public var xCursor: Int = 0

    /** `yCursor` (u8, 0x4F). */
    public var yCursor: Int = 0

    /** `cursorQuadrant` (s8, 0x50): the quadrant of the screen the map cursor is in (0..3). */
    public var cursorQuadrant: Int = 0

    /** `hideContents` (s8, 0x55): the window's contents are not drawn (it is sliding). */
    public var hideContents: Boolean = false

    /** `isRetracting` (s8, 0x56): the window is sliding out. */
    public var isRetracting: Boolean = false

    /** `windowQuadrant` (s8, 0x57): the screen corner the window occupies, -1 if none. */
    public var windowQuadrant: Int = 0

    /** `showHideClock` (0x58): frame counter of the slide in or out. */
    public var showHideClock: Int = 0
}

/**
 * The player-phase side windows of src/player_interface.c, as far as the terrain window and the
 * window maker: `gProcScr_TerrainDisplay` (the terrain name, defence and avoid box that slides in
 * beside the map cursor) and `gProcScr_SideWindowMaker` with `StartPlayerPhaseSideWindows` and
 * `EndPlayerPhaseSideWindows`.
 *
 * Graphics, palettes and terrain names are read from the ROM at runtime. The unit mini-box and the
 * goal window are other ports; [Hooks] start, end and query them until they exist. Nothing
 * allocates per frame.
 */
public class PlayerInterface(
    private val engine: ProcEngine,
    private val bm: BmSt,
    private val playSt: PlaySt,
    private val rom: RomView,
    private val hw: BgHardware,
    private val text: TextSystem,
    private val messages: MsgStrings,
    /** `ProcScr_CamMove`: the side windows wait while it exists. */
    private val camMoveScript: ProcScript,
    /** `gUiTmScratchA` and `gUiTmScratchB`, shared by all side windows. */
    public val scratch: UiTmScratch = UiTmScratch(),
) {
    /** Services outside the ported part of the file; the defaults do nothing. */
    public class Hooks {
        /** Stand-in for `DoesBMXFADEExist` (false) until M6-B. */
        public var doesBmxFadeExist: ProcBoolFunc = ProcBoolFunc { false }

        /** Stand-in for `gBmMapTerrain[y][x]` until PP-17 wires the map. */
        public var terrainAt: (x: Int, y: Int) -> Int = { _, _ -> 0 }

        /** Stand-in for `GetObstacleHpAt` (bmtrick.c) until M6-H. */
        public var obstacleHpAt: (x: Int, y: Int) -> Int = { _, _ -> 0 }

        /**
         * Stand-in until PP-15 for the `unitDisplayType` branches of `InitPlayerPhaseInterface`:
         * starts `gProcScr_UnitDisplay_MinimugBox` or `gProcScr_UnitDisplay_Burst` in tree 3.
         */
        public var startUnitDisplay: () -> Unit = {}

        /**
         * Stand-in until PP-15 for `Proc_EndEach` of `gProcScr_UnitDisplay_MinimugBox` and
         * `gProcScr_UnitDisplay_Burst`.
         */
        public var endUnitDisplay: () -> Unit = {}

        /**
         * Stand-in until PP-15 for `Proc_Find(gProcScr_UnitDisplay_MinimugBox)`: its
         * `windowQuadrant`, or [NO_WINDOW] where the proc does not exist.
         */
        public var unitDisplayWindowQuadrant: () -> Int = { NO_WINDOW }

        /**
         * Stand-in until PP-16 for the goal branch of `InitPlayerPhaseInterface`: starts
         * `gProcScr_GoalDisplay` in tree 3 if the config and `EVFLAG_OBJWINDOW_DISABLE` allow it.
         */
        public var startGoalDisplay: () -> Unit = {}

        /** Stand-in until PP-16 for `Proc_EndEach(gProcScr_GoalDisplay)`. */
        public var endGoalDisplay: () -> Unit = {}

        /**
         * Stand-in until PP-16 for `Proc_Find(gProcScr_GoalDisplay)`: its `windowQuadrant`, or
         * [NO_WINDOW] where the proc does not exist.
         */
        public var goalDisplayWindowQuadrant: () -> Int = { NO_WINDOW }

        /**
         * Stand-in until M6-I for `Proc_Start(gProcScr_PrepMap_MenuButtonDisplay)`, run instead
         * of the goal window while the preparations screen is up.
         */
        public var startPrepMenuButtonDisplay: () -> Unit = {}

        /** Stand-in until M6-I for `Proc_EndEach(gProcScr_PrepMap_MenuButtonDisplay)`. */
        public var endPrepMenuButtonDisplay: () -> Unit = {}
    }

    /** Host services. */
    public val hooks: Hooks = Hooks()

    /** `gNumberStr`: the digits of the number being drawn, space padded on the left. */
    private val numberStr = ByteArray(NUMBER_STR_SIZE)

    private val proc15 = ProcCmd(0x15)
    private val initFn = ProcFunc { terrainInit(it) }
    private val sideChangeFn = ProcFunc { terrainOnSideChange(it) }
    private val slideInFn = ProcFunc { terrainSlideIn(it) }
    private val displayFn = ProcFunc { terrainDisplay(it) }
    private val slideOutFn = ProcFunc { terrainSlideOut(it) }

    /** `gProcScr_TerrainDisplay` (0x08A018AC). */
    public val terrainScript: ProcScript = ProcScript(
        "gProcScr_TerrainDisplay",
        listOf(
            name("TI"),
            proc15,
            yield(),
            call(initFn),
            label(0),
            whileExists { camMoveScript },
            repeat(sideChangeFn),
            repeat(slideInFn),
            repeat(displayFn),
            repeat(slideOutFn),
            goto(0),
            end(),
        ),
        ::PlayerInterfaceState,
    )

    /** `gProcScr_SideWindowMaker` (0x08A019C4). */
    public val sideWindowMakerScript: ProcScript = ProcScript(
        "gProcScr_SideWindowMaker",
        listOf(
            whileTrue(ProcBoolFunc { hooks.doesBmxFadeExist.invoke(it) }),
            call(ProcFunc { initPlayerPhaseInterface() }),
            end(),
        ),
    )

    private fun stateOf(proc: Proc): PlayerInterfaceState = proc.state as PlayerInterfaceState

    /**
     * Ported from src/player_interface.c: GetWindowQuadrant
     * (the quadrants 0..2 are not verified: the terrain window asks with both signs positive in
     * every reference run, so only quadrant 3 is compared)
     *
     * The screen corner for the side signs [x] and [y] of a window position (negative is the
     * left or top): 0 top left, 1 bottom left, 2 top right, 3 bottom right.
     */
    public fun getWindowQuadrant(x: Int, y: Int): Int = when {
        x < 0 -> if (y < 0) 0 else 1
        y < 0 -> 2
        else -> 3
    }

    /**
     * Ported from src/player_interface.c: GetCursorQuadrant
     *
     * The quadrant of the 240x160 screen holding the map cursor (screen position of the cursor
     * tile less 8 pixels of camera slack): 0 top left, 1 top right, 2 bottom left, 3 bottom
     * right, the split being at x 121 and y 81.
     */
    public fun getCursorQuadrant(): Int {
        val x = bm.playerCursorX * 16 - (bm.cameraX - 8)
        val y = bm.playerCursorY * 16 - (bm.cameraY - 8)
        val right = x >= HALF_WIDTH
        val bottom = y >= HALF_HEIGHT
        return (if (right) 1 else 0) + (if (bottom) 2 else 0)
    }

    /**
     * Ported from src/player_interface.c: DrawTerrainDisplayWindow
     * (the obstacle and ballista branches are not verified: the cursor stands on plain terrain
     * in every reference run)
     *
     * Draws the box for the terrain under the map cursor into [scratch]: the terrain name
     * centred, the labels and the defence and avoid digits (the digits only where the terrain
     * can be entered; walls and ballistae show their own labels and hit points).
     */
    public fun drawTerrainDisplayWindow(state: PlayerInterfaceState) {
        val cursorX = bm.playerCursorX
        val cursorY = bm.playerCursorY
        val terrainId = hooks.terrainAt(cursorX, cursorY)
        val a = scratch.a

        fillRect(a, TM_BOX_ORIGIN, BOX_WIDTH, BOX_HEIGHT, 0)
        fillRect(scratch.b, TM_BOX_ORIGIN, BOX_WIDTH, BOX_HEIGHT, 0)

        val str = messages.getStringFromIndex(rom.u16(TERRAIN_NAME_MSGS + 2 * terrainId))
        val name = state.text
        text.clearText(name)
        name.x = text.getStringTextCenteredPos(NAME_WIDTH, str)
        name.colorId = TEXT_COLOR_SYSTEM_WHITE
        text.drawString(name, str)
        text.putText(name, a, tmIndex(1, 12))

        fillTsa(a, tmIndex(1, 14), TSA_LABELS, TILEREF_100_PAL2)

        if (rom.u8(MOV_COST_BERSERKER_NORMAL + terrainId).toByte() > 0) {
            putNumber(a, tmIndex(5, 14), rom.u8(DEF_COMMON + terrainId).toByte().toInt())
            putNumber(a, tmIndex(5, 15), rom.u8(AVO_COMMON + terrainId).toByte().toInt())
        }

        when (terrainId) {
            TERRAIN_SNAG, TERRAIN_WALL_DAMAGED -> {
                fillTsa(a, tmIndex(1, 14), TSA_OBSTACLE_LABELS, TILEREF_100_PAL2)
                val hp = hooks.obstacleHpAt(cursorX, cursorY)
                if (hp == 100) {
                    fillTsa(a, tmIndex(4, 15), TSA_OBSTACLE_FULL_HP, TILEREF_100_PAL0)
                } else {
                    putNumber(a, tmIndex(5, 15), hp)
                }
            }
            TERRAIN_BALLISTA_REGULAR, TERRAIN_BALLISTA_LONG, TERRAIN_BALLISTA_KILLER -> {
                fillTsa(a, tmIndex(1, 14), TSA_BALLISTAE, TILEREF_100_PAL0)
                putNumber(a, tmIndex(5, 14), hooks.obstacleHpAt(cursorX, cursorY))
            }
        }

        fillTsa(scratch.b, tmIndex(0, 11), TSA_TERRAIN_BOX, TILEREF_0_PAL1)
    }

    /**
     * Ported from src/player_interface.c: TerrainDisplay_Init
     *
     * Starts the window without a position (`windowQuadrant` -1) and reserves its double-buffered
     * text strip of 5 tiles in the active font.
     */
    public fun terrainInit(proc: Proc) {
        val state = stateOf(proc)
        state.windowQuadrant = NO_WINDOW
        state.isRetracting = false
        state.showHideClock = 0
        state.cursorQuadrant = 1
        val font = text.activeFont
        val name = state.text
        name.chrPosition = font.chrCounter
        name.tileWidth = TEXT_TILES
        name.dbId = 0
        name.dbEnabled = true
        name.isPrinting = false
        font.chrCounter += TEXT_TILES * 2
    }

    /**
     * Ported from src/player_interface.c: TerrainDisplay_Loop_OnSideChange
     *
     * Picks the corner for the window from the cursor's quadrant. If the unit window or the goal
     * window already holds that corner it waits (a proc that does not exist holds none: C reads
     * the goal proc through a pointer that is NULL only in a build without BUGFIX when the unit
     * window is also absent). Otherwise it draws the window, remembers the cursor and moves on to
     * the slide in. The original's first pass reads the other windows' `windowQuadrant` from
     * their not yet initialised proc slots.
     */
    public fun terrainOnSideChange(proc: Proc) {
        val state = stateOf(proc)
        state.hideContents = true
        state.cursorQuadrant = getCursorQuadrant()

        val quadrant = getWindowQuadrant(
            CONFIG[state.cursorQuadrant * CONFIG_STRIDE + CONFIG_X_TERRAIN],
            CONFIG[state.cursorQuadrant * CONFIG_STRIDE + CONFIG_Y_TERRAIN],
        )

        val unitQuadrant = hooks.unitDisplayWindowQuadrant()
        if (unitQuadrant > NO_WINDOW && unitQuadrant == quadrant) return
        val goalQuadrant = hooks.goalDisplayWindowQuadrant()
        if (goalQuadrant > NO_WINDOW && goalQuadrant == quadrant) return

        state.windowQuadrant = quadrant
        drawTerrainDisplayWindow(state)
        state.xCursor = bm.playerCursorX
        state.yCursor = bm.playerCursorY
        engine.breakProc(proc)
    }

    /**
     * Ported from src/player_interface.c: TerrainDisplay_Loop_SlideIn
     *
     * One frame of the slide in: clears the window's strip of BG0 and BG1 (columns 0..6 on the
     * left of the screen, 23..29 on the right, rows 13..19), then copies the rightmost (or
     * leftmost) 5, 6 and 7 columns of the box from [scratch] on successive frames. Shows the
     * contents and ends the step after the third.
     */
    public fun terrainSlideIn(proc: Proc) {
        val state = stateOf(proc)
        val left = CONFIG[state.cursorQuadrant * CONFIG_STRIDE + CONFIG_X_TERRAIN] < 0
        val bg0 = hw.mapBuffer(0)
        val bg1 = hw.mapBuffer(1)
        val x = if (left) 0 else RIGHT_X
        fillRect(bg0, tmIndex(x, 13), 7, 7, 0)
        fillRect(bg1, tmIndex(x, 13), 7, 7, 0)
        hw.enableBgSyncByMask(BG0_SYNC_BIT or BG1_SYNC_BIT)

        val width = SLIDE_IN_WIDTH[state.showHideClock] + 1
        if (left) {
            copyRect(scratch.a, tmIndex(7 - width, 10), bg0, tmIndex(0, 13), width, 7)
            copyRect(scratch.b, tmIndex(7 - width, 10), bg1, tmIndex(0, 13), width, 7)
        } else {
            copyRect(scratch.a, tmIndex(0, 10), bg0, tmIndex(30 - width, 13), width, 7)
            copyRect(scratch.b, tmIndex(0, 10), bg1, tmIndex(30 - width, 13), width, 7)
        }

        state.showHideClock++
        if (state.showHideClock == 3) {
            state.showHideClock = 0
            state.hideContents = false
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/player_interface.c: TerrainDisplay_Loop_Display
     *
     * Steady state: while the cursor stays put nothing happens. When it moves and the camera is
     * still, a cursor in the same quadrant (or one whose quadrant puts the window in the same
     * place) redraws the box and copies it to BG0 in place; any other move starts the slide out.
     * (The slide-out branch is not verified: the cursor never changes quadrant in a reference
     * run.)
     */
    public fun terrainDisplay(proc: Proc) {
        val state = stateOf(proc)
        state.xCursorPrev = state.xCursor
        state.yCursorPrev = state.yCursor
        state.xCursor = bm.playerCursorX
        state.yCursor = bm.playerCursorY

        if (state.xCursor == state.xCursorPrev && state.yCursor == state.yCursorPrev) return

        if (engine.find(camMoveScript) == null) {
            val cursorQuadrant = getCursorQuadrant()
            val own = state.cursorQuadrant * CONFIG_STRIDE
            val now = cursorQuadrant * CONFIG_STRIDE
            if (cursorQuadrant == state.cursorQuadrant ||
                (CONFIG[now + CONFIG_X_TERRAIN] == CONFIG[own + CONFIG_X_TERRAIN] &&
                    CONFIG[now + CONFIG_Y_TERRAIN] == CONFIG[own + CONFIG_Y_TERRAIN])
            ) {
                drawTerrainDisplayWindow(state)
                copyTerrainWindowToBg0(state)
                return
            }
        }

        state.isRetracting = true
        engine.breakProc(proc)
    }

    /**
     * Transcribed from src/player_interface.c: TerrainDisplay_Loop_SlideOut
     * (not verified: no reference run moves the cursor across the screen half, so the window
     * never slides out; `EndPlayerPhaseSideWindows` ends it instead)
     *
     * One frame of the slide out: the same strip clear as [terrainSlideIn], then the box
     * narrowed to 5, 4 and 0 columns. Hides the contents; after the third frame it unhides them,
     * stops retracting and ends the step.
     */
    public fun terrainSlideOut(proc: Proc) {
        val state = stateOf(proc)
        state.hideContents = true
        val left = CONFIG[state.cursorQuadrant * CONFIG_STRIDE + CONFIG_X_TERRAIN] < 0
        val bg0 = hw.mapBuffer(0)
        val bg1 = hw.mapBuffer(1)
        val x = if (left) 0 else RIGHT_X
        fillRect(bg0, tmIndex(x, 13), 7, 7, 0)
        fillRect(bg1, tmIndex(x, 13), 7, 7, 0)
        hw.enableBgSyncByMask(BG0_SYNC_BIT or BG1_SYNC_BIT)

        val width = SLIDE_OUT_WIDTH[state.showHideClock]
        if (left) {
            copyRect(scratch.a, tmIndex(6 - width, 10), bg0, tmIndex(0, 13), width, 7)
            copyRect(scratch.b, tmIndex(6 - width, 10), bg1, tmIndex(0, 13), width, 7)
        } else {
            copyRect(scratch.a, tmIndex(0, 10), bg0, tmIndex(30 - width, 13), width, 7)
            copyRect(scratch.b, tmIndex(0, 10), bg1, tmIndex(30 - width, 13), width, 7)
        }

        state.showHideClock++
        if (state.showHideClock == 3) {
            state.showHideClock = 0
            state.hideContents = false
            state.isRetracting = false
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/player_interface.c: sub_808C288
     *
     * Copies the terrain box (7x7 tiles at row 10 of the BG0 scratch) to its place on BG0, on the
     * left or right of the screen as the window's quadrant says, and flags BG0 and BG1 for sync.
     */
    public fun copyTerrainWindowToBg0(state: PlayerInterfaceState) {
        val left = CONFIG[state.cursorQuadrant * CONFIG_STRIDE + CONFIG_X_TERRAIN] < 0
        val x = if (left) 0 else RIGHT_X
        copyRect(scratch.a, tmIndex(0, 10), hw.mapBuffer(0), tmIndex(x, 13), 7, 7)
        hw.enableBgSyncByMask(BG0_SYNC_BIT or BG1_SYNC_BIT)
    }

    /**
     * Ported from src/player_interface.c: InitPlayerPhaseInterface
     * (the preparations-screen branch is not verified: the reference runs are in the player
     * phase)
     *
     * Sets up the display for the side windows: no windows but the outside-window layers with
     * blending, BG scroll 0, alpha blend 13/3 of BG0 over BG1, BG2, BG3 and OBJ, the interface
     * font tiles in BG VRAM (tile 0x100) and the digit tiles in OBJ VRAM (tile 0x2E0), palette
     * bank 0 copied to bank 0x18 and the item icon palette in bank 2; then resets the font's tile
     * counter and starts the windows: terrain (unless disabled), then the preparations menu
     * button or the goal window, then the unit window.
     */
    public fun initPlayerPhaseInterface() {
        val lcd = hw.lcd
        lcd.setWinEnable(0, 0, 0)
        lcd.setWOutLayers(1, 1, 1, 1, 1)
        lcd.setWincntBit(LcdControl.WOUT_ENABLE_BLEND, true)

        lcd.setBgPosition(0, 0, 0)
        lcd.setBgPosition(1, 0, 0)
        lcd.setBgPosition(2, 0, 0)

        lcd.setBlendConfig(BLEND_EFFECT_ALPHA, 13, 3, 0)
        lcd.setBlendTargetA(0, 1, 0, 0, 0)
        lcd.setBlendBackdropA(0)
        lcd.setBlendTargetB(0, 0, 1, 1, 1)

        rom.decompressInto(GFX_FONT_TILES, hw.vram, BG_CHR_0X100)
        rom.decompressInto(GFX_NUMBERS, hw.vram, OBJ_CHR_0X2E0)
        hw.vram.copyInto(hw.vram, OBJ_CHR_0X2EA, BG_CHR_0X175, BG_CHR_0X175 + CHR_SIZE)

        hw.palette.copyInto(hw.palette, PAL_BANK_0X18 * 16, 0, 16)
        hw.enablePaletteSync()

        ItemIcon.loadIconPalette(hw, rom, 1, 2)

        text.activeFont.chrCounter = 0

        if (playSt.disableTerrainDisplay == 0) engine.start(terrainScript, PROC_TREE_3)

        if (bm.gameStateBits and BM_FLAG_PREPSCREEN != 0) {
            hooks.startPrepMenuButtonDisplay()
        } else {
            hooks.startGoalDisplay()
        }
        hooks.startUnitDisplay()
    }

    /**
     * Ported from src/player_interface.c: StartPlayerPhaseSideWindows
     *
     * Starts [sideWindowMakerScript] in tree 3; it makes the windows once no map fade is running.
     */
    public fun startPlayerPhaseSideWindows() {
        engine.start(sideWindowMakerScript, PROC_TREE_3)
    }

    /**
     * Ported from src/player_interface.c: EndPlayerPhaseSideWindows
     *
     * Ends every side window, restores the default colour effects (no blend) and clears BG0 and
     * BG1.
     */
    public fun endPlayerPhaseSideWindows() {
        hooks.endUnitDisplay()
        engine.endEach(terrainScript)
        hooks.endGoalDisplay()
        hooks.endPrepMenuButtonDisplay()

        hw.lcd.setBlendConfig(BLEND_EFFECT_NONE, 0x10, 0, 0)

        hw.mapBuffer(0).fill(0)
        hw.mapBuffer(1).fill(0)
        hw.enableBgSyncByMask(BG0_SYNC_BIT or BG1_SYNC_BIT)
    }

    private fun tmIndex(x: Int, y: Int): Int = y * TM_STRIDE + x

    /** `TileMap_FillRect`: fills a [w] x [h] rectangle at entry [at] of [tm] with [value]. */
    private fun fillRect(tm: ShortArray, at: Int, w: Int, h: Int, value: Int) {
        for (row in 0 until h) {
            val start = at + row * TM_STRIDE
            tm.fill(value.toShort(), start, start + w)
        }
    }

    /** `TileMap_CopyRect`: copies a [w] x [h] rectangle from [src] at [from] to [dst] at [to]. */
    private fun copyRect(src: ShortArray, from: Int, dst: ShortArray, to: Int, w: Int, h: Int) {
        for (row in 0 until h) {
            val srcStart = from + row * TM_STRIDE
            src.copyInto(dst, to + row * TM_STRIDE, srcStart, srcStart + w)
        }
    }

    /**
     * `CallARM_FillTileRect` for a TSA stored uncompressed in the ROM at [addr]: byte 0 and 1 are
     * width and height less 1, then the entries, bottom row first; [tileRef] is added to each.
     */
    private fun fillTsa(tm: ShortArray, at: Int, addr: Int, tileRef: Int) {
        val width = rom.u8(addr) + 1
        val height = rom.u8(addr + 1) + 1
        var entry = addr + 2
        for (row in height - 1 downTo 0) {
            for (col in 0 until width) {
                tm[at + row * TM_STRIDE + col] = (rom.u16(entry) + tileRef).toShort()
                entry += 2
            }
        }
    }

    /**
     * `StoreNumberStringToSmallBuffer(n)` followed by
     * `PutDigits(tm + at, gNumberStr + 7, TILEREF(0x128, 2), 2)`: the decimal digits of [n]
     * (C `%` and `/`) right-aligned at entry [at], two entries cleared first.
     */
    private fun putNumber(tm: ShortArray, at: Int, n: Int) {
        numberStr.fill(SPACE, 0, 8)
        numberStr[8] = 0
        var rest = n
        for (i in 7 downTo 0) {
            numberStr[i] = (ZERO_CHAR + rest % 10).toByte()
            rest /= 10
            if (rest == 0) break
        }
        tm[at] = 0
        tm[at - 1] = 0
        var out = at
        var src = 7
        while (numberStr[src].toInt() != SPACE.toInt()) {
            tm[out--] = (TILEREF_128_PAL2 + numberStr[src] - ZERO_CHAR).toShort()
            src--
        }
    }

    /** Constants. */
    public companion object {
        /** `windowQuadrant` of a window that is not up (and the "no proc" answer of the hooks). */
        public const val NO_WINDOW: Int = -1

        private const val HALF_WIDTH = 240 / 2 + 1
        private const val HALF_HEIGHT = 160 / 2 + 1
        private const val TM_STRIDE = 32
        private const val TM_BOX_ORIGIN = 10 * TM_STRIDE
        private const val RIGHT_X = 23
        private const val BOX_WIDTH = 14
        private const val BOX_HEIGHT = 7
        private const val NAME_WIDTH = 40
        private const val TEXT_TILES = 5
        private const val TEXT_COLOR_SYSTEM_WHITE = 0
        private const val BG0_SYNC_BIT = 1
        private const val BG1_SYNC_BIT = 2
        private const val PROC_TREE_3 = 3
        private const val BM_FLAG_PREPSCREEN = 1 shl 4
        private const val BLEND_EFFECT_NONE = 0
        private const val BLEND_EFFECT_ALPHA = 1

        private const val NUMBER_STR_SIZE = 9
        private const val SPACE: Byte = 0x20
        private const val ZERO_CHAR = '0'.code

        private const val TERRAIN_WALL_DAMAGED = 0x1B
        private const val TERRAIN_BALLISTA_REGULAR = 0x27
        private const val TERRAIN_BALLISTA_LONG = 0x28
        private const val TERRAIN_BALLISTA_KILLER = 0x29
        private const val TERRAIN_SNAG = 0x33

        private const val TILEREF_0_PAL1 = 0x1000
        private const val TILEREF_100_PAL0 = 0x100
        private const val TILEREF_100_PAL2 = 0x2100
        private const val TILEREF_128_PAL2 = 0x2128

        private const val CHR_SIZE = 0x20
        private const val BG_CHR_0X100 = 0x100 * CHR_SIZE
        private const val BG_CHR_0X175 = 0x175 * CHR_SIZE
        private const val OBJ_VRAM0 = 0x10000
        private const val OBJ_CHR_0X2E0 = OBJ_VRAM0 + 0x2E0 * CHR_SIZE
        private const val OBJ_CHR_0X2EA = OBJ_VRAM0 + 0x2EA * CHR_SIZE
        private const val PAL_BANK_0X18 = 0x18

        /** `sPlayerInterfaceConfigLut`: per entry, six signs (the last two are padding). */
        private val CONFIG = intArrayOf(
            +1, +1, -1, +1, +1, -1,
            -1, +1, -1, -1, +1, +1,
            +1, +1, -1, -1, +1, -1,
            -1, +1, -1, -1, +1, -1,
        )
        private const val CONFIG_STRIDE = 6
        private const val CONFIG_X_TERRAIN = 0
        private const val CONFIG_Y_TERRAIN = 1

        /** `sTerrainSlideInWidthLut`. */
        private val SLIDE_IN_WIDTH = intArrayOf(4, 5, 6)

        /** `sTerrainSlideOutWidthLut`. */
        private val SLIDE_OUT_WIDTH = intArrayOf(5, 4, 0, 0, 0, 0)

        private const val TERRAIN_NAME_MSGS = 0x0880D374
        private const val MOV_COST_BERSERKER_NORMAL = 0x0880B90C
        private const val AVO_COMMON = 0x0880C479
        private const val DEF_COMMON = 0x0880C4BA
        private const val GFX_FONT_TILES = 0x08A167C8
        private const val GFX_NUMBERS = 0x08A16D6C
        private const val TSA_TERRAIN_BOX = 0x08A1742C
        private const val TSA_LABELS = 0x08A1768C
        private const val TSA_BALLISTAE = 0x08A1769C
        private const val TSA_OBSTACLE_LABELS = 0x08A176A4
        private const val TSA_OBSTACLE_FULL_HP = 0x08A176B4
    }
}
