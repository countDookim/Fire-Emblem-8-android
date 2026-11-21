package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ActionData
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.MapTraps
import fe8.game.bm.PlaySt
import fe8.game.bm.SelectTargets
import fe8.game.bm.UnitTable
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.end
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Phase-end traps of `src/bmtrick.c` (EP-10a) against EWRAM dumps and watch traces:
 * - Verification of `sTrapPool` (all 64 slots empty) and `sSelectTargetCount` (0) per step
 *   `f2766..2767` in `ram_trace_prologue_enemy_phase` and `f3875..3876` in
 *   `ram_trace_prologue_clear`.
 * - Port verification of [MapTraps.sub802EA00] (temporary red faction switch and entity map
 *   refresh) and [MapTraps.postTrapExecFlag] (game over check and defeat-all flag setting).
 * - Full execution of [MapTraps.updateTrapsScript] (`gProcScr_UpdateTraps`).
 * - Transcribed logic for countdowns, damage targets, counter resets, and trap decay.
 */
class TrapsDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    fun `trap pool and select targets are empty at f2766-2767 and f3875-3876 in captures`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val emptyTrapPool = ByteArray(MapTraps.POOL_SIZE)

        // Check ram_trace_prologue_enemy_phase at f2765..f2768
        val runEnemy = ref.run("ram_trace_prologue_enemy_phase")
        for (dumpName in listOf("r02765", "r02766", "r02767", "r02768")) {
            val ewram = runEnemy.region(dumpName, "ewram")
            val trapSlice = ewram.copyOfRange(
                TRAP_POOL_ADDR - EWRAM_BASE,
                TRAP_POOL_ADDR - EWRAM_BASE + MapTraps.POOL_SIZE,
            )
            assertArrayEquals(emptyTrapPool, trapSlice, "sTrapPool at $dumpName")
            val targetCount = ewram.u32(TARGET_COUNT_ADDR - EWRAM_BASE)
            assertEquals(0, targetCount, "sSelectTargetCount at $dumpName")
        }

        // Check ram_trace_prologue_clear at f3874..f3877
        val runClear = ref.run("ram_trace_prologue_clear")
        for (dumpName in listOf("r03874", "r03875", "r03876", "r03877")) {
            val ewram = runClear.region(dumpName, "ewram")
            val trapSlice = ewram.copyOfRange(
                TRAP_POOL_ADDR - EWRAM_BASE,
                TRAP_POOL_ADDR - EWRAM_BASE + MapTraps.POOL_SIZE,
            )
            assertArrayEquals(emptyTrapPool, trapSlice, "sTrapPool at $dumpName")
            val targetCount = ewram.u32(TARGET_COUNT_ADDR - EWRAM_BASE)
            assertEquals(0, targetCount, "sSelectTargetCount at $dumpName")
        }
    }

    @Test
    fun `UpdateTraps proc lifetime in procs watch matches the two phase-end windows`() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")

        val runEnemy = ref.run("ram_trace_prologue_enemy_phase")
        val procsEnemy = runEnemy.watch("procs")
        fun enemyAlive(step: Int) =
            ProcArrayDump.ofWatch(procsEnemy, step, names).live.any {
                it.name == "gProcScr_UpdateTraps"
            }
        assertTrue(enemyAlive(2766), "UpdateTraps alive at f2766 (enemy phase)")
        assertFalse(enemyAlive(2765), "UpdateTraps not started at f2765 (enemy phase)")
        assertFalse(enemyAlive(2767), "UpdateTraps finished at f2767 (enemy phase)")

        val runClear = ref.run("ram_trace_prologue_clear")
        val procsClear = runClear.watch("procs")
        fun clearAlive(step: Int) =
            ProcArrayDump.ofWatch(procsClear, step, names).live.any {
                it.name == "gProcScr_UpdateTraps"
            }
        assertTrue(clearAlive(3875), "UpdateTraps alive at f3875 (clear run)")
        assertFalse(clearAlive(3874), "UpdateTraps not started at f3874 (clear run)")
        assertFalse(clearAlive(3876), "UpdateTraps finished at f3876 (clear run)")
    }

    @Test
    @VerifiesPort("sub_802EA00")
    fun `sub_802EA00 sets faction to RED during refresh and restores it`() {
        val sim = Sim("ram_trace_prologue_clear")
        sim.seedPlaySt(3875)
        assertEquals(0x40, sim.playSt.faction, "faction before sub_802EA00")

        var factionDuringRefresh = -1
        var refreshCount = 0
        sim.traps.hooks.refreshEntityBmMaps = {
            factionDuringRefresh = sim.playSt.faction
            refreshCount++
        }

        sim.traps.sub802EA00()

        assertEquals(1, refreshCount, "refreshEntityBmMaps call count")
        assertEquals(0x80, factionDuringRefresh, "faction during refreshEntityBmMaps")
        assertEquals(0x40, sim.playSt.faction, "faction restored after sub_802EA00")
    }

    @Test
    @VerifiesPort("PostTrapExecFlag")
    fun `PostTrapExecFlag checks game over and defeat all against capture state`() {
        val sim = Sim("ram_trace_prologue_clear")
        sim.seedUnits(3876)
        sim.seedPlaySt(3876)

        var gameOverCalled = false
        sim.traps.hooks.callGameOverEvent = { gameOverCalled = true }

        // Seth and Eirika are alive, enemies are alive (O'Neill has 23 HP)
        sim.flags.clearFlag(MapTraps.EVFLAG_GAMEOVER)
        sim.flags.clearFlag(MapTraps.EVFLAG_DEFEAT_ALL)

        sim.traps.postTrapExecFlag()

        assertFalse(gameOverCalled, "gameOver must not be called when units available")
        assertFalse(
            sim.flags.checkFlag(MapTraps.EVFLAG_DEFEAT_ALL),
            "EVFLAG_DEFEAT_ALL must not be set when enemies remain alive",
        )

        // Verify defeat-all branch when all enemies are defeated
        for (id in 0x81 until 0xC0) {
            if (sim.units.isValid(id)) {
                sim.units.setState(id, sim.units.state(id) or US_DEAD)
            }
        }
        assertFalse(sim.units.areAnyEnemyUnitAlive(), "no living enemy units")

        sim.traps.postTrapExecFlag()
        assertTrue(
            sim.flags.checkFlag(MapTraps.EVFLAG_DEFEAT_ALL),
            "EVFLAG_DEFEAT_ALL must be set when no living enemy units remain",
        )

        // Verify game over branch when EVFLAG_GAMEOVER is set
        sim.flags.setFlag(MapTraps.EVFLAG_GAMEOVER)
        sim.traps.postTrapExecFlag()
        assertTrue(gameOverCalled, "gameOver must be called when EVFLAG_GAMEOVER is set")
    }

    @Test
    @VerifiesPort("TrapDamageDisplay_Init", "TrapDamageDisplay_Check")
    fun `TrapDamageDisplay spawns, yields on sleep 0, and ends when target count is 0 at f2766 and f3875`() {
        // ram_trace_prologue_enemy_phase at f2766
        val enemySim = Sim("ram_trace_prologue_enemy_phase")
        enemySim.seedUnits(2766)
        enemySim.seedPlaySt(2766)
        assertEquals(0, enemySim.targets.getSelectTargetCount())

        val enemyProc = enemySim.engine.start(enemySim.traps.trapDamageDisplayScript, 6)
        assertNotNull(enemyProc, "enemyProc started")
        val enemyState = requireNotNull(enemyProc?.state as? MapTraps.TrapDamageDisplayState)
        assertEquals(0, enemyState.unk4C, "unk4C initialized to 0")
        assertEquals(1, enemySim.engine.count(enemySim.traps.trapDamageDisplayScript), "alive at sleep 0")

        enemySim.action.subjectIndex = 0x55
        enemySim.engine.run(enemySim.engine.root(6))
        assertEquals(0, enemySim.action.subjectIndex, "subjectIndex updated to target uid (0)")
        assertEquals(0, enemySim.engine.count(enemySim.traps.trapDamageDisplayScript), "proc ended by check")

        // ram_trace_prologue_clear at f3875
        val clearSim = Sim("ram_trace_prologue_clear")
        clearSim.seedUnits(3875)
        clearSim.seedPlaySt(3875)
        assertEquals(0, clearSim.targets.getSelectTargetCount())

        val clearProc = clearSim.engine.start(clearSim.traps.trapDamageDisplayScript, 6)
        assertNotNull(clearProc, "clearProc started")
        val clearState = requireNotNull(clearProc?.state as? MapTraps.TrapDamageDisplayState)
        assertEquals(0, clearState.unk4C, "unk4C initialized to 0")
        assertEquals(1, clearSim.engine.count(clearSim.traps.trapDamageDisplayScript), "alive at sleep 0")

        clearSim.action.subjectIndex = 0x77
        clearSim.engine.run(clearSim.engine.root(6))
        assertEquals(0, clearSim.action.subjectIndex, "subjectIndex updated to target uid (0)")
        assertEquals(0, clearSim.engine.count(clearSim.traps.trapDamageDisplayScript), "proc ended by check")
    }

    @Test
    fun `updateTrapsScript executes to completion on empty trap pool`() {
        val sim = Sim("ram_trace_prologue_clear")
        sim.seedUnits(3875)
        sim.seedPlaySt(3875)

        var refreshes = 0
        sim.traps.hooks.refreshEntityBmMaps = { refreshes++ }
        var gameOverCalled = false
        sim.traps.hooks.callGameOverEvent = { gameOverCalled = true }

        val parentScript = ProcScript("parent", listOf(block(), end()))
        val parent = sim.engine.start(parentScript, 6)
        val trapProc = sim.engine.startBlocking(sim.traps.updateTrapsScript, parent)

        // Run engine cycles until the child display proc and update script finish
        while (sim.engine.find(sim.traps.updateTrapsScript) != null) {
            sim.engine.run(sim.engine.root(6))
        }

        // On an empty trap pool, decayTraps leaves the pool empty
        sim.traps.decayTraps()

        assertEquals(0, sim.targets.getSelectTargetCount(), "target count")
        assertEquals(2, refreshes, "sub_802EA00 and script cmd 7 each refresh entity maps")
        assertFalse(gameOverCalled, "gameOver not called")
        assertFalse(sim.flags.checkFlag(MapTraps.EVFLAG_DEFEAT_ALL), "defeat-all not set")
    }

    @Test
    fun `CountDownTraps decrements counter only for damaging traps`() {
        val sim = Sim("ram_trace_prologue_clear")
        val fire = sim.traps.addDamagingTrap(1, 2, MapTraps.TRAP_FIRETILE, 0, 3, 2, 10)
        val gas = sim.traps.addDamagingTrap(3, 4, MapTraps.TRAP_GAS, 1, 2, 2, 3)
        val arrow = sim.traps.addDamagingTrap(5, 0, MapTraps.TRAP_LIGHTARROW, 0, 1, 2, 10)
        val mapCh = sim.traps.addDamagingTrap(6, 7, MapTraps.TRAP_MAPCHANGE2, 0, 4, 2, 0)
        val torch = sim.traps.addTrap(8, 9, MapTraps.TRAP_TORCHLIGHT, 5)

        sim.traps.countDownTraps()

        assertEquals(2, sim.traps.data(fire, MapTraps.TRAP_EXTDATA_TRAP_COUNTER))
        assertEquals(1, sim.traps.data(gas, MapTraps.TRAP_EXTDATA_TRAP_COUNTER))
        assertEquals(0, sim.traps.data(arrow, MapTraps.TRAP_EXTDATA_TRAP_COUNTER))
        assertEquals(3, sim.traps.data(mapCh, MapTraps.TRAP_EXTDATA_TRAP_COUNTER))
        assertEquals(5, sim.traps.extra(torch))
    }

    @Test
    fun `ResetCountedDownTraps resets zero counter to turn interval`() {
        val sim = Sim("ram_trace_prologue_clear")
        val activeTrap = sim.traps.addDamagingTrap(1, 2, MapTraps.TRAP_FIRETILE, 0, 0, 3, 10)
        val pendingTrap = sim.traps.addDamagingTrap(3, 4, MapTraps.TRAP_GAS, 0, 2, 3, 3)

        sim.traps.resetCountedDownTraps()

        assertEquals(3, sim.traps.data(activeTrap, MapTraps.TRAP_EXTDATA_TRAP_COUNTER))
        assertEquals(2, sim.traps.data(pendingTrap, MapTraps.TRAP_EXTDATA_TRAP_COUNTER))
    }

    @Test
    fun `GenerateTrapDamageTargets finds units on fire tile, column, and gas line`() {
        val sim = Sim("ram_trace_prologue_clear")
        sim.map.unit[5, 5] = 1
        sim.map.unit[8, 10] = 2
        sim.map.unit[6, 3] = 0x81

        // Fire tile at (5, 5)
        sim.traps.addDamagingTrap(5, 5, MapTraps.TRAP_FIRETILE, 0, 0, 2, 10)
        // Arrow trap at column 10
        sim.traps.addDamagingTrap(10, 0, MapTraps.TRAP_LIGHTARROW, 0, 0, 2, 10)
        // Gas trap at (3, 7) facing UP (tiles (3,6), (3,5), (3,4))
        sim.traps.addDamagingTrap(3, 7, MapTraps.TRAP_GAS, MapTraps.FACING_UP, 0, 2, 3)

        sim.traps.generateTrapDamageTargets()

        assertEquals(3, sim.targets.getSelectTargetCount())
        assertEquals(5, sim.targets.targetX(0))
        assertEquals(5, sim.targets.targetY(0))
        assertEquals(1, sim.targets.targetUid(0))
        assertEquals(10, sim.targets.targetExtra(0))

        assertEquals(10, sim.targets.targetX(1))
        assertEquals(8, sim.targets.targetY(1))
        assertEquals(2, sim.targets.targetUid(1))

        assertEquals(3, sim.targets.targetX(2))
        assertEquals(6, sim.targets.targetY(2))
        assertEquals(0x81.toByte().toInt(), sim.targets.targetUid(2))
        assertEquals(3, sim.targets.targetExtra(2))
    }

    @Test
    fun `GenerateDisplayedTrapDamageTargets adds display target and damage target`() {
        val sim = Sim("ram_trace_prologue_clear")
        sim.map.unit[4, 4] = 1

        sim.traps.addDamagingTrap(4, 4, MapTraps.TRAP_FIRETILE, 0, 0, 2, 10)

        sim.traps.generateDisplayedTrapDamageTargets()

        assertEquals(2, sim.targets.getSelectTargetCount())
        // Display target
        assertEquals(4, sim.targets.targetX(0))
        assertEquals(4, sim.targets.targetY(0))
        assertEquals(0, sim.targets.targetUid(0))
        assertEquals(MapTraps.TRAP_FIRETILE, sim.targets.targetExtra(0))
        // Damage target
        assertEquals(4, sim.targets.targetX(1))
        assertEquals(4, sim.targets.targetY(1))
        assertEquals(1, sim.targets.targetUid(1))
        assertEquals(10, sim.targets.targetExtra(1))
    }

    @Test
    fun `DecayTraps decrements torchlight and light rune and removes them at zero`() {
        val sim = Sim("ram_trace_prologue_clear")
        val torch = sim.traps.addTrap(2, 3, MapTraps.TRAP_TORCHLIGHT, 2)
        val rune = sim.traps.addLightRune(4, 5)

        assertEquals(MapTraps.TERRAIN_NONE, sim.map.terrain[5, 4])

        // First decay
        sim.traps.decayTraps()
        assertEquals(1, sim.traps.extra(torch))
        assertEquals(2, sim.traps.data(rune, MapTraps.TRAP_EXTDATA_RUNE_TURNSLEFT))

        // Second decay: torch reaches 0 and is removed; rune shifts to index 0
        sim.traps.decayTraps()
        assertEquals(MapTraps.TRAP_LIGHT_RUNE, sim.traps.type(0))
        assertEquals(1, sim.traps.data(0, MapTraps.TRAP_EXTDATA_RUNE_TURNSLEFT))
        assertEquals(MapTraps.TRAP_NONE, sim.traps.type(1))

        // Third decay: rune reaches 0 and is removed; terrain restored
        sim.traps.decayTraps()
        assertEquals(MapTraps.TRAP_NONE, sim.traps.type(0))
        assertEquals(sim.map.getTrueTerrainAt(4, 5), sim.map.terrain[5, 4])
    }

    @Test
    fun `GetTrapAt and GetObstacleHpAt resolve vertically stacked damaged walls`() {
        val sim = Sim("ram_trace_prologue_clear")
        sim.map.terrain[5, 3] = MapTraps.TERRAIN_WALL_DAMAGED
        sim.map.terrain[6, 3] = MapTraps.TERRAIN_WALL_DAMAGED

        // Obstacle trap on the topmost wall tile (3, 5) with 20 HP
        sim.traps.addTrap(3, 5, MapTraps.TRAP_OBSTACLE, 20)

        // Querying (3, 5) finds the trap
        assertEquals(0, sim.traps.getTrapAt(3, 5))
        assertEquals(20, sim.traps.getObstacleHpAt(3, 5))

        // Querying bottom wall tile (3, 6) resolves to topmost wall tile (3, 5)
        assertEquals(0, sim.traps.getTrapAt(3, 6))
        assertEquals(20, sim.traps.getObstacleHpAt(3, 6))
    }

    private inner class Sim(runName: String) {
        val run: ReferenceData.Run
        val rom: RomView
        val engine = ProcEngine()
        val playSt = PlaySt()
        val map: BmMap
        val units: UnitTable
        val flags = Fe8EventFlags.create()
        val action = ActionData()
        val targets = SelectTargets()
        val traps: MapTraps

        init {
            assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
            assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
            run = ref.run(runName)
            rom = RomView.ofRom(romFile.readBytes())
            units = UnitTable(rom)
            map = BmMap(rom, ChapterData(rom), BmSt())
            map.initChapterMap(0)
            traps = MapTraps(ByteArray(MapTraps.POOL_SIZE), map, units, playSt, flags, targets, engine, action)
        }

        val blue = run.watch("blue")
        val red = run.watch("red")
        val playStTrace = run.watch("gplayst")

        fun seedUnits(step: Int) {
            blue.step(step).copyInto(units.bytes, 0)
            red.step(step).copyInto(units.bytes, RED_OFFSET)
        }

        fun seedPlaySt(step: Int) = playSt.decode(playStTrace.step(step), 0)
    }

    @Test
    fun `battle window f4181-4892 has an empty pool and the Light Rune toggles leave terrain as captured`() {
        val sim = Sim("ram_trace_prologue_clear")
        val empty = ByteArray(MapTraps.POOL_SIZE)
        val frames = listOf(4181, 4182, 4189, 4240, 4250, 4260, 4266, 4269, 4271, 4272, 4273, 4274,
            4892)
        for (frame in frames) {
            val ewram = sim.run.region("r%05d".format(frame), "ewram")
            val pool = ewram.copyOfRange(
                TRAP_POOL_ADDR - EWRAM_BASE, TRAP_POOL_ADDR - EWRAM_BASE + MapTraps.POOL_SIZE,
            )
            assertArrayEquals(empty, pool, "sTrapPool at f$frame")
        }
        // The Prologue pool is empty, so the toggles that bracket every battle (f4240..4274:
        // forecast generation, then the real battle) have nothing to switch: the terrain map the
        // capture holds equals the chapter's own at both ends, and the port's toggles keep it so.
        for (frame in listOf(4240, 4274)) {
            val ewram = sim.run.region("r%05d".format(frame), "ewram")
            val rows = ewram.u32(GBM_MAP_TERRAIN - EWRAM_BASE) - EWRAM_BASE
            sim.traps.disableAllLightRunes()
            sim.traps.enableAllLightRunes()
            for (y in 0 until sim.map.sizeY) {
                val row = ewram.u32(rows + 4 * y) - EWRAM_BASE
                for (x in 0 until sim.map.sizeX) {
                    assertEquals(
                        ewram[row + x].toInt() and 0xFF, sim.map.terrain[y, x],
                        "terrain ($x, $y) at f$frame",
                    )
                }
            }
        }
    }

    @Test
    fun `GetPickTrapType is none and GetTrapAt finds nothing for every unit on an empty pool`() {
        val sim = Sim("ram_trace_prologue_clear")
        sim.seedUnits(4181)
        var checked = 0
        for (id in 1..62) {
            if (sim.units.u32At(id) == 0) continue
            checked++
            assertEquals(MapTraps.TRAP_NONE, sim.traps.getPickTrapType(id), "unit $id")
            assertEquals(-1, sim.traps.getTrapAt(sim.units.xPos(id), sim.units.yPos(id)))
        }
        assertTrue(checked > 0, "no blue unit")
    }

    @Test
    fun `Light Rune toggles and GetPickTrapType on a synthetic pool`() {
        val sim = Sim("ram_trace_prologue_clear")
        val trueTerrain = sim.map.terrain[5, 4]
        sim.traps.addLightRune(4, 5)
        assertEquals(MapTraps.TERRAIN_NONE, sim.map.terrain[5, 4])
        sim.traps.disableAllLightRunes()
        assertEquals(sim.map.getTrueTerrainAt(4, 5), sim.map.terrain[5, 4])
        assertEquals(trueTerrain, sim.map.terrain[5, 4])
        sim.traps.enableAllLightRunes()
        assertEquals(MapTraps.TERRAIN_NONE, sim.map.terrain[5, 4])

        // A ballista picks nothing; a fire tile gives its own type to a unit without CA_THIEF.
        sim.seedUnits(4181)
        val id = (1..62).first { sim.units.u32At(it) != 0 }
        val x = sim.units.xPos(id)
        val y = sim.units.yPos(id)
        sim.traps.addTrap(x, y, MapTraps.TRAP_BALLISTA, 0)
        assertEquals(MapTraps.TRAP_NONE, sim.traps.getPickTrapType(id))
        sim.traps.clearTraps()
        sim.traps.addTrap(x, y, MapTraps.TRAP_GORGON_EGG, 0)
        assertEquals(MapTraps.TRAP_GORGON_EGG, sim.traps.getPickTrapType(id))
    }

    @Test
    fun `retail ROM bodies of the battle debug prints change nothing`() {
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        fun half(address: Int, i: Int): Int =
            (rom[address - 0x08000000 + 2 * i].toInt() and 0xFF) or
                ((rom[address - 0x08000000 + 2 * i + 1].toInt() and 0xFF) shl 8)
        // BattlePrintDebugUnitInfo: 2 bytes, `bx lr`.
        assertEquals(0x4770, half(0x0802CAF8, 0))
        // BattlePrintDebugHitInfo: 0x26 bytes; no BL, no store (str/strb/strh immediate or sp).
        for (i in 0 until 0x26 / 2) {
            val op = half(0x0802CAFC, i)
            if (i == 7 || i == 8) continue // literal pool word
            assertTrue((op ushr 11) != 0b11110 && (op ushr 11) != 0b11111, "BL at $i")
            assertTrue((op ushr 11) !in listOf(0b01100, 0b01110, 0b10000, 0b10010), "store at $i")
        }
    }

    private companion object {
        const val EWRAM_BASE = 0x02000000
        const val TRAP_POOL_ADDR = 0x0203A614
        const val TARGET_ROOT_ADDR = 0x0203DDE8
        const val TARGET_LIST_ADDR = 0x0203DDEC
        const val TARGET_COUNT_ADDR = 0x0203E0EC
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val RED_OFFSET = 62 * 0x48
        const val US_DEAD = 1 shl 2

        /** The character pointer word of unit [id] (0 for an empty slot). */
        fun UnitTable.u32At(id: Int): Int = bytes.u32(unitOffset(id))

        fun ByteArray.u32(offset: Int): Int =
            (this[offset].toInt() and 0xFF) or
                ((this[offset + 1].toInt() and 0xFF) shl 8) or
                ((this[offset + 2].toInt() and 0xFF) shl 16) or
                ((this[offset + 3].toInt() and 0xFF) shl 24)
    }
}
