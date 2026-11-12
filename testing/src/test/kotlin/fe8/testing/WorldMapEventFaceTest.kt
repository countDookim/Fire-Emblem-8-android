package fe8.testing

import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.bm.PlaySt
import fe8.game.event.EventConst.EVC_ADVANCE_CONTINUE
import fe8.game.event.EventConst.EVC_ADVANCE_YIELD
import fe8.game.event.EventConst.EVC_STOP_YIELD
import fe8.game.event.EventConst.EV_EXEC_WORLDMAP
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineProc
import fe8.game.event.EventHandler
import fe8.game.event.EventInput
import fe8.game.event.EventScrCore
import fe8.game.event.EventState
import fe8.game.event.ScriptMemory
import fe8.game.flags.Fe8EventFlags
import fe8.game.scene.FaceState
import fe8.game.scene.TalkScene
import fe8.game.worldmap.GmData
import fe8.game.worldmap.WmFaceCtrlState
import fe8.game.worldmap.WmFaceHolderState
import fe8.game.worldmap.WorldMapFace
import fe8.game.worldmap.WorldMapFaceEvents
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.LcgRng
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The world map portrait commands C4 (show) and C5 (clear) run by the event VM, steps 5701-7781 of
 * `ram_trace_prologue` (the faces of nations 1-8; the first C4 is at step 5765, the last C5 at
 * 7749). The `ram_trace_prologue_wm_lcd` capture supplies the full dump f05700 as the seed and the
 * per-step `pal` / `displayio` / `oam` watches (index = step - 5500).
 *
 * The seed is the state at step 5700: the script is stalling at 0x08A39824 (5 frames left), the
 * face controller is idle with no face. Both the faces and the event VM run on the frames the
 * original ran (a frame is run on the steps where the traced controller timer advanced). The
 * script only registers what runs here; the opcodes of other lanes are explicit stand-ins that
 * yield exactly while the capture's `pEventCurrent` still points at them:
 *
 * - B6 (camera move), B8 / B9 / BA (nation highlight start / end / wait) and 1C / 1D (text
 *   continue / end): [standIn]. Their effects are not in the compared state.
 * - 0E STAL is the real handler ([EventScrCore]).
 *
 * Compared per step: engine `pEventCurrent` / `evStateBits` / `evStallTimer`, the controller, both
 * holder wrappers and the `E_FACE` proc; BG2 map on every EWRAM dump; BG palette bank 4, BG2 scroll
 * and the face OBJ against the per-step watches (hardware shows the previous step's end); portrait
 * tiles and BLDCNT / BLDALPHA at the full dumps f06000..f07500 (per-step blend registers depend on
 * where in the frame VBlank fell and on the nation highlight of another lane).
 */
class WorldMapEventFaceTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val romFile = File(assets, "baserom.gba")
    private val runDir = File(assets, "reference/out/ram_trace_prologue")
    private val names by lazy { ProcArrayDump.romNames() }

    private fun dump(step: Int, region: String): ByteArray? {
        for (prefix in listOf("r", "f")) {
            val file = File(runDir, "%s%05d.%s.bin".format(prefix, step, region))
            if (file.isFile) return file.readBytes()
        }
        return null
    }

    @Test
    @VerifiesPort("EventC4_WmShowPortrait", "EventC5_WmClearPortrait")
    fun `show and clear portrait commands run by the VM match the capture`() {
        assumeTrue(
            ref.available && romFile.isFile && names.isNotEmpty(),
            "reference data / ROM / nm file not present",
        )
        val run = ref.run("ram_trace_prologue")
        val lcd = ref.run("ram_trace_prologue_wm_lcd")
        val trace = run.watch("procs")
        val pal = lcd.watch("pal")
        val io = lcd.watch("displayio")
        val oam = lcd.watch("oam")
        val bytes = romFile.readBytes()
        val rom = RomView.ofRom(bytes)
        val mem = ScriptMemory { a -> rom.u16(a) }

        val engine = ProcEngine()
        engine.init()
        val hw = BgHardware()
        val sprites = SpriteLayers()
        val scene = TalkScene(
            rom, bytes, hw, engine, sprites, KeyStatus(), { 0 },
            MsgStrings(bytes, MsgHuffman(bytes, 0, 0), 0), {}, { 0 }, {}, LcgRng(),
        )
        val gm = GmData(lcd.region("f05700", "iwram").copyOf(0x8000), RngService())
        val faces = WorldMapFace(engine, hw, gm, rom, scene.face, scene, scene)
        faces.setupFaceConfig()

        val playSt = PlaySt()
        val flags = Fe8EventFlags.create().also { it.copyIn(run.watch("flags").step(5700)) }
        val eventState = EventState()
        val events = EventEngine(engine, eventState, mem)
        val core = EventScrCore(
            eventState, mem, object : EventInput {}, flags, RngService(), playSt, KeyStatus(),
        )
        var step = 5700
        /**
         * Stand-in: yields while the capture's script position still is this command; when done it
         * continues, or ends the frame ([waits]: the wait commands, which yield when satisfied).
         */
        fun standIn(waits: Boolean) = EventHandler { proc ->
            val at = ProcArrayDump.ofWatch(trace, step, names).find("ProcScr_StdEventEngine")!!
            when {
                at.u32(0x38) == proc.pEventCurrent -> EVC_STOP_YIELD
                waits -> EVC_ADVANCE_YIELD
                else -> EVC_ADVANCE_CONTINUE
            }
        }
        events.register(0x0E, core.event0eStal)
        for (code in listOf(0x1C, 0xB6, 0xB8, 0xB9)) events.register(code, standIn(false))
        for (code in listOf(0x1D, 0xBA)) events.register(code, standIn(true))
        val faceEvents = WorldMapFaceEvents(mem, faces)
        faceEvents.registerOn(events)

        // Controller and the script's engine proc, in the original's order; one warm-up frame
        // (the engine proc's first frame only yields), then the seeds overwrite its effects.
        val main = engine.start(ProcScript("ProcScr_WorldMapMain", listOf(block())), 3)
        val ctrl = faces.startWmFaceCtrl(main)
        val vm = events.create(SCRIPT_AT_5700, EV_EXEC_WORLDMAP)
        fun frame() {
            sprites.clear()
            for (i in 0 until 8) engine.run(engine.root(i))
        }
        frame()

        val ctrlState = ctrl.state as WmFaceCtrlState
        val holder = ctrlState.faceHolderProc!!.state as WmFaceHolderState
        fun ProcArrayDump.ctrl() = find("ProcScr_WorldMapFaceCtrl")
        fun ProcArrayDump.holder() = find("ProcScr_WorldMapFaceHolder")
        fun ProcArrayDump.eface() = find("gProcScr_E_FACE_ExtraFrame")

        // Seed: hardware, BG2 map, controller, wrappers and the engine proc at step 5700.
        val seed = ProcArrayDump.ofWatch(trace, 5700, names)
        assertTrue(seed.eface() == null, "no face shown at the seed")
        val vramSeed = lcd.region("f05700", "vram")
        vramSeed.copyInto(hw.vram)
        val palSeed = lcd.region("f05700", "pal")
        for (i in 0 until 512) {
            hw.palette[i] = palSeed.u16(2 * i).toShort()
            hw.paletteRam[i] = palSeed.u16(2 * i).toShort()
        }
        val bgcnt = lcd.region("f05700", "io")
        for (bg in 0..3) hw.setMapDataOffset(bg, ((bgcnt.u16(8 + 2 * bg) shr 8) and 0x1F) * 0x800)
        hw.initBmBgLayers(false)
        hw.lcd.readFrom(lcd.region("f05700", "iwram"), LCD_BUFFER)
        val ewramSeed = lcd.region("f05700", "ewram")
        for (i in hw.tilemaps[2].indices) hw.tilemaps[2][i] = ewramSeed.u16(BG2_MAP + 2 * i).toShort()
        ctrlState.timer = seed.ctrl()!!.u16(0x2A)
        ctrlState.increment = seed.ctrl()!!.u8(0x30).toByte().toInt()
        ctrlState.blendAmt = seed.ctrl()!!.u8(0x31)
        for (i in 0..1) {
            val p = seed.holder()!!
            val at = 0x2C + 12 * i
            holder.faceWrapper[i].x = p.u16(at).toShort().toInt()
            holder.faceWrapper[i].y = p.u16(at + 2)
            holder.faceWrapper[i].faceId = p.u16(at + 8)
            holder.faceWrapper[i].unk0a = p.u8(at + 10).toByte().toInt()
            assertEquals(0, p.u32(at + 4), "wrapper $i is empty at the seed")
        }
        val engineSeed = seed.find("ProcScr_StdEventEngine")!!
        vm.pEventCurrent = engineSeed.u32(0x38)
        vm.evStateBits = engineSeed.u16(0x3C)
        vm.evStallTimer = engineSeed.u16(0x3E)
        assertEquals(SCRIPT_AT_5700, vm.pEventCurrent, "script position at the seed")

        var previousTimer = ctrlState.timer
        val previousPal = IntArray(16) { hw.palette[64 + it].toInt() and 0xFFFF }
        var previousRegs = listOf(
            hw.lcd.bldcnt and 0x3FFF, hw.lcd.blendCoeffA or (hw.lcd.blendCoeffB shl 8),
            hw.lcd.bgHofs[2] and 0x1FF, hw.lcd.bgVofs[2] and 0x1FF,
        )
        var objChecked = 0
        var mapsChecked = 0
        var palChecked = 0
        var previousObjects: IntArray? = null
        for (s in 5701..7781) {
            step = s
            val now = ProcArrayDump.ofWatch(trace, s, names)
            val timer = now.ctrl()!!.u16(0x2A)
            if (timer != previousTimer) frame()
            previousTimer = timer

            val t = now.find("ProcScr_StdEventEngine")!!
            assertEquals(
                listOf(t.u32(0x38), t.u16(0x3C), t.u16(0x3E)),
                listOf(vm.pEventCurrent, vm.evStateBits, vm.evStallTimer),
                "pEventCurrent / evStateBits / evStallTimer @$s",
            )
            val c = now.ctrl()!!
            assertEquals(
                listOf(c.u16(0x2A), c.u8(0x30), c.u8(0x31)),
                listOf(ctrlState.timer, ctrlState.increment and 0xFF, ctrlState.blendAmt),
                "controller timer / increment / blendAmt @$s",
            )
            for (i in 0..1) {
                val p = now.holder()!!
                val at = 0x2C + 12 * i
                val w = holder.faceWrapper[i]
                assertEquals(
                    listOf(
                        p.u16(at), p.u16(at + 2), if (p.u32(at + 4) == 0) 0 else 1, p.u16(at + 8),
                        p.u8(at + 10).toByte().toInt(),
                    ),
                    listOf(
                        w.x and 0xFFFF, w.y, if (w.faceProc == null) 0 else 1, w.faceId,
                        w.unk0a,
                    ),
                    "wrapper $i @$s",
                )
            }
            val face = now.eface()
            val simFace = scene.face.gFaces[0]
            assertEquals(face != null, simFace != null, "face exists @$s")
            if (face != null && simFace != null) {
                val st = simFace.state as FaceState
                assertEquals(face.u32(0x30), st.displayBits, "displayBits @$s")
                assertEquals(face.u16(0x34).toShort().toInt(), st.xPos, "xPos @$s")
                assertEquals(face.u16(0x36).toShort().toInt(), st.yPos, "yPos @$s")
                assertEquals(face.u16(0x3C), st.oam2, "oam2 @$s")
                assertEquals(face.u16(0x3E), st.faceId, "faceId @$s")
                assertEquals(face.u8(0x40), st.faceSlot, "slot @$s")
                assertEquals(face.u8(0x41), st.spriteLayer, "layer @$s")
                assertEquals(face.u32(0x38), st.sprite?.id, "sprite @$s")
            }

            // The traced BG2 map (EWRAM dumps every 30 steps).
            dump(s, "ewram")?.let { ew ->
                val map = hw.mapBuffer(2)
                val expected = ShortArray(map.size) { ew.u16(BG2_MAP + 2 * it).toShort() }
                assertEquals(expected.toList(), map.toList(), "BG2 tile map @$s")
                mapsChecked++
            }
            // Per-step palette bank 4 and display registers of the lcd watches: the hardware
            // shows the state at the end of the previous step.
            val palNow = pal.step(s)
            val ioNow = io.step(s)
            val bad = (64 until 80).filter { palNow.u16(2 * it) != previousPal[it - 64] }
            assertEquals(emptyList<Int>(), bad, "BG palette bank 4 @$s")
            palChecked++
            val regs = listOf(ioNow.u16(0x50) and 0x3FFF, ioNow.u16(0x52), ioNow.u16(0x18) and 0x1FF,
                ioNow.u16(0x1A) and 0x1FF)
            assertEquals(regs.drop(2), previousRegs.drop(2), "BG2 scroll @$s")
            for (i in 0 until 16) previousPal[i] = hw.palette[64 + i].toInt() and 0xFFFF
            previousRegs = listOf(
                hw.lcd.bldcnt and 0x3FFF, hw.lcd.blendCoeffA or (hw.lcd.blendCoeffB shl 8),
                hw.lcd.bgHofs[2] and 0x1FF, hw.lcd.bgVofs[2] and 0x1FF,
            )
            // OAM: like the palette, the hardware shows the previous step's end.
            val shownObjects = previousObjects
            if (shownObjects != null) {
                val count = shownObjects.size / 3
                val reference = oam.step(s)
                assertTrue(
                    (0..128 - count).any { start ->
                        (0 until count).all { i ->
                            (0..2).all { attr ->
                                reference.u16((start + i) * 8 + attr * 2) ==
                                    shownObjects[i * 3 + attr]
                            }
                        }
                    },
                    "face OBJ differs @$s",
                )
                objChecked++
            }
            previousObjects = if (
                simFace != null && (simFace.state as FaceState).displayBits and 0x4000 == 0
            ) {
                val buffer = OamBuffer()
                sprites.pushLayerObjects(0) {
                    buffer.putHi(it.oam1, it.oam0, it.obj!!.data, it.oam2)
                }
                val count = buffer.hiPut / 4
                assertTrue(count > 0, "face OBJ drawn @$s")
                IntArray(count * 3) { buffer.buffer[(it / 3) * 4 + it % 3].toInt() and 0xFFFF }
            } else {
                null
            }
            // Full dumps: portrait tiles and palette bank 4.
            if (s in FULL_DUMPS) {
                val vram = dump(s, "vram")!!
                val io = dump(s, "io")!!
                assertEquals(
                    listOf(io.u16(0x50) and 0x3FFF, io.u16(0x52)),
                    listOf(hw.lcd.bldcnt and 0x3FFF, hw.lcd.blendCoeffA or (hw.lcd.blendCoeffB shl 8)),
                    "BLDCNT / BLDALPHA @$s",
                )
                assertEquals(
                    vram.copyOfRange(0x5000, 0x6000).toList(),
                    hw.vram.copyOfRange(0x5000, 0x6000).toList(),
                    "BG portrait tiles @$s",
                )
            }
        }
        assertTrue(objChecked > 1000, "face OBJ steps: $objChecked")
        assertTrue(mapsChecked > 50, "BG2 maps: $mapsChecked")
        assertTrue(palChecked > 2000, "palette steps: $palChecked")
    }

    private fun ByteArray.u16(at: Int): Int =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)

    private companion object {
        /** `pEventCurrent` of the stalling engine at step 5700. */
        const val SCRIPT_AT_5700 = 0x08A39824

        /** `gLCDControlBuffer` as an IWRAM offset. */
        const val LCD_BUFFER = 0x3080

        /** `gBG2TilemapBuffer` (0x02023CA8) as an EWRAM offset. */
        const val BG2_MAP = 0x23CA8

        val FULL_DUMPS = setOf(6000, 6300, 6600, 6900, 7200, 7500)
    }
}
