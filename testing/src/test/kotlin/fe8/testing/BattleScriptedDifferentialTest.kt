package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleGenerate
import fe8.game.bm.BattleStats
import fe8.game.bm.BmBattle
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.mechanics.Mechanics
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The scripted battle of `src/bmbattle.c` against `ram_trace_prologue_battle`: between frames
 * 14429 and 14430 the Prologue event battle fills `gBattleHitArray` and `gBattleStats` from the
 * scripted hits (`gActionData.script_hits`, built from the event queue, which the event code fills
 * in the same frame: it is read from the frame 14430 IWRAM). The
 * unit arrays, `gPlaySt`, `gBmSt`, the terrain map of frame 14429 are the
 * inputs; the battle units, hit array and stats start on sentinels.
 */
class BattleScriptedDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val run = "ram_trace_prologue_battle"
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    // Not @VerifiesPort: BattleGenerateRealInternal, BattleGenerateReal (the trace runs the event
    // variant), StartEventBattle (only the plain weapon path is ported), SetScriptedBattle,
    // ClearBattleHits, BattleUnitTargetSetEquippedWeapon (not observable here: the target has a
    // weaponBefore, nothing clears the array).
    @Test
    @VerifiesPort(
        "BattleUnwindScripted", "BattleGenerateHitScriptedDamage", "BattleUpdateBattleStats",
        "BattleGenerate", "EvtBattleGenerateRealInternal",
        "EvtBattleGenerateReal", "GenerateScriptBattleHitFormEventQueue",
    )
    fun `scripted battle hits and stats match the dumped structs`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val r = ref.run(run)
        val before = r.region("r14429", "ewram")
        val after = r.region("r14430", "ewram")

        val table = UnitTable(rom).also { it.loadFrom(before) }
        val playSt = PlaySt().also { it.decode(before, GPLAYST - EWRAM) }
        val bmSt = BmSt().also { it.decode(before, GBMST - EWRAM) }
        val rows = before.u32(GBM_MAP_TERRAIN)
        val terrainAt = { y: Int, x: Int -> before.u8(before.u32(rows + 4 * y) + x) }
        val rng = RngService()
        val loader = UnitLoader(table, rom, { 0 }, rng, { false })
        val battle = BmBattle(rom, table, loader, playSt, bmSt, terrainAt)
        val gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics())

        battle.actor.bytes.fill(0xA5.toByte())
        battle.target.bytes.fill(0x5A.toByte())
        for (bu in listOf(battle.actor, battle.target)) {
            for (i in intArrayOf(0x59, 0x7E, 0x7F)) bu.bytes[i] = 0
        }
        // The stats: sentinels for what the code writes, zero for the two unit pointers.
        val sentinel = ByteArray(BattleStats.SIZE) { 0xA5.toByte() }
        sentinel.fill(0, 0x10, BattleStats.SIZE)
        battle.stats.decode(sentinel, 0)
        // Entries 0..2 are written by the unwind; the rest keeps the zero of the earlier frame.
        gen.hitArray.fill(0)
        for (i in 0 until 3) gen.hitArray[i] = 0x5A5A5A5A
        // script_hits: the two hits are written whole, the END hit sets its info only.
        gen.scriptHits.fill(0)
        for (i in 0 until 2) gen.scriptHits[i] = 0x5A5A5A5A
        // Event3F_ScriptBattle: the hits come from the event queue and slot 0xD. The event code
        // fills them in the same frame as the battle, so they are read from r14430 (the battle
        // code does not touch them).
        val iw = ref.run(run).region("r14430", "iwram")
        val slotD = iw.u32i(GEVENTSLOTS + 4 * 0xD)
        gen.generateScriptBattleHitFormEventQueue(slotD, iw, GEVENTSLOTQUEUE - IWRAM)

        gen.startEventBattle(
            table.bytes, table.unitOffset(ACTOR_ID), table.unitOffset(TARGET_ID), false,
            gen.scriptHits,
        )

        for (i in 0 until BattleGenerate.BATTLE_HIT_MAX) {
            assertEquals(after.u32(GHITARRAY + 4 * i), gen.hitArray[i], "gBattleHitArray[$i]")
        }
        for (i in 0 until BattleGenerate.BATTLE_HIT_MAX) {
            val want = after.u32(GACTIONDATA + 0x1C + 4 * i)
            assertEquals(want, gen.scriptHits[i], "script_hits[$i]")
        }
        val stats = ByteArray(BattleStats.SIZE)
        battle.stats.encode(stats, 0)
        val expected = after.copyOfRange(GBATTLESTATS - EWRAM, GBATTLESTATS - EWRAM + stats.size)
        assertEquals(expected.toHex(), stats.toHex(), "gBattleStats")
        assertEquals(null, gen.scriptedBattleHits, "scriptedBattleHits cleared")
    }

    @Test
    @VerifiesPort("BattleUnitTargetSetEquippedWeapon")
    fun `BattleUnitTargetSetEquippedWeapon fills target weaponBefore from equipped weapon when empty`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val r = ref.run(run)
        val before = r.region("r14429", "ewram")
        val after = r.region("r14430", "ewram")

        val table = UnitTable(rom).also { it.loadFrom(before) }
        val playSt = PlaySt().also { it.decode(before, GPLAYST - EWRAM) }
        val bmSt = BmSt().also { it.decode(before, GBMST - EWRAM) }
        val rows = before.u32(GBM_MAP_TERRAIN)
        val terrainAt = { y: Int, x: Int -> before.u8(before.u32(rows + 4 * y) + x) }
        val rng = RngService()
        val loader = UnitLoader(table, rom, { 0 }, rng, { false })
        val battle = BmBattle(rom, table, loader, playSt, bmSt, terrainAt)
        val gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics())

        battle.initBattleUnit(battle.target, table.bytes, table.unitOffset(TARGET_ID))
        battle.target.weaponBefore = 0
        gen.battleUnitTargetSetEquippedWeapon(battle.target)

        val wantWeaponBefore = after.u16(GBATTLETARGET + 0x4A)
        assertEquals(wantWeaponBefore, battle.target.weaponBefore, "target weaponBefore matches r14430 dump")

        battle.target.weaponBefore = 0x1234
        gen.battleUnitTargetSetEquippedWeapon(battle.target)
        assertEquals(0x1234, battle.target.weaponBefore, "target weaponBefore unchanged when non-zero")
    }

    private fun ByteArray.toHex() = joinToString("") { "%02X".format(it) }
    private fun ByteArray.u32i(o: Int) =
        (this[o].toInt() and 0xFF) or ((this[o + 1].toInt() and 0xFF) shl 8) or
            ((this[o + 2].toInt() and 0xFF) shl 16) or ((this[o + 3].toInt() and 0xFF) shl 24)

    private fun ByteArray.u8(addr: Int) = this[addr - EWRAM].toInt() and 0xFF
    private fun ByteArray.u16(addr: Int) = u8(addr) or (u8(addr + 1) shl 8)
    private fun ByteArray.u32(addr: Int) =
        u8(addr) or (u8(addr + 1) shl 8) or (u8(addr + 2) shl 16) or (u8(addr + 3) shl 24)

    private companion object {
        const val EWRAM = 0x02000000
        const val GBMST = 0x0202BCB0
        const val GPLAYST = 0x0202BCF0
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val GBATTLESTATS = 0x0203A4D4
        const val GBATTLETARGET = 0x0203A56C
        const val GHITARRAY = 0x0203A5EC
        const val IWRAM = 0x03000000
        const val GEVENTSLOTS = 0x030004B8 - 0x03000000
        const val GEVENTSLOTQUEUE = 0x030004F0
        const val GACTIONDATA = 0x0203A958
        const val ACTOR_ID = 0x01
        const val TARGET_ID = 0x81
    }
}
