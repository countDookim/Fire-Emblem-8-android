package fe8.game.event

import fe8.game.RomView
import fe8.game.bm.BattleGenerate
import fe8.game.bm.BmBattle
import fe8.game.bm.BmSt
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitLoader
import fe8.game.bm.UnitTable
import gbafe.core.mechanics.Mechanics
import gbafe.core.rng.RngService
import gbafe.proc.ProcEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EventScriptBattleTest {
    @Test
    fun `queue words become hits`() {
        val s = EventState()
        val rom = RomView(emptyList())
        val table = UnitTable(rom)
        val rng = RngService()
        val loader = UnitLoader(table, rom, { 0 }, rng, { false })
        val playSt = PlaySt()
        val battle = BmBattle(rom, table, loader, playSt, BmSt()) { _, _ -> 0 }
        val gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics())
        val host = object : EventScriptBattleHost {
            override fun getUnitStructFromEventParameter(pid: Int): Int = -1

            override fun gameLock(): Int = 0
        }
        val esb = EventScriptBattle(ProcEngine(), s, ScriptMemory { 0 }, table, gen, host)
        // side 0 / 1 (low byte), hp change (byte 1), attributes (high half).
        s.slotQueue[0] = 0x0002_0000 or (5 shl 8) or 0
        s.slotQueue[1] = 0x0001_0000 or (0xFB shl 8) or 1
        s.slots[0xD] = 2
        val hits = esb.generateScriptBattleHits()
        // attributes in bits 0..18, info 19..23, hpChange 24..31
        assertEquals(2 or (BEGIN shl 19) or (5 shl 24), hits[0])
        assertEquals(1 or (RETAL shl 19) or (0xFB shl 24), hits[1])
        assertEquals(END, (hits[2] ushr 19) and 0x1F)
    }

    private companion object {
        const val BEGIN = 1
        const val RETAL = 8
        const val END = 0x10
    }
}
