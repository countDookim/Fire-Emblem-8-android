package fe8.game

import fe8.game.bm.ActiveUnit
import fe8.game.bm.AttackMapSelect
import fe8.game.bm.AttackRange
import fe8.game.bm.AttackTargets
import fe8.game.bm.BMapMain
import fe8.game.bm.BattleForecast
import fe8.game.bm.BattleGenerate
import fe8.game.bm.BattleMapStart
import fe8.game.bm.BmBattle
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.CombatAction
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.ItemInfo
import fe8.game.bm.ItemMenuLine
import fe8.game.bm.MenuItemPanel
import fe8.game.bm.ChapterMapGfx
import fe8.game.bm.MapAnimApi
import fe8.game.bm.MapAnimInfoBox
import fe8.game.bm.MapAnimScanline
import fe8.game.bm.MapCursor
import fe8.game.bm.MapMenu
import fe8.game.bm.MoveLimitView
import fe8.game.bm.Mu
import fe8.game.bm.MuProc
import fe8.game.bm.PlaySt
import fe8.game.bm.PlayerInterface
import fe8.game.bm.PlayerPhase
import fe8.game.bm.SelectTargets
import fe8.game.bm.TargetSelection
import fe8.game.bm.UnitActionMenu
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitFacing
import fe8.game.bm.UnitSpriteHandles
import fe8.game.bm.UnitTable
import fe8.game.event.EventInfos
import fe8.game.save.PidStats
import fe8.game.scene.IntBinaryOperator
import fe8.game.scene.IntConsumer
import fe8.game.scene.IntSupplier
import fe8.game.scene.IntUnaryOperator
import fe8.game.scene.TalkScene
import fe8.game.ui.GreenText
import fe8.game.ui.Menu
import fe8.game.ui.UiFrame
import fe8.game.ui.UiHand
import gbafe.core.flags.EventFlags
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc

/**
 * Connects the ported player phase parts to [PlayerPhase.hooks] for the real game (PP-17): the
 * range squares, the unit and map menus, the side windows, the MU walk, the post-action events
 * and the suspend save. Everything is created once here; the hooks allocate nothing per frame.
 *
 * What stays a stand-in is listed with its plan row on the declarations below: the attack
 * (`M6-C`), the item commands and traps (`M6-H`), the info screens (`M6-F`), the suspend and end
 * events (`M6-E`).
 */
