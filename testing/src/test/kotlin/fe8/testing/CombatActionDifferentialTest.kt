package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ActiveUnit
import fe8.game.bm.BattleGenerate
import fe8.game.bm.BattleStats
import fe8.game.bm.BattleUnit
import fe8.game.bm.BmBattle
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.CombatAction
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.PlaySt
import fe8.game.bm.PlayerPhase
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.mechanics.Mechanics
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import gbafe.render.BgHardware
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The combat action (MC-13, src/bmmind.c) against `ram_trace_prologue_attack_noanim`: A on the
 * target has recorded the attack in `gActionData`; game frame 1823 (dump 1824 is its result, as in
 * the other differential tests) runs `ApplyUnitAction` -> `ActionCombat` -> `BattleGenerateReal`
 * and starts `sProcScr_CombatAction`, which ends at frame 2073 (dump 2074 holds its last command,
 * dump 2075 has no proc).
 *
 * Seeded from dump 1823: the unit table, `gPlaySt`, `gBmSt`, the terrain, both battle units,
 * `gBattleStats`, `gActionData` and the RNG. Every frame runs the proc trees as `OnMain` does
 * (tree 2 only while the game is not locked). The map battle animation (M6-H) is a test
 * placeholder: it locks the game and ends when the dump's `ProcScr_MapAnimBattle` proc is gone, as
 * the real one does; its BG, OAM, palette, sprite and hit flash writes are not compared.
 *
 * From dump 1825 per frame: the combat proc and its script position, the blocked parent's lock
 * count, both battle units (all bytes, exp included), `gBattleStats` and the RNG. Dump 1824
 * holds `BeginBattleAnimations` half run and is skipped. At the end the unit table entries of both
 * units (HP, weapon uses, weapon exp, every byte), the proc's unit ids
 * (`BATTLE_HandleItemDrop`) and the ids the death handling reads (`BATTLE_HandleCombatDeaths`,
 * nobody dies here) are compared.
 */
class CombatActionDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "ApplyUnitAction", "ActionCombat", "BATTLE_GOTO1_IfNobodyIsDead",
        "BATTLE_HandleItemDrop", "BATTLE_HandleCombatDeaths",
    )
    fun `the combat action follows the game from A on the target to the end of its proc`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val run = ref.run(RUN)
        val names = run.dumps.entries.associate { it.value to it.key }
        fun ewram(frame: Int) = run.region(names.getValue(frame), "ewram")
        fun iwram(frame: Int) = run.region(names.getValue(frame), "iwram")
        val procNames = ProcArrayDump.romNames()

        val before = ewram(START)
        val table = UnitTable(rom).also { it.loadFrom(before) }
        val playSt = PlaySt().also { it.decode(before, GPLAYST - EWRAM) }
        val bm = BmSt().also { it.decode(before, GBMST - EWRAM) }
        val rows = before.u32(GBM_MAP_TERRAIN)
        val terrainAt = { y: Int, x: Int -> before.u8(before.u32(rows + 4 * y) + x) }
        val rng = RngService()
        val startIw = iwram(START)
        rng.primary.load(startIw.half(0), startIw.half(2), startIw.half(4))
        val loader = UnitLoader(table, rom, { 0 }, rng, { false })
        val battle = BmBattle(rom, table, loader, playSt, bm, terrainAt)
        val gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics())
        val actorAt = GBATTLE_ACTOR - EWRAM
        val targetAt = GBATTLE_TARGET - EWRAM
        before.copyInto(battle.actor.bytes, 0, actorAt, actorAt + BattleUnit.SIZE)
        before.copyInto(battle.target.bytes, 0, targetAt, targetAt + BattleUnit.SIZE)
        battle.stats.decode(before, GBATTLESTATS - EWRAM)

        val engine = ProcEngine()
        val active = ActiveUnit()
        val a = GACTIONDATA - EWRAM
        active.action.subjectIndex = before[a + 0x0C].toInt() and 0xFF
        active.action.targetIndex = before[a + 0x0D].toInt() and 0xFF
        active.action.unitActionType = before[a + 0x11].toInt() and 0xFF
        active.action.itemSlotIndex = before[a + 0x12].toInt() and 0xFF
        val actorId = active.action.subjectIndex
        val targetId = active.action.targetIndex
        assertEquals(2, active.action.unitActionType, "the seed is a combat action")

        val combat = CombatAction(engine, battle, gen, table, active)
        val phase = PlayerPhase(
            engine, bm, playSt, BmMap(rom, ChapterData(rom), bm), table, CamMove(engine, bm),
            EntityBmMaps(BmMap(rom, ChapterData(rom), bm), table, playSt),
            BmMapRender(bm, BmMap(rom, ChapterData(rom), bm), BgHardware()), KeyStatus(),
            { false }, active = active,
        )
        phase.hooks.actionCombat = ProcBoolFunc { combat.actionCombat(it) }

        // The player phase proc the combat proc blocks (tree 2), and the map animation
        // placeholder (tree 3).
        var frame = START
        combat.hooks.itemGotPopup = CombatAction.ItemPopup { _, _, _ -> error("no item drop") }
        val animation = ProcScript(
            "map animation (test placeholder)",
            listOf(
                ProcScripts.whileTrue(
                    ProcBoolFunc {
                        // Dump 1824 is taken before the real animation proc exists.
                        frame == START ||
                            ProcArrayDump.ofEwram(ewram(frame + 1), procNames)
                                .find(MAP_ANIM_BATTLE) != null
                    },
                ),
                ProcScripts.call(ProcFunc { bm.lock = (bm.lock - 1) and 0xFF }),
                ProcScripts.end(),
            ),
        )
        combat.hooks.beginBattleAnimations = ProcFunc {
            battle.stats.config = battle.stats.config or BattleStats.CONFIG_MAPANIMS
            bm.lock = (bm.lock + 1) and 0xFF // the real proc's first command is LockGame
            engine.start(animation, 3)
        }
        val parent = engine.start(
            ProcScript(
                "PlayerPhase (test)",
                listOf(
                    ProcScripts.yield(),
                    ProcScripts.call2(ProcBoolFunc { phase.applyUnitAction(it) }),
                    ProcScripts.block(),
                    ProcScripts.end(),
                ),
            ),
            2,
        )

        var sawCombat = 0
        while (frame <= END) {
            for (tree in 0 until 8) {
                if (tree == 2 && bm.lock != 0) continue
                engine.run(engine.root(tree))
            }
            val d = frame + 1
            val e = ewram(d)
            val at = "$RUN f$frame (dump $d)"
            if (d >= START + 2) {
                val dump = ProcArrayDump.ofEwram(e, procNames)
                val original = dump.find(COMBAT_SCRIPT)
                val mine = engine.find(combat.script)
                assertEquals(original != null, mine != null, "$at combat proc exists")
                if (original != null) {
                    sawCombat++
                    assertEquals(original.scrCur, mine!!.scrCur, "$at script position")
                    val parentDump = dump.find(PLAYER_PHASE_SCRIPT)!!
                    assertEquals(parentDump.u8(0x28), parent.lockCnt, "$at parent lock")
                }
                compareBattle(battle, e, iwram(d), rng, at)
            }
            frame++
        }
        assertTrue(sawCombat > 200, "the combat proc is compared in every dump it exists")
        assertEquals(null, engine.find(combat.script), "the combat proc has ended")
        assertEquals(0, parent.lockCnt, "the player phase proc is released")
        assertEquals(0, bm.lock, "the game is unlocked")

        // The proc's last dump (2074) holds the ids BATTLE_HandleItemDrop stored (nobody dies, so
        // the death handling changes nothing: the unit table is compared above).
        val last = ProcArrayDump.ofEwram(ewram(LAST_PROC), procNames).find(COMBAT_SCRIPT)
        assertNotNull(last)
        val idA = last!!.u16(0x64).toShort().toInt()
        val idB = last.u16(0x66).toShort().toInt()
        assertEquals(idA and 0xFF, actorId, "the actor's id")
        assertEquals(idB and 0xFF, targetId, "the target's id")

        // The write-back into the unit table, once, at the end.
        val after = ewram(END)
        val units = UnitTable(rom).also { it.loadFrom(after) }
        for (id in intArrayOf(actorId, targetId)) {
            val o = table.unitOffset(id)
            for (i in 0 until UnitTable.UNIT_SIZE) {
                assertEquals(
                    units.bytes[o + i], table.bytes[o + i], "unit $id byte 0x${i.toString(16)}",
                )
            }
        }
    }

    private fun compareBattle(
        battle: BmBattle,
        e: ByteArray,
        iw: ByteArray,
        rng: RngService,
        at: String,
    ) {
        for ((bu, address) in listOf(
            battle.actor to GBATTLE_ACTOR, battle.target to GBATTLE_TARGET,
        )) {
            for (i in 0 until BattleUnit.SIZE) {
                assertEquals(e[address - EWRAM + i], bu.bytes[i], "$at battle unit $address+$i")
            }
        }
        val stats = ByteArray(BattleStats.SIZE)
        battle.stats.encode(stats, 0)
        for (i in stats.indices) {
            assertEquals(e[GBATTLESTATS - EWRAM + i], stats[i], "$at gBattleStats+$i")
        }
        for (w in 0 until 3) assertEquals(iw.half(2 * w), rng.primary.word(w), "$at rng $w")
    }

    @Test
    @VerifiesPort("StoreRNStateToActionStruct")
    fun `the RN state is stored in gActionData`() {
        assumeTrue(ref.available, "reference data not present")
        val run = ref.run(RUN)
        val names = run.dumps.entries.associate { it.value to it.key }
        // Before the battle (the suspend save of f1820) and after it (the idle save of f2079).
        for ((from, to) in listOf(1820 to 1822, 2079 to 2081)) {
            val rng = RngService()
            val iw = run.region(names.getValue(from), "iwram")
            rng.primary.load(iw.half(0), iw.half(2), iw.half(4))
            val actionData = ByteArray(0x38) { 0x5A }
            CombatAction.storeRNStateToActionStruct(rng, actionData)
            val want = run.region(names.getValue(to), "ewram")
            for (i in 0 until 6) {
                assertEquals(want[GACTIONDATA - EWRAM + i], actionData[i], "f$to RN byte $i")
            }
            assertEquals(0x5A, actionData[6].toInt(), "the rest is untouched")
        }
        val a = run.region(names.getValue(1822), "ewram")
        val b = run.region(names.getValue(2081), "ewram")
        assertTrue(
            (0 until 6).any { a[GACTIONDATA - EWRAM + it] != b[GACTIONDATA - EWRAM + it] },
            "the two stores differ",
        )
    }

    private fun ByteArray.u8(addr: Int) = this[addr - EWRAM].toInt() and 0xFF
    private fun ByteArray.half(o: Int) =
        (this[o].toInt() and 0xFF) or ((this[o + 1].toInt() and 0xFF) shl 8)
    private fun ByteArray.u32(addr: Int) =
        u8(addr) or (u8(addr + 1) shl 8) or (u8(addr + 2) shl 16) or (u8(addr + 3) shl 24)

    private companion object {
        const val RUN = "ram_trace_prologue_attack_noanim"
        const val EWRAM = 0x02000000
        const val GBMST = 0x0202BCB0
        const val GPLAYST = 0x0202BCF0
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val GBATTLESTATS = 0x0203A4D4
        const val GBATTLE_ACTOR = 0x0203A4EC
        const val GBATTLE_TARGET = 0x0203A56C
        const val GACTIONDATA = 0x0203A958
        const val COMBAT_SCRIPT = 0x0859DABC
        const val MAP_ANIM_BATTLE = 0x089A3508
        const val PLAYER_PHASE_SCRIPT = 0x0859AAD8
        const val START = 1823
        const val END = 2076
        const val LAST_PROC = 2074
    }
}
