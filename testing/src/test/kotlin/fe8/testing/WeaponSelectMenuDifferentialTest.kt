package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ActiveUnit
import fe8.game.bm.AttackRange
import fe8.game.bm.AttackTargets
import fe8.game.bm.BmMap
import fe8.game.bm.BmBattle
import fe8.game.bm.BmMapRender
import fe8.game.bm.BattleUnit
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.ItemInfo
import fe8.game.bm.ItemMenuLine
import fe8.game.bm.MenuItemPanel
import fe8.game.bm.MoveLimitView
import fe8.game.bm.PlaySt
import fe8.game.bm.SelectTargets
import fe8.game.bm.UnitActionMenu
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.scene.IntConsumer
import fe8.game.scene.FaceBlinkState
import fe8.game.scene.FaceState
import fe8.game.scene.IntSupplier
import fe8.game.scene.TalkScene
import fe8.game.text.Fe8Text
import fe8.game.ui.GreenText
import fe8.game.ui.ItemIcon
import fe8.game.ui.Menu
import fe8.game.ui.UiHand
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.LcgRng
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
 *
 * MC-3b: A on the weapon at f1660 (`WeaponSelectMenu_Selected`) runs on top of that window and is
 * compared with the dump after it: the active unit's struct (the item order after
 * `EquipUnitItemSlot`), the action data's item slot, BG0 and BG1 (`ClearBg0Bg1`), the range
 * squares that stay (`BallistaRangeMenu_SwitchOut` on a menu that is ending) and the menu's end.
 * The target selection it starts is MC-5 (a hook). B in the weapon list at f1730
 * (`ItemMenu_ButtonBPressed`) is run from the dump of f1703 (the target selection's cancel
 * proc, MC-6, is stood in for by starting the weapon list at f1704, where the dump has it) and
 * compared with the unit menu the game restarts.
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
        "GetUnitWeaponReachBits", "DrawItemMenuLine", "GetItemUses", "DrawIcon",
        "GetIconTileIndex", "GetIconGfxIndex", "Text_SetParams", "PutSpecialChar",
        "PutNumberExt", "PutNumber", "PutNumberOrBlank", "GetSpecialCharChr", "AddSpecialChar",
        "DrawSpecialCharGlyph", "ForceMenuItemPanel", "UpdateMenuItemPanel",
        "MenuItemPanelProcIdle", "BattleGenerateUiStats", "Text_DrawNumber",
        "Text_InsertDrawNumberOrBlank", "Text_DrawNumberOrBlank", "StartFace",
        "SetFaceBlinkControl", "SetFaceBlinkControlById", "EndFaceById", "EndFace",
        "GetUnitPortraitId",
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

    @Test
    @VerifiesPort(
        "WeaponSelectMenu_Selected", "EquipUnitItemSlot", "ClearBg0Bg1",
        "BallistaRangeMenu_SwitchOut",
    )
    fun `choosing a weapon equips it and clears the list`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val sim = Sim("ram_trace_prologue_attack_noanim", 1594)
        sim.seed()
        for (f in 1595..1658) sim.frame(f)
        // The unit holds its weapon in slot 1 before the choice.
        val before = sim.units.item(sim.active.unit, 0)
        sim.frame(WEAPON_CHOSEN)
        assertEquals(1, sim.selectCalls, "target selection started once")
        assertEquals(listOf(sim.units.item(sim.active.unit, 0)), sim.listItems, "target list item")
        assertEquals(false, before == sim.units.item(sim.active.unit, 0), "the weapon moved up")
    }

    @Test
    @VerifiesPort("ItemMenu_ButtonBPressed", "sub_8022E54")
    fun `cancelling the weapon list restarts the unit menu`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val sim = Sim("ram_trace_prologue_attack_noanim", CANCEL_SEED)
        sim.seed(CANCEL_SEED, 1700)
        for (f in CANCEL_SEED until WEAPON_CANCELLED) sim.cancelFrame(f)
        sim.cancelFrame(WEAPON_CANCELLED)
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
        lateinit var itemLine: ItemMenuLine
        lateinit var panel: MenuItemPanel
        lateinit var battle: BmBattle
        var arrowCalls = 0
        lateinit var scene: TalkScene
        var faceEnds = 0
        val iconTable = ByteArray(ItemIcon.MAX_ICON_COUNT * ItemIcon.ICON_ENTRY_SIZE)
        val iconGfxTable = ByteArray(ItemIcon.MAX_ICON_GFX_COUNT)

        var faceCalls = 0
        var panelCalls = 0
        val panelSlots = ArrayList<Int>()
        var resetCalls = 0
        val paletteCalls = ArrayList<Int>()
        val rowsDrawn = ArrayList<Int>()
        val shown = HashMap<Int, Int>()
        var selectCalls = 0
        val listItems = ArrayList<Int>()

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
            unitMenu = UnitActionMenu(units, active, itemInfo, loader, map, range, hw)
            view = MoveLimitView(engine, bm, map, hw, rom, BmMapRender(bm, map, hw))
            view.gameClock = IntSupplier { clock }
            unitMenu.hooks.weaponTargetCount = { unit, item ->
                targets.makeTargetListForWeapon(unit, item)
                targets.targets.getSelectTargetCount()
            }
            unitMenu.hooks.makeTargetListForWeapon = { unit, item ->
                listItems.add(item)
                targets.makeTargetListForWeapon(unit, item)
            }
            unitMenu.hooks.startAttackTargetSelection = { selectCalls++ }
            unitMenu.hooks.cursorScreenX = { bm.cursorTargetX - bm.cameraX }
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
            scene = TalkScene(
                rom, romBytes, hw, engine, sprites, keys, { clock }, messages, IntConsumer {},
                { 0 }, {}, LcgRng(),
            )
            unitMenu.hooks.chapterIndex = IntSupplier { playSt.chapterIndex }
            unitMenu.hooks.startFace = UnitActionMenu.FaceStarter { slot, fid, x, y, disp ->
                faceCalls++
                scene.face.startFace(slot, fid, x, y, disp)
            }
            unitMenu.hooks.setFaceBlinkControlById = UnitActionMenu.BlinkSetter { slot, control ->
                scene.face.setFaceBlinkControlById(slot, control)
            }
            menu.hooks.endFaceById = { slot ->
                faceEnds++
                scene.face.endFaceById(slot)
            }
            battle = BmBattle(rom, units, loader, playSt, bm) { y, x -> map.terrain[y, x] }
            panel = MenuItemPanel(
                engine, units, battle, itemInfo, loader, bm, text, messages, hw, rom, iconTable,
                iconGfxTable,
            ) { _, _, _ -> arrowCalls++ }
            unitMenu.hooks.forceMenuItemPanel = UnitActionMenu.PanelStarter { m, u, x, y ->
                panelCalls++
                panel.forceMenuItemPanel(m, u, x, y)
            }
            unitMenu.hooks.updateMenuItemPanel = IntConsumer {
                panelSlots.add(it)
                panel.updateMenuItemPanel(it)
            }
            itemLine = ItemMenuLine(
                text, itemInfo, messages, hw, rom, iconTable, iconGfxTable,
            )
            unitMenu.hooks.drawItemMenuLine = UnitActionMenu.ItemLineDrawer { row, item, ok, at ->
                rowsDrawn.add(at)
                itemLine.drawItemMenuLine(row, item, ok, hw.mapBuffer(0), at)
            }
            unitMenu.hooks.resetTextFont = { text.resetTextFont() }
            unitMenu.hooks.displayMoveRangeGraphics =
                IntConsumer { view.displayMoveRangeGraphics(it) }
            unitMenu.hooks.hideMoveRangeGraphics = { view.hideMoveRangeGraphics() }
            unitMenu.install(menu, rom)
        }


        fun ewram(frame: Int): ByteArray = data.region(names.getValue(frame), "ewram")

        fun iwram(frame: Int): ByteArray = data.region(names.getValue(frame), "iwram")

        fun seed(unitFrame: Int = UNIT_MENU_FIRST, vramFrame: Int = 1590) {
            val e = ewram(before)
            val iw = iwram(before)
            // The unit has reached its tile when the menu starts (the caller, PP-11, moved it):
            // units, action data and the unit map come from the menu's first frame.
            val first = ewram(unitFrame)
            val firstIw = iwram(unitFrame)
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
            scene.lcg.set(u32(iw, LCG - IWRAM))
            text.initTextFont(null, 0x06001000, 0x80, 0)
            val vram = data.region(names.getValue(vramFrame), "vram")
            vram.copyInto(host.vram, 0, 0, host.vram.size)
            vram.copyInto(hw.vram, 0, 0, minOf(vram.size, hw.vram.size))
            if (unitFrame == UNIT_MENU_FIRST) {
                // Icon bookkeeping the Attack command must clear.
                iconTable.fill(0x55)
                iconGfxTable.fill(0x55)
            } else {
                e.copyInto(iconTable, 0, DRAWN_ICONS - EWRAM, DRAWN_ICONS - EWRAM + iconTable.size)
                e.copyInto(
                    iconGfxTable, 0, ICON_GFX_IDS - EWRAM,
                    ICON_GFX_IDS - EWRAM + iconGfxTable.size,
                )
                val font = u32(e, GACTIVE_FONT - EWRAM) - EWRAM
                text.activeFont.chrCounter = half(e, font + 0x12)
                text.loadSpecialCharList(e, SPECIAL_CHAR_LIST - EWRAM)
            }

            // The other items' availability, from the first menu frame's item procs.
            val firstProcs = ProcArrayDump.ofEwram(ewram(UNIT_MENU_FIRST))
            val original = firstProcs.find(MENU_SCRIPT)!!
            for (i in 0 until original.u8(0x60)) {
                val item = firstProcs.Proc(procSlot(original.u32(0x34 + 4 * i)))
                shown[item.u8(0x3C)] = item.u8(0x3D)
            }
        }

        fun frame(f: Int) {
            // The game's frame of the weapon list's start (f1630 to f1631) runs past one
            // VBlank (the item panel numbers, BattleGenerateUiStats): the dump of f1631 is taken
            // in the middle of it (the menu is before its jump to the main loop, the range view
            // has no flags yet), and from then on the game's frame k is the dump of
            // k + 1 (so the inputs of that frame are `inputs[k]`, not `inputs[k - 1]`). The dump of
            // f1631 is not compared.
            val d = if (f > WEAPON_MENU_FIRST) f + 1 else f
            run(f, d)
            compare(f, d)
            compareVram(d)
        }

        /** One frame of the cancel window: the weapon list is started at its first frame. */
        fun cancelFrame(f: Int) {
            val d = f + 1
            // The dump of 1706 repeats the one of 1705: that frame outran a VBlank and the face
            // tree (root 5) did not run in it (the blink timer stays).
            run(f, d, skipFaceTree = f == CANCEL_LAG_FRAME)
            if (f >= WEAPON_CANCELLED - 1) compare(f, d)
            compareVram(d)
        }

        /**
         * The text tiles (the unit menu and weapon list strips, the panel's strips and the
         * special characters) and the icon tiles, in the dumps that have VRAM.
         */
        private fun compareVram(d: Int) {
            if (!names.getValue(d).startsWith("f")) return
            val dump = data.region(names.getValue(d), "vram")
            val at = "$runName dump $d VRAM"
            val counter = text.activeFont.chrCounter
            for (slot in 0 until counter) {
                for (k in 0 until 0x40) {
                    val a = 0x1000 + slot * 0x40 + k
                    assertEquals(dump[a], host.vram[a], "$at text slot $slot byte $k")
                }
            }
            if (scene.face.gFaces[0] != null) {
                val tiles = 0x10000 + scene.face.tileOffsetOf(0)
                // The upper 12 by 6 tiles of the portrait, a row of the sprite sheet 0x400 bytes
                // apart (the rows below change in the dumps of the cancel window: other tiles).
                for (row in 0 until FACE_TILE_ROWS) {
                    for (k in 0 until FACE_TILE_ROW_BYTES) {
                        val a = tiles + row * 0x400 + k
                        assertEquals(dump[a], hw.vram[a], "$at face tile byte 0x${a.toString(16)}")
                    }
                }
                val pal = data.region(names.getValue(d), "pal")
                val from = (scene.face.paletteIdOf(0) + 0x10) * 32
                // The palette reaches the dump at the VBlank after the start frame.
                for (k in 0 until if (d == WEAPON_MENU_FIRST) 0 else 32) {
                    assertEquals(pal[from + k], hw.palette[(from + k) / 2].let {
                        (if (k % 2 == 0) it.toInt() else it.toInt() shr 8).toByte()
                    }, "$at face palette byte $k")
                }
            }
            for (slot in 0 until ItemIcon.MAX_ICON_GFX_COUNT) {
                if (iconGfxTable[slot].toInt() == 0 || iconGfxTable[slot].toInt() == 0x55) continue
                val from = (0x20 * ItemIcon.getIconGfxTileIndex(slot + 1)) and 0x1FFE0
                for (a in from until from + ItemIcon.ICON_BYTES) {
                    assertEquals(
                        dump[a], hw.vram[a], "$at icon slot $slot byte 0x${a.toString(16)}",
                    )
                }
            }
        }

        private fun run(f: Int, d: Int, skipFaceTree: Boolean = false) {
            keys.update(data.inputs[d - 1])
            clock = u32(iwram(d), CLOCK - IWRAM)
            if (f == UNIT_MENU_FIRST) {
                // The caller (`PlayerPhase_ApplyUnitMovement`) runs before the menu procs.
                text.resetTextFont()
                menu.startSemiCenteredOrphanMenu(
                    Menu.UNIT_ACTION_MENU_DEF, bm.cursorTargetX - bm.cameraX, 1, 22,
                )
            }
            if (f == CANCEL_SEED) {
                // The target selection's cancel proc (`gProcScr_0859B630`, its timing is in
                // `TargetSelectionDifferentialTest`) runs `sub_8022E54`.
                unitMenu.unitActionMenuAttackFromProc()
            }
            sprites.clear()
            for (tree in 0 until 8) {
                if (!(skipFaceTree && tree == FACE_TREE)) engine.run(engine.root(tree))
            }
            scene.flushTileMoves()
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
            if (f == WEAPON_MENU_FIRST || f == WEAPON_CHOSEN - 2) compareIcons(e, at)
            val a = ACTION_DATA - EWRAM
            assertEquals(
                e[a + 0x11].toInt() and 0xFF, active.action.unitActionType, "$at unitActionType",
            )
            assertEquals(
                e[a + 0x12].toInt() and 0xFF, active.action.itemSlotIndex, "$at itemSlotIndex",
            )
            val unitAt = units.unitOffset(active.unit)
            val dumpAt = u32(iw, GACTIVEUNIT - IWRAM) - EWRAM
            for (k in 0 until UNIT_SIZE) {
                assertEquals(
                    e[dumpAt + k], units.bytes[unitAt + k], "$at unit byte 0x${k.toString(16)}",
                )
            }
            compareFace(dump, iw, at)
            // The weapon list's helper procs exist from the frame the menu starts.
            if (f >= WEAPON_CHOSEN) return
            val faces = dump.findAll(FACE_SCRIPT).size
            val panels = dump.findAll(PANEL_SCRIPT).size
            val started = f >= WEAPON_MENU_FIRST
            assertEquals(if (started) 1 else 0, minOf(faces, 1), "$at face proc exists")
            assertEquals(if (started) 1 else 0, panels, "$at item panel proc exists")
            assertEquals(if (started) 1 else 0, faceCalls, "$at face started")
            assertEquals(if (started) 1 else 0, panelCalls, "$at item panel started")
            if (started) comparePanel(dump, e, at)
            assertEquals(0, arrowCalls, "$at stat arrows (the dump's stats are equal)")
        }

        /**
         * The face proc of slot 0 (portrait, place, display bits, `oam2`), its blink child, and the
         * game's random number generator (the blink draws from it).
         */
        private fun compareFace(dump: ProcArrayDump, iw: ByteArray, at: String) {
            val original = dump.findAll(FACE_SCRIPT)
            val mine = scene.face.gFaces[0]
            assertEquals(original.size, if (mine != null) 1 else 0, "$at face count")
            assertEquals(u32(iw, LCG - IWRAM), scene.lcg.value, "$at random number generator")
            if (mine == null) return
            val face = original[0]
            val state = mine.state as FaceState
            assertEquals(face.u16(0x30), state.displayBits, "$at face display bits")
            assertEquals(face.u16(0x34), state.xPos, "$at face x")
            assertEquals(face.u16(0x36), state.yPos, "$at face y")
            assertEquals(face.u16(0x3C), state.oam2, "$at face oam2")
            assertEquals(face.u16(0x3E), state.faceId, "$at face portrait")
            assertEquals(face.u8(0x40), state.faceSlot, "$at face slot")
            assertEquals(face.u8(0x41), state.spriteLayer, "$at face sprite layer")
            val blink = dump.findAll(BLINK_SCRIPT).first { it.u32(0x2C) == face.address }
            val blinkProc = state.pBlinkProc!!
            val actual = blinkProc.state as FaceBlinkState
            assertEquals(blink.u16(0x30), actual.blinkControl, "$at blink control")
            assertEquals(blink.u16(0x32), actual.unk32, "$at blink unk32")
            assertEquals(blink.u32(0x38), actual.unk38, "$at blink interval")
            assertEquals(blink.scrCur, blinkProc.scrCur, "$at blink script position")
        }

        /**
         * The item panel proc (unit, position, icon palette, weapon slot, the strips and the arrow
         * switch) and the two battle units `BattleGenerateUiStats` fills.
         */
        private fun comparePanel(dump: ProcArrayDump, e: ByteArray, at: String) {
            val original = dump.find(PANEL_SCRIPT)!!
            val mine = engine.find(panel.script)
            assertEquals(true, mine != null, "$at item panel proc")
            val state = mine!!.state as MenuItemPanel.State
            assertEquals(
                original.u32(0x2C), UnitTable.BLUE_ADDRESS + state.unitOffset, "$at panel unit",
            )
            assertEquals(original.u8(0x30), state.x, "$at panel x")
            assertEquals(original.u8(0x31), state.y, "$at panel y")
            assertEquals(original.u8(0x32), state.iconPalIndex, "$at panel icon palette")
            assertEquals(original.u8(0x33).toByte().toInt(), state.itemSlotIndex, "$at panel slot")
            assertEquals(original.u8(0x64) != 0, state.drawArrow, "$at panel draw_arrow")
            for (i in 0 until 6) {
                val o = 0x34 + 8 * i
                val t = state.text[i]
                assertEquals(original.u16(o), t.chrPosition, "$at panel text $i chr position")
                assertEquals(original.u8(o + 2), t.x, "$at panel text $i x")
                assertEquals(original.u8(o + 3), t.colorId, "$at panel text $i colour")
                assertEquals(original.u8(o + 4), t.tileWidth, "$at panel text $i tile width")
                assertEquals(original.u8(o + 5) != 0, t.dbEnabled, "$at panel text $i db")
                assertEquals(original.u8(o + 6), t.dbId, "$at panel text $i buffer half")
            }
            for ((bu, address) in listOf(
                battle.actor to GBATTLE_ACTOR, battle.target to GBATTLE_TARGET,
            )) {
                for (i in 0 until BattleUnit.SIZE) {
                    assertEquals(
                        e[address - EWRAM + i], bu.bytes[i], "$at battle unit $address+$i",
                    )
                }
            }
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
                    assertEquals(
                        half(e, BG_BUFFERS[bg] - EWRAM + 2 * k),
                        hw.mapBuffer(bg)[k].toInt() and 0xFFFF,
                        "$at BG$bg[$k] (x=${k % 32}, y=${k / 32})",
                    )
                }
            }
        }

        private fun compareIcons(e: ByteArray, at: String) {
            for (k in 0 until ItemIcon.MAX_ICON_COUNT * ItemIcon.ICON_ENTRY_SIZE) {
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
        const val BLINK_SCRIPT = 0x08591264
        const val LCG = 0x03000008
        const val PANEL_SCRIPT = 0x0859AE88
        const val UNIT_SIZE = 0x48
        const val GBATTLE_ACTOR = 0x0203A4EC
        const val GBATTLE_TARGET = 0x0203A56C
        const val GACTIVE_FONT = 0x02028E70
        const val SPECIAL_CHAR_LIST = 0x02028E78
        const val UNIT_MENU_FIRST = 1595
        const val WEAPON_CHOSEN = 1659
        const val CANCEL_SEED = 1703
        const val CANCEL_LAG_FRAME = 1705
        const val FACE_TILE_ROWS = 6
        const val FACE_TILE_ROW_BYTES = 12 * 0x20
        const val FACE_TREE = 5
        const val WEAPON_CANCELLED = 1729
        const val WEAPON_MENU_FIRST = 1630
        val BG_BUFFERS = intArrayOf(0x02022CA8, 0x020234A8, 0x02023CA8, 0x020244A8)

        fun half(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun u32(b: ByteArray, at: Int) = half(b, at) or (half(b, at + 2) shl 16)
    }
}

internal class TextVram : gbafe.text.TextHost {
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
