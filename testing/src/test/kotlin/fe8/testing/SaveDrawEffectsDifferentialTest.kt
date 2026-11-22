package fe8.testing

import fe8.game.RomView
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterTitle
import fe8.game.mapanim.ApProcs
import fe8.game.save.BmSaveLib
import fe8.game.savemenu.SaveDrawCursor
import fe8.game.savemenu.SaveDrawEffects
import fe8.game.savemenu.SaveDrawLifecycle
import fe8.game.savemenu.SaveDrawParticle
import fe8.game.savemenu.SaveDrawProc
import fe8.game.savemenu.SaveDrawSlots
import fe8.game.savemenu.SaveMenuPostChapter
import fe8.game.savemenu.SaveMenuState
import fe8.game.savemenu.SaveSlotData
import fe8.game.ui.UiHand
import fe8.game.worldmap.GmData
import fe8.game.worldmap.WorldMapApHost
import gba.core.VerifiesPort
import gba.core.sram.CartSram
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.BgVerticalScroll
import gbafe.render.OamBuffer
import gbafe.render.PaletteFade
import gbafe.render.SpriteAnimPlayer
import gbafe.render.SpriteLayers
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential tests for `PC-4c2` ([SaveDrawEffects]: `sub_80AA7AC`, `sub_80AACBC`, and
 * `sub_80AB56C`) and the wired `SaveDraw_Loop_Main` (`SaveDrawLifecycle.wireLoopMain` combining
 * [SaveDrawSlots] and [SaveDrawEffects]) against `ram_trace_prologue_end` (`PC-cap1`,
 * `f9458..9862`).
 *
 * Only `sub_80AA7AC` carries `@VerifiesPort` (its single path cycles all 16 `Pal_08A28088` colors
 * for slot `0`; non-zero slot strides `b * 0x20` for slots `1..2` never run in `PC-cap1`).
 * `sub_80AACBC` (`unk_3c != sus_slot` never taken; `SaveDrawSetDifficultSlotPalette` sees no
 * `PLAY_FLAG_HARD` slot) and `sub_80AB56C` (`unk_04 > 0xB3F` never taken; `unk_0a += unk_0d`
 * unobservable because `unk_0d == 0` throughout) stay `Transcribed`.
 */
class SaveDrawEffectsDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private class Harness(
        val rom: RomView,
        val run: ReferenceData.Run,
        val names: Map<Int, String>,
    ) {
        val engine = ProcEngine()
        val hardware = BgHardware()
        val scroll = BgVerticalScroll()
        val oam = OamBuffer()
        val layers = SpriteLayers()
        val rng = RngService()
        val cursor = SaveDrawCursor(engine, layers, rom)
        val apPlayer = SpriteAnimPlayer(
            { rom.u16(it) },
            layers,
            WorldMapApHost(rom, hardware, oam),
        )
        val apProcs = ApProcs(engine, apPlayer)
        val lifecycle = SaveDrawLifecycle(engine, hardware, scroll, oam, cursor, apProcs, rom)
        val chapterTitle = ChapterTitle(
            rom,
            hardware,
            ChapterData(rom),
            getPlayChapterId = { error("not run") },
            getNextUnclearedNode = { error("not run") },
        )
        val saveLib = BmSaveLib(CartSram()).also { it.sramInit() }
        val slotData = SaveSlotData(saveLib, chapterTitle, hardware, rom)
        var gameClock = 0
        val slots = SaveDrawSlots(layers, rom, cursor, UiHand(rom, layers), apProcs) {
            gameClock
        }
        val effects = SaveDrawEffects(
            hardware,
            oam,
            layers,
            slotData,
            rng.lcg,
            lifecycle.particles,
            rom,
        )
        val archivedFade = ArchivedPaletteFade(PaletteFade(hardware, engine), hardware, engine)
        val menu = SaveMenuPostChapter(
            engine,
            hardware,
            fe8.game.bm.PlaySt(),
            fe8.game.bm.BmSt(),
            GmData(ByteArray(0x8000), rng),
            saveLib,
            archivedFade,
        )

        fun procs(step: Int): ProcArrayDump =
            ProcArrayDump.ofWatch(run.watch("procs"), step, names)

        fun region(step: Int, kind: String): ByteArray {
            val prefix = if (step in GRAPHIC_DUMP_STEPS) "f" else "r"
            return run.region("%s%05d".format(prefix, step), kind)
        }
    }

    private fun createHarness(): Harness {
        assumeTrue(
            ref.available && romFile.isFile,
            "ROM / mGBA reference data not present (run tools/setup.sh)",
        )
        val names = ProcArrayDump.romNames()
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        val run = ref.run(RUN)
        assumeTrue(
            run.hasWatch("procs") && run.hasWatch("rng") && run.hasSram,
            "$RUN lacks required watches or SRAM dumps",
        )
        return Harness(RomView.ofRom(romFile.readBytes()), run, names)
    }

    @Test
    @VerifiesPort("sub_80AA7AC")
    fun `sub80AA7AC cycles all 16 highlight colors and requests palette sync against PC-cap1`() {
        val h = createHarness()

        // Check all 16 entries (unk_2a = 64..127 -> offsets 0..15) across unfaded PC-cap1 frames.
        // Offset i occurs at unk_2a = 64 + 4 * i, which maps to step 9460 + a (since f9460 finished
        // step 0 without advancing unk_2a, and no overrun occurs before step 9800). Verify against
        // captured gPaletteBuffer[0x111] and [0x1A1].
        val seenOffsets = mutableSetOf<Int>()
        for (a in 64..127) {
            val step = 9460 + a
            val sd = h.procs(step).find("ProcScr_savedraw")!!
            // unk_2a in the dump is post-increment, so the arg to sub_80AA7AC is unk_2a - 1.
            val argA = sd.u16(SD_UNK2A) - 1
            assertEquals(a, argA, "expected unk_2a arg at f$step")

            val ew = h.region(step, "ewram")
            val iw = h.region(step, "iwram")
            h.hardware.palette[SaveDrawEffects.PAL_CURSOR_COLOR_OFFSET] = SENTINEL_COLOR
            h.hardware.palette[SaveDrawEffects.PAL_SLOT_BASE_COLOR_OFFSET] = SENTINEL_COLOR
            h.hardware.modifiedPalette = false

            h.effects.sub80AA7AC(argA, 0)

            assertTrue(h.hardware.modifiedPalette, "EnablePaletteSync at f$step")
            assertEquals(
                u8(iw, ADDR_S_MODIFIED_PALETTE - IWRAM) != 0,
                h.hardware.modifiedPalette,
                "sModifiedPalette matches f$step",
            )
            assertEquals(
                u16(ew, ADDR_PALETTE_BUFFER - EWRAM + SaveDrawEffects.PAL_CURSOR_COLOR_OFFSET * 2),
                h.hardware.palette[SaveDrawEffects.PAL_CURSOR_COLOR_OFFSET].toInt() and 0xFFFF,
                "palette[0x111] at f$step (a=$argA)",
            )
            assertEquals(
                u16(
                    ew,
                    ADDR_PALETTE_BUFFER - EWRAM + SaveDrawEffects.PAL_SLOT_BASE_COLOR_OFFSET * 2,
                ),
                h.hardware.palette[SaveDrawEffects.PAL_SLOT_BASE_COLOR_OFFSET].toInt() and 0xFFFF,
                "palette[0x1A1] at f$step (a=$argA)",
            )
            seenOffsets += (argA and 0x3F) ushr 2
        }
        assertEquals((0 until 16).toSet(), seenOffsets, "all 16 palette offsets exercised")
    }

    @Test
    fun `wired SaveDrawSlots and SaveDrawEffects match PC-cap1 f9459 to f9862`() {
        val h = createHarness()
        val ew9458 = h.region(9458, "ewram")
        val iw9458 = h.region(9458, "iwram")
        val rngTrace = h.run.watch("rng")

        h.saveLib.sram.copyIn(h.run.sram("s09449"))
        h.hardware.lcd.readFrom(iw9458, ADDR_LCD_CONTROL - IWRAM)
        h.scroll.readFrom(ew9458, ADDR_BG_VSCROLL - EWRAM)
        for (i in h.hardware.palette.indices) {
            h.hardware.palette[i] = u16(ew9458, ADDR_PALETTE_BUFFER - EWRAM + i * 2).toShort()
        }
        for (i in h.oam.buffer.indices) {
            h.oam.buffer[i] = u16(iw9458, ADDR_OAM - IWRAM + i * 2).toShort()
        }
        readParticles(ew9458, h.lifecycle.particles)
        h.rng.lcg.set(u32(rngTrace.step(9458), 8))

        val sm9458 = h.procs(9458).find("gProcScr_SaveMenuPostChapter")!!
        val gameControl = h.engine.start(ProcScript("gProcScr_GameControl", listOf(block())), 3)
        h.menu.hooks.initScreen = ProcFunc { proc -> h.menu.startSaveDraw(proc) }
        h.menu.wireSaveDraw(h.lifecycle, slots = h.slots, effects = h.effects)

        val menuProc = h.menu.make6cSaveMenuPostChapter(gameControl)!!
        menuProc.idleCb = null
        menuProc.scrCur = sm9458.scrCur - 1
        val menuState = menuProc.state as SaveMenuState
        syncMenuView(menuState, sm9458, sm9458)
        menuState.susSlotCur = sm9458.u8(SM_SUS_SLOT_CUR)
        menuState.unk44 = sm9458.u16(SM_UNK_44)
        h.gameClock = u32(iw9458, ADDR_GAME_CLOCK - IWRAM)

        // Step 9459: SaveDraw_Init + first SaveDraw_Loop_Main.
        // In PC-cap1, VBlank fires mid-sub_80AB56C(1) during f9459 and finishes before r09460.
        h.layers.clear()
        h.engine.run(h.engine.root(3))
        val sdProc = menuState.savedraw!!
        val sdState = sdProc.state as SaveDrawProc

        val sd9459 = h.procs(9459).find("ProcScr_savedraw")!!
        val ew9459 = h.region(9459, "ewram")
        val iw9459 = h.region(9459, "iwram")
        assertEquals(sd9459.u16(SD_UNK2A), sdState.unk2a, "unk_2a at r09459")
        assertEquals(sd9459.u8(SD_UNK3C), sdState.unk3c, "unk_3c at r09459")
        assertEquals(oamAffine(iw9459, 3), oamAffine(h.oam, 3), "OAM affine 3 at r09459")
        assertEquals(
            u16(ew9459, ADDR_PALETTE_BUFFER - EWRAM + 0x111 * 2),
            h.hardware.palette[0x111].toInt() and 0xFFFF,
            "palette[0x111] at r09459",
        )
        assertEquals(
            u16(ew9459, ADDR_PALETTE_BUFFER - EWRAM + 0x1A1 * 2),
            h.hardware.palette[0x1A1].toInt() and 0xFFFF,
            "palette[0x1A1] at r09459",
        )
        assertTrue(h.hardware.modifiedPalette, "modifiedPalette at r09459")

        // Step 9460 finishes the first sub_80AB56C(1) call without advancing SaveDraw_Loop_Main.
        val ew9460 = h.region(9460, "ewram")
        assertEquals(u32(rngTrace.step(9460), 8), h.rng.lcg.value, "gLCGRNValue at r09460")
        assertParticlesMatch(ew9460, h.lifecycle.particles, "r09460")

        var compared = 0
        var sawBookmarkSpin = false
        var sawBookmarkHide = false
        var sawParticleTop = false
        var sawParticleBottom = false
        var sawParticleClipped = false
        var sawDeactivateX = false
        var sawDeactivateYNeg = false
        var sawDeactivateYPos = false
        var sawSpawnCapHit = false
        var sawAccelStep = false
        var sawNonAccelStep = false
        var sawSlotChange = false
        var sawNonZeroAy = false

        for (step in 9461..9862) {
            val dStep = h.procs(step)
            val smDump = dStep.find("gProcScr_SaveMenuPostChapter")!!
            val sdDump = dStep.find("ProcScr_savedraw")!!
            val ew = h.region(step, "ewram")
            val iw = h.region(step, "iwram")

            if (step in OVERRUN_STEPS) {
                assertEquals(sdDump.u16(SD_UNK2A), sdState.unk2a, "overrun unk_2a at f$step")
                assertEquals(u32(rngTrace.step(step), 8), h.rng.lcg.value, "overrun lcg f$step")
                continue
            }

            // At f9814 (overrun), SaveMenuWaitSlotBoxScrolling (scroll_cnt == 8) called
            // SaveMenuInitSaveSlotData(4), switching unk_44 from 0x100 to 0xF0 before f9815 draws.
            if (step == 9815) {
                h.saveLib.sram.copyIn(h.run.sram("s09813"))
                h.slotData.saveMenuInitSaveSlotData(4, menuState.slots)
                assertEquals(0xF0, menuState.unk44, "SaveMenuInitSaveSlotData(4) sets unk_44=0xF0")
            }

            val smNext = h.procs(step + 1).find("gProcScr_SaveMenuPostChapter") ?: smDump
            syncMenuView(menuState, smDump, smNext)
            h.gameClock = u32(iw, ADDR_GAME_CLOCK - IWRAM)

            if (menuState.unk3cChanged(sdState)) sawSlotChange = true
            val preActive = h.lifecycle.particles.count { it.unk0e != 0 }
            val preWillDeactX = h.lifecycle.particles.any { p ->
                p.unk0e != 0 && ((p.unk00 + p.unk08 + 0x5A0).toUInt() > 0x167Fu)
            }
            val preWillDeactYNeg = h.lifecycle.particles.any { p ->
                p.unk0e != 0 && (p.unk04 + p.unk0a) < -0x3C0
            }
            val preWillDeactYPos = h.lifecycle.particles.any { p ->
                p.unk0e != 0 && (p.unk04 + p.unk0a) > 0xB3F
            }
            if (h.lifecycle.particles.any { it.unk0d != 0 }) sawNonZeroAy = true

            h.layers.clear()
            h.hardware.modifiedPalette = false
            h.engine.run(h.engine.root(3))

            // Verify SaveDrawProc fields & SaveMenuProc mutated fields (unk_44, sus_slot_cur).
            assertEquals(sdDump.u16(SD_UNK2A), sdState.unk2a, "unk_2a at f$step")
            assertEquals(sdDump.u8(SD_UNK3C), sdState.unk3c, "unk_3c at f$step")
            assertEquals(smDump.u8(SM_SUS_SLOT_CUR), menuState.susSlotCur, "susSlotCur at f$step")
            assertEquals(smDump.u16(SM_UNK_44), menuState.unk44, "unk_44 at f$step")

            // Verify OAM affine matrix 3 (bookmark spin/scale f9815..9829 and rest/frozen state).
            assertEquals(oamAffine(iw, 3), oamAffine(h.oam, 3), "OAM affine 3 at f$step")
            if (step in 9815..9829) sawBookmarkSpin = true
            if (step == 9830) sawBookmarkHide = true

            // Verify palette sync flag and unfaded cycled palette entries.
            assertTrue(h.hardware.modifiedPalette, "modifiedPalette at f$step")
            assertEquals(
                u8(iw, ADDR_S_MODIFIED_PALETTE - IWRAM) != 0,
                h.hardware.modifiedPalette,
                "sModifiedPalette at f$step",
            )
            if (step in 9493..9844 || step >= 9861) {
                assertEquals(
                    u16(ew, ADDR_PALETTE_BUFFER - EWRAM + 0x111 * 2),
                    h.hardware.palette[0x111].toInt() and 0xFFFF,
                    "palette[0x111] at f$step",
                )
                assertEquals(
                    u16(ew, ADDR_PALETTE_BUFFER - EWRAM + 0x1A1 * 2),
                    h.hardware.palette[0x1A1].toInt() and 0xFFFF,
                    "palette[0x1A1] at f$step",
                )
            }

            // Verify LCG RNG state and all 20 fog particles in gUnknown_020007E0.
            assertEquals(u32(rngTrace.step(step), 8), h.rng.lcg.value, "gLCGRNValue at f$step")
            assertParticlesMatch(ew, h.lifecycle.particles, "f$step")

            // Verify particle sprites submitted on layer 14 match sSpritePool in order.
            val ownParticles = readOwnParticleSprites(h.layers)
            val capParticles = readCapturedParticleSprites(ew, iw)
            assertEquals(capParticles, ownParticles, "particle sprites at f$step")

            if (ownParticles.any { it.obj == SaveDrawEffects.SPRITE_PARTICLE_TOP_ADDRESS }) {
                sawParticleTop = true
            }
            if (ownParticles.any { it.obj == SaveDrawEffects.SPRITE_PARTICLE_BOTTOM_ADDRESS }) {
                sawParticleBottom = true
            }
            if (preActive > ownParticles.size) sawParticleClipped = true
            if (preWillDeactX) sawDeactivateX = true
            if (preWillDeactYNeg) sawDeactivateYNeg = true
            if (preWillDeactYPos) sawDeactivateYPos = true
            if (sdState.unk2a and 3 == 0) sawAccelStep = true else sawNonAccelStep = true
            if (h.lifecycle.particles.count { it.unk0e != 0 } >= 19) sawSpawnCapHit = true

            compared++
        }

        assertEquals(393, compared, "active SaveDraw_Loop_Main frames compared")
        assertTrue(sawBookmarkSpin, "bookmark spin/scale exercised (f9815..9829)")
        assertTrue(sawBookmarkHide, "bookmark hide exercised (f9830)")
        assertTrue(sawParticleTop && sawParticleBottom, "both particle sprites exercised")
        assertTrue(sawParticleClipped, "particle X clipping exercised")
        assertTrue(sawDeactivateX && sawDeactivateYNeg, "X and top-Y deactivation exercised")
        assertTrue(sawAccelStep && sawNonAccelStep, "both (a & 3) == 0 and != 0 exercised")
        assertTrue(sawSpawnCapHit, "count >= 19 spawn cap exercised")
        assertFalse(sawSlotChange, "sus_slot never changes in PC-cap1")
        assertFalse(sawDeactivateYPos, "unk_04 > 0xB3F never taken in PC-cap1")
        assertFalse(sawNonZeroAy, "unk_0d is always 0 in PC-cap1")
    }

    private fun SaveMenuState.unk3cChanged(sd: SaveDrawProc): Boolean = sd.unk3c != susSlot

    private fun syncMenuView(
        menu: SaveMenuState,
        sm: ProcArrayDump.Proc,
        smNext: ProcArrayDump.Proc,
    ) {
        menu.susSlot = sm.u8(SM_SUS_SLOT)
        menu.cursorSlot = sm.u8(SM_CURSOR_SLOT)
        menu.jumpLabel = sm.u8(SM_JUMP_LABEL)
        menu.unk2f = sm.u8(SM_UNK_2F)
        menu.cursorConfig = sm.u8(SM_CURSOR_CONFIG)
        menu.mainSelBitfile = sm.u16(SM_MAIN_SEL_BITFILE)
        menu.extraSelBitfile = sm.u8(SM_EXTRA_SEL_BITFILE)
        menu.unk46 = sm.u16(SM_UNK_46)
        for (i in 0 until 3) menu.slots.playedTime[i] = smNext.u32(SM_PLAYED_TIME + 4 * i)
        menu.slots.totalTime = smNext.u32(SM_TOTAL_TIME)
    }

    private data class ParticleSprite(
        val layer: Int,
        val x: Int,
        val y: Int,
        val oam2: Int,
        val obj: Int,
    )

    private fun readOwnParticleSprites(layers: SpriteLayers): List<ParticleSprite> {
        val out = ArrayList<ParticleSprite>()
        layers.pushLayerObjects(SaveDrawEffects.PARTICLE_SPRITE_LAYER) { entry ->
            val objId = entry.obj?.id
            if (objId in PARTICLE_SPRITE_ADDRESSES) {
                out += ParticleSprite(
                    SaveDrawEffects.PARTICLE_SPRITE_LAYER,
                    entry.oam1,
                    entry.oam0,
                    entry.oam2,
                    objId!!,
                )
            }
        }
        return out
    }

    private fun readCapturedParticleSprites(
        ewram: ByteArray,
        iwram: ByteArray,
    ): List<ParticleSprite> {
        val allocIt = GbaMem.u32(iwram, IWRAM, ADDR_G_SPRITE_ALLOC_IT)
        val count = (allocIt - ADDR_S_SPRITE_POOL) / SPRITE_ENTRY_SIZE
        val layer14Head = ewramSliceHead(ewram, SaveDrawEffects.PARTICLE_SPRITE_LAYER)
        val out = ArrayList<ParticleSprite>()
        var addr = u32(ewram, layer14Head - EWRAM)
        val poolEnd = ADDR_S_SPRITE_POOL + count * SPRITE_ENTRY_SIZE
        while (addr in ADDR_S_SPRITE_POOL until poolEnd) {
            val base = addr - EWRAM
            val obj = u32(ewram, base + 0x0C)
            if (obj in PARTICLE_SPRITE_ADDRESSES) {
                out += ParticleSprite(
                    SaveDrawEffects.PARTICLE_SPRITE_LAYER,
                    s16(ewram, base + 0x04),
                    s16(ewram, base + 0x06),
                    u16(ewram, base + 0x08),
                    obj,
                )
            }
            addr = u32(ewram, base + 0x00)
        }
        return out
    }

    private fun ewramSliceHead(ewram: ByteArray, layer: Int): Int =
        ADDR_S_SPRITE_LAYERS + layer * SPRITE_ENTRY_SIZE

    private fun oamAffine(iwram: ByteArray, matrix: Int): List<Int> = listOf(
        s16(iwram, ADDR_OAM - IWRAM + (matrix * 4 + 0) * 8 + 6),
        s16(iwram, ADDR_OAM - IWRAM + (matrix * 4 + 1) * 8 + 6),
        s16(iwram, ADDR_OAM - IWRAM + (matrix * 4 + 2) * 8 + 6),
        s16(iwram, ADDR_OAM - IWRAM + (matrix * 4 + 3) * 8 + 6),
    )

    private fun oamAffine(oam: OamBuffer, matrix: Int): List<Int> = listOf(
        oam.buffer[matrix * 16 + 3].toInt(),
        oam.buffer[matrix * 16 + 7].toInt(),
        oam.buffer[matrix * 16 + 11].toInt(),
        oam.buffer[matrix * 16 + 15].toInt(),
    )

    private fun readParticles(ewram: ByteArray, dst: Array<SaveDrawParticle>) {
        val base = ADDR_G_UNKNOWN_020007E0 - EWRAM
        for (i in dst.indices) {
            val o = base + i * PARTICLE_SIZE
            dst[i].unk00 = u32(ewram, o + 0x00)
            dst[i].unk04 = u32(ewram, o + 0x04)
            dst[i].unk08 = s16(ewram, o + 0x08)
            dst[i].unk0a = s16(ewram, o + 0x0A)
            dst[i].unk0c = ewram[o + 0x0C].toInt()
            dst[i].unk0d = ewram[o + 0x0D].toInt()
            dst[i].unk0e = ewram[o + 0x0E].toInt()
        }
    }

    private fun assertParticlesMatch(
        ewram: ByteArray,
        actual: Array<SaveDrawParticle>,
        label: String,
    ) {
        val expected = Array(SaveDrawLifecycle.PARTICLE_COUNT) { SaveDrawParticle() }
        readParticles(ewram, expected)
        for (i in actual.indices) {
            assertEquals(
                particleFields(expected[i]),
                particleFields(actual[i]),
                "gUnknown_020007E0[$i] at $label",
            )
        }
    }

    private fun particleFields(p: SaveDrawParticle): List<Int> =
        listOf(p.unk00, p.unk04, p.unk08, p.unk0a, p.unk0c, p.unk0d, p.unk0e)

    private companion object {
        const val RUN = "ram_trace_prologue_end"
        const val EWRAM = GbaMem.EWRAM
        const val IWRAM = GbaMem.IWRAM

        const val ADDR_BG_VSCROLL = 0x02000000
        const val ADDR_G_UNKNOWN_020007E0 = 0x020007E0
        const val ADDR_PALETTE_BUFFER = 0x020228A8
        const val ADDR_S_SPRITE_POOL = 0x02029D8C
        const val ADDR_S_SPRITE_LAYERS = 0x0202A58C
        const val ADDR_S_MODIFIED_PALETTE = 0x0300000E
        const val ADDR_GAME_CLOCK = 0x03000014
        const val ADDR_LCD_CONTROL = 0x03003080
        const val ADDR_OAM = 0x03003140
        const val ADDR_G_SPRITE_ALLOC_IT = 0x03004970

        const val PARTICLE_SIZE = 0x10
        const val SPRITE_ENTRY_SIZE = 0x10
        const val SENTINEL_COLOR: Short = 0x7FFF

        const val SM_SUS_SLOT = 0x2C
        const val SM_CURSOR_SLOT = 0x2D
        const val SM_JUMP_LABEL = 0x2E
        const val SM_UNK_2F = 0x2F
        const val SM_EXTRA_SEL_BITFILE = 0x35
        const val SM_CURSOR_CONFIG = 0x36
        const val SM_SUS_SLOT_CUR = 0x3F
        const val SM_MAIN_SEL_BITFILE = 0x42
        const val SM_UNK_44 = 0x44
        const val SM_UNK_46 = 0x46
        const val SM_PLAYED_TIME = 0x48
        const val SM_TOTAL_TIME = 0x54

        const val SD_UNK2A = 0x2A
        const val SD_UNK3C = 0x3C

        val PARTICLE_SPRITE_ADDRESSES = setOf(
            SaveDrawEffects.SPRITE_PARTICLE_BOTTOM_ADDRESS,
            SaveDrawEffects.SPRITE_PARTICLE_TOP_ADDRESS,
        )
        val OVERRUN_STEPS = setOf(9800, 9801, 9813, 9814, 9839, 9840, 9841, 9842, 9843)
        val GRAPHIC_DUMP_STEPS = setOf(9444, 9494, 9500, 9600, 9700, 9799, 9800, 9802, 9863)

        fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
        fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
        fun s16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()
        fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}
