package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ActiveUnit
import fe8.game.bm.AttackMapSelect
import fe8.game.bm.AttackRange
import fe8.game.bm.AttackTargets
import fe8.game.bm.BattleForecast
import fe8.game.bm.BattleGenerate
import fe8.game.bm.BattleStats
import fe8.game.bm.BattleUnit
import fe8.game.bm.BmBattle
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
import fe8.game.bm.UnitFacing
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.mechanics.Mechanics
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
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
 * The forecast window (MC-10, `BattleForecast`) runs for real: per frame the `gProcScr_BKSEL` proc
 * exists as in the dump (it starts at 1659 and ends at 1703 after B, at 1819 after A) at the same
 * script position with its fields (frame kind, ready, frame count, position, side, slide
 * counter, hit counts, effectiveness flags; the first dump after the start holds stale bytes in
 * the fields the proc has not set yet, so only the frame kind and `ready` are compared there),
 * and `gBattleActor`, `gBattleTarget`, `gBattleStats` and `gBmMapMovement` (filled with -1) are
 * the dump's until the cancel reopens the weapon list (the panel regenerates them). Not compared:
 * the BG tiles, palettes and text the forecast draws (MC-11, MC-12), which are stand-in hooks.
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
        "AttackMapSelect_SwitchIn", "AttackMapSelect_Select", "AttackMapSelect_Cancel",
        "AttackMapSelect_End", "ChangeActiveUnitFacing", "GetFacingDirection",
        "NewBattleForecast", "BattleForecast_Init",
        "InitBattleForecastBattleStats", "BattleForecastHitCountUpdate",
        "GetBattleForecastPanelSide", "CloseBattleForecast",
    )
    fun `the target selection follows the game from the pick to B and to A`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val cancelled = Sim(FIRST_START)
        for (f in FIRST_START..RESTART_FRAME) cancelled.frame(f)
        assertEquals(1, cancelled.cancels, "B cancelled once")
        assertEquals(0, cancelled.selects, "A did not select")
        assertEquals(listOf(0x6B), cancelled.sounds, "cancel sound")
        assertEquals(listOf(RESTART_FRAME), cancelled.restarts, "the weapon list reopens")
        assertEquals(1, cancelled.facingCalls, "facing set once")
        val confirmed = Sim(SECOND_START)
        for (f in SECOND_START..A_FRAME) confirmed.frame(f)
        assertEquals(1, confirmed.selects, "A selected once")
        assertEquals(0, confirmed.cancels, "B did not cancel")
        assertEquals(listOf(0x6A), confirmed.sounds, "select sound")
        assertEquals(emptyList<Int>(), confirmed.restarts, "no weapon list")
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
        val selects: Int get() = if (active.action.unitActionType == 2) 1 else 0
        var facingCalls = 0
        val restarts = ArrayList<Int>()
        var frameNow = 0
        val script = IntArray(0x40)
        val hw = BgHardware()
        val camMove = CamMove(engine, bm)
        val select: AttackMapSelect
        val battle: BmBattle
        val forecast: BattleForecast
        val sounds = ArrayList<Int>()
        var proc: Proc? = null

        /** The dump that holds the forecast's set-up frame half run (the second selection). */
        val partial = if (start == SECOND_START) start + 2 else -1

        init {
            val loader = UnitLoader(units, rom, { 0 }, RngService(), { false })
            val items = ItemInfo(rom)
            val range = AttackRange(map, units, items, EntityBmMaps(map, units, playSt), loader)
            attack = AttackTargets(map, units, items, range, ByteArray(TRAP_POOL_SIZE), targets)
            selection = TargetSelection(engine, targets, keys, camMove, {})
            val facing = UnitFacing(units, active, script)
            facing.setAutoMuMoveScript = { facingCalls++ }
            battle = BmBattle(rom, units, loader, playSt, bm) { y, x -> map.terrain[y, x] }
            val gen = BattleGenerate(battle, rom, loader, playSt, RngService(), Mechanics())
            forecast = BattleForecast(
                engine, battle, gen, items, units, active, playSt, bm, map, hw,
            )
            select = AttackMapSelect(
                engine, targets, active, units, facing, camMove, forecast, hw,
            )
            select.hooks.lockGame = { bm.lock = (bm.lock + 1) and 0xFF }
            select.hooks.unlockGame = { bm.lock = (bm.lock - 1) and 0xFF }
            select.hooks.restartWeaponList = {
                restarts.add(frameNow)
                // The weapon list (`WeaponSelectMenuDifferentialTest`) locks the game while open.
                bm.lock = (bm.lock + 1) and 0xFF
            }
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
            e.copyInto(
                battle.actor.bytes, 0, GBATTLE_ACTOR - EWRAM,
                GBATTLE_ACTOR - EWRAM + BattleUnit.SIZE,
            )
            e.copyInto(
                battle.target.bytes, 0, GBATTLE_TARGET - EWRAM,
                GBATTLE_TARGET - EWRAM + BattleUnit.SIZE,
            )
            battle.stats.decode(e, GBATTLE_STATS - EWRAM)
            readPool(e, iw, GBM_MAP_MOVEMENT, map.movement)
            val pointer = u32(iwram(start + 1), GACTIVEUNIT - IWRAM)
            active.unit = units.bytes[pointer - UnitTable.BLUE_ADDRESS + 0x0B].toInt() and 0xFF
            // The weapon list's end (the menu proc, MC-3b) unlocks the game as the pick returns.
            bm.lock = (bm.lock - 1) and 0xFF
            for (i in 0 until 0x40) script[i] = e[WORKING_SCRIPT - EWRAM + i].toInt() and 0xFF
            for (k in 0 until 0x400) hw.mapBuffer(2)[k] = half(e, BG2 - EWRAM + 2 * k).toShort()
            val a = ACTION_DATA - EWRAM
            active.action.subjectIndex = e[a + 0x0C].toInt() and 0xFF
            active.action.targetIndex = e[a + 0x0D].toInt() and 0xFF
            active.action.xMove = e[a + 0x0E].toInt() and 0xFF
            active.action.yMove = e[a + 0x0F].toInt() and 0xFF
            active.action.moveCount = e[a + 0x10].toInt() and 0xFF
            active.action.unitActionType = e[a + 0x11].toInt() and 0xFF
            active.action.itemSlotIndex = e[a + 0x12].toInt() and 0xFF
            cursor.lastCoordX = s16(e, SLAST_COORD - EWRAM)
            cursor.lastCoordY = s16(e, SLAST_COORD - EWRAM + 2)
            cursor.lastTimeDrawn = u32(e, SLAST_TIME - EWRAM)
        }

        fun frame(f: Int) {
            val d = f + 1
            frameNow = f
            keys.update(data.inputs[f])
            clock = u32(iwram(d), CLOCK - IWRAM)
            sprites.clear()
            // The forecast's set-up frame is heavy: the game takes two VBlanks for it, so the dump
            // 3 frames after the start repeats the one before (the first selection) or holds the
            // half-run frame (the second), and the game runs one frame behind the dump index
            // from there (the same frame the cursor's drawn time lags in).
            if (f != start + 2) for (tree in 0 until 8) engine.run(engine.root(tree))
            if (f == start) {
                // The weapon menu's proc starts it in tree 3 (the dump has the selection proc's
                // first run one frame later).
                attack.makeTargetListForWeapon(active.unit, units.item(active.unit, 0))
                proc = selection.newTargetSelection(select)
            }
            compare(d, f)
        }

        private fun compareForecast(d: Int, e: ByteArray, dump: ProcArrayDump, at: String) {
            val original = dump.find(BKSEL)
            val mine = forecast.find()
            assertEquals(original != null, mine != null, "$at forecast proc exists")
            if (original != null) {
                val s = mine!!.state as BattleForecast.State
                assertEquals(original.scrCur, mine!!.scrCur, "$at forecast script position")
                assertEquals(original.u8(0x32), s.frameKind, "$at frameKind")
                assertEquals(original.u8(0x33), s.ready, "$at ready")
                if (d != start + 1 && d != partial) {
                    assertEquals(original.u32(0x2C), s.frameCount, "$at frame count")
                    assertEquals(original.u8(0x30), s.x and 0xFF, "$at x")
                    assertEquals(original.u8(0x31), s.y and 0xFF, "$at y")
                    assertEquals(original.u8(0x34), s.needContentUpdate, "$at need update")
                    assertEquals(original.u8(0x35), s.side and 0xFF, "$at side")
    
                    assertEquals(original.u8(0x36), s.slideOffset and 0xFF, "$at slide")
                    assertEquals(original.u8(0x50), s.hitCountA and 0xFF, "$at hit count A")
                    assertEquals(original.u8(0x51), s.hitCountB and 0xFF, "$at hit count B")
                    assertEquals(original.u8(0x52), s.isEffectiveA, "$at effective A")
                    assertEquals(original.u8(0x53), s.isEffectiveB, "$at effective B")
                }
            }
            val limit = if (start == FIRST_START) B_FRAME + 1 else A_FRAME + 1
            if (d > start && d <= limit) compareBattle(d, e, at)
        }

        private fun compareBattle(d: Int, e: ByteArray, at: String) {
            for ((bu, address) in listOf(
                battle.actor to GBATTLE_ACTOR, battle.target to GBATTLE_TARGET,
            )) {
                for (i in 0 until BattleUnit.SIZE) {
                    assertEquals(
                        e[address - EWRAM + i], bu.bytes[i], "$at battle unit $address+$i",
                    )
                }
            }
            val stats = ByteArray(BattleStats.SIZE)
            battle.stats.encode(stats, 0)
            for (i in stats.indices) {
                assertEquals(e[GBATTLE_STATS - EWRAM + i], stats[i], "$at gBattleStats+$i")
            }
            val want = BmMap.Pool()
            map.bmMapInit(want, map.sizeX, map.sizeY)
            readPool(e, iwram(d), GBM_MAP_MOVEMENT, want)
            for (y in -2 until map.sizeY + 2) {
                for (x in 0 until map.sizeX + 2) {
                    assertEquals(want[y, x], map.movement[y, x], "$at movement[$y][$x]")
                }
            }
        }

        private fun compare(d: Int, f: Int) {
            val e = ewram(d)
            val at = "$RUN f$f (dump $d)"
            val dump = ProcArrayDump.ofEwram(e, procNames)
            compareForecast(d, e, dump, at)
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
            val cancelProc = dump.find(CANCEL_SCRIPT)
            if (cancelProc != null) cancels = 1
            val cancelMine = engine.find(select.cancelScript) != null
            assertEquals(cancelProc != null, cancelMine, "$at cancel proc")
            for (i in 0 until 0x40) {
                assertEquals(
                    e[WORKING_SCRIPT - EWRAM + i].toInt() and 0xFF, script[i],
                    "$at movement script $i",
                )
            }
            val a = ACTION_DATA - EWRAM
            assertEquals(e[a + 0x0D].toInt() and 0xFF, active.action.targetIndex, "$at target")
            assertEquals(e[a + 0x11].toInt() and 0xFF, active.action.unitActionType, "$at action")
            assertEquals(e[a + 0x12].toInt() and 0xFF, active.action.itemSlotIndex, "$at slot")
            assertEquals(e[a + 0x13].toInt() and 0xFF, active.action.xOther, "$at xOther")
            assertEquals(e[a + 0x14].toInt() and 0xFF, active.action.yOther, "$at yOther")
            for (k in 0 until 0x400) {
                assertEquals(
                    half(e, BG2 - EWRAM + 2 * k), hw.mapBuffer(2)[k].toInt() and 0xFFFF,
                    "$at BG2[$k]",
                )
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
        const val RESTART_FRAME = 1703
        const val BKSEL = 0x0859E520
        const val GBATTLE_STATS = 0x0203A4D4
        const val GBATTLE_ACTOR = 0x0203A4EC
        const val GBATTLE_TARGET = 0x0203A56C
        const val GBM_MAP_MOVEMENT = 0x0202E4E0
        const val CANCEL_SCRIPT = 0x0859B630
        const val WORKING_SCRIPT = 0x02033EFC
        const val ACTION_DATA = 0x0203A958
        const val BG2 = 0x02023CA8
        const val SECOND_START = 1789
        const val A_FRAME = 1819

        fun half(b: ByteArray, at: Int) =
            (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        fun s16(b: ByteArray, at: Int) = half(b, at).toShort().toInt()

        fun u32(b: ByteArray, at: Int) = half(b, at) or (half(b, at + 2) shl 16)
    }
}
