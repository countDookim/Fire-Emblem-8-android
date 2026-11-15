package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleGenerate
import fe8.game.bm.BattleStats
import fe8.game.bm.BattleUnit
import fe8.game.bm.BmBattle
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.MapAnimApi
import fe8.game.bm.Mu
import fe8.game.bm.MuHooks
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.mechanics.Mechanics
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test verifying the map animation flow (EP-17, src/mapanim.c) against captured
 * runs: Seth attacking O'Neill in `ram_trace_prologue_clear` (steps 4274..4484) and the two Soldier
 * counter-attacks in `ram_trace_prologue_enemy_phase` (steps 1695..1722 and 2128..2155).
 *
 * Verifies `ProcScr_MapAnimBattle` position, sleep timer, game lock, camera positioning, battle
 * quotes, MOVEUNIT palette update, info box layout and round advancement.
 */
class MapAnimFlowDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "MapAnim_MoveCameraOntoSubject",
        "MapAnim_CallBattleQuoteEvents",
        "MapAnim_InitInfoBox",
        "MapAnim_AdvanceBattleRound",
        "MapAnim_PrepareNextBattleRound",
    )
    fun `map animation battle reproduces script positions and gManimSt f4275 to f4484`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val names = ProcArrayDump.romNames()

        val run = ref.run(RUN_CLEAR)
        val dumpNames = run.dumps.entries.associate { it.value to it.key }
        val ewram = run.region(dumpNames.getValue(4280), "ewram")
        val procs = run.watch("procs")
        val gbm = run.watch("gbmst")

        val table = UnitTable(rom).also { it.loadFrom(ewram) }
        val playSt = PlaySt().also { it.decode(ewram, GPLAYST - EWRAM) }
        val bm = BmSt().also { it.decode(ewram, GBMST - EWRAM) }
        val rng = RngService()
        val loader = UnitLoader(table, rom, { 0 }, rng, { false })
        val battle = BmBattle(rom, table, loader, playSt, bm) { _, _ -> 0 }
        val gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics())
        ewram.copyInto(
            battle.actor.bytes, 0, GBATTLE_ACTOR - EWRAM, GBATTLE_ACTOR - EWRAM + BattleUnit.SIZE,
        )
        ewram.copyInto(
            battle.target.bytes, 0, GBATTLE_TARGET - EWRAM,
            GBATTLE_TARGET - EWRAM + BattleUnit.SIZE,
        )
        battle.stats.decode(ewram, GBATTLESTATS - EWRAM)
        battle.stats.config = battle.stats.config and BattleStats.CONFIG_MAPANIMS.inv()
        for (h in 0 until BattleGenerate.BATTLE_HIT_MAX) {
            gen.hitArray[h] = u32(ewram, GHITARRAY - EWRAM + 4 * h)
        }
        bm.lock = 0

        val engine = ProcEngine()
        val camMove = CamMove(engine, bm)
        val mu = Mu(engine, bm, playSt, KeyStatus(), camMove.script, ClassHooks(rom))
        val api = MapAnimApi(engine, bm, battle, gen, table, loader, mu, rom, null, camMove)
        api.hooks.spritePalette = { id ->
            when (id and 0xC0) {
                0x00 -> 0xC
                0x80 -> 0xD
                0x40 -> 0xE
                else -> 0xB
            }
        }

        var cameraMovedX = -1
        var cameraMovedY = -1
        api.hooks.ensureCameraOntoPosition = { _, x, y ->
            cameraMovedX = x
            cameraMovedY = y
            false
        }

        var quoteCalledPidA = -1
        var quoteCalledPidB = -1
        api.hooks.callBattleQuoteEventsIfAny = { pidA, pidB ->
            quoteCalledPidA = pidA
            quoteCalledPidB = pidB
        }

        var infoBoxX = -1
        var infoBoxY = -1
        api.hooks.startMapAnimInfoWindow = { x, y, _ ->
            infoBoxX = x
            infoBoxY = y
        }

        var currentStep = 4274
        api.hooks.battleEventEngineExists = {
            // Battle quote runs 4278..4460; in frame 4461 MapAnimBattle runs before
            // BattleEventEngine ends
            currentStep in 4278..4461
        }

        engine.start(
            ProcScript(
                "CombatAction (test)",
                listOf(
                    ProcScripts.call(ProcFunc { api.beginBattleAnimations() }),
                    ProcScripts.end(),
                ),
            ),
            2,
        )

        for (step in 4275..4484) {
            currentStep = step
            // Skip engine advance on the one LZ77 decompression stall frame (4471) where
            // reference procs do not run
            if (step != 4471) {
                for (tree in 0 until 8) engine.run(engine.root(tree))
            }

            val at = "$RUN_CLEAR step $step"
            val dump = ProcArrayDump.ofWatch(procs, step, names)
            val original = checkNotNull(dump.find("ProcScr_MapAnimBattle")) {
                "$at original animation proc"
            }
            val mine = checkNotNull(engine.find(api.script)) { "$at my animation proc" }

            assertEquals(original.scrCur, mine.scrCur, "$at script position")
            assertEquals(original.u8(0x24), mine.sleepTime, "$at sleep timer")
            assertEquals(gbm.u8(step, 1), bm.lock, "$at gBmSt.lock")

            if (step == 4276) {
                assertEquals(8, cameraMovedX, "$at camera X")
                assertEquals(6, cameraMovedY, "$at camera Y")
            }
            if (step == 4278) {
                assertEquals(2, quoteCalledPidA, "$at quote pidA (Seth)")
                assertEquals(0x68, quoteCalledPidB, "$at quote pidB (O'Neill)")
            }
            if (step == 4468) {
                assertEquals(15, infoBoxX, "$at info box X")
                assertEquals(15, infoBoxY, "$at info box Y")
            }
        }

        // Verify gManimSt state after step 4484 matches decomp and capture dump
        val s = api.state
        assertEquals(1, s.currentRound, "currentRound advanced to 1")
        assertEquals(0, s.subjectActorId, "subjectActorId is 0 (actor)")
        assertEquals(1, s.targetActorId, "targetActorId is 1 (target)")
        assertEquals(0, s.hitAttributes, "hitAttributes is 0")
        assertEquals(BattleGenerate.INFO_BEGIN, s.hitInfo, "hitInfo has INFO_BEGIN")
        assertEquals(20, s.hitDamage, "hitDamage is 20")
        assertEquals(2, s.actorCount, "actorCount is 2")
        assertEquals(0, s.specialProcScr, "specialProcScr is 0")
    }

    @Test
    fun `map animation battle proc flow reproduces soldier attacks in enemy phase`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val names = ProcArrayDump.romNames()

        val run = ref.run(RUN_ENEMY)
        val dumpNames = run.dumps.entries.associate { it.value to it.key }
        val ewram1700 = run.region(dumpNames.getValue(1700), "ewram")
        val ewram2130 = run.region(dumpNames.getValue(2130), "ewram")
        val procs = run.watch("procs")
        val gbm = run.watch("gbmst")

        val cases = listOf(
            SoldierCase(1697, 1723, ewram1700),
            SoldierCase(2130, 2156, ewram2130),
        )

        for (sc in cases) {
            val table = UnitTable(rom).also { it.loadFrom(sc.ewram) }
            val playSt = PlaySt().also { it.decode(sc.ewram, GPLAYST - EWRAM) }
            val bm = BmSt().also { it.decode(sc.ewram, GBMST - EWRAM) }
            val rng = RngService()
            val loader = UnitLoader(table, rom, { 0 }, rng, { false })
            val battle = BmBattle(rom, table, loader, playSt, bm) { _, _ -> 0 }
            val gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics())
            sc.ewram.copyInto(
                battle.actor.bytes, 0, GBATTLE_ACTOR - EWRAM,
                GBATTLE_ACTOR - EWRAM + BattleUnit.SIZE,
            )
            sc.ewram.copyInto(
                battle.target.bytes, 0, GBATTLE_TARGET - EWRAM,
                GBATTLE_TARGET - EWRAM + BattleUnit.SIZE,
            )
            battle.stats.decode(sc.ewram, GBATTLESTATS - EWRAM)
            battle.stats.config = battle.stats.config and BattleStats.CONFIG_MAPANIMS.inv()
            for (h in 0 until BattleGenerate.BATTLE_HIT_MAX) {
                gen.hitArray[h] = u32(sc.ewram, GHITARRAY - EWRAM + 4 * h)
            }
            bm.lock = 0

            val engine = ProcEngine()
            val camMove = CamMove(engine, bm)
            val mu = Mu(engine, bm, playSt, KeyStatus(), camMove.script, ClassHooks(rom))
            val api = MapAnimApi(engine, bm, battle, gen, table, loader, mu, rom, null, camMove)
            api.hooks.spritePalette = { id ->
                when (id and 0xC0) {
                    0x00 -> 0xC
                    0x80 -> 0xD
                    0x40 -> 0xE
                    else -> 0xB
                }
            }

            engine.start(
                ProcScript(
                    "CombatAction (test)",
                    listOf(
                        ProcScripts.call(ProcFunc { api.beginBattleAnimations() }),
                        ProcScripts.end(),
                    ),
                ),
                2,
            )

            for (step in sc.start..sc.end) {
                // Skip engine advance on the one LZ77 decompression stall frame (1709 or 2142)
                if (step != 1709 && step != 2142) {
                    for (tree in 0 until 8) engine.run(engine.root(tree))
                }

                val at = "$RUN_ENEMY step $step"
                val dump = ProcArrayDump.ofWatch(procs, step, names)
                val original = checkNotNull(dump.find("ProcScr_MapAnimBattle")) {
                    "$at original animation proc"
                }
                val mine = checkNotNull(engine.find(api.script)) { "$at my animation proc" }

                assertEquals(original.scrCur, mine.scrCur, "$at script position")
                assertEquals(original.u8(0x24), mine.sleepTime, "$at sleep timer")
                assertEquals(gbm.u8(step, 1), bm.lock, "$at gBmSt.lock")
            }
        }
    }

    @Test
    fun `setBattleMuPaletteByIndex configures special palette and sprite for transformed Myrrh`() {
        assumeTrue(romFile.isFile, "ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val engine = ProcEngine()
        val bm = BmSt()
        val playSt = PlaySt()
        val rng = RngService()
        val table = UnitTable(rom)
        val loader = UnitLoader(table, rom, { 0 }, rng, { false })
        val battle = BmBattle(rom, table, loader, playSt, bm) { _, _ -> 0 }
        val gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics())
        val mu = Mu(engine, bm, playSt, KeyStatus(), CamMove(engine, bm).script, ClassHooks(rom))
        val api = MapAnimApi(engine, bm, battle, gen, table, loader, mu, rom)

        var palSetMu = -1
        var specialSpriteClass = -1
        api.hooks.setMuPal = { _, pal -> palSetMu = pal }
        api.hooks.setMuSpecialSprite = { _, classId, _ -> specialSpriteClass = classId }

        val dummyMu = engine.start(ProcScript("DummyMu", listOf(ProcScripts.end())), 0)
        api.state.mu[0] = dummyMu
        api.state.actorCount = 2

        // Actor 0: Blue Myrrh with Divinestone
        val myrrhBytes = ByteArray(0x48)
        val charPtr = characterDataAt(1) // Eirika pid as character
        val classPtr = classDataAt(0x3C) // CLASS_MANAKETE_MYRRH
        put32(myrrhBytes, 0x00, charPtr)
        put32(myrrhBytes, 0x04, classPtr)
        myrrhBytes[0x0B] = 0x00 // Blue faction
        myrrhBytes[0x30] = 0x00 // No status
        api.state.unitBytes[0] = myrrhBytes
        api.state.unitOffset[0] = 0
        battle.actor.weaponBefore = 0xAA // ITEM_DIVINESTONE
        api.state.bu[0] = battle.actor

        // Actor 1: Red enemy with Iron Sword
        val enemyBytes = ByteArray(0x48)
        val enemyCharPtr = characterDataAt(0x41)
        val enemyClassPtr = classDataAt(1)
        put32(enemyBytes, 0x00, enemyCharPtr)
        put32(enemyBytes, 0x04, enemyClassPtr)
        enemyBytes[0x0B] = 0x80.toByte() // Red faction
        enemyBytes[0x30] = 0x00
        api.state.unitBytes[1] = enemyBytes
        api.state.unitOffset[1] = 0
        battle.target.weaponBefore = 0x01 // ITEM_SWORD_IRON
        api.state.bu[1] = battle.target

        api.setBattleMuPalette()

        assertEquals(8, palSetMu, "BM_OBJPAL_BANIM_SPECIALMU set on Myrrh MU")
        assertEquals(0x0E, specialSpriteClass, "CLASS_MANAKETE sprite set on Myrrh MU")
    }

    private class SoldierCase(val start: Int, val end: Int, val ewram: ByteArray)

    private class ClassHooks(private val rom: RomView) : MuHooks {
        override fun classAttributes(jid: Int): Int =
            rom.u32(GCLASS_DATA + (jid - 1) * CLASS_SIZE + 0x28)

        override fun classSlowWalking(jid: Int): Int =
            rom.u8(GCLASS_DATA + (jid - 1) * CLASS_SIZE + 0x07)
    }

    private fun characterDataAt(id: Int): Int =
        if (id < 1) 0 else CHARACTER_DATA + (id - 1) * CHARACTER_DATA_SIZE

    private fun classDataAt(id: Int): Int =
        if (id < 1) 0 else GCLASS_DATA + (id - 1) * CLASS_SIZE

    private fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v ushr 8) and 0xFF).toByte()
        b[o + 2] = ((v ushr 16) and 0xFF).toByte()
        b[o + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private companion object {
        const val RUN_CLEAR = "ram_trace_prologue_clear"
        const val RUN_ENEMY = "ram_trace_prologue_enemy_phase"
        const val EWRAM = 0x02000000
        const val CHARACTER_DATA = 0x08803D64
        const val CHARACTER_DATA_SIZE = 0x34
        const val GCLASS_DATA = 0x08807164
        const val CLASS_SIZE = 0x54
        const val GBMST = 0x0202BCB0
        const val GPLAYST = 0x0202BCF0
        const val GBATTLESTATS = 0x0203A4D4
        const val GBATTLE_ACTOR = 0x0203A4EC
        const val GBATTLE_TARGET = 0x0203A56C
        const val GHITARRAY = 0x0203A5EC
    }
}
