package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import fe8.game.event.EventConst
import fe8.game.event.EventEngineProc
import fe8.game.event.EventState
import gba.core.sram.SaveStorage
import gba.testkit.ReferenceData
import gba.testkit.WatchTrace
import gbafe.core.rng.RngService
import java.io.File
import java.util.TreeMap
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * End-to-end `Fe8Boot` replay of `ram_trace_prologue_clear` (`EP-cap2`) from the Prologue
 * suspend save through Turn 1 Enemy Phase (`EventScr_Prologue_OneEnemyLeft`), Turn 2 Enemy Phase
 * (`EventScr_Prologue_ONeillAttack` and `CHAI` AI transition `6, 1, 3, 1` -> `0, 0, 0, 0`), boss
 * movement to `(9, 6)`, Turn 3 Player Phase, and Turn 3 Enemy Phase boss attack.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
class PrologueBossEventWireTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val romFile = File(assets, "baserom.gba")

    private var boot: Fe8Boot? = null
    private var procsWatch: WatchTrace? = null
    private var flagsWatch: WatchTrace? = null
    private var playStWatch: WatchTrace? = null
    private var redWatch: WatchTrace? = null
    private var blueWatch: WatchTrace? = null
    private var evGlobalsWatch: WatchTrace? = null

    /** Releases cached boot and watch references after all tests in this class finish. */
    @AfterAll
    fun release() {
        boot = null
        procsWatch = null
        flagsWatch = null
        playStWatch = null
        redWatch = null
        blueWatch = null
        evGlobalsWatch = null
    }

    private fun available(): Boolean =
        ref.available && romFile.isFile && ProcArrayDump.romNames().isNotEmpty()

    private fun u16(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)

    private fun compareUnits(
        boot: Fe8Boot,
        ewram: ByteArray,
        tag: String,
    ): List<String> {
        val want = UnitTable(RomView.ofRom(ByteArray(0))).also { it.loadFrom(ewram) }
        val diffs = ArrayList<String>()
        for (id in 1..0xBF) {
            if (id == 0x40) continue
            if (want.isValid(id) != boot.units.isValid(id)) {
                diffs += "$tag unit 0x${id.toString(16)} valid got ${boot.units.isValid(id)} " +
                    "want ${want.isValid(id)}"
                continue
            }
            if (!want.isValid(id)) continue
            val o = want.unitOffset(id)
            for (i in 0 until UnitTable.UNIT_SIZE) {
                if (i in 0x3C..0x3F) continue // sprite handle pointer (RAM address, not state)
                val w = want.bytes[o + i].toInt() and 0xFF
                val g = boot.units.bytes[o + i].toInt() and 0xFF
                if (w != g) {
                    diffs += "$tag unit 0x${id.toString(16)}+0x${i.toString(16)} " +
                        "got %02X want %02X".format(g, w)
                }
            }
        }
        return diffs
    }

    private fun bossAi(u: UnitTable): List<Int> =
        listOf(u.ai1(BOSS_ID), u.aiAPc(BOSS_ID), u.ai2(BOSS_ID), u.aiBPc(BOSS_ID))

    private fun compareBmMapUnit(
        map: BmMap,
        ewram: ByteArray,
        tag: String,
    ): List<String> {
        val ptr = u32(ewram, ADDR_MAP_UNIT - EWRAM)
        val diffs = ArrayList<String>()
        for (y in 0 until map.sizeY) {
            val row = u32(ewram, ptr + 4 * y - EWRAM) - EWRAM
            for (x in 0 until map.sizeX) {
                val want = ewram[row + x].toInt() and 0xFF
                val got = map.unit[y, x]
                if (got != want) diffs += "$tag gBmMapUnit($x,$y) got $got want $want"
            }
        }
        return diffs
    }

    private fun compareFlags(
        boot: Fe8Boot,
        flags: WatchTrace,
        capStep: Int,
        tag: String,
    ): List<String> {
        val want = flags.step(capStep)
        val got = ByteArray(want.size).also { boot.flags.copyOut(it) }
        val diffs = ArrayList<String>()
        for (i in want.indices) {
            if (got[i] != want[i]) {
                diffs += "$tag flag byte $i got %02X want %02X".format(got[i], want[i])
            }
        }
        return diffs
    }

    private fun comparePlaySt(
        boot: Fe8Boot,
        playSt: WatchTrace,
        capStep: Int,
        tag: String,
    ): List<String> {
        val want = PlaySt().also { it.decode(playSt.step(capStep), 0) }
        val got = boot.control.playSt
        val diffs = ArrayList<String>()
        fun chk(name: String, g: Int, w: Int) {
            if (g != w) diffs += "$tag gPlaySt.$name got $g want $w"
        }
        chk("chapterIndex", got.chapterIndex, want.chapterIndex)
        chk("chapterTurnNumber", got.chapterTurnNumber, want.chapterTurnNumber)
        chk("faction", got.faction, want.faction)
        chk("chapterStateBits", got.chapterStateBits, want.chapterStateBits)
        chk("tutorialCounter", got.tutorialCounter, want.tutorialCounter)
        chk("tutorialExecType", got.tutorialExecType, want.tutorialExecType)
        chk("controller", got.controller, want.controller)
        return diffs
    }

    private fun compareSlots(
        boot: Fe8Boot,
        evGlobals: WatchTrace,
        capStep: Int,
        tag: String,
    ): List<String> {
        val g = evGlobals.step(capStep)
        val diffs = ArrayList<String>()
        for (i in 1 until EventState.SLOT_COUNT) {
            val want = u32(g, EV_SLOTS_OFFSET + 4 * i)
            val got = boot.eventState.slots[i]
            if (got != want) {
                diffs += "$tag slot$i got 0x${got.toString(16)} want 0x${want.toString(16)}"
            }
        }
        return diffs
    }

    @Test
    fun `prologue clear replay runs one-enemy-left event, boss turn event CHAI, and boss move`() {
        assumeTrue(available(), "reference data / ROM / nm file not present")
        val names = ProcArrayDump.romNames()
        val run = ref.run(RUN_CLEAR)
        assumeTrue(
            run.hasWatch("procs") && run.hasWatch("flags") &&
                run.hasWatch("gplayst") && run.hasWatch("red") &&
                run.hasWatch("blue") && run.hasWatch("evglobals"),
            "$RUN_CLEAR watches missing",
        )
        val procs = run.watch("procs").also { procsWatch = it }
        val flags = run.watch("flags").also { flagsWatch = it }
        val playSt = run.watch("gplayst").also { playStWatch = it }
        val red = run.watch("red").also { redWatch = it }
        blueWatch = run.watch("blue")
        val evGlobals = run.watch("evglobals").also { evGlobalsWatch = it }

        val idleFrame = (1100 until 1525).first {
            ProcArrayDump.ofWatch(procs, it, names).find("gProcScr_PlayerPhase")?.scrCur == 16
        }
        val inputs = Array<Any>(run.inputs.size) { run.inputs[it] }
        val pulse = arrayOf<Any>(0, 1)
        val save = ref.run(RUN_RESUME).sram("s01001")

        var frame = 0
        var idleAt = -1
        val b = Fe8Boot(
            RomView.ofRom(romFile.readBytes()),
            @Suppress("UNCHECKED_CAST")
            ({
                if (idleAt < 0) pulse[if (frame % 20 in 5..8) 1 else 0]
                else inputs.getOrElse(frame - idleAt + idleFrame) { 0 }
            } as () -> Int),
            RngService(),
            saveStorage = object : SaveStorage {
                override fun load(dest: ByteArray): Boolean {
                    save.copyInto(dest)
                    return true
                }

                override fun store(src: ByteArray) {}
            },
        )
        boot = b

        val stubs = TreeMap<Int, Int>()
        b.onEventStub = { code -> stubs.merge(code, 1, Int::plus) }

        var prevEvStart = 0
        var oneEnemyLeftStartedAt = -1
        var oneEnemyLeftEndedAt = -1
        var oneEnemyLeftTalkSeen = false
        var flag7SetAt = -1
        var flag8ClearedAfterOneEnemyLeft = false
        var bossEventStartedAt = -1
        var bossEventTalkSeen = false
        var bossEventFaceSeen = false
        var bossEventSongId = -1
        var bossAiChangedAt = -1
        var bossEventEndedAt = -1
        var bossCpDecideSeen = false
        var bossCpPerformSeen = false
        var bossWalkedAt = -1
        var turn2IdleAt = -1
        var turn3StartedAt = -1
        var turn3IdleAt = -1
        var endingStartedAt = -1

        var unitDiffs2721: List<String>? = null
        var flagDiffs2721: List<String>? = null
        var unitDiffs2964: List<String>? = null
        var flagDiffs2964: List<String>? = null
        var playStDiffs2964: List<String>? = null
        var unitDiffs3719: List<String>? = null
        var flagDiffs3719: List<String>? = null
        var slotDiffs3719: List<String>? = null
        var unitDiffs3721: List<String>? = null
        var unitDiffs3824: List<String>? = null
        var mapDiffs3824: List<String>? = null
        var unitDiffs3943: List<String>? = null
        var flagDiffs3943: List<String>? = null
        var playStDiffs3943: List<String>? = null
        var mapDiffs3943: List<String>? = null

        var prevAi = listOf(-1, -1, -1, -1)
        var prevBossPos = -1 to -1

        while (frame < 5800) {
            b.frame()
            frame++
            if (idleAt < 0) {
                if (b.engine.find(b.playerPhaseScript)?.scrCur == 16) idleAt = frame
                continue
            }
            val step = frame - idleAt + idleFrame
            if (step > 5000) break

            val ps = b.control.playSt
            val evProc = b.eventEngine.findStd()
            val evState = evProc?.state as? EventEngineProc
            val evStart = evState?.pEventStart ?: 0

            if (evStart != prevEvStart) {
                if (evStart == EVENT_ONE_ENEMY_LEFT &&
                    evState?.execType == EventConst.EV_EXEC_CUTSCENE
                ) {
                    oneEnemyLeftStartedAt = step
                }
                if (prevEvStart == EVENT_ONE_ENEMY_LEFT && evStart == 0) {
                    oneEnemyLeftEndedAt = step
                    flag8ClearedAfterOneEnemyLeft = !b.flags.checkFlag(FLAG_ONEILL_ATTACK)
                    unitDiffs2721 = compareUnits(b, run.region("r02730", "ewram"), "r02730")
                    flagDiffs2721 = compareFlags(b, flags, 2722, "f2722")
                }
                if (evStart == EVENT_ONEILL_ATTACK) {
                    bossEventStartedAt = step
                    val wantEv = ProcArrayDump.ofWatch(procs, 2964, names)
                        .find("ProcScr_StdEventEngine")!!
                    assertEquals(wantEv.u32(0x30), evState?.pEventStart)
                    assertEquals(wantEv.u32(0x38), evState?.pEventCurrent)
                    assertEquals(wantEv.u8(0x41), evState?.execType)
                    assertEquals(wantEv.u16(0x3C), evState?.evStateBits)
                    assertEquals(wantEv.u8(0x43), evState?.chapterIndex)
                    assertEquals(0x80, ps.faction)
                    assertEquals(2, ps.chapterTurnNumber)
                    assertTrue(b.flags.checkFlag(FLAG_ONEILL_ATTACK), "flag 8 set at boss start")
                    unitDiffs2964 = compareUnits(b, run.region("r02964", "ewram"), "r02964")
                    flagDiffs2964 = compareFlags(b, flags, 2964, "f2964")
                    playStDiffs2964 = comparePlaySt(b, playSt, 2964, "f2964")
                }
                if (prevEvStart == EVENT_ONEILL_ATTACK && evStart == 0) {
                    bossEventEndedAt = step
                    unitDiffs3721 = compareUnits(b, run.region("r03722", "ewram"), "r03722")
                }
                if (evStart == EVENT_PROLOGUE_ENDING) {
                    endingStartedAt = step
                    break
                }
                prevEvStart = evStart
            }

            if (flag7SetAt < 0 && b.flags.checkFlag(FLAG_ONE_ENEMY_LEFT)) {
                flag7SetAt = step
            }

            val hasTalk = b.engine.procs.any { it.script?.name == "gProcScr_Talk" }
            if (evStart == EVENT_ONE_ENEMY_LEFT &&
                evState?.execType == EventConst.EV_EXEC_CUTSCENE &&
                hasTalk
            ) {
                oneEnemyLeftTalkSeen = true
            }
            if (evStart == EVENT_ONEILL_ATTACK && hasTalk) {
                bossEventTalkSeen = true
                if (b.engine.procs.any { it.script?.name == "gProcScr_E_FACE" }) {
                    bossEventFaceSeen = true
                }
                bossEventSongId = b.control.sound.songId
            }

            val ai = bossAi(b.units)
            if (ai != prevAi) {
                if (prevAi == listOf(6, 1, 3, 1) && ai == listOf(0, 0, 0, 0)) {
                    bossAiChangedAt = step
                    unitDiffs3719 = compareUnits(b, run.region("r03719", "ewram"), "r03719")
                    flagDiffs3719 = compareFlags(b, flags, 3719, "f3719")
                    slotDiffs3719 = compareSlots(b, evGlobals, 3719, "f3719")
                }
                prevAi = ai
            }

            if (ps.chapterTurnNumber == 2 && ps.faction == 0x80) {
                if (b.engine.procs.any { it.script?.name == "gProcScr_CpDecide" }) {
                    bossCpDecideSeen = true
                }
                if (b.engine.procs.any { it.script?.name == "gProcScr_CpPerform" }) {
                    bossCpPerformSeen = true
                }
            }

            val pos = b.units.xPos(BOSS_ID) to b.units.yPos(BOSS_ID)
            if (pos != prevBossPos) {
                if (pos == 9 to 6) {
                    bossWalkedAt = step
                    val r3831 = run.region("r03831", "ewram")
                    unitDiffs3824 = compareUnits(b, r3831, "r03831")
                    mapDiffs3824 = compareBmMapUnit(b.battleBmMap, r3831, "r03831")
                }
                prevBossPos = pos
            }

            val idle = b.engine.find(b.playerPhaseScript)?.scrCur == 16
            if (ps.chapterTurnNumber == 2 && ps.faction == 0 && idle && turn2IdleAt < 0) {
                turn2IdleAt = step
            }
            if (ps.chapterTurnNumber == 3 && ps.faction == 0 && turn3StartedAt < 0) {
                turn3StartedAt = step
            }
            if (ps.chapterTurnNumber == 3 && ps.faction == 0 && idle && turn3IdleAt < 0) {
                turn3IdleAt = step
                val f3960 = run.region("f03960", "ewram")
                unitDiffs3943 = compareUnits(b, f3960, "f03960")
                flagDiffs3943 = compareFlags(b, flags, 3960, "f03960")
                playStDiffs3943 = comparePlaySt(b, playSt, 3960, "f03960")
                mapDiffs3943 = compareBmMapUnit(b.battleBmMap, f3960, "f03960")
            }
        }

        assertEquals(2386, oneEnemyLeftStartedAt)
        assertEquals(2386, flag7SetAt)
        assertTrue(oneEnemyLeftTalkSeen, "OneEnemyLeft ran gProcScr_Talk")
        assertTrue(
            oneEnemyLeftEndedAt > 2700,
            "OneEnemyLeft ended after A press: $oneEnemyLeftEndedAt",
        )
        assertTrue(flag8ClearedAfterOneEnemyLeft, "OneEnemyLeft cleared EVFLAG_TMP(8)")
        assertEquals(emptyList<String>(), unitDiffs2721, "units after OneEnemyLeft")
        assertEquals(emptyList<String>(), flagDiffs2721, "flags after OneEnemyLeft")

        assertTrue(turn2IdleAt in 2835..2860, "Turn 2 idle at $turn2IdleAt")
        assertEquals(2964, bossEventStartedAt)
        assertTrue(bossEventTalkSeen, "boss turn event ran gProcScr_Talk")
        assertTrue(bossEventFaceSeen, "boss turn event ran gProcScr_E_FACE")
        assertEquals(
            SONG_SHADOW_OF_THE_ENEMY,
            bossEventSongId,
            "MUSC set SONG_SHADOW_OF_THE_ENEMY",
        )
        assertEquals(3719, bossAiChangedAt)
        assertTrue(bossEventEndedAt in 3720..3723, "boss turn event ended at $bossEventEndedAt")
        assertTrue(bossCpDecideSeen, "gProcScr_CpDecide ran in Enemy Phase 2")
        assertTrue(bossCpPerformSeen, "gProcScr_CpPerform ran in Enemy Phase 2")
        assertTrue(bossWalkedAt in 3820..3835, "boss walked to (9, 6) at $bossWalkedAt")
        assertTrue(turn3StartedAt in 3860..3885, "Turn 3 started at $turn3StartedAt")
        assertTrue(turn3IdleAt in 3930..3970, "Turn 3 idle reached at $turn3IdleAt")
        assertTrue(
            endingStartedAt > 4800,
            "clear replay reached Prologue ending at $endingStartedAt",
        )

        assertEquals(emptyList<String>(), unitDiffs2964, "units at f2964")
        assertEquals(emptyList<String>(), flagDiffs2964, "flags at f2964")
        assertEquals(emptyList<String>(), playStDiffs2964, "playSt at f2964")
        assertEquals(emptyList<String>(), unitDiffs3719, "units at f3719")
        assertEquals(emptyList<String>(), flagDiffs3719, "flags at f3719")
        assertEquals(emptyList<String>(), slotDiffs3719, "slots at f3719")
        assertEquals(emptyList<String>(), unitDiffs3721, "units at f3722")
        assertEquals(emptyList<String>(), unitDiffs3824, "units at f3831")
        assertEquals(emptyList<String>(), mapDiffs3824, "gBmMapUnit at f3831")
        assertEquals(emptyList<String>(), unitDiffs3943, "units at Turn 3 idle")
        assertEquals(emptyList<String>(), flagDiffs3943, "flags at Turn 3 idle")
        assertEquals(emptyList<String>(), playStDiffs3943, "playSt at Turn 3 idle")
        assertEquals(emptyList<String>(), mapDiffs3943, "gBmMapUnit at Turn 3 idle")
        assertEquals(emptyMap<Int, Int>(), stubs.toMap(), "event stubs")

        // Verify capture milestones and live state match
        assertEquals(listOf(6, 1, 3, 1), (0x42..0x45).map { red.u8(3718, it) })
        assertEquals(listOf(0, 0, 0, 0), (0x42..0x45).map { red.u8(3719, it) })
        assertEquals(9, red.u8(3831, 0x10))
        assertEquals(6, red.u8(3831, 0x11))
        assertEquals(0, playSt.u8(3877, 0x0F))
        assertEquals(3, playSt.u16(3877, 0x10))
        assertTrue((flags.u8(2403, 32) and 0x40) != 0)
        assertFalse(b.units.isValid(BOSS_ID), "boss defeated in clear replay")
        assertFalse(b.units.isValid(0x82))
        assertFalse(b.units.isValid(0x83))
        assertTrue(b.flags.checkFlag(FLAG_ONEILL_ATTACK))
        assertEquals(listOf(0, 1, 0, 1), bossAi(b.units))
    }

    @Test
    fun `boss attacks player unit in turn 3 enemy phase instead of idling`() {
        assumeTrue(available(), "reference data / ROM / nm file not present")
        val names = ProcArrayDump.romNames()
        val run = ref.run(RUN_CLEAR)
        val procs = run.watch("procs").also { procsWatch = it }
        val idleFrame = (1100 until 1525).first {
            ProcArrayDump.ofWatch(procs, it, names).find("gProcScr_PlayerPhase")?.scrCur == 16
        }
        val inputs = Array<Any>(run.inputs.size) { run.inputs[it] }
        val pulse = arrayOf<Any>(0, 1)
        val save = ref.run(RUN_RESUME).sram("s01001")

        var frame = 0
        var idleAt = -1
        var manualKeys = false
        var key = 0
        val b = Fe8Boot(
            RomView.ofRom(romFile.readBytes()),
            @Suppress("UNCHECKED_CAST")
            ({
                if (manualKeys) key
                else if (idleAt < 0) pulse[if (frame % 20 in 5..8) 1 else 0]
                else inputs.getOrElse(frame - idleAt + idleFrame) { 0 }
            } as () -> Int),
            RngService(),
            saveStorage = object : SaveStorage {
                override fun load(dest: ByteArray): Boolean {
                    save.copyInto(dest)
                    return true
                }

                override fun store(src: ByteArray) {}
            },
        )
        boot = b

        while (frame < 5500) {
            b.frame()
            frame++
            if (idleAt < 0) {
                if (b.engine.find(b.playerPhaseScript)?.scrCur == 16) idleAt = frame
                continue
            }
            val ps = b.control.playSt
            if (ps.chapterTurnNumber == 3 && ps.faction == 0 &&
                b.engine.find(b.playerPhaseScript)?.scrCur == 16
            ) {
                break
            }
        }
        assertEquals(3, b.control.playSt.chapterTurnNumber)
        assertEquals(0, b.control.playSt.faction)
        assertEquals(9 to 6, b.units.xPos(BOSS_ID) to b.units.yPos(BOSS_ID))
        assertEquals(listOf(0, 1, 0, 1), listOf(
            b.units.ai1(BOSS_ID), b.units.aiAPc(BOSS_ID),
            b.units.ai2(BOSS_ID), b.units.aiBPc(BOSS_ID),
        ))

        manualKeys = true
        fun tick() {
            b.frame()
            frame++
        }
        fun press(k: Int, hold: Int = 2, gap: Int = 10) {
            key = k
            repeat(hold) { tick() }
            key = 0
            repeat(gap) { tick() }
        }
        fun cursorTo(x: Int, y: Int) {
            var guard = 0
            while ((b.loop.bmSt.playerCursorX != x || b.loop.bmSt.playerCursorY != y) &&
                guard++ < 200
            ) {
                val cx = b.loop.bmSt.playerCursorX
                val cy = b.loop.bmSt.playerCursorY
                val k = when {
                    cx < x -> 0x10
                    cx > x -> 0x20
                    cy < y -> 0x80
                    else -> 0x40
                }
                press(k, 1, 6)
            }
        }

        // Move Eirika (unit 2) from (4, 5) to (7, 5) (distance 3; boss at (9, 6) must move to
        // (8, 5) and attack), Wait, End Turn.
        val eirikaId = 2
        val eirikaX = b.units.xPos(eirikaId)
        val eirikaY = b.units.yPos(eirikaId)
        cursorTo(eirikaX, eirikaY)
        press(0x01) // select Eirika
        cursorTo(7, 5)
        press(0x01) // move Eirika to (7, 5)
        repeat(40) { tick() }
        press(0x40) // UP wraps to Wait
        press(0x01, 2, 30) // Wait
        assertEquals(7 to 5, b.units.xPos(eirikaId) to b.units.yPos(eirikaId), "Eirika at (7, 5)")

        // Open map menu on empty tile (7, 4) and End Phase
        cursorTo(7, 4)
        press(0x01, 2, 20)
        press(0x40, 1, 10) // UP wraps to End
        press(0x01, 2, 20)

        var sawEp3 = false
        var sawCombat = false
        var sawBattleQuote = false
        var sawBossMovedTo85 = false
        var sawTurn4 = false
        val eirikaHpBefore = b.units.currentHp(eirikaId)
        val bossHpBefore = b.units.currentHp(BOSS_ID)
        var eirikaHpAfterCombat = -1
        var bossHpAfterCombat = -1
        var combatEnded = false

        var guard = 0
        while (guard++ < 2500) {
            val ps = b.control.playSt
            if (b.eventEngine.findStd() != null) break
            val battleQuote = b.eventEngine.battleEventEngineExists() ||
                b.engine.procs.any { it.script?.name == "gProcScr_Talk" }
            if (battleQuote) {
                sawBattleQuote = true
                key = if (guard % 10 in 2..3) 0x01 else 0
            } else {
                key = 0
            }
            tick()
            if (ps.chapterTurnNumber == 3 && ps.faction == 0x80) sawEp3 = true
            val inCombat = b.engine.procs.any { it.script?.name == "sProcScr_CombatAction" }
            if (inCombat) sawCombat = true
            if (sawCombat && !inCombat && !combatEnded) {
                combatEnded = true
                eirikaHpAfterCombat = b.units.currentHp(eirikaId)
                bossHpAfterCombat = b.units.currentHp(BOSS_ID)
            }
            if (b.units.xPos(BOSS_ID) == 8 && b.units.yPos(BOSS_ID) == 5) sawBossMovedTo85 = true
            if (ps.chapterTurnNumber == 4 && ps.faction == 0 &&
                b.engine.find(b.playerPhaseScript)?.scrCur == 16
            ) {
                sawTurn4 = true
                break
            }
        }
        assertTrue(sawEp3, "Turn 3 enemy phase began")
        assertTrue(sawCombat, "boss started combat")
        assertTrue(sawBossMovedTo85, "boss moved from (9, 6) to (8, 5) to attack")
        assertTrue(sawBattleQuote, "boss battle quote ran")
        assertTrue(combatEnded, "combat completed")
        assertTrue(sawTurn4, "Turn 4 player phase idle reached after boss attack")
        assertTrue(
            bossHpAfterCombat < bossHpBefore || eirikaHpAfterCombat < eirikaHpBefore,
            "combat dealt damage",
        )
        assertEquals(listOf(0, 1, 0, 1), bossAi(b.units))
    }

    private companion object {
        const val RUN_CLEAR = "ram_trace_prologue_clear"
        const val RUN_RESUME = "ram_trace_prologue_resume"
        const val BOSS_ID = 0x81
        const val EVENT_ONE_ENEMY_LEFT = 0x089EF0D8
        const val EVENT_ONEILL_ATTACK = 0x089EF12C
        const val EVENT_PROLOGUE_ENDING = 0x089EF164
        const val FLAG_ONE_ENEMY_LEFT = 7
        const val FLAG_ONEILL_ATTACK = 8
        const val SONG_SHADOW_OF_THE_ENEMY = 0x13
        const val EWRAM = 0x02000000
        const val ADDR_MAP_UNIT = 0x0202E4D8
        const val EV_SLOTS_OFFSET = 0x80
    }
}
