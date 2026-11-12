package fe8.game.bm

import fe8.game.RomView
import fe8.game.scene.IntBinaryOperator
import fe8.game.scene.IntUnaryOperator
import fe8.game.ui.GreenText
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
import gbafe.render.OamBuffer
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
        /**
         * Entries per buffer: 0x500 bytes of `u16` (20 rows) and two rows of slack, because the
         * goal box clears 10 rows from row 12 of buffer A (`TileMap_FillRect` with height 9 writes
         * 10 rows), two past its end (the original writes into the memory after it; nothing reads
         * it).
         */
        public const val SIZE: Int = 0x2C0
    }
}

/**
 * `struct PlayerInterfaceProc` past `PROC_HEADER` (src/player_interface.c): the members of the
 * terrain, goal, unit mini-box and Burst windows.
 *
 * The three side windows run the same struct. A fresh state is all zero; the original's
 * `Proc_Start` leaves the old contents of the proc slot in place, which matters only for members
 * the first pass reads before they are set (see [PlayerInterface.terrainOnSideChange]).
 */
public class PlayerInterfaceState {
    /** `texts[0]` (0x2C): the window's text strip (the terrain window draws the name here). */
    public val text: Text = Text()

    /** `texts[1]` (0x34): the goal window's second text strip. */
    public val text1: Text = Text()

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

    /**
     * `statusTm` (0x40): entry index of the unit window's status cells, in the BG0 scratch for the
     * mini-box and in the BG0 tilemap buffer itself when [statusInBg0] is set (Burst).
     */
    public var statusTm: Int = 0

    /** Whether [statusTm] indexes the BG0 tilemap buffer (the original holds a pointer). */
    public var statusInBg0: Boolean = false

    /** `unitClock` (s16, 0x44): frames since the unit window drew its unit. */
    public var unitClock: Int = 0

    /** `xHp` (s16, 0x46): tile column of the unit window's HP digits. */
    public var xHp: Int = 0

    /** `yHp` (s16, 0x48): tile row of the unit window's HP digits. */
    public var yHp: Int = 0

    /** `hpCurHi` (u8, 0x51): tens digit of the current HP (0xF0 for a blank). */
    public var hpCurHi: Int = 0

    /** `hpCurLo` (u8, 0x52): units digit of the current HP. */
    public var hpCurLo: Int = 0

    /** `hpMaxHi` (u8, 0x53): tens digit of the maximum HP (0xF0 for a blank). */
    public var hpMaxHi: Int = 0

    /** `hpMaxLo` (u8, 0x54): units digit of the maximum HP. */
    public var hpMaxLo: Int = 0

    /** `xBurst` (s8, 0x3C): tile column of the Burst box. */
    public var xBurst: Int = 0

    /** `yBurst` (s8, 0x3D): tile row of the Burst box. */
    public var yBurst: Int = 0

    /** `wBurst` (s8, 0x3E): width of the drawn Burst box in tiles, 0 when none is drawn. */
    public var wBurst: Int = 0

    /** `hBurst` (s8, 0x3F): height of the drawn Burst box in tiles, 0 when none is drawn. */
    public var hBurst: Int = 0

    /** `burstUnitIdPrev` (u8, 0x4A): the map unit id under the cursor the pass before. */
    public var burstUnitIdPrev: Int = 0

    /** `burstUnitId` (u8, 0x4B): the map unit id under the cursor (0 for none). */
    public var burstUnitId: Int = 0
}

/**
 * The player-phase side windows of src/player_interface.c, as far as the terrain window, the goal
 * window and the window maker: `gProcScr_TerrainDisplay` (the terrain name, defence and avoid box that slides in
 * beside the map cursor) and `gProcScr_SideWindowMaker` with `StartPlayerPhaseSideWindows` and
 * `EndPlayerPhaseSideWindows`.
 *
 * Graphics, palettes and terrain names are read from the ROM at runtime. The goal window
 * (`gProcScr_GoalDisplay`) is here too; the unit mini-box is in this file, and the services
 * outside the ported part are [Hooks]. Nothing allocates per frame.
 */
