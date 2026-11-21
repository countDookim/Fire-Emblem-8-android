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
import fe8.game.bm.UnitSpriteHandles
import fe8.game.bm.UnitSprites
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `Event25_ChangeMap` ([EventChangeMap]) with `RestartBattleMap`
 * ([BattleMapStart.restartBattleMap]) against `ram_trace_newgame_bm` (the Prologue opening's
 * first `load_map`, chapter 0 -> 16). The game's call spans several frames (`chapterIndex` is
 * set at f9508, the map size at f9509, the camera at f9513, `RefreshBMapGraphics` runs over
 * f9514..f9517, and the handler finishes and advances `pEventCurrent` at f9518); the state after
 * it is f9518. The state before is seeded from f9507 (`gPlaySt`, `gBmSt`, `gPaletteBuffer`,
 * `gLCDControlBuffer`), the unit arrays from f9518 (the command does not change them), event
 * slot 0xB = (14, 10) from f9508, `chapterWeatherId` 5, every map pool byte a sentinel, and
 * sentinel values in `palette[0]`, OBJ UI/sprite palettes and `BG0`/`BG1` tilemaps.
 *
 * Compared: `gPlaySt.chapterIndex` / weather, camera and camera limits of `gBmSt`, `gBmMapSize`,
 * terrain/unit/fog/hidden/base-tile pools, all 32 palette banks (backdrop, tileset, OBJ UI and
 * unit sprites), `gLCDControlBuffer` (`dispcnt`, `bgCnt`, BG scroll and unchanged `bldcnt`/
 * `blendCoeffA`/`blendCoeffB`/`blendY`), `BG0`/`BG1` cleared to 0, `BG3` visible 15x10 window,
 * sync flags (`sModifiedBGs`, `sModifiedPalette`) and `EV_STATE_GFXLOCKED`. A companion test
 * pins the handler's own order, BG0/BG1 clear+sync, `mapSpritePalIdOverride` forwarding, and
 * non-mutation of blend/`palette[0]` when hooks do not mask them.
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
    private fun ByteArray.iwU8(addr: Int) = this[addr - GbaMem.IWRAM].toInt() and 0xFF
    private fun ByteArray.iwU16(addr: Int) = iwU8(addr) or (iwU8(addr + 1) shl 8)

    @Test
    @VerifiesPort("Event25_ChangeMap", "RestartBattleMap")
    fun `load_map 16 from the prologue opening reproduces the dumped map`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val dump = ref.run("ram_trace_newgame_bm")
        val before = dump.region("r09507", "ewram")
        val iwBefore07 = dump.region("r09507", "iwram")
        val after = dump.region("r09518", "ewram")
        val iwAfter = dump.region("r09518", "iwram")
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
        hw.lcd.readFrom(iwBefore07, LCD_CONTROL - GbaMem.IWRAM)
        for (i in 0 until BgHardware.PALETTE_COLORS) {
            hw.palette[i] = before.u16(PALETTE_BUF + 2 * i).toShort()
        }
        // Sentinels that RestartBattleMap / RefreshBMapGraphics / ChangeUnitSpritePalette must fix.
        hw.palette[0] = 0x1234.toShort()
        for (bank in listOf(16, 17, 27, 28, 29, 30, 31)) {
            for (c in 0 until 16) hw.palette[bank * 16 + c] = 0x2222.toShort()
        }
        hw.mapBuffer(0).fill(0x1234.toShort())
        hw.mapBuffer(1).fill(0x5678.toShort())

        val engine = ProcEngine()
        val chapters = ChapterData(rom)
        val map = BmMap(rom, chapters, bmSt)
        for (p in listOf(map.unit, map.terrain, map.fog, map.hidden, map.other)) {
            p.bytes.fill(SENTINEL)
        }
        val gfx = ChapterMapGfx(rom, chapters, hw, playSt)
        val render = BmMapRender(bmSt, map, hw)
        val sprites = UnitSprites(rom, hw, bmSt)
        val handles = UnitSpriteHandles(rom, sprites, units)
        val traps = ByteArray(0x40)
        val refreshSprites = { handles.refreshUnitSprites({ x, y -> map.unit[y, x] }, traps) }
        val start = BattleMapStart(
            engine, playSt, bmSt, hw, chapters, map, gfx,
            render, Fe8EventFlags.create(), RngService(), GameClock(), units,
        ).also {
            it.hooks.applyUnitSpritePalettes = { sprites.applyUnitSpritePalettes() }
            it.hooks.resetUnitSprites = { sprites.resetUnitSprites() }
        }
        val entities = EntityBmMaps(map, units, playSt)
        // The command: load_map 16 (EVT2 0x25, argument = chapter).
        val words = shortArrayOf(0x25, 16)
        val memory = ScriptMemory { a -> words[(a - SCRIPT) / 2].toInt() and 0xFFFF }
        val command = EventChangeMap(
            state, memory, playSt, bmSt, hw,
            { start.restartBattleMap() },
            { entities.refreshEntityBmMaps() },
            { render.renderBmMap() },
            refreshUnitSprites = refreshSprites,
            refreshBMapGraphics = {
                hw.setupBackgrounds(
                    cloudsWeather = playSt.chapterWeatherId == ChapterMapGfx.WEATHER_CLOUDS,
                )
                gfx.loadObjUiGfx()
                gfx.unpackChapterMapGraphics(playSt.chapterIndex)
                render.renderBmMap()
                refreshSprites()
                sprites.applyUnitSpritePalettes()
                sprites.forceSyncUnitSpriteSheet()
            },
            changeUnitSpritePalette = { sprites.changeUnitSpritePalette(it) },
        )
        val proc = EventEngineProc().apply {
            pEventCurrent = SCRIPT
            evStateBits = EV_STATE_GFXLOCKED
        }
        assertEquals(EVC_ADVANCE_YIELD, command.event25ChangeMap.run(proc))

        val at = "f9518"
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

        // Palettes: backdrop, tileset (6..15), OBJ UI (16..17), unit sprites (27..31) and rest.
        for (i in 0 until BgHardware.PALETTE_COLORS) {
            assertEquals(
                after.u16(PALETTE_BUF + 2 * i),
                hw.palette[i].toInt() and 0xFFFF,
                "palette[$i] $at",
            )
        }

        // LCD control: dispcnt, bgCnt, scroll, and blend registers untouched by Event25_ChangeMap.
        assertEquals(iwAfter.iwU16(LCD_CONTROL), hw.lcd.dispcnt, "dispcnt $at")
        for (bg in 0..3) {
            assertEquals(iwAfter.iwU16(LCD_CONTROL + 0x0C + 4 * bg), hw.lcd.bgCnt[bg], "bg${bg}Cnt")
            assertEquals(iwAfter.iwU16(LCD_CONTROL + 0x1C + 4 * bg), hw.lcd.bgHofs[bg], "bg${bg}X")
            assertEquals(iwAfter.iwU16(LCD_CONTROL + 0x1E + 4 * bg), hw.lcd.bgVofs[bg], "bg${bg}Y")
        }
        assertEquals(iwAfter.iwU16(LCD_CONTROL + 0x3C), hw.lcd.bldcnt, "bldcnt $at")
        assertEquals(iwAfter.iwU8(LCD_CONTROL + 0x44), hw.lcd.blendCoeffA, "blendCoeffA $at")
        assertEquals(iwAfter.iwU8(LCD_CONTROL + 0x45), hw.lcd.blendCoeffB, "blendCoeffB $at")
        assertEquals(iwAfter.iwU8(LCD_CONTROL + 0x46), hw.lcd.blendY, "blendY $at")

        // BG0 and BG1 cleared to 0; BG3 visible 15x10 metatile window matches; sync flags set.
        val bg0 = hw.mapBuffer(0)
        val bg1 = hw.mapBuffer(1)
        val bg3 = hw.mapBuffer(3)
        for (i in 0 until BgHardware.TILEMAP_ENTRIES) {
            assertEquals(after.u16(BG0_TILEMAP + 2 * i), bg0[i].toInt() and 0xFFFF, "bg0[$i] $at")
            assertEquals(after.u16(BG1_TILEMAP + 2 * i), bg1[i].toInt() and 0xFFFF, "bg1[$i] $at")
        }
        for (y in 0 until 20) {
            for (x in 0 until 30) {
                val idx = y * 32 + x
                assertEquals(
                    after.u16(BG3_TILEMAP + 2 * idx),
                    bg3[idx].toInt() and 0xFFFF,
                    "bg3($x,$y) $at",
                )
            }
        }
        assertEquals(iwAfter.iwU8(S_MODIFIED_BGS), hw.modifiedBgs, "sModifiedBGs $at")
        assertEquals(iwAfter.iwU8(S_MODIFIED_PALETTE) != 0, hw.modifiedPalette, "sModifiedPal $at")
    }

    @Test
    fun `handler clears BG0 and BG1, forwards pal override, and preserves blend and palette0`() {
        val calls = mutableListOf<String>()
        val hw = BgHardware()
        hw.palette[0] = 0x1234.toShort()
        hw.modifiedPalette = false
        hw.lcd.bldcnt = 0x2468
        hw.lcd.blendCoeffA = 3
        hw.lcd.blendCoeffB = 7
        hw.lcd.blendY = 9

        val state = EventState().apply {
            slots[2] = 16
            slots[0xB] = 14 or (10 shl 16)
        }
        val words = shortArrayOf(0x25, -1)
        val memory = ScriptMemory { a -> words[(a - SCRIPT) / 2].toInt() and 0xFFFF }
        val playSt = PlaySt()
        val bmSt = BmSt().apply {
            cameraMaxX = 112
            cameraMaxY = 288
        }

        val command = EventChangeMap(
            state, memory, playSt, bmSt, hw,
            restartBattleMap = { calls += "restartBattleMap" },
            refreshEntityBmMaps = { calls += "refreshEntityBmMaps" },
            renderBmMap = { calls += "renderBmMap" },
            resumeMapDisplay = { calls += "resumeMapDisplay" },
            refreshUnitSprites = { calls += "refreshUnitSprites" },
            refreshBMapGraphics = {
                calls += "refreshBMapGraphics"
                hw.mapBuffer(0).fill(0x1111.toShort())
                hw.mapBuffer(1).fill(0x2222.toShort())
                hw.modifiedBgs = 0
            },
            changeUnitSpritePalette = { palId -> calls += "changeUnitSpritePalette($palId)" },
        )

        val proc = EventEngineProc().apply {
            pEventCurrent = SCRIPT
            evStateBits = EV_STATE_GFXLOCKED
            mapSpritePalIdOverride = 0x123
        }
        assertEquals(EVC_ADVANCE_YIELD, command.event25ChangeMap.run(proc))

        assertEquals(
            listOf(
                "resumeMapDisplay",
                "restartBattleMap",
                "refreshEntityBmMaps",
                "renderBmMap",
                "refreshUnitSprites",
                "refreshBMapGraphics",
                "changeUnitSpritePalette(291)",
            ),
            calls,
        )
        assertEquals(16, playSt.chapterIndex, "slot 2 fallback")
        assertEquals(0, proc.evStateBits and EV_STATE_GFXLOCKED, "GFXLOCKED cleared")
        for (i in 0 until BgHardware.TILEMAP_ENTRIES) {
            assertEquals(0, hw.mapBuffer(0)[i].toInt(), "bg0[$i] cleared")
            assertEquals(0, hw.mapBuffer(1)[i].toInt(), "bg1[$i] cleared")
        }
        assertEquals(0x3, hw.modifiedBgs, "BG0_SYNC_BIT | BG1_SYNC_BIT")
        assertEquals(0x1234.toShort(), hw.palette[0], "palette[0] untouched by handler")
        assertFalse(hw.modifiedPalette, "modifiedPalette untouched by handler")
        assertEquals(0x2468, hw.lcd.bldcnt, "bldcnt untouched")
        assertEquals(3, hw.lcd.blendCoeffA, "blendCoeffA untouched")
        assertEquals(7, hw.lcd.blendCoeffB, "blendCoeffB untouched")
        assertEquals(9, hw.lcd.blendY, "blendY untouched")
    }

    private companion object {
        const val SENTINEL: Byte = 0x77
        const val SCRIPT = 0x08000000
        const val PALETTE_BUF = 0x020228A8
        const val BG0_TILEMAP = 0x02022CA8
        const val BG1_TILEMAP = 0x020234A8
        const val BG3_TILEMAP = 0x020244A8
        const val PLAY_ST = 0x0202BCF0
        const val BM_ST = 0x0202BCB0
        const val S_MODIFIED_BGS = 0x0300000D
        const val S_MODIFIED_PALETTE = 0x0300000E
        const val EVENT_SLOT_B = 0x030004B8 + 4 * 0xB
        const val LCD_CONTROL = 0x03003080
        const val BM_MAP_SIZE = 0x0202E4D4
        const val GBM_MAP_UNIT = 0x0202E4D8
        const val GBM_MAP_TERRAIN = 0x0202E4DC
        const val GBM_MAP_FOG = 0x0202E4E8
        const val GBM_MAP_HIDDEN = 0x0202E4EC
        const val BASE_TILES_POOL = 0x02032F8C
    }
}
