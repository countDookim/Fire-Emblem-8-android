package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.ChapterData
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.MapCursor
import fe8.game.bm.PlayerPhase
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitSpriteHover
import fe8.game.bm.UnitTable
import gba.core.VerifiesPort
import gba.core.sram.SaveStorage
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential tests for unit sprite hover functions of src/bmudisp.c:
 * [UnitSpriteHover.isUnitSpriteHoverEnabledAt], [UnitSpriteHover.resetUnitSpriteHover], and
 * [UnitSpriteHover.unitSpriteHoverUpdate], plus cursor OAM comparisons against reference captures
 * (`ram_trace_prologue_cancel_end` f1430/f1440, `ram_trace_prologue_select_move` f1250..f1260),
 * and a headless wired test on [Fe8Boot].
 */
class UnitSpriteHoverDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort("IsUnitSpriteHoverEnabledAt")
    fun `isUnitSpriteHoverEnabledAt matches the reference dumps and rules`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val runCancel = ref.run("ram_trace_prologue_cancel_end")
        val ew1430 = runCancel.region("f01430", "ewram")

        val bmSt = BmSt().apply { decode(ew1430, sym["gBmSt"].address - GbaMem.EWRAM) }
        val playSt = PlaySt().apply { decode(ew1430, sym["gPlaySt"].address - GbaMem.EWRAM) }
        val map = BmMap(rom, ChapterData(rom), bmSt).apply { initChapterMap(playSt.chapterIndex) }
        val units = UnitTable(rom).apply { loadFrom(ew1430) }
        EntityBmMaps(map, units, playSt).refreshEntityBmMaps()

        val hover = UnitSpriteHover(bmSt, map, units)

        // Eirika is at (4,5), Seth at (4,4) in Prologue
        assertTrue(hover.isUnitSpriteHoverEnabledAt(4, 5), "Eirika at (4,5) should be hover-enabled")
        assertTrue(hover.isUnitSpriteHoverEnabledAt(4, 4), "Seth at (4,4) should be hover-enabled")
        assertFalse(hover.isUnitSpriteHoverEnabledAt(5, 5), "Empty tile (5,5) should not be hover-enabled")
        assertFalse(hover.isUnitSpriteHoverEnabledAt(6, 5), "Empty tile (6,5) should not be hover-enabled")
        assertFalse(hover.isUnitSpriteHoverEnabledAt(-1, 0), "Negative x should not be hover-enabled")
        assertFalse(hover.isUnitSpriteHoverEnabledAt(0, -1), "Negative y should not be hover-enabled")
        assertFalse(hover.isUnitSpriteHoverEnabledAt(99, 99), "Out of bounds should not be hover-enabled")

        // Enemy units (e.g. Breguet / fighter / soldier at (8,6) or similar) are red, not blue
        assertFalse(hover.isUnitSpriteHoverEnabledAt(8, 6), "Enemy unit should not be hover-enabled")

        // Edge case: unselectable state (US_UNSELECTABLE = 1 shl 1)
        val eirikaId = map.unit[5, 4]
        val originalState = units.state(eirikaId)
        units.setState(eirikaId, originalState or UnitSpriteHover.US_UNSELECTABLE)
        assertFalse(hover.isUnitSpriteHoverEnabledAt(4, 5), "Unselectable unit must not be hover-enabled")
        units.setState(eirikaId, originalState)

        // Edge case: sleep status
        val offset = units.unitOffset(eirikaId)
        val origStatusByte = units.bytes[offset + 0x30]
        units.bytes[offset + 0x30] = (UnitSpriteHover.UNIT_STATUS_SLEEP and 0xF).toByte()
        assertFalse(hover.isUnitSpriteHoverEnabledAt(4, 5), "Sleeping unit must not be hover-enabled")

        // Edge case: berserk status
        units.bytes[offset + 0x30] = (UnitSpriteHover.UNIT_STATUS_BERSERK and 0xF).toByte()
        assertFalse(hover.isUnitSpriteHoverEnabledAt(4, 5), "Berserk unit must not be hover-enabled")
        units.bytes[offset + 0x30] = origStatusByte

        // Check select_move r1255 as well (before selection at f1260)
        val runSelect = ref.run("ram_trace_prologue_select_move")
        val ew1255 = runSelect.region("r01255", "ewram")
        val units1255 = UnitTable(rom).apply { loadFrom(ew1255) }
        val map1255 = BmMap(rom, ChapterData(rom), bmSt).apply { initChapterMap(playSt.chapterIndex) }
        EntityBmMaps(map1255, units1255, playSt).refreshEntityBmMaps()
        val hover1255 = UnitSpriteHover(bmSt, map1255, units1255)
        assertTrue(hover1255.isUnitSpriteHoverEnabledAt(4, 5), "select_move r1255 Eirika hover-enabled")
    }

    @Test
    @VerifiesPort("ResetUnitSpriteHover")
    fun `resetUnitSpriteHover resets timer to 0 matching idle entry`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val runCancel = ref.run("ram_trace_prologue_cancel_end")
        val ew1440 = runCancel.region("f01440", "ewram")

        val bmSt = BmSt().apply { decode(ew1440, sym["gBmSt"].address - GbaMem.EWRAM) }
        val playSt = PlaySt().apply { decode(ew1440, sym["gPlaySt"].address - GbaMem.EWRAM) }
        val map = BmMap(rom, ChapterData(rom), bmSt).apply { initChapterMap(playSt.chapterIndex) }
        val units = UnitTable(rom).apply { loadFrom(ew1440) }

        val hover = UnitSpriteHover(bmSt, map, units)
        hover.loadFrom(ew1440)
        assertEquals(14, hover.hoverTimer, "f1440 hover timer in dump is 14")

        hover.resetUnitSpriteHover()
        assertEquals(0, hover.hoverTimer, "resetUnitSpriteHover must set timer to 0")

        val ewCopy = ew1440.copyOf()
        hover.storeTo(ewCopy)
        val stored = GbaMem.u32(ewCopy, UnitSpriteHover.EWRAM_BASE, UnitSpriteHover.HOVER_TIMER_ADDRESS)
        assertEquals(0, stored, "stored hover timer in EWRAM must be 0")
    }

    @Test
    @VerifiesPort("UnitSpriteHoverUpdate")
    fun `unitSpriteHoverUpdate matches reference dump timers and transitions`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())
        val runSelect = ref.run("ram_trace_prologue_select_move")

        val ew1255 = runSelect.region("r01255", "ewram")
        val bmSt = BmSt().apply { decode(ew1255, sym["gBmSt"].address - GbaMem.EWRAM) }
        val playSt = PlaySt().apply { decode(ew1255, sym["gPlaySt"].address - GbaMem.EWRAM) }
        val map = BmMap(rom, ChapterData(rom), bmSt).apply { initChapterMap(playSt.chapterIndex) }
        val units = UnitTable(rom).apply { loadFrom(ew1255) }
        EntityBmMaps(map, units, playSt).refreshEntityBmMaps()

        var startMuCalled = 0
        var hideUnitSpriteCalled = 0
        var endAllMusCalled = 0
        var showUnitSpriteCalled = 0

        val hover = UnitSpriteHover(bmSt, map, units)
        hover.hooks.startMu = { startMuCalled++ }
        hover.hooks.hideUnitSprite = { hideUnitSpriteCalled++ }
        hover.hooks.endAllMus = { endAllMusCalled++ }
        hover.hooks.showUnitSprite = { showUnitSpriteCalled++ }

        // At frame 1255, timer in reference EWRAM is 5
        // If we simulate from timer = 4 (frame 1254):
        hover.hoverTimer = 4
        hover.unitSpriteHoverUpdate()
        assertEquals(5, hover.hoverTimer, "timer after 1 step on Eirika")
        assertEquals(1, startMuCalled, "startMu must be called on reaching 5")
        assertEquals(1, hideUnitSpriteCalled, "hideUnitSprite must be called on reaching 5")

        // In reference frames r1255..r1259, verify exact timer values in dumps
        val expectedTimers = mapOf(
            "r01255" to 5,
            "r01256" to 6,
            "r01257" to 7,
            "r01258" to 8,
            "r01259" to 9,
            "f01260" to 9,
        )
        for ((dump, expectedTimer) in expectedTimers) {
            val ew = runSelect.region(dump, "ewram")
            val timerInDump = GbaMem.u32(ew, UnitSpriteHover.EWRAM_BASE, UnitSpriteHover.HOVER_TIMER_ADDRESS)
            assertEquals(expectedTimer, timerInDump, "$dump EWRAM timer value")
        }

        // Test moving off unit: in cancel_end at f1450, cursor is at (5,5), previous was (4,5)
        val runCancel = ref.run("ram_trace_prologue_cancel_end")
        val ew1450 = runCancel.region("f01450", "ewram")
        val timer1450 = GbaMem.u32(ew1450, UnitSpriteHover.EWRAM_BASE, UnitSpriteHover.HOVER_TIMER_ADDRESS)
        assertEquals(0, timer1450, "f1450 timer in dump is 0 after moving off Eirika")

        // Verify that moving cursor triggers cleanup
        hover.hoverTimer = 14
        bmSt.playerCursorX = 5
        bmSt.playerCursorY = 5
        bmSt.cursorPreviousX = 4
        bmSt.cursorPreviousY = 5
        hover.unitSpriteHoverUpdate()
        assertEquals(0, hover.hoverTimer, "timer must reset to 0 when cursor moved off")
        assertEquals(1, endAllMusCalled, "endAllMus must be called on move off")
        assertEquals(1, showUnitSpriteCalled, "showUnitSprite must be called on move off")
    }

    @Test
    fun `cursor OAM equals reference captures at cancel_end f1430 and f1440 and select_move f1260`() {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val rom = RomView.ofRom(romFile.readBytes())

        // 1. cancel_end f1430 (timer 4, before MU starts): cursor objects are 0..3
        val runCancel = ref.run("ram_trace_prologue_cancel_end")
        val hw1430 = runCancel.region("f01430", "oam")
        val ew1430 = runCancel.region("f01430", "ewram")
        val bm1430 = BmSt().apply { decode(ew1430, sym["gBmSt"].address - GbaMem.EWRAM) }

        val layers1430 = SpriteLayers()
        val cursor1430 = MapCursor(rom, bm1430, layers1430)
        cursor1430.put(bm1430.playerCursorDisplayX, bm1430.playerCursorDisplayY, 3)
        val oam1430 = OamBuffer()
        layers1430.pushLayerObjects(4) {
            oam1430.putHi(it.oam1, it.oam0, it.obj!!.data, it.oam2)
        }
        assertEquals(4, oam1430.hiPut / 4, "4 cursor objects generated")
        // Hardware OAM objects 0..3 match
        for (i in 0 until 4) {
            val hw0 = GbaMem.u16(hw1430, 0, i * 8)
            val hw1 = GbaMem.u16(hw1430, 0, i * 8 + 2)
            val hw2 = GbaMem.u16(hw1430, 0, i * 8 + 4)
            val got0 = oam1430.buffer[i * 4].toInt() and 0xFFFF
            val got1 = oam1430.buffer[i * 4 + 1].toInt() and 0xFFFF
            val got2 = oam1430.buffer[i * 4 + 2].toInt() and 0xFFFF
            assertEquals(hw0, got0, "f1430 obj $i oam0")
            assertEquals(hw1, got1, "f1430 obj $i oam1")
            assertEquals(hw2, got2, "f1430 obj $i oam2")
        }

        // 2. cancel_end f1440 (timer 14, MU running): cursor objects are 4..7
        val hw1440 = runCancel.region("f01440", "oam")
        for (i in 0 until 4) {
            val hw0 = GbaMem.u16(hw1440, 0, (4 + i) * 8)
            val hw1 = GbaMem.u16(hw1440, 0, (4 + i) * 8 + 2)
            val hw2 = GbaMem.u16(hw1440, 0, (4 + i) * 8 + 4)
            val got0 = oam1430.buffer[i * 4].toInt() and 0xFFFF
            val got1 = oam1430.buffer[i * 4 + 1].toInt() and 0xFFFF
            val got2 = oam1430.buffer[i * 4 + 2].toInt() and 0xFFFF
            assertEquals(hw0, got0, "f1440 obj ${4 + i} oam0")
            assertEquals(hw1, got1, "f1440 obj ${4 + i} oam1")
            assertEquals(hw2, got2, "f1440 obj ${4 + i} oam2")
        }

        // 3. select_move f1260: cursor objects are 4..7
        val runSelect = ref.run("ram_trace_prologue_select_move")
        val hw1260 = runSelect.region("f01260", "oam")
        for (i in 0 until 4) {
            val hw0 = GbaMem.u16(hw1260, 0, (4 + i) * 8)
            val hw1 = GbaMem.u16(hw1260, 0, (4 + i) * 8 + 2)
            val hw2 = GbaMem.u16(hw1260, 0, (4 + i) * 8 + 4)
            val got0 = oam1430.buffer[i * 4].toInt() and 0xFFFF
            val got1 = oam1430.buffer[i * 4 + 1].toInt() and 0xFFFF
            val got2 = oam1430.buffer[i * 4 + 2].toInt() and 0xFFFF
            assertEquals(hw0, got0, "f1260 obj ${4 + i} oam0")
            assertEquals(hw1, got1, "f1260 obj ${4 + i} oam1")
            assertEquals(hw2, got2, "f1260 obj ${4 + i} oam2")
        }

        // 4. select_move f1250..f1259 per-frame watch check
        val gbmstWatch = runSelect.watch("gbmst")
        for (step in 1250..1259) {
            val chunk = gbmstWatch.step(step)
            val dispX = (chunk[0x20].toInt() and 0xFF or (chunk[0x21].toInt() shl 8)).toShort().toInt()
            val dispY = (chunk[0x22].toInt() and 0xFF or (chunk[0x23].toInt() shl 8)).toShort().toInt()
            val layers = SpriteLayers()
            val cursor = MapCursor(rom, bm1430, layers)
            cursor.put(dispX, dispY, 3)
            val oam = OamBuffer()
            layers.pushLayerObjects(4) {
                oam.putHi(it.oam1, it.oam0, it.obj!!.data, it.oam2)
            }
            assertEquals(4, oam.hiPut / 4, "step $step 4 cursor objects")
            // Verify stretched cursor corners surround dispX, dispY
            val y0 = oam.buffer[0].toInt() and 0xFF
            val x0 = oam.buffer[1].toInt() and 0x1FF
            val tile0 = oam.buffer[2].toInt() and 0x3FF
            assertEquals((dispX - 4) and 0x1FF, x0, "step $step corner top-left x")
            assertEquals((dispY - 8) and 0xFF, y0, "step $step corner top-left y")
            assertEquals(2, tile0, "step $step cursor tile")
        }
    }

    @Test
    fun `wired headless test on Fe8Boot verifies hover cursor kind 3 and MU lifecycle`() {
        assumeTrue(romFile.isFile, "baserom.gba not present")
        assumeTrue(ref.available, "reference data not present")
        val run = ref.run("ram_trace_prologue_resume")
        assumeTrue(run.hasSram, "prologue resume SRAM not present")
        val sramImage = run.sram("s01001")

        // Boot into Prologue turn 1 suspend save
        var frame = 0
        var keyInput = 0
        val boot = Fe8Boot(
            RomView.ofRom(romFile.readBytes()),
            { keyInput },
            RngService(),
            saveStorage = object : SaveStorage {
                override fun load(dest: ByteArray): Boolean {
                    sramImage.copyInto(dest)
                    return true
                }
                override fun store(src: ByteArray) {}
            },
        )

        // Advance to player phase idle
        while (frame < 12000 && boot.engine.find(boot.playerPhaseScript) == null) {
            keyInput = if (frame % 20 in 5..8) 1 else 0
            boot.frame()
            frame++
        }
        var guard = 0
        while (boot.eventEngine.eventEngineExists() && guard++ < 12000) {
            boot.frame()
            frame++
        }
        val proc = boot.engine.find(boot.playerPhaseScript)
        assertNotNull(proc, "player phase proc must exist")

        // Resume starts cursor at (5,5) - empty tile
        assertEquals(5, boot.loop.bmSt.playerCursorX)
        assertEquals(5, boot.loop.bmSt.playerCursorY)
        assertFalse(boot.bmapMain.hooks.playerPhase.name.isEmpty())

        // Press LEFT to move cursor to (4,5) onto Eirika
        keyInput = 1 shl 5 // DPAD_LEFT
        boot.frame()
        assertEquals(4, boot.loop.bmSt.playerCursorX)
        assertEquals(5, boot.loop.bmSt.playerCursorY)

        // Release keys and wait for cursor display sliding to finish (4 frames at 4 px/frame)
        keyInput = 0
        repeat(4) { boot.frame() }
        assertEquals(64, boot.loop.bmSt.playerCursorDisplayX)
        assertEquals(80, boot.loop.bmSt.playerCursorDisplayY)

        // Check that cursor OAM in boot.loop.oam.oam has expanded (kind 3: tile 2, x 60, y 72)
        val oam = boot.loop.oam
        var foundStretchedCursor = false
        for (i in 0 until OamBuffer.OBJ_COUNT) {
            val y = oam.oam[i * 4].toInt() and 0xFF
            val x = oam.oam[i * 4 + 1].toInt() and 0x1FF
            val tile = oam.oam[i * 4 + 2].toInt() and 0x3FF
            if (tile == 2 && x == 60 && y == 72) {
                foundStretchedCursor = true
                break
            }
        }
        assertTrue(foundStretchedCursor, "expanded cursor (kind 3) must be drawn over Eirika")

        // Wait 5 frames for hover timer to trigger MU
        repeat(5) { boot.frame() }
        val muProc = boot.engine.find(boot.mu.script)
        assertNotNull(muProc, "MU proc must start after hovering for 5 frames")

        // Move cursor off Eirika (press RIGHT to 5,5)
        keyInput = 1 shl 4 // DPAD_RIGHT
        boot.frame()
        keyInput = 0
        repeat(4) { boot.frame() }
        assertEquals(5, boot.loop.bmSt.playerCursorX)

        // MU should now be terminated, cursor returns to normal
        val muProcAfter = boot.engine.find(boot.mu.script)
        assertEquals(null, muProcAfter, "MU must end when cursor moves off unit")
    }
}