public class PlayerInterface(
    private val engine: ProcEngine,
    private val bm: BmSt,
    private val playSt: PlaySt,
    private val units: UnitTable,
    private val rom: RomView,
    private val hw: BgHardware,
    private val oam: OamBuffer,
    private val text: TextSystem,
    private val messages: MsgStrings,
    /** `ProcScr_CamMove`: the side windows wait while it exists. */
    private val camMoveScript: ProcScript,
    /** `ProcScr_GreenTextColor`'s owner: the goal window starts its colour proc. */
    private val greenText: GreenText,
    /** `gUiTmScratchA` and `gUiTmScratchB`, shared by all side windows. */
    public val scratch: UiTmScratch = UiTmScratch(),
) {
    /** Services outside the ported part of the file; the defaults do nothing. */
    public class Hooks {
        /** Stand-in for `DoesBMXFADEExist` (false) until M6-H (the fog fade). */
        public var doesBmxFadeExist: ProcBoolFunc = ProcBoolFunc { false }

        /** `gBmMapTerrain[y][x]`; `Fe8Boot` reads the map. */
        public var terrainAt: IntBinaryOperator = IntBinaryOperator { _, _ -> 0 }

        /** Stand-in for `GetObstacleHpAt` (bmtrick.c) until M6-H. */
        public var obstacleHpAt: (x: Int, y: Int) -> Int = { _, _ -> 0 }

        /** `gBmMapUnit[y][x]`; `Fe8Boot` reads the map. */
        public var unitIdAt: IntBinaryOperator = IntBinaryOperator { _, _ -> 0 }

        /**
         * `GetUnitMaxHp` (bmunit.c). The default is the unit's `maxHP` byte (without the
         * equipped weapon's bonus); `Fe8Boot` sets `UnitLoader.getUnitMaxHp`. The argument is
         * the unit's byte offset in [UnitTable.bytes].
         */
        public var unitMaxHp: IntUnaryOperator = IntUnaryOperator { 0 }

        /**
         * `CheckFlag(EVFLAG_OBJWINDOW_DISABLE)`; `Fe8Boot` reads the event flags. The goal window
         * is not started while it is set.
         */
        public var objWindowDisabled: () -> Boolean = { false }

        /**
         * `GetBattleMapKind() == BATTLEMAP_KIND_SKIRMISH`; `Fe8Boot` answers from
         * [BattleMapKind].
         */
        public var isSkirmish: () -> Boolean = { false }

        /**
         * Stand-in until M6-H (the first chapter with a "defeat all" or "defend" goal) for the second
         * text line
         * of `GoalDisplay_Init` (enemy count or turn counter, drawn with
         * `Text_InsertDrawNumberOrBlank`); [goalType] is the chapter's goal window data type.
         */
        public var drawGoalSecondLine: (state: PlayerInterfaceState, goalType: Int) -> Unit =
            { _, _ -> }

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

    /**
     * The `windowQuadrant` a new unit window proc holds before its `MMB_Init` has run. The
     * original's `Proc_Start` leaves the old contents of the proc slot there, and the terrain
     * window's first pass reads it. In the reference runs the slot had held a terrain window
     * (quadrant 3); the default is [NO_WINDOW].
     */
    public var newUnitWindowResidue: Int = NO_WINDOW

    init {
        hooks.unitMaxHp = IntUnaryOperator { StructCodec.s8(units.bytes, it + UNIT_MAX_HP) }
    }

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

    private val mmbInitFn = ProcFunc { mmbInit(it) }
    private val mmbSideChangeFn = ProcFunc { mmbOnSideChange(it) }
    private val mmbSlideInFn = ProcFunc { mmbSlideIn(it) }
    private val mmbDisplayFn = ProcFunc { mmbDisplay(it) }
    private val mmbSlideOutFn = ProcFunc { mmbSlideOut(it) }
    private val mmbCheckFn = ProcFunc { mmbCheckForUnit(it) }

    /** `gProcScr_UnitDisplay_MinimugBox` (0x08A0190C). */
    public val minimugScript: ProcScript = ProcScript(
        "gProcScr_UnitDisplay_MinimugBox",
        listOf(
            name("UI1"),
            proc15,
            yield(),
            call(mmbInitFn),
            label(0),
            whileExists { camMoveScript },
            repeat(mmbSideChangeFn),
            repeat(mmbSlideInFn),
            label(2),
            repeat(mmbDisplayFn),
            label(3),
            repeat(mmbSlideOutFn),
            goto(0),
            label(1),
            call(mmbCheckFn),
            goto(2),
            end(),
        ),
    ) { PlayerInterfaceState().also { it.windowQuadrant = newUnitWindowResidue } }

    private val burstInitFn = ProcFunc { burstInit(it) }
    private val burstDisplayFn = ProcFunc { burstDisplay(it) }

    /** `gProcScr_UnitDisplay_Burst` (0x08A01994): the unit window of option "Burst". */
    public val burstScript: ProcScript = ProcScript(
        "gProcScr_UnitDisplay_Burst",
        listOf(
            name("UI2"),
            proc15,
            yield(),
            call(burstInitFn),
            repeat(burstDisplayFn),
            end(),
        ),
        ::PlayerInterfaceState,
    )

    private val goalInitFn = ProcFunc { goalInit(it) }
    private val goalSideChangeFn = ProcFunc { goalOnSideChange(it) }
    private val goalSlideInFn = ProcFunc { goalSlideIn(it) }
    private val goalDisplayFn = ProcFunc { goalDisplay(it) }
    private val goalSlideOutFn = ProcFunc { goalSlideOut(it) }

    /**
     * The `windowQuadrant` a new goal window proc holds before its `GoalDisplay_Init` has run
     * (the old contents of the proc slot, as for [newUnitWindowResidue]); the terrain window's
     * first pass reads it.
     */
    public var newGoalWindowResidue: Int = NO_WINDOW

    /** `gProcScr_GoalDisplay` (0x08A019E4). */
    public val goalScript: ProcScript = ProcScript(
        "gProcScr_GoalDisplay",
        listOf(
            name("PI"),
            proc15,
            yield(),
            call(goalInitFn),
            label(0),
            whileExists { camMoveScript },
            repeat(goalSideChangeFn),
            repeat(goalSlideInFn),
            repeat(goalDisplayFn),
            repeat(goalSlideOutFn),
            goto(0),
            end(),
        ),
    ) { PlayerInterfaceState().also { it.windowQuadrant = newGoalWindowResidue } }

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

        val unitProc = engine.find(minimugScript)
        val unitQuadrant = if (unitProc == null) NO_WINDOW else stateOf(unitProc).windowQuadrant
        if (unitQuadrant > NO_WINDOW && unitQuadrant == quadrant) return
        val goalProc = engine.find(goalScript)
        val goalQuadrant = if (goalProc == null) NO_WINDOW else stateOf(goalProc).windowQuadrant
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
            if (playSt.disableGoalDisplay == 0 && !hooks.objWindowDisabled()) {
                engine.start(goalScript, PROC_TREE_3)
            }
        }
        if (playSt.unitDisplayType == 0) engine.start(minimugScript, PROC_TREE_3)
        if (playSt.unitDisplayType == 1) engine.start(burstScript, PROC_TREE_3)
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
        engine.endEach(minimugScript)
        engine.endEach(burstScript)
        engine.endEach(terrainScript)
        engine.endEach(goalScript)
        hooks.endPrepMenuButtonDisplay()

        hw.lcd.setBlendConfig(BLEND_EFFECT_NONE, 0x10, 0, 0)

        hw.mapBuffer(0).fill(0)
        hw.mapBuffer(1).fill(0)
        hw.enableBgSyncByMask(BG0_SYNC_BIT or BG1_SYNC_BIT)
    }

    private val chapters by lazy { ChapterData(rom) }

    /**
     * Ported from src/player_interface.c: GoalDisplay_Init
     * (the defeat-all and defend branches are not verified: the Prologue's goal is to defeat the
     * boss, so only the one-line window runs; their second line is [Hooks.drawGoalSecondLine])
     *
     * Starts the window without a position, reserves two 8-tile text strips, starts the green
     * text colour proc under the window, and draws the goal text (the chapter's, or "Defeat
     * enemy" in a skirmish) centred in the first strip. A seize, defeat boss or special goal has
     * one line (`unitClock` 0); the others fill the second strip and have two (`unitClock` 1).
     */
    public fun goalInit(proc: Proc) {
        val state = stateOf(proc)
        state.showHideClock = 0
        state.isRetracting = false
        state.cursorQuadrant = 0
        state.windowQuadrant = NO_WINDOW

        text.initText(state.text, GOAL_TEXT_TILES)
        text.initText(state.text1, GOAL_TEXT_TILES)
        greenText.startGreenText(proc)
        text.clearText(state.text)
        text.clearText(state.text1)

        val chapter = chapters.getROMChapterStruct(playSt.chapterIndex)
        val skirmish = hooks.isSkirmish()
        val goalTextId = if (skirmish) MSG_DEFEAT_ENEMY else rom.u16(chapter + CHAPTER_GOAL_TEXT)
        val str = messages.getStringFromIndex(goalTextId)
        text.textInsertDrawString(
            state.text, text.getStringTextCenteredPos(GOAL_NAME_WIDTH, str),
            TEXT_COLOR_SYSTEM_WHITE, str,
        )

        val goalType = if (skirmish) GOAL_TYPE_DEFEAT_ALL else rom.u8(chapter + CHAPTER_GOAL_TYPE)
        when (goalType) {
            GOAL_TYPE_SEIZE, GOAL_TYPE_DEFEAT_BOSS, GOAL_TYPE_SPECIAL -> state.unitClock = 0
            GOAL_TYPE_DEFEAT_ALL, GOAL_TYPE_DEFENSE -> {
                hooks.drawGoalSecondLine(state, goalType)
                state.unitClock = 1
            }
        }
    }

    /**
     * Ported from src/player_interface.c: sub_808D200
     * (the two-line box is not verified: the Prologue's goal window has one line)
     *
     * Draws the goal box into [scratch]: clears the 11x9 areas, then the one-line or two-line
     * frame into the BG1 side and the text strip(s) into the BG0 side.
     */
    public fun drawGoalWindow(state: PlayerInterfaceState) {
        fillRect(scratch.b, tmIndex(20, 10), GOAL_CLEAR_WIDTH, GOAL_CLEAR_HEIGHT, 0)
        fillRect(scratch.a, tmIndex(20, 12), GOAL_CLEAR_WIDTH, GOAL_CLEAR_HEIGHT, 0)

        if (state.unitClock == 0) {
            fillTsa(scratch.b, tmIndex(20, 10), TSA_GOAL_ONE_LINE, TILEREF_0_PAL1)
            text.putText(state.text, scratch.a, tmIndex(21, 13))
        }
        if (state.unitClock == 1) {
            fillTsa(scratch.b, tmIndex(20, 10), TSA_GOAL_TWO_LINES, TILEREF_0_PAL1)
            text.putText(state.text, scratch.a, tmIndex(21, 13))
            text.putText(state.text1, scratch.a, tmIndex(21, 15))
        }
    }

    /**
     * Ported from src/player_interface.c: GoalDisplay_Loop_OnSideChange
     *
     * Picks the corner from the cursor's quadrant; waits if the terrain window holds it.
     * Otherwise draws the goal box, remembers the cursor and moves on to the slide in.
     */
    public fun goalOnSideChange(proc: Proc) {
        val state = stateOf(proc)
        state.showHideClock = 0
        state.hideContents = true
        state.cursorQuadrant = getCursorQuadrant()

        val quadrant = getWindowQuadrant(
            CONFIG[state.cursorQuadrant * CONFIG_STRIDE + CONFIG_X_GOAL],
            CONFIG[state.cursorQuadrant * CONFIG_STRIDE + CONFIG_Y_GOAL],
        )

        val terrainProc = engine.find(terrainScript)
        if (terrainProc != null) {
            val terrainQuadrant = stateOf(terrainProc).windowQuadrant
            if (terrainQuadrant > NO_WINDOW && terrainQuadrant == quadrant) return
        }

        state.windowQuadrant = quadrant
        drawGoalWindow(state)

        state.xCursor = bm.playerCursorX
        state.yCursor = bm.playerCursorY
        state.xCursorPrev = state.xCursor
        state.yCursorPrev = state.yCursor
        engine.breakProc(proc)
    }

    /**
     * Ported from src/player_interface.c: sub_808D514
     * (quadrant corners other than the top right are not verified: see [goalSlideIn])
     *
     * Blanks the goal window's 12x6 area of BG0 and BG1 in the corner [quadrant]'s config says
     * (columns 0 or 19, rows 0 or 14), then copies [rows] rows of the box from [scratch] in
     * (from the top or bottom edge, as the corner needs), and flags BG0 and BG1 for sync.
     * [lines] is the proc's `unitClock` (0 one-line box, 1 two-line box).
     */
    public fun copyGoalWindowToBg(quadrant: Int, rows: Int, lines: Int) {
        val x = CONFIG[quadrant * CONFIG_STRIDE + CONFIG_X_GOAL]
        val y = CONFIG[quadrant * CONFIG_STRIDE + CONFIG_Y_GOAL]
        val bg0 = hw.mapBuffer(0)
        val bg1 = hw.mapBuffer(1)
        val bottom = (1 - lines) * 2 + 20 - rows

        if (x < 0 && y < 0) {
            fillRect(bg1, 0, GOAL_BG_WIDTH, GOAL_BG_HEIGHT, 0)
            fillRect(bg0, 0, GOAL_BG_WIDTH, GOAL_BG_HEIGHT, 0)
            copyRect(scratch.b, tmIndex(20, 16 - rows), bg1, 0, GOAL_BG_WIDTH, rows)
            copyRect(scratch.a, tmIndex(20, 18 - rows), bg0, 0, GOAL_BG_WIDTH, rows)
        }
        if (x > 0 && y < 0) {
            val at = tmIndex(19, 0)
            fillRect(bg1, at, GOAL_BG_WIDTH, GOAL_BG_HEIGHT, 0)
            fillRect(bg0, at, GOAL_BG_WIDTH, GOAL_BG_HEIGHT, 0)
            copyRect(scratch.b, tmIndex(20, 16 - rows), bg1, at, GOAL_BG_WIDTH, rows)
            copyRect(scratch.a, tmIndex(20, 18 - rows), bg0, at, GOAL_BG_WIDTH, rows)
        }
        if (x < 0 && y > 0) {
            val at = tmIndex(0, 14)
            fillRect(bg1, at, GOAL_BG_WIDTH, GOAL_BG_HEIGHT, 0)
            fillRect(bg0, at, GOAL_BG_WIDTH, GOAL_BG_HEIGHT, 0)
            copyRect(scratch.b, tmIndex(20, 10), bg1, tmIndex(0, bottom), GOAL_BG_WIDTH, rows)
            copyRect(scratch.a, tmIndex(20, 12), bg0, tmIndex(0, bottom), GOAL_BG_WIDTH, rows)
        }
        if (x > 0 && y > 0) {
            val at = tmIndex(19, 14)
            fillRect(bg1, at, GOAL_BG_WIDTH, GOAL_BG_HEIGHT, 0)
            fillRect(bg0, at, GOAL_BG_WIDTH, GOAL_BG_HEIGHT, 0)
            copyRect(scratch.b, tmIndex(20, 10), bg1, tmIndex(19, bottom), GOAL_BG_WIDTH, rows)
            copyRect(scratch.a, tmIndex(20, 12), bg0, tmIndex(19, bottom), GOAL_BG_WIDTH, rows)
        }

        hw.enableBgSyncByMask(BG0_SYNC_BIT or BG1_SYNC_BIT)
    }

    /**
     * Ported from src/player_interface.c: GoalDisplay_Loop_SlideIn
     *
     * One frame of the slide in: the box grows by 1, 3, 4, 5 and 6 rows; after the fifth it shows
     * the contents and ends the step.
     */
    public fun goalSlideIn(proc: Proc) {
        val state = stateOf(proc)
        copyGoalWindowToBg(
            state.cursorQuadrant, GOAL_SLIDE_IN_WIDTH[state.showHideClock], state.unitClock,
        )

        state.showHideClock++
        if (state.showHideClock == GOAL_SLIDE_IN_WIDTH.size) {
            state.showHideClock = 0
            state.hideContents = false
            engine.breakProc(proc)
        }
    }

    /**
     * Transcribed from src/player_interface.c: GoalDisplay_Loop_SlideOut
     * (not verified: no reference run moves the cursor across the screen half, so the window
     * never slides out; `EndPlayerPhaseSideWindows` ends it instead)
     *
     * One frame of the slide out: the box shrinks to 3, 1 and 0 rows; after the third it unhides
     * the contents, stops retracting, gives up its corner and ends the step.
     */
    public fun goalSlideOut(proc: Proc) {
        val state = stateOf(proc)
        state.hideContents = true
        copyGoalWindowToBg(
            state.cursorQuadrant, GOAL_SLIDE_OUT_WIDTH[state.showHideClock], state.unitClock,
        )

        state.showHideClock++
        if (state.showHideClock == GOAL_SLIDE_OUT_WIDTH.size) {
            state.showHideClock = 0
            state.hideContents = false
            state.isRetracting = false
            state.windowQuadrant = NO_WINDOW
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/player_interface.c: GoalDisplay_Loop_Display
     * (the slide-out branches are not verified: the cursor never changes quadrant in a reference
     * run)
     *
     * Steady state: while the cursor stays put nothing happens. When it moves with the camera
     * still, a cursor in the same quadrant (or one whose quadrant puts the window in the same
     * place) keeps the window; any other move starts the slide out.
     */
    public fun goalDisplay(proc: Proc) {
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
                (CONFIG[now + CONFIG_X_GOAL] == CONFIG[own + CONFIG_X_GOAL] &&
                    CONFIG[now + CONFIG_Y_GOAL] == CONFIG[own + CONFIG_Y_GOAL])
            ) {
                return
            }
        }

        state.isRetracting = true
        engine.breakProc(proc)
    }

    /** `GetUnit(gBmMapUnit[cursor.y][cursor.x])` as a byte offset in [UnitTable.bytes], or -1. */
    private fun unitAtCursor(): Int =
        units.unitOffset(hooks.unitIdAt(bm.playerCursorX, bm.playerCursorY))

    private fun unitCurrentHp(o: Int): Int = StructCodec.s8(units.bytes, o + UNIT_CUR_HP)

    private fun unitStatusIndex(o: Int): Int =
        if (o < 0) 0 else StructCodec.u8(units.bytes, o + UNIT_STATUS) and 0xF

    private fun mmbConfig(state: PlayerInterfaceState, field: Int): Int =
        CONFIG[state.cursorQuadrant * CONFIG_STRIDE + field]

    /**
     * Ported from src/player_interface.c: GetHpBarLeftTile
     *
     * Writes the left end tile of the HP bar to [tm] at [at]: [tileBase] plus [hp] (at most 5).
     */
    public fun getHpBarLeftTile(tm: ShortArray, at: Int, hp: Int, tileBase: Int) {
        tm[at] = (minOf(hp, 5) + tileBase).toShort()
    }

    /**
     * Ported from src/player_interface.c: GetHpBarMidTiles
     *
     * Writes the five middle tiles of the HP bar at [at]. Each tile holds 8 of the bar's 50
     * steps: a full tile for each whole 8 of [hp], then a partial tile showing the remaining
     * `hp & 7` steps, then empty tiles.
     */
    public fun getHpBarMidTiles(tm: ShortArray, at: Int, hp: Int, tileBase: Int) {
        val hpEighth = hp shr 3
        val eighthTileIdx = hp and 7
        for (i in 0 until 5) {
            val fullTileIdx = tileBase + 14
            val emptyTileIdx = tileBase + 6
            tm[at + i] = when {
                i < hpEighth -> fullTileIdx
                i == hpEighth -> emptyTileIdx + eighthTileIdx
                else -> emptyTileIdx
            }.toShort()
        }
    }

    /**
     * Ported from src/player_interface.c: GetHpBarRightTile
     *
     * Writes the right end tile of the HP bar at [at]: `tileBase + 15` plus [hp] clamped to 0..5.
     */
    public fun getHpBarRightTile(tm: ShortArray, at: Int, hp: Int, tileBase: Int) {
        tm[at] = (tileBase + 15 + hp.coerceIn(0, 5)).toShort()
    }

    /**
     * Ported from src/player_interface.c: DrawHpBar
     *
     * Draws the seven tiles of the HP bar of the unit at byte offset [unitOffset] at [at] of
     * [tm]: the current HP scaled to 0..50 steps of the maximum (`50 * hp / maxHp`), split over
     * the left end (first 5 steps), the middle (the next 40) and the right end (the last 5).
     */
    public fun drawHpBar(tm: ShortArray, at: Int, unitOffset: Int, tileBase: Int) {
        val hpCurrent = (50 * unitCurrentHp(unitOffset)).toShort().toInt()
        val hpPercent = (hpCurrent / hooks.unitMaxHp(unitOffset)).toShort().toInt()
        getHpBarLeftTile(tm, at, hpPercent, tileBase)
        getHpBarMidTiles(tm, at + 1, hpPercent - 5, tileBase)
        getHpBarRightTile(tm, at + 6, hpPercent - 45, tileBase)
    }

    /**
     * Ported from src/player_interface.c: ApplyUnitMapUiFramePal
     * (the unknown-faction branch is not verified: a unit's faction is blue, green or red)
     *
     * Loads the window frame palette of [faction] (0 blue, 0x40 green, 0x80 red) into BG
     * palette bank [palId].
     */
    public fun applyUnitMapUiFramePal(faction: Int, palId: Int) {
        val pal = when (faction) {
            FACTION_BLUE -> PAL_INTERFACE_BLUE
            FACTION_RED -> PAL_INTERFACE_RED
            FACTION_GREEN -> PAL_INTERFACE_GREEN
            else -> return
        }
        rom.pal(pal, 16).copyInto(hw.palette, palId * 16)
        hw.enablePaletteSync()
    }

    /**
     * Ported from src/player_interface.c: ClearUnitMapUiStatus
     *
     * Writes the seven status cells at [at] of [tm] for a unit without a status shown: the HP
     * label tiles, blanks, and the "/" tile.
     */
    public fun clearUnitMapUiStatus(tm: ShortArray, at: Int) {
        tm[at] = 0x2120
        tm[at + 1] = 0x2121
        tm[at + 2] = 0
        tm[at + 3] = 0
        tm[at + 4] = 0x213E
        tm[at + 5] = 0
        tm[at + 6] = 0
    }

    /** The tilemap [PlayerInterfaceState.statusTm] indexes: BG0 for Burst, else scratch A. */
    private fun statusBuffer(state: PlayerInterfaceState): ShortArray =
        if (state.statusInBg0) hw.mapBuffer(0) else scratch.a

    /**
     * Ported from src/player_interface.c: PutUnitMapUiStatus
     * (not verified: the status icon branches; no capture has a unit with a status, the Prologue
     * units are all NONE, so only the early return for NONE is compared. The original leaves the
     * offset undefined for status 12, 14 and 15; they are read as the poison icon)
     *
     * Writes the five status icon tiles (tile 0x16F on) and the remaining duration digit at [at]
     * of [tm] for the unit at [unitOffset] after copying the icon graphics of its status to BG
     * tile 0x16F; returns without drawing for no unit and for the statuses NONE, sickness and
     * recovery.
     */
    public fun putUnitMapUiStatus(tm: ShortArray, at: Int, unitOffset: Int) {
        if (unitOffset < 0) return
        val offset = when (unitStatusIndex(unitOffset)) {
            STATUS_POISON -> 0
            STATUS_SLEEP -> 0xA0
            STATUS_SILENCED -> 0x140
            STATUS_BERSERK -> 0x1E0
            STATUS_ATTACK -> 0x280
            STATUS_DEFENSE -> 0x320
            STATUS_CRIT -> 0x3C0
            STATUS_AVOID -> 0x460
            STATUS_PETRIFY, STATUS_13 -> 0x500
            UNIT_STATUS_NONE, STATUS_SICK, UNIT_STATUS_RECOVER -> return
            else -> 0
        }
        val dst = STATUS_ICON_CHR * CHR_SIZE
        for (i in 0 until 5 * CHR_SIZE) {
            hw.vram[dst + i] = rom.u8(GFX_STATUS_TEXT + offset + i).toByte()
        }
        for (i in 0 until 5) tm[at + i] = (STATUS_ICON_CHR + i).toShort()
        tm[at + 5] = 0
        val duration = StructCodec.u8(units.bytes, unitOffset + UNIT_STATUS) shr 4
        tm[at + 6] = (0x1000 + 0x128 + duration).toShort()
    }

    /**
     * Ported from src/player_interface.c: UnitMapUiUpdate
     * (not verified: the RECOVER status clock reset; no capture has a unit with a status.
     * A [unitOffset] of -1 stands for the NULL unit the original passes when the
     * cursor is on an empty tile: its status byte is read as 0)
     *
     * Every 64 frames of [PlayerInterfaceState.unitClock] it refreshes the window's status cells
     * (the HP digits at clock 0 mod 128, the status at 64 mod 128); then, unless the window's
     * contents are hidden, pushes the four HP digit sprites (current and maximum HP, a tens
     * digit left blank when 0).
     */
    public fun unitMapUiUpdate(state: PlayerInterfaceState, unitOffset: Int) {
        var frameCount = state.unitClock
        if (unitStatusIndex(unitOffset) == UNIT_STATUS_RECOVER) frameCount = 0

        if (frameCount and 63 == 0) {
            if (frameCount and 64 != 0) {
                putUnitMapUiStatus(statusBuffer(state), state.statusTm, unitOffset)
                hw.enableBgSyncByMask(BG0_SYNC_BIT)
            } else {
                val cur = unitCurrentHp(unitOffset)
                storeNumberStringOrDashes(if (cur >= 100) 0xFF else cur)
                state.hpCurHi = (numberStr[6] - ZERO_CHAR) and 0xFF
                state.hpCurLo = (numberStr[7] - ZERO_CHAR) and 0xFF
                val max = hooks.unitMaxHp(unitOffset)
                storeNumberStringOrDashes(if (max >= 100) 0xFF else max)
                state.hpMaxHi = (numberStr[6] - ZERO_CHAR) and 0xFF
                state.hpMaxLo = (numberStr[7] - ZERO_CHAR) and 0xFF
                clearUnitMapUiStatus(statusBuffer(state), state.statusTm)
                hw.enableBgSyncByMask(BG0_SYNC_BIT)
            }
        }

        if (!state.hideContents &&
            (frameCount and 64 == 0 || unitStatusIndex(unitOffset) == UNIT_STATUS_NONE)
        ) {
            val xDigits = state.xHp * 8
            val yDigits = state.yHp * 8
            val digits = object8x8
            if (state.hpCurHi != BLANK_DIGIT) {
                oam.putHi(xDigits + 17, yDigits, digits, state.hpCurHi + DIGIT_OAM2)
            }
            oam.putHi(xDigits + 24, yDigits, digits, state.hpCurLo + DIGIT_OAM2)
            if (state.hpMaxHi != BLANK_DIGIT) {
                oam.putHi(xDigits + 41, yDigits, digits, state.hpMaxHi + DIGIT_OAM2)
            }
            oam.putHi(xDigits + 48, yDigits, digits, state.hpMaxLo + DIGIT_OAM2)
        }
    }

    /**
     * Ported from src/face.c: PutFaceTm
     * (the flipped branch is not verified: the window never flips the face)
     *
     * Writes a face tilemap of [dataAddr] (width, height, then one tile number per cell; 0xFF
     * leaves the cell alone) to [tm] at [at], adding [tileRef] to each tile.
     */
    public fun putFaceTm(tm: ShortArray, at: Int, dataAddr: Int, tileRef: Int, isFlipped: Boolean) {
        val width = rom.u8(dataAddr)
        val height = rom.u8(dataAddr + 1)
        var it = dataAddr + 2
        for (iy in 0 until height) {
            val cols = if (isFlipped) width - 1 downTo 0 else 0 until width
            for (ix in cols) {
                val v = rom.u8(it++)
                if (v == 0xFF) continue
                tm[at + iy * TM_STRIDE + ix] = (v + tileRef).toShort()
            }
        }
    }

    /**
     * Ported from src/face.c: UnpackFaceChibiGraphics
     * (the faction chibi branch, a portrait id from 0x7F00, is not verified)
     *
     * Loads the 4x4-tile mini portrait [fid] to BG tile [chr] and its palette to bank [pal].
     */
    public fun unpackFaceChibiGraphics(fid: Int, chr: Int, pal: Int) {
        if (fid >= FID_FACTION_CHIBI) {
            val kind = fid - FID_FACTION_CHIBI
            val src = FACTION_CHIBI_IMAGES[kind]
            val dst = (chr * CHR_SIZE) and 0x1FFFF
            for (i in 0 until 0x200) hw.vram[dst + i] = rom.u8(src + i).toByte()
            rom.pal(FACTION_CHIBI_PALETTES[kind], 16).copyInto(hw.palette, pal * 16)
        } else {
            val info = PORTRAIT_DATA + (fid - 1) * PORTRAIT_SIZE
            rom.decompressInto(rom.u32(info + 4), hw.vram, chr * CHR_SIZE)
            rom.pal(rom.u32(info + 8), 16).copyInto(hw.palette, pal * 16)
        }
        hw.enablePaletteSync()
    }

    /**
     * Ported from src/face.c: PutFaceChibi
     *
     * Loads the mini portrait [fid] (tiles from [chr], palette bank [pal]) and writes its 4x4
     * tilemap to [tm] at [at].
     */
    public fun putFaceChibi(tm: ShortArray, at: Int, fid: Int, chr: Int, pal: Int) {
        unpackFaceChibiGraphics(fid, chr, pal)
        putFaceTm(tm, at, FACE_CHIBI_TM, (chr and 0x3FF) + (pal shl 12), false)
    }

    /**
     * Ported from src/player_interface.c: DrawUnitMapUi
     *
     * Draws the unit window for the unit at [unitOffset] into [scratch]: the name centred in
     * black, the mini portrait (tile 0xF0, palette bank 4), the status cells and HP digits
     * (through [unitMapUiUpdate]), the HP bar, the frame (BG1) with the faction palette in
     * bank 3. Resets the unit clock and places the HP digits by the window's corner.
     */
    public fun drawUnitMapUi(state: PlayerInterfaceState, unitOffset: Int) {
        val a = scratch.a
        a.fill(0, 0, 6 * CHR_SIZE)

        val character = StructCodec.u32(units.bytes, unitOffset + UNIT_CHARACTER)
        val str = messages.getStringFromIndex(rom.u16(character))
        val name = state.text
        text.clearText(name)
        name.x = text.getStringTextCenteredPos(56, str)
        name.colorId = TEXT_COLOR_SYSTEM_BLACK
        text.drawString(name, str)
        text.putText(name, a, tmIndex(5, 1))

        var faceId = unitMiniPortraitId(unitOffset, character)
        if (StructCodec.u32(units.bytes, unitOffset + UNIT_STATE) and US_BIT23 != 0) faceId++
        putFaceChibi(a, tmIndex(1, 1), faceId, 0xF0, 4)

        state.statusTm = tmIndex(5, 3)
        state.statusInBg0 = false
        state.unitClock = 0
        state.xHp = if (mmbConfig(state, CONFIG_X_MINIMUG) < 0) 5 else 23
        state.yHp = if (mmbConfig(state, CONFIG_Y_MINIMUG) < 0) 3 else 17

        unitMapUiUpdate(state, unitOffset)
        drawHpBar(a, tmIndex(5, 4), unitOffset, 0x1140)

        fillTsa(scratch.b, 0, TSA_MINIMUG_BOX, 0x3000)
        applyUnitMapUiFramePal(StructCodec.s8(units.bytes, unitOffset + UNIT_INDEX) and 0xC0, 3)
    }

    /** `GetUnitMiniPortraitId` (bmunit.c, inline) for the unit at [o] of data [character]. */
    private fun unitMiniPortraitId(o: Int, character: Int): Int {
        if (rom.u8(character + 8) != 0) return FID_FACTION_CHIBI + rom.u8(character + 8)
        val portrait = rom.u16(character + 6)
        if (portrait != 0) {
            return if (playSt.chapterIndex == 0x22 && portrait == 0x4A) 0x46 else portrait
        }
        val classData = StructCodec.u32(units.bytes, o + UNIT_CLASS)
        return rom.u16(classData + 8)
    }

    /**
     * Ported from src/player_interface.c: MMB_Init
     *
     * Starts the unit window without a position and reserves its double-buffered text strip of
     * 7 tiles in the active font.
     */
    public fun mmbInit(proc: Proc) {
        val state = stateOf(proc)
        state.windowQuadrant = NO_WINDOW
        val font = text.activeFont
        val name = state.text
        name.chrPosition = font.chrCounter
        name.tileWidth = MMB_TEXT_TILES
        name.dbId = 0
        name.dbEnabled = true
        name.isPrinting = false
        font.chrCounter += MMB_TEXT_TILES * 2
        state.showHideClock = 0
        state.isRetracting = false
    }

    /**
     * Ported from src/player_interface.c: MMB_Loop_OnSideChange
     *
     * Waits while the cursor is on an empty tile. Otherwise picks the window's corner from the
     * cursor's quadrant; if the terrain window holds it, waits; else draws the unit window, notes
     * the cursor and moves on to the slide in.
     */
    public fun mmbOnSideChange(proc: Proc) {
        val state = stateOf(proc)
        val unit = unitAtCursor()
        if (unit < 0) return

        state.hideContents = true
        state.cursorQuadrant = getCursorQuadrant()
        val quadrant = getWindowQuadrant(
            mmbConfig(state, CONFIG_X_MINIMUG),
            mmbConfig(state, CONFIG_Y_MINIMUG),
        )

        val terrainProc = engine.find(terrainScript)
        if (terrainProc != null) {
            val terrainQuadrant = stateOf(terrainProc).windowQuadrant
            if (terrainQuadrant > NO_WINDOW && terrainQuadrant == quadrant) return
        }

        state.windowQuadrant = quadrant
        state.xCursor = bm.playerCursorX
        state.yCursor = bm.playerCursorY
        drawUnitMapUi(state, unit)
        engine.breakProc(proc)
    }

    private fun mmbClearStrip(state: PlayerInterfaceState, y: Int) {
        val left = mmbConfig(state, CONFIG_X_MINIMUG) < 0
        val at = if (left) tmIndex(0, y) else tmIndex(17, 0) + tmIndex(0, y)
        fillRect(hw.mapBuffer(0), at, MMB_WIDTH, MMB_HEIGHT, 0)
        fillRect(hw.mapBuffer(1), at, MMB_WIDTH, MMB_HEIGHT, 0)
        hw.enableBgSyncByMask(BG0_SYNC_BIT or BG1_SYNC_BIT)
    }

    private fun mmbCopyStrip(state: PlayerInterfaceState, y: Int, width: Int) {
        val bg0 = hw.mapBuffer(0)
        val bg1 = hw.mapBuffer(1)
        if (mmbConfig(state, CONFIG_X_MINIMUG) < 0) {
            copyRect(scratch.a, MMB_WIDTH - width, bg0, tmIndex(0, y), width, MMB_HEIGHT)
            copyRect(scratch.b, MMB_WIDTH - width, bg1, tmIndex(0, y), width, MMB_HEIGHT)
        } else {
            copyRect(scratch.a, 0, bg0, tmIndex(30 - width, y), width, MMB_HEIGHT)
            copyRect(scratch.b, 0, bg1, tmIndex(30 - width, y), width, MMB_HEIGHT)
        }
    }

    /**
     * Ported from src/player_interface.c: MMB_Loop_SlideIn
     *
     * One frame of the slide in: clears the window's 13x6 strip of BG0 and BG1 (top or bottom,
     * left or right as the quadrant says) and copies the last 5, 9, 11 and 13 columns of the
     * window from [scratch]; after the fourth frame it shows the contents, ends the step and
     * refreshes the HP digits.
     */
    public fun mmbSlideIn(proc: Proc) {
        val state = stateOf(proc)
        val y = if (mmbConfig(state, CONFIG_Y_MINIMUG) < 0) 0 else MMB_BOTTOM_Y
        mmbClearStrip(state, y)
        mmbCopyStrip(state, y, MMB_SLIDE_IN_WIDTH[state.showHideClock])

        state.showHideClock++
        if (state.showHideClock == 4) {
            state.hideContents = false
            state.showHideClock = 0
            engine.breakProc(proc)
            unitMapUiUpdate(state, unitAtCursor())
        }
    }

    /**
     * Ported from src/player_interface.c: MMB_Loop_SlideOut
     *
     * One frame of the slide out: the same strip clear, then the window narrowed to 11, 7 and 0
     * columns. After the third frame the window has no position (`windowQuadrant` -1) and the
     * step ends.
     */
    public fun mmbSlideOut(proc: Proc) {
        val state = stateOf(proc)
        val y = if (mmbConfig(state, CONFIG_Y_MINIMUG) < 0) 0 else MMB_BOTTOM_Y
        state.hideContents = true
        mmbClearStrip(state, y)
        mmbCopyStrip(state, y, MMB_SLIDE_OUT_WIDTH[state.showHideClock])

        state.showHideClock++
        if (state.showHideClock == 3) {
            state.isRetracting = false
            state.showHideClock = 0
            state.windowQuadrant = NO_WINDOW
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/player_interface.c: sub_808C234
     *
     * Copies the 13x6 window from the BG0 scratch to its corner of BG0 and flags BG0 and BG1 for
     * sync.
     */
    public fun copyUnitWindowToBg0(state: PlayerInterfaceState) {
        val x = if (mmbConfig(state, CONFIG_X_MINIMUG) < 0) 0 else MMB_RIGHT_X
        val y = if (mmbConfig(state, CONFIG_Y_MINIMUG) < 0) 0 else MMB_BOTTOM_Y
        copyRect(scratch.a, 0, hw.mapBuffer(0), tmIndex(x, y), MMB_WIDTH, MMB_HEIGHT)
        hw.enableBgSyncByMask(BG0_SYNC_BIT or BG1_SYNC_BIT)
    }

    /**
     * Ported from src/player_interface.c: MMB_Loop_Display
     *
     * Steady state: counts the unit clock and refreshes the HP digits (every 64th frame also
     * recopies the window). When the cursor has moved: onto a unit within the same corner it
     * redraws (label 1); any other move starts the slide out.
     */
    public fun mmbDisplay(proc: Proc) {
        val state = stateOf(proc)
        val unit = unitAtCursor()

        state.unitClock++
        unitMapUiUpdate(state, unit)
        if (state.unitClock and 63 == 0) copyUnitWindowToBg0(state)

        state.xCursorPrev = state.xCursor
        state.yCursorPrev = state.yCursor
        state.xCursor = bm.playerCursorX
        state.yCursor = bm.playerCursorY

        if (state.xCursor == state.xCursorPrev && state.yCursor == state.yCursorPrev) return

        if (unit >= 0 && engine.find(camMoveScript) == null) {
            val cursorQuadrant = getCursorQuadrant()
            val own = state.cursorQuadrant * CONFIG_STRIDE
            val now = cursorQuadrant * CONFIG_STRIDE
            if (cursorQuadrant == state.cursorQuadrant ||
                (CONFIG[now + CONFIG_X_MINIMUG] == CONFIG[own + CONFIG_X_MINIMUG] &&
                    CONFIG[now + CONFIG_Y_MINIMUG] == CONFIG[own + CONFIG_Y_MINIMUG])
            ) {
                engine.goto(proc, 1)
                return
            }
        }

        state.isRetracting = true
        engine.breakProc(proc)
    }

    /**
     * Ported from src/player_interface.c: MMB_CheckForUnit
     * (the empty-tile branch, label 3, is not verified: the cursor lands on units in the run)
     *
     * Redraws the window for the unit under the cursor and recopies it, or, with none, goes to
     * label 3 (the slide out).
     */
    public fun mmbCheckForUnit(proc: Proc) {
        val state = stateOf(proc)
        val unit = unitAtCursor()
        if (unit < 0) {
            engine.goto(proc, 3)
        } else {
            drawUnitMapUi(state, unit)
            copyUnitWindowToBg0(state)
        }
    }

    /**
     * Ported from src/player_interface.c: GetUnitBurstMapUiOrientationAt
     *
     * The shape (0..5) of the Burst box for a unit at tile position ([x], [y]) on screen: 1 (the
     * tail below the box) by default, 4 (the tail above) in the top rows (or the top 12 rows when
     * the goal window is at the top), one step to the left near the left edge and one to the
     * right near the right edge.
     */
    public fun getUnitBurstMapUiOrientationAt(x: Int, y: Int): Int {
        val cursorQuadrant = getCursorQuadrant()
        var result = 1
        if (y < 6 || (y < 12 && CONFIG[cursorQuadrant * CONFIG_STRIDE + CONFIG_Y_GOAL] < 0)) {
            result = 4
        }
        if (x < 2) result--
        if (x > 22) result++
        return result
    }

    /**
     * Ported from src/player_interface.c: DrawUnitBurstMapUi
     *
     * Draws the Burst box of the unit at [unitOffset] straight into BG0 and BG1 at its screen
     * position: the name centred, the HP bar cells and digits (through [unitMapUiUpdate], so the
     * status cells live in BG0), the frame (BG1, palette bank 3) in the shape given by
     * [getUnitBurstMapUiOrientationAt].
     */
    public fun drawUnitBurstMapUi(state: PlayerInterfaceState, unitOffset: Int) {
        var x = (StructCodec.s8(units.bytes, unitOffset + UNIT_X) * 16 - bm.cameraX) / 8
        var y = (StructCodec.s8(units.bytes, unitOffset + UNIT_Y) * 16 - bm.cameraY) / 8

        val orientation = getUnitBurstMapUiOrientationAt(x, y)

        x += BURST_X_OFFSET[orientation]
        y += BURST_Y_OFFSET[orientation]

        state.xBurst = x
        state.yBurst = y
        state.wBurst = BURST_WIDTH
        state.hBurst = BURST_HEIGHT

        val character = StructCodec.u32(units.bytes, unitOffset + UNIT_CHARACTER)
        val str = messages.getStringFromIndex(rom.u16(character))
        val name = state.text
        text.clearText(name)
        name.x = text.getStringTextCenteredPos(56, str)
        name.colorId = TEXT_COLOR_SYSTEM_BLACK
        text.drawString(name, str)
        val bg0 = hw.mapBuffer(0)
        text.putText(name, bg0, tmIndex(x + BURST_TEXT_X, y + BURST_TEXT_Y))

        state.statusTm = tmIndex(x + 1, y + 3)
        state.statusInBg0 = true
        state.unitClock = 0
        state.xHp = x + 1
        state.yHp = y + 3

        unitMapUiUpdate(state, unitOffset)

        val bg1 = hw.mapBuffer(1)
        fillTsa(bg1, tmIndex(x, y), rom.u32(BURST_TOP_TSA + 4 * orientation), 0x3100)
        fillTsa(bg1, tmIndex(x, y + 1), TSA_BURST_MIDDLE, 0x3100)
        fillTsa(bg1, tmIndex(x, y + 4), rom.u32(BURST_BOTTOM_TSA + 4 * orientation), 0x3100)

        hw.enableBgSyncByMask(BG0_SYNC_BIT or BG1_SYNC_BIT)
        applyUnitMapUiFramePal(StructCodec.s8(units.bytes, unitOffset + UNIT_INDEX) and 0xC0, 3)
    }

    /**
     * Ported from src/player_interface.c: ClearUnitBurstMapUi
     *
     * Erases the drawn Burst box from BG0 and BG1 and marks it as not drawn.
     */
    public fun clearUnitBurstMapUi(state: PlayerInterfaceState) {
        if (state.wBurst == BURST_WIDTH && state.hBurst == BURST_HEIGHT) {
            val at = tmIndex(state.xBurst, state.yBurst)
            fillRect(hw.mapBuffer(0), at, state.wBurst, state.hBurst, 0)
            fillRect(hw.mapBuffer(1), at, state.wBurst, state.hBurst, 0)
            hw.enableBgSyncByMask(BG0_SYNC_BIT or BG1_SYNC_BIT)
            state.wBurst = 0
            state.hBurst = 0
        }
    }

    /**
     * Ported from src/player_interface.c: BurstDisplay_Init
     *
     * Starts the Burst window with nothing shown and reserves its double-buffered text strip of
     * 7 tiles in the active font.
     */
    public fun burstInit(proc: Proc) {
        val state = stateOf(proc)
        val font = text.activeFont
        val name = state.text
        name.chrPosition = font.chrCounter
        name.tileWidth = MMB_TEXT_TILES
        name.dbId = 0
        name.dbEnabled = true
        name.isPrinting = false
        font.chrCounter += MMB_TEXT_TILES * 2
        state.burstUnitId = 0
        state.hideContents = false
        state.showHideClock = 0
        state.wBurst = 0
        state.hBurst = 0
        state.isRetracting = false
    }

    /**
     * Ported from src/player_interface.c: BurstDisplay_Loop_Display
     *
     * Each frame: when the unit under the cursor changes, erases the box and restarts the
     * 8-frame delay; with a unit under the cursor and no camera move, counts the delay (it only
     * counts to 4 while the terrain or goal window is sliding), draws the box on the 8th frame
     * and from then counts the unit clock and refreshes the HP digits.
     */
    public fun burstDisplay(proc: Proc) {
        val state = stateOf(proc)
        state.burstUnitIdPrev = state.burstUnitId
        state.burstUnitId = hooks.unitIdAt(bm.playerCursorX, bm.playerCursorY) and 0xFF

        if (state.burstUnitIdPrev != state.burstUnitId && state.burstUnitIdPrev != 0) {
            clearUnitBurstMapUi(state)
            state.showHideClock = 0
            return
        }

        if (state.burstUnitId == 0 || engine.find(camMoveScript) != null) return

        val terrain = engine.find(terrainScript)?.let { stateOf(it) }
        if (terrain != null && terrain.hideContents) {
            if (state.showHideClock < 4) state.showHideClock++
            return
        }

        val goal = engine.find(goalScript)?.let { stateOf(it) }
        if (goal != null && goal.hideContents) {
            if (state.showHideClock < 4) state.showHideClock++
            return
        }

        state.showHideClock++
        if (state.showHideClock < 8) return

        val unit = units.unitOffset(state.burstUnitId)
        if (state.showHideClock == 8) {
            drawUnitBurstMapUi(state, unit)
        } else {
            state.unitClock++
            state.hideContents = terrain?.hideContents ?: false
            unitMapUiUpdate(state, unit)
        }
    }

    /** `StoreNumberStringOrDashesToSmallBuffer`: 255 or -1 become `::`. */
    private fun storeNumberStringOrDashes(n: Int) {
        numberStr.fill(SPACE, 0, 8)
        numberStr[8] = 0
        if (n == 255 || n == -1) {
            numberStr[7] = ':'.code.toByte()
            numberStr[6] = ':'.code.toByte()
        } else {
            storeDigits(n)
        }
    }

    private fun tmIndex(x: Int, y: Int): Int = y * TM_STRIDE + x

    /**
     * `TileMap_FillRect` (`TmFillRect`, asm/arm.s): fills the rectangle at entry [at] of [tm] with
     * [value]. Its loops run `bpl` after `subs`, so it writes [w] + 1 columns and [h] + 1 rows
     * (the C comment above it says [w] x [h]; the capture shows the extra column and row).
     */
    private fun fillRect(tm: ShortArray, at: Int, w: Int, h: Int, value: Int) {
        for (row in 0..h) {
            val start = at + row * TM_STRIDE
            tm.fill(value.toShort(), start, start + w + 1)
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
        storeDigits(n)
        tm[at] = 0
        tm[at - 1] = 0
        var out = at
        var src = 7
        while (numberStr[src].toInt() != SPACE.toInt()) {
            tm[out--] = (TILEREF_128_PAL2 + numberStr[src] - ZERO_CHAR).toShort()
            src--
        }
    }

    /** The digit loop of `StoreNumberStringToSmallBuffer` (C `%` and `/`), from the right. */
    private fun storeDigits(n: Int) {
        var rest = n
        for (i in 7 downTo 0) {
            numberStr[i] = (ZERO_CHAR + rest % 10).toByte()
            rest /= 10
            if (rest == 0) break
        }
    }

    private val object8x8: ShortArray by lazy { rom.objN(OBJECT_8X8).data }

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
        private const val CONFIG_X_MINIMUG = 2
        private const val CONFIG_Y_MINIMUG = 3
        private const val CONFIG_X_GOAL = 4
        private const val CONFIG_Y_GOAL = 5

        private const val GOAL_TEXT_TILES = 8
        private const val GOAL_NAME_WIDTH = 64
        private const val GOAL_CLEAR_WIDTH = 11
        private const val GOAL_CLEAR_HEIGHT = 9
        private const val GOAL_BG_WIDTH = 12
        private const val GOAL_BG_HEIGHT = 6
        private const val MSG_DEFEAT_ENEMY = 0x19E
        private const val CHAPTER_GOAL_TEXT = 0x8A
        private const val CHAPTER_GOAL_TYPE = 0x8C
        private const val GOAL_TYPE_SEIZE = 0
        private const val GOAL_TYPE_DEFEAT_ALL = 1
        private const val GOAL_TYPE_DEFENSE = 2
        private const val GOAL_TYPE_DEFEAT_BOSS = 3
        private const val GOAL_TYPE_SPECIAL = 4
        private const val TSA_GOAL_TWO_LINES = 0x08A176BC
        private const val TSA_GOAL_ONE_LINE = 0x08A17744

        /** `sGoalSlideInWidthLut`. */
        private val GOAL_SLIDE_IN_WIDTH = intArrayOf(1, 3, 4, 5, 6)

        /** `sGoalSlideOutWidthLut`. */
        private val GOAL_SLIDE_OUT_WIDTH = intArrayOf(3, 1, 0)

        private const val MMB_TEXT_TILES = 7
        private const val MMB_WIDTH = 13
        private const val MMB_HEIGHT = 6
        private const val MMB_BOTTOM_Y = 14
        private const val MMB_RIGHT_X = 18
        private val MMB_SLIDE_IN_WIDTH = intArrayOf(5, 9, 11, 13)
        private val MMB_SLIDE_OUT_WIDTH = intArrayOf(11, 7, 0)

        private const val BURST_WIDTH = 8
        private const val BURST_HEIGHT = 5
        private const val BURST_TEXT_X = 1
        private const val BURST_TEXT_Y = 1
        private val BURST_X_OFFSET = intArrayOf(0, -1, -6, 0, -1, -6)
        private val BURST_Y_OFFSET = intArrayOf(-6, -6, -6, 3, 3, 3)
        private const val BURST_TOP_TSA = 0x08A0186C
        private const val BURST_BOTTOM_TSA = 0x08A01884
        private const val TSA_BURST_MIDDLE = 0x08A17604

        private const val UNIT_X = 0x10
        private const val UNIT_Y = 0x11
        private const val UNIT_CHARACTER = 0x00
        private const val UNIT_CLASS = 0x04
        private const val UNIT_INDEX = 0x0B
        private const val UNIT_STATE = 0x0C
        private const val UNIT_MAX_HP = 0x12
        private const val UNIT_CUR_HP = 0x13
        private const val UNIT_STATUS = 0x30
        private const val UNIT_STATUS_NONE = 0
        private const val STATUS_POISON = 1
        private const val STATUS_SLEEP = 2
        private const val STATUS_SILENCED = 3
        private const val STATUS_BERSERK = 4
        private const val STATUS_ATTACK = 5
        private const val STATUS_DEFENSE = 6
        private const val STATUS_CRIT = 7
        private const val STATUS_AVOID = 8
        private const val STATUS_SICK = 9
        private const val STATUS_PETRIFY = 11
        private const val STATUS_13 = 13
        private const val STATUS_ICON_CHR = 0x16F
        private const val GFX_STATUS_TEXT = 0x08A16DEC
        private const val UNIT_STATUS_RECOVER = 0xA
        private const val US_BIT23 = 1 shl 23
        private const val FACTION_BLUE = 0x00
        private const val FACTION_GREEN = 0x40
        private const val FACTION_RED = 0x80
        private const val FID_FACTION_CHIBI = 0x7F00
        private const val TEXT_COLOR_SYSTEM_BLACK = 5
        private const val BLANK_DIGIT = 0xF0
        private const val DIGIT_OAM2 = 0x2E0 + (8 shl 12)

        private const val OBJECT_8X8 = 0x08590F44
        private const val PORTRAIT_DATA = 0x088ACBE0
        private const val PORTRAIT_SIZE = 28
        private const val FACE_CHIBI_TM = 0x085911C4
        private const val PAL_INTERFACE_BLUE = 0x08A1738C
        private const val PAL_INTERFACE_RED = 0x08A173AC
        private const val PAL_INTERFACE_GREEN = 0x08A173CC
        private const val TSA_MINIMUG_BOX = 0x08A17484
        private val FACTION_CHIBI_IMAGES = intArrayOf(
            0x08599D34, 0x08599B34, 0x08599734, 0x08599734,
            0x08599D34, 0x08599934, 0x08599934, 0x08599D34,
        )
        private val FACTION_CHIBI_PALETTES = intArrayOf(
            0x08599F34, 0x08599F54, 0x08599F74, 0x08599F54,
            0x08599F54, 0x08599F74, 0x08599F54, 0x08599F34,
        )

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
