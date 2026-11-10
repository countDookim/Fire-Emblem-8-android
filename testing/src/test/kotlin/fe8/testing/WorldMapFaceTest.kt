package fe8.testing

import fe8.game.RomView
import fe8.game.scene.FaceState
import fe8.game.scene.TalkScene
import fe8.game.worldmap.GmData
import fe8.game.worldmap.WmFaceCtrlState
import fe8.game.worldmap.WmFaceHolderState
import fe8.game.worldmap.WorldMapFace
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.LcgRng
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `src/worldmap_face.c` against `ram_trace_prologue`. The prologue shows nine portraits between
 * steps 5496 and 8846. The controller is run frame by frame from step 4907; a frame is run exactly
 * on the steps where the traced controller timer advanced (the game loop skips some steps). The
 * show and clear commands (not their event VM) are replayed from the first step a wrapper is
 * seen filled or leaving in the trace.
 *
 * Compared: the controller, holder and `E_FACE` proc fields on every step (`procs` watch), the
 * BG2 tile map (EWRAM dumps every 30 steps), the portrait tiles, palette bank 4, blend and BG
 * scroll registers and face OBJ entries of the full dumps f06000..f07500.
 */
class WorldMapFaceTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val romFile = File(assets, "baserom.gba")
    private val runDir = File(assets, "reference/out/ram_trace_prologue")

    /** The Kotlin world map face rig. */
    private inner class Rig(iwram: ByteArray) {
        val bytes = romFile.readBytes()
        val engine = ProcEngine()
        val hw = BgHardware()
        val sprites = SpriteLayers()
        val scene = TalkScene(
            RomView.ofRom(bytes), bytes, hw, engine, sprites, KeyStatus(), { 0 },
            MsgStrings(bytes, MsgHuffman(bytes, 0, 0), 0), {}, { 0 }, {}, LcgRng(),
        )
        val gm = GmData(iwram, RngService())
        val faces = WorldMapFace(
            engine, hw, gm, RomView.ofRom(bytes), scene.face, scene, scene,
        )

        init {
            faces.setupFaceConfig()
        }

        fun runFrame() {
            sprites.clear()
            for (i in 0 until 8) engine.run(engine.root(i))
        }

        fun ctrl(): Proc? = engine.find(faces.ctrlScript)

        fun ctrlState() = ctrl()!!.state as WmFaceCtrlState

        fun holder() = ctrlState().faceHolderProc!!.state as WmFaceHolderState
    }

    private fun available(): ReferenceData.Run {
        assumeTrue(ref.available && romFile.isFile, "reference data / ROM not present")
        assumeTrue(names.isNotEmpty(), "decomp nm file not present")
        return ref.run("ram_trace_prologue")
    }

    private fun dump(step: Int, region: String): ByteArray? {
        for (prefix in listOf("r", "f")) {
            val file = File(runDir, "%s%05d.%s.bin".format(prefix, step, region))
            if (file.isFile) return file.readBytes()
        }
        return null
    }

    private fun rig(run: ReferenceData.Run) = Rig(run.region("r05250", "iwram").copyOf(0x8000))

    private val names by lazy { ProcArrayDump.romNames() }

    private fun procs(run: ReferenceData.Run, step: Int) =
        ProcArrayDump.ofWatch(run.watch("procs"), step, names)

    private fun ProcArrayDump.holder() = find(HOLDER)
    private fun ProcArrayDump.ctrl() = find(CTRL)
    private fun ProcArrayDump.eface() = find(EFACE_EXTRA)

    private fun simWrapper(rig: Rig, i: Int): List<Int> {
        val w = rig.holder().faceWrapper[i]
        return listOf(w.x, w.y, if (w.faceProc == null) 0 else 1, w.faceId, w.unk0a)
    }

    /** Wrapper fields of the trace in the order of [simWrapper] (presence instead of pointer). */
    private fun traceWrapper(p: ProcArrayDump.Proc, i: Int): List<Int> {
        val at = 0x2C + 12 * i
        return listOf(
            p.u16(at), p.u16(at + 2), if (p.u32(at + 4) == 0) 0 else 1, p.u16(at + 8),
            p.u8(at + 10).toByte().toInt(),
        )
    }

    /**
     * Runs the controller from step 4907 to [last], calling [after] on every step (after the
     * frame and the replayed commands). Returns the number of frames run.
     */
    private fun simulate(
        run: ReferenceData.Run,
        rig: Rig,
        last: Int,
        after: (step: Int, ran: Boolean) -> Unit,
    ) {
        val start = procs(run, FIRST_STEP)
        assertNotNull(start.ctrl(), "controller exists at $FIRST_STEP")
        rig.faces.startWmFaceCtrl(null)
        // Proc_Start inside a running frame: the new child also runs its first loop.
        rig.runFrame()
        var previousTimer = start.ctrl()!!.u16(0x2A)
        after(FIRST_STEP, true)
        for (step in FIRST_STEP + 1..last) {
            val now = procs(run, step)
            val ctrl = now.ctrl() ?: break
            val timer = ctrl.u16(0x2A)
            val ran = timer != previousTimer
            previousTimer = timer
            if (ran) rig.runFrame()
            for (slot in 0 until 2) {
                val t = traceWrapper(now.holder()!!, slot)
                val s = simWrapper(rig, slot)
                if (t[2] == 1 && s[2] == 0) {
                    val config = t[0] or ((t[1] - 0x800) and 0xFF00)
                    rig.faces.wmDrawFace(slot, t[3], config)
                } else if (t[1] and 0x1000 != 0 && t[4] == -1 && s[1] and 0x1000 == 0) {
                    rig.faces.wmClearFace(slot, t[1] and 0x300)
                }
            }
            after(step, ran)
        }
    }

    @Test
    @VerifiesPort("WorldMapFaceHolder_Init", "WMFaceCtrl_Init")
    fun `controller and holder init match the proc fields at 4907`() {
        val run = available()
        val rig = rig(run)
        rig.faces.startWmFaceCtrl(null)
        val trace = procs(run, FIRST_STEP)
        val c = trace.ctrl()!!
        val state = rig.ctrlState()
        assertEquals(0, state.timer, "init timer (the trace has the first loop already run)")
        assertEquals(1, c.u16(0x2A), "trace timer after the first loop")
        assertEquals(c.u8(0x30), state.increment and 0xFF, "increment")
        assertEquals(c.u8(0x31), state.blendAmt, "blendAmt")
        for (i in 0 until 2) {
            assertEquals(traceWrapper(trace.holder()!!, i), simWrapper(rig, i), "wrapper $i")
        }
        assertEquals(HOLDER_MARK, rig.ctrl()!!.mark, "controller mark")
    }

    @Test
    @VerifiesPort(
        "WMFaceCtrl_Loop", "WMFaceCtrl_LoopExt", "HandleWMFaceFade", "WmDrawFace", "WmClearFace",
        "GetWMFaceBlendAmt",
    )
    fun `controller holder and face procs match every step from 4907 to 8846`() {
        val run = available()
        val rig = rig(run)
        val seen = mutableSetOf<Int>()
        var faceSteps = 0
        simulate(run, rig, LAST_STEP) { step, _ ->
            val trace = procs(run, step)
            val c = trace.ctrl()!!
            val state = rig.ctrlState()
            if (step > FIRST_STEP) {
                assertEquals(c.u16(0x2A), state.timer, "timer @$step")
            }
            assertEquals(c.u8(0x30), state.increment and 0xFF, "increment @$step")
            assertEquals(c.u8(0x31), state.blendAmt, "blendAmt @$step")
            assertEquals(c.u8(0x31) shr 1, rig.faces.getWmFaceBlendAmt(), "blend coeff @$step")
            for (i in 0 until 2) {
                assertEquals(
                    traceWrapper(trace.holder()!!, i), simWrapper(rig, i), "wrapper $i @$step",
                )
            }
            val face = trace.eface()
            val simFace = rig.scene.face.gFaces[0]
            assertEquals(face != null, simFace != null, "face exists @$step")
            if (face != null && simFace != null) {
                val st = simFace.state as FaceState
                seen += face.u16(0x3E)
                faceSteps++
                assertEquals(face.u32(0x30), st.displayBits, "displayBits @$step")
                assertEquals(face.u16(0x34).toShort().toInt(), st.xPos, "xPos @$step")
                assertEquals(face.u16(0x36).toShort().toInt(), st.yPos, "yPos @$step")
                assertEquals(face.u16(0x3C), st.oam2, "oam2 @$step")
                assertEquals(face.u16(0x3E), st.faceId, "faceId @$step")
                assertEquals(face.u8(0x40), st.faceSlot, "slot @$step")
                assertEquals(face.u8(0x41), st.spriteLayer, "layer @$step")
                assertEquals(face.u32(0x38), st.sprite?.id, "sprite @$step")
            }
        }
        assertTrue(seen.size >= 4, "several portraits are shown: $seen")
        assertTrue(faceSteps > 1500, "face steps compared: $faceSteps")
    }

    @Test
    @VerifiesPort(
        "PutFaceOnBackGround", "sub_80B82C8", "sub_80B826C", "GetWMFaceBg",
        "GetWMFaceVramOffset",
    )
    fun `BG2 tile map matches the EWRAM dumps and portrait data the full dumps`() {
        val run = available()
        val rig = rig(run)
        assertEquals(1, rig.gm.stateRaw shr 3 and 1, "state_3 set (as from step 4950): BG2")
        var maps = 0
        var nonEmpty = 0
        val full = mutableListOf<Int>()
        simulate(run, rig, 7500) { step, ran ->
            if (!ran) return@simulate
            val ewram = dump(step, "ewram") ?: return@simulate
            val map = rig.hw.mapBuffer(2)
            val expected = ShortArray(map.size) {
                ((ewram[BG2_MAP + 2 * it].toInt() and 0xFF) or
                    ((ewram[BG2_MAP + 2 * it + 1].toInt() and 0xFF) shl 8)).toShort()
            }
            assertEquals(expected.toList(), map.toList(), "BG2 tile map @$step")
            maps++
            if (map.any { it.toInt() != 0 }) nonEmpty++
            if (step < 6000 || dump(step, "vram") == null) return@simulate
            full += step
            val vram = dump(step, "vram")!!
            assertEquals(
                vram.copyOfRange(0x5000, 0x6000).toList(),
                rig.hw.vram.copyOfRange(0x5000, 0x6000).toList(),
                "BG portrait tiles @$step",
            )
            val io = dump(step, "io")!!
            assertEquals(u16(io, 0x18), rig.hw.lcd.bgHofs[2], "BG2HOFS @$step")
            assertEquals(u16(io, 0x1A), rig.hw.lcd.bgVofs[2], "BG2VOFS @$step")
        }
        assertTrue(maps > 100, "tile map dumps compared: $maps")
        assertTrue(nonEmpty > 3, "dumps taken while a portrait slides: $nonEmpty")
        assertEquals(listOf(6000, 6300, 6600, 6900, 7200, 7500), full, "full dumps")
    }

    @Test
    fun `palette bank 4 blend registers and face OBJ match the full dumps`() {
        val run = available()
        val rig = rig(run)
        var objChecked = 0
        simulate(run, rig, 7500) { step, ran ->
            if (!ran || step < 6000 || dump(step, "vram") == null) return@simulate
            val pal = dump(step, "pal")!!
            for (i in 64 until 80) {
                assertEquals(
                    u16(pal, 2 * i), rig.hw.palette[i].toInt() and 0xFFFF, "BG pal $i @$step",
                )
            }
            val io = dump(step, "io")!!
            assertEquals(u16(io, 0x50) and 0x3FFF, rig.hw.lcd.bldcnt and 0x3FFF, "BLDCNT @$step")
            assertEquals(
                u16(io, 0x52), rig.hw.lcd.blendCoeffA or (rig.hw.lcd.blendCoeffB shl 8),
                "BLDALPHA @$step",
            )
            val face = rig.scene.face.gFaces[0]
            val st = face?.state as FaceState?
            if (face != null && st!!.displayBits and 0x4000 == 0) {
                val oam = OamBuffer()
                rig.sprites.pushLayerObjects(0) {
                    oam.putHi(it.oam1, it.oam0, it.obj!!.data, it.oam2)
                }
                val count = oam.hiPut / 4
                val reference = dump(step, "oam")!!
                assertTrue(count > 0, "face OBJ drawn @$step")
                assertTrue(
                    (0..128 - count).any { start ->
                        (0 until count).all { i ->
                            (0..2).all { attr ->
                                u16(reference, (start + i) * 8 + attr * 2) ==
                                    (oam.buffer[i * 4 + attr].toInt() and 0xFFFF)
                            }
                        }
                    },
                    "face OBJ differs @$step",
                )
                objChecked++
            }
        }
        assertTrue(objChecked >= 2, "dumps with a shown face OBJ: $objChecked")
    }

    private fun u16(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private companion object {
        const val FIRST_STEP = 4907
        const val LAST_STEP = 8846
        const val HOLDER = "ProcScr_WorldMapFaceHolder"
        const val CTRL = "ProcScr_WorldMapFaceCtrl"
        const val EFACE_EXTRA = "gProcScr_E_FACE_ExtraFrame"
        const val HOLDER_MARK = 8

        /** `gBG2TilemapBuffer` (0x02023CA8) as an EWRAM offset. */
        const val BG2_MAP = 0x23CA8
    }
}
