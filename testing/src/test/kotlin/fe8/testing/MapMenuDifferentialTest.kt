package fe8.testing

import fe8.game.scene.IntSupplier
import fe8.game.RomView
import fe8.game.bm.BattleMapKind
import fe8.game.bm.BattleMapNodes
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.MapMenu
import fe8.game.bm.MovementMap
import fe8.game.bm.PlaySt
import fe8.game.bm.PlayerPhase
import fe8.game.bm.UnitTable
import fe8.game.text.Fe8Text
import fe8.game.ui.GreenText
import fe8.game.ui.Menu
import fe8.game.ui.UiHand
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.flags.FlagStore
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
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
 * The map menu and its End command (src/bmmenu.c, src/menu_def.c `gMapMenuDef`) against
 * `ram_trace_prologue_cancel_end` f1465..f1511: A on the empty tile (5,5) of the idle player phase
 * (f1465) opens the map menu, UP wraps the cursor Unit to End (f1490), A on End (f1510) ends the
 * player phase procs.
 *
 * The real [fe8.game.bm.PlayerPhase] runs from the idle frame f1464 (seeded from its RAM: `gBmSt`,
 * `gPlaySt`, the units, the terrain and unit maps, the key status) and its A branch starts the map
 * menu through the real [Menu] with the real [MapMenu] callbacks; the game lock skips the player
 * phase tree while the menu holds it, as `OnMain` does. Stand-ins: the BG tiles outside the
 * menu's rectangle (`EndPlayerPhaseSideWindows`, taken from the original's f1465), `IsGuideLocked`
 * and `BmGuideTextShowGreenOrNormal` (bmguide.c, not ported: answered from the original's Guide
 * item proc and text colour), and `GetBattleMapKind` runs on the Prologue chapter id only (a story
 * chapter, no world map nodes).
 */
class MapMenuDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "MapMenu_IsGuideCommandAvailable", "MapMenu_IsSuspendCommandAvailable",
        "MapMenu_IsRecordsCommandAvailable", "MapMenu_IsRetreatCommandAvailable",
    )
    fun `map menu lists Unit Status Guide Options Suspend End like the game`() {
        assumeInputs()
        val sim = Sim()
        sim.seed()
        sim.runTo(FIRST)
        sim.compareItems()
        assertEquals(
            listOf(0 to 1, 1 to 1, 2 to 1, 4 to 1, 6 to 1, 7 to 1), sim.items(),
            "items (number to availability)",
        )
        // Every availability callback ran (Records and Retreat answer not shown in a story map).
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), sim.availabilityAsked.sorted())
    }

    @Test
    @VerifiesPort("MapMenu_GuideCommandDraw")
    fun `map menu tilemaps match the game at every frame and full dump`() {
        assumeInputs()
        val sim = Sim()
        sim.seed()
        sim.runTo(LAST_MENU_FRAME)
        assertEquals(listOf(1470, 1480, 1490, 1500), sim.fullDumps, "full dumps compared")
        assertEquals(36, sim.tilemapFrames, "frames compared")
    }

    @Test
    @VerifiesPort("CommandEffectEndPlayerPhase")
    fun `UP wraps to End and A on End ends the player phase like the game`() {
        assumeInputs()
        val sim = Sim()
        sim.seed()
        sim.runTo(LAST)
        assertEquals(listOf(MapMenu.COMMAND_EFFECT_END_PLAYER_PHASE), sim.selectCallbacks)
        assertEquals(0x17, sim.selectResult, "menu actions returned by the End effect")
        assertEquals(listOf(1490 to 5), sim.wraps, "cursor wrap: frame to item")
        sim.compareAfterEnd()
    }

    private fun assumeInputs() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
    }

    private inner class Sim {
        val data = ref.run("ram_trace_prologue_cancel_end")
        val names = data.dumps.entries.associate { it.value to it.key }
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val engine = ProcEngine()
        val hw = BgHardware()
        val host = MapMenuVram()
        val text = TextSystem(romBytes, host, Fe8Text.CONFIG)
        val keys = KeyStatus()
        val sprites = SpriteLayers()
        var clock = 0
        var lock = 0
        val hand = UiHand(rom, sprites)
        val greenText = GreenText(engine, rom, hw) { clock }
        val playSt = PlaySt()
        val bm = BmSt()
        val units = UnitTable(rom)
        val map = BmMap(rom, ChapterData(rom), bm)
        val camMove = CamMove(engine, bm)
        val phase: PlayerPhase
        val menu: Menu
        val mapMenu: MapMenu
        val availabilityAsked = ArrayList<Int>()
        val selectCallbacks = ArrayList<Int>()
        val wraps = ArrayList<Pair<Int, Int>>()
        var selectResult = -1
        var frame = FIRST - 1
        var next = FIRST - 1
        val fullDumps = ArrayList<Int>()
        var tilemapFrames = 0
        lateinit var menuProc: Menu.MenuProc
        var started = false

        init {
            val root = u32(romBytes, 0x0815D488 - ROM_BASE) - ROM_BASE
            val messages = MsgStrings(
                romBytes, MsgHuffman(romBytes, 0x0815A72C - ROM_BASE, root), 0x0815D48C - ROM_BASE,
            )
            phase = PlayerPhase(
                engine, bm, playSt, map, units, camMove, EntityBmMaps(map, units, playSt),
                BmMapRender(bm, map, hw), keys, { false },
                movementMap = MovementMap(rom, map, units, playSt),
            )
            menu = Menu(engine, rom, hw, text, messages, keys, hand, greenText, camMove.script)
            menu.hooks.lockGame = { lock++ }
            menu.hooks.unlockGame = { lock-- }
            menu.hooks.gameClock = IntSupplier { clock }
            val flags = object : FlagStore {
                override fun checkFlag(id: Int) = error("no flag is read in a story map")
                override fun setFlag(id: Int) = error("not used")
                override fun clearFlag(id: Int) = error("not used")
            }
            mapMenu = MapMenu(engine, rom, playSt, flags, hw, text, messages) { phase.script }
        }

        fun ewram(f: Int): ByteArray = data.region(names.getValue(f), "ewram")

        fun iwram(f: Int): ByteArray = data.region(names.getValue(f), "iwram")

        fun seed() {
            val e = ewram(FIRST - 1)
            playSt.decode(e, PLAY_ST - EWRAM)
            bm.decode(e, BM_ST - EWRAM)
            map.initChapterMap(playSt.chapterIndex)
            units.loadFrom(e)
            readPool(e, iwram(FIRST - 1), GBM_MAP_TERRAIN, map.terrain)
            readPool(e, iwram(FIRST - 1), GBM_MAP_UNIT, map.unit)
            for (bg in 0..1) {
                val at = (if (bg == 0) BG0_BUFFER else BG1_BUFFER) - EWRAM
                for (i in 0 until 0x400) hw.mapBuffer(bg)[i] = u16(e, at + 2 * i).toShort()
            }
            for (i in 0 until 0x200) {
                hw.palette[i] = u16(e, PALETTE_BUFFER - EWRAM + 2 * i).toShort()
            }
            hw.lcd.readFrom(iwram(FIRST - 1), LCD_BUFFER - IWRAM)
            keys.readFrom(e, KEY_STATUS - EWRAM)
            hand.prevX = u16(e, HAND_PREV - EWRAM).toShort().toInt()
            hand.prevY = u16(e, HAND_PREV - EWRAM + 2).toShort().toInt()
            hand.prevClockFrame = u32(e, HAND_PREV - EWRAM + 4)
            System.arraycopy(
                data.region(names.getValue((FIRST - 1) / 10 * 10), "vram"), 0, host.vram, 0,
                0x18000,
            )
            text.initTextFont(null, 0x06001000, 0x80, 0)
            text.activeFont.chrCounter = u16(e, DEFAULT_FONT - EWRAM + 0x12)

            // Stand-ins from the original's first menu frame.
            val dump = ProcArrayDump.ofEwram(ewram(FIRST))
            val original = dump.find(MENU_SCRIPT)!!
            val shown = HashMap<Int, ProcArrayDump.Proc>()
            for (i in 0 until original.u8(0x60)) {
                val item = dump.Proc(procSlot(original.u32(0x34 + 4 * i)))
                shown[item.u8(0x3C)] = item
            }
            mapMenu.hooks.battleMapKind = {
                BattleMapKind.getBattleMapKind(playSt.chapterIndex, NoNodes)
            }
            mapMenu.hooks.isGuideLocked = { !shown.containsKey(GUIDE_ITEM_NUMBER) }
            mapMenu.hooks.guideTextShowGreenOrNormal = {
                shown.getValue(GUIDE_ITEM_NUMBER).u8(0x34 + 3) != 4
            }
            mapMenu.install(menu, rom)
            for (address in listOf(
                MapMenu.GUIDE_USABILITY, MapMenu.RECORDS_USABILITY, MapMenu.RETREAT_USABILITY,
                MapMenu.SUSPEND_USABILITY,
            )) {
                val real = menu.callbacks.getValue(address)
                menu.callbacks[address] = Menu.MenuFunc { m, i, d, n ->
                    availabilityAsked.add(n)
                    real.invoke(m, i, d, n)
                }
            }
            val end = menu.callbacks.getValue(MapMenu.COMMAND_EFFECT_END_PLAYER_PHASE)
            menu.callbacks[MapMenu.COMMAND_EFFECT_END_PLAYER_PHASE] = Menu.MenuFunc { m, i, d, n ->
                selectCallbacks.add(MapMenu.COMMAND_EFFECT_END_PLAYER_PHASE)
                end.invoke(m, i, d, n).also { selectResult = it }
            }
            // Unit and Status have no availability callback of their own (MenuAlwaysEnabled).
            availabilityAsked.addAll(listOf(0, 1, 4, 7))

            val h = phase.hooks
            h.unitSelectEnabled = true
            h.endSideWindows = ProcFunc { applySideWindowEnd() }
            h.startMapMenu = { x ->
                val proc = menu.startOrphanMenuAdjusted(Menu.MAP_MENU_DEF, x, 1, 0x17)
                menuProc = proc.state as Menu.MenuProc
                started = true
            }
            val proc = engine.start(phase.script, 2)
            engine.goto(proc, 9)
            engine.run(engine.root(2))
        }

        /** `EndPlayerPhaseSideWindows` clears the side windows: every tile outside the menu. */
        fun applySideWindowEnd() {
            val e = ewram(FIRST)
            val m = ProcArrayDump.ofEwram(e).find(MENU_SCRIPT)!!
            val x0 = m.u8(0x2C)
            val y0 = m.u8(0x2D)
            val x1 = x0 + m.u8(0x2E)
            val y1 = y0 + m.u8(0x2F)
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

        fun readPool(e: ByteArray, iw: ByteArray, handle: Int, pool: BmMap.Pool) {
            fun word(a: Int): Int =
                if (a >= IWRAM) u32(iw, a - IWRAM) else u32(e, a - EWRAM)
            fun byte(a: Int): Int = e[a - EWRAM].toInt() and 0xFF
            val first = word(word(handle) - 8)
            for (y in -2 until map.sizeY + 2) {
                for (x in 0 until map.sizeX + 2) {
                    pool[y, x] = byte(first + (y + 2) * (map.sizeX + 2) + x)
                }
            }
        }

        /** Runs the frames up to and including [last]; compares each frame as it goes. */
        fun runTo(last: Int) {
            while (next < last) {
                next++
                frame = next
                val f = frame
                keys.update(data.inputs[f - 1])
                clock = u32(iwram(f), CLOCK - IWRAM)
                sprites.clear()
                engine.run(engine.root(1))
                if (lock == 0) engine.run(engine.root(2))
                engine.run(engine.root(3))
                if (f in FIRST..LAST_MENU_FRAME) compareTilemaps(f)
                if (f in FIRST..LAST_MENU_FRAME && started) {
                    val before = wrapBefore
                    val now = menuProc.itemCurrent
                    if (before == 0 && now == menuProc.itemCount - 1) {
                        wraps.add(f to now)
                    }
                    wrapBefore = now
                }
            }
        }

        var wrapBefore: Int? = null

        fun items(): List<Pair<Int, Int>> =
            (0 until menuProc.itemCount).map {
                menuProc.menuItems[it]!!.itemNumber to menuProc.menuItems[it]!!.availability
            }

        fun compareItems() {
            val dump = ProcArrayDump.ofEwram(ewram(FIRST))
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
            assertEquals(original.u8(0x61), menuProc.itemCurrent, "itemCurrent")
            assertEquals(
                dump.findAll(MENU_ITEM_SCRIPT).size, engine.count(menu.menuItemScript),
                "item procs",
            )
        }

        fun compareTilemaps(f: Int) {
            val e = ewram(f)
            for (bg in 0..1) {
                val base = (if (bg == 0) BG0_BUFFER else BG1_BUFFER) - EWRAM
                for (i in 0 until 0x400) {
                    assertEquals(
                        u16(e, base + 2 * i), hw.mapBuffer(bg)[i].toInt() and 0xFFFF,
                        "f$f BG$bg tilemap[$i]",
                    )
                }
            }
            val dump = ProcArrayDump.ofEwram(e)
            val original = dump.find(MENU_SCRIPT)
            if (original != null) {
                assertEquals(original.u8(0x61), menuProc.itemCurrent, "f$f itemCurrent")
                assertEquals(original.u8(0x62), menuProc.itemPrevious, "f$f itemPrevious")
            }
            if (names.getValue(f).startsWith("f") && f <= LAST_MENU_FRAME) {
                fullDumps.add(f)
                val vram = data.region(names.getValue(f), "vram")
                val end = 0x1000 + text.activeFont.chrCounter * 2 * 0x20
                for (i in 0x1000 until end) {
                    assertEquals(vram[i].toInt(), host.vram[i].toInt(), "f$f text VRAM +0x$i")
                }
            }
            tilemapFrames++
        }

        /** The state after the End effect (f1510 ran) against the original's f1510 and f1511. */
        fun compareAfterEnd() {
            for (f in listOf(LAST, LAST + 1)) {
                val dump = ProcArrayDump.ofEwram(ewram(f))
                assertEquals(null, dump.find(PLAYER_PHASE_SCRIPT), "f$f PlayerPhase proc ended")
            }
            assertEquals(null, engine.find(phase.script), "mine PlayerPhase proc ended")
            assertEquals(null, engine.find(menu.menuScript), "mine menu proc ended")
            assertEquals(0, lock, "game lock after the menu ended")
            for (f in listOf(LAST, LAST + 1)) {
                val e = ewram(f)
                val mine = ByteArray(BmSt.SIZE).also { bm.encode(it, 0) }
                for (k in listOf(4) + (0x0A until BmSt.SIZE)) {
                    assertEquals(e[BM_ST - EWRAM + k], mine[k], "f$f gBmSt+0x${k.toString(16)}")
                }
                val ps = ByteArray(PlaySt.SIZE).also { playSt.encode(it, 0) }
                for (k in 0 until PlaySt.SIZE) {
                    if (k in 0x00..0x05) continue // clock and play time advance
                    assertEquals(e[PLAY_ST - EWRAM + k], ps[k], "f$f gPlaySt+0x${k.toString(16)}")
                }
            }
        }

        fun procSlot(address: Int): Int =
            (address - ProcArrayDump.ARRAY_ADDRESS) / ProcArrayDump.PROC_SIZE
    }

    private object NoNodes : BattleMapNodes {
        override fun chapterId(node: Int): Int = error("a story chapter reads no node")
        override fun isCleared(node: Int): Boolean = error("a story chapter reads no node")
        override fun encounters(node: Int): Int = error("a story chapter reads no node")
        override fun placementFlag(node: Int): Int = error("a story chapter reads no node")
    }

    private companion object {
        const val FIRST = 1465
        const val LAST_MENU_FRAME = 1500
        const val LAST = 1510
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
        const val PLAY_ST = 0x0202BCF0
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val LCD_BUFFER = 0x03003080
        const val CLOCK = 0x03000014
        const val MENU_SCRIPT = 0x085B64D0
        const val MENU_ITEM_SCRIPT = 0x085B6510
        const val PLAYER_PHASE_SCRIPT = 0x0859AAD8
        const val GUIDE_ITEM_NUMBER = 2

        fun u16(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun u32(b: ByteArray, at: Int) = u16(b, at) or (u16(b, at + 2) shl 16)
    }
}

private class MapMenuVram : TextHost {
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
