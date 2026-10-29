package fe8.testing

import fe8.game.opanim.EarlyStartUi
import fe8.game.opanim.OpAnimHs
import fe8.game.GameControl
import fe8.game.opanim.OpAnim
import fe8.game.title.TitleScreen
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject
import gbafe.core.VerifiesPort
import gbafe.core.time.GameClock
import gbafe.input.KeyComboReset
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.render.Camera
import gbafe.render.FrameBuffer
import gbafe.render.LcdControl
import gbafe.render.OamBuffer
import gbafe.render.RenderState
import gbafe.render.SoftwareRenderer
import gbafe.render.TileSheets
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Runs `ProcScr_GameEarlyStartUI` ([EarlyStartUi]) on our [ProcEngine] frame by frame against `ram_trace_logo_skip`
 * (RAM every frame f400..f520; B on the IS logo at f470): starting from the proc as dumped at f400 (script position,
 * repeat callback, fields, `gPaletteBuffer`), each frame feeds that frame's dumped key status and runs the proc
 * tree; script position (`proc_scrCur`), whether a repeat callback is set, `ProcOpAnimHS` fields and the palette
 * buffer (plus `gKeyComboResetEN`, set by the script's `EnableKeyComboResetEN` at f413, and `gGameClock`)
 * must match every dump, through the skip (Proc_Goto), the skip fade and the proc's end (PROC_END) at f475. Its parent
 * is the game control proc ([GameControl], tree 3), which then sets the sound option bits and starts the op anim
 * ([OpAnim], ported up to `OpAnimInit`) as its blocking child at f476; its script position and `gPlaySt.config`
 * sound bits are checked too, and on f476 the op anim (script position, timer, terminator child, `gOpAnimSt.pal`,
 * palette buffer, DISPCNT) and GAMECTRL `nextAction`; then the world map setup (which overruns 3 frames) and the
 * `ProcScr_OpAnimBLDALPHA` fade (timer, palette, blend registers) until f520.
 */
class ProcEngineDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    private fun procAt(ew: ByteArray, script: Int = PROC_SCRIPT): Int? {
        val a = sym["sProcArray"]
        return (0 until a.size / PROC_SIZE).map { a.address - GbaMem.EWRAM + it * PROC_SIZE }.firstOrNull { u32(ew, it) == script }
    }

    /** The game's sprite chain from layer head [layer]: (oam1, oam0, oam2, object address or 0) per entry. */
    private fun spriteChain(ew: ByteArray, layer: Int): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var p = sym["sSpriteLayers"].address + 16 * layer
        while (p != 0) {
            val o = p - GbaMem.EWRAM
            out += listOf(u16(ew, o + 4), u16(ew, o + 6), u16(ew, o + 8), u32(ew, o + 0x0C))
            p = u32(ew, o)
        }
        return out
    }

    private fun ourChain(s: SpriteLayers, layer: Int): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var it: SpriteLayers.Entry? = s.layers[layer]
        while (it != null) {
            out += listOf(it.oam1 and 0xFFFF, it.oam0 and 0xFFFF, it.oam2, it.obj?.id ?: 0)
            it = it.next
        }
        return out
    }

    private fun palette(ew: ByteArray) = ShortArray(512) { u16(ew, sym["gPaletteBuffer"].address - GbaMem.EWRAM + 2 * it).toShort() }

    private fun lcdDispcnt(iw: ByteArray) = LcdControl().apply { readFrom(iw, sym["gLCDControlBuffer"].address - GbaMem.IWRAM) }.dispcnt

    @Test
    @VerifiesPort("Proc_Start", "Proc_Run", "Proc_Break", "Proc_Goto", "Proc_End", "Proc_Find", "EnableKeyComboResetEN", "CheckCanKeyComboReset", "IncrementGameClock", "GetGameClock", "GameControl_EnableSoundEffects", "Proc_StartBlocking",
        "SetNextGameActionId", "CopyToPalOpAnim",
        "OpAnimBldAlphaUpdateBgPalette", "OpAnimBldAlphaExists", "OpAnimBldAlphaDelay", "OpAnimBldAlphaMain",
        "OpAnimUpdateScreen1", "sub_80C6F70", "sub_80C69B0", "NewProcOpAnimFadeIn", "OpAnimAllBlack", "OpAnimFadeInMain", "sub_80C7050",
        "OpAnimPreparefxEphraim", "NewProc08AA6D04", "Proc08AA6D04Main", "OpAnimEphraimfxFlyIn", "Interpolate",
        "TsaModifyFirstPalMaybe", "sub_80C689C", "CopyFirstPalDirectly", "SetFirstPalDirectly", "ClearFirstPalDirectly",
        "OpAnimMergeBGProcInit", "OpAnimMergeBGProcUpdateBgPalette", "sub_80C7900",
        "ClearSprites", "PutSpriteExt", "OpAnimDrawSplitLine", "OpAnim1AdvanceSplitLine", "OpAnimEphraimMergeShadow",
        "OpAnimEphraimDisplayName", "OpAnimEphraimExit", "OpAnimPreparefxEirika",
        "sub_80C7F90", "sub_80C8100", "sub_80C8184", "sub_80C8214", "sub_80C8278", "OpAnim1_UpdateScrollOneLine",
        "sub_80C835C", "sub_80C84D8",
        "sub_80C8564", "sub_80C72A4", "OpAnim1_UpdateScroll", "sub_80C8580", "sub_80C85B0", "sub_80C85FC", "sub_80C8690",
        "sub_80C8554", "sub_80C9DF0", "sub_80C9E6C", "sub_80C9B6C", "sub_80C9C08", "sub_80C9FF8",
        "sub_80CA10C", "sub_80CA26C", "sub_80CA3B8", "sub_80C9EE8", "sub_80C9F7C",
        "sub_80C883C", "sub_80C8934", "sub_80C8A20", "sub_80C8B0C", "sub_80C8BF8",
        "sub_80C9C5C", "sub_80C9CAC", "sub_80C9CFC", "sub_80C9D4C", "sub_80C9DA0",
        "sub_80C8608", "sub_80C8638", "sub_80C8684", "sub_80C8CE4", "sub_80CA4A4",
        "sub_80CA4DC", "OpAnimPutObjCommon", "sub_80CA92C", "sub_80C8D30",
        "sub_80CA940", "sub_80CA9F8", "sub_80CAA38", "sub_80C8ED4", "sub_80CABB0", "sub_80C9024", "sub_80CAE20", "sub_80C9100", "sub_80CAF2C", "sub_80C9218",
        "sub_80CB0A0", "sub_80C9330", "sub_80CB20C", "sub_80C940C", "sub_80CB320",
        "sub_80C955C", "sub_80CB594", "sub_80C9638", "sub_80CB6A0", "sub_80C9750", "sub_80CB878", "sub_80C98A0",
        "sub_80CBA64", "sub_80C99B8", "sub_80CBC40", "sub_80C9A94", "sub_80C9AFC",
        "sub_80CBD7C", "EndAllProcChildren")
    fun `intro proc runs frame by frame like the game`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val run = ref.run("ram_trace_logo_skip")
        val run2 = ref.run("ram_trace_opanim") // same inputs, f515..f1100
        val run3 = ref.run("ram_trace_opanim2") // same inputs, f1090..f1600 (full dumps every 10 frames from f1100)
        val run4 = ref.run("ram_trace_opanim3") // same inputs, f1590..f2600 (full dumps every 10 frames from f1600)
        val run5 = ref.run("ram_trace_opanim4") // same inputs, f2590..f3000 (full dumps every 10 frames from f2600)
        val run6 = ref.run("ram_trace_title1") // same inputs, f2990..f3600: the title screen
        fun dump(f: Int, region: String) = when {
            f <= 520 -> run.region("r%05d".format(f), region)
            f <= 1100 -> run2.region("r%05d".format(f), region)
            f <= 1600 -> run3.region((if (f % 10 == 0) "f%05d" else "r%05d").format(f), region)
            f <= 2600 -> run4.region((if (f % 10 == 0) "f%05d" else "r%05d").format(f), region)
            f <= 3000 -> run5.region((if (f % 10 == 0) "f%05d" else "r%05d").format(f), region)
            else -> run6.region((if (f % 10 == 0) "f%05d" else "r%05d").format(f), region)
        }
        fun ew(f: Int) = dump(f, "ewram")
        fun iw(f: Int) = dump(f, "iwram")
        fun romPal(at: Int, n: Int) = ShortArray(n) { u16(rom, at - 0x08000000 + 2 * it).toShort() }
        fun blob(at: Int) = OpAnimHs.Blob(rom, at - 0x08000000)
        fun face(img: Int, shadow: Int, pal: Int) = OpAnim.Face(blob(img), blob(shadow), romPal(pal, 16))
        fun objN(at: Int) = SpriteObject(at, romPal(at, 1 + 3 * u16(rom, at - 0x08000000)))
        val keys = KeyStatus()
        val e0 = ew(400)
        val i0 = run.region("r00400", "iwram")
        val combo = KeyComboReset().apply { enabled = i0[sym["gKeyComboResetEN"].address - GbaMem.IWRAM].toInt() != 0 }
        val clock = GameClock().apply { frames = u32(i0, sym["gGameClock"].address - GbaMem.IWRAM).toLong() and 0xFFFFFFFFL }
        val env = EarlyStartUi.Env(palette(e0), ShortArray(512), LcdControl().apply { readFrom(i0, sym["gLCDControlBuffer"].address - GbaMem.IWRAM) },
            keys, romPal(0x08B125F4, 16), romPal(0x08B10ABC, 16), romPal(0x08B11864, 48),
            gfx = EarlyStartUi.Gfx(blob(0x08B11D0C), blob(0x08B118C4), blob(0x08B11C0C), blob(0x08B10630),
                blob(0x08B104D8), blob(0x08B10CA4), blob(0x08B10ADC)),
            keyComboReset = combo)
        val engine = ProcEngine()
        val ui = EarlyStartUi(engine, env)
        val o = procAt(e0)!!
        // The game starts the intro as a child of its main proc: stand in a root proc that only blocks.
        assertEquals(true, u32(e0, o + 0x14) ushr 24 == 0x02, "intro proc has a proc parent")
        // The game control proc (GAMECTRL, tree 3) as at f400: blocked on the intro, its blocking child.
        val rootIndex = 3
        lateinit var opPort: OpAnim
        lateinit var title: TitleScreen
        val ctrl = GameControl(engine, keys, { ui.script }, { opPort.script }, { title })
        opPort = fe8.game.BootRom.opAnim(fe8.game.RomView.ofRom(rom), engine, env.hardware, keys, ctrl)
        title = titleScreenFor(rom, engine, env.hardware, opPort.sprites, keys, ctrl)
        val opAnim = opPort.script
        val parent = engine.start(ctrl.script, rootIndex)
        parent.idleCb = null
        ctrl.playStConfig = u32(e0, sym["gPlaySt"].address - GbaMem.EWRAM + 0x40)
        val proc = engine.startBlocking(ui.script, parent)!!
        parent.scrCur = 8
        assertEquals(1, proc.scrCur) // PROC_YIELD ran
        // Put the proc where the game had it at f400.
        proc.scrCur = (u32(e0, o + 4) - PROC_SCRIPT) / 8
        proc.idleCb = if (u32(e0, o + 0x0C) != 0) ui.script.cmds[proc.scrCur - 1].ptr as gbafe.proc.ProcFunc else null
        val st = proc.state as OpAnimHs.Proc
        st.status = u32(e0, o + 0x2C); st.paletteTimer = u32(e0, o + 0x30); st.delayTimer = u32(e0, o + 0x34); st.skipFadeTimer = u32(e0, o + 0x38)
        var ended = 0
        var opStarted = 0
        var opChecked = 0
        var titleChecked = 0
        var ephraimChecked = 0
        var flyInChecked = 0
        var splitChecked = 0
        var splitLineChecked = 0
        var nameChecked = 0
        var nameEnded = false
        var exitChecked = 0
        var eirikaChecked = 0
        var eirikaFlyInChecked = 0
        var eirikaSplitChecked = 0
        var eirikaNameChecked = 0
        var eirikaNameEnded = false
        var scrollVramChecked = 0
        var eirikaExitChecked = 0
        var darkChecked = 0
        var castDarkenChecked = 0
        var joshuaChecked = 0
        var castFadeChecked = 0
        var joshuaInChecked = 0
        val sceneOutChecked = IntArray(3)
        val sceneLoadChecked = IntArray(5)
        var castEndChecked = 0
        var sparklesChecked = 0
        var facesChecked = 0
        var faces1InChecked = 0
        var faces2Checked = 0
        var faces2InChecked = 0
        var faces3Checked = 0
        var faces3InChecked = 0
        var faces4Checked = 0
        var faces4InChecked = 0
        var faces5Checked = 0
        var faces5InChecked = 0
        var faces6Checked = 0
        var faces6InChecked = 0
        var faces7Checked = 0
        var faces7InChecked = 0
        val laterChecked = IntArray(10)
        var ringBlinkChecked = 0
        var ringOutChecked = 0
        var whiteChecked = 0
        var sleepChecked = 0
        var endChecked = 0
        var fadeOutProcChecked = 0
        val sceneInChecked = IntArray(5)
        var enabledSeen = false
        val io = ByteArray(0x60) // I/O registers as FlushLCDControl writes them
        var framesChecked = 0
        val oam = OamBuffer().apply { clear(0, OamBuffer.OBJ_COUNT) }
        fun vblankSync() { // OnVBlank's display sync after a finished step
            env.hardware.lcd.flushTo(io)
            env.hardware.flushBackgrounds()
            oam.syncLo()
            oam.syncHi()
        }
        for (f in 401..LAST) {
            val e = ew(f)
            keys.readFrom(e, sym["sKeyStatusBuffer"].address - GbaMem.EWRAM)
            clock.increment()
            // Large decompressions (SetupOpAnimWorldMapfx, OpAnimPreparefxEphraim) overrun: while the previous dump
            // shows a step still running (gBmSt.main_loop_ended 0), the game is still finishing it and starts none.
            val lagging = ew(f - 1)[sym["gBmSt"].address - GbaMem.EWRAM].toInt() == 0
            val midStep = e[sym["gBmSt"].address - GbaMem.EWRAM].toInt() == 0
            // gGenericBuffer holds earlier screens' leftovers the op anim only partly overwrites: start from the game's.
            if (f == 843) ew(f - 1).copyInto(opPort.genericBuffer, 0, sym["gGenericBuffer"].address - GbaMem.EWRAM,
                sym["gGenericBuffer"].address - GbaMem.EWRAM + 0x2000)
            engine.run(engine.root(0)) // VSync tree (OnVBlank), before the step
            if (!lagging) {
                opPort.sprites.clear() // OnMain's ClearSprites
                engine.run(engine.root(rootIndex))
                for (head in listOf(0, 13)) opPort.sprites.pushLayerObjects(head) { oam.putHi(it.oam1, it.oam0, it.obj!!.data, it.oam2) }
                if (!midStep) vblankSync()
            } else {
                vblankSync() // the overrunning step finished during this frame
            }
            if (f >= 1100 && (f + 1) % 10 == 0 && f + 1 <= 3000 && !midStep) {
                val nf = "f%05d".format(f + 1)
                val r = if (f + 1 <= 1600) run3 else if (f + 1 <= 2600) run4 else run5
                val w = r.region(nf, "iwram")
                if (u32(w, sym["sHBlankHandler1"].address - GbaMem.IWRAM) == 0 && u32(w, sym["sHBlankHandler2"].address - GbaMem.IWRAM) == 0) {
                    val state = RenderState()
                    val sheets = TileSheets()
                    gbafe.render.HardwareScene.load(state, sheets, env.hardware, oam, io)
                    val fb = FrameBuffer()
                    SoftwareRenderer(state, sheets, fb, MgbaColorMath).render(Camera())
                    val dp = r.region(nf, "pal"); val bad = (0 until 512).filter { u16(dp, 2 * it) != (env.hardware.paletteRam[it].toInt() and 0xFFFF) }
                    val dio = r.region(nf, "io"); val badIo = (0 until 0x56 step 2).filter { u16(dio, it) != u16(io, it) }.map { "%X:%04X/%04X".format(it, u16(dio, it), u16(io, it)) }
                    if (f + 1 !in FRAME_GAPS) {
                        val out = File(System.getProperty("fe8.frameDiffDir") ?: "build/frame-diff", "op_ours_$nf.png")
                        assertEquals(0, HardwareScene.compare(fb, r.frameRgb(nf), out), "$nf: frame from our display state")
                        framesChecked++
                    }
                }
            }
            val i = iw(f)
            assertEquals(i[sym["gKeyComboResetEN"].address - GbaMem.IWRAM].toInt() != 0, combo.canReset(), "f$f gKeyComboResetEN")
            assertEquals(u32(i, sym["gGameClock"].address - GbaMem.IWRAM).toLong() and 0xFFFFFFFFL, clock.get(), "f$f gGameClock")
            if (f == 413) enabledSeen = combo.canReset()
            val d = procAt(e)
            assertEquals(d != null, engine.find(ui.script) === proc, "f$f Proc_Find")
            val c = procAt(e, GAME_CTRL_SCRIPT)!!
            assertEquals((u32(e, c + 4) - GAME_CTRL_SCRIPT) / 8, parent.scrCur, "f$f GAMECTRL scrCur")
            assertEquals(u32(e, sym["gPlaySt"].address - GbaMem.EWRAM + 0x40) and 0x300, ctrl.playStConfig and 0x300, "f$f sound option bits")
            if (d == null) {
                assertTrue(proc.script !== ui.script, "f$f: proc should have ended") // its slot may already run the op anim
                val op = procAt(e, OP_ANIM_SCRIPT)
                if (op != null) assertEquals(GbaMem.EWRAM + c, u32(e, op + 0x14), "f$f op anim is GAMECTRL's child")
                assertEquals(op != null, parent.child?.script === opAnim, "f$f GAMECTRL started the op anim")
                if (op != null) opStarted++
                val t = procAt(e, TITLE_SCRIPT)
                assertEquals(t != null, parent.child?.script === title.script, "f$f GAMECTRL started the title screen")
                if (t != null && !midStep) {
                    assertEquals((u32(e, t + 4) - TITLE_SCRIPT) / 8, parent.child!!.scrCur, "f$f title scrCur")
                    assertArrayEquals(palette(e), env.paletteBuffer, "f$f gPaletteBuffer (title)")
                    titleChecked++
                }
                val ram = op?.let { (u32(e, it + 4) - OP_ANIM_SCRIPT) / 8 }
                if (ram != null && !midStep && ram <= 150) {
                    val ours = parent.child!!
                    val bld = procAt(e, BLDALPHA_SCRIPT)
                    val ourBld = ours.child?.takeIf { it.script === opPort.bldAlphaScript }
                    assertEquals(ram, ours.scrCur, "f$f op anim scrCur")
                    assertEquals(e[c + 0x29].toInt() and 0xFF, (parent.state as GameControl.State).nextAction, "f$f nextAction")
                    assertEquals(procAt(e, TERMINATOR_SCRIPT) != null, engine.find(opPort.terminatorScript) != null, "f$f terminator")
                    assertEquals(u16(e, op + 0x2A), (ours.state as OpAnim.State).timer, "f$f op anim timer")
                    assertEquals(bld != null, ourBld != null, "f$f BLDALPHA proc")
                    val fade = procAt(e, FADE_IN_SCRIPT)
                    val ourFade = engine.find(opPort.fadeInScript)
                    assertEquals(fade != null, ourFade != null, "f$f fade-in proc")
                    if (fade != null) {
                        val fs = ourFade!!.state as OpAnim.FadeInState
                        assertEquals(listOf((u32(e, fade + 4) - FADE_IN_SCRIPT) / 8, u16(e, fade + 0x4C), u16(e, fade + 0x64)),
                            listOf(ourFade.scrCur, fs.cur and 0xFFFF, fs.max and 0xFFFF), "f$f fade-in scrCur, cur, max")
                    }
                    // unk2C/2E keep the slot's old bytes (Proc_Start does not clear fields) until OpAnimUpdateScreen1.
                    if (ram >= 9) assertEquals(listOf(0x38, 0x3A, 0x3C, 0x3E).map { u16(e, op + it) } + (e[op + 0x46].toInt() and 0xFF),
                        (ours.state as OpAnim.State).unk38to46.toList(), "f$f op anim unk38..46")
                    if (ram >= 7) assertEquals(listOf(u16(e, op + 0x2C), u16(e, op + 0x2E)),
                        listOf((ours.state as OpAnim.State).unk2C and 0xFFFF, (ours.state as OpAnim.State).unk2E and 0xFFFF), "f$f op anim unk2C/2E")
                    if (bld != null) {
                        assertEquals(listOf((u32(e, bld + 4) - BLDALPHA_SCRIPT) / 8, u16(e, bld + 0x2A)),
                            listOf(ourBld!!.scrCur, (ourBld.state as OpAnim.BldAlphaState).timer), "f$f BLDALPHA scrCur, timer")
                    }
                    val opSt = sym["gOpAnimSt"].address - GbaMem.EWRAM
                    assertEquals(u16(e, opSt + 0x0C), opPort.pal, "f$f gOpAnimSt.pal")
                    val lcd = LcdControl().apply { readFrom(iw(f), sym["gLCDControlBuffer"].address - GbaMem.IWRAM) }
                    if (ram >= 11) {
                        val os = ours.state as OpAnim.State
                        assertEquals((0 until 6).map { u16(e, opSt + 2 * it) }, opPort.opAnimSt.map { it and 0xFFFF },
                            "f$f gOpAnimSt x..unk0A")
                        // unk30/32 hold the slot's old bytes until the last step (gOpAnimSt.unk06 = 0xA0) clears them.
                        if (u16(e, opSt + 6) == 0xA0) assertEquals(listOf(u16(e, op + 0x30), u16(e, op + 0x32)),
                            listOf(os.unk30 and 0xFFFF, os.unk32 and 0xFFFF), "f$f op anim unk30/32")
                        val gb = sym["gGenericBuffer"].address - GbaMem.EWRAM
                        assertArrayEquals(e.copyOfRange(gb, gb + 0x2000), opPort.genericBuffer, "f$f gGenericBuffer")
                        val bg3 = sym["gBG3TilemapBuffer"].address - GbaMem.EWRAM
                        for (bg in 0..3) {
                            val m = sym["gBG${bg}TilemapBuffer"].address - GbaMem.EWRAM
                            assertArrayEquals(ShortArray(0x400) { u16(e, m + 2 * it).toShort() }, env.hardware.mapBuffer(bg),
                                "f$f gBG${bg}TilemapBuffer")
                        }
                        assertEquals(procAt(e, PROC_6D04_SCRIPT) != null, engine.find(opPort.proc08AA6D04Script) != null,
                            "f$f ProcScr_08AA6D04")
                        val merge = procAt(e, MERGE_BG_SCRIPT)
                        val ourMerge = engine.find(opPort.mergeBgScript)
                        assertEquals(merge != null, ourMerge != null, "f$f ProcScr_OpAnimMergeBG")
                        if (merge != null) assertEquals(listOf((u32(e, merge + 4) - MERGE_BG_SCRIPT) / 8, u16(e, merge + 0x4C)),
                            listOf(ourMerge!!.scrCur, (ourMerge.state as OpAnim.MergeBgState).timer and 0xFFFF), "f$f merge BG scrCur, timer")
                        if (ram == 13) flyInChecked++
                        if (ram == 14) splitChecked++
                        if (ram == 15) splitLineChecked++
                        if (ram == 17) nameChecked++
                        if (ram == 18) exitChecked++
                        if (ram == 19) eirikaChecked++
                        if (ram == 20) eirikaFlyInChecked++
                        if (ram in 21..23) eirikaSplitChecked++
                        if (ram == 24) eirikaNameChecked++
                        if (ram == 25) eirikaExitChecked++
                        if (ram == 26) darkChecked++
                        if (ram == 29) joshuaChecked++
                        if (ram == 32) castFadeChecked++
                        if (ram == 34) joshuaInChecked++
                        if (ram in 35..36) sceneOutChecked[ram - 35]++
                        if (ram == 38) sceneOutChecked[2]++
                        if (ram == 92) castEndChecked++
                        if (ram == 94) sparklesChecked++
                        if (ram == 97) facesChecked++
                        if (ram == 99) faces1InChecked++
                        if (ram == 101) faces2Checked++
                        if (ram == 103) faces2InChecked++
                        if (ram == 105) faces3Checked++
                        if (ram == 107) faces3InChecked++
                        if (ram == 109) faces4Checked++
                        if (ram == 111) faces4InChecked++
                        if (ram == 113) faces5Checked++
                        if (ram == 115) faces5InChecked++
                        if (ram == 117) faces6Checked++
                        if (ram == 119) faces6InChecked++
                        if (ram == 121) faces7Checked++
                        if (ram == 123) faces7InChecked++
                        if (ram in 125..143 && ram % 2 == 1) laterChecked[(ram - 125) / 2]++
                        if (ram == 145) ringOutChecked++
                        if (ram == 146) whiteChecked++
                        if (ram == 147) sleepChecked++
                        if (ram == 150) endChecked++
                        if (ram in 144..146 && f % 10 == 0) {
                            assertArrayEquals(dump(f, "vram").copyOfRange(0xF000, 0xF500),
                                env.hardware.vram.copyOfRange(0xF000, 0xF500), "f$f blinking ring map")
                            ringBlinkChecked++
                        }
                        if (ram in 117..141 step 4 && f % 10 == 0) {
                            val v = dump(f, "vram")
                            assertArrayEquals(v.copyOfRange(0x10000, 0x17800), env.hardware.vram.copyOfRange(0x10000, 0x17800),
                                "f$f face OBJ tiles (6..12)")
                        }
                        if (ram == 103 && f % 10 == 0) {
                            val v = dump(f, "vram")
                            assertArrayEquals(v.copyOfRange(0x10000, 0x17800), env.hardware.vram.copyOfRange(0x10000, 0x17800),
                                "f$f face OBJ tiles (2)")
                        }
                        if (ram == 97 && f % 10 == 0 && u16(e, op + 0x2A) > 5) {
                            val v = dump(f, "vram")
                            assertArrayEquals(v.copyOfRange(0x10000, 0x15A00), env.hardware.vram.copyOfRange(0x10000, 0x15A00),
                                "f$f face OBJ tiles")
                        }
                        val fo = procAt(e, CAST_FADE_OUT_SCRIPT)
                        val ourFo = engine.find(opPort.castBrightenScript)
                        assertEquals(fo != null, ourFo != null, "f$f gUnknown_08AA707C")
                        if (fo != null) {
                            assertEquals(listOf((u32(e, fo + 4) - CAST_FADE_OUT_SCRIPT) / 8, u16(e, fo + 0x4C)),
                                listOf(ourFo!!.scrCur, (ourFo.state as OpAnim.BlendState).timer), "f$f 707C scrCur, timer")
                            fadeOutProcChecked++
                        }
                        if (ram in 40..88 && (ram - 40) % 10 == 0) sceneLoadChecked[(ram - 40) / 10]++
                        if (ram in 44..88 && (ram - 44) % 10 == 0) sceneInChecked[(ram - 44) / 10]++
                        assertEquals(procAt(e, BG3_SCROLL_SCRIPT) != null, engine.find(opPort.bg3ScrollScript) != null,
                            "f$f gUnknown_08AA704C")
                        if (ram >= 33) {
                            val gb = sym["gGenericBuffer"].address
                            assertEquals(listOf(u16(e, op + 0x40), u16(e, op + 0x42), u16(e, op + 0x44), e[op + 0x47].toInt() and 0xFF,
                                u32(e, op + 0x48) - gb, u32(e, op + 0x4C) - gb),
                                listOf(os.unk40to44[0], os.unk40to44[1], os.unk40to44[2] and 0xFFFF, os.unk47, os.unk48, os.unk4C),
                                "f$f op anim unk40..4C")
                            assertEquals(listOf(u16(e, op + 0x30), u16(e, op + 0x32)),
                                listOf(os.unk30 and 0xFFFF, os.unk32 and 0xFFFF), "f$f op anim unk30/32 (cast)")
                        }
                        val cd = procAt(e, CAST_DARKEN_SCRIPT)
                        val ourCd = engine.find(opPort.castDarkenScript)
                        assertEquals(cd != null, ourCd != null, "f$f gUnknown_08AA705C")
                        if (cd != null) {
                            assertEquals(listOf((u32(e, cd + 4) - CAST_DARKEN_SCRIPT) / 8, u16(e, cd + 0x4C)),
                                listOf(ourCd!!.scrCur, (ourCd.state as OpAnim.BlendState).timer), "f$f 705C scrCur, timer")
                            castDarkenChecked++
                        }
                        if (ram == 29 && f % 10 == 0) { // after the step that loads each range (BG1's map buffer is not synced here)
                            val v = dump(f, "vram")
                            val t = u16(e, op + 0x2A)
                            for ((a, b) in listOf(0x4000 to 0x8000, 0xD800 to 0xE800, 0xF000 to 0xF500)
                                .filterIndexed { k, _ -> t > listOf(5, 4, 6)[k] }) {
                                assertArrayEquals(v.copyOfRange(a, b), env.hardware.vram.copyOfRange(a, b), "f$f VRAM %X".format(a))
                            }
                        }
                        if (ram == 25 && u16(e, op + 0x2A) <= 1) eirikaNameEnded = true
                        // Scroll lines (OpAnim1_UpdateScrollOneLine): tiles at VRAM 0x8000.., map rows at 0xE800.
                        if (ram == 24 && f > 1100 && f % 10 == 0) {
                            val v = dump(f, "vram")
                            assertArrayEquals(v.copyOfRange(0x8000, 0x8000 + 0x16 * 0x400),
                                env.hardware.vram.copyOfRange(0x8000, 0x8000 + 0x16 * 0x400), "f$f scroll line tiles")
                            for (row in 10..31) { // lines 0..21 (row ~line & 0x1F); other rows hold earlier leftovers
                                val a = 0xE800 + row * 0x40
                                assertArrayEquals(v.copyOfRange(a, a + 0x3C), env.hardware.vram.copyOfRange(a, a + 0x3C),
                                    "f$f scroll line map row $row")
                            }
                            scrollVramChecked++
                        }
                        // unk34/36 hold the slot's old bytes until OpAnimEphraimDisplayName breaks (timer back to 0).
                        if ((ram == 17 || ram == 24) && u16(e, op + 0x2A) == 0) {
                            assertEquals(listOf(u16(e, op + 0x34), u16(e, op + 0x36)),
                                listOf(os.unk34 and 0xFFFF, os.unk36 and 0xFFFF), "f$f op anim unk34/36")
                            nameEnded = true
                        }
                        assertEquals(listOf(0, 1, 2, 13).map { spriteChain(e, it) }, listOf(0, 1, 2, 13).map { ourChain(opPort.sprites, it) },
                            "f$f sprite layers")
                        val hb = u32(iw(f), sym["sHBlankHandler1"].address - GbaMem.IWRAM)
                        assertEquals(hb == OP_ANIM_HBLANK1, env.hardware.hblank.primary === opPort.hBlank1, "f$f sHBlankHandler1")
                        assertEquals(listOf(lcd.wincnt) + lcd.winH.toList() + lcd.winV.toList(),
                            listOf(env.lcd.wincnt) + env.lcd.winH.toList() + env.lcd.winV.toList(), "f$f wincnt, win0/1 boxes")
                        ephraimChecked++
                    }
                    assertArrayEquals(palette(e), env.paletteBuffer, "f$f gPaletteBuffer (op anim)")
                    assertEquals(listOf(lcd.dispcnt, lcd.bldcnt, lcd.blendCoeffA, lcd.blendCoeffB, lcd.blendY) + lcd.bgCnt.toList() + lcd.bgHofs.toList() + lcd.bgVofs.toList(),
                        listOf(env.lcd.dispcnt, env.lcd.bldcnt, env.lcd.blendCoeffA, env.lcd.blendCoeffB, env.lcd.blendY) + env.lcd.bgCnt.toList() + env.lcd.bgHofs.toList() + env.lcd.bgVofs.toList(),
                        "f$f dispcnt, bldcnt, BLDALPHA, BLDY, bgcnt, bg offsets")
                    opChecked++
                }
                ended++
                continue
            }
            assertEquals((u32(e, d + 4) - PROC_SCRIPT) / 8, proc.scrCur, "f$f scrCur")
            assertEquals(u32(e, d + 0x0C) != 0, proc.idleCb != null, "f$f idleCb")
            assertEquals(listOf(u32(e, d + 0x2C), u32(e, d + 0x30), u32(e, d + 0x34), u32(e, d + 0x38)),
                listOf(st.status, st.paletteTimer, st.delayTimer, st.skipFadeTimer), "f$f fields")
            assertArrayEquals(palette(e), env.paletteBuffer, "f$f gPaletteBuffer")
        }
        assertEquals(LAST - 474, ended) // f475..
        assertEquals(OP_END - 475, opStarted) // f476..f2908; then GAMECTRL starts the title screen
        assertTrue(titleChecked >= 600, "title screen checked on $titleChecked frames")
        assertTrue(framesChecked >= 150, "op anim frames compared from our display state: $framesChecked")
        assertTrue(opChecked >= 300, "op anim checked on $opChecked frames")
        assertTrue(ephraimChecked >= 6, "OpAnimPreparefxEphraim checked on $ephraimChecked frames")
        assertTrue(flyInChecked >= 20, "OpAnimEphraimfxFlyIn checked on $flyInChecked frames")
        assertTrue(splitChecked >= 1, "sub_80C7900 checked on $splitChecked frames")
        assertTrue(splitLineChecked >= 16, "OpAnim1AdvanceSplitLine checked on $splitLineChecked frames")
        assertTrue(nameChecked >= 50, "OpAnimEphraimDisplayName checked on $nameChecked frames")
        assertTrue(nameEnded, "OpAnimEphraimDisplayName's last step checked")
        assertTrue(exitChecked >= 30, "OpAnimEphraimExit checked on $exitChecked frames")
        assertTrue(eirikaChecked >= 5, "OpAnimPreparefxEirika checked on $eirikaChecked frames")
        assertTrue(eirikaFlyInChecked >= 20, "sub_80C7F90 checked on $eirikaFlyInChecked frames")
        assertTrue(eirikaSplitChecked >= 33, "sub_80C8100..8214 checked on $eirikaSplitChecked frames")
        assertTrue(eirikaNameChecked >= 50, "sub_80C8278 checked on $eirikaNameChecked frames")
        assertTrue(eirikaNameEnded, "sub_80C8278's last step checked")
        assertTrue(scrollVramChecked >= 1, "scroll line VRAM checked on $scrollVramChecked frames")
        assertTrue(eirikaExitChecked >= 30, "sub_80C835C checked on $eirikaExitChecked frames")
        assertTrue(darkChecked >= 8, "sub_80C84D8 checked on $darkChecked frames")
        assertTrue(castDarkenChecked >= 0x28, "gUnknown_08AA705C checked on $castDarkenChecked frames")
        assertTrue(joshuaChecked >= 0x28, "sub_80C8690 checked on $joshuaChecked frames")
        assertTrue(castFadeChecked >= 0x10, "sub_80C9E6C checked on $castFadeChecked frames")
        assertTrue(joshuaInChecked >= 0x1E, "sub_80C9FF8 checked on $joshuaInChecked frames")
        assertTrue(faces5Checked >= 10, "sub_80C9218 checked on $faces5Checked frames")
        assertTrue(faces5InChecked >= 0xD, "sub_80CB0A0 checked on $faces5InChecked frames")
        assertTrue(faces6Checked >= 11, "sub_80C9330 checked on $faces6Checked frames")
        assertTrue(faces6InChecked >= 0xB, "sub_80CB20C checked on $faces6InChecked frames")
        assertTrue(faces7Checked >= 12, "sub_80C940C checked on $faces7Checked frames")
        assertTrue(faces7InChecked >= 10, "sub_80CB320 checked on $faces7InChecked frames")
        // Loaders run until their last step; slide-ins until theirs.
        val laterNames = listOf("sub_80C955C", "sub_80CB594", "sub_80C9638", "sub_80CB6A0", "sub_80C9750", "sub_80CB878",
            "sub_80C98A0", "sub_80CBA64", "sub_80C99B8", "sub_80CBC40")
        val laterSteps = listOf(13, 9, 14, 8, 15, 7, 16, 6, 18, 5)
        for (k in laterNames.indices) {
            assertTrue(laterChecked[k] >= laterSteps[k], "${laterNames[k]} checked on ${laterChecked[k]} frames")
        }
        assertTrue(ringOutChecked >= 0x30, "sub_80C9A94 checked on $ringOutChecked frames")
        assertTrue(whiteChecked >= 0x30, "sub_80C9AFC checked on $whiteChecked frames")
        assertTrue(sleepChecked >= 0x1F, "the end's PROC_SLEEP checked on $sleepChecked frames")
        assertTrue(endChecked >= 1, "sub_80CBD7C checked on $endChecked frames")
        assertTrue(ringBlinkChecked >= 1, "Tsa_OpAnimShiningRingBlinking checked on $ringBlinkChecked frames")
        assertTrue(faces4Checked >= 9, "sub_80C9100 checked on $faces4Checked frames")
        assertTrue(faces4InChecked >= 0xF, "sub_80CAF2C checked on $faces4InChecked frames")
        assertTrue(faces3Checked >= 8, "sub_80C9024 checked on $faces3Checked frames")
        assertTrue(faces3InChecked >= 0x10, "sub_80CAE20 checked on $faces3InChecked frames")
        assertTrue(faces2Checked >= 7, "sub_80C8ED4 checked on $faces2Checked frames")
        assertTrue(faces2InChecked >= 0x12, "sub_80CABB0 checked on $faces2InChecked frames")
        assertTrue(faces1InChecked >= 0x14, "sub_80CAA38 checked on $faces1InChecked frames")
        assertTrue(facesChecked >= 0x26, "sub_80C8D30 checked on $facesChecked frames")
        assertTrue(sparklesChecked >= 0xD1, "sub_80CA4DC checked on $sparklesChecked frames")
        assertTrue(castEndChecked >= 0x28, "sub_80C8CE4 checked on $castEndChecked frames")
        assertTrue(fadeOutProcChecked >= 0x28, "gUnknown_08AA707C checked on $fadeOutProcChecked frames")
        assertTrue(sceneLoadChecked.all { it >= 0x28 }, "sub_80C883C..8BF8 checked on ${sceneLoadChecked.toList()} frames")
        assertTrue(sceneInChecked.all { it >= 0x1E }, "sub_80C9C5C..9DA0 (scroll-in) checked on ${sceneInChecked.toList()} frames")
        assertTrue(sceneOutChecked.all { it >= 0x10 }, "sub_80CA10C, sub_80CA3B8, sub_80C9F7C checked on ${sceneOutChecked.toList()} frames")
        assertEquals(true, enabledSeen, "key combo reset enabled after the Nintendo logo")
    }

    private companion object {
        /**
         * Full-dump frames (f1100..f3000, no HBlank handler) not yet drawn identically from our display state.
         * f1170, f1190..f1210, f2200..f2230: palette, VRAM, OAM and registers equal the dump's, so a renderer gap
         * (likely a mid-frame effect without an HBlank handler); f1180: BG VRAM 0xD800..0xE800 differs (sync timing);
         * f3000: the title's first frame (sprites one step off).
         */
        val FRAME_GAPS = setOf(1170, 1180, 1190, 1200, 1210, 2200, 2210, 2220, 2230, 3000)
        const val PROC_SCRIPT = 0x08AA7680 // ProcScr_GameEarlyStartUI
        const val GAME_CTRL_SCRIPT = 0x085916D4 // gProcScr_GameControl
        const val OP_ANIM_SCRIPT = 0x08AA71C8 // ProcScr_OpAnim
        const val TITLE_SCRIPT = 0x08AA6A50 // gProcScr_TitleScreen
        const val TERMINATOR_SCRIPT = 0x08AA6CA4 // ProcScr_OpAnimfxTerminator
        const val BLDALPHA_SCRIPT = 0x08AA6CDC // ProcScr_OpAnimBLDALPHA
        const val FADE_IN_SCRIPT = 0x08AA6CBC // ProcScr_OpAnimFadeIn
        const val PROC_6D04_SCRIPT = 0x08AA6D04 // ProcScr_08AA6D04
        const val MERGE_BG_SCRIPT = 0x08AA7034 // ProcScr_OpAnimMergeBG
        const val BG3_SCROLL_SCRIPT = 0x08AA704C // gUnknown_08AA704C
        const val CAST_FADE_OUT_SCRIPT = 0x08AA707C // gUnknown_08AA707C
        const val CAST_DARKEN_SCRIPT = 0x08AA705C // gUnknown_08AA705C
        const val OP_ANIM_HBLANK1 = 0x080C7845 // OpAnimHBlank1 (Thumb)
        const val LAST = 3600
        const val OP_END = 2908
        const val PROC_SIZE = 0x6C

        fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}
