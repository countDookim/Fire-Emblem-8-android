package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ActiveUnit
import fe8.game.bm.AttackRange
import fe8.game.bm.AttackTargets
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.ItemInfo
import fe8.game.bm.MapCursor
import fe8.game.bm.PlaySt
import fe8.game.bm.SelectTargets
import fe8.game.bm.TargetSelection
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.render.SpriteLayers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The attack target selection (MC-5, src/uiselecttarget.c) against
 * `ram_trace_prologue_attack_noanim`.
 *
 * Two selections run: the one A on the weapon starts at game frame 1659 and B cancels at 1699, and
 * the one started at 1789 that A confirms at 1819 (the game's frame k is the dump k + 1, inputs
 * `inputs[k]`; the weapon list's A and the unit and weapon lists are MC-3). Each is seeded from the
 * dump of its start frame (the units are those after the weapon was equipped, which the pick does,
 * MC-3b; the menu's end unlocks the game once, done by the test), the target list is made by the
 * ported `MakeTargetListForWeapon`, `NewTargetSelection` runs at the start frame and the proc
 * trees run once per dump. After every frame the proc exists as in the dump with its flags, the
 * current target is the dump's, the cursor and camera fields of `gBmSt`, the map cursor's
 * statics (`sLastCoordMapCursorDrawn`, `sLastTimeMapCursorDrawn`) and the game lock are the
 * dump's, and the target list (the dump's links after `GetLinkedTargets`) is the same.
 *
 * The callbacks of `gSelectInfo_Attack` are MC-6 and MC-10: the test's stand-ins return their
 * `MENU_ACT` results and the cancel one starts the cancel proc's `LockGame` (dumps f1700+ lock
 * the game again).
 */
class TargetSelectionDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "NewTargetSelection", "TargetSelection_Loop", "TargetSelection_HandleSelectInput",
        "TargetSelection_GetRealCursorPosition", "EndTargetSelection",
    )
    fun `the target selection follows the game from the pick to B and to A`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val cancelled = Sim(FIRST_START)
        for (f in FIRST_START..B_FRAME) cancelled.frame(f)
        assertEquals(1, cancelled.cancels, "B cancelled once")
        assertEquals(0, cancelled.selects, "A did not select")
        assertEquals(listOf(0x6B), cancelled.sounds, "cancel sound")
        val confirmed = Sim(SECOND_START)
        for (f in SECOND_START..A_FRAME) confirmed.frame(f)
        assertEquals(1, confirmed.selects, "A selected once")
        assertEquals(0, confirmed.cancels, "B did not cancel")
        assertEquals(listOf(0x6A), confirmed.sounds, "select sound")
        assertEquals(1, confirmed.ends, "the selection ended once")
    }

    private inner class Sim(val start: Int) {
        val data = ref.run(RUN)
        val names = data.dumps.entries.associate { it.value to it.key }
        val rom = RomView.ofRom(romFile.readBytes())
        val engine = ProcEngine()
        val keys = KeyStatus()
        val sprites = SpriteLayers()
        val bm = BmSt()
        val playSt = PlaySt()
        val units = UnitTable(rom)
        val active = ActiveUnit()
        val map = BmMap(rom, ChapterData(rom), bm)
        val cursor = MapCursor(rom, bm, sprites)
        val selection: TargetSelection
        val targets = SelectTargets()
        val attack: AttackTargets
        val procNames = ProcArrayDump.romNames()
        var clock = 0
        var cancels = 0
        var selects = 0
        var ends = 0
        val sounds = ArrayList<Int>()
        var proc: Proc? = null

        init {
            val loader = UnitLoader(units, rom, { 0 }, RngService(), { false })
            val items = ItemInfo(rom)
            val range = AttackRange(map, units, items, EntityBmMaps(map, units, playSt), loader)
            attack = AttackTargets(map, units, items, range, ByteArray(TRAP_POOL_SIZE), targets)
            selection = TargetSelection(
                engine, targets, keys, CamMove(engine, bm), {},
            )
            selection.hooks.lockGame = { bm.lock = (bm.lock + 1) and 0xFF }
            selection.hooks.unlockGame = { bm.lock = (bm.lock - 1) and 0xFF }
            selection.hooks.playSoundEffect = { sounds.add(it) }
            selection.hooks.putMapCursor = fe8.game.bm.PlayerPhase.PutMapCursor { x, y, kind ->
                cursor.put(x, y, kind)
            }
            cursor.gameClock = { clock }
            seed()
        }

        fun ewram(frame: Int): ByteArray = data.region(names.getValue(frame), "ewram")

        fun iwram(frame: Int): ByteArray = data.region(names.getValue(frame), "iwram")

        /** The state before the start frame; the units are the ones the pick leaves. */
        private fun seed() {
            val e = ewram(start)
            val iw = iwram(start)
            val after = ewram(start + 1)
            units.loadFrom(after)
            playSt.decode(e, PLAY_ST - EWRAM)
            bm.decode(e, BM_ST - EWRAM)
            map.initChapterMap(playSt.chapterIndex)
            readPool(e, iw, GBM_MAP_TERRAIN, map.terrain)
            readPool(after, iwram(start + 1), GBM_MAP_UNIT, map.unit)
            val pointer = u32(iwram(start + 1), GACTIVEUNIT - IWRAM)
            active.unit = units.bytes[pointer - UnitTable.BLUE_ADDRESS + 0x0B].toInt() and 0xFF
            // The weapon list's end (the menu proc, MC-3b) unlocks the game as the pick returns.
            bm.lock = (bm.lock - 1) and 0xFF
            cursor.lastCoordX = s16(e, SLAST_COORD - EWRAM)
            cursor.lastCoordY = s16(e, SLAST_COORD - EWRAM + 2)
            cursor.lastTimeDrawn = u32(e, SLAST_TIME - EWRAM)
        }

        fun frame(f: Int) {
            val d = f + 1
            keys.update(data.inputs[f])
            clock = u32(iwram(d), CLOCK - IWRAM)
            sprites.clear()
            for (tree in 0 until 8) engine.run(engine.root(tree))
            if (f == start) {
                // The weapon menu's proc starts it in tree 3 (the dump has the selection proc's
                // first run one frame later).
                attack.makeTargetListForWeapon(active.unit, units.item(active.unit, 0))
                proc = selection.newTargetSelection(info)
            }
            compare(d, f)
        }

        private val info = object : TargetSelection.SelectInfo {
            override fun onEnd(proc: Proc) {
                ends++
            }

            override fun onSelect(proc: Proc, target: Int): Int {
                selects++
                return TargetSelection.ACTION_ENDFAST or TargetSelection.ACTION_END or
                    TargetSelection.ACTION_SE_6A or TargetSelection.ACTION_CLEARBGS
            }

            override fun onCancel(proc: Proc, target: Int): Int {
                cancels++
                // `AttackMapSelect_Cancel` starts `gProcScr_0859B630` (MC-6); its first command
                // is `LockGame`.
                bm.lock = (bm.lock + 1) and 0xFF
                return TargetSelection.ACTION_ENDFAST or TargetSelection.ACTION_END or
                    TargetSelection.ACTION_SE_6B
            }
        }

        private fun compare(d: Int, f: Int) {
            val e = ewram(d)
            val at = "$RUN f$f (dump $d)"
            val dump = ProcArrayDump.ofEwram(e, procNames)
            val original = dump.find("gProcScr_TargetSelection")
            val mine = engine.find(selection.script)
            assertEquals(original != null, mine != null, "$at selection proc exists")
            if (original != null) {
                val state = mine!!.state as TargetSelection.State
                assertEquals(original.u8(0x34), state.flags, "$at flags")
                assertEquals(
                    LIST + 12 * state.currentTarget, original.u32(0x30), "$at current target",
                )
                assertEquals(0, original.u32(0x38), "$at onAPress")
            }
            val want = BmSt().apply { decode(e, BM_ST - EWRAM) }
            assertEquals(want.lock, bm.lock, "$at lock")
            assertEquals(want.cameraX, bm.cameraX, "$at cameraX")
            assertEquals(want.cameraY, bm.cameraY, "$at cameraY")
            assertEquals(want.playerCursorX, bm.playerCursorX, "$at cursor x")
            assertEquals(want.playerCursorY, bm.playerCursorY, "$at cursor y")
            assertEquals(want.cursorTargetX, bm.cursorTargetX, "$at cursor target x")
            assertEquals(want.cursorTargetY, bm.cursorTargetY, "$at cursor target y")
            assertEquals(want.cursorPreviousX, bm.cursorPreviousX, "$at cursor previous x")
            assertEquals(want.cursorPreviousY, bm.cursorPreviousY, "$at cursor previous y")
            assertEquals(s16(e, SLAST_COORD - EWRAM), cursor.lastCoordX, "$at drawn x")
            assertEquals(s16(e, SLAST_COORD - EWRAM + 2), cursor.lastCoordY, "$at drawn y")
            // The dump 3 frames after each start (f1662, f1792) is taken before the cursor is
            // put (like the f1631 one, a frame that outruns a VBlank); the clock has ticked and
            // the time is the old one.
            if (d != start + MID_FRAME_DUMP) {
                assertEquals(u32(e, SLAST_TIME - EWRAM), cursor.lastTimeDrawn, "$at drawn time")
            }
            assertEquals(u32(e, COUNT - EWRAM), targets.getSelectTargetCount(), "$at count")
            for (i in 0 until targets.getSelectTargetCount()) {
                val o = LIST - EWRAM + 12 * i
                assertEquals(e[o].toInt(), targets.targetX(i), "$at target $i x")
                assertEquals(e[o + 1].toInt(), targets.targetY(i), "$at target $i y")
                assertEquals(link(u32(e, o + 4)), targets.targetNext(i), "$at target $i next")
                assertEquals(link(u32(e, o + 8)), targets.targetPrev(i), "$at target $i prev")
            }
        }

        private fun link(pointer: Int): Int =
            if (pointer == 0) SelectTargets.NONE else (pointer - LIST) / 12

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
    }

    private companion object {
        const val RUN = "ram_trace_prologue_attack_noanim"
        const val EWRAM = 0x02000000
        const val IWRAM = 0x03000000
        const val CLOCK = 0x03000014
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val GACTIVEUNIT = 0x03004E50
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val SLAST_COORD = 0x0202BD3C
        const val SLAST_TIME = 0x0202BD40
        const val LIST = 0x0203DDEC
        const val COUNT = 0x0203E0EC
        const val TRAP_POOL_SIZE = 0x200
        const val MID_FRAME_DUMP = 3
        const val FIRST_START = 1659
        const val B_FRAME = 1699
        const val SECOND_START = 1789
        const val A_FRAME = 1819

        fun half(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun s16(b: ByteArray, at: Int) = half(b, at).toShort().toInt()

        fun u32(b: ByteArray, at: Int) = half(b, at) or (half(b, at + 2) shl 16)
    }
}