internal class PlayerPhaseWiring(
    private val engine: ProcEngine,
    private val rom: RomView,
    private val hw: gbafe.render.BgHardware,
    private val bm: BmSt,
    private val playSt: PlaySt,
    private val map: BmMap,
    private val units: UnitTable,
    private val flags: EventFlags,
    private val keys: KeyStatus,
    private val clock: IntSupplier,
    private val sound: SoundWrapper,
    private val loop: GameLoop,
    private val camMove: CamMove,
    private val render: BmMapRender,
    private val entityMaps: EntityBmMaps,
    private val talk: TalkScene,
    private val handles: UnitSpriteHandles,
    /** `InitBmBgLayers` and `LoadObjUIGfx` for the map animation's clean-up. */
    private val mapGfx: ChapterMapGfx,
    /** The per-line palette writes of the info box's gradient (`LineTable.writePalette`). */
    private val gradientLines: MapAnimScanline.PaletteLineWriter,
    private val mu: Mu,
    private val active: ActiveUnit,
    private val unitLoader: UnitLoader,
    private val battleMapKind: () -> Int,
    /** `gTrapArray`, read by the attack target list. */
    traps: ByteArray,
    /** `ResetIconGraphics`, called by the unit menu's Attack. */
    private val resetIcons: () -> Unit,
    /** `LoadIconPalettes(palId)`. */
    private val loadIconPalettes: IntConsumer,
    /** `DrawnIconLookupTable` and `IconGFXIDLookupTable`, read by the item rows. */
    private val drawnIconLookupTable: ByteArray,
    private val iconGfxIdLookupTable: ByteArray,
    private val mapCursor: MapCursor,
    /** The battle units and the forecast battle (`gBattleActor`, `BattleGenerateSimulation`). */
    private val battle: BmBattle,
    private val battleGenerate: BattleGenerate,
    /** `EventEngineExists`, which the forecast waits on. */
    private val eventEngineExists: () -> Boolean,
    /** `BattleEventEngineExists`. */
    private val battleEventEngineExists: () -> Boolean = { false },
    /** Per-unit battle stats (`gPidStatsData`). */
    private val pidStats: PidStats? = null,
) {
    private val items = ItemInfo(rom)

    /** The unit menu, the map menu and their callbacks. */
    val menu: Menu = Menu(
        engine, rom, hw, talk.text, talk.messages, keys, UiHand(rom, loop.sprites),
        GreenText(engine, rom, hw, clock), camMove.script,
    ).also {
        it.hooks.lockGame = { loop.lockGame() }
        it.hooks.unlockGame = { loop.unlockGame() }
        it.hooks.playSoundEffect = { song -> sound.playSoundEffect(song) }
        it.hooks.gameClock = clock
    }

    /** The range squares of the movement and attack range. */
    val moveLimitView: MoveLimitView = MoveLimitView(engine, bm, map, hw, rom, render).also {
        it.gameClock = clock
    }

    /** The terrain, goal and unit windows. */
    val playerInterface: PlayerInterface = PlayerInterface(
        engine, bm, playSt, units, rom, hw, loop.oam, talk.text, talk.messages, camMove.script,
        GreenText(engine, rom, hw, clock),
    )

    private val attackRange =
        AttackRange(map, units, items, entityMaps, unitLoader)
    private val unitMenu = UnitActionMenu(units, active, items, unitLoader, map, attackRange, hw)
    private val itemLine = ItemMenuLine(
        talk.text, items, talk.messages, hw, rom, drawnIconLookupTable, iconGfxIdLookupTable,
    )
    /** The battle forecast window; set once [wire] has run the attack select. */
    lateinit var forecast: BattleForecast
        private set

    /** Whether [forecast] has been built. */
    val isForecastReady: Boolean get() = this::forecast.isInitialized
    private val menuItemPanel = MenuItemPanel(
        engine, units, battle, items, unitLoader, bm, talk.text, talk.messages, hw, rom,
        drawnIconLookupTable, iconGfxIdLookupTable,
    ) { x, y, isDown -> forecast.updateStatArrowSprites(x, y, isDown) }
    private val attackTargets =
        AttackTargets(map, units, items, attackRange, traps, SelectTargets())
    private val targetSelection = TargetSelection(
        engine, attackTargets.targets, keys, camMove,
    ) { UiFrame.clearBg0Bg1(hw) }.also {
        it.hooks.playSoundEffect = { song -> sound.playSoundEffect(song) }
        it.hooks.endFaceById = { slot -> menu.hooks.endFaceById(slot) }
        it.hooks.putMapCursor =
            PlayerPhase.PutMapCursor { x, y, kind -> mapCursor.put(x, y, kind) }
        it.hooks.lockGame = { loop.lockGame() }
        it.hooks.unlockGame = { loop.unlockGame() }
    }

    private lateinit var attackSelect: AttackMapSelect

    private val mapMenu = MapMenu(
        engine, rom, playSt, flags, hw, talk.text, talk.messages,
    ) { fe8PlayerPhaseScript() }
    private lateinit var playerPhaseScript: () -> gbafe.proc.ProcScript

    private fun fe8PlayerPhaseScript() = playerPhaseScript()

    /**
     * Sets every hook of [phase] that has a ported implementation; the rest keeps its default
     * (stand-ins, M6-C to M6-I, listed on [PlayerPhase.Hooks]). [main] is the BMapMain whose
     * suspend save is written, [infos] the event lists.
     */
    fun wire(phase: PlayerPhase, main: BMapMain, infos: EventInfos) {
        playerPhaseScript = { phase.script }
        wireInterface(phase)
        wireMenus(phase)
        wireRange(phase)
        wireMovement(phase)
        wireAction(phase, main, infos)
        wireStandIns(phase)
    }

    private fun wireInterface(phase: PlayerPhase) {
        val pi = playerInterface
        pi.hooks.terrainAt = IntBinaryOperator { x, y -> map.terrain[y, x] }
        pi.hooks.unitIdAt = IntBinaryOperator { x, y -> map.unit[y, x] }
        // -1 is "no unit": the cursor can leave the unit before the box's slide-in ends, and the
        // original then reads a NULL unit (BIOS memory, not reproduced); the digits are not shown.
        pi.hooks.unitMaxHp = IntUnaryOperator { offset ->
            if (offset < 0) 0 else unitLoader.getUnitMaxHp(offset)
        }
        pi.hooks.objWindowDisabled = { flags.checkFlag(EVFLAG_OBJWINDOW_DISABLE) }
        pi.hooks.isSkirmish = { battleMapKind() == fe8.game.bm.BattleMapKind.SKIRMISH }
        phase.hooks.startSideWindows = ProcFunc { pi.startPlayerPhaseSideWindows() }
        phase.hooks.endSideWindows = ProcFunc { pi.endPlayerPhaseSideWindows() }
    }

    /**
     * `gSelectInfo_Attack`: [AttackMapSelect]. The battle forecast window slides in and shows
     * its numbers (its arrows and multipliers are MC-12b); A on a target runs the combat action
     * ([wireAction]).
     */
    private fun wireAttackSelect(phase: PlayerPhase) {
        val facing = UnitFacing(units, active, phase.workingMovementScript)
        facing.setAutoMuMoveScript = { script -> mu.setAutoMuMoveScript(script) }
        forecast = BattleForecast(
            engine, battle, battleGenerate, items, units, active, playSt, bm, map, hw, rom,
            talk.text, talk.messages, drawnIconLookupTable, iconGfxIdLookupTable,
            loop.sprites, clock,
        )
        forecast.hooks.eventEngineExists = eventEngineExists
        val select = AttackMapSelect(
            engine, attackTargets.targets, active, units, facing, camMove, forecast, hw,
        )
        select.hooks.hideMoveRangeGraphics = { moveLimitView.hideMoveRangeGraphics() }
        select.hooks.lockGame = { loop.lockGame() }
        select.hooks.unlockGame = { loop.unlockGame() }
        select.hooks.restartWeaponList = { unitMenu.unitActionMenuAttackFromProc() }
        attackSelect = select
    }

    private fun wireMenus(phase: PlayerPhase) {
        wireAttackSelect(phase)
        unitMenu.hooks.weaponTargetCount = { unit, item ->
            attackTargets.makeTargetListForWeapon(unit, item)
            attackTargets.targets.getSelectTargetCount()
        }
        unitMenu.hooks.makeTargetListForWeapon = { unit, item ->
            attackTargets.makeTargetListForWeapon(unit, item)
        }
        unitMenu.hooks.cursorScreenX = { bm.cursorTargetX - bm.cameraX }
        unitMenu.hooks.startAttackTargetSelection = {
            targetSelection.newTargetSelection(attackSelect)
        }
        unitMenu.hooks.drawItemMenuLine = UnitActionMenu.ItemLineDrawer { row, item, usable, at ->
            itemLine.drawItemMenuLine(row, item, usable, hw.mapBuffer(0), at)
        }
        unitMenu.hooks.chapterIndex = IntSupplier { playSt.chapterIndex }
        unitMenu.hooks.startFace = UnitActionMenu.FaceStarter { slot, fid, x, y, disp ->
            talk.face.startFace(slot, fid, x, y, disp)
        }
        unitMenu.hooks.setFaceBlinkControlById = UnitActionMenu.BlinkSetter { slot, control ->
            talk.face.setFaceBlinkControlById(slot, control)
        }
        unitMenu.hooks.forceMenuItemPanel = UnitActionMenu.PanelStarter { m, unit, x, y ->
            menuItemPanel.forceMenuItemPanel(m, unit, x, y)
        }
        unitMenu.hooks.updateMenuItemPanel =
            IntConsumer { slot -> menuItemPanel.updateMenuItemPanel(slot) }
        menu.hooks.endFaceById = { slot -> talk.face.endFaceById(slot) }
        unitMenu.hooks.resetTextFont = { talk.text.resetTextFont() }
        // `IsPositionMagicSealed` is not ported: no tile counts as magic-sealed.
        unitMenu.hooks.isPositionMagicSealed = { _, _ -> false }
        unitMenu.hooks.resetIconGraphics = resetIcons
        unitMenu.hooks.loadIconPalettes = loadIconPalettes
        unitMenu.hooks.displayMoveRangeGraphics =
            IntConsumer { flags -> moveLimitView.displayMoveRangeGraphics(flags) }
        unitMenu.hooks.hideMoveRangeGraphics = { moveLimitView.hideMoveRangeGraphics() }
        // The unit menu commands without a port are hidden.
        unitMenu.hooks.unportedUsability = { _, _ -> Menu.MENU_NOTSHOWN }
        unitMenu.install(menu, rom)
        mapMenu.hooks.battleMapKind = battleMapKind
        // The map menu's Guide entry is never locked and always uses the normal text color.
        mapMenu.hooks.isGuideLocked = { false }
        mapMenu.hooks.guideTextShowGreenOrNormal = { true }
        mapMenu.install(menu, rom)
        for (def in intArrayOf(
            Menu.UNIT_ACTION_MENU_DEF, Menu.MAP_MENU_DEF, Menu.WEAPON_SELECT_MENU_DEF,
        )) {
            registerMenuStandIns(def)
        }
        phase.hooks.startUnitActionMenu = { x ->
            talk.text.resetTextFont() // `ResetTextFont` of PlayerPhase_ApplyUnitMovement
            menu.startSemiCenteredOrphanMenu(Menu.UNIT_ACTION_MENU_DEF, x, 1, 22)
        }
        phase.hooks.startMapMenu = { x ->
            menu.startOrphanMenuAdjusted(Menu.MAP_MENU_DEF, x, 1, 0x17)
        }
    }

    /**
     * For the menu `def` (a `MenuDef` address), registers a do-nothing callback for every
     * callback in the def and its `MenuItemDef`s that no port registered: the menu-level
     * `onInit`, `onEnd`, `onBPress` and `onRPress`, and each item's `onDraw`, `onIdle`,
     * `onSwitchIn` and `onSwitchOut`. The `onSelected` of an unported command plays the refusal
     * sound (`SONG_6C`) and returns 0 (keeps the menu open). Stand-in: Item and the other
     * commands (`M6-H`) and the map menu's Unit, Status, Guide, Options and Suspend (`M6-F`,
     * Suspend `M6-E`).
     */
    private fun registerMenuStandIns(def: Int) {
        for (offset in intArrayOf(0x0C, 0x10, 0x18, 0x1C)) {
            val callback = rom.u32(def + offset)
            if (callback != 0 && callback !in menu.callbacks) {
                menu.callbacks[callback] = Menu.MenuFunc { _, _, _, _ -> 0 }
            }
        }
        val refuse = Menu.MenuFunc { _, _, _, _ ->
            sound.playSoundEffect(SONG_REFUSED)
            0
        }
        val nothing = Menu.MenuFunc { _, _, _, _ -> 0 }
        val list = rom.u32(def + 8)
        var i = 0
        while (rom.u32(list + ITEM_DEF_SIZE * i + 0xC) != 0) {
            val item = list + ITEM_DEF_SIZE * i
            val select = rom.u32(item + 0x14)
            if (select != 0 && select !in menu.callbacks) menu.callbacks[select] = refuse
            for (offset in intArrayOf(0x10, 0x18, 0x1C, 0x20)) {
                val callback = rom.u32(item + offset)
                if (callback != 0 && callback !in menu.callbacks) menu.callbacks[callback] = nothing
            }
            i++
        }
    }

    private fun wireRange(phase: PlayerPhase) {
        val view = moveLimitView
        val h = phase.hooks
        h.displayMoveRangeGraphics = { flags -> view.displayMoveRangeGraphics(flags) }
        h.hideMoveRangeGraphics = ProcFunc { view.hideMoveRangeGraphics() }
        h.getUnitWeaponUsabilityBits = { unit -> weaponUsabilityBits(unit) }
        h.generateAttackRange = { unit ->
            // The part of `DisplayUnitEffectRange` for the weapon case; a unit with only a staff
            // or no usable weapon shows the blue squares alone (staff range: M6-C).
            map.bmMapFill(map.other, 0)
            map.bmMapFill(map.range, 0)
            if (weaponUsabilityBits(unit) == USEBIT_WEAPON) {
                attackRange.generateUnitCompleteAttackRange(unit)
                PlayerPhase.LIMITVIEW_RED or PlayerPhase.LIMITVIEW_BLUE
            } else {
                PlayerPhase.LIMITVIEW_BLUE
            }
        }
    }

    /**
     * `GetUnitWeaponUsabilityBits` for the weapon bit only: 1 when the unit holds a weapon it can
     * wield now, else 0. Stand-in until M6-C (the staff bit needs `CanUnitUseStaff`).
     */
    private fun weaponUsabilityBits(unit: Int): Int {
        val o = units.unitOffset(unit)
        for (i in 0 until UnitLoader.UNIT_ITEM_COUNT) {
            val item = units.item(unit, i) and 0xFFFF
            if (item == 0) break
            if (unitMenu.canUnitUseWeaponNow(o, item)) return USEBIT_WEAPON
        }
        return 0
    }

    private fun wireMovement(phase: PlayerPhase) {
        val h = phase.hooks
        h.muExists = gbafe.proc.ProcBoolFunc { mu.muExists() }
        h.muExistsActive = { mu.muExistsActive() }
        h.setAutoMuMoveScript = { script -> mu.setAutoMuMoveScript(script) }
        h.endAllMus = ProcFunc { mu.endAllMus() }
        h.showUnitSprite = { id -> handles.showUnitSprite(id) }
        h.hideUnitSprite = { id -> handles.hideUnitSprite(id) }
        h.startMu = phase.hover.hooks.startMu
        h.setAutoMuDefaultFacing = ProcFunc {
            val proc = engine.find(mu.script)
            if (proc != null) mu.setMuDefaultFacing(proc.state as MuProc)
        }
    }

    private fun wireAction(phase: PlayerPhase, main: BMapMain, infos: EventInfos) {
        val h = phase.hooks
        h.writeSuspendSave = { slot ->
            main.suspendPointType = active.action.suspendPointType
            main.hooks.writeSuspendSave(slot)
        }
        h.suspend = ProcFunc {
            active.action.suspendPointType = SUSPEND_POINT_PLAYERIDLE
            h.writeSuspendSave(BMapMain.SAVE_ID_SUSPEND)
        }
        h.checkForWaitEvents = {
            infos.checkForWaitEvents(units.xPos(active.unit), units.yPos(active.unit))
        }
        h.runWaitEvents = { infos.runWaitEvents(units.xPos(active.unit), units.yPos(active.unit)) }
        h.shouldCallEndEvent = { infos.shouldCallEndEvent() }
        h.startDestSelectedEvent = { infos.startDestSelectedEvent() }
        h.startAfterUnitMovedEvent = { infos.startAfterUnitMovedEvent() }
        forecast.hooks.tutorialPending = { infos.checkBattleForecastTutorialEvent() }
        val combat = CombatAction(engine, battle, battleGenerate, units, active)
        combat.hooks.doesBmxFadeExist = gbafe.proc.ProcBoolFunc { p ->
            h.doesBmxFadeExist.invoke(p)
        }
        // EP-13: the map battle animation start (MOVEUNITs and the animation proc).
        val mapAnim = MapAnimApi(
            engine, bm, battle, battleGenerate, units, unitLoader, mu, rom, hw, camMove, flags,
        )
        mapAnim.hooks.renderBmMap = { render.renderBmMap() }
        mapAnim.hooks.hideUnitSprite = { id -> handles.hideUnitSprite(id) }
        mapAnim.hooks.showUnitSprite = { id -> handles.showUnitSprite(id) }
        mapAnim.hooks.spritePalette = { id -> handles.getUnitSpritePalette(id) }
        mapAnim.hooks.eventEngineExists = eventEngineExists
        mapAnim.hooks.resetText = { talk.text.resetText() }
        mapAnim.hooks.battleEventEngineExists = battleEventEngineExists
        mapAnim.hooks.callBattleQuoteEventsIfAny = { pidA, pidB ->
            infos.callBattleQuoteEventsIfAny(pidA, pidB)
        }
        val infoBox = MapAnimInfoBox(engine, mapAnim.state, rom, talk.text, talk.messages, hw)
        infoBox.scanline.paletteWriter = gradientLines
        mapAnim.hooks.startMapAnimInfoWindow = { x, y, p -> infoBox.startMapAnimInfoWindow(x, y, p) }
        mapAnim.hooks.endMapAnimInfoWindow = { infoBox.endMapAnimInfoWindow() }
        mapAnim.hooks.resetTextFont = { talk.text.resetTextFont() }
        mapAnim.hooks.initBmBgLayers = { mapGfx.initBmBgLayers() }
        mapAnim.hooks.loadUiFrameGraphics = {
            UiFrame.loadUiFrameGraphics(hw, rom, playSt.windowColor)
        }
        mapAnim.hooks.loadObjUiGfx = { mapGfx.loadObjUiGfx() }
        mapAnim.hooks.checkBattleDefeatTalk = { pid -> infos.checkBattleDefeatTalk(pid) }
        mapAnim.hooks.displayDefeatTalkForPid = { pid -> infos.displayDefeatTalkForPid(pid) }
        combat.hooks.beginBattleAnimations = ProcFunc { mapAnim.beginBattleAnimations() }
        h.actionCombat = gbafe.proc.ProcBoolFunc { combat.actionCombat(it) }
        h.battleAnimPreconfOff =
            { combat.hooks.getBattleAnimPreconfType.get() == CombatAction.PLAY_ANIMCONF_OFF }
        h.cursorSound = PlayerPhase.SoundEffect { song -> sound.playSoundEffect(song) }
        h.playSoundEffect = PlayerPhase.SoundEffect { song -> sound.playSoundEffect(song) }
    }

    /**
     * Turns on the A-button select flow ([PlayerPhase.Hooks.unitSelectEnabled]). The hooks left
     * at their defaults are stand-ins that never block the phase: the cursor, unit menu and Wait
     * keep working; the plan rows are on the hooks' declarations in [PlayerPhase.Hooks]
     * (`M6-C`, `M6-E`, `M6-F`, `M6-H`, `M6-I`).
     */
    private fun wireStandIns(phase: PlayerPhase) {
        phase.hooks.unitSelectEnabled = true
        if (pidStats != null) {
            phase.hooks.pidStatsAddActAmt = { pid -> pidStats.pidStatsAddActAmt(pid) }
            phase.hooks.pidStatsAddSquaresMoved = { charId, amount ->
                pidStats.pidStatsAddSquaresMoved(charId, amount)
            }
        }
    }

    private companion object {
        const val ITEM_DEF_SIZE = 0x24
        const val EVFLAG_OBJWINDOW_DISABLE = 102
        const val USEBIT_WEAPON = 1
        const val SUSPEND_POINT_PLAYERIDLE = 0
        const val SONG_REFUSED = 0x6C
    }
}
