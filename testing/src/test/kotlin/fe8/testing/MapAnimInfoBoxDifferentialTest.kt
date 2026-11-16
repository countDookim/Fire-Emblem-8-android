package fe8.testing

import fe8.game.RomView
import fe8.game.bm.MAInfoFrameProc
import fe8.game.bm.ManimSt
import fe8.game.bm.MapAnimInfoBox
import fe8.game.text.Fe8Text
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import gbafe.render.BgHardware
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextHost
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Differential test verifying the map battle animation info window (EP-20,
 * `src/mapanim_infobox.c` and `PutStringCentered` in `src/bmlib.c`) against the Prologue
 * clear capture `ram_trace_prologue_clear` (steps 4468..4480, frames f4470 and f4480).
 */
class MapAnimInfoBoxDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private lateinit var romBytes: ByteArray
    private lateinit var rom: RomView
    private lateinit var messages: MsgStrings

    @BeforeEach
    fun setUp() {
        val assets = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val romFile = File(assets, "baserom.gba")
        assumeTrue(ref.available && romFile.isFile, "Capture and ROM must be available")
        romBytes = romFile.readBytes()
        rom = RomView.ofRom(romBytes)
        val root = u32(romBytes, 0x0815D488 - 0x08000000) and 0x01FFFFFF
        messages = MsgStrings(
            romBytes,
            MsgHuffman(romBytes, 0x0815A72C - 0x08000000, root),
            0x0815D48C - 0x08000000,
        )
    }

    private fun createHwText(hw: BgHardware): TextSystem {
        val host = object : TextHost {
            override fun language(): Int = 1
            override fun applyTextPalette(talk: Boolean, palid: Int) {
                val palAddr = 0x0859EF20 + if (talk) 0x20 else 0
                val base = palid * 16
                for (i in 0 until 16) {
                    hw.palette[base + i] = rom.u16(palAddr + 2 * i).toShort()
                }
                hw.palette[base] = 0
                hw.enablePaletteSync()
            }
            override fun readVram32(addr: Int): Int {
                val offset = addr - 0x06000000
                return (hw.vram[offset].toInt() and 0xFF) or
                    ((hw.vram[offset + 1].toInt() and 0xFF) shl 8) or
                    ((hw.vram[offset + 2].toInt() and 0xFF) shl 16) or
                    ((hw.vram[offset + 3].toInt() and 0xFF) shl 24)
            }
            override fun writeVram32(addr: Int, value: Int) {
                val offset = addr - 0x06000000
                for (k in 0..3) {
                    hw.vram[offset + k] = (value shr (8 * k)).toByte()
                }
            }
            override fun clearVram(dest: Int, byteCount: Int) {
                val offset = dest - 0x06000000
                hw.vram.fill(0, offset, offset + byteCount)
            }
        }
        val ts = TextSystem(romBytes, host, Fe8Text.CONFIG)
        ts.resetText()
        return ts
    }

    @Test
    @VerifiesPort("PutStringCentered")
    fun `putStringCentered matches the name rows of the BG0 tilemap at f4470`() {
        val run = ref.run("ram_trace_prologue_clear")
        val ewram = run.region("f04470", "ewram")
        val hw = BgHardware()
        val ts = createHwText(hw)
        val bg0 = hw.tilemaps[0]

        // Actor 0 then actor 1 at the captured hp_info_x/y, as DisplayBattleInfoBox draws them.
        val manim = ManimSt()
        setActors(manim, ewram)
        for (i in 0 until manim.actorCount) {
            val actor = MANIM_ST + i * ACTOR_SIZE
            val x = ewram[actor + 0x10].toInt() and 0xFF
            val y = ewram[actor + 0x11].toInt() and 0xFF
            val charData = u32(ewram, manim.unitOffset[i])
            val name = messages.getStringFromIndex(rom.u16(charData))
            ts.putStringCentered(bg0, (y + 1) * 32 + x + 2, 0, 9, name)
        }

        for (col in 0 until 32) {
            val expected = u16(ewram, BG0_TILEMAP + (16 * 32 + col) * 2)
            assertEquals(expected, bg0[16 * 32 + col].toInt() and 0xFFFF, "name row col $col")
        }
    }

    @Test
    @VerifiesPort("UncompMapBattleBoxNumGfx")
    fun `uncompMapBattleBoxNumGfx decompresses numbers into BG0 tile VRAM matching f4470`() {
        val run = ref.run("ram_trace_prologue_clear")
        val vram = run.region("f04470", "vram")
        val hw = BgHardware()
        val engine = ProcEngine()
        val manim = ManimSt()
        val ts = createHwText(hw)
        val infoBox = MapAnimInfoBox(engine, manim, rom, ts, messages, hw)

        infoBox.uncompMapBattleBoxNumGfx(0x20)

        val expected = vram.copyOfRange(0x400, 0x400 + 352)
        val actual = hw.vram.copyOfRange(0x400, 0x400 + 352)
        assertArrayEquals(expected, actual, "decompressed number glyphs in VRAM at 0x400")
    }

    @Test
    @VerifiesPort("PrepareMapBattleBoxNumGfx")
    fun `prepareMapBattleBoxNumGfx decompresses HP bar and applies number palette`() {
        val run = ref.run("ram_trace_prologue_clear")
        val vram = run.region("f04470", "vram")
        val ewram = run.region("f04470", "ewram")
        val hw = BgHardware()
        val engine = ProcEngine()
        val manim = ManimSt()
        val ts = createHwText(hw)
        val infoBox = MapAnimInfoBox(engine, manim, rom, ts, messages, hw)

        infoBox.prepareMapBattleBoxNumGfx(MapAnimInfoBox.IMG_MAP_BATTLE_INFO_HP_BAR)

        // Numbers in VRAM at 0x400
        val expNum = vram.copyOfRange(0x400, 0x400 + 352)
        val actNum = hw.vram.copyOfRange(0x400, 0x400 + 352)
        assertArrayEquals(expNum, actNum, "decompressed number tiles in VRAM at 0x400")

        // HP bar in VRAM at 0x560
        val expHp = vram.copyOfRange(0x560, 0x560 + 672)
        val actHp = hw.vram.copyOfRange(0x560, 0x560 + 672)
        assertArrayEquals(expHp, actHp, "decompressed HP bar tiles in VRAM at 0x560")

        // Palette bank 5 in gPaletteBuffer
        val palOff = PALETTE_BUFFER + 5 * 32
        for (i in 0 until 16) {
            val expColor = u16(ewram, palOff + 2 * i).toShort()
            assertEquals(expColor, hw.palette[5 * 16 + i], "palette bank 5 color $i")
        }
    }

    @Test
    @VerifiesPort("GetBattleInfoPalByFaction")
    fun `getBattleInfoPalByFaction gives the palettes of both captured factions`() {
        val run = ref.run("ram_trace_prologue_clear")
        val ewram = run.region("f04470", "ewram")
        val hw = BgHardware()
        val infoBox = MapAnimInfoBox(ProcEngine(), ManimSt(), rom, createHwText(hw), messages, hw)
        val manim = ManimSt()
        setActors(manim, ewram)

        // Bank 1 + actor index holds the faction palette; the Prologue exercises two factions.
        val palettes = mutableSetOf<Int>()
        for (i in 0 until manim.actorCount) {
            val faction = ewram[manim.unitOffset[i] + MapAnimInfoBox.OFF_INDEX].toInt() and 0xFF
            val pal = infoBox.getBattleInfoPalByFaction(faction)
            palettes += pal
            for (c in 0 until 16) {
                val expected = u16(ewram, PALETTE_BUFFER + (1 + i) * 32 + 2 * c)
                assertEquals(expected, rom.u16(pal + 2 * c), "actor $i palette color $c")
            }
        }
        assertEquals(2, palettes.size, "the two factions use different palettes")
    }

    @Test
    @VerifiesPort("MapInfoBox_PrepareForShake")
    fun `mapInfoBoxPrepareForShake resets unk2A and sets the window registers of f4470`() {
        val run = ref.run("ram_trace_prologue_clear")
        val iwram = run.region("f04470", "iwram")
        val engine = ProcEngine()
        val manim = ManimSt()
        val hw = BgHardware()
        val ts = createHwText(hw)
        val infoBox = MapAnimInfoBox(engine, manim, rom, ts, messages, hw)

        var unk2AAtShake = -1
        infoBox.hooks.mapInfoBoxShake = { p -> unk2AAtShake = p.unk2A }

        val proc = MAInfoFrameProc(unk2A = 10, x = 15, y = 15)
        infoBox.mapInfoBoxPrepareForShake(proc)

        assertEquals(0, unk2AAtShake, "unk2A reset before MapInfoBoxShake")
        // The capture's gLCDControlBuffer; the colour-effect enable bits (0x20 in WININ/WINOUT)
        // come from the scanline effect, not from this function, so only the layer bits compare.
        val dispcnt = u16(iwram, LCD_BUFFER)
        val wincnt = u32(iwram, LCD_BUFFER + LCD_WINCNT)
        assertEquals(dispcnt and DISPCNT_WIN_MASK, hw.lcd.dispcnt and DISPCNT_WIN_MASK, "DISPCNT")
        assertEquals(wincnt and WIN_LAYERS, hw.lcd.wincnt and WIN_LAYERS, "win0 layers")
        assertEquals(
            (wincnt shr 16) and WIN_LAYERS, (hw.lcd.wincnt shr 16) and WIN_LAYERS, "wout layers",
        )
    }

    @Test
    @VerifiesPort("DisplayBattleInfoBox")
    fun `displayBattleInfoBox positions actor info box, applies TSA and palette matching f4470`() {
        val run = ref.run("ram_trace_prologue_clear")
        val ewram = run.region("f04470", "ewram")
        val hw = BgHardware()
        val engine = ProcEngine()
        val manim = ManimSt()
        val ts = createHwText(hw)
        val infoBox = MapAnimInfoBox(engine, manim, rom, ts, messages, hw)

        setActors(manim, ewram)

        val proc = MAInfoFrameProc(x = 15, y = 15)
        val subCalls = mutableListOf<Int>()
        infoBox.hooks.sub807BD54 = { _, idx -> subCalls += idx }

        infoBox.displayBattleInfoBox(proc, 0, -1)
        infoBox.displayBattleInfoBox(proc, 1, -11)

        // hp_info_x/y and hp_displayed_q4 of both actors, as the capture's gManimSt holds them.
        for (i in 0..1) {
            val actor = MANIM_ST + i * ACTOR_SIZE
            assertEquals(ewram[actor + 0x10].toInt() and 0xFF, manim.hpInfoX[i], "actor $i x")
            assertEquals(ewram[actor + 0x11].toInt() and 0xFF, manim.hpInfoY[i], "actor $i y")
            assertEquals(u16(ewram, actor + 0x0E), manim.hpDisplayedQ4[i], "actor $i hp q4")
        }
        assertEquals(listOf(0, 1), subCalls)

        // Palette banks 1 and 2 in hardware palette match f04470 gPaletteBuffer
        for (i in 0 until 16) {
            val expBlue = u16(ewram, PALETTE_BUFFER + 1 * 32 + 2 * i).toShort()
            val expRed = u16(ewram, PALETTE_BUFFER + 2 * 32 + 2 * i).toShort()
            assertEquals(expBlue, hw.palette[1 * 16 + i], "palette bank 1 color $i")
            assertEquals(expRed, hw.palette[2 * 16 + i], "palette bank 2 color $i")
        }

        // BG1 tilemap rows 15..19 match f04470 gBG1TilemapBuffer
        val bg1Off = BG1_TILEMAP
        for (r in 15..19) {
            for (c in 0 until 32) {
                val exp = u16(ewram, bg1Off + (r * 32 + c) * 2)
                val act = hw.tilemaps[1][r * 32 + c].toInt() and 0xFFFF
                assertEquals(exp, act, "BG1 row $r col $c")
            }
        }
    }

    @Test
    @VerifiesPort("ProcMapInfoBox_OnDraw")
    fun `procMapInfoBoxOnDraw decompresses frame graphics and draws boxes matching f4470`() {
        val run = ref.run("ram_trace_prologue_clear")
        val ewram = run.region("f04470", "ewram")
        val vram = run.region("f04470", "vram")
        val hw = BgHardware()
        val engine = ProcEngine()
        val manim = ManimSt()
        val ts = createHwText(hw)
        val infoBox = MapAnimInfoBox(engine, manim, rom, ts, messages, hw)

        setActors(manim, ewram)

        var scanlineInited = false
        var gradTop = -1
        var gradBottom = -1
        var gradColA = -1
        var gradColB = -1
        infoBox.hooks.initScanline = { scanlineInited = true }
        infoBox.hooks.startManimFrameGradientScanlineEffect = { top, bottom, colA, colB ->
            gradTop = top
            gradBottom = bottom
            gradColA = colA
            gradColB = colB
        }

        val proc = MAInfoFrameProc(x = 15, y = 15)
        infoBox.procMapInfoBoxOnDraw(proc)

        assertTrue(scanlineInited, "initScanline called")
        assertEquals(120, gradTop, "gradient top (15 * 8)")
        assertEquals(152, gradBottom, "gradient bottom (120 + 32)")
        assertEquals(hw.palette[1 * 16 + 1].toInt(), gradColA)
        assertEquals(hw.palette[2 * 16 + 1].toInt(), gradColB)

        // Frame graphics decompressed into VRAM at 0x20
        val expBox = vram.copyOfRange(0x20, 0x400)
        val actBox = hw.vram.copyOfRange(0x20, 0x400)
        assertArrayEquals(expBox, actBox, "decompressed info box frame tiles at 0x20")

        // BG1 tilemap matches f04470
        val bg1Off = BG1_TILEMAP
        for (r in 15..19) {
            for (c in 0 until 32) {
                val exp = u16(ewram, bg1Off + (r * 32 + c) * 2)
                val act = hw.tilemaps[1][r * 32 + c].toInt() and 0xFFFF
                assertEquals(exp, act, "BG1 row $r col $c")
            }
        }
    }

    @Test
    @VerifiesPort("StartMapAnimInfoWindow")
    fun `info box proc follows the capture through steps 4468 to 4480`() {
        val run = ref.run("ram_trace_prologue_clear")
        val procsTrace = run.watch("procs")
        val ewram = run.region("f04470", "ewram")
        val names = ProcArrayDump.romNames()

        val engine = ProcEngine()
        val manim = ManimSt()
        val hw = BgHardware()
        val ts = createHwText(hw)
        val infoBox = MapAnimInfoBox(engine, manim, rom, ts, messages, hw)
        setActors(manim, ewram)

        // MapInfoBoxShake is EP-21; the test supplies its counter and its Proc_Break only.
        lateinit var infoProc: Proc
        infoBox.hooks.mapInfoBoxShake = { p ->
            p.unk2A += 2
            if (p.unk2A > SHAKE_LIMIT) engine.breakProc(infoProc)
        }

        val parent = engine.start(
            ProcScript("parent", listOf(ProcScripts.sleep(100), ProcScripts.end())),
            2,
        )
        infoProc = infoBox.startMapAnimInfoWindow(15, 15, parent)
        assertEquals(MapAnimInfoBox.TREE_INFOBOX, infoProc.rootIndex)
        assertEquals(parent, (infoProc.state as MAInfoFrameProc).maMain)

        // The capture samples at frame boundaries, and the frame that runs ProcMapInfoBox_OnDraw
        // spans a boundary: sample 4470 is taken before that frame's PROC_REPEAT ran, sample 4471
        // is the state after it. One engine frame therefore covers samples 4470 and 4471.
        for (step in 4468..4480) {
            val orig = checkNotNull(
                ProcArrayDump.ofWatch(procsTrace, step, names).find("ProcScr_MapBattleInfoBox"),
            ) { "step $step ProcScr_MapBattleInfoBox" }
            val mine = infoProc.state as MAInfoFrameProc
            if (step != 4470) {
                assertEquals(orig.scrCur, infoProc.scrCur, "step $step scrCur")
                // unk2A is uninitialised before PrepareForShake runs.
                if (step >= 4471) assertEquals(orig.u16(0x2A), mine.unk2A, "step $step unk2A")
            }
            assertEquals(orig.u16(0x2C), mine.unk2C, "step $step unk2C")
            assertEquals(orig.u8(0x2E), mine.x, "step $step x")
            assertEquals(orig.u8(0x2F), mine.y, "step $step y")
            if (step != 4470) engine.run(engine.root(MapAnimInfoBox.TREE_INFOBOX))
        }
    }

    private companion object {
        // Byte offsets into the capture's EWRAM dump (address - 0x02000000) of decomp symbols.
        const val PALETTE_BUFFER = 0x228A8 // gPaletteBuffer
        const val BG0_TILEMAP = 0x22CA8 // gBG0TilemapBuffer
        const val BG1_TILEMAP = 0x234A8 // gBG1TilemapBuffer
        const val MANIM_ST = 0x3E1F0 // gManimSt
        const val ACTOR_SIZE = 0x14 // sizeof(struct MapAnimActorState)
        const val MANIM_ACTOR_COUNT = 0x5E // gManimSt.actorCount
        const val SHAKE_LIMIT = 0x10 // MapInfoBoxShake: break once unk2A exceeds this
                const val LCD_BUFFER = 0x3080 // gLCDControlBuffer, IWRAM
        const val LCD_WINCNT = 0x34 // gLCDControlBuffer.wincnt
        const val DISPCNT_WIN_MASK = 0xE000 // DISPCNT_WIN0_ON | WIN1_ON | WINOBJ_ON
        const val WIN_LAYERS = 0x1F // win0_enableBg0..Obj / wout_enableBg0..Obj

        /** Fills actor state of [manim] from the capture's gManimSt, units in the same dump. */
        fun setActors(manim: ManimSt, ewram: ByteArray) {
            manim.actorCount = ewram[MANIM_ST + MANIM_ACTOR_COUNT].toInt() and 0xFF
            for (i in 0 until manim.actorCount) {
                val actor = MANIM_ST + i * ACTOR_SIZE
                manim.unitBytes[i] = ewram
                manim.unitOffset[i] = u32(ewram, actor) - 0x02000000
                manim.hpCur[i] = ewram[actor + 0x0D].toInt() and 0xFF
            }
        }
        private fun u16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun u32(bytes: ByteArray, offset: Int): Int =
            u16(bytes, offset) or (u16(bytes, offset + 2) shl 16)
    }
}
