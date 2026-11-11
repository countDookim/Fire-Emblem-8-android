package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.text.Fe8Text
import fe8.game.ui.GreenText
import fe8.game.ui.Menu
import fe8.game.ui.UiHand
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextHost
import gbafe.text.TextSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The generic menu (src/uimenu.c) against the Prologue unit menu and map menu of
 * `ram_trace_prologue_select_move` (unit menu f1314..f1355, closed by A on Wait) and
 * `ram_trace_prologue_cancel_end` (unit menu f1374..f1400, closed by B; map menu f1465..f1510,
 * UP wraps to End, closed by A on End).
 *
 * Each window is seeded from the per-frame RAM of the frame before the menu opens (BG0 and BG1
 * tilemap buffers, the BG palette, the LCD buffer, the key status, the hand state; text VRAM from
 * the last full dump). The menu is started as the original caller does (the `gUnitActionMenuDef`
 * or `gMapMenuDef` address, the cursor's screen x), the keys come from the run's `inputs.bin`, and
 * every frame runs the proc trees once. Each frame is compared with the dump of the same frame:
 * the menu proc (script position, header, rect, def, item count / current / previous, state,
 * BGs, tileref) and each item proc (position, def, number, availability, text strip), the BG0 and
 * BG1 tilemap buffers, BG palette colour 14 (the green text proc), the BG0/BG1 scroll, the key
 * status, and the hand's previous position and clock frame (the cursor position passed to
 * `DisplayUiHand`, with its smoothing). Full dumps (every 10th frame) also compare the text
 * glyph VRAM and the hand sprite in OAM (the dump holds the previous pass's OAM).
 *
 * The item callbacks are stand-ins (PP-10 unit menu, PP-12 map menu): availability returns what
 * the original item procs show (hidden items are not shown), the Guide draw callback draws the
 * item name in the colour the original item has, and the select callbacks of Wait and End return
 * the decomp's result `SKIPCURSOR | END | SND6A | CLEAR`. `MenuAlwaysEnabled` and
 * `MenuCancelSelect` are the ports.
 */
class MenuDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "StartSemiCenteredOrphanMenu", "StartOrphanMenuAdjusted", "StartMenuAt", "StartMenuCore",
        "Menu_OnInit", "RedrawMenu", "DrawMenuItemHover", "Menu_OnIdle",
        "ProcessMenuDpadInput", "ProcessMenuSelectInput", "EndMenu", "ClearMenuBgs",
        "GetMenuCursorPosition", "ApplyMenuCursorVScroll", "MenuAlwaysEnabled",
        "DrawUiFrame", "ClearUiFrame", "DrawUiItemHoverExt", "ClearUiItemHoverExt",
        "DisplayUiHand", "GreenText_OnLoop", "StartGreenText",
    )
    fun `unit menu opens, moves to Wait and closes on A like the game`() {
        assumeInputs()
        val sim = Sim("ram_trace_prologue_select_move", 1314, 1355, Menu.UNIT_ACTION_MENU_DEF)
        sim.run()
        assertEquals(42, sim.framesCompared, "frames compared")
        assertEquals(listOf(1320, 1330, 1340, 1350), sim.fullDumps, "full dumps compared")
        assertEquals(listOf(0x68, 0x66, 0x6A), sim.sounds, "sounds: open, cursor, select")
        assertEquals(0, sim.locks, "game lock after the menu ended")
        assertEquals(2, sim.itemCountAtStart, "unit menu items")
    }

    @Test
    @VerifiesPort(
        "StartSemiCenteredOrphanMenu", "StartOrphanMenuAdjusted", "StartMenuAt", "StartMenuCore",
        "Menu_OnIdle", "ProcessMenuSelectInput", "EndMenu", "ClearMenuBgs", "MenuCancelSelect",
        "DisplayUiHand", "GreenText_OnLoop",
    )
    fun `unit menu closes on B (cancel) like the game`() {
        assumeInputs()
        val sim = Sim("ram_trace_prologue_cancel_end", 1374, 1400, Menu.UNIT_ACTION_MENU_DEF)
        sim.run()
        assertEquals(27, sim.framesCompared, "frames compared")
        assertEquals(listOf(1380, 1390, 1400), sim.fullDumps, "full dumps compared")
        assertEquals(listOf(0x68, 0x6B), sim.sounds, "sounds: open, cancel")
        assertEquals(0, sim.locks, "game lock after the menu ended")
    }

    @Test
    @VerifiesPort(
        "StartOrphanMenuAdjusted", "StartMenuAt", "StartMenuCore", "RedrawMenu",
        "DrawMenuItemHover", "Menu_OnIdle", "ProcessMenuDpadInput", "ProcessMenuSelectInput",
        "EndMenu", "ClearMenuBgs", "GetMenuCursorPosition", "DrawUiFrame", "ClearUiFrame",
        "DrawUiItemHoverExt", "ClearUiItemHoverExt", "DisplayUiHand", "GreenText_OnLoop",
    )
    fun `map menu opens, wraps up to End and closes on A like the game`() {
        assumeInputs()
        val sim = Sim("ram_trace_prologue_cancel_end", 1465, 1510, Menu.MAP_MENU_DEF)
        sim.run()
        assertEquals(46, sim.framesCompared, "frames compared")
        assertEquals(listOf(1470, 1480, 1490, 1500, 1510), sim.fullDumps, "full dumps compared")
        assertEquals(listOf(0x68, 0x66, 0x6A), sim.sounds, "sounds: open, cursor, select")
        assertEquals(0, sim.locks, "game lock after the menu ended")
        assertEquals(6, sim.itemCountAtStart, "map menu items")
    }

    private fun assumeInputs() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
    }

    private inner class Sim(
        val runName: String,
        val first: Int,
        val last: Int,
        val def: Int,
    ) {
        val data = ref.run(runName)
        val names = data.dumps.entries.associate { it.value to it.key }
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val engine = ProcEngine()
        val hw = BgHardware()
        val vramHost = VramHost()
        val text = TextSystem(romBytes, vramHost, Fe8Text.CONFIG)
        val keys = KeyStatus()
        val sprites = SpriteLayers()
        var clock = 0
        val hand = UiHand(rom, sprites)
        val greenText = GreenText(engine, rom, hw) { clock }
        val camMove = CamMove(engine, BmSt())
        val messages: MsgStrings
        val menu: Menu
        val sounds = ArrayList<Int>()
        var locks = 0
        var framesCompared = 0
        var itemCountAtStart = 0
        val fullDumps = ArrayList<Int>()
        val keyBuffer = ByteArray(KeyStatus.STRUCT_SIZE)

        /** The hand put by the previous pass (x, y), or null when it put none. */
        var handBefore: IntArray? = null
        val handNow = IntArray(2)

        init {
            val root = u32(romBytes, 0x0815D488 - ROM_BASE) - ROM_BASE
            messages = MsgStrings(
                romBytes, MsgHuffman(romBytes, 0x0815A72C - ROM_BASE, root), 0x0815D48C - ROM_BASE,
            )
            menu = Menu(engine, rom, hw, text, messages, keys, hand, greenText, camMove.script)
            menu.hooks.lockGame = { locks++ }
            menu.hooks.unlockGame = { locks-- }
            menu.hooks.playSoundEffect = { sounds.add(it) }
            menu.hooks.gameClock = { clock }
        }

        fun ewram(frame: Int): ByteArray = data.region(names.getValue(frame), "ewram")

        fun iwram(frame: Int): ByteArray = data.region(names.getValue(frame), "iwram")

        fun seed() {
            val e = ewram(first - 1)
            for (bg in 0..1) {
                val at = (if (bg == 0) BG0_BUFFER else BG1_BUFFER) - EWRAM
                for (i in 0 until 0x400) hw.mapBuffer(bg)[i] = u16(e, at + 2 * i).toShort()
            }
            if (def != Menu.UNIT_ACTION_MENU_DEF) applySideWindowEnd()
            for (i in 0 until 0x200) {
                hw.palette[i] = u16(e, PALETTE_BUFFER - EWRAM + 2 * i).toShort()
            }
            hw.lcd.readFrom(iwram(first - 1), LCD_BUFFER - IWRAM)
            keys.readFrom(e, KEY_STATUS - EWRAM)
            hand.prevX = s16(u16(e, HAND_PREV - EWRAM))
            hand.prevY = s16(u16(e, HAND_PREV - EWRAM + 2))
            hand.prevClockFrame = u32(e, HAND_PREV - EWRAM + 4)
            // The last full dump before the window holds the VRAM.
            val vramFrame = (first - 1) / 10 * 10
            System.arraycopy(
                data.region(names.getValue(vramFrame), "vram"), 0, vramHost.vram, 0, 0x18000,
            )
            text.initTextFont(null, 0x06001000, 0x80, 0)
            // The map menu starts on the font as the player phase left it; the unit menu's
            // caller (bmmenu.c, PP-10) resets the text first.
            if (def != Menu.UNIT_ACTION_MENU_DEF) {
                text.activeFont.chrCounter = u16(e, DEFAULT_FONT - EWRAM + 0x12)
            }
        }

        /**
         * Stand-in for the map menu's caller (playerphase.c, PP-12): `EndPlayerPhaseSideWindows`
         * runs before `StartOrphanMenuAdjusted` and clears the terrain and unit windows. Every
         * tile outside the menu's rectangle takes the original's value of the first menu frame;
         * the tiles inside it are the menu's to draw.
         */
        fun applySideWindowEnd() {
            val e = ewram(first)
            val menuProc = ProcArrayDump.ofEwram(e).find(MENU_SCRIPT)!!
            val x0 = menuProc.u8(0x2C)
            val y0 = menuProc.u8(0x2D)
            val x1 = x0 + menuProc.u8(0x2E)
            val y1 = y0 + menuProc.u8(0x2F)
            for (bg in 0..1) {
                val at = (if (bg == 0) BG0_BUFFER else BG1_BUFFER) - EWRAM
                for (i in 0 until 0x400) {
                    val x = i % 32
                    val y = i / 32
                    if (x < x0 || x >= x1 || y < y0 || y >= y1) {
                        hw.mapBuffer(bg)[i] = u16(e, at + 2 * i).toShort()
                    }
                }
            }
        }

        fun registerStandIns() {
            // Availability: what the original menu's item procs show at the first menu frame.
            val originalDump = ProcArrayDump.ofEwram(ewram(first))
            val originalMenu = originalDump.find(MENU_SCRIPT)!!
            val shown = HashMap<Int, Int>()
            for (i in 0 until originalMenu.u8(0x60)) {
                val item = originalDump.Proc(procSlot(originalMenu.u32(0x34 + 4 * i)))
                shown[item.u8(0x3C)] = item.u8(0x3D)
            }
            itemCountAtStart = shown.size
            val items = rom.u32(def + 8)
            var i = 0
            while (rom.u32(items + 0x24 * i + 0xC) != 0) {
                val address = rom.u32(items + 0x24 * i + 0xC)
                if (address != Menu.MENU_ALWAYS_ENABLED) {
                    menu.callbacks[address] = Menu.MenuFunc { _, _, _, number ->
                        shown[number] ?: Menu.MENU_NOTSHOWN
                    }
                }
                i++
            }
            val result = Menu.MENU_ACT_SKIPCURSOR or Menu.MENU_ACT_END or Menu.MENU_ACT_SND6A or
                Menu.MENU_ACT_CLEAR
            menu.callbacks[EFFECT_WAIT] = Menu.MenuFunc { _, _, _, _ -> result }
            menu.callbacks[COMMAND_EFFECT_END_PLAYER_PHASE] =
                Menu.MenuFunc { _, _, _, _ -> result }
            // Guide: the item name in the colour the original gave it, drawn like RedrawMenu.
            val guideColor = (0 until originalMenu.u8(0x60)).map {
                originalDump.Proc(procSlot(originalMenu.u32(0x34 + 4 * it)))
            }.firstOrNull { it.u8(0x3C) == GUIDE_ITEM_NUMBER }?.u8(0x34 + 3)
            menu.callbacks[GUIDE_COMMAND_DRAW] = Menu.MenuFunc { m, item, _, _ ->
                item!!.text.colorId = guideColor!!
                text.drawString(item.text, messages.getStringFromIndex(rom.u16(item.def + 4)))
                text.putText(item.text, hw.mapBuffer(m!!.frontBg), item.yTile * 32 + item.xTile)
                0
            }
        }

        fun run() {
            seed()
            registerStandIns()
            val bm = BmSt().also { it.decode(ewram(first - 1), BM_ST - EWRAM) }
            val xSubject = bm.cursorTargetX - bm.cameraX
            for (f in first..last) {
                keys.update(data.inputs[f - 1])
                clock = u32(iwram(f), CLOCK - IWRAM)
                sprites.clear()
                if (f == first) {
                    if (def == Menu.UNIT_ACTION_MENU_DEF) {
                        menu.startSemiCenteredOrphanMenu(def, xSubject, 1, 22)
                    } else {
                        menu.startOrphanMenuAdjusted(def, xSubject, 1, 0x17)
                    }
                }
                for (tree in 0 until 8) engine.run(engine.root(tree))
                compare(f)
                framesCompared++
                val put = sprites.layers[2].next
                if (put != null) {
                    handNow[0] = put.oam1
                    handNow[1] = put.oam0
                    handBefore = handNow
                } else {
                    handBefore = null
                }
            }
            assertTrue(engine.find(menu.menuScript) == null, "menu proc ended at f$last")
        }

        fun compare(f: Int) {
            val at = "$runName f$f"
            val e = ewram(f)
            val original = ProcArrayDump.ofEwram(e)
            val originalMenu = original.find(MENU_SCRIPT)
            val mineMenu = engine.find(menu.menuScript)
            if (f < last) {
                assertTrue(originalMenu != null && mineMenu != null, "$at menu proc alive")
                compareMenuProc(at, original, originalMenu!!, mineMenu!!)
            } else {
                assertTrue(originalMenu == null && mineMenu == null, "$at menu proc ended")
                assertEquals(0, original.findAll(MENU_ITEM_SCRIPT).size, "$at item procs left")
                assertEquals(0, engine.count(menu.menuItemScript), "$at mine item procs left")
            }
            for (bg in 0..1) {
                val base = (if (bg == 0) BG0_BUFFER else BG1_BUFFER) - EWRAM
                for (i in 0 until 0x400) {
                    assertEquals(
                        u16(e, base + 2 * i), hw.mapBuffer(bg)[i].toInt() and 0xFFFF,
                        "$at BG$bg tilemap[$i]",
                    )
                }
            }
            assertEquals(
                u16(e, PALETTE_BUFFER - EWRAM + 28), hw.palette[14].toInt() and 0xFFFF,
                "$at palette colour 14",
            )
            val lcd = gbafe.render.LcdControl().also { it.readFrom(iwram(f), LCD_BUFFER - IWRAM) }
            for (bg in 0..1) {
                assertEquals(lcd.bgHofs[bg], hw.lcd.bgHofs[bg], "$at BG$bg hofs")
                assertEquals(lcd.bgVofs[bg], hw.lcd.bgVofs[bg], "$at BG$bg vofs")
            }
            keys.writeTo(keyBuffer)
            assertEquals(
                e.copyOfRange(
                    KEY_STATUS - EWRAM, KEY_STATUS - EWRAM + KeyStatus.STRUCT_SIZE,
                ).toList(),
                keyBuffer.toList(), "$at key status",
            )
            assertEquals(s16(u16(e, HAND_PREV - EWRAM)), hand.prevX, "$at hand x")
            assertEquals(s16(u16(e, HAND_PREV - EWRAM + 2)), hand.prevY, "$at hand y")
            assertEquals(u32(e, HAND_PREV - EWRAM + 4), hand.prevClockFrame, "$at hand clock")
            if (names.getValue(f).startsWith("f")) {
                fullDumps.add(f)
                compareText(at, f)
                compareHandOam(at, f)
            }
        }

        fun compareMenuProc(
            at: String,
            dump: ProcArrayDump,
            original: ProcArrayDump.Proc,
            mine: Proc,
        ) {
            comparePosition("$at menu", original, mine, SCRIPT_ADDRESS.getValue(menu.menuScript))
            val m = mine.state as Menu.MenuProc
            assertEquals(3, original.u32(0x14), "$at menu root tree")
            assertEquals(3, mine.rootIndex, "$at mine root tree")
            assertEquals(original.u8(0x2C), m.rect.x and 0xFF, "$at rect x")
            assertEquals(original.u8(0x2D), m.rect.y and 0xFF, "$at rect y")
            assertEquals(original.u8(0x2E), m.rect.w and 0xFF, "$at rect w")
            assertEquals(original.u8(0x2F), m.rect.h and 0xFF, "$at rect h")
            assertEquals(original.u32(0x30), m.def, "$at def")
            assertEquals(original.u8(0x60), m.itemCount, "$at itemCount")
            assertEquals(original.u8(0x61), m.itemCurrent, "$at itemCurrent")
            assertEquals(original.u8(0x62), m.itemPrevious, "$at itemPrevious")
            assertEquals(original.u8(0x63), m.state, "$at state")
            assertEquals(original.u8(0x64) and 3, m.backBg, "$at backBg")
            assertEquals((original.u8(0x64) shr 2) and 3, m.frontBg, "$at frontBg")
            assertEquals(original.u16(0x66), m.tileref, "$at tileref")
            assertEquals(original.u16(0x68), m.unk68, "$at unk68")
            for (i in 0 until m.itemCount) {
                val item = dump.Proc(procSlot(original.u32(0x34 + 4 * i)))
                val mineItem = m.menuItems[i]!!
                compareItem("$at item $i", item, mineItem, dump, original)
            }
            assertEquals(
                dump.findAll(MENU_ITEM_SCRIPT).size, engine.count(menu.menuItemScript),
                "$at item proc count",
            )
            assertEquals(
                dump.findAll(GREEN_TEXT_SCRIPT).size, engine.count(greenText.script),
                "$at green text proc count",
            )
            for (green in dump.findAll(GREEN_TEXT_SCRIPT)) {
                assertEquals(original.address, green.u32(0x14), "$at green text parent")
                comparePosition("$at green", green, engine.find(greenText.script)!!,
                    SCRIPT_ADDRESS.getValue(greenText.script))
            }
        }

        fun compareItem(
            at: String,
            original: ProcArrayDump.Proc,
            mine: Menu.MenuItemProc,
            dump: ProcArrayDump,
            parent: ProcArrayDump.Proc,
        ) {
            comparePosition(at, original, mine.proc, SCRIPT_ADDRESS.getValue(menu.menuItemScript))
            assertEquals(parent.address, original.u32(0x14), "$at parent")
            assertTrue(mine.proc.parent === engine.find(menu.menuScript), "$at mine parent")
            assertEquals(original.u16(0x2A).let(::s16), mine.xTile, "$at xTile")
            assertEquals(original.u16(0x2C).let(::s16), mine.yTile, "$at yTile")
            assertEquals(original.u32(0x30), mine.def, "$at def")
            val textBytes = ByteArray(8)
            mine.text.serialize(textBytes)
            assertEquals(
                (0 until 8).map { original.u8(0x34 + it) }, textBytes.map { it.toInt() and 0xFF },
                "$at text strip",
            )
            assertEquals(original.u8(0x3C), mine.itemNumber and 0xFF, "$at itemNumber")
            assertEquals(original.u8(0x3D), mine.availability, "$at availability")
        }

        /** Script, script position, header bytes of a proc against the original's. */
        fun comparePosition(
            at: String,
            original: ProcArrayDump.Proc,
            mine: Proc,
            scriptAddress: Int,
        ) {
            val mineScript = SCRIPT_ADDRESS.getValue(mine.script!!)
            assertEquals(scriptAddress, mineScript, "$at script")
            assertEquals(original.u32(0), mineScript, "$at original script")
            assertEquals(
                original.u32(4), SCRIPT_ADDRESS.getValue(mine.scrScript!!) + 8 * mine.scrCur,
                "$at script position",
            )
            assertEquals(original.u32(0xC) != 0, mine.idleCb != null, "$at repeat callback set")
            assertEquals(s16(original.u16(0x24)), mine.sleepTime, "$at sleepTime")
            assertEquals(original.u8(0x26), mine.mark, "$at mark")
            assertEquals(original.u8(0x28), mine.lockCnt, "$at lockCnt")
            // Procs without PROC_NAME keep the stale name of their slot in the original.
            val mineName = mine.name
            if (mineName != null) assertEquals(cString(original.u32(0x10)), mineName, "$at name")
        }

        fun compareText(at: String, f: Int) {
            val dump = data.region(names.getValue(f), "vram")
            val end = 0x1000 + text.activeFont.chrCounter * 2 * 0x20
            for (i in 0x1000 until end) {
                assertEquals(
                    dump[i].toInt(), vramHost.vram[i].toInt(), "$at text VRAM +0x${i.toString(16)}",
                )
            }
            assertTrue(end > 0x1000, "$at text drawn")
        }

        fun compareHandOam(at: String, f: Int) {
            // The OAM of a full dump holds the previous pass's objects, so it is checked against
            // the hand put by the pass before. The hand object is (oam0 = y + 2,
            // oam1 = 0x4000 | x, oam2 = 0).
            val before = handBefore
            assertTrue(before != null, "$at hand put by the previous pass")
            val oamBytes = data.region(names.getValue(f), "oam")
            val handShown = (0 until 128).any {
                u16(oamBytes, it * 8) == ((before!![1] + 2) and 0xFF) &&
                    u16(oamBytes, it * 8 + 2) == (0x4000 or (before[0] and 0x1FF)) &&
                    u16(oamBytes, it * 8 + 4) == 0
            }
            assertTrue(handShown, "$at hand sprite in OAM")
            handOamChecks++
        }

        var handOamChecks = 0

        fun procSlot(address: Int): Int =
            (address - ProcArrayDump.ARRAY_ADDRESS) / ProcArrayDump.PROC_SIZE

        fun cString(address: Int): String {
            val rom = romBytes
            var end = address - ROM_BASE
            val sb = StringBuilder()
            while (rom[end].toInt() != 0) sb.append(rom[end++].toInt().toChar())
            return sb.toString()
        }

        val SCRIPT_ADDRESS: Map<ProcScript, Int> = mapOf(
            menu.menuScript to MENU_SCRIPT,
            menu.menuMainScript to MENU_MAIN_SCRIPT,
            menu.menuItemScript to MENU_ITEM_SCRIPT,
            greenText.script to GREEN_TEXT_SCRIPT,
        )
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val ROM_BASE = 0x08000000
        const val BG0_BUFFER = 0x02022CA8
        const val BG1_BUFFER = 0x020234A8
        const val PALETTE_BUFFER = 0x020228A8
        const val KEY_STATUS = 0x02024CC0
        const val HAND_PREV = 0x0203DDE0
        const val DEFAULT_FONT = 0x02028E58
        const val BM_ST = 0x0202BCB0
        const val LCD_BUFFER = 0x03003080
        const val CLOCK = 0x03000014

        const val MENU_SCRIPT = 0x085B64D0
        const val MENU_MAIN_SCRIPT = 0x085B64B8
        const val MENU_ITEM_SCRIPT = 0x085B6510
        const val GREEN_TEXT_SCRIPT = 0x08588284

        const val EFFECT_WAIT = 0x08022739
        const val COMMAND_EFFECT_END_PLAYER_PHASE = 0x080225F9
        const val GUIDE_COMMAND_DRAW = 0x08022679
        const val GUIDE_ITEM_NUMBER = 2

        fun u16(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun u32(b: ByteArray, at: Int) = u16(b, at) or (u16(b, at + 2) shl 16)

        fun s16(v: Int) = v.toShort().toInt()
    }
}

private class VramHost : TextHost {
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
