package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleGenerate
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
import gbafe.core.mechanics.Mechanics
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `SetBattleMuPaletteByIndex` (src/mapanim.c): two units of one faction keep their palettes
 * unless one battle unit is an obstacle (`BUNIT_IS_OBSTACLE`, by terrain); the special palette
 * goes to a `CLASS_MANAKETE_MYRRH` actor holding `ITEM_DIVINESTONE`.
 */
class MapAnimMuPaletteTest {
    private fun palettesSet(targetTerrain: Int, targetFaction: Int): List<Int> {
        val rom = RomView.ofRom(ByteArray(0x100))
        val engine = ProcEngine()
        val bm = BmSt()
        val playSt = PlaySt()
        val table = UnitTable(rom)
        val rng = RngService()
        val loader = UnitLoader(table, rom, { 0 }, rng, { false })
        val battle = BmBattle(rom, table, loader, playSt, bm) { _, _ -> 0 }
        val gen = BattleGenerate(battle, rom, loader, playSt, rng, Mechanics())
        val camScript = CamMove(engine, bm).script
        val mu = Mu(engine, bm, playSt, KeyStatus(), camScript, object : MuHooks {})
        val api = MapAnimApi(engine, bm, battle, gen, table, loader, mu, rom)
        val set = ArrayList<Int>()
        api.hooks.setMuPal = { _, pal -> set += pal }
        val idle = ProcScript("test", listOf(ProcScripts.yield(), ProcScripts.end()))
        val proc = engine.start(idle, 3)
        val state = api.state
        for (i in 0..1) {
            val bu = BattleUnit()
            bu.terrainId = if (i == 1) targetTerrain else 0
            bu.weaponBefore = if (i == 0) ITEM_DIVINESTONE else 0
            val bytes = ByteArray(BattleUnit.SIZE)
            bytes[OFF_INDEX] = (if (i == 1) targetFaction else 0).toByte()
            if (i == 0) {
                val classPtr = CLASS_DATA +
                    (CLASS_MANAKETE_MYRRH - 1) * CLASS_DATA_SIZE
                for (b in 0..3) bytes[OFF_CLASS + b] = (classPtr shr (8 * b)).toByte()
            }
            state.bu[i] = bu
            state.unitBytes[i] = bytes
            state.unitOffset[i] = 0
            state.mu[i] = proc
        }
        api.setBattleMuPaletteByIndex(0)
        return set
    }

    @Test
    fun `a same-faction pair without an obstacle keeps its palettes`() {
        assertEquals(emptyList<Int>(), palettesSet(targetTerrain = 0, targetFaction = 0))
    }

    @Test
    fun `an obstacle target gets the special palette even in one faction`() {
        for (terrain in listOf(TERRAIN_WALL_DAMAGED, TERRAIN_SNAG)) {
            assertEquals(listOf(8), palettesSet(targetTerrain = terrain, targetFaction = 0))
        }
    }

    @Test
    fun `units of different factions get the special palette`() {
        assertEquals(listOf(8), palettesSet(targetTerrain = 0, targetFaction = 0x80))
    }

    private companion object {
        const val ITEM_DIVINESTONE = 0xAA
        const val CLASS_MANAKETE_MYRRH = 0x3C
        const val TERRAIN_WALL_DAMAGED = 0x1B
        const val TERRAIN_SNAG = 0x33
        const val CLASS_DATA = 0x08807164
        const val CLASS_DATA_SIZE = 0x54
        const val OFF_INDEX = 0x0B
        const val OFF_CLASS = 0x04
    }
}
