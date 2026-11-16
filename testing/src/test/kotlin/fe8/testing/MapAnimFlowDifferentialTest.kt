package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleGenerate
import fe8.game.bm.BattleStats
import fe8.game.bm.BattleUnit
import fe8.game.bm.BmBattle
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.SoundWrapper
import fe8.game.bm.ItemInfo
import fe8.game.bm.MapAnimApi
import fe8.game.bm.MapAnimExpBar
import fe8.game.bm.MapAnimInfoBox
import fe8.game.bm.Mu
import fe8.game.bm.MuConfig
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
import gbafe.render.BgHardware
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextHost
import gbafe.text.TextSystem
import fe8.game.text.Fe8Text
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
        "MapAnimMoveUnitTowardsTargetExt",
        "MapAnim_BeginSubjectFastAnim",
        "MapAnim_GetRoundProcScript",
        "MapAnim_MoveSubjectsTowardsTarget",
        "MapAnim_MoveCameraOnTarget",
        "MapAnim_BeginRoundSpecificAnims",
        "RegisterMapHpChangeAnim",
        "MapAnim_WaitForHPToEndChangingMaybe",
        "MapAnimMoveUnitAwayFromTargetExt",
        "MapAnim_MoveSubjectsAwayFromTarget",
        "DidBattleUnitBreakWeapon",
        "HasBattleUnitGainedWeaponLevel",
        "GetWeaponLevelFromExp",
        "GetItemAwardedExp",
        "BattleUnit_ShouldDisplayWpnBroke",
        "BattleUnit_ShouldDisplayWRankUp",
        "DisplayWpnBrokePopup",
        "DisplayWRankUpPopup",
    )
    fun `map animation battle runs rounds, death and exp bar through the end script f4275 to f4891`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
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
        var currentStep = 4274
        // Each MOVEUNIT's sprite animation gets its own handle, so the speed-up calls
        // (StartMuSpeedUpAnim) name the MOVEUNIT they speed up.
        val speedUps = ArrayList<IntArray>()
        var nextHandle = 0x100
        val muHooks = object : MuHooks by ClassHooks(rom) {
            override fun createMuAnim(proc: MuProc, config: MuConfig) = nextHandle++

            override fun setSpriteAnimClock(anim: Int, interval: Int) {
                speedUps.add(intArrayOf(currentStep, anim, interval))
            }
        }
        val mu = Mu(
            engine, bm, playSt, KeyStatus(), camMove.script, muHooks, rom,
        )
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
        hooks.afterCleanup = {}

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

        // The round proc is the real ProcScr_MapAnimDefaultItemEffect (EP-23). Its HP drain waits
        // for the real info box (EP-20, EP-21), which clears hp_changing.
        val infoHw = BgHardware()
        val infoBox = MapAnimInfoBox(
            engine, api.state, rom, hwText(rom, romBytes, infoHw), messagesOf(romBytes), infoHw,
        )
        hooks.startMapAnimInfoWindow = { x, y, p -> infoBox.startMapAnimInfoWindow(x, y, p) }
        val cameraCalls = ArrayList<IntArray>()
        val missCalls = ArrayList<IntArray>()
        hooks.beginMissAnim = java.util.function.IntConsumer { unit ->
            missCalls.add(intArrayOf(currentStep, unit))
        }
        hooks.ensureCameraOntoPosition = { _, x, y ->
            cameraCalls.add(intArrayOf(currentStep, x, y))
            false
        }

        val quotePids = ArrayList<Int>()
        val quoteSteps = ArrayList<Int>()
        hooks.checkBattleDefeatTalk = { pid -> quotePids.add(pid); true }
        hooks.displayDefeatTalkForPid = { pid ->
            quoteSteps.add(currentStep)
            quotePids.add(pid)
            engine.start(eventScript, 3)
        }
        val infoBoxEnds = ArrayList<Int>()
        hooks.endMapAnimInfoWindow = {
            infoBoxEnds.add(currentStep)
            infoBox.endMapAnimInfoWindow()
        }

        var fadeMu: MuProc? = null
        val fadeScript = capturedProcScript("ProcScr_MuDeathFade") {
            mu.endMu(fadeMu!!)
        }
        hooks.muStartDeathFade = { proc ->
            fadeMu = proc.state as MuProc
            engine.start(fadeScript, proc)
        }
        hooks.muDeathFadeExists = { engine.find(fadeScript) != null }

        // The real exp bar (EP-26); the info box supplies its tile writers.
        val expBar = MapAnimExpBar(engine, rom, infoBox, SoundWrapper(engine, null) { 0 })
        val expArgs = ArrayList<IntArray>()
        hooks.startMapAnimExpBar = { parent, from, to, actor ->
            expArgs.add(intArrayOf(currentStep, from, to, actor))
            expBar.start(parent, from, to, actor)
        }

        val brokenCalls = ArrayList<Int>()
        hooks.newPopupWeaponBroke = { weapon, _ -> brokenCalls.add(weapon) }
        val rankUpCalls = ArrayList<Int>()
        hooks.newPopupWRankIncrease = { wType, _ -> rankUpCalls.add(wType) }

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
        var muHandles = listOf(0, 0)
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
            assertEquals(
                dump.find("ProcScr_MapAnimExpBar") != null, engine.find(expBar.script) != null,
                "$at exp bar exists",
            )
            if (step == 4485) {
                muHandles = (0 until 2).map { (api.state.mu[it]!!.state as MuProc).spriteAnim }
            }
            if (step in ROUND_CHECK_STEPS) {
                checkRoundStep(
                    step, dump, engine.find(api.roundScript), api, mu, engine, addressOf,
                    dumpNames[step]
                        ?.let { run.region(it, "ewram") },
                )
            }
        }
        assertEquals(4891, ended, "animation proc ends")

        // The round procs, by their captured start frames.
        val roundStarts = (4485..4891).filter { step ->
            dumpAt(step).find("ProcScr_MapAnimDefaultItemEffect") != null &&
                dumpAt(step - 1).find("ProcScr_MapAnimDefaultItemEffect") == null
        }
        assertEquals(listOf(4485, 4544, 4590), roundStarts, "round frames")

        // The round proc's commands that leave no mark on the proc: the captured frames of the
        // command after each one (scrCur 11: the camera ran; 13: the hit ran).
        fun firstStep(round: Int, scrCur: Int) = ROUND_WINDOWS[round].first {
            dumpAt(it).find("ProcScr_MapAnimDefaultItemEffect")!!.scrCur == scrCur
        }
        val cameraSteps = ROUND_WINDOWS.indices.map { firstStep(it, 11) }
        val hitSteps = ROUND_WINDOWS.indices.map { firstStep(it, 13) }
        assertEquals(
            cameraSteps, cameraCalls.filter { it[0] in 4485..4627 }.map { it[0] }, "camera frames",
        )
        // The camera goes onto the target's tile: Soldier O'Neill, Seth in the retaliation.
        val unitTiles = listOf(GBATTLE_TARGET, GBATTLE_ACTOR, GBATTLE_TARGET).map {
            intArrayOf(ewram[it - EWRAM + 0x10].toInt(), ewram[it - EWRAM + 0x11].toInt())
        }
        assertEquals(
            unitTiles.map { it.toList() },
            cameraCalls.filter { it[0] in 4485..4627 }.map { listOf(it[1], it[2]) },
            "camera tiles",
        )
        // StartMuSpeedUpAnim: the clock 0x40 of the subject's sprite animation at each round start.
        val roundSubjects = listOf(0, 1, 0)
        assertEquals(
            roundStarts.map { it },
            speedUps.filter { it[0] in 4485..4627 }.map { it[0] },
            "speed-up frames",
        )
        for ((r, call) in speedUps.filter { it[0] in 4485..4627 }.withIndex()) {
            assertEquals(0x40, call[2], "speed-up clock $r")
            assertEquals(
                muHandles[roundSubjects[r]], call[1],
                "speed-up MOVEUNIT $r",
            )
        }
        // The miss of round 2 (Seth dodges): the MISS text is asked for at the hit frame.
        assertEquals(
            listOf(listOf(hitSteps[1], GBATTLE_ACTOR)),
            missCalls.map { it.toList() },
            "miss call",
        )

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

        // Popups after exp bar: no broken weapon popup or weapon rank increase popup triggered.
        assertEquals(emptyList<Int>(), brokenCalls, "no weapon broke popups")
        assertEquals(emptyList<Int>(), rankUpCalls, "no rank up popups")
        assertFalse(api.battleUnitShouldDisplayWpnBroke(battle.actor), "actor should not display wpn broke")
        assertFalse(api.battleUnitShouldDisplayWpnBroke(battle.target), "target should not display wpn broke")
        assertFalse(api.battleUnitShouldDisplayWRankUp(battle.actor), "actor should not display rank up")
        assertFalse(api.battleUnitShouldDisplayWRankUp(battle.target), "target should not display rank up")
        assertFalse(battle.didBattleUnitBreakWeapon(battle.actor), "actor did not break weapon")
        assertFalse(battle.didBattleUnitBreakWeapon(battle.target), "target did not break weapon")
        assertFalse(battle.hasBattleUnitGainedWeaponLevel(battle.actor), "actor did not gain weapon level")
    }

    @Test
    @VerifiesPort(
        "DidBattleUnitBreakWeapon",
        "HasBattleUnitGainedWeaponLevel",
        "GetWeaponLevelFromExp",
        "GetItemAwardedExp",
        "BattleUnit_ShouldDisplayWpnBroke",
        "BattleUnit_ShouldDisplayWRankUp",
        "DisplayWpnBrokePopup",
        "DisplayWRankUpPopup",
    )
    fun `map anim popup checks and helpers reproduce captured trace steps f4880 to f4892`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val names = ProcArrayDump.romNames()
        val runClear = ref.run(RUN_CLEAR)
        val items = ItemInfo(rom)

        val bactorWatch = runClear.watch("bactor")
        val btargetWatch = runClear.watch("btarget")
        val procsWatch = runClear.watch("procs")

        val table = UnitTable(rom)
        val playSt = PlaySt()
        val bm = BmSt()
        val rng = RngService()
        val loader = UnitLoader(table, rom, { 0 }, rng, { false })
        val battle = BmBattle(rom, table, loader, playSt, bm) { _, _ -> 0 }
        val gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics())
        val engine = ProcEngine()
        val camMove = CamMove(engine, bm)
        val mu = Mu(engine, bm, playSt, KeyStatus(), camMove.script, ClassHooks(rom))
        val api = MapAnimApi(engine, bm, battle, gen, table, loader, mu, rom, null, camMove)

        val actorBytes4880 = bactorWatch.step(4880)
        val targetBytes4880 = btargetWatch.step(4880)
        System.arraycopy(actorBytes4880, 0, battle.actor.bytes, 0, BattleUnit.SIZE)
        System.arraycopy(targetBytes4880, 0, battle.target.bytes, 0, BattleUnit.SIZE)

        // 1. Verify GetItemAwardedExp
        assertEquals(1, items.getItemAwardedExp(battle.actor.weapon), "actor weapon wexp gain")
        assertEquals(1, items.getItemAwardedExp(battle.actor.weaponBefore), "actor weaponBefore wexp gain")

        // 2. Verify GetWeaponLevelFromExp
        assertEquals(ItemInfo.WPN_LEVEL_A, items.getWeaponLevelFromExp(181), "wexp 181 is rank A")
        assertEquals(ItemInfo.WPN_LEVEL_A, items.getWeaponLevelFromExp(182), "wexp 182 is rank A")
        assertEquals(ItemInfo.WPN_LEVEL_E, items.getWeaponLevelFromExp(1), "wexp 1 is rank E")
        assertEquals(ItemInfo.WPN_LEVEL_E, items.getWeaponLevelFromExp(3), "wexp 3 is rank E")

        // 3. Verify DidBattleUnitBreakWeapon
        assertFalse(battle.didBattleUnitBreakWeapon(battle.actor), "actor curHP > 0 and broke == 0")
        assertFalse(battle.didBattleUnitBreakWeapon(battle.target), "target curHP == 0")

        // 4. Verify HasBattleUnitGainedWeaponLevel
        assertFalse(battle.hasBattleUnitGainedWeaponLevel(battle.actor), "actor rank A did not increase")

        // 5. Verify BattleUnit_ShouldDisplayWpnBroke
        assertFalse(api.battleUnitShouldDisplayWpnBroke(battle.actor), "actor blue did not break")
        assertFalse(api.battleUnitShouldDisplayWpnBroke(battle.target), "target red not blue faction")

        // 6. Verify BattleUnit_ShouldDisplayWRankUp
        assertFalse(api.battleUnitShouldDisplayWRankUp(battle.actor), "actor blue did not rank up")
        assertFalse(api.battleUnitShouldDisplayWRankUp(battle.target), "target red not blue faction")

        // 7. Verify DisplayWpnBrokePopup and DisplayWRankUpPopup do not trigger popups
        val dummyParent = engine.start(ProcScript("DummyParent", listOf(ProcScripts.end())), 1)!!
        var wpnBrokePopupCalled = false
        var wRankUpPopupCalled = false
        api.hooks.newPopupWeaponBroke = { _, _ -> wpnBrokePopupCalled = true }
        api.hooks.newPopupWRankIncrease = { _, _ -> wRankUpPopupCalled = true }

        api.displayWpnBrokePopup(dummyParent)
        assertFalse(wpnBrokePopupCalled, "displayWpnBrokePopup should not trigger popup")

        api.displayWRankUpPopup(dummyParent)
        assertFalse(wRankUpPopupCalled, "displayWRankUpPopup should not trigger popup")

        // 8. Evaluation on prologue_enemy_phase at step 1700 where target is blue faction
        val runEnemy = ref.run(RUN_ENEMY)
        val actorEnemy1700 = runEnemy.watch("bactor").step(1700)
        val targetEnemy1700 = runEnemy.watch("btarget").step(1700)
        System.arraycopy(actorEnemy1700, 0, battle.actor.bytes, 0, BattleUnit.SIZE)
        System.arraycopy(targetEnemy1700, 0, battle.target.bytes, 0, BattleUnit.SIZE)

        assertEquals(2, items.getItemAwardedExp(battle.target.weapon), "target weapon wexp gain")
        assertFalse(battle.didBattleUnitBreakWeapon(battle.target), "target curHP=16, broke=0")
        assertFalse(battle.didBattleUnitBreakWeapon(battle.actor), "enemy actor curHP=0")
        assertFalse(battle.hasBattleUnitGainedWeaponLevel(battle.target), "target wexp 1->3 stays rank E")
        assertFalse(api.battleUnitShouldDisplayWpnBroke(battle.target), "target blue did not break")
        assertFalse(api.battleUnitShouldDisplayWpnBroke(battle.actor), "enemy red not blue faction")
        assertFalse(api.battleUnitShouldDisplayWRankUp(battle.target), "target blue did not rank up")
        assertFalse(api.battleUnitShouldDisplayWRankUp(battle.actor), "enemy red not blue faction")

        api.displayWpnBrokePopup(dummyParent)
        assertFalse(wpnBrokePopupCalled, "enemy phase: no broke weapon popup")
        api.displayWRankUpPopup(dummyParent)
        assertFalse(wRankUpPopupCalled, "enemy phase: no rank up popup")

        // 9. Verify step-by-step proc states in prologue_clear f4880..f4892:
        // Confirming that no popup procs exist in the capture and the animation ends cleanly.
        for (step in 4880..4892) {
            val dump = ProcArrayDump.ofWatch(procsWatch, step, names)
            val popup = dump.find("ProcScr_Popup")
            assertEquals(null, popup, "step $step has no popup proc")
            if (step in 4880..4889) {
                val animProc = checkNotNull(dump.find("ProcScr_MapAnimBattle")) {
                    "step $step has map anim proc"
                }
                assertTrue(animProc.scrCur in 33..37, "step $step script command in range 33..37")
            } else if (step >= 4891) {
                assertEquals(null, dump.find("ProcScr_MapAnimBattle"), "step $step map anim proc ended")
            }
        }
    }

    /**
     * Smoke test, no capture ground truth: the first hit of the Prologue battle made critical
     * runs through the round proc to its end without throwing, and asks for the crit effects.
     */
    @Test
    fun `smoke - a critical hit round runs through the round proc`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val run = ref.run(RUN_CLEAR)
        val dumpNames = run.dumps.entries.associate { it.value to it.key }
        val ewram = run.region(dumpNames.getValue(4280), "ewram")
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
        gen.hitArray[0] = gen.hitArray[0] or BattleGenerate.ATTR_CRIT
        bm.lock = 0

        val engine = ProcEngine()
        val camMove = CamMove(engine, bm)
        val mu = Mu(engine, bm, playSt, KeyStatus(), camMove.script, ClassHooks(rom), rom)
        val api = MapAnimApi(engine, bm, battle, gen, table, loader, mu, rom, null, camMove)
        api.hooks.ensureCameraOntoPosition = { _, _, _ -> false }
        var crits = 0
        var shakes = 0
        api.hooks.startMuCritFlash = { _, _ -> crits++ }
        api.hooks.newBg0Shaker = { shakes++ }
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
        var seen = false
        var ended = false
        for (frame in 0 until 400) {
            for (tree in 0 until 8) engine.run(engine.root(tree))
            api.state.hpChanging = 0 // no info box here: the drain is over at once
            val round = engine.find(api.roundScript)
            if (round != null) seen = true
            if (seen && round == null) {
                ended = true
                break
            }
        }
        assertTrue(seen && ended, "the round proc runs and ends")
        assertEquals(1, crits, "crit flash")
        assertEquals(1, shakes, "screen shake")
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

    private fun messagesOf(romBytes: ByteArray): MsgStrings {
        val root = u32(romBytes, 0x0815D488 - 0x08000000) and 0x01FFFFFF
        return MsgStrings(
            romBytes,
            MsgHuffman(romBytes, 0x0815A72C - 0x08000000, root),
            0x0815D48C - 0x08000000,
        )
    }

    /** A text system over [hw] as the info box draws its names and numbers. */
    private fun hwText(rom: RomView, romBytes: ByteArray, hw: BgHardware): TextSystem {
        val host = object : TextHost {
            override fun language(): Int = 1
            override fun applyTextPalette(talk: Boolean, palid: Int) {
                val palAddr = 0x0859EF20 + if (talk) 0x20 else 0
                for (i in 0 until 16) {
                    hw.palette[palid * 16 + i] = rom.u16(palAddr + 2 * i).toShort()
                }
                hw.palette[palid * 16] = 0
                hw.enablePaletteSync()
            }
            override fun readVram32(addr: Int): Int = u32(hw.vram, addr - 0x06000000)
            override fun writeVram32(addr: Int, value: Int) {
                for (k in 0..3) hw.vram[addr - 0x06000000 + k] = (value shr (8 * k)).toByte()
            }
            override fun clearVram(dest: Int, byteCount: Int) {
                hw.vram.fill(0, dest - 0x06000000, dest - 0x06000000 + byteCount)
            }
        }
        return TextSystem(romBytes, host, Fe8Text.CONFIG).also { it.resetText() }
    }

    /**
     * One frame of a round: the round proc's position and sleep timer, the MOVEUNIT positions,
     * the hit flash proc and, in a frame with an EWRAM dump, the HP fields of `gManimSt`.
     */
    private fun checkRoundStep(
        step: Int,
        dump: ProcArrayDump,
        mine: Proc?,
        api: MapAnimApi,
        mu: Mu,
        engine: ProcEngine,
        addressOf: (String) -> Int,
        ewram: ByteArray?,
    ) {
        val at = "$RUN_CLEAR step $step"
        val original = dump.find("ProcScr_MapAnimDefaultItemEffect")
        assertEquals(original == null, mine == null, "$at round proc exists")
        if (original != null && mine != null) {
            assertEquals(
                original.u32(4),
                addressOf("ProcScr_MapAnimDefaultItemEffect") + 8 * mine.scrCur,
                "$at round proc position",
            )
            assertEquals(original.u8(0x24), mine.sleepTime, "$at round proc sleep timer")
        }
        for (c in dump.findAll(addressOf("ProcScr_Mu"))) {
            val m = (0 until 2).map { api.state.mu[it]!!.state as MuProc }
                .first { it.unitAddress == c.u32(0x2C) }
            val what = "$at MOVEUNIT ${c.u32(0x2C).toString(16)}"
            assertEquals(c.u16(0x4C).toShort().toInt(), m.xQ4, "$what x_q4")
            assertEquals(c.u16(0x4E).toShort().toInt(), m.yQ4, "$what y_q4")
            assertEquals(c.u16(0x50).toShort().toInt(), m.xOffsetQ4, "$what x_offset_q4")
            assertEquals(c.u16(0x52).toShort().toInt(), m.yOffsetQ4, "$what y_offset_q4")
        }
        val flash = dump.find("ProcScr_MuHitFlash")
        val myFlash = engine.find(mu.hitFlashScript)
        assertEquals(flash == null, myFlash == null, "$at hit flash exists")
        if (flash != null && myFlash != null) {
            assertEquals(flash.u8(0x24), myFlash.sleepTime, "$at hit flash sleep timer")
        }
        if (ewram != null) {
            for (i in 0 until 2) {
                val actor = MANIM_ST + i * 0x14
                assertEquals(
                    ewram[actor + 0x0D].toInt() and 0xFF, api.state.hpCur[i], "$at hp_cur $i",
                )
                assertEquals(
                    u16(ewram, actor + 0x0E), api.state.hpDisplayedQ4[i], "$at hp_displayed_q4 $i",
                )
            }
            assertEquals(
                ewram[MANIM_ST + 0x5F].toInt(), api.state.hpChanging, "$at hp_changing",
            )
        }
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

    private fun u16(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

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
        val ROUND_WINDOWS = listOf(4485..4535, 4544..4581, 4590..4627)
        val ROUND_CHECK_STEPS = ROUND_WINDOWS.flatMap { it }.toSet()
        const val MANIM_ST = 0x3E1F0 // gManimSt, as an EWRAM offset
        const val GBMST = 0x0202BCB0
        const val GPLAYST = 0x0202BCF0
        const val GBATTLESTATS = 0x0203A4D4
        const val GBATTLE_ACTOR = 0x0203A4EC
        const val GBATTLE_TARGET = 0x0203A56C
        const val GHITARRAY = 0x0203A5EC
    }
}
