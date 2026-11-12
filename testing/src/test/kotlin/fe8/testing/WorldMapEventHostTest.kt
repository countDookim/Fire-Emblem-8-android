package fe8.testing

import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.bm.ChapterData
import fe8.game.bm.PlaySt
import fe8.game.event.EventBgm
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventEngineProc
import fe8.game.event.EventInput
import fe8.game.event.EventScrCore
import fe8.game.event.EventState
import fe8.game.event.ScriptMemory
import fe8.game.flags.Fe8EventFlags
import fe8.game.worldmap.GmData
import fe8.game.worldmap.GmapLineFadeProc
import fe8.game.worldmap.GmapMuEntryProc
import fe8.game.worldmap.GmapRmProc
import fe8.game.worldmap.WmBigMapHooks
import fe8.game.worldmap.WmNodeData
import fe8.game.worldmap.WmPathData
import fe8.game.worldmap.WmTextFadeHooks
import fe8.game.worldmap.WorldMapBeginning
import fe8.game.worldmap.WorldMapBigMapEvents
import fe8.game.worldmap.WorldMapHblank
import fe8.game.worldmap.WorldMapLineFade
import fe8.game.worldmap.WorldMapMain
import fe8.game.worldmap.WorldMapMainProc
import fe8.game.worldmap.WorldMapMuEntry
import fe8.game.worldmap.WorldMapPath
import fe8.game.worldmap.WorldMapRm
import fe8.game.worldmap.WorldMapScroll
import fe8.game.worldmap.WorldMapSpawnEvents
import fe8.game.worldmap.WorldMapTextFadeEvents
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.end
import gbafe.render.ArchivedPaletteFade
import gbafe.render.BgHardware
import gbafe.render.PaletteFade
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The opening of the Prologue world map, steps 4907-5100 of `ram_trace_prologue`, run through the
 * event VM: `EventScrWM_Prologue_Beginning` (started by `WorldMap_CallBeginningEvent`'s
 * `CallEvent`) drives the opcodes registered one by one below; the `GmapRM`, `GmapLineFade`,
 * `BmFadeIN` and `GmapMuEntry2` procs are started by the script, not by the test.
 *
 * Seeds: the `r04920` dump (GmData with the camera and `no_fade` poisoned, LCD buffer, palette
 * buffer, scanline buffer, BG0 tilemap: `WorldMap_Init` and the fade set them before 4907 and
 * nothing the script runs changes them before 4944). Port frame
 * `n` is step `4907 + n` up to 4943; steps 4944-4947 are lag frames of one original callback
 * (`GmapRm_StartUpdateDirect`, whose snapshots show a half-run frame), so frame `n >= 37` is
 * step `4911 + n`.
 */
class WorldMapEventHostTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    private fun available() =
        ref.available && romFile.isFile && ProcArrayDump.romNames().isNotEmpty()

    @Test
    @VerifiesPort(
        "EventC2_WmNoFade", "EventC3_SpawnLord", "GetGmMuEntryFlag", "StartGmapLineFade",
        "IsGmapLineFadeActive", "GmapLineFade_Loop",
        "GmapLineFade_80C0358", "GmapLineFade_OnEnd", "EventAF_WmShowTextBox",
        "EventB1_WmTextBoxWait", "EventB2_WmFancyFade", "EventB3_WmFancyFadeWait",
        "EventB4_WmDisplayBigMap",
    )
    fun `the opening script starts the map, fades and entry bands like the original`() {
        assumeTrue(available(), "reference data / ROM / nm file not present")
        val names = ProcArrayDump.romNames()
        val run = ref.run("ram_trace_prologue")
        val trace = run.watch("procs")
        val playStTrace = run.watch("gplayst")
        val rom = RomView.ofRom(romFile.readBytes())
        val iwram = run.region("r04920", "iwram").copyOf(0x8000)
        // Hardware state `WorldMap_Init` and the fade left (nothing changes it before 4944).
        val hwIwram = run.region("r04920", "iwram")
        val ewram = run.region("r04920", "ewram")
        // Poison what the script writes (the camera 0,0 to 32,8, `no_fade`), so a missing write
        // fails against the dumps.
        val seedGm = GmData(iwram, RngService())
        seedGm.xCamera = 0x7F
        seedGm.yCamera = 0x7F
        seedGm.stateRaw = seedGm.stateRaw and 0x80.inv()

        val engine = ProcEngine()
        engine.init()
        val gm = GmData(iwram, RngService())
        val hw = BgHardware()
        // Screen blocks as the capture's BGCNT registers set them.
        val bgcnt = run.region("f05100", "io")
        for (bg in 0..3) hw.setMapDataOffset(bg, ((bgcnt.u16(8 + 2 * bg) shr 8) and 0x1F) * 0x800)
        hw.initBmBgLayers(false)
        hw.lcd.readFrom(hwIwram, LCD_BUFFER)
        for (i in 0 until 512) hw.palette[i] = ewram.u16(PALETTE_BUFFER + 2 * i).toShort()
        for (i in hw.tilemaps[0].indices) hw.tilemaps[0][i] = ewram.u16(BG0_TILEMAP + 2 * i).toShort()
        val hblank = WorldMapHblank()
        for (i in hblank.buffer.indices) hblank.buffer[i] = ewram.u16(HB_BUFFER + 2 * i).toShort()
        for (i in 0..2) hblank.registers[i] = ewram.u32(HB_REGISTERS + 4 * i)
        hblank.page = ewram[HB_PAGE].toInt() and 0xFF
        hblank.status = ewram[HB_STATUS].toInt() and 0xFF

        val mem = ScriptMemory { a -> rom.u16(a) }
        val playSt = PlaySt()
        playSt.chapterIndex = 0x55
        playSt.chapterModeIndex = playStTrace.u8(4860, 0x1B)
        val flags = Fe8EventFlags.create().also { it.copyIn(run.watch("flags").step(4860)) }
        val eventState = EventState()
        val events = EventEngine(
            engine, eventState, mem,
            object : EventEngineHooks {
                override fun blendY() = hw.lcd.blendY
                override fun chapterIndex() = playSt.chapterIndex
            },
        )
        val nodes = WmNodeData(rom)

        // Services.
        val muEntry = WorldMapMuEntry(engine, hw, rom, hblank)
        val rm = WorldMapRm(engine, hw, rom, gm)
        val fade = ArchivedPaletteFade(PaletteFade(hw, engine), hw, engine)
        val lineFade = WorldMapLineFade(engine, hw.lcd, hblank) { fade.newFadeIn(it, null) }
        val scroll = WorldMapScroll(engine, gm, nodes)
        val core = EventScrCore(
            eventState, mem, object : EventInput {}, flags, RngService(), playSt, KeyStatus(),
        )
        val bgm = EventBgm(eventState, mem, SoundWrapper(engine, null) { 0 })
        val spawn = WorldMapSpawnEvents(mem, gm) { _, _, _, _ -> error("slot already holds Eirika") }
        val bigMap = WorldMapBigMapEvents(
            mem, WmBigMapHooks(startGmapRm = { x, y, c -> rm.startGmapRm(x, y, c, null) }),
        )
        val textFade = WorldMapTextFadeEvents(
            mem, gbafe.render.ScreenFade(engine, hw.lcd),
            WmTextFadeHooks(
                gmMuEntryStartShow = { s, b -> muEntry.gmMuEntryStartShow(s, b) },
                gmMuEntryFlag = muEntry::getGmMuEntryFlag,
                startGmapLineFade = { lineFade.startGmapLineFade(it) },
                isGmapLineFadeActive = lineFade::isGmapLineFadeActive,
            ),
        )
        // Exactly the opcodes of steps 4907-5100.
        events.register(0x0E, core.event0eStal)
        events.register(0x10, core.event10ModifyEvBit)
        events.register(0x12, bgm.event12StartBgm)
        events.register(0x13, bgm.event13BgmFadeIn)
        events.register(0x85, scroll.CameraEvents(mem).event85SetCameraOntoUnit)
        events.register(0xAF, textFade.eventAFWmShowTextBox)
        events.register(0xB1, textFade.eventB1WmTextBoxWait)
        events.register(0xB2, textFade.eventB2WmFancyFade)
        events.register(0xB3, textFade.eventB3WmFancyFadeWait)
        events.register(0xB4, bigMap.eventB4WmDisplayBigMap)
        events.register(0xC2, spawn.eventC2WmNoFade)
        events.register(0xC3, spawn.eventC3SpawnLord)

        // `WorldMap_CallBeginningEvent` starts the script and the entry bands (step 4907).
        val main = WorldMapMain(
            engine, playSt, gm, ProcScript("m", listOf(end())), ProcScript("f", listOf(end())),
        )
        val beginning = WorldMapBeginning(
            rom, ChapterData(rom), WorldMapPath(gm, WmPathData(rom)), events, {},
        )
        val proc = WorldMapMainProc()
        main.callBeginningEvent(
            proc, nodes, { flags.checkFlag(it) }, beginning.hooks(proc, muEntry = muEntry),
        )

        val symbol = HashMap<ProcScript, String>()
        for (s in listOf(
            events.stdScript, muEntry.script, rm.rmScript, rm.updateDirectScript, fade.script,
            lineFade.script,
        )) symbol[s] = s.name
        val watched = symbol.values.toSet()

        fun snapshot(): List<String> = engine.procs.filter { it.script != null }
            .mapNotNull { p ->
                val n = symbol[p.script!!] ?: return@mapNotNull null
                "$n@${p.scrCur}/${p.parent?.script?.name ?: "root${p.rootIndex}"}"
            }.sorted()

        fun expectedSnapshot(step: Int): List<String> {
            val dump = ProcArrayDump.ofWatch(trace, step, names)
            return dump.live.filter { it.name in watched }.map { p ->
                val parent = dump.live.firstOrNull { it.address == p.u32(0x14) }
                "${p.name}@${p.scrCur}/${parent?.name ?: "root${p.u32(0x14)}"}"
            }.sorted()
        }

        fun compareFields(step: Int, dump: ProcArrayDump) {
            dump.find("gProcScr_GmapMuEntry2")?.let { t ->
                val e = checkNotNull(engine.find(muEntry.script)).state as GmapMuEntryProc
                assertEquals(
                    listOf(
                        t.u8(0x29) and 1 != 0, t.u8(0x2A), t.u8(0x2B), t.u8(0x2C), t.u8(0x2D),
                        t.u8(0x2E), t.u8(0x2F), t.u16(0x30).toShort().toInt(), t.u32(0x34),
                        t.u32(0x38), t.u32(0x3C), t.u32(0x40),
                    ),
                    listOf(
                        e.flags0, e.unk2a, e.unk2b, e.unk2c, e.unk2d, e.unk2e, e.unk2f, e.unk30,
                        e.unk34, e.unk38, e.unk3c, e.unk40,
                    ),
                    "GmapMuEntry2 fields at $step",
                )
            }
            dump.find("ProcScr_GmapRM")?.let { t ->
                val r = checkNotNull(engine.find(rm.rmScript)).state as GmapRmProc
                assertEquals(
                    listOf(t.u8(0x29), t.u16(0x2E).toShort().toInt(), t.u16(0x30).toShort().toInt()),
                    listOf(r.flag, r.x, r.y),
                    "GmapRM flag/x/y at $step",
                )
            }
            dump.find("ProcScr_GmapLineFade")?.let { t ->
                val l = checkNotNull(engine.find(lineFade.script)).state as GmapLineFadeProc
                assertEquals(
                    listOf(t.u8(0x29), t.u16(0x2A).toShort().toInt(), t.u16(0x2C).toShort().toInt()),
                    listOf(l.unk29, l.unk2a, l.unk2c),
                    "GmapLineFade fields at $step",
                )
            }
            dump.find("ProcScr_BmFadeIN")?.let { t ->
                val f = fade.stateOf(checkNotNull(engine.find(fade.script)))
                assertEquals(
                    listOf(t.u8(0x29) != 0, t.u32(0x2C), t.u32(0x30), t.u32(0x34)),
                    listOf(f.whiteOut, f.timer, f.speed, f.mask),
                    "BmFadeIN fields at $step",
                )
            }
        }

        fun u32(b: ByteArray, at: Int) = b.u32(at)

        fun compareDump(step: Int) {
            val name = (if (step % 300 == 0) "f%05d" else "r%05d").format(step)
            val ew = run.region(name, "ewram")
            val iw = run.region(name, "iwram")
            val theirs = GmData(iw.copyOf(0x8000), RngService())
            assertEquals(
                listOf(theirs.xCamera, theirs.yCamera, theirs.stateRaw and 0x80, theirs.unitId(0),
                    theirs.unitState(0) and 2, theirs.unitLocation(0)),
                listOf(gm.xCamera, gm.yCamera, gm.stateRaw and 0x80, gm.unitId(0),
                    gm.unitState(0) and 2, gm.unitLocation(0)),
                "camera, no_fade and unit 0 id/location at $name",
            )
            val bad = hblank.buffer.indices.filter {
                hblank.buffer[it].toInt() and 0xFFFF != ew.u16(HB_BUFFER + 2 * it)
            }
            assertEquals(0, bad.size, "scanline buffer at $name, first bad ${bad.firstOrNull()}")
            assertEquals(
                listOf(
                    ew.u32(HB_REGISTERS), ew.u32(HB_REGISTERS + 4), ew.u32(HB_REGISTERS + 8),
                    ew[HB_PAGE].toInt() and 0xFF, ew[HB_STATUS].toInt() and 0xFF,
                ),
                listOf(
                    hblank.registers[0], hblank.registers[1], hblank.registers[2], hblank.page,
                    hblank.status,
                ),
                "scanline registers, page and status at $name",
            )
            val expected = BgHardware().lcd
            expected.readFrom(iw, LCD_BUFFER)
            assertEquals(
                listOf(
                    expected.dispcnt, expected.bldcnt, expected.blendCoeffA, expected.blendCoeffB,
                    expected.blendY,
                ),
                listOf(
                    hw.lcd.dispcnt, hw.lcd.bldcnt, hw.lcd.blendCoeffA, hw.lcd.blendCoeffB,
                    hw.lcd.blendY,
                ),
                "display and blend registers at $name",
            )
        }

        fun compare(step: Int) {
            val dump = ProcArrayDump.ofWatch(trace, step, names)
            val t = checkNotNull(dump.find("ProcScr_StdEventEngine")) { "event engine at $step" }
            val ours = checkNotNull(events.findStd()).state as EventEngineProc
            assertEquals(
                listOf(t.u32(0x38), t.u16(0x3C), t.u16(0x3E)),
                listOf(ours.pEventCurrent, ours.evStateBits, ours.evStallTimer),
                "pEventCurrent / evStateBits / evStallTimer at $step",
            )
            assertEquals(expectedSnapshot(step), snapshot(), "proc tree at $step")
            compareFields(step, dump)
        }

        fun frame() {
            for (i in 0..5) engine.run(engine.root(i))
        }

        compare(4907)
        var n = 0
        for (step in 4908..5100) {
            if (step in 4944..4947) continue
            n = if (step <= 4943) step - 4907 else step - 4911
            while (frames < n) {
                frame()
                frames++
            }
            compare(step)
            if (step in DUMPS) compareDump(step)
        }

        // Full dump f05100: the big map's tiles, BG1/BG2 tilemaps, palette banks 5-8 and the BG
        // registers.
        hw.flushBackgrounds()
        val vram = run.region("f05100", "vram")
        val tileBytes = rom.decompressInto(IMG_EVENT_GMAP, ByteArray(0x10000), 0)
        val bandBytes = rom.decompressInto(WorldMapMuEntry.IMG_BAND, ByteArray(0x2000), 0)
        for ((range, what) in listOf(
            (0 until tileBytes) to "BG tile data",
            (0x6800 until 0x7000) to "BG1 tilemap",
            (0x7000 until 0x7800) to "BG2 tilemap",
            (BAND_TILES until BAND_TILES + bandBytes) to "band tiles",
        )) {
            val bad = range.filter { vram[it] != hw.vram[it] }
            assertEquals(
                0, bad.size,
                "$what at f05100: first bad ${bad.firstOrNull()}, last ${bad.lastOrNull()}, " +
                    bad.groupBy { it / 0x400 }.mapValues { it.value.size } +
                    " theirs ${vram.slice(0 until 16)} ours ${hw.vram.slice(0 until 16)}",
            )
        }
        val pal = run.region("f05100", "pal")
        for (i in 5 * 16 until 9 * 16) {
            assertEquals(pal.u16(2 * i), hw.paletteRam[i].toInt() and 0xFFFF, "palette entry $i")
        }
        val io = run.region("f05100", "io")
        val ours = ByteArray(0x100)
        hw.lcd.flushTo(ours)
        for (bg in 0 until 4) {
            assertEquals(io.u16(8 + 2 * bg) and 3, ours.u16(8 + 2 * bg) and 3, "BG$bg priority")
            // BG0's VOFS is the HBlank copy of the scanline buffer, not the register state.
            if (bg == 0) continue
            assertEquals(io.u16(0x10 + 4 * bg) and 0x1FF, ours.u16(0x10 + 4 * bg) and 0x1FF, "BG$bg HOFS")
            assertEquals(io.u16(0x12 + 4 * bg) and 0x1FF, ours.u16(0x12 + 4 * bg) and 0x1FF, "BG$bg VOFS")
        }
    }

    /**
     * `EventC3_SpawnLord` on an emptied unit slot 0 (the real run finds Eirika there already and
     * returns early): it must call `SetGmCharUnit(0, CHARACTER_EIRIKA, -1, node)` with the
     * script's arguments, the node being the leader's location in the `r04920` dump.
     */
    @Test
    @VerifiesPort("EventC3_SpawnLord")
    fun `spawn lord on an empty slot passes the script arguments to SetGmCharUnit`() {
        assumeTrue(available(), "reference data / ROM / nm file not present")
        val run = ref.run("ram_trace_prologue")
        val rom = RomView.ofRom(romFile.readBytes())
        val mem = ScriptMemory { a -> rom.u16(a) }
        val expectedNode =
            GmData(run.region("r04920", "iwram").copyOf(0x8000), RngService()).unitLocation(0)
        // The first 0xC3 command of the script, found by walking command lengths.
        val engine = ProcEngine()
        engine.init()
        val gm = GmData(ByteArray(0x8000), RngService())
        val calls = mutableListOf<List<Int>>()
        val spawn = WorldMapSpawnEvents(mem, gm) { a, b, c, d -> calls += listOf(a, b, c, d) }
        val script = ProcArrayDump.ofWatch(run.watch("procs"), 4907, ProcArrayDump.romNames())
            .find("ProcScr_StdEventEngine")!!.u32(0x30)
        var at = script
        while (mem.u16(at) ushr 8 != 0xC3) at += 2 * ((mem.u16(at) ushr 4) and 0xF)
        val proc = EventEngineProc()
        proc.pEventCurrent = at
        spawn.eventC3SpawnLord.run(proc)
        assertEquals(listOf(listOf(0, 1, -1, expectedNode)), calls)
        // The leader already in the slot: nothing is called.
        gm.setUnitId(0, 1)
        calls.clear()
        spawn.eventC3SpawnLord.run(proc)
        assertEquals(emptyList<List<Int>>(), calls)
    }

    private var frames = 0

    private companion object {
        const val LCD_BUFFER = 0x3080

        /** VRAM offset of the entry bands' tiles. */
        const val BAND_TILES = 0x4C00

        /** ROM address of `Img_EventGmap`. */
        const val IMG_EVENT_GMAP = 0x08A99140
        const val PALETTE_BUFFER = 0x228A8
        const val BG0_TILEMAP = 0x22CA8
        const val HB_BUFFER = 0x1BE32
        const val HB_REGISTERS = 0x1C5B4
        const val HB_PAGE = 0x1C5CF
        const val HB_STATUS = 0x1BE30

        /** Steps with a RAM dump inside the window. */
        val DUMPS = setOf(4920, 4950, 4980, 5010, 5040, 5070, 5100)
    }
}

private fun ByteArray.u16(at: Int): Int =
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.u32(at: Int): Int = u16(at) or (u16(at + 2) shl 16)
