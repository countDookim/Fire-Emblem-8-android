package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ActiveUnit
import fe8.game.bm.AttackRange
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.ItemInfo
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitActionMenu
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventHandler
import fe8.game.event.EventMenuOverride
import fe8.game.event.EventState
import fe8.game.event.ScriptMemory
import fe8.game.text.Fe8Text
import fe8.game.ui.GreenText
import fe8.game.ui.Menu
import fe8.game.ui.MenuOverrides
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
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * PC-10c: the Wait-only unit menu override of `ram_trace_newgame_easy`.
 * (a) The `Event3D_MenuOverride` command (`EvtOverrideUnitMenu(~EVENT_MENUOVERRIDE_WAIT)`, and
 * `~EVENT_MENUOVERRIDE_ATTACK` at f22441) of
 * `EventScr_Prologue_Tutorial1`, which runs at f18988 (not in f19068..19080: no capture executes
 * it there; the table is unchanged in that window), run from the `sMenuOverrides` table of
 * f17010 leaves the table of f18988. (b) The unit menu started at f19068 with that table lists
 * Wait only, as the capture's `MenuProc`/`MenuItemProc`s, and A at f19080 selects Wait.
 */
@Timeout(300)
class EasyTutorialMenuOverrideDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun assumeInputs() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        assumeTrue(ref.run(RUN).dumps.containsKey("r19068"), "no r19068 dump")
    }

    @Test
    @VerifiesPort("Event3D_MenuOverride", "ResetMenuOverrides", "AddMenuOverride")
    fun `Event3D_MenuOverride rebuilds sMenuOverrides to the Wait-only table`() {
        // `EventScr_Prologue_Tutorial1` runs the override in f18988 (the engine snapshot after
        // it is past the command); the table before it is the older one of f17010 (2 entries).
        assertOverrideCommand(
            seedTag = "r17010", seedEntries = 2, step = 18987, mask = WAIT_ONLY_MASK,
            wantEntries = 14, wantMissing = 0x6B,
        )
    }

    @Test
    @VerifiesPort("Event3D_MenuOverride", "ResetMenuOverrides", "AddMenuOverride")
    fun `Event3D_MenuOverride rebuilds sMenuOverrides to the Attack-only table`() {
        // The override runs in f22441 (the table changes there); the table before it is the
        // Wait-only one (14 entries), so slots are overwritten, not appended.
        assertOverrideCommand(
            seedTag = "r22440", seedEntries = 14, step = 22440, mask = ATTACK_ONLY_MASK,
            wantEntries = 14, wantMissing = 0x4F,
        )
    }

    private fun assertOverrideCommand(
        seedTag: String,
        seedEntries: Int,
        step: Int,
        mask: Int,
        wantEntries: Int,
        wantMissing: Int,
    ) {
        assumeInputs()
        val run = ref.run(RUN)
        assumeTrue(run.dumps.containsKey(seedTag), "no $seedTag dump")
        val romBytes = romFile.readBytes()
        val trace = EventTrace.prologue(ref, RUN)
        assumeTrue(trace != null, "no per-step trace")
        val mem = ScriptMemory { a ->
            val o = a - ROM_BASE
            (romBytes[o].toInt() and 0xFF) or ((romBytes[o + 1].toInt() and 0xFF) shl 8)
        }
        val engineState = checkNotNull(trace!!.at(step + 1).engine) { "no engine at f${step + 1}" }
        var cmd = engineState.pEventStart
        while (cmd < engineState.pEventStart + 0x100 && mem.u16(cmd) != EVT_MENUOVERRIDE_WORD) {
            cmd += 2
        }
        assertEquals(EVT_MENUOVERRIDE_WORD, mem.u16(cmd), "override command in the script")
        assertEquals(mask, mem.u16(cmd + 2), "command argument")

        val overrides = loadTable(run.region(seedTag, "iwram"))
        assertEquals(seedEntries, countUsed(overrides), "seed table entries")
        val procs = ProcEngine()
        val state = EventState()
        val events = EventEngine(procs, state, mem, object : EventEngineHooks {})
        EventMenuOverride(mem) { overrides }.registerOn(events)
        val real = events.lo[0x3D]
        var runs = 0
        events.register(0x3D, EventHandler { p ->
            runs++
            real.run(p)
        })
        // The next command after the override yields, so only the override runs.
        for (code in 0 until 0x100) {
            if (code != 0x3D) events.register(code, EventHandler { EVC_ADVANCE_YIELD })
        }
        val proc = events.create(engineState.pEventStart, engineState.execType)
        proc.pEventCurrent = cmd
        events.onUpdate(proc)
        assertEquals(1, runs, "Event3D_MenuOverride runs")

        val want = loadTable(run.region(dumpTag(run, step + 1), "iwram"))
        assertTables(want, overrides, "after f$step")
        assertEquals(wantEntries, countUsed(overrides), "entries")
        for (i in 0 until wantEntries) {
            assertEquals(MenuOverrides.MENU_ALWAYS_NOT_SHOWN, overrides.func(i), "func $i")
            assertEquals(MenuOverrides.MENU_OVERRIDE_ISAVAILABLE, overrides.kind(i), "kind $i")
            assertTrue(overrides.cmdId(i) != wantMissing, "slot $i hides the kept command")
        }
    }

    @Test
    @VerifiesPort(
        "OverriddenMenuAvailability", "MenuAlwaysNotShown", "AttackCommandUsability",
    )
    fun `Attack-only unit menu lists Attack only at f22448 and stays open until A`() {
        assumeInputs()
        val sim = Sim(22448)
        sim.start()
        assertEquals(1, sim.items.size, "item count")
        assertEquals(Menu.MENU_ENABLED, sim.items[0].second, "Attack availability")
        assertEquals(emptyList<Int>(), sim.standInCalls, "stand-in usability calls")
        sim.compareItems()
        // The capture's A presses at f22469, f22499 and f22529 go to tutorial boxes (not
        // ported here), and A at f22530 selects Attack (needs `MakeTargetListForWeapon`): the
        // menu is run only up to f22468.
        sim.run(22468)
        assertEquals(emptyList<Int>(), sim.selectCallbacks, "select callbacks before A")
        assertTrue(sim.engine.find(sim.menu.menuScript) != null, "menu still open at f22468")
    }

    @Test
    @VerifiesPort(
        "OverriddenMenuAvailability", "MenuAlwaysNotShown", "MenuAlwaysEnabled", "EffectWait",
    )
    fun `Wait-only unit menu lists Wait only at f19068 and A at f19080 selects it`() {
        assumeInputs()
        val sim = Sim(19068)
        sim.start()
        assertEquals(listOf(WAIT_ITEM_NUMBER to Menu.MENU_ENABLED), sim.items, "items")
        assertEquals(emptyList<Int>(), sim.standInCalls, "stand-in usability calls")
        sim.compareItems()
        sim.run(19080)
        assertEquals(listOf(UnitActionMenu.EFFECT_WAIT), sim.selectCallbacks, "select callbacks")
        sim.compareAction(19081)
        assertEquals(1, sim.active.action.unitActionType, "unitActionType after A")
    }

    private fun dumpTag(run: ReferenceData.Run, step: Int): String {
        val full = "f%05d".format(step)
        return if (run.dumps.containsKey(full)) full else "r%05d".format(step)
    }

    private fun countUsed(t: MenuOverrides): Int =
        (0 until MenuOverrides.MENU_OVERRIDE_MAX).count { t.kind(it) != 0 }

    /** `sMenuOverrides` (16 entries of cmdid u16, kind u16, func u32) from an IWRAM dump. */
    private fun loadTable(iwram: ByteArray): MenuOverrides {
        val t = MenuOverrides()
        for (i in 0 until MenuOverrides.MENU_OVERRIDE_MAX) {
            val b = SMENUOVERRIDES - IWRAM + i * 8
            t.setOverride(i, u16(iwram, b), u16(iwram, b + 2), u32(iwram, b + 4))
        }
        return t
    }

    private fun assertTables(want: MenuOverrides, got: MenuOverrides, at: String) {
        for (i in 0 until MenuOverrides.MENU_OVERRIDE_MAX) {
            assertEquals(want.kind(i), got.kind(i), "$at slot $i kind")
            assertEquals(want.cmdId(i), got.cmdId(i), "$at slot $i cmdid")
            assertEquals(want.func(i), got.func(i), "$at slot $i func")
        }
    }

    private inner class Sim(val first: Int) {
        val data = ref.run(RUN)
        val names = data.dumps.entries.associate { it.value to it.key }
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val engine = ProcEngine()
        val hw = BgHardware()
        val host = MenuVram()
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
        val playSt = PlaySt()
        val selectCallbacks = ArrayList<Int>()
        val standInCalls = ArrayList<Int>()
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
            val map = BmMap(rom, ChapterData(rom), BmSt())
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
            val e = ewram(first)
            units.loadFrom(e)
            bmSt.decode(e, BM_ST - EWRAM)
            playSt.decode(e, PLAY_ST - EWRAM)
            readAction(e, iwram(first))
            keys.readFrom(e, KEY_STATUS - EWRAM)
            text.initTextFont(null, 0x06001000, 0x80, 0)
            // The override table the event command left, read from the dump of the menu's frame.
            val table = loadTable(iwram(first))
            for (i in 0 until MenuOverrides.MENU_OVERRIDE_MAX) {
                menu.overrides.setOverride(i, table.cmdId(i), table.kind(i), table.func(i))
            }
            // Stand-in for the items whose usability is not ported: any call is recorded and
            // answers NOTSHOWN; with the override none of them may be asked.
            unitMenu.hooks.unportedUsability = { _, number ->
                standInCalls.add(number)
                Menu.MENU_NOTSHOWN
            }
            unitMenu.install(menu, rom)
            for (callback in listOf(
                UnitActionMenu.EFFECT_WAIT, UnitActionMenu.UNIT_ACTION_MENU_ATTACK,
            )) {
                val real = menu.callbacks.getValue(callback)
                menu.callbacks[callback] = Menu.MenuFunc { m, i, d, n ->
                    selectCallbacks.add(callback)
                    real.invoke(m, i, d, n)
                }
            }
            unitMenu.hooks.weaponTargetCount = { unit, item -> enemiesInRange(unit, item) }
            keys.update(data.inputs[first - 1])
            clock = u32(iwram(first), CLOCK - IWRAM)
            val proc = menu.startSemiCenteredOrphanMenu(
                Menu.UNIT_ACTION_MENU_DEF, bmSt.cursorTargetX - bmSt.cameraX, 1, 22,
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
                if (units.state(id) and UnitTable.US_UNAVAILABLE != 0) continue
                val distance = Math.abs(units.xPos(id) - x) + Math.abs(units.yPos(id) - y)
                if (distance in itemInfo.getItemMinRange(item)..itemInfo.getItemMaxRange(item)) {
                    count++
                }
            }
            return count
        }

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
            val at = "f$frame gActionData"
            assertEquals(e[a + 0x0C].toInt() and 0xFF, active.action.subjectIndex, "$at subject")
            assertEquals(e[a + 0x0D].toInt() and 0xFF, active.action.targetIndex, "$at target")
            assertEquals(e[a + 0x0E].toInt() and 0xFF, active.action.xMove, "$at xMove")
            assertEquals(e[a + 0x0F].toInt() and 0xFF, active.action.yMove, "$at yMove")
            assertEquals(e[a + 0x10].toInt() and 0xFF, active.action.moveCount, "$at moveCount")
            assertEquals(
                e[a + 0x11].toInt() and 0xFF, active.action.unitActionType, "$at unitActionType",
            )
            assertEquals(null, ProcArrayDump.ofEwram(e).find(MENU_SCRIPT), "$at menu proc ended")
            assertEquals(null, engine.find(menu.menuScript), "mine menu proc ended")
        }

        fun procSlot(address: Int): Int =
            (address - ProcArrayDump.ARRAY_ADDRESS) / ProcArrayDump.PROC_SIZE
    }

    private companion object {
        const val RUN = "ram_trace_newgame_easy"
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
        const val SMENUOVERRIDES = 0x03001870

        /** `EvtOverrideUnitMenu` command word: `EV_CMD_MENUOVERRIDE` (0x3D), length 2, sub 0. */
        const val EVT_MENUOVERRIDE_WORD = 0x3D20

        /** Every `EVENT_MENUOVERRIDE_*` bit of the unit menu except `EVENT_MENUOVERRIDE_WAIT`. */
        const val WAIT_ONLY_MASK = 0xFFFB

        /** Every unit menu bit except `EVENT_MENUOVERRIDE_ATTACK`. */
        const val ATTACK_ONLY_MASK = 0xFFFE

        /** Item number of Wait in `gUnitActionMenuItems`. */
        const val WAIT_ITEM_NUMBER = 28

        fun u16(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun u32(b: ByteArray, at: Int) = u16(b, at) or (u16(b, at + 2) shl 16)
    }
}

private class MenuVram : TextHost {
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
