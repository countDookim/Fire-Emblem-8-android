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
import fe8.game.bm.MuProc
import gbafe.core.flags.FlagStore
import gbafe.core.mechanics.Mechanics
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcBoolFunc
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
 * Differential test verifying the map animation flow (EP-17 and EP-18, src/mapanim.c) against captured
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
    @VerifiesPort(
        "MapAnim_DisplayRoundAnim",
        "MapAnim_DisplayDeathQuote",
        "MapAnim_DisplayDeathFade",
        "MapAnim_DisplayExpBar",
        "MapAnim_PrepareNextBattleRound",
    )
    fun `map animation battle runs rounds, death and exp bar through the end script f4275 to f4891`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val names = ProcArrayDump.romNames()
        val addressOf = { name: String ->
            names.entries.first { it.value == name }.key
        }

        val run = ref.run(RUN_CLEAR)
        val dumpNames = run.dumps.entries.associate { it.value to it.key }
        val ewram = run.region(dumpNames.getValue(4280), "ewram")
        val procs = run.watch("procs")
        val gbm = run.watch("gbmst")
        val dumpAt = { step: Int -> ProcArrayDump.ofWatch(procs, step, names) }

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
        val flagSet = HashSet<Int>().also { it.add(EVFLAG_GAMEOVER) }
        val flags = object : FlagStore {
            override fun checkFlag(id: Int) = id in flagSet
            override fun setFlag(id: Int) { flagSet.add(id) }
            override fun clearFlag(id: Int) { flagSet.remove(id) }
        }
        val api = MapAnimApi(
            engine, bm, battle, gen, table, loader, mu, rom, null, camMove, flags,
        )
        val hooks = api.hooks
        hooks.spritePalette = { id ->
            when (id and 0xC0) {
                0x00 -> 0xC
                0x80 -> 0xD
                0x40 -> 0xE
                else -> 0xB
            }
        }
        hooks.ensureCameraOntoPosition = { _, _, _ -> false }
        hooks.afterCleanup = {}

        var currentStep = 4274
        // Stand-ins for the procs of later tasks: each lives exactly as long as the captured proc
        // of the same name, so the dispatchers under test see a child that blocks and then ends.
        fun capturedProcScript(name: String, onEnd: () -> Unit = {}) = ProcScript(
            "$name (capture)",
            listOf(
                ProcScripts.whileTrue(ProcBoolFunc { dumpAt(currentStep).find(name) != null }),
                ProcScripts.call(ProcFunc { onEnd() }),
                ProcScripts.end(),
            ),
        )

        val eventScript = capturedProcScript("ProcScr_BattleEventEngine")
        hooks.callBattleQuoteEventsIfAny = { _, _ -> engine.start(eventScript, 3) }
        hooks.battleEventEngineExists = { engine.find(eventScript) != null }

        val roundScript = capturedProcScript("ProcScr_MapAnimDefaultItemEffect") {
            val target = api.state.targetActorId
            api.state.hpCur[target] = maxOf(0, api.state.hpCur[target] - api.state.hitDamage)
        }
        hooks.roundProcScript = { roundScript }

        val quotePids = ArrayList<Int>()
        val quoteSteps = ArrayList<Int>()
        hooks.checkBattleDefeatTalk = { pid -> quotePids.add(pid); true }
        hooks.displayDefeatTalkForPid = { pid ->
            quoteSteps.add(currentStep)
            quotePids.add(pid)
            engine.start(eventScript, 3)
        }
        val infoBoxEnds = ArrayList<Int>()
        hooks.endMapAnimInfoWindow = { infoBoxEnds.add(currentStep) }

        var fadeMu: MuProc? = null
        val fadeScript = capturedProcScript("ProcScr_MuDeathFade") {
            mu.endMu(fadeMu!!)
        }
        hooks.muStartDeathFade = { proc ->
            fadeMu = proc.state as MuProc
            engine.start(fadeScript, proc)
        }
        hooks.muDeathFadeExists = { engine.find(fadeScript) != null }

        val expArgs = ArrayList<IntArray>()
        val expScript = capturedProcScript("ProcScr_MapAnimExpBar")
        hooks.startMapAnimExpBar = { parent, from, to, actor ->
            expArgs.add(intArrayOf(currentStep, from, to, actor))
            engine.startBlocking(expScript, parent)
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

        val scriptBase = mapOf(
            api.script to addressOf("ProcScr_MapAnimBattle"),
            api.endScript to addressOf("ProcScr_MapAnimEnd"),
        )
        var ended = -1
        for (step in 4275..4891) {
            currentStep = step
            if (step != 4471) {
                for (tree in 0 until 8) engine.run(engine.root(tree))
            }

            val at = "$RUN_CLEAR step $step"
            val dump = dumpAt(step)
            val original = dump.find("ProcScr_MapAnimBattle")
            val mine = engine.find(api.script)
            if (original == null) {
                assertEquals(null, mine, "$at animation proc ended")
                assertEquals(gbm.u8(step, 1), bm.lock, "$at gBmSt.lock")
                ended = step
                break
            }
            checkNotNull(mine) { "$at my animation proc" }
            val mineAddress = scriptBase.getValue(mine.scrScript!!) + 8 * mine.scrCur
            assertEquals(original.u32(4), mineAddress, "$at script position")
            assertEquals(original.u8(0x24), mine.sleepTime, "$at sleep timer")
            assertEquals(gbm.u8(step, 1), bm.lock, "$at gBmSt.lock")
            assertEquals(
                dump.findAll(addressOf("ProcScr_Mu")).size, engine.count(mu.script), "$at MUs",
            )
        }
        assertEquals(4891, ended, "animation proc ends")

        // The round procs, by their captured start frames.
        val roundStarts = (4485..4891).filter { step ->
            dumpAt(step).find("ProcScr_MapAnimDefaultItemEffect") != null &&
                dumpAt(step - 1).find("ProcScr_MapAnimDefaultItemEffect") == null
        }
        assertEquals(listOf(4485, 4544, 4590), roundStarts, "round frames")

        // Death: the captured fade proc names the MOVEUNIT, its unit the character.
        val fadeDump = dumpAt(4742)
        val fade = checkNotNull(fadeDump.find("ProcScr_MuDeathFade")) { "captured death fade" }
        val deadMu = fadeDump.live.first { it.address == fade.u32(0x54) }
        val deadUnit = deadMu.u32(0x2C)
        val deadPid = rom.u8(u32(ewram, deadUnit - EWRAM) + 4)
        assertEquals(deadUnit, fadeMu!!.unitAddress, "death fade MOVEUNIT's unit")
        assertEquals(listOf(deadPid, deadPid), quotePids, "defeat quote character")
        assertEquals(listOf(4636), quoteSteps, "defeat quote frame")
        assertTrue(EVFLAG_GAMEOVER in flagSet, "game over flag kept for another character")

        // The info box ends with the quote (f4636), after the fade (f4774) and in the clean-up.
        val boxGone = (4630..4891).first { dumpAt(it).find("ProcScr_MapBattleInfoBox") == null }
        assertEquals(boxGone, infoBoxEnds[0], "info box end frame")
        assertEquals(listOf(4636, 4774, 4891), infoBoxEnds, "info box end calls")

        // Exp: the captured bar's from, to and actor.
        val bar = checkNotNull(dumpAt(4776).find("ProcScr_MapAnimExpBar")) { "captured exp bar" }
        fun s16(v: Int) = v.toShort().toInt()
        assertEquals(1, expArgs.size, "one exp bar")
        assertEquals(4776, expArgs[0][0], "exp bar frame")
        assertEquals(s16(bar.u16(0x64)), expArgs[0][1], "exp from")
        assertEquals(s16(bar.u16(0x66)), expArgs[0][2], "exp to")
        assertEquals(s16(bar.u16(0x68)), expArgs[0][3], "exp actor")
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

        // Actor 1: red enemy holding ITEM_SWORD_IRON
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
        const val EVFLAG_GAMEOVER = 0x65
        const val GBMST = 0x0202BCB0
        const val GPLAYST = 0x0202BCF0
        const val GBATTLESTATS = 0x0203A4D4
        const val GBATTLE_ACTOR = 0x0203A4EC
        const val GBATTLE_TARGET = 0x0203A56C
        const val GHITARRAY = 0x0203A5EC
    }
}
