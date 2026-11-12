package fe8.testing

import fe8.game.RomView
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventConst.EV_EXEC_WORLDMAP
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.EventInput
import fe8.game.event.EventScrCore
import fe8.game.event.EventState
import fe8.game.event.ScriptMemory
import fe8.game.flags.Fe8EventFlags
import fe8.game.bm.PlaySt
import fe8.game.worldmap.GmData
import fe8.game.worldmap.GmapRmBaPalAnimProc
import fe8.game.worldmap.GmapRmBorderProc
import fe8.game.worldmap.GmapRmProc
import fe8.game.worldmap.GmapRmUpdateProc
import fe8.game.worldmap.WmBigMapHooks
import fe8.game.worldmap.WorldMapBigMapEvents
import fe8.game.worldmap.WorldMapRm
import fe8.game.worldmap.WorldMapRmBaPalAnim
import fe8.game.worldmap.WorldMapRmBorder
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.render.ApHost
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteAnimPlayer
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The Prologue world map's camera scrolls and nation highlights, steps 5433-8110 of
 * `ram_trace_prologue`, driven by the event script through the event VM: `EventB6_WmMoveBigMap`
 * starts `ProcScr_GmapRmUpdateExt`, `EventB8_WmShowBigMapHighlight` starts
 * `ProcScr_GmapRmBorder1` (and the blend pulse, from the border's own script),
 * `EventB9_WmRemoveHighlightNationPart1` sets the border's flag and
 * `EventBA_WmRemoveHighlightNationPart2` waits for it to end. None of these procs is started by
 * the test.
 *
 * Seed: the big map proc (`ProcScr_GmapRM`) is brought up as in `WorldMapRmTest` (B4 ran at
 * 4942, before this window), `GmData` is the `r05430` dump, and the event engine proc is
 * created at the script position the capture shows at step 5433 (the `Event1D_WaitForLockTalk`
 * before the first `EventB6`). From step 5500 (first step of the `ram_trace_prologue_wm_lcd`
 * watches) the LCD buffer and palette are seeded from the capture.
 *
 * Test-only stand-ins (they only advance the script as the capture shows; the text and portrait
 * commands are not part of this task): opcodes 0x1C and 0x1D (talk continue / wait for the talk
 * lock: they stay on the command while the traced `pEventCurrent` does, else advance) and
 * 0xC4 / 0xC5 (portrait show / clear: advance). 4 stand-in handlers.
 *
 * Game loop lag: after a nation highlight starts, the original spends several steps in its
 * graphics load; those steps run no frame (taken from the capture: the steps between the border
 * proc's first step and its first `timer` change).
 */
class WorldMapEventCameraTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    /** What the port shows after one iteration: blend/window/BG1 registers, palette, sprites. */
    private class Shown(val lcd: List<Int>, val palette: List<Int>, val sprites: List<Int>)

    @Test
    @VerifiesPort(
        "EventB6_WmMoveBigMap", "EventB8_WmShowBigMapHighlight",
        "EventB9_WmRemoveHighlightNationPart1", "EventBA_WmRemoveHighlightNationPart2",
        "sub_80C2C10", "sub_80C2BC4", "GmapRm_GetPosition",
    )
    fun `script driven scrolls and highlights follow the capture every step`() {
        val run = ref.run("ram_trace_prologue_wm_lcd")
        assumeTrue(
            ref.available && romFile.isFile && ProcArrayDump.romNames().isNotEmpty() &&
                run.hasWatch("oam"),
        )
        val names = ProcArrayDump.romNames()
        val main = ref.run("ram_trace_prologue")
        val trace = main.watch("procs")
        val io = run.watch("displayio")
        val pal = run.watch("pal")
        val oam = run.watch("oam")
        fun dump(step: Int) = ProcArrayDump.ofWatch(trace, step, names)
        val rom = RomView.ofRom(romFile.readBytes())
        val borderAddress = names.entries.first { it.value == "ProcScr_GmapRmBorder1" }.key

        // Lag steps (no game loop pass), read from the capture. (1) After a nation highlight
        // starts, the graphics load: the steps between the border proc's first step and its first
        // timer change. (2) Any step where a field that must change every pass (event stall
        // timer, scroll timer, border timer in its merge phases, blend pulse timer in its fade
        // phases) stays as it was; those coincide with an A press of the capture's input.
        val lagSteps = HashSet<Int>()
        var armed = false
        var seen = false
        var previous = dump(FIRST - 1)
        val extAddress = names.entries.first { it.value == "ProcScr_GmapRmUpdateExt" }.key
        val palAddress = names.entries.first { it.value == "ProcScr_GmapRmBaPalAnim1" }.key
        val stdAddress = names.entries.first { it.value == "ProcScr_StdEventEngine" }.key
        var startLags = 0
        for (step in FIRST..LAST) {
            val now = dump(step)
            val border = now.findAll(borderAddress).firstOrNull()
            if (border == null) {
                seen = false
            } else if (!seen) {
                seen = true
                armed = true
            } else if (armed && border.u16(0x30) == 0) {
                lagSteps.add(step)
                startLags++
            } else {
                armed = false
            }
            val stall = now.find(stdAddress)!!
            val before = previous.find(stdAddress)!!
            val ext = now.find(extAddress)
            val extBefore = previous.find(extAddress)
            val bd = now.find(borderAddress)
            val bdBefore = previous.find(borderAddress)
            val pulse = now.find(palAddress)
            val pulseBefore = previous.find(palAddress)
            val stuck = before.u16(0x3E) > 0 && stall.u16(0x3E) == before.u16(0x3E) ||
                ext != null && extBefore != null && extBefore.scrCur >= 7 &&
                ext.u16(0x34) == extBefore.u16(0x34) ||
                bd != null && bdBefore != null && bdBefore.scrCur in listOf(5, 12) &&
                bdBefore.u16(0x30) in 1..21 && bd.u16(0x30) == bdBefore.u16(0x30) ||
                pulse != null && pulseBefore != null && pulseBefore.scrCur in listOf(7, 9) &&
                pulseBefore.u16(0x2A) > 0 && pulse.u16(0x2A) == pulseBefore.u16(0x2A)
            if (stuck && step !in lagSteps) lagSteps.add(step)
            previous = now
        }

        val engine = ProcEngine()
        engine.init()
        val hw = BgHardware()
        hw.setMapDataOffset(1, 0x6800)
        hw.setMapDataOffset(2, 0x7000)
        hw.initBmBgLayers(false)
        val gm = GmData(main.region("r04920", "iwram").copyOf(0x8000), RngService())
        val rm = WorldMapRm(engine, hw, rom, gm)
        val layers = SpriteLayers()
        val cache = HashMap<Int, SpriteObject>()
        val host = object : ApHost {
            override fun objectAt(addr: Int) = cache.getOrPut(addr) { rom.objN(addr) }
            override fun sin(a: Int) = 0
            override fun cos(a: Int) = 0x1000
            override fun setObjAffine(index: Int, pa: Int, pb: Int, pc: Int, pd: Int) {}
            override fun register2dChrMove(src: Int, dstOffset: Int, width: Int, height: Int) {}
            override fun obj1dMap() = false
        }
        val player = SpriteAnimPlayer({ rom.u16(it) }, layers, host)
        player.clearAll()
        val baPal = WorldMapRmBaPalAnim(engine, hw.lcd) { 1 }
        val border = WorldMapRmBorder(engine, hw, rom, player, baPal) { rm.gmapRmGetPosition() }

        // The map proc as in WorldMapRmTest: started at 4942 (B4), lag frames 4944-4947.
        val first = checkNotNull(dump(4942).find("ProcScr_GmapRM"))
        rm.startGmapRm(0, 0, first.u8(0x29), null)
        engine.run(engine.root(3))
        engine.run(engine.root(3))
        for (step in 4949..BEFORE) engine.run(engine.root(3))

        // The event VM with exactly the opcodes of the window.
        var now = BEFORE
        val mem = ScriptMemory { a -> rom.u16(a) }
        val playSt = PlaySt()
        val eventState = EventState()
        val events = EventEngine(
            engine, eventState, mem,
            object : EventEngineHooks {
                override fun blendY() = hw.lcd.blendY
                override fun chapterIndex() = playSt.chapterIndex
            },
        )
        val core = EventScrCore(
            eventState, mem, object : EventInput {}, Fe8EventFlags.create(), RngService(), playSt,
            KeyStatus(),
        )
        val bigMap = WorldMapBigMapEvents(mem, WmBigMapHooks.of(rm, border))
        fun traceCurrent() = checkNotNull(dump(now).find("ProcScr_StdEventEngine")).u32(0x38)
        fun follower() = EventHandler { proc ->
            val here = proc.pEventCurrent
            val next = here + 2 * ((mem.u16(here) ushr 4) and 0xF)
            when (traceCurrent()) {
                here -> EVC_STOP_YIELD
                next -> EVC_ADVANCE_YIELD
                else -> EVC_ADVANCE_CONTINUE
            }
        }
        events.register(0x0E, core.event0eStal)
        events.register(0x1C, follower())
        events.register(0x1D, follower())
        events.register(0xB6, bigMap.eventB6WmMoveBigMap)
        events.register(0xB8, bigMap.eventB8WmShowBigMapHighlight)
        events.register(0xB9, bigMap.eventB9WmRemoveHighlightNationPart1)
        events.register(0xBA, bigMap.eventBAWmRemoveHighlightNationPart2)
        events.register(0xC4, EventHandler { EVC_ADVANCE_CONTINUE })
        events.register(0xC5, EventHandler { EVC_ADVANCE_CONTINUE })
        val script = checkNotNull(dump(BEFORE).find("ProcScr_StdEventEngine")).u32(0x38)
        val std = events.create(script, EV_EXEC_WORLDMAP)

        val symbol = mapOf(
            events.stdScript to events.stdScript.name, rm.rmScript to rm.rmScript.name,
            rm.updateDirectScript to rm.updateDirectScript.name,
            rm.updateExtScript to rm.updateExtScript.name, border.script to border.script.name,
            baPal.script to baPal.script.name,
        )
        val watched = symbol.values.toSet()
        fun snapshot(): List<String> = engine.procs.filter { it.script != null }
            .mapNotNull { p -> symbol[p.script!!]?.let { "$it@${p.scrCur}" } }.sorted()
        fun expectedSnapshot(step: Int): List<String> =
            dump(step).live.filter { it.name in watched }.map { "${it.name}@${it.scrCur}" }.sorted()

        fun fields(early: Boolean): List<Int> {
            val out = ArrayList<Int>()
            out.addAll(listOf(std.pEventCurrent, std.evStateBits, std.evStallTimer))
            val rmProc = engine.find(rm.rmScript)
            if (rmProc != null) {
                val s = rmProc.state as GmapRmProc
                out.addAll(listOf(s.flag, s.x, s.y))
            }
            val ext = engine.find(rm.updateExtScript)
            if (ext != null) {
                val s = ext.state as GmapRmUpdateProc
                val timer = if (early) -1 else s.timer
                out.addAll(listOf(s.x0, s.y0, s.x1, s.y1, s.delay, timer, s.speed))
            }
            var b = engine.findAfter(border.script, null)
            while (b != null) {
                val s = b.state as GmapRmBorderProc
                out.addAll(listOf(s.flag, s.index, s.slot, s.timer))
                b = engine.findAfter(border.script, b)
            }
            val pulse = engine.find(baPal.script)
            if (pulse != null) {
                val s = pulse.state as GmapRmBaPalAnimProc
                out.addAll(listOf(s.flag, s.timer))
            }
            return out
        }
        fun expectedFields(step: Int): List<Int> {
            val d = dump(step)
            val out = ArrayList<Int>()
            val t = checkNotNull(d.find("ProcScr_StdEventEngine"))
            out.addAll(listOf(t.u32(0x38), t.u16(0x3C), t.u16(0x3E)))
            d.find("ProcScr_GmapRM")?.let {
                out.addAll(
                    listOf(
                        it.u8(0x29), it.u16(0x2E).toShort().toInt(),
                        it.u16(0x30).toShort().toInt(),
                    ),
                )
            }
            d.find("ProcScr_GmapRmUpdateExt")?.let { e ->
                val f = IntArray(7) { e.u16(0x2A + 2 * it).toShort().toInt() }
                // The timer is only set by `GmapRmUpdateExt_InitPosition`: the slot's old value
                // stays until then (script position < 6), so it is not compared before.
                out.addAll(f.toList().mapIndexed { i, v -> if (i == 5 && e.scrCur < 6) -1 else v })
            }
            for (e in d.findAll(borderAddress)) {
                out.addAll(listOf(e.u8(0x29), e.u8(0x2A), e.u8(0x2B), e.u16(0x30)))
            }
            d.find("ProcScr_GmapRmBaPalAnim1")?.let {
                out.addAll(listOf(it.u8(0x29), it.u16(0x2A).toShort().toInt()))
            }
            return out
        }
        fun capture(): Shown {
            val regs = ByteArray(0x60)
            hw.lcd.flushTo(regs)
            fun u16(at: Int) =
                (regs[at].toInt() and 0xFF) or ((regs[at + 1].toInt() and 0xFF) shl 8)
            val lcd = LCD_BYTES.map { regs[it].toInt() and 0xFF } +
                listOf(u16(0x14) and 0x1FF, u16(0x16) and 0x1FF)
            val buffer = OamBuffer()
            layers.pushLayerObjects(13) { buffer.putHi(it.oam1, it.oam0, it.obj!!.data, it.oam2) }
            val sprites = ArrayList<Int>()
            for (i in 0 until buffer.hiPut / 4) {
                for (a in 0..2) sprites.add(buffer.buffer[i * 4 + a].toInt() and 0xFFFF)
            }
            return Shown(lcd, hw.palette.map { it.toInt() and 0xFFFF }, sprites)
        }

        var latest = capture()
        var shown = latest
        var compared = 0
        var spriteSteps = 0
        var vramChecked = 0
        var blendSteps = 0
        var extSteps = 0
        var borderSteps = 0
        for (step in FIRST..LAST) {
            now = step
            if (step == LCD_FIRST) {
                hw.lcd.readRegisters(io.step(step))
                val seed = pal.step(step)
                for (i in 0 until 512) hw.palette[i] = u16(seed, 2 * i).toShort()
                latest = capture()
                shown = latest
            }
            if (step !in lagSteps) {
                shown = latest
                layers.clear()
                for (i in 0..5) engine.run(engine.root(i))
                latest = capture()
            }
            assertEquals(expectedSnapshot(step), snapshot(), "proc tree at $step")
            val early = (dump(step).find("ProcScr_GmapRmUpdateExt")?.scrCur ?: 6) < 6
            assertEquals(expectedFields(step), fields(early), "proc fields at $step")
            if (dump(step).find("ProcScr_GmapRmUpdateExt") != null) extSteps++
            if (dump(step).findAll(borderAddress).isNotEmpty()) borderSteps++
            if (step >= LCD_FIRST) {
                val lcd = LCD_BYTES.map { io.u8(step, it) } +
                    listOf(io.u16(step, 0x14) and 0x1FF, io.u16(step, 0x16) and 0x1FF)
                // The face procs (portrait fades, stand-in commands here) write BLDCNT/BLDALPHA
                // outside the border's runs: compare them only while the border owns them (its
                // frames, and the step after its end, where MergeOut's last write shows).
                val owns = step !in lagSteps &&
                    (dump(step).findAll(borderAddress).isNotEmpty() &&
                        dump(step - 1).findAll(borderAddress).isNotEmpty() ||
                        dump(step - 1).findAll(borderAddress).isNotEmpty())
                val keep = lcd.indices.filter { owns || it !in 12..16 }
                if (owns) blendSteps++
                assertEquals(
                    keep.map { lcd[it] }, keep.map { shown.lcd[it] },
                    "BLD/WIN/BG1 registers at $step",
                )
                // Palette 0x19 (OBJ bank 9, the border's); the portraits load other banks.
                assertEquals(
                    List(16) { pal.u16(step, 2 * (0x190 + it)) },
                    shown.palette.subList(0x190, 0x1A0),
                    "palette 0x19 at $step",
                )
                val reference = oam.step(step)
                val sprites = shown.sprites
                if (sprites.isNotEmpty()) {
                    val count = sprites.size / 3
                    assertTrue(
                        (0..128 - count).any { start ->
                            (0 until count * 3).all { k ->
                                u16(reference, start * 8 + (k / 3) * 8 + (k % 3) * 2) == sprites[k]
                            }
                        },
                        "border sprites in OAM at step $step",
                    )
                    spriteSteps++
                } else if (dump(step).findAll(borderAddress).isEmpty()) {
                    for (i in 0 until 128) {
                        assertTrue(
                            u16(reference, i * 8 + 4) and 0xFC00 != 0x9C00 ||
                                u16(reference, i * 8) == 0xA0,
                            "border sprite $i still in OAM at step $step",
                        )
                    }
                }
                if (step in FULL_DUMPS && dump(step).findAll(borderAddress).isNotEmpty()) {
                    val vram = run.region("f%05d".format(step), "vram")
                    assertEquals(
                        vram.copyOfRange(0x10000, 0x11000).toList(),
                        hw.vram.copyOfRange(0x10000, 0x11000).toList(),
                        "OBJ tiles of the highlighted nation at step $step",
                    )
                    vramChecked++
                }
            }
            compared++
        }
        assertEquals(13, startLags, "lag steps after a highlight start")
        assertEquals(19, lagSteps.size, "lag steps taken from the capture")
        assertTrue(compared > 2600, "steps compared: $compared")
        assertTrue(extSteps > 400, "steps with a scroll: $extSteps")
        assertTrue(borderSteps > 1000, "steps with a highlight: $borderSteps")
        assertTrue(blendSteps > 1000, "steps with the blend registers compared: $blendSteps")
        assertTrue(spriteSteps > 500, "steps with border sprites: $spriteSteps")
        assertTrue(vramChecked >= 2, "full dumps inside border runs: $vramChecked")
    }

    private fun u16(bytes: ByteArray, at: Int) =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private companion object {
        /** The step the event engine proc is created for (1D wait before the first B6). */
        const val BEFORE = 5432
        const val FIRST = 5433
        const val LAST = 8110
        const val LCD_FIRST = 5500
        val FULL_DUMPS = setOf(5528, 5600, 5670, 8100)

        /** WIN0H..WINOUT (0x40..0x4B) and BLDCNT, BLDALPHA, BLDY (0x50..0x54). */
        val LCD_BYTES = (0x40..0x4B).toList() + (0x50..0x54).toList()
    }
}
