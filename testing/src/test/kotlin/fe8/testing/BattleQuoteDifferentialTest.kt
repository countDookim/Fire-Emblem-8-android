package fe8.testing

import fe8.game.RomView
import fe8.game.bm.PlaySt
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineProc
import fe8.game.event.EventInfoHooks
import fe8.game.event.EventInfos
import fe8.game.event.EventState
import fe8.game.event.ScriptMemory
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Battle quotes and defeat events of `src/event.c` and `src/eventinfo.c` (EP-28).
 *
 * Pre-battle quote: `CallBattleQuoteEventsIfAny` > `GetBattleQuoteEntry` >
 * `CallBattleQuoteEventInBattle` at step 4278 of `ram_trace_prologue_clear`. Seth (pid 2) attacks
 * the boss (pid 0x68); `GetBattleQuoteEntry` finds the entry in `gBattleTalkList`, starts
 * `ProcScr_BattleEventEngine` running `EventScr_DisplayBattleQuote` on proc tree 3, writes the
 * message index (0x916) to slot 2 and sets flag 1 (`EVFLAG_BATTLE_QUOTES`). The engine proc
 * remains active through step 4460 and terminates before step 4461.
 *
 * Defeat event: `CheckBattleDefeatTalk` > `GetDefeatTalkEntry` > `DisplayDefeatTalkForPid` and
 * `SetPidDefeatedFlag` at step 4636 of `ram_trace_prologue_clear`. The boss is defeated;
 * `CheckBattleDefeatTalk` returns true, `DisplayDefeatTalkForPid` starts the battle engine proc
 * with slot 2 set to 0x917, and `SetPidDefeatedFlag` sets flag 2 (`EVFLAG_DEFEAT_BOSS`). The engine
 * proc remains active through step 4740 and terminates before step 4741.
 *
 * No-quote calls: at step 1700 and 2133 in both `ram_trace_prologue_clear` and
 * `ram_trace_prologue_enemy_phase`, generic enemies engage Seth; `CallBattleQuoteEventsIfAny`
 * finds no entry, starts no proc, and leaves flags unchanged.
 */
class BattleQuoteDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "CallBattleQuoteEventInBattle", "GetEventTriggerState",
        "CallBattleQuoteEventsIfAny", "GetBattleQuoteEntry",
    )
    fun `pre-battle quote at f4278 starts battle engine proc and sets slot 2 and flag 1`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val run = ref.run(RUN_CLEAR)
        val rom = RomView.ofRom(romFile.readBytes())
        val trace = EventTrace.prologue(ref, RUN_CLEAR)
        assumeTrue(trace != null, "$RUN_CLEAR event trace not present")

        val flags = Fe8EventFlags.create().also {
            it.copyIn(run.watch("flags").step(PRE_BATTLE_STEP - 1))
        }
        val playSt = PlaySt().also {
            it.decode(run.watch("gplayst").step(PRE_BATTLE_STEP - 1), 0)
        }
        val state = EventState()
        val procs = ProcEngine().also { it.init() }
        val engine = EventEngine(procs, state, scriptMemory(rom))
        val infos = EventInfos(scriptMemory(rom), flags, playSt).also {
            it.hooks = object : EventInfoHooks {
                override fun callBattleQuoteEventInBattle(msg: Int) {
                    engine.callBattleQuoteEventInBattle(msg)
                }

                override fun createBattle(event: Int) {
                    engine.createBattle(event)
                }

                override fun unitActionType(): Int = ACTION_COMBAT
            }
        }

        assertFalse(infos.getEventTriggerState(FLAG_BATTLE_QUOTE), "flag 1 clear before")
        assertNull(procs.find(engine.battleScript), "battle engine not running before")

        infos.callBattleQuoteEventsIfAny(PID_SETH, PID_ONEILL)

        assertTrue(infos.getEventTriggerState(FLAG_BATTLE_QUOTE), "flag 1 set after")
        assertEquals(PRE_BATTLE_MSG, state.slots[2], "slot 2 contains message id")
        val battleProc = procs.find(engine.battleScript)
        assertNotNull(battleProc, "battle engine proc started on tree 3")
        val battleState = battleProc!!.state as EventEngineProc
        assertEquals(EVENT_SCR_DISPLAY_BATTLE_QUOTE, battleState.pEventStart)
        assertEquals(EVENT_SCR_DISPLAY_BATTLE_QUOTE, battleState.pEventCurrent)
        assertEquals(1, battleState.evStateBits)
        assertEquals(3, battleState.execType)
        assertEquals(0, battleState.evStallTimer)

        // Compare against captured reference trace at step 4278
        val snap = trace!!.at(PRE_BATTLE_STEP)
        val refEngine = snap.engine
        assertNotNull(refEngine, "captured engine exists at step $PRE_BATTLE_STEP")
        assertEquals(refEngine!!.pEventStart, battleState.pEventStart, "captured pEventStart")
        assertEquals(refEngine.execType, battleState.execType, "captured execType")
        assertEquals(refEngine.evStallTimer, battleState.evStallTimer, "captured evStallTimer")
        assertEquals(snap.slots[2], state.slots[2], "captured slot 2")
        val flagBytes = ByteArray(0x30).also { flags.copyOut(it) }
        assertEquals(
            run.watch("flags").step(PRE_BATTLE_STEP)[0x20].toInt() and 1,
            flagBytes[0x20].toInt() and 1,
            "captured flag 1",
        )

        // Verify BattleEventEngine lifetime per step f4278..4461 in ram_trace_prologue_clear
        for (step in PRE_BATTLE_STEP until PRE_BATTLE_END) {
            val s = trace.at(step)
            assertNotNull(s.engine, "BattleEventEngine exists at step $step")
            assertEquals(PRE_BATTLE_MSG, s.slots[2], "slot 2 is $PRE_BATTLE_MSG at step $step")
        }
        assertNull(
            trace.at(PRE_BATTLE_END).engine,
            "BattleEventEngine ended at step $PRE_BATTLE_END",
        )
    }

    @Test
    @VerifiesPort(
        "CheckBattleDefeatTalk", "DisplayDefeatTalkForPid",
        "GetDefeatTalkEntry", "SetPidDefeatedFlag",
    )
    fun `defeat event at f4636 starts battle engine proc and sets boss defeat flag`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val run = ref.run(RUN_CLEAR)
        val rom = RomView.ofRom(romFile.readBytes())
        val trace = EventTrace.prologue(ref, RUN_CLEAR)
        assumeTrue(trace != null, "$RUN_CLEAR event trace not present")

        val flags = Fe8EventFlags.create().also {
            it.copyIn(run.watch("flags").step(DEFEAT_STEP - 1))
        }
        val playSt = PlaySt().also {
            it.decode(run.watch("gplayst").step(DEFEAT_STEP - 1), 0)
        }
        val state = EventState()
        val procs = ProcEngine().also { it.init() }
        val engine = EventEngine(procs, state, scriptMemory(rom))
        val infos = EventInfos(scriptMemory(rom), flags, playSt).also {
            it.hooks = object : EventInfoHooks {
                override fun callBattleQuoteEventInBattle(msg: Int) {
                    engine.callBattleQuoteEventInBattle(msg)
                }

                override fun createBattle(event: Int) {
                    engine.createBattle(event)
                }

                override fun getBattleMapKind(): Int = 0 // story map
            }
        }

        assertFalse(infos.getEventTriggerState(FLAG_DEFEAT_BOSS), "flag 2 clear before")
        assertTrue(infos.checkBattleDefeatTalk(PID_ONEILL), "checkBattleDefeatTalk returns true")

        infos.displayDefeatTalkForPid(PID_ONEILL)

        assertTrue(infos.getEventTriggerState(FLAG_DEFEAT_BOSS), "flag 2 set after")
        assertEquals(DEFEAT_MSG, state.slots[2], "slot 2 contains defeat message id")
        val battleProc = procs.find(engine.battleScript)
        assertNotNull(battleProc, "battle engine proc started on tree 3")
        val battleState = battleProc!!.state as EventEngineProc
        assertEquals(EVENT_SCR_DISPLAY_BATTLE_QUOTE, battleState.pEventStart)
        assertEquals(3, battleState.execType)

        // Compare against captured reference trace at step 4636
        val snap = trace!!.at(DEFEAT_STEP)
        val refEngine = snap.engine
        assertNotNull(refEngine, "captured engine exists at step $DEFEAT_STEP")
        assertEquals(refEngine!!.pEventStart, battleState.pEventStart, "captured pEventStart")
        assertEquals(refEngine.execType, battleState.execType, "captured execType")
        assertEquals(snap.slots[2], state.slots[2], "captured slot 2")
        val flagBytes = ByteArray(0x30).also { flags.copyOut(it) }
        assertEquals(
            run.watch("flags").step(DEFEAT_STEP)[0x20].toInt() and 2,
            flagBytes[0x20].toInt() and 2,
            "captured flag 2",
        )

        // Verify BattleEventEngine lifetime per step f4636..4741 in ram_trace_prologue_clear
        for (step in DEFEAT_STEP until DEFEAT_END) {
            val s = trace.at(step)
            assertNotNull(s.engine, "BattleEventEngine exists at step $step")
            assertEquals(DEFEAT_MSG, s.slots[2], "slot 2 is $DEFEAT_MSG at step $step")
        }
        assertNull(trace.at(DEFEAT_END).engine, "BattleEventEngine ended at step $DEFEAT_END")
    }

    @Test
    @VerifiesPort("CallBattleQuoteEventsIfAny", "GetBattleQuoteEntry")
    fun `combat at f1700 and f2133 in prologue clear and enemy phase produces no quote`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())

        for (runName in listOf(RUN_CLEAR, RUN_ENEMY_PHASE)) {
            val run = ref.run(runName)
            val names = ProcArrayDump.romNames()

            for (step in listOf(STEP_SOLDIER_1, STEP_SOLDIER_2)) {
                val flags = Fe8EventFlags.create().also {
                    it.copyIn(run.watch("flags").step(step - 1))
                }
                val playSt = PlaySt().also {
                    it.decode(run.watch("gplayst").step(step - 1), 0)
                }
                val state = EventState()
                val procs = ProcEngine().also { it.init() }
                val engine = EventEngine(procs, state, scriptMemory(rom))
                val infos = EventInfos(scriptMemory(rom), flags, playSt).also {
                    it.hooks = object : EventInfoHooks {
                        override fun callBattleQuoteEventInBattle(msg: Int) {
                            engine.callBattleQuoteEventInBattle(msg)
                        }

                        override fun createBattle(event: Int) {
                            engine.createBattle(event)
                        }

                        override fun unitActionType(): Int = ACTION_COMBAT
                    }
                }

                val enemyPid = if (step == STEP_SOLDIER_1) PID_SOLDIER_1 else PID_SOLDIER_2
                infos.callBattleQuoteEventsIfAny(enemyPid, PID_SETH)

                assertNull(
                    procs.find(engine.battleScript),
                    "no proc started at $runName step $step",
                )
                assertFalse(infos.getEventTriggerState(FLAG_BATTLE_QUOTE), "flag 1 not set")

                // Reference trace confirms BattleEventEngine does not exist at these steps
                val dump = ProcArrayDump.ofWatch(run.watch("procs"), step, names)
                assertNull(
                    dump.find("ProcScr_BattleEventEngine"),
                    "no engine at $runName step $step",
                )
            }
        }
    }

    @Test
    fun `defeated flag and talk entry edge cases follow decomp rules`() {
        assumeTrue(romFile.isFile, "ROM file not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val flags = Fe8EventFlags.create()
        val playSt = PlaySt().also { it.chapterIndex = 0 }
        var mapKind = 0
        var protectPid = 0
        var triangleAttack = false
        var lastBgm = 0

        val infos = EventInfos(scriptMemory(rom), flags, playSt).also {
            it.hooks = object : EventInfoHooks {
                override fun getBattleMapKind(): Int = mapKind
                override fun protectCharacterIndex(chapter: Int): Int = protectPid
                override fun battleIsTriangleAttack(): Boolean = triangleAttack
                override fun startBgm(song: Int) { lastBgm = song }
                override fun unitFaction(pid: Int): Int = if (pid < 0x40) 0x00 else 0x80
            }
        }

        // SetPidDefeatedFlag: on skirmish map with protected pid and flag 0x65, flag is ignored
        mapKind = 2
        protectPid = 0x10
        infos.setPidDefeatedFlag(0x10, 0x65)
        assertFalse(flags.checkFlag(0x65), "protect character death flag 0x65 suppressed")
        infos.setPidDefeatedFlag(0x20, 0x65)
        assertTrue(flags.checkFlag(0x65), "non-protect character sets flag 0x65")
        infos.setPidDefeatedFlag(0x10, 2)
        assertTrue(flags.checkFlag(2), "flag other than 0x65 is set even for protect char")

        // CheckBattleDefeatTalk: generic enemy has no defeat entry
        assertFalse(infos.checkBattleDefeatTalk(PID_SOLDIER_1), "generic enemy has no defeat talk")

        // CheckBattleDefeatTalk: entry with msg == 0 and event == 0 sets flag and returns false
        playSt.chapterIndex = 4
        flags.clearFlag(FLAG_DEFEAT_BOSS)
        assertFalse(infos.checkBattleDefeatTalk(0x49), "msg=0 event=0 defeat talk returns false")
        assertTrue(flags.checkFlag(FLAG_DEFEAT_BOSS), "defeat flag set immediately when msg=0")

        // DisplayDefeatTalkForPid: blue unit death plays sorrow song
        playSt.chapterIndex = 6
        lastBgm = 0
        infos.displayDefeatTalkForPid(0x0D)
        assertEquals(0x3F, lastBgm, "blue unit defeat plays sorrow shroud BGM")

        // GetBattleQuoteEntry: triangle attack chapter 0xFE
        playSt.chapterIndex = 1
        triangleAttack = false
        val noTriangle = infos.getBattleQuoteEntry(0, 0)
        assertNull(noTriangle, "no triangle entry when triangle attack is false")
    }

    private fun scriptMemory(rom: RomView): ScriptMemory = ScriptMemory { a -> rom.u16(a) }

    private companion object {
        const val RUN_CLEAR = "ram_trace_prologue_clear"
        const val RUN_ENEMY_PHASE = "ram_trace_prologue_enemy_phase"

        const val PRE_BATTLE_STEP = 4278
        const val PRE_BATTLE_END = 4461
        const val DEFEAT_STEP = 4636
        const val DEFEAT_END = 4741

        const val STEP_SOLDIER_1 = 1700
        const val STEP_SOLDIER_2 = 2133

        const val PID_SETH = 2
        const val PID_ONEILL = 0x68
        const val PID_SOLDIER_1 = 0x82
        const val PID_SOLDIER_2 = 0x80

        const val FLAG_BATTLE_QUOTE = 1
        const val FLAG_DEFEAT_BOSS = 2

        const val PRE_BATTLE_MSG = 0x0916
        const val DEFEAT_MSG = 0x0917

        const val ACTION_COMBAT = 2
        const val EVENT_SCR_DISPLAY_BATTLE_QUOTE = 0x08591F88
    }
}
