package fe8.game

import fe8.game.bm.BattleUnit
import fe8.game.bm.BmBattle
import fe8.game.bm.BmSt
import fe8.game.bm.MAExpBarProc
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import fe8.game.mapanim.MapLevelUpAnnouncement
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Tests that [PlayerPhaseWiring] wires [MapLevelUpAnnouncement] into [fe8.game.bm.MapAnimExpBar],
 * that an exp gain crossing 100 starts `ProcScr_ManimLevelUp` as a blocking child, that exp below
 * 100 does not, and that the display cleanup via [MapLevelUpAnnouncement.Hooks.resetDialogueScreen]
 * clears BG0/BG1 tilemaps, resets window enables and scroll offsets, and flags background sync.
 */
class MapLevelUpWiringTest {
    private fun boot(): Fe8Boot {
        val dir = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val file = File(dir, "baserom.gba")
        assumeTrue(file.isFile, "baserom.gba not present")
        return Fe8Boot(RomView.ofRom(file.readBytes()), { 0 }, RngService())
    }

    @Test
    fun `boot wires MapLevelUpAnnouncement into MapAnimExpBar and starts level-up at 100 exp`() {
        val boot = boot()
        // Accessing bmapMain triggers construction of battle map and PlayerPhaseWiring
        assertNotNull(boot.bmapMain)
        val wiring = checkNotNull(boot.playerWiring) { "playerWiring must be initialized" }

        assertNotNull(wiring.expBar, "expBar must be initialized")
        assertNotNull(wiring.levelUp, "levelUp announcement must be initialized")

        val parentScript = ProcScript("TestParent", listOf(block()))
        val parent = boot.engine.start(parentScript, 3)

        // 1. Exp >= 100 starts ProcScr_ManimLevelUp as a blocking child.
        wiring.expBar.start(parent, expFrom = 80, expTo = 120, actorId = 0)
        val expBarProc = checkNotNull(boot.engine.find(wiring.expBar.script)) {
            "exp bar proc should exist"
        }
        val expState = expBarProc.state as MAExpBarProc
        assertEquals(80, expState.expFrom)
        assertEquals(120, expState.expTo)

        wiring.expBar.procMAExpBarLevelUpIfPossible(expBarProc)
        val levelUpProc = boot.engine.find(wiring.levelUp.script)
        assertNotNull(levelUpProc, "ProcScr_ManimLevelUp must start when expTo >= 100")
        assertTrue(expBarProc.lockCnt > 0, "expBar must be blocked while level-up runs")

        // 2. Exp < 100 does NOT start level-up.
        boot.engine.end(levelUpProc!!)
        boot.engine.end(expBarProc)

        val parent2 = boot.engine.start(parentScript, 3)
        wiring.expBar.start(parent2, expFrom = 30, expTo = 65, actorId = 0)
        val expBarProc2 = checkNotNull(boot.engine.find(wiring.expBar.script))
        wiring.expBar.procMAExpBarLevelUpIfPossible(expBarProc2)
        assertNull(
            boot.engine.find(wiring.levelUp.script),
            "level-up must not start when exp < 100",
        )
    }

    @Test
    fun `level-up display cleans up BG0 BG1 tilemaps window enables and resets scroll offsets`() {
        val boot = boot()
        assertNotNull(boot.bmapMain)
        val wiring = checkNotNull(boot.playerWiring)
        val hw = boot.ui.env.hardware

        val parentScript = ProcScript("TestParent2", listOf(block()))
        val parent = boot.engine.start(parentScript, 3)
        wiring.expBar.start(parent, expFrom = 87, expTo = 167, actorId = 0)
        val expBarProc = checkNotNull(boot.engine.find(wiring.expBar.script))

        wiring.expBar.procMAExpBarLevelUpIfPossible(expBarProc)
        val levelUpProc = checkNotNull(boot.engine.find(wiring.levelUp.script))
        val state = levelUpProc.state as MapLevelUpAnnouncement.State
        assertEquals(0, state.actorId)

        // 1. Window initialization
        wiring.levelUp.initManimLevelUpWindow()
        assertTrue((hw.lcd.dispcnt and (1 shl 13)) != 0, "WIN0 must be enabled")

        // 2. Scroll in and scroll out offsets
        state.yScrollOffset = -144
        wiring.levelUp.scrollIn(levelUpProc)
        assertEquals(-136, state.yScrollOffset)
        assertEquals(0, hw.lcd.bgHofs[0])
        assertEquals((-136) and 0xFFFF, hw.lcd.bgVofs[0])
        assertEquals(0, hw.lcd.bgHofs[1])
        assertEquals((-136) and 0xFFFF, hw.lcd.bgVofs[1])

        // 3. Clear and verify resetDialogueScreen hook execution
        hw.tilemaps[0].fill(0x1234.toShort())
        hw.tilemaps[1].fill(0x5678.toShort())
        hw.lcd.setWinEnable(1, 1, 1)

        wiring.levelUp.clear()

        // resetDialogueScreen must have cleared tilemaps 0 & 1, reset win enable,
        // and reset BG offsets.
        for (i in 0 until 1024) {
            assertEquals(0, hw.tilemaps[0][i].toInt(), "tilemap 0 entry $i must be 0")
            assertEquals(0, hw.tilemaps[1][i].toInt(), "tilemap 1 entry $i must be 0")
        }
        assertEquals(0, hw.lcd.dispcnt and (7 shl 13), "all windows must be disabled")
        assertEquals(0, hw.lcd.bgHofs[0], "BG0 H offset must be 0")
        assertEquals(0, hw.lcd.bgVofs[0], "BG0 V offset must be 0")
        assertEquals(0, hw.lcd.bgHofs[1], "BG1 H offset must be 0")
        assertEquals(0, hw.lcd.bgVofs[1], "BG1 V offset must be 0")
        assertEquals(3, hw.modifiedBgs and 3, "BG0 and BG1 must be flagged for sync")
    }

