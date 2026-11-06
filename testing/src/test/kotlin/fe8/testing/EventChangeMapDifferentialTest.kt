package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleMapStart
import fe8.game.bm.BmMap
import fe8.game.bm.BmMapRender
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterMapGfx
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable
import fe8.game.event.EventChangeMap
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EV_STATE_GFXLOCKED
import fe8.game.event.EventEngineProc
import fe8.game.event.EventState
import fe8.game.event.ScriptMemory
import fe8.game.flags.Fe8EventFlags
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.core.time.GameClock
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * M6-23: `Event25_ChangeMap` ([EventChangeMap]) with `RestartBattleMap`
 * ([BattleMapStart.restartBattleMap]) against `ram_trace_newgame_bm` (the Prologue opening's
 * first `load_map`, chapter 0 -> 16). The game's call spans several frames (a lag frame: the
 * chapter index is set at f9508, the map size at f9509 and the camera at f9513); the state after
 * it is f9514. The state before is synthetic: `gPlaySt` and `gBmSt` from f9507, the unit arrays
 * from f9514 (the command does not change them), event slot 0xB = (14, 10) as the script's
 * `setval` left it (read from f9508: the same frame as the command, so f9507 still has 0), and `chapterWeatherId` 5 and every map pool byte a sentinel.
 *
 * Compared: `gPlaySt.chapterIndex` / weather, the camera and camera limits of `gBmSt`,
 * `gBmMapSize`, the terrain, unit, fog and hidden pools, the base tile map and the event
 * state bits. Not compared (no mask, simply not looked at): the BG graphics and palettes
 * ([ChapterMapGfx] has its own tests), the procs, and the stand-in [BattleMapStart.Hooks] (units'
 * sprites, traps, obstacles).
 */
class EventChangeMapDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun ByteArray.u8(addr: Int) = this[addr - GbaMem.EWRAM].toInt() and 0xFF
    private fun ByteArray.u16(addr: Int) = u8(addr) or (u8(addr + 1) shl 8)
    private fun ByteArray.u32(addr: Int) = u16(addr) or (u16(addr + 2) shl 16)

    @Test
    @VerifiesPort("Event25_ChangeMap", "RestartBattleMap")
    fun `load_map 16 from the prologue opening reproduces the dumped map`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val dump = ref.run("ram_trace_newgame_bm")
        val before = dump.region("r09507", "ewram")
        val after = dump.region("r09514", "ewram")
        val iwBefore = dump.region("r09508", "iwram")
        val slotB = EVENT_SLOT_B - GbaMem.IWRAM
        val rom = RomView.ofRom(romFile.readBytes())

        val playSt = PlaySt().apply { decode(before, PLAY_ST - GbaMem.EWRAM) }
        assertEquals(0, playSt.chapterIndex, "pre-state chapter")
        playSt.chapterWeatherId = 5 // sentinel: RestartBattleMap must reset it to the chapter's
        val bmSt = BmSt().apply { decode(before, BM_ST - GbaMem.EWRAM) }
        val units = UnitTable(rom).also { it.loadFrom(after) }
        val state = EventState()
        state.slots[0xB] = (iwBefore[slotB].toInt() and 0xFF) or
            ((iwBefore[slotB + 2].toInt() and 0xFF) shl 16)
        assertEquals(14 or (10 shl 16), state.slots[0xB], "slot 0xB at f9508")

        val hw = BgHardware()
        val engine = ProcEngine()
        val chapters = ChapterData(rom)
        val map = BmMap(rom, chapters, bmSt)
        for (p in listOf(map.unit, map.terrain, map.fog, map.hidden, map.other)) {
            p.bytes.fill(SENTINEL)
        }
        val render = BmMapRender(bmSt, map, hw)
        val start = BattleMapStart(
            engine, playSt, bmSt, hw, chapters, map, ChapterMapGfx(rom, chapters, hw, playSt),
            render, Fe8EventFlags.create(), RngService(), GameClock(), units,
        )
        val entities = EntityBmMaps(map, units, playSt)
        // The command: load_map 16 (EVT2 0x25, argument = chapter).
        val words = shortArrayOf(0x25, 16)
        val memory = ScriptMemory { a -> words[(a - SCRIPT) / 2].toInt() and 0xFFFF }
        val command = EventChangeMap(
            state, memory, playSt, bmSt, hw,
            { start.restartBattleMap() }, { entities.refreshEntityBmMaps() },
            { render.renderBmMap() },
        )
        val proc = EventEngineProc().apply {
            pEventCurrent = SCRIPT
            evStateBits = EV_STATE_GFXLOCKED
        }
        assertEquals(EVC_ADVANCE_YIELD, command.event25ChangeMap.run(proc))

        val at = "f9514"
        assertEquals(after.u8(PLAY_ST + 0x0E), playSt.chapterIndex, "chapterIndex")
        assertEquals(16, playSt.chapterIndex)
        assertEquals(after.u8(PLAY_ST + 0x15), playSt.chapterWeatherId, "chapterWeatherId")
        assertEquals(0, proc.evStateBits and EV_STATE_GFXLOCKED, "GFXLOCKED cleared")
        val gameBm = BmSt().apply { decode(after, BM_ST - GbaMem.EWRAM) }
        assertEquals(gameBm.cameraX, bmSt.cameraX, "camera.x")
        assertEquals(gameBm.cameraY, bmSt.cameraY, "camera.y")
        assertTrue(bmSt.cameraX > 0 && bmSt.cameraY > 0, "camera set")
        assertEquals(gameBm.cameraMaxX, bmSt.cameraMaxX, "cameraMax.x")
        assertEquals(gameBm.cameraMaxY, bmSt.cameraMaxY, "cameraMax.y")
        assertEquals(after.u8(BM_MAP_SIZE), map.sizeX, "gBmMapSize.x")
        assertEquals(after.u8(BM_MAP_SIZE + 2), map.sizeY, "gBmMapSize.y")

        val baseRows = BASE_TILES_POOL
        for (y in 0 until map.sizeY) {
            val baseRow = after.u32(baseRows + 4 * y)
            for (x in 0 until map.sizeX) {
                assertEquals(after.u16(baseRow + 2 * x), map.baseTile(x, y), "tile ($x,$y) $at")
            }
        }
        val pools = listOf(
            Triple("terrain", GBM_MAP_TERRAIN, map.terrain),
            Triple("unit", GBM_MAP_UNIT, map.unit),
            Triple("fog", GBM_MAP_FOG, map.fog),
            Triple("hidden", GBM_MAP_HIDDEN, map.hidden),
        )
        for ((name, handle, pool) in pools) {
            val rows = after.u32(handle)
            for (y in 0 until map.sizeY) {
                val row = after.u32(rows + 4 * y)
                for (x in 0 until map.sizeX) {
                    assertEquals(after.u8(row + x), pool[y, x], "$name ($x,$y) $at")
                }
            }
        }
    }

    private companion object {
        const val SENTINEL: Byte = 0x77
        const val SCRIPT = 0x08000000
        const val PLAY_ST = 0x0202BCF0
        const val BM_ST = 0x0202BCB0
        const val EVENT_SLOT_B = 0x030004B8 + 4 * 0xB
        const val BM_MAP_SIZE = 0x0202E4D4
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val GBM_MAP_FOG = 0x0202E4E8
        const val GBM_MAP_HIDDEN = 0x0202E4EC
        const val BASE_TILES_POOL = 0x02032F8C
    }
}
