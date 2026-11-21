package fe8.testing

import fe8.game.RomView
import fe8.game.bm.AttackRange
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.PlaySt
import fe8.game.bm.CamMove
import fe8.game.bm.ActiveUnit
import fe8.game.bm.ItemInfo
import fe8.game.bm.MapTraps
import fe8.game.bm.UnitActionMenu
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.text.Fe8Text
import fe8.game.ui.GreenText
import fe8.game.ui.Menu
import fe8.game.ui.UiHand
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextHost
import gbafe.text.TextSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The unit action menu's item callbacks (src/bmmenu.c) against the Prologue captures.
 *
 * The menu is started as its caller does (`StartSemiCenteredOrphanMenu(&gUnitActionMenuDef, ...)`
 * from the units, `gActiveUnit` and `gActionData` of the frame before) with the ported Attack and
 * Item availability, `MenuAlwaysEnabled`, and `EffectWait`. The other items' availability and
 * `MakeTargetListForWeapon` are stand-ins: the former answers what the capture's item procs show,
 * the latter counts the enemy-faction units within the weapon's range from the unit array.
 * (a) The menu proc at its first frame (`ram_trace_prologue_select_move` f1314 and
 * `ram_trace_prologue_attack` f1365) holds the same items, in the same order and availability, as
 * the capture's `MenuProc`/`MenuItemProc`s. (b) The select window runs on frame by frame with the
 * run's inputs; A on Wait (f1355) must give `gActionData` of f1356 and end the menu.
 */
class UnitActionMenuDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "AttackCommandUsability", "ItemCommandUsability", "CanUnitUseWeaponNow", "EffectWait",
    )
    fun `unit menu without a target lists Item and Wait and Wait sets the action`() {
        assumeInputs()
        val sim = Sim("ram_trace_prologue_select_move", 1314)
        sim.start()
        assertEquals(listOf(25 to 1, 28 to 1), sim.items, "items (number to availability)")
        sim.compareItems()
        sim.run(1355)
        assertEquals(
            listOf(UnitActionMenu.EFFECT_WAIT), sim.selectCallbacks, "select callback of A",
        )
        assertEquals(0x17, sim.selectResult, "menu actions returned by EffectWait")
        sim.compareAction(1356)
        assertEquals(0, sim.actionTypeBefore, "gActionData.unitActionType before A")
        assertEquals(1, sim.active.action.unitActionType, "unitActionType after A")
    }

    @Test
    @VerifiesPort("AttackCommandUsability", "ItemCommandUsability", "CanUnitUseWeaponNow")
    fun `unit menu with an enemy in range lists Attack Item and Wait`() {
        assumeInputs()
        val sim = Sim("ram_trace_prologue_attack", 1365)
        sim.start()
        assertEquals(listOf(1 to 1, 25 to 1, 28 to 1), sim.items, "items (number to availability)")
        sim.compareItems()
        assertEquals(1, sim.targetCountCalls.size.coerceAtMost(1), "target count asked")
    }

    @Test
    @VerifiesPort(
        "ArenaCommandUsability",
        "ArmoryCommandUsability",
        "AttackBallistaCommandUsability",
        "ChestCommandUsability",
        "DanceCommandUsability",
        "DoorCommandUsability",
        "DropUsability",
    )
    fun `unit menu build at f4181 evaluates usability A callbacks to NOTSHOWN`() {
        assumeInputs()
        val sim = Sim("ram_trace_prologue_clear", 4181)
        sim.start()
        assertEquals(listOf(1 to 1, 25 to 1, 28 to 1), sim.items, "items (number to availability)")
        sim.compareItems()

        assertEquals(
            Menu.MENU_NOTSHOWN,
            sim.unitMenu.attackBallistaCommandUsability(0x0859CA7C, 2),
            "AttackBallistaCommandUsability",
        )
        assertEquals(
            Menu.MENU_NOTSHOWN,
            sim.unitMenu.danceCommandUsability(0x0859CB30, 7),
            "DanceCommandUsability",
        )
        assertEquals(
            Menu.MENU_NOTSHOWN,
            sim.unitMenu.chestCommandUsability(0x0859CC50, 15),
            "ChestCommandUsability",
        )
        assertEquals(
            Menu.MENU_NOTSHOWN,
            sim.unitMenu.doorCommandUsability(0x0859CC74, 16),
            "DoorCommandUsability",
        )
        assertEquals(
            Menu.MENU_NOTSHOWN,
            sim.unitMenu.armoryCommandUsability(0x0859CC98, 17),
            "ArmoryCommandUsability",
        )
        assertEquals(
            Menu.MENU_NOTSHOWN,
            sim.unitMenu.arenaCommandUsability(0x0859CD04, 20),
            "ArenaCommandUsability",
        )
        assertEquals(
            Menu.MENU_NOTSHOWN,
            sim.unitMenu.dropUsability(0x0859CD4C, 22),
            "DropUsability",
        )
    }

    @Test
    fun `usability A callbacks branch coverage`() {
        assumeInputs()
        val sim = Sim("ram_trace_prologue_clear", 4181)
        sim.start()
        val menu = sim.unitMenu

        // DropUsability
        sim.unitMenu.hooks.dropTargetCount = { 1 }
        assertEquals(Menu.MENU_NOTSHOWN, menu.dropUsability(0, 22))

        // DanceCommandUsability
        sim.unitMenu.hooks.refreshTargetCount = { 1 }
        assertEquals(Menu.MENU_NOTSHOWN, menu.danceCommandUsability(0, 7))

        // DoorCommandUsability
        sim.unitMenu.hooks.doorTargetCount = { 1 }
        assertEquals(Menu.MENU_NOTSHOWN, menu.doorCommandUsability(0, 16))

        // ChestCommandUsability
        sim.unitMenu.hooks.canUnitUseChestKeyItem = { true }
        assertEquals(Menu.MENU_NOTSHOWN, menu.chestCommandUsability(0, 15))

        // ArmoryCommandUsability
        sim.unitMenu.hooks.getAvailableTileEventCommand = { _, _ ->
            UnitActionMenu.TILE_COMMAND_ARMORY
        }
        assertEquals(Menu.MENU_ENABLED, menu.armoryCommandUsability(0, 17))
        sim.unitMenu.hooks.getAvailableTileEventCommand = { _, _ -> 0 }
        assertEquals(Menu.MENU_NOTSHOWN, menu.armoryCommandUsability(0, 17))

        // ArenaCommandUsability
        sim.unitMenu.hooks.arenaIsUnitAllowed = { true }
        assertEquals(Menu.MENU_NOTSHOWN, menu.arenaCommandUsability(0, 20))

        // AttackBallistaCommandUsability
        sim.unitMenu.hooks.ballistaAttackUsability = { _, _, _ -> Menu.MENU_ENABLED }
        assertEquals(Menu.MENU_NOTSHOWN, menu.attackBallistaCommandUsability(0, 2))
    }

    @Test
    @VerifiesPort(
        "ExitCommandUsability",
        "GiveUsability",
        "ItemSubMenu_IsTradeAvailable",
        "PickCommandUsability",
        "PlayCommandUsability",
        "RescueUsability",
        "RideCommandUsability",
    )
    fun `unit menu build at f4181 evaluates usability B callbacks to NOTSHOWN`() {
        assumeInputs()
        val sim = Sim("ram_trace_prologue_clear", 4181)
        sim.start()
        // The GBA's menu holds only Attack, Item and Wait, so every other callback answered
        // MENU_NOTSHOWN (a menu item is created only for a shown or disabled answer).
        assertEquals(listOf(1 to 1, 25 to 1, 28 to 1), sim.items, "items (number to availability)")
        val menu = sim.unitMenu
        val results = listOf(
            "RideCommandUsability" to menu.rideCommandUsability(itemDef(4), 4),
            "ExitCommandUsability" to menu.exitCommandUsability(itemDef(5), 5),
            "PlayCommandUsability" to menu.playCommandUsability(itemDef(6), 6),
            "PickCommandUsability" to menu.pickCommandUsability(itemDef(11), 11),
            "RescueUsability" to menu.rescueUsability(itemDef(21), 21),
            "GiveUsability" to menu.giveUsability(itemDef(24), 24),
            "ItemSubMenu_IsTradeAvailable" to menu.itemSubMenuIsTradeAvailable(itemDef(26), 26),
        )
        for ((name, result) in results) {
            assertEquals(Menu.MENU_NOTSHOWN, result, name)
        }
        for (number in listOf(4, 5, 6, 11, 21, 24, 26)) {
            assertEquals(null, sim.items.find { it.first == number }, "item $number not built")
        }
    }

    @Test
    fun `usability B callbacks branch coverage`() {
        assumeInputs()
        val sim = Sim("ram_trace_prologue_clear", 4181)
        sim.start()
        val menu = sim.unitMenu
        val units = sim.units
        val unit = sim.active.unit
        val o = units.unitOffset(unit)
        val saved = units.bytes.copyOfRange(o, o + 8)
        val savedState = units.state(unit)
        fun reset() {
            saved.copyInto(units.bytes, o)
            units.setState(unit, savedState)
            sim.bmSt.takenAction = 0
            sim.bmSt.umTmpItem = 0
        }
        fun use(attribute: Int) {
            reset()
            val ptr = ATTRIBUTE_OWNERS.firstNotNullOf { (table, size, count) ->
                (0 until count).map { table + size * it }.firstOrNull {
                    sim.rom.u32(it + OFF_ATTRIBUTES) and attribute != 0
                }?.let { it to table }
            }
            val at = if (ptr.second == CHARACTER_DATA) o else o + 4
            put32(units.bytes, at, ptr.first)
        }
        val hooks = menu.hooks

        // RescueUsability
        hooks.rescueTargetCount = { 1 }
        assertEquals(Menu.MENU_ENABLED, menu.rescueUsability(0, 21))
        units.setState(unit, savedState or US_HAS_MOVED)
        assertEquals(Menu.MENU_NOTSHOWN, menu.rescueUsability(0, 21))
        for (bit in listOf(UnitTable.US_IN_BALLISTA, UnitTable.US_RESCUING)) {
            units.setState(unit, savedState or bit)
            assertEquals(Menu.MENU_NOTSHOWN, menu.rescueUsability(0, 21))
        }
        reset()
        hooks.rescueTargetCount = { 0 }
        assertEquals(Menu.MENU_NOTSHOWN, menu.rescueUsability(0, 21))

        // GiveUsability
        hooks.giveTargetCount = { 1 }
        units.setState(unit, savedState or UnitTable.US_RESCUING)
        assertEquals(Menu.MENU_ENABLED, menu.giveUsability(0, 24))
        sim.bmSt.takenAction = UnitActionMenu.BM_TAKEN_ACTION_TAKE
        assertEquals(Menu.MENU_NOTSHOWN, menu.giveUsability(0, 24))
        sim.bmSt.takenAction = 0
        units.setState(unit, savedState or UnitTable.US_RESCUING or US_HAS_MOVED)
        assertEquals(Menu.MENU_NOTSHOWN, menu.giveUsability(0, 24))
        units.setState(unit, savedState or UnitTable.US_RESCUING)
        hooks.giveTargetCount = { 0 }
        assertEquals(Menu.MENU_NOTSHOWN, menu.giveUsability(0, 24))
        reset()
        hooks.giveTargetCount = { 1 }
        assertEquals(Menu.MENU_NOTSHOWN, menu.giveUsability(0, 24))

        // ItemSubMenu_IsTradeAvailable
        hooks.tradeTargetCount = { 1 }
        assertEquals(Menu.MENU_ENABLED, menu.itemSubMenuIsTradeAvailable(0, 26))
        units.setState(unit, savedState or US_HAS_MOVED)
        assertEquals(Menu.MENU_NOTSHOWN, menu.itemSubMenuIsTradeAvailable(0, 26))
        reset()
        sim.bmSt.takenAction = UnitActionMenu.BM_TAKEN_ACTION_TRADE
        assertEquals(Menu.MENU_NOTSHOWN, menu.itemSubMenuIsTradeAvailable(0, 26))
        use(UnitActionMenu.CA_SUPPLY)
        assertEquals(Menu.MENU_NOTSHOWN, menu.itemSubMenuIsTradeAvailable(0, 26))
        reset()
        hooks.tradeTargetCount = { 0 }
        assertEquals(Menu.MENU_NOTSHOWN, menu.itemSubMenuIsTradeAvailable(0, 26))

        // PickCommandUsability
        hooks.pickTargetCount = { 1 }
        put32(units.bytes, o + 4, CLASS_DATA + CLASS_SIZE * (CLASS_ROGUE - 1))
        assertEquals(Menu.MENU_ENABLED, menu.pickCommandUsability(0, 11))
        hooks.pickTargetCount = { 0 }
        assertEquals(Menu.MENU_NOTSHOWN, menu.pickCommandUsability(0, 11))
        hooks.pickTargetCount = { 1 }
        units.setState(unit, savedState or US_HAS_MOVED)
        assertEquals(Menu.MENU_NOTSHOWN, menu.pickCommandUsability(0, 11))
        reset()
        assertEquals(Menu.MENU_NOTSHOWN, menu.pickCommandUsability(0, 11))

        // PlayCommandUsability (delegates to the Transcribed sub_80230F0)
        use(UnitActionMenu.CA_PLAY)
        hooks.refreshTargetCount = { 1 }
        assertEquals(Menu.MENU_ENABLED, menu.playCommandUsability(0, 6))
        assertEquals(UnitActionMenu.ITEM_UNK_CD, sim.bmSt.umTmpItem)
        units.setState(unit, savedState or US_HAS_MOVED)
        assertEquals(Menu.MENU_NOTSHOWN, menu.playCommandUsability(0, 6))

        // RideCommandUsability
        use(UnitActionMenu.CA_BALLISTAE)
        hooks.trapTypeAt = { _, _ -> MapTraps.TRAP_BALLISTA }
        assertEquals(Menu.MENU_ENABLED, menu.rideCommandUsability(0, 4))
        hooks.trapTypeAt = { _, _ -> MapTraps.TRAP_OBSTACLE }
        assertEquals(Menu.MENU_NOTSHOWN, menu.rideCommandUsability(0, 4))
        hooks.trapTypeAt = { _, _ -> -1 }
        assertEquals(Menu.MENU_NOTSHOWN, menu.rideCommandUsability(0, 4))
        hooks.trapTypeAt = { _, _ -> MapTraps.TRAP_BALLISTA }
        for (bit in listOf(
            UnitTable.US_RESCUING, UnitTable.US_RESCUED, UnitTable.US_IN_BALLISTA,
        )) {
            units.setState(unit, savedState or bit)
            assertEquals(Menu.MENU_NOTSHOWN, menu.rideCommandUsability(0, 4))
        }
        units.setState(unit, savedState)
        sim.bmSt.takenAction = UnitActionMenu.BM_TAKEN_ACTION_BALLISTA
        assertEquals(Menu.MENU_NOTSHOWN, menu.rideCommandUsability(0, 4))
        reset()
        assertEquals(Menu.MENU_NOTSHOWN, menu.rideCommandUsability(0, 4))

        // ExitCommandUsability
        units.setState(unit, savedState or UnitTable.US_IN_BALLISTA)
        assertEquals(Menu.MENU_ENABLED, menu.exitCommandUsability(0, 5))
        sim.bmSt.takenAction = UnitActionMenu.BM_TAKEN_ACTION_BALLISTA
        assertEquals(Menu.MENU_NOTSHOWN, menu.exitCommandUsability(0, 5))
        reset()
        assertEquals(Menu.MENU_NOTSHOWN, menu.exitCommandUsability(0, 5))
    }

    @Test
    @VerifiesPort(
        "SecretShopCommandUsability",
        "StaffCommandUsability",
        "StealCommandUsability",
        "SummonCommandUsability",
        "SupplyUsability",
        "SupportCommandUsability",
        "TakeUsability",
    )
    fun `unit menu build at f4181 evaluates usability C callbacks to NOTSHOWN`() {
        assumeInputs()
        val sim = Sim("ram_trace_prologue_clear", 4181)
        sim.start()
        // The GBA's menu holds only Attack, Item and Wait, so every other callback answered
        // MENU_NOTSHOWN (a menu item is created only for a shown or disabled answer).
        assertEquals(listOf(1 to 1, 25 to 1, 28 to 1), sim.items, "items (number to availability)")
        val menu = sim.unitMenu
        val results = listOf(
            "StaffCommandUsability" to menu.staffCommandUsability(itemDef(3), 3),
            "StealCommandUsability" to menu.stealCommandUsability(itemDef(8), 8),
            "SummonCommandUsability" to menu.summonCommandUsability(itemDef(9), 9),
            "SupportCommandUsability" to menu.supportCommandUsability(itemDef(13), 13),
            "SecretShopCommandUsability" to menu.secretShopCommandUsability(itemDef(19), 19),
            "TakeUsability" to menu.takeUsability(itemDef(23), 23),
            "SupplyUsability" to menu.supplyUsability(itemDef(27), 27),
        )
        for ((name, result) in results) {
            assertEquals(Menu.MENU_NOTSHOWN, result, name)
        }
        for (number in listOf(3, 8, 9, 13, 19, 23, 27)) {
            assertEquals(null, sim.items.find { it.first == number }, "item $number not built")
        }
    }

    @Test
    fun `usability C callbacks branch coverage`() {
        assumeInputs()
        val sim = Sim("ram_trace_prologue_clear", 4181)
        sim.start()
        val menu = sim.unitMenu
        val units = sim.units
        val unit = sim.active.unit
        val o = units.unitOffset(unit)
        val saved = units.bytes.copyOfRange(o, o + 0x40)
        val savedState = units.state(unit)
        val slot = units.unitOffset(0x3E)
        val savedSlot = units.bytes.copyOfRange(slot, slot + 0x40)
        fun reset() {
            saved.copyInto(units.bytes, o)
            savedSlot.copyInto(units.bytes, slot)
            units.setState(unit, savedState)
            sim.bmSt.takenAction = 0
            sim.playSt.chapterModeIndex = 2
            sim.playSt.chapterIndex = 0
        }
        fun charPtr(id: Int) = CHARACTER_DATA + 0x34 * (id - 1)
        fun use(attribute: Int) {
            reset()
            val (ptr, table) = ATTRIBUTE_OWNERS.firstNotNullOf { (table, size, count) ->
                (0 until count).map { table + size * it }.firstOrNull {
                    sim.rom.u32(it + OFF_ATTRIBUTES) and attribute != 0
                }?.let { it to table }
            }
            put32(units.bytes, if (table == CHARACTER_DATA) o else o + 4, ptr)
        }
        val hooks = menu.hooks

        // TakeUsability
        hooks.takeTargetCount = { 1 }
        assertEquals(Menu.MENU_ENABLED, menu.takeUsability(0, 23))
        units.setState(unit, savedState or US_HAS_MOVED)
        assertEquals(Menu.MENU_NOTSHOWN, menu.takeUsability(0, 23))
        units.setState(unit, savedState or UnitTable.US_RESCUING)
        assertEquals(Menu.MENU_NOTSHOWN, menu.takeUsability(0, 23))
        units.setState(unit, savedState)
        sim.bmSt.takenAction = UnitActionMenu.BM_TAKEN_ACTION_TAKE
        assertEquals(Menu.MENU_NOTSHOWN, menu.takeUsability(0, 23))
        reset()
        hooks.takeTargetCount = { 0 }
        assertEquals(Menu.MENU_NOTSHOWN, menu.takeUsability(0, 23))

        // SupportCommandUsability
        hooks.supportTargetCount = { 1 }
        hooks.talkTargetCount = { 0 }
        assertEquals(Menu.MENU_ENABLED, menu.supportCommandUsability(0, 13))
        hooks.talkTargetCount = { 1 }
        assertEquals(Menu.MENU_NOTSHOWN, menu.supportCommandUsability(0, 13))
        hooks.talkTargetCount = { 0 }
        units.setState(unit, savedState or US_HAS_MOVED)
        assertEquals(Menu.MENU_NOTSHOWN, menu.supportCommandUsability(0, 13))
        units.setState(unit, savedState)
        hooks.supportTargetCount = { 0 }
        assertEquals(Menu.MENU_NOTSHOWN, menu.supportCommandUsability(0, 13))
        hooks.supportTargetCount = { 1 }
        units.bytes[o + 0x30] = UNIT_STATUS_SILENCED.toByte()
        assertEquals(Menu.MENU_DISABLED, menu.supportCommandUsability(0, 13))
        reset()

        // SecretShopCommandUsability
        hooks.getAvailableTileEventCommand = { _, _ -> UnitActionMenu.TILE_COMMAND_SECRET }
        assertEquals(Menu.MENU_ENABLED, menu.secretShopCommandUsability(0, 19))
        hooks.getAvailableTileEventCommand = { _, _ -> 0 }
        assertEquals(Menu.MENU_NOTSHOWN, menu.secretShopCommandUsability(0, 19))
        hooks.getAvailableTileEventCommand = { _, _ -> UnitActionMenu.TILE_COMMAND_SECRET }
        units.setState(unit, savedState or US_HAS_MOVED)
        assertEquals(Menu.MENU_NOTSHOWN, menu.secretShopCommandUsability(0, 19))
        reset()
        put32(units.bytes, o + 4, CLASS_DATA + CLASS_SIZE * (CLASS_PHANTOM - 1))
        assertEquals(Menu.MENU_NOTSHOWN, menu.secretShopCommandUsability(0, 19))
        reset()

        // StealCommandUsability
        use(UnitActionMenu.CA_STEAL)
        hooks.stealTargetCount = { 1 }
        units.bytes.fill(0, o + 0x1E, o + 0x28)
        assertEquals(Menu.MENU_ENABLED, menu.stealCommandUsability(0, 8))
        units.bytes[o + 0x1E + 8] = 1
        units.bytes[o + 0x1E + 9] = 1
        assertEquals(Menu.MENU_DISABLED, menu.stealCommandUsability(0, 8))
        hooks.stealTargetCount = { 0 }
        assertEquals(Menu.MENU_NOTSHOWN, menu.stealCommandUsability(0, 8))
        hooks.stealTargetCount = { 1 }
        units.setState(unit, savedState or US_HAS_MOVED)
        assertEquals(Menu.MENU_NOTSHOWN, menu.stealCommandUsability(0, 8))
        reset()
        assertEquals(Menu.MENU_NOTSHOWN, menu.stealCommandUsability(0, 8))

        // StaffCommandUsability
        val staff = (1..0xFF).first { sim.itemInfo.getItemType(it) == ITYPE_STAFF }
        units.bytes.fill(0, o + 0x1E, o + 0x28)
        units.bytes[o + 0x1E] = staff.toByte()
        hooks.canUnitUseItem = { _, _ -> true }
        hooks.isPositionMagicSealed = { _, _ -> false }
        assertEquals(Menu.MENU_ENABLED, menu.staffCommandUsability(0, 3))
        hooks.isPositionMagicSealed = { _, _ -> true }
        assertEquals(Menu.MENU_DISABLED, menu.staffCommandUsability(0, 3))
        hooks.canUnitUseItem = { _, _ -> false }
        assertEquals(Menu.MENU_NOTSHOWN, menu.staffCommandUsability(0, 3))
        hooks.canUnitUseItem = { _, _ -> true }
        units.setState(unit, savedState or US_HAS_MOVED)
        assertEquals(Menu.MENU_NOTSHOWN, menu.staffCommandUsability(0, 3))
        units.setState(unit, savedState)
        units.bytes.fill(0, o + 0x1E, o + 0x28)
        assertEquals(Menu.MENU_NOTSHOWN, menu.staffCommandUsability(0, 3))
        reset()

        // SummonCommandUsability
        val summoner = sim.rom.u8(SUMMON_CONFIG)
        val summoned = sim.rom.u8(SUMMON_CONFIG + 1)
        use(UnitActionMenu.CA_SUMMON)
        hooks.summonTargetCount = { 1 }
        assertEquals(Menu.MENU_NOTSHOWN, menu.summonCommandUsability(0, 9))
        put32(units.bytes, o, charPtr(summoner))
        if (units.cAttributes(unit) and UnitActionMenu.CA_SUMMON == 0) {
            val summonClass = (0 until 0x7F).map { CLASS_DATA + CLASS_SIZE * it }.first {
                sim.rom.u32(it + OFF_ATTRIBUTES) and UnitActionMenu.CA_SUMMON != 0
            }
            put32(units.bytes, o + 4, summonClass)
        }
        assertEquals(Menu.MENU_ENABLED, menu.summonCommandUsability(0, 9))
        hooks.summonTargetCount = { 0 }
        assertEquals(Menu.MENU_NOTSHOWN, menu.summonCommandUsability(0, 9))
        hooks.summonTargetCount = { 1 }
        units.setState(unit, units.state(unit) or US_HAS_MOVED)
        assertEquals(Menu.MENU_NOTSHOWN, menu.summonCommandUsability(0, 9))
        units.setState(unit, savedState)
        put32(units.bytes, slot, charPtr(summoned))
        units.setState(0x3E, 0)
        assertEquals(Menu.MENU_NOTSHOWN, menu.summonCommandUsability(0, 9))
        units.setState(0x3E, UnitTable.US_UNAVAILABLE)
        assertEquals(Menu.MENU_ENABLED, menu.summonCommandUsability(0, 9))
        assertEquals(0, units.state(0x3E) and UnitTable.US_UNAVAILABLE, "state cleared")
        reset()

        // SupplyUsability
        sim.playSt.chapterIndex = CHAPTER_L_2
        hooks.isAdjacentForSupply = { false }
        assertEquals(Menu.MENU_NOTSHOWN, menu.supplyUsability(0, 27))
        put32(units.bytes, o, charPtr(CHARACTER_EIRIKA))
        assertEquals(Menu.MENU_ENABLED, menu.supplyUsability(0, 27))
        sim.playSt.chapterModeIndex = CHAPTER_MODE_EPHRAIM
        assertEquals(Menu.MENU_NOTSHOWN, menu.supplyUsability(0, 27))
        hooks.isAdjacentForSupply = { pid -> pid == CHARACTER_EPHRAIM }
        assertEquals(Menu.MENU_ENABLED, menu.supplyUsability(0, 27))
        put32(units.bytes, o, charPtr(CHARACTER_EPHRAIM))
        hooks.isAdjacentForSupply = { false }
        assertEquals(Menu.MENU_ENABLED, menu.supplyUsability(0, 27))
        put32(units.bytes, o + 4, CLASS_DATA + CLASS_SIZE * (CLASS_PHANTOM - 1))
        assertEquals(Menu.MENU_NOTSHOWN, menu.supplyUsability(0, 27))
        reset()
        sim.playSt.chapterIndex = 0
        put32(units.bytes, o, charPtr(CHARACTER_EIRIKA))
        assertEquals(Menu.MENU_NOTSHOWN, menu.supplyUsability(0, 27))
        reset()
    }

    @Test
    @VerifiesPort(
        "VendorCommandUsability",
        "TalkCommandUsability",
        "UnitActionMenu_CanSeize",
        "VisitCommandUsability",
        "YobimaCommandUsability",
    )
    fun `unit menu build at f4181 evaluates usability D callbacks to NOTSHOWN`() {
        assumeInputs()
        val sim = Sim("ram_trace_prologue_clear", 4181)
        sim.start()
        // The GBA's menu holds only Attack, Item and Wait, so every other callback answered
        // MENU_NOTSHOWN (a menu item is created only for a shown or disabled answer).
        assertEquals(listOf(1 to 1, 25 to 1, 28 to 1), sim.items, "items (number to availability)")
        val menu = sim.unitMenu
        val results = listOf(
            "UnitActionMenu_CanSeize" to menu.unitActionMenuCanSeize(itemDef(0), 0),
            "YobimaCommandUsability" to menu.yobimaCommandUsability(itemDef(10), 10),
            "TalkCommandUsability" to menu.talkCommandUsability(itemDef(12), 12),
            "VisitCommandUsability" to menu.visitCommandUsability(itemDef(14), 14),
            "VendorCommandUsability" to menu.vendorCommandUsability(itemDef(18), 18),
        )
        for ((name, result) in results) {
            assertEquals(Menu.MENU_NOTSHOWN, result, name)
        }
        for (number in listOf(0, 10, 12, 14, 18)) {
            assertEquals(null, sim.items.find { it.first == number }, "item $number not built")
        }

        // HasConvoyAccess: SupplyUsability asks it first and the GBA built no Supply item. In
        // the capture GM_STATE_0 is clear and the chapter is the prologue, so the decomp's
        // answer is false.
        assertEquals(0, sim.iwram(4181)[GM_DATA - IWRAM].toInt() and 1, "GM_STATE_0")
        assertEquals(0, sim.playSt.chapterIndex, "chapterIndex")
        assertEquals(false, menu.convoy.hasConvoyAccess(), "HasConvoyAccess")
        assertEquals(Menu.MENU_NOTSHOWN, menu.supplyUsability(itemDef(27), 27))

        // CanUnitSeize: mode 1 and chapter 0 make the lord of the route the seizer; the unit
        // is only asked for it once it has not moved, and no Seize item was built.
        assertEquals(1, sim.playSt.chapterModeIndex, "chapterModeIndex")
        val character = sim.units.charId(sim.active.unit)
        assertEquals(character == CHARACTER_EIRIKA, sim.playSt.canUnitSeize(character))
        assertEquals(true, sim.playSt.canUnitSeize(CHARACTER_EIRIKA))
        assertEquals(false, sim.playSt.canUnitSeize(CHARACTER_EPHRAIM))
    }

    @Test
    fun `usability D callbacks branch coverage`() {
        assumeInputs()
        val sim = Sim("ram_trace_prologue_clear", 4181)
        sim.start()
        val menu = sim.unitMenu
        val units = sim.units
        val unit = sim.active.unit
        val o = units.unitOffset(unit)
        val x = units.xPos(unit)
        val y = units.yPos(unit)
        val saved = units.bytes.copyOfRange(o, o + 0x40)
        val savedState = units.state(unit)
        val savedTerrain = sim.map.terrain[y, x]
        val hooks = menu.hooks
        fun reset() {
            saved.copyInto(units.bytes, o)
            units.setState(unit, savedState)
            sim.map.terrain[y, x] = savedTerrain
            sim.playSt.chapterModeIndex = 1
            sim.playSt.chapterIndex = 0
        }
        fun charPtr(id: Int) = CHARACTER_DATA + 0x34 * (id - 1)
        fun classPtr(id: Int) = CLASS_DATA + CLASS_SIZE * (id - 1)
        fun tileCommand(command: Int) {
            hooks.getAvailableTileEventCommand = { _, _ -> command }
        }

        // VendorCommandUsability
        tileCommand(0x17)
        assertEquals(Menu.MENU_ENABLED, menu.vendorCommandUsability(0, 18))
        tileCommand(0)
        assertEquals(Menu.MENU_NOTSHOWN, menu.vendorCommandUsability(0, 18))
        tileCommand(0x17)
        units.setState(unit, savedState or US_HAS_MOVED)
        assertEquals(Menu.MENU_NOTSHOWN, menu.vendorCommandUsability(0, 18))
        reset()
        put32(units.bytes, o + 4, classPtr(CLASS_PHANTOM))
        assertEquals(Menu.MENU_NOTSHOWN, menu.vendorCommandUsability(0, 18))
        reset()

        // TalkCommandUsability
        hooks.talkTargetCount = { 1 }
        assertEquals(Menu.MENU_ENABLED, menu.talkCommandUsability(0, 12))
        units.bytes[o + 0x30] = UNIT_STATUS_SILENCED.toByte()
        assertEquals(Menu.MENU_DISABLED, menu.talkCommandUsability(0, 12))
        reset()
        units.setState(unit, savedState or US_HAS_MOVED)
        assertEquals(Menu.MENU_NOTSHOWN, menu.talkCommandUsability(0, 12))
        reset()
        hooks.talkTargetCount = { 0 }
        assertEquals(Menu.MENU_NOTSHOWN, menu.talkCommandUsability(0, 12))

        // UnitActionMenu_CanSeize and CanUnitSeize
        put32(units.bytes, o, charPtr(CHARACTER_EIRIKA))
        tileCommand(0x11)
        assertEquals(Menu.MENU_ENABLED, menu.unitActionMenuCanSeize(0, 0))
        tileCommand(0)
        assertEquals(Menu.MENU_NOTSHOWN, menu.unitActionMenuCanSeize(0, 0))
        tileCommand(0x11)
        units.setState(unit, savedState or US_HAS_MOVED)
        assertEquals(Menu.MENU_NOTSHOWN, menu.unitActionMenuCanSeize(0, 0))
        units.setState(unit, savedState)
        put32(units.bytes, o, charPtr(CHARACTER_EPHRAIM))
        assertEquals(Menu.MENU_NOTSHOWN, menu.unitActionMenuCanSeize(0, 0))
        sim.playSt.chapterModeIndex = 3
        assertEquals(Menu.MENU_ENABLED, menu.unitActionMenuCanSeize(0, 0))
        put32(units.bytes, o, charPtr(CHARACTER_EIRIKA))
        assertEquals(Menu.MENU_NOTSHOWN, menu.unitActionMenuCanSeize(0, 0))
        sim.playSt.chapterModeIndex = 2
        assertEquals(Menu.MENU_ENABLED, menu.unitActionMenuCanSeize(0, 0))
        sim.playSt.chapterIndex = 5
        assertEquals(Menu.MENU_NOTSHOWN, menu.unitActionMenuCanSeize(0, 0))
        put32(units.bytes, o, charPtr(CHARACTER_EPHRAIM))
        assertEquals(Menu.MENU_ENABLED, menu.unitActionMenuCanSeize(0, 0))
        reset()

        // VisitCommandUsability
        hooks.isPositionMagicSealed = { _, _ -> false }
        tileCommand(0x10)
        for (terrain in listOf(0x03, 0x05, 0x37, 0x38)) {
            sim.map.terrain[y, x] = terrain
            assertEquals(Menu.MENU_ENABLED, menu.visitCommandUsability(0, 14), "terrain $terrain")
        }
        sim.map.terrain[y, x] = 0x03
        hooks.isPositionMagicSealed = { _, _ -> true }
        assertEquals(Menu.MENU_DISABLED, menu.visitCommandUsability(0, 14))
        hooks.isPositionMagicSealed = { _, _ -> false }
        tileCommand(0)
        assertEquals(Menu.MENU_NOTSHOWN, menu.visitCommandUsability(0, 14))
        tileCommand(0x10)
        sim.map.terrain[y, x] = 0x01
        assertEquals(Menu.MENU_NOTSHOWN, menu.visitCommandUsability(0, 14))
        sim.map.terrain[y, x] = 0x03
        units.setState(unit, savedState or US_HAS_MOVED)
        assertEquals(Menu.MENU_NOTSHOWN, menu.visitCommandUsability(0, 14))
        reset()
        sim.map.terrain[y, x] = 0x03
        put32(units.bytes, o + 4, classPtr(CLASS_PHANTOM))
        assertEquals(Menu.MENU_NOTSHOWN, menu.visitCommandUsability(0, 14))
        reset()

        // YobimaCommandUsability
        put32(units.bytes, o + 4, classPtr(CLASS_DEMON_KING))
        assertEquals(Menu.MENU_ENABLED, menu.yobimaCommandUsability(0, 10))
        units.setState(unit, savedState or US_HAS_MOVED)
        assertEquals(Menu.MENU_NOTSHOWN, menu.yobimaCommandUsability(0, 10))
        units.setState(unit, savedState)
        // 0x28 valid units among ids 0x81..0xBF still pass, the 0x29th fails.
        val slots = (0x81..0xB2).map { units.unitOffset(it) }
        val savedSlots = slots.map { units.bytes.copyOfRange(it, it + 0x40) }
        for (n in 1..0x29) {
            for ((k, slot) in slots.withIndex()) {
                put32(units.bytes, slot, if (k < n) charPtr(CHARACTER_EIRIKA) else 0)
            }
            val expected = if (n <= 0x28) Menu.MENU_ENABLED else Menu.MENU_NOTSHOWN
            assertEquals(expected, menu.yobimaCommandUsability(0, 10), "$n red units")
        }
        slots.forEachIndexed { k, slot -> savedSlots[k].copyInto(units.bytes, slot) }
        reset()
        assertEquals(Menu.MENU_NOTSHOWN, menu.yobimaCommandUsability(0, 10))

        // HasConvoyAccess
        val hooksConvoy = menu.convoy.hooks
        val convoy = menu.convoy
        for ((chapter, expected) in listOf(0 to false, 1 to false, 2 to true, 5 to false,
            0x38 to false, 0x39 to true)) {
            sim.playSt.chapterIndex = chapter
            assertEquals(expected, convoy.hasConvoyAccess(), "chapterIndex $chapter")
        }
        hooksConvoy.isGmState0 = { true }
        sim.playSt.chapterIndex = 2
        for ((chapter, expected) in listOf(0 to false, 1 to false, 0x38 to false, 2 to true,
            5 to true, -1 to true)) {
            hooksConvoy.getNextUnclearedChapter = { chapter }
            assertEquals(expected, convoy.hasConvoyAccess(), "next chapter $chapter")
        }
        reset()
    }

    /** The `MenuItemDef` address of `gUnitActionMenuItems[index]`. */
    private fun itemDef(index: Int) = UNIT_ACTION_MENU_ITEMS + ITEM_DEF_SIZE * index

    private fun assumeInputs() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
    }

    private inner class Sim(val runName: String, val first: Int) {
        val data = ref.run(runName)
        val names = data.dumps.entries.associate { it.value to it.key }
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val engine = ProcEngine()
        val hw = BgHardware()
        val host = Vram()
        val text = TextSystem(romBytes, host, Fe8Text.CONFIG)
        val keys = KeyStatus()
        val sprites = SpriteLayers()
        var clock = 0
        val hand = UiHand(rom, sprites)
        val greenText = GreenText(engine, rom, hw) { clock }
        val menu: Menu
        val units = UnitTable(rom)
        val active = ActiveUnit()
        val itemInfo = ItemInfo(rom)
        val unitMenu: UnitActionMenu
        val bmSt = BmSt()
        val map: BmMap
        val playSt = PlaySt()
        val targetCountCalls = ArrayList<Pair<Int, Int>>()
        val selectCallbacks = ArrayList<Int>()
        var selectResult = -1
        var actionTypeBefore = -1
        val shown = HashMap<Int, Int>()
        val items = ArrayList<Pair<Int, Int>>()
        lateinit var menuProc: Menu.MenuProc

        init {
            val root = u32(romBytes, 0x0815D488 - ROM_BASE) - ROM_BASE
            val messages = MsgStrings(
                romBytes, MsgHuffman(romBytes, 0x0815A72C - ROM_BASE, root), 0x0815D48C - ROM_BASE,
            )
            menu = Menu(
                engine, rom, hw, text, messages, keys, hand, greenText,
                CamMove(engine, BmSt()).script,
            )
            map = BmMap(rom, ChapterData(rom), BmSt())
            val loader = UnitLoader(units, rom, { 0 }, RngService(), { false })
            unitMenu = UnitActionMenu(
                units, active, itemInfo, loader, map,
                AttackRange(map, units, itemInfo, EntityBmMaps(map, units, PlaySt()), loader),
                hw, bmSt, playSt,
            )
        }

        fun ewram(frame: Int): ByteArray = data.region(names.getValue(frame), "ewram")

        fun iwram(frame: Int): ByteArray = data.region(names.getValue(frame), "iwram")

        fun start() {
            // The unit has reached its tile when the menu starts: the first menu frame's RAM
            // holds the unit and the action the caller (PP-11) leaves.
            val e = ewram(first)
            units.loadFrom(e)
            bmSt.decode(e, BM_ST - EWRAM)
            playSt.decode(e, PLAY_ST - EWRAM)
            readAction(e, iwram(first))
            keys.readFrom(e, KEY_STATUS - EWRAM)
            text.initTextFont(null, 0x06001000, 0x80, 0)

            // Stand-ins: the other items' availability from the capture's item procs, and the
            // target count of MakeTargetListForWeapon.
            val original = ProcArrayDump.ofEwram(ewram(first))
            val originalMenu = original.find(MENU_SCRIPT)!!
            for (i in 0 until originalMenu.u8(0x60)) {
                val item = original.Proc(procSlot(originalMenu.u32(0x34 + 4 * i)))
                shown[item.u8(0x3C)] = item.u8(0x3D)
            }
            unitMenu.hooks.unportedUsability = { _, number ->
                shown[number] ?: Menu.MENU_NOTSHOWN
            }
            unitMenu.hooks.weaponTargetCount = { unit, item ->
                targetCountCalls.add(unit to item)
                enemiesInRange(unit, item)
            }
            unitMenu.install(menu, rom)
            // Record the select callbacks that run.
            val wait = menu.callbacks.getValue(UnitActionMenu.EFFECT_WAIT)
            menu.callbacks[UnitActionMenu.EFFECT_WAIT] = Menu.MenuFunc { m, i, d, n ->
                selectCallbacks.add(UnitActionMenu.EFFECT_WAIT)
                actionTypeBefore = active.action.unitActionType
                wait.invoke(m, i, d, n).also { selectResult = it }
            }

            val bm = BmSt().also { it.decode(e, BM_ST - EWRAM) }
            keys.update(data.inputs[first - 1])
            clock = u32(iwram(first), CLOCK - IWRAM)
            val proc = menu.startSemiCenteredOrphanMenu(
                Menu.UNIT_ACTION_MENU_DEF, bm.cursorTargetX - bm.cameraX, 1, 22,
            )
            menuProc = proc.state as Menu.MenuProc
            for (i in 0 until menuProc.itemCount) {
                val item = menuProc.menuItems[i]!!
                items.add(item.itemNumber to item.availability)
            }
        }

        fun readAction(e: ByteArray, iw: ByteArray) {
            val pointer = u32(iw, GACTIVEUNIT - IWRAM)
            active.unit = if (pointer == 0) 0 else {
                units.bytes[pointer - UnitTable.BLUE_ADDRESS + 0x0B].toInt() and 0xFF
            }
            val a = ACTION_DATA - EWRAM
            active.action.subjectIndex = e[a + 0x0C].toInt() and 0xFF
            active.action.targetIndex = e[a + 0x0D].toInt() and 0xFF
            active.action.xMove = e[a + 0x0E].toInt() and 0xFF
            active.action.yMove = e[a + 0x0F].toInt() and 0xFF
            active.action.moveCount = e[a + 0x10].toInt() and 0xFF
            active.action.unitActionType = e[a + 0x11].toInt() and 0xFF
        }

        /** Stand-in for the target list: living enemy-faction units within the weapon's reach. */
        fun enemiesInRange(unit: Int, item: Int): Int {
            val x = units.xPos(unit)
            val y = units.yPos(unit)
            var count = 0
            for (id in 0x81..0xB2) {
                if (!units.isValid(id)) continue
                if (units.state(id) and (UnitTable.US_UNAVAILABLE) != 0) continue
                val distance = Math.abs(units.xPos(id) - x) + Math.abs(units.yPos(id) - y)
                if (distance in itemInfo.getItemMinRange(item)..itemInfo.getItemMaxRange(item)) {
                    count++
                }
            }
            return count
        }

        /** The item procs of the first menu frame: number, availability and def. */
        fun compareItems() {
            val dump = ProcArrayDump.ofEwram(ewram(first))
            val original = dump.find(MENU_SCRIPT)!!
            assertEquals(original.u8(0x60), menuProc.itemCount, "itemCount")
            assertEquals(original.u32(0x30), menuProc.def, "def")
            for (i in 0 until menuProc.itemCount) {
                val o = dump.Proc(procSlot(original.u32(0x34 + 4 * i)))
                val m = menuProc.menuItems[i]!!
                assertEquals(o.u8(0x3C), m.itemNumber, "item $i number")
                assertEquals(o.u8(0x3D), m.availability, "item $i availability")
                assertEquals(o.u32(0x30), m.def, "item $i def")
            }
        }

        /** Runs the menu frame by frame up to and including [last]. */
        fun run(last: Int) {
            for (f in first..last) {
                if (f > first) keys.update(data.inputs[f - 1])
                clock = u32(iwram(f), CLOCK - IWRAM)
                sprites.clear()
                for (tree in 0 until 8) engine.run(engine.root(tree))
            }
        }

        fun compareAction(frame: Int) {
            val e = ewram(frame)
            val a = ACTION_DATA - EWRAM
            val at = "$runName f$frame gActionData"
            assertEquals(e[a + 0x0C].toInt() and 0xFF, active.action.subjectIndex, "$at subject")
            assertEquals(e[a + 0x0D].toInt() and 0xFF, active.action.targetIndex, "$at target")
            assertEquals(e[a + 0x0E].toInt() and 0xFF, active.action.xMove, "$at xMove")
            assertEquals(e[a + 0x0F].toInt() and 0xFF, active.action.yMove, "$at yMove")
            assertEquals(e[a + 0x10].toInt() and 0xFF, active.action.moveCount, "$at moveCount")
            assertEquals(
                e[a + 0x11].toInt() and 0xFF, active.action.unitActionType, "$at unitActionType",
            )
            // The menu ended (A on Wait ends it).
            assertEquals(null, ProcArrayDump.ofEwram(e).find(MENU_SCRIPT), "$at menu proc ended")
            assertEquals(null, engine.find(menu.menuScript), "mine menu proc ended")
        }

        fun procSlot(address: Int): Int =
            (address - ProcArrayDump.ARRAY_ADDRESS) / ProcArrayDump.PROC_SIZE
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val ROM_BASE = 0x08000000
        const val KEY_STATUS = 0x02024CC0
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val CLOCK = 0x03000014
        const val ACTION_DATA = 0x0203A958
        const val GACTIVEUNIT = 0x03004E50
        const val MENU_SCRIPT = 0x085B64D0
        const val UNIT_ACTION_MENU_ITEMS = 0x0859CA34
        const val ITEM_DEF_SIZE = 0x24
        const val US_HAS_MOVED = 1 shl 6
        const val CLASS_ROGUE = 0x33
        const val CLASS_PHANTOM = 0x51
        const val UNIT_STATUS_SILENCED = 3
        const val CHARACTER_EIRIKA = 0x01
        const val CHAPTER_L_2 = 2
        const val CLASS_DEMON_KING = 0x66
        const val GM_DATA = 0x03005280
        const val CHARACTER_EPHRAIM = 0x0F
        const val CHAPTER_MODE_EPHRAIM = 3
        const val ITYPE_STAFF = 4
        const val SUMMON_CONFIG = 0x0895F5A4
        const val CHARACTER_DATA = 0x08803D64
        const val CLASS_DATA = 0x08807164
        const val CLASS_SIZE = 0x54
        const val OFF_ATTRIBUTES = 0x28

        /** The (table, entry size, entry count) of `gCharacterData` and `gClassData`. */
        val ATTRIBUTE_OWNERS = listOf(
            Triple(CHARACTER_DATA, 0x34, 0x100),
            Triple(CLASS_DATA, CLASS_SIZE, 0x7F),
        )

        fun put32(b: ByteArray, at: Int, v: Int) {
            for (k in 0..3) b[at + k] = (v ushr (8 * k)).toByte()
        }

        fun u16(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun u32(b: ByteArray, at: Int) = u16(b, at) or (u16(b, at + 2) shl 16)
    }
}

private class Vram : TextHost {
    val vram = ByteArray(0x18000)

    override fun language() = 1

    override fun applyTextPalette(talk: Boolean, palid: Int) = Unit

    override fun readVram32(addr: Int): Int {
        var v = 0
        for (k in 0..3) v = v or ((vram[addr - 0x06000000 + k].toInt() and 0xFF) shl (8 * k))
        return v
    }

    override fun writeVram32(addr: Int, value: Int) {
        for (k in 0..3) vram[addr - 0x06000000 + k] = (value shr (8 * k)).toByte()
    }

    override fun clearVram(dest: Int, byteCount: Int) {
        java.util.Arrays.fill(vram, dest - 0x06000000, dest - 0x06000000 + byteCount, 0)
    }
}
