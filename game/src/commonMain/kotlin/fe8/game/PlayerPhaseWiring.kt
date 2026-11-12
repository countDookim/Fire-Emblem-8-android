package fe8.game

import fe8.game.bm.ActiveUnit
import fe8.game.bm.AttackRange
import fe8.game.bm.AttackTargets
import fe8.game.bm.BMapMain
import fe8.game.bm.BattleMapStart
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.ItemInfo
import fe8.game.bm.MapMenu
import fe8.game.bm.MoveLimitView
import fe8.game.bm.Mu
import fe8.game.bm.MuProc
import fe8.game.bm.PlaySt
import fe8.game.bm.PlayerInterface
import fe8.game.bm.PlayerPhase
import fe8.game.bm.SelectTargets
import fe8.game.bm.UnitActionMenu
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitSpriteHandles
import fe8.game.bm.UnitTable
import fe8.game.event.EventInfos
import fe8.game.scene.IntBinaryOperator
import fe8.game.scene.IntConsumer
import fe8.game.scene.IntSupplier
import fe8.game.scene.IntUnaryOperator
import fe8.game.scene.TalkScene
import fe8.game.ui.GreenText
import fe8.game.ui.Menu
import fe8.game.ui.UiHand
import gbafe.core.flags.EventFlags
import gbafe.input.KeyStatus
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
    private val attackTargets =
        AttackTargets(map, units, items, attackRange, traps, SelectTargets())
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

    private fun wireMenus(phase: PlayerPhase) {
        unitMenu.hooks.weaponTargetCount = { unit, item ->
            attackTargets.makeTargetListForWeapon(unit, item)
            attackTargets.targets.getSelectTargetCount()
        }
        unitMenu.hooks.makeTargetListForWeapon = { unit, item ->
            attackTargets.makeTargetListForWeapon(unit, item)
        }
        unitMenu.hooks.cursorScreenX = { bm.cursorTargetX - bm.cameraX }
        // Stand-in until MC-5 (the target selection): back to the unit menu, so the player is
        // never stuck. The weapon is already equipped; the real cursor is MC-5.
        unitMenu.hooks.startAttackTargetSelection = {
            menu.startSemiCenteredOrphanMenu(
                Menu.UNIT_ACTION_MENU_DEF, bm.cursorTargetX - bm.cameraX, 1, 22,
            )
            moveLimitView.hideMoveRangeGraphics()
        }
        unitMenu.hooks.isPositionMagicSealed = { _, _ -> false }
        unitMenu.hooks.resetIconGraphics = resetIcons
        unitMenu.hooks.loadIconPalettes = loadIconPalettes
        unitMenu.hooks.displayMoveRangeGraphics =
            IntConsumer { flags -> moveLimitView.displayMoveRangeGraphics(flags) }
        unitMenu.hooks.hideMoveRangeGraphics = { moveLimitView.hideMoveRangeGraphics() }
        unitMenu.hooks.unportedUsability = { _, _ -> Menu.MENU_NOTSHOWN }
        unitMenu.install(menu, rom)
        mapMenu.hooks.battleMapKind = battleMapKind
        mapMenu.hooks.isGuideLocked = { false }
        mapMenu.hooks.guideTextShowGreenOrNormal = { true }
        mapMenu.install(menu, rom)
        for (def in intArrayOf(
            Menu.UNIT_ACTION_MENU_DEF, Menu.MAP_MENU_DEF, Menu.WEAPON_SELECT_MENU_DEF,
        )) {
            registerMenuStandIns(def)
        }
        phase.hooks.startUnitActionMenu = { x ->
            menu.startSemiCenteredOrphanMenu(Menu.UNIT_ACTION_MENU_DEF, x, 1, 22)
        }
        phase.hooks.startMapMenu = { x ->
            menu.startOrphanMenuAdjusted(Menu.MAP_MENU_DEF, x, 1, 0x17)
        }
    }

    /**
     * Gives every menu callback of the two menus that no port registered a stand-in that does
     * nothing: the select of an unported command plays the refusal sound (`SONG_6C`) and keeps
     * the menu open, the switch and idle callbacks do nothing. Stand-in: Item and the other
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
        h.cursorSound = PlayerPhase.SoundEffect { song -> sound.playSoundEffect(song) }
        h.playSoundEffect = PlayerPhase.SoundEffect { song -> sound.playSoundEffect(song) }
    }

    /**
     * The hooks without a port. Each is a stand-in that never blocks the phase: the cursor,
     * unit menu and Wait keep working; the plan rows are on the hooks' declarations in
     * [PlayerPhase.Hooks] (`M6-C`, `M6-E`, `M6-F`, `M6-H`, `M6-I`).
     */
    private fun wireStandIns(phase: PlayerPhase) {
        phase.hooks.unitSelectEnabled = true
    }

    private companion object {
        const val ITEM_DEF_SIZE = 0x24
        const val EVFLAG_OBJWINDOW_DISABLE = 102
        const val USEBIT_WEAPON = 1
        const val SUSPEND_POINT_PLAYERIDLE = 0
        const val SONG_REFUSED = 0x6C
    }
}
