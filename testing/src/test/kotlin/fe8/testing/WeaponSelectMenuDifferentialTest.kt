package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ActiveUnit
import fe8.game.bm.AttackRange
import fe8.game.bm.AttackTargets
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.ItemInfo
import fe8.game.bm.MoveLimitView
import fe8.game.bm.PlaySt
import fe8.game.bm.SelectTargets
import fe8.game.bm.UnitActionMenu
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.scene.IntConsumer
import fe8.game.scene.IntSupplier
import fe8.game.text.Fe8Text
import fe8.game.ui.GreenText
import fe8.game.ui.ItemIcon
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
import gbafe.text.TextSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The Attack command of the unit action menu and the weapon select menu it opens (MC-3a,
 * src/bmmenu.c) against `ram_trace_prologue_attack_noanim` f1595..f1658 (the same window of the
 * animations-on capture, f1395..f1429, is in the AttackTargetList test's seeds).
 *
 * Seeded from the dump of f1594 (units, `gActionData`, `gActiveUnit`, the maps, the BG
 * tilemaps, the palette, the LCD buffer), the unit menu is started as its caller does at f1595
 * and the proc trees run one frame per dump with the run's inputs. After every frame the
 * menu procs (rect, def, item count, current item, state, the shown items' number and
 * availability), the range maps `gBmMapMovement` and `gBmMapRange`, the range squares' procs
 * and flags, and BG0 (outside the weapon rows, which `DrawItemMenuLine` draws, MC-4), BG1, BG2
 * and BG3 are compared with the dump. At f1630 (A on Attack) the icon tables and palettes the
 * Attack command resets and loads, and the item panel and face the weapon list starts (they are
 * stand-ins until MC-4: the panel and face procs of the dump are matched with the hook calls)
 * are compared as well. The target lists are the ported ones (`MakeTargetListForWeapon`).
 * The selection at f1659 is not run: choosing a weapon is MC-3b.
 */
class WeaponSelectMenuDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "UnitActionMenu_Attack", "StartUnitWeaponSelect", "WeaponSelectMenu_IsAvailable",
        "WeaponSelectMenu_SwitchIn", "DisplayUnitStandingAttackRange",
        "HideMoveRangeGraphicsWrapper", "StartOrphanMenu", "GenerateUnitStandingReachRange",
        "GetUnitWeaponReachBits",
    )
    fun `attack opens the weapon list with the ranges of the game`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val sim = Sim("ram_trace_prologue_attack_noanim", 1594)
        sim.seed()
        for (f in 1595..1658) sim.frame(f)
        assertEquals(1, sim.faceCalls, "face started once")
        assertEquals(1, sim.panelCalls, "item panel started once")
        assertEquals(listOf(1), sim.panelSlots, "item panel slots shown")
        assertEquals(1, sim.resetCalls, "icon graphics reset once")
        assertEquals(listOf(4), sim.paletteCalls, "icon palettes loaded")
        assertEquals(1, sim.rowsDrawn.size, "weapon rows drawn")
    }

    private inner class Sim(val runName: String, val before: Int) {
        val data = ref.run(runName)
        val names = data.dumps.entries.associate { it.value to it.key }
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val procNames = ProcArrayDump.romNames()
        val engine = ProcEngine()
        val hw = BgHardware()
        val host = TextVram()
        val text = TextSystem(romBytes, host, Fe8Text.CONFIG)
        val keys = KeyStatus()
        val sprites = SpriteLayers()
        var clock = 0
        val hand = UiHand(rom, sprites)
        val greenText = GreenText(engine, rom, hw) { clock }
        val units = UnitTable(rom)
        val active = ActiveUnit()
        val itemInfo = ItemInfo(rom)
        val playSt = PlaySt()
        val bm = BmSt()
        val map: BmMap
        val menu: Menu
        val unitMenu: UnitActionMenu
        val view: MoveLimitView
        val iconTable = ByteArray(ItemIcon.MAX_ICON_COUNT * 2)
        val iconGfxTable = ByteArray(ItemIcon.MAX_ICON_GFX_COUNT)

        var faceCalls = 0
        var panelCalls = 0
        val panelSlots = ArrayList<Int>()
        var resetCalls = 0
        val paletteCalls = ArrayList<Int>()
        val rowsDrawn = ArrayList<Int>()
        val shown = HashMap<Int, Int>()

        init {
            val root = u32(romBytes, 0x0815D488 - ROM_BASE) - ROM_BASE
            val messages = MsgStrings(
                romBytes, MsgHuffman(romBytes, 0x0815A72C - ROM_BASE, root), 0x0815D48C - ROM_BASE,
            )
            menu = Menu(
                engine, rom, hw, text, messages, keys, hand, greenText,
                CamMove(engine, BmSt()).script,
            )
            map = BmMap(rom, ChapterData(rom), bm)
            val loader = UnitLoader(units, rom, { 0 }, RngService(), { false })
            val range = AttackRange(map, units, itemInfo, EntityBmMaps(map, units, playSt), loader)
            val targets = AttackTargets(
                map, units, itemInfo, range, ByteArray(TRAP_POOL_SIZE), SelectTargets(),
            )
            unitMenu = UnitActionMenu(units, active, itemInfo, loader, map, range)
            view = MoveLimitView(engine, bm, map, hw, rom, BmMapRender(bm, map, hw))
            view.gameClock = IntSupplier { clock }
            unitMenu.hooks.weaponTargetCount = { unit, item ->
                targets.makeTargetListForWeapon(unit, item)
                targets.targets.getSelectTargetCount()
            }
            unitMenu.hooks.unportedUsability = { _, number ->
                shown[number] ?: Menu.MENU_NOTSHOWN
            }
            unitMenu.hooks.resetIconGraphics = {
                resetCalls++
                ItemIcon.resetIconGraphics(iconTable, iconGfxTable)
            }
            unitMenu.hooks.loadIconPalettes = IntConsumer { palId ->
                paletteCalls.add(palId)
                ItemIcon.loadIconPalette(hw, rom, 0, palId)
                ItemIcon.loadIconPalette(hw, rom, 1, palId + 1)
            }
            unitMenu.hooks.startUnitFace = IntConsumer { faceCalls++ }
            unitMenu.hooks.forceMenuItemPanel = IntConsumer { panelCalls++ }
            unitMenu.hooks.updateMenuItemPanel = IntConsumer { panelSlots.add(it) }
            unitMenu.hooks.drawItemMenuLine =
                UnitActionMenu.ItemLineDrawer { _, _, _, offset -> rowsDrawn.add(offset) }
            unitMenu.hooks.displayMoveRangeGraphics =
                IntConsumer { view.displayMoveRangeGraphics(it) }
            unitMenu.hooks.hideMoveRangeGraphics = { view.hideMoveRangeGraphics() }
            unitMenu.install(menu, rom)
        }

        fun ewram(frame: Int): ByteArray = data.region(names.getValue(frame), "ewram")

        fun iwram(frame: Int): ByteArray = data.region(names.getValue(frame), "iwram")

        fun seed() {
            val e = ewram(before)
            val iw = iwram(before)
            // The unit has reached its tile when the menu starts (the caller, PP-11, moved it):
            // units, action data and the unit map come from the menu's first frame.
            val first = ewram(UNIT_MENU_FIRST)
            val firstIw = iwram(UNIT_MENU_FIRST)
            units.loadFrom(first)
            playSt.decode(e, PLAY_ST - EWRAM)
            bm.decode(e, BM_ST - EWRAM)
            map.initChapterMap(playSt.chapterIndex)
            readPool(first, firstIw, GBM_MAP_UNIT, map.unit)
            readPool(e, iw, GBM_MAP_TERRAIN, map.terrain)
            readPool(e, iw, GBM_MAP_MOVEMENT, map.movement)
            readPool(e, iw, GBM_MAP_RANGE, map.range)
            readPool(e, iw, GBM_MAP_FOG, map.fog)
            val pointer = u32(firstIw, GACTIVEUNIT - IWRAM)
            active.unit = units.bytes[pointer - UnitTable.BLUE_ADDRESS + 0x0B].toInt() and 0xFF
            val a = ACTION_DATA - EWRAM
            active.action.subjectIndex = first[a + 0x0C].toInt() and 0xFF
            active.action.targetIndex = first[a + 0x0D].toInt() and 0xFF
            active.action.xMove = first[a + 0x0E].toInt() and 0xFF
            active.action.yMove = first[a + 0x0F].toInt() and 0xFF
            active.action.moveCount = first[a + 0x10].toInt() and 0xFF
            active.action.unitActionType = first[a + 0x11].toInt() and 0xFF
            for (bg in 0..3) {
                for (k in 0 until 0x400) {
                    hw.mapBuffer(bg)[k] = half(e, BG_BUFFERS[bg] - EWRAM + 2 * k).toShort()
                }
            }
            for (i in 0 until BgHardware.PALETTE_COLORS) {
                hw.palette[i] = half(e, PALETTE - EWRAM + 2 * i).toShort()
            }
            hw.lcd.readFrom(iw, LCD_BUFFER - IWRAM)
            text.initTextFont(null, 0x06001000, 0x80, 0)
            // Icon bookkeeping the Attack command must clear.
            iconTable.fill(0x55)
            iconGfxTable.fill(0x55)

            // The other items' availability, from the first menu frame's item procs.
            val firstProcs = ProcArrayDump.ofEwram(ewram(UNIT_MENU_FIRST))
            val original = firstProcs.find(MENU_SCRIPT)!!
            for (i in 0 until original.u8(0x60)) {
                val item = firstProcs.Proc(procSlot(original.u32(0x34 + 4 * i)))
                shown[item.u8(0x3C)] = item.u8(0x3D)
            }
        }

        fun frame(f: Int) {
            keys.update(data.inputs[f - 1])
            // The game's frame of the weapon list's start (f1630 to f1631) runs past one
            // VBlank (the item panel numbers, BattleGenerateUiStats): the dump of f1631 is taken
            // in the middle of it (the menu is before its jump to the main loop, the range view
            // has no flags yet), and from then on the game's frame k is the dump of
            // k + 1. The dump of f1631 is not compared.
            val d = if (f > WEAPON_MENU_FIRST) f + 1 else f
            clock = u32(iwram(d), CLOCK - IWRAM)
            if (f == UNIT_MENU_FIRST) {
                // The caller (`PlayerPhase_ApplyUnitMovement`) runs before the menu procs.
                menu.startSemiCenteredOrphanMenu(
                    Menu.UNIT_ACTION_MENU_DEF, bm.cursorTargetX - bm.cameraX, 1, 22,
                )
            }
            sprites.clear()
            for (tree in 0 until 8) engine.run(engine.root(tree))
            compare(f, d)
        }

        private fun compare(f: Int, d: Int) {
            val e = ewram(d)
            val iw = iwram(d)
            val at = "$runName f$f (dump $d)"
            val dump = ProcArrayDump.ofEwram(e, procNames)
            compareMenu(dump, at)
            compareView(dump, at)
            comparePools(e, iw, at)
            compareTilemaps(e, f, at)
            if (f == WEAPON_MENU_FIRST) compareIcons(e, f, at)
            val a = ACTION_DATA - EWRAM
            assertEquals(
                e[a + 0x11].toInt() and 0xFF, active.action.unitActionType, "$at unitActionType",
            )
            // The weapon list's helper procs exist from the frame the menu starts.
            val faces = dump.findAll(FACE_SCRIPT).size
            val panels = dump.findAll(PANEL_SCRIPT).size
            val started = f >= WEAPON_MENU_FIRST
            assertEquals(if (started) 1 else 0, minOf(faces, 1), "$at face proc exists")
            assertEquals(if (started) 1 else 0, panels, "$at item panel proc exists")
            assertEquals(if (started) 1 else 0, faceCalls, "$at face started")
            assertEquals(if (started) 1 else 0, panelCalls, "$at item panel started")
        }

        private fun compareMenu(dump: ProcArrayDump, at: String) {
            val original = dump.find(MENU_SCRIPT)
            val mine = engine.find(menu.menuScript)
            assertEquals(original != null, mine != null, "$at menu proc exists")
            if (original == null) return
            val m = mine!!.state as Menu.MenuProc
            assertEquals(original.u8(0x60), m.itemCount, "$at itemCount")
            for (k in 0..3) {
                val got = when (k) {
                    0 -> m.rect.x
                    1 -> m.rect.y
                    2 -> m.rect.w
                    else -> m.rect.h
                }
                assertEquals(original.u8(0x2C + k).toByte().toInt(), got, "$at menu rect $k")
            }
            assertEquals(original.u32(0x30), m.def, "$at menu def")
            assertEquals(original.u8(0x61), m.itemCurrent, "$at itemCurrent")
            assertEquals(original.u8(0x62), m.itemPrevious, "$at itemPrevious")
            assertEquals(original.u8(0x63), m.state, "$at menu state")
            assertEquals(original.u8(0x64) and 3, m.backBg, "$at backBg")
            assertEquals((original.u8(0x64) shr 2) and 3, m.frontBg, "$at frontBg")
            assertEquals(original.u16(0x66), m.tileref, "$at tileref")
            for (i in 0 until m.itemCount) {
                val o = dump.Proc(procSlot(original.u32(0x34 + 4 * i)))
                val item = m.menuItems[i]!!
                assertEquals(o.u32(0x30), item.def, "$at item $i def")
                assertEquals(o.u8(0x3C), item.itemNumber, "$at item $i number")
                assertEquals(o.u8(0x3D), item.availability, "$at item $i availability")
                assertEquals(o.u16(0x2A), item.xTile, "$at item $i x")
                assertEquals(o.u16(0x2C), item.yTile, "$at item $i y")
            }
        }

        private fun compareView(dump: ProcArrayDump, at: String) {
            val original = dump.find("sProcScr_MoveLimitView")
            val mine = engine.find(view.viewScript)
            assertEquals(original != null, mine != null, "$at range view exists")
            if (original == null) return
            assertEquals(
                original.u16(0x4A), (mine!!.state as MoveLimitView.State).flags,
                "$at range view flags",
            )
        }

        private fun comparePools(e: ByteArray, iw: ByteArray, at: String) {
            val want = BmMap(rom, ChapterData(rom), bm)
                .also { it.initChapterMap(playSt.chapterIndex) }
            readPool(e, iw, GBM_MAP_MOVEMENT, want.movement)
            readPool(e, iw, GBM_MAP_RANGE, want.range)
            for (y in 0 until map.sizeY) {
                for (x in 0 until map.sizeX) {
                    assertEquals(
                        want.movement[y, x], map.movement[y, x], "$at movement map ($x,$y)",
                    )
                    assertEquals(want.range[y, x], map.range[y, x], "$at range map ($x,$y)")
                }
            }
        }

        private fun compareTilemaps(e: ByteArray, f: Int, at: String) {
            for (bg in 0..3) {
                for (k in 0 until 0x400) {
                    if (inMc4Tiles(bg, k, f)) continue
                    assertEquals(
                        half(e, BG_BUFFERS[bg] - EWRAM + 2 * k),
                        hw.mapBuffer(bg)[k].toInt() and 0xFFFF,
                        "$at BG$bg[$k] (x=${k % 32}, y=${k / 32})",
                    )
                }
            }
        }

        /**
         * The tiles MC-4 draws: the item panel (`ForceMenuItemPanel(proc, unit, 0xF, 0xB)`) on
         * BG0 and BG1, and the weapon list's item rows (`DrawItemMenuLine`) on BG0.
         */
        private fun inMc4Tiles(bg: Int, k: Int, f: Int): Boolean {
            if (f < WEAPON_MENU_FIRST || bg > 1) return false
            val x = k % 32
            val y = k / 32
            if (x in 15..29 && y in 11..18) return true
            if (bg != 0) return false
            val m = engine.find(menu.menuScript)?.state as Menu.MenuProc? ?: return false
            for (i in 0 until m.itemCount) {
                val item = m.menuItems[i]!!
                if (y in item.yTile..item.yTile + 1 && x in item.xTile until item.xTile + 15) {
                    return true
                }
            }
            return false
        }

        private fun compareIcons(e: ByteArray, f: Int, at: String) {
            for (k in 0 until ItemIcon.MAX_ICON_COUNT * 2) {
                assertEquals(
                    e[DRAWN_ICONS - EWRAM + k], iconTable[k], "$at DrawnIconLookupTable[$k]",
                )
            }
            for (k in 0 until ItemIcon.MAX_ICON_GFX_COUNT) {
                assertEquals(
                    e[ICON_GFX_IDS - EWRAM + k], iconGfxTable[k], "$at IconGFXIDLookupTable[$k]",
                )
            }
            for (k in 0x40 until 0x60) {
                assertEquals(
                    half(e, PALETTE - EWRAM + 2 * k), hw.palette[k].toInt() and 0xFFFF,
                    "$at icon palette colour 0x${k.toString(16)}",
                )
            }
        }

        private fun readPool(e: ByteArray, iw: ByteArray, handle: Int, pool: BmMap.Pool) {
            fun byte(address: Int) =
                (if (address >= IWRAM) iw[address - IWRAM] else e[address - EWRAM]).toInt() and 0xFF
            fun mem32(address: Int) = byte(address) or (byte(address + 1) shl 8) or
                (byte(address + 2) shl 16) or (byte(address + 3) shl 24)
            val first = mem32(mem32(handle) - 8)
            for (y in -2 until map.sizeY + 2) {
                for (x in 0 until map.sizeX + 2) {
                    pool[y, x] = byte(first + (y + 2) * (map.sizeX + 2) + x)
                }
            }
        }

        fun procSlot(address: Int): Int =
            (address - ProcArrayDump.ARRAY_ADDRESS) / ProcArrayDump.PROC_SIZE
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val ROM_BASE = 0x08000000
        const val CLOCK = 0x03000014
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val ACTION_DATA = 0x0203A958
        const val GACTIVEUNIT = 0x03004E50
        const val PALETTE = 0x020228A8
        const val LCD_BUFFER = 0x03003080
        const val DRAWN_ICONS = 0x02026A90
        const val ICON_GFX_IDS = 0x02026E10
        const val TRAP_POOL_SIZE = 0x200
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val GBM_MAP_MOVEMENT = 0x0202E4E0
        const val GBM_MAP_RANGE = 0x0202E4E4
        const val GBM_MAP_FOG = 0x0202E4E8
        const val MENU_SCRIPT = 0x085B64D0
        const val FACE_SCRIPT = 0x08591154
        const val PANEL_SCRIPT = 0x0859AE88
        const val UNIT_MENU_FIRST = 1595
        const val WEAPON_MENU_FIRST = 1630
        val BG_BUFFERS = intArrayOf(0x02022CA8, 0x020234A8, 0x02023CA8, 0x020244A8)

        fun half(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun u32(b: ByteArray, at: Int) = half(b, at) or (half(b, at + 2) shl 16)
    }
}

private class TextVram : gbafe.text.TextHost {
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
