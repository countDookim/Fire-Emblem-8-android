package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ActiveUnit
import fe8.game.bm.ItemInfo
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
            val loader = UnitLoader(units, rom, { 0 }, RngService(), { false })
            unitMenu = UnitActionMenu(units, active, itemInfo, loader)
        }

        fun ewram(frame: Int): ByteArray = data.region(names.getValue(frame), "ewram")

        fun iwram(frame: Int): ByteArray = data.region(names.getValue(frame), "iwram")

        fun start() {
            // The unit has reached its tile when the menu starts: the first menu frame's RAM
            // holds the unit and the action the caller (PP-11) leaves.
            val e = ewram(first)
            units.loadFrom(e)
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
        const val CLOCK = 0x03000014
        const val ACTION_DATA = 0x0203A958
        const val GACTIVEUNIT = 0x03004E50
        const val MENU_SCRIPT = 0x085B64D0

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