    @Test
    fun `boot end-to-end level-up replay matches f6519 cleans up and preserves write-back`() {
        val dir = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val ref = ReferenceData.fromEnvironment()
        val romFile = File(dir, "baserom.gba")
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")

        val rom = RomView.ofRom(romFile.readBytes())
        val boot = Fe8Boot(rom, { 0 }, RngService())
        assertNotNull(boot.bmapMain)
        val wiring = checkNotNull(boot.playerWiring)
        val hw = boot.ui.env.hardware

        fun findProc(name: String) = boot.engine.procs.firstOrNull { it.script?.name == name }

        val run = ref.run("ram_trace_prologue_levelup")
        val symbols = RamSymbols.fromEnvironment()
        val seed = run.region("r06412", "ewram")
        val seedIwram = run.region("r06412", "iwram")
        val ewramBase = GbaMem.EWRAM

        // 1. Seed unit table, battle units, and state
        boot.units.loadFrom(seed)
        val battleActor = symbols["gBattleActor"].address - ewramBase
        val battleTarget = symbols["gBattleTarget"].address - ewramBase
        seed.copyInto(wiring.battle.actor.bytes, 0, battleActor, battleActor + BattleUnit.SIZE)
        seed.copyInto(wiring.battle.target.bytes, 0, battleTarget, battleTarget + BattleUnit.SIZE)
        wiring.battle.stats.decode(seed, symbols["gBattleStats"].address - ewramBase)

        // Snapshot battle unit bytes before display runs to verify immutability
        val actorSnapshot = wiring.battle.actor.bytes.copyOf()
        val targetSnapshot = wiring.battle.target.bytes.copyOf()

        // Configure mapAnim state
        val unitPointer = GbaMem.u32(seed, ewramBase, symbols["gManimSt"].address)
        wiring.mapAnim.state.unitBytes[0] = seed
        wiring.mapAnim.state.unitOffset[0] = unitPointer - ewramBase
        wiring.mapAnim.state.bu[0] = wiring.battle.actor
        wiring.mapAnim.state.bu[1] = wiring.battle.target
        wiring.mapAnim.state.buAddress[0] = symbols["gBattleActor"].address
        wiring.mapAnim.state.buAddress[1] = symbols["gBattleTarget"].address
        wiring.mapAnim.state.actorCount = 2

        hw.lcd.readFrom(seedIwram, symbols["gLCDControlBuffer"].address - GbaMem.IWRAM)
        val paletteAddress = symbols["gPaletteBuffer"].address - ewramBase
        for (i in 0 until 512) {
            hw.palette[i] = GbaMem.u16(seed, 0, paletteAddress + 2 * i).toShort()
        }
        boot.loop.bmSt.cameraX = 160
        boot.loop.bmSt.cameraY = 96

        boot.talkScene.text.initTextFont(null, 0x06001000, 0x80, 0)
        boot.talkScene.text.resetTextFont()

        // Start exp bar via mapAnimDisplayExpBar
        val parentScript = ProcScript("CombatParent", listOf(block()))
        val parent = boot.engine.start(parentScript, 2)
        wiring.mapAnim.mapAnimDisplayExpBar(parent)

        // Step engine through steps 6276..6747
        val bg0Address = symbols["gBG0TilemapBuffer"].address - ewramBase
        val bg1Address = symbols["gBG1TilemapBuffer"].address - ewramBase
        var matchedF6519 = false

        for (step in 6276..6747) {
            boot.loop.sprites.clear()
            for (tree in 0 until 8) boot.engine.run(boot.engine.root(tree))

            if (step == 6519) {
                assertNotNull(
                    boot.engine.find(wiring.levelUp.script),
                    "ProcScr_ManimLevelUp must exist at f6519",
                )
                assertNotNull(
                    findProc("ProcScr_ManimLevelUpStatGainLabel"),
                    "ProcScr_ManimLevelUpStatGainLabel must exist at f6519",
                )

                // Verify BG0 and BG1 panel draw matches ram_trace_prologue_levelup
                val ewram6519 = run.region("r06519", "ewram")
                for (i in 0 until 1024) {
                    assertEquals(
                        GbaMem.u16(ewram6519, 0, bg0Address + 2 * i),
                        hw.tilemaps[0][i].toInt() and 0xFFFF,
                        "BG0[$i] at f6519",
                    )
                    assertEquals(
                        GbaMem.u16(ewram6519, 0, bg1Address + 2 * i),
                        hw.tilemaps[1][i].toInt() and 0xFFFF,
                        "BG1[$i] at f6519",
                    )
                }
                matchedF6519 = true
            }
        }

        assertTrue(matchedF6519, "Must have reached and checked frame 6519")

        // Run two extra ticks to let cleanup settle
        for (tree in 0 until 8) boot.engine.run(boot.engine.root(tree))
        for (tree in 0 until 8) boot.engine.run(boot.engine.root(tree))

        // a) Verify no leaked procs remain
        assertNull(
            boot.engine.find(wiring.levelUp.script),
            "ProcScr_ManimLevelUp must end cleanly",
        )
        assertNull(
            findProc("ProcScr_ManimLevelUpStatGainLabel"),
            "ProcScr_ManimLevelUpStatGainLabel must end cleanly",
        )
        assertNull(
            findProc("ProcScr_ApProc"),
            "ProcScr_ApProc must end cleanly",
        )
        assertNull(
            boot.engine.find(wiring.expBar.script),
            "ProcScr_MapAnimExpBar must end cleanly",
        )

        // Verify display cleanup: BG0/BG1 tilemaps, window enables, scroll offsets
        for (i in 0 until 1024) {
            assertEquals(0, hw.tilemaps[0][i].toInt(), "tilemap 0 entry $i must be 0")
            assertEquals(0, hw.tilemaps[1][i].toInt(), "tilemap 1 entry $i must be 0")
        }
        assertEquals(0, hw.lcd.dispcnt and (7 shl 13), "all windows must be disabled")
        assertEquals(0, hw.lcd.bgHofs[0], "BG0 H offset must be reset")
        assertEquals(0, hw.lcd.bgVofs[0], "BG0 V offset must be reset")
        assertEquals(0, hw.lcd.bgHofs[1], "BG1 H offset must be reset")
        assertEquals(0, hw.lcd.bgVofs[1], "BG1 V offset must be reset")
        assertEquals(3, hw.modifiedBgs and 3, "BG0 and BG1 must be flagged for sync")

        // b) Verify battle actor/target bytes are completely unchanged by level-up display
        for (i in 0 until BattleUnit.SIZE) {
            assertEquals(
                actorSnapshot[i],
                wiring.battle.actor.bytes[i],
                "battle actor byte $i unchanged by display",
            )
            assertEquals(
                targetSnapshot[i],
                wiring.battle.target.bytes[i],
                "battle target byte $i unchanged by display",
            )
        }

        // Apply write-back
        wiring.battle.battleApplyGameStateUpdates()

        // Compare against headless write-back
        val headlessTable = UnitTable(rom).also { it.loadFrom(seed) }
        val headlessBattle = BmBattle(
            rom,
            headlessTable,
            UnitLoader(headlessTable, rom, { 0 }, RngService(), { false }),
            PlaySt().also { it.decode(seed, symbols["gPlaySt"].address - ewramBase) },
            BmSt().also { it.decode(seed, symbols["gBmSt"].address - ewramBase) },
        ) { _, _ -> 0 }
        seed.copyInto(headlessBattle.actor.bytes, 0, battleActor, battleActor + BattleUnit.SIZE)
        seed.copyInto(headlessBattle.target.bytes, 0, battleTarget, battleTarget + BattleUnit.SIZE)
        headlessBattle.stats.decode(seed, symbols["gBattleStats"].address - ewramBase)
        headlessBattle.battleApplyGameStateUpdates()

        // Write-back must be 100% byte-for-byte identical between game wiring and headless
        val actorUnitId = seed[symbols["gActionData"].address - ewramBase + 0x0C].toInt() and 0xFF
        val unitOffset = boot.units.unitOffset(actorUnitId)
        for (i in 0 until UnitTable.UNIT_SIZE) {
            assertEquals(
                headlessTable.bytes[unitOffset + i],
                boot.units.bytes[unitOffset + i],
                "Unit table byte 0x${i.toString(16)} must match headless write-back",
            )
        }

        // Also verify Eirika's new level and stats match the recorded f6760 dump
        val f6760DumpKey = run.dumps.entries.first { it.value == 6760 }.key
        val f6760Ewram = run.region(f6760DumpKey, "ewram")
        val wantTable = UnitTable(rom).also { it.loadFrom(f6760Ewram) }
        for (i in 0 until UnitTable.UNIT_SIZE) {
            assertEquals(
                wantTable.bytes[unitOffset + i],
                boot.units.bytes[unitOffset + i],
                "f6760 Eirika byte 0x${i.toString(16)}",
            )
        }
    }
}
