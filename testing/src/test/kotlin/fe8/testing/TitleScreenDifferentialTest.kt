package fe8.testing

import fe8.game.GameControl
import fe8.game.opanim.OpAnimHs
import fe8.game.title.TitleScreen
import gba.data.Decompress
import gbafe.core.VerifiesPort
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.render.BgHardware
import gbafe.render.Camera
import gbafe.render.FrameBuffer
import gbafe.render.OamBuffer
import gbafe.render.RenderState
import gbafe.render.SoftwareRenderer
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject
import gbafe.render.TileSheets
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The title screen's start after the op anim (`StartTitleScreen_FlagTrue`, mode 1) against
 * `ram_trace_opanim4` (RAM every frame to f3000) and `ram_trace_title1` (to f3600): state from
 * the frame before the title proc appears, then frame by frame through the intro fx to the idle
 * title. Each step's sprites are pushed to OAM and the VBlank sync applied; on idle-title full dumps
 * the OAM and the whole frame drawn from our VRAM, palette, OAM and LCD registers must match mGBA.
 */
class TitleScreenDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val sym = RamSymbols.fromEnvironment()
    private val romFile = File(System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets", "baserom.gba")

    private fun procAt(ew: ByteArray, script: Int = TITLE_SCRIPT): Int? {
        val a = sym["sProcArray"]
        return (0 until a.size / PROC_SIZE).map { a.address - GbaMem.EWRAM + it * PROC_SIZE }
            .firstOrNull { u32(ew, it) == script }
    }

    private fun shorts(ew: ByteArray, name: String, n: Int) =
        ShortArray(n) { u16(ew, sym[name].address - GbaMem.EWRAM + 2 * it).toShort() }

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

    @Test
    @VerifiesPort("PutOamHi", "SyncHiOam", "ClearOam", "PushSpriteLayerObjects", "StartTitleScreen_FlagTrue", "Title_Init", "Title_SetupMainGraphics", "Title_SetupSpecialEffectGraphics",
        "sub_80C5A44", "BgAffineSetOpAnim", "Title_Loop_DrawRotatingBlueLights", "sub_80C5BD4",
        "Title_Loop_DrawRedBlueOrbs", "Title_PrepareMainScreenWithExtendedBgm", "Title_Loop_RevealGreenDragonFlashFx",
        "Title_Loop_FlashFxExpand", "Title_LoadDemonKingOnBg2", "Title_ShowBg2", "sub_80C5FB4", "Title_EndSkipFxListener",
        "Title_PrepareMainLogoZoom", "Title_Loop_MainLogoZoom", "sub_80C5548", "sub_80C69B0", "sub_80C6104",
        "Title_Loop_LightExplosionFx", "DrawTitleLightBubbleSprites", "sub_80C62DC", "DrawTitleSprites_Init",
        "DrawTitleSprites_Loop", "Title_StartSkipFxListener")
    fun `title screen start matches the per-frame RAM trace`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val run4 = ref.run("ram_trace_opanim4")
        val runT = ref.run("ram_trace_title1")
        fun run(f: Int) = if (f <= 3000) run4 else runT
        fun ew(f: Int) = run(f).region((if (f % 10 == 0) "f%05d" else "r%05d").format(f), "ewram")
        fun iw(f: Int) = run(f).region((if (f % 10 == 0) "f%05d" else "r%05d").format(f), "iwram")
        val first = (2890..3000).first { procAt(ew(it)) != null }
        val e0 = ew(first - 1)
        val hw = BgHardware()
        hw.lcd.readFrom(iw(first - 1), sym["gLCDControlBuffer"].address - GbaMem.IWRAM)
        shorts(e0, "gPaletteBuffer", 512).copyInto(hw.palette)
        for (bg in 0..3) shorts(e0, "gBG${bg}TilemapBuffer", 0x400).copyInto(hw.mapBuffer(bg))
        val engine = ProcEngine()
        val title = titleScreenFor(rom, engine, hw)
        val parent = engine.start(ProcScript("parent", listOf(block())), 3)
        title.start(parent, 1)
        var checked = 0
        var fxChecked = 0
        var vramChecked = 0
        var blueChecked = 0
        var orbsChecked = 0
        var zoomChecked = 0
        var explosionChecked = 0
        var idleChecked = 0
        var framesChecked = 0
        val oam = OamBuffer().apply { // gOam, sOamLo.count and gOamHiPutIt from the start dump
            val w = iw(first - 1)
            for (i in buffer.indices) buffer[i] = u16(w, G_OAM - GbaMem.IWRAM + 2 * i).toShort()
            loCount = u32(w, sym["sOamLo"].address - GbaMem.IWRAM + 12)
            hiPut = (u32(w, G_OAM_HI_PUT_IT - GbaMem.IWRAM) - G_OAM) / 2
        }
        for (f in first..LAST_FRAME) {
            val e = ew(f)
            val o = procAt(e) ?: break
            val scr = (u32(e, o + 4) - TITLE_SCRIPT) / 8
            // Decompressions overrun: a dump with gBmSt.main_loop_ended 0 is mid-step (compare nothing), and the
            // frame after one starts no new step.
            val lagging = ew(f - 1)[sym["gBmSt"].address - GbaMem.EWRAM].toInt() == 0
            val midStep = e[sym["gBmSt"].address - GbaMem.EWRAM].toInt() == 0
            if (!lagging) {
                title.sprites.clear() // OnMain's ClearSprites
                engine.run(engine.root(3))
                // OnMain pushes layers 0 and 13; the VBlank syncs the step's display state.
                for (head in listOf(0, 13)) title.sprites.pushLayerObjects(head) { oam.putHi(it.oam1, it.oam0, it.obj!!.data, it.oam2) }
                hw.flushBackgrounds()
                oam.syncLo()
                oam.syncHi()
            }
            if (midStep) continue
            if (scr >= LAST_INDEX) break // the fade-out stand-in
            val ours = engine.find(title.script)!!
            assertEquals(scr, ours.scrCur, "f$f title scrCur")
            if (scr == 13) fxChecked++
            assertEquals(u16(e, o + 0x2A), (ours.state as TitleScreen.State).timer, "f$f timer")
            assertArrayEquals(shorts(e, "gPaletteBuffer", 512), hw.palette, "f$f gPaletteBuffer")
            for (bg in 0..2) assertArrayEquals(shorts(e, "gBG${bg}TilemapBuffer", 0x400), hw.mapBuffer(bg), "f$f BG$bg map")
            val lcd = hw.lcd
            val g = BgHardware().lcd.apply { readFrom(iw(f), sym["gLCDControlBuffer"].address - GbaMem.IWRAM) }
            assertEquals(listOf(g.dispcnt, g.bldcnt, g.blendCoeffA, g.blendCoeffB, g.blendY) + g.bgCnt.toList() + g.affine.toList().subList(0, 6),
                listOf(lcd.dispcnt, lcd.bldcnt, lcd.blendCoeffA, lcd.blendCoeffB, lcd.blendY) + lcd.bgCnt.toList() + lcd.affine.toList().subList(0, 6),
                "f$f dispcnt, bldcnt, BLDALPHA, BLDY, bgcnt, BG2 affine")
            for (layer in 0..4) assertEquals(spriteChain(e, layer), ourChain(title.sprites, layer), "f$f sprite layer $layer")
            if (scr == 15) blueChecked++
            if (scr == 17) orbsChecked++
            if (scr == 30) zoomChecked++
            if (scr == 32) explosionChecked++
            if (scr == 35) idleChecked++
            if (scr == 35 && (f + 1) % 10 == 0 && f + 1 <= LAST_FRAME) { // frame f+1 shows the state synced after step f
                val dump = "f%05d".format(f + 1)
                val dumpOam = run(f + 1).region(dump, "oam")
                assertArrayEquals(ShortArray(0x200) { u16(dumpOam, 2 * it).toShort() }, oam.oam, "$dump OAM")
                assertEquals(0, ourFrameDiffs(hw, oam, run(f + 1), dump), "$dump: idle title frame from our state")
                framesChecked++
            }
            assertEquals(procAt(e, SKIP_SCRIPT) != null, engine.find(title.skipListenerScript) != null, "f$f skip listener")
            assertEquals(procAt(e, SPRITES_SCRIPT) != null, engine.find(title.drawSpritesScript) != null, "f$f title sprites")
            assertEquals(u32(e, o + 0x2C), (ours.state as TitleScreen.State).timerIdle, "f$f timer_idle")
            if (f % 10 == 0) { // full dump: VRAM written by the loaders so far (exact decompressed lengths)
                val v = run(f).region("f%05d".format(f), "vram")
                for ((at, dst) in listOf(0x08AA7760 to 0, 0x08AA9708 to 0x3000, 0x08AAB414 to 0x5000, 0x08AAC5AC to 0x10000,
                    0x08AACEDC to 0x13000, 0x08AAFD14 to 0x8000, 0x08AAFF10 to 0xB000, 0x08AADC08 to 0xC000, 0x08AB0B44 to 0x8000, 0x08AB0134 to 0x8000)) {
                    if (at == 0x08AB0B44 && scr !in 23..29) continue // demon king, until the logo replaces it
                    if (at == 0x08AB0134 && scr < 30) continue // the zooming logo
                    val n = Decompress.into(rom, at - 0x08000000, ByteArray(0x10000), 0)
                    if (hw.vram.copyOfRange(dst, dst + n).any { it != 0.toByte() }) {
                        assertArrayEquals(v.copyOfRange(dst, dst + n), hw.vram.copyOfRange(dst, dst + n), "f$f VRAM %X".format(dst))
                        vramChecked++
                    }
                }
            }
            checked++
        }
        assertTrue(checked >= 5, "checked $checked frames from f$first")
        assertTrue(blueChecked >= 70, "Title_Loop_DrawRotatingBlueLights checked on $blueChecked frames")
        assertTrue(vramChecked >= 5, "VRAM ranges checked: $vramChecked")
        assertTrue(orbsChecked >= 40, "Title_Loop_DrawRedBlueOrbs checked on $orbsChecked frames")
        assertTrue(zoomChecked >= 12, "Title_Loop_MainLogoZoom checked on $zoomChecked frames")
        assertTrue(explosionChecked >= 20, "Title_Loop_LightExplosionFx checked on $explosionChecked frames")
        assertTrue(idleChecked >= 300, "Title_IDLE checked on $idleChecked frames")
        assertTrue(framesChecked >= 20, "idle title frames compared: $framesChecked")
        assertTrue(fxChecked >= 4, "Title_SetupSpecialEffectGraphics checked on $fxChecked frames")
    }

    @Test
    @VerifiesPort("TitleScreenTryJumpIntroAnim", "sub_80C5870",
        "StartFadeCore", "FadeCore_Init", "FadeCore_Tick", "FadeCore_Loop", "ColorFadeSetupFromBlack", "ColorFadeTick")
    fun `title screen in mode 0 fades in like the per-frame RAM trace`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present (run tools/setup.sh)")
        val run = ref.run("ram_trace_title_fade")
        fun ew(f: Int) = run.region("r%05d".format(f), "ewram")
        fun iw(f: Int) = run.region("r%05d".format(f), "iwram")
        // No dumps f700..f702 (START held): the title proc already runs at f703. Start ours from the first
        // settled dump's display state and step it (re-applying idempotent setup steps) to its script
        // position and timer.
        fun done(f: Int) = ew(f)[sym["gBmSt"].address - GbaMem.EWRAM].toInt() != 0 // step finished in the dump
        val first = (705..800).first { done(it - 1) && done(it - 2) }
        val e0 = ew(first - 1)
        val hw = BgHardware()
        hw.lcd.readFrom(iw(first - 1), sym["gLCDControlBuffer"].address - GbaMem.IWRAM)
        shorts(e0, "gPaletteBuffer", 512).copyInto(hw.palette)
        for (bg in 0..3) shorts(e0, "gBG${bg}TilemapBuffer", 0x400).copyInto(hw.mapBuffer(bg))
        val engine = ProcEngine()
        val title = titleScreenFor(romFile.readBytes(), engine, hw)
        val parent = engine.start(ProcScript("parent", listOf(block())), 3)
        title.start(parent, 0)
        val o0 = procAt(e0)!!
        fun at(p: gbafe.proc.Proc) = p.scrCur == (u32(e0, o0 + 4) - TITLE_SCRIPT) / 8 &&
            (p.state as TitleScreen.State).timer == u16(e0, o0 + 0x2A)
        repeat(10) { if (!at(engine.find(title.script)!!)) engine.run(engine.root(3)) }
        assertTrue(at(engine.find(title.script)!!), "title proc synced to f${first - 1}")
        var fadeChecked = 0
        var idleChecked = 0
        for (f in first..800) {
            val e = ew(f)
            val o = procAt(e) ?: break
            val lagging = ew(f - 1)[sym["gBmSt"].address - GbaMem.EWRAM].toInt() == 0
            if (!lagging) {
                title.sprites.clear()
                engine.run(engine.root(3))
            }
            if (e[sym["gBmSt"].address - GbaMem.EWRAM].toInt() == 0) continue
            val scr = (u32(e, o + 4) - TITLE_SCRIPT) / 8
            val ours = engine.find(title.script)!!
            assertEquals(scr, ours.scrCur, "f$f title scrCur")
            assertEquals(u16(e, o + 0x2A), (ours.state as TitleScreen.State).timer, "f$f timer")
            assertArrayEquals(shorts(e, "gPaletteBuffer", 512), hw.palette, "f$f gPaletteBuffer")
            val fade = procAt(e, FADE_SCRIPT)
            assertEquals(fade != null, engine.find(title.fadeCore.script) != null, "f$f fade proc")
            val fc = sym["gFadeComponents"].address - GbaMem.EWRAM
            assertArrayEquals(e.copyOfRange(fc, fc + 0x600), title.fadeCore.colorFade.components, "f$f gFadeComponents")
            val fs = sym["gFadeComponentStep"].address - GbaMem.EWRAM
            assertArrayEquals(e.copyOfRange(fs, fs + 0x20), title.fadeCore.colorFade.step, "f$f gFadeComponentStep")
            val g = BgHardware().lcd.apply { readFrom(iw(f), sym["gLCDControlBuffer"].address - GbaMem.IWRAM) }
            assertEquals(listOf(g.dispcnt, g.bldcnt, g.blendCoeffA, g.blendCoeffB, g.blendY),
                listOf(hw.lcd.dispcnt, hw.lcd.bldcnt, hw.lcd.blendCoeffA, hw.lcd.blendCoeffB, hw.lcd.blendY), "f$f lcd")
            for (layer in 0..2) assertEquals(spriteChain(e, layer), ourChain(title.sprites, layer), "f$f sprite layer $layer")
            if (fade != null) fadeChecked++
            if (scr == 35) idleChecked++
        }
        assertTrue(fadeChecked >= 14, "fade checked on $fadeChecked frames")
        assertTrue(idleChecked >= 20, "idle checked on $idleChecked frames")
    }

    @Test
    @VerifiesPort("SkipTitleFx_Loop_KeyListener", "Title_RestartProc")
    fun `A on the title intro fx restarts the title like the per-frame RAM trace`() {
        assumeTrue(ref.available && romFile.isFile && File(ref.root, "ram_trace_title_skip/manifest.txt").isFile,
            "ROM / mGBA reference run ram_trace_title_skip not present (run tools/setup.sh)")
        val run4 = ref.run("ram_trace_opanim4")
        val runT = ref.run("ram_trace_title1")
        val runS = ref.run("ram_trace_title_skip") // same input as runT until A at f3040
        fun ew(f: Int) = when {
            f >= 3030 -> runS.region("r%05d".format(f), "ewram")
            else -> (if (f <= 3000) run4 else runT).region((if (f % 10 == 0) "f%05d" else "r%05d").format(f), "ewram")
        }
        fun iw(f: Int) = when {
            f >= 3030 -> runS.region("r%05d".format(f), "iwram")
            else -> (if (f <= 3000) run4 else runT).region((if (f % 10 == 0) "f%05d" else "r%05d").format(f), "iwram")
        }
        val keyAddr = sym["sKeyStatusBuffer"].address - GbaMem.EWRAM
        val first = (2890..3000).first { procAt(ew(it)) != null }
        val e0 = ew(first - 1)
        val hw = BgHardware()
        hw.lcd.readFrom(iw(first - 1), sym["gLCDControlBuffer"].address - GbaMem.IWRAM)
        shorts(e0, "gPaletteBuffer", 512).copyInto(hw.palette)
        for (bg in 0..3) shorts(e0, "gBG${bg}TilemapBuffer", 0x400).copyInto(hw.mapBuffer(bg))
        val engine = ProcEngine()
        val keys = KeyStatus()
        val title = titleScreenFor(romFile.readBytes(), engine, hw, keys = keys)
        val parent = engine.start(ProcScript("parent", listOf(block())), 3)
        title.start(parent, 1)
        var checked = 0
        var restarted = 0
        val bmSt = sym["gBmSt"].address - GbaMem.EWRAM
        for (f in first..3200) {
            val e = ew(f)
            val o = procAt(e) ?: break
            val scr = (u32(e, o + 4) - TITLE_SCRIPT) / 8
            val lagging = ew(f - 1)[bmSt].toInt() == 0
            val midStep = e[bmSt].toInt() == 0
            if (!lagging) {
                keys.readFrom(e, keyAddr)
                title.sprites.clear()
                engine.run(engine.root(3))
            }
            if (midStep || f < 3030) continue
            val ours = engine.find(title.script)!!
            assertEquals(scr, ours.scrCur, "f$f title scrCur")
            assertEquals(u16(e, o + 0x2A), (ours.state as TitleScreen.State).timer, "f$f timer")
            assertEquals(e[o + 0x29].toInt(), (ours.state as TitleScreen.State).mode, "f$f mode")
            assertArrayEquals(shorts(e, "gPaletteBuffer", 512), hw.palette, "f$f gPaletteBuffer")
            val g = BgHardware().lcd.apply { readFrom(iw(f), sym["gLCDControlBuffer"].address - GbaMem.IWRAM) }
            assertEquals(listOf(g.dispcnt, g.bldcnt, g.blendCoeffA, g.blendCoeffB, g.blendY),
                listOf(hw.lcd.dispcnt, hw.lcd.bldcnt, hw.lcd.blendCoeffA, hw.lcd.blendCoeffB, hw.lcd.blendY), "f$f lcd")
            assertEquals(procAt(e, SKIP_SCRIPT) != null, engine.find(title.skipListenerScript) != null, "f$f skip listener")
            assertEquals(procAt(e, SPRITES_SCRIPT) != null, engine.find(title.drawSpritesScript) != null, "f$f title sprites")
            assertEquals(procAt(e, FADE_SCRIPT) != null, engine.find(title.fadeCore.script) != null, "f$f fade proc")
            if (f > 3040 && scr < 36) restarted++
            checked++
        }
        assertTrue(checked >= 100 && restarted >= 50, "checked $checked frames, $restarted after the restart")
    }

    @Test
    @VerifiesPort("Title_IDLE", "sub_8013F40", "ColorFadeSetupFromColorToBlack")
    fun `A on the idle title starts the fade to dark like the per-frame RAM trace`() {
        val c = titleKeyPath("key_status_title", "k", 901)
        assertTrue(c.idle >= 40 && c.fade >= 5, "checked $c")
    }

    @Test
    @VerifiesPort("sub_80141B0", "Title_EndAllProcChildren")
    fun `START on the idle title fades to dark and ends the title like the per-frame RAM trace`() {
        val c = titleKeyPath("ram_trace_title_start", "t", 900)
        assertTrue(c.fade >= 14 && c.ended == 1, "checked $c")
    }

    private data class KeyPathChecks(val idle: Int, val fade: Int, val ended: Int)

    /**
     * Syncs our idle title to dump [first] of [runName] (per-frame RAM dumps `<prefix><frame>`), then steps it
     * with the dumped key status of each frame, comparing the title, sprite and fade procs, the palette, the
     * fade components and the LCD blend/display registers.
     */
    private fun titleKeyPath(runName: String, prefix: String, first: Int): KeyPathChecks {
        assumeTrue(ref.available && romFile.isFile && File(ref.root, "$runName/manifest.txt").isFile,
            "ROM / mGBA reference run $runName not present (run tools/setup.sh)")
        val run = ref.run(runName)
        fun ew(f: Int) = run.region("$prefix%05d".format(f), "ewram")
        fun iw(f: Int) = run.region("$prefix%05d".format(f), "iwram")
        val keyAddr = sym["sKeyStatusBuffer"].address - GbaMem.EWRAM
        fun keyStruct(f: Int) = ew(f).copyOfRange(keyAddr, keyAddr + KeyStatus.STRUCT_SIZE)
        val e0 = ew(first)
        val o0 = procAt(e0)!!
        val hw = BgHardware()
        val keys = KeyStatus()
        val engine = ProcEngine()
        val title = titleScreenFor(romFile.readBytes(), engine, hw, keys = keys)
        val parent = engine.start(ProcScript("parent", listOf(block())), 3)
        title.start(parent, 0)
        val idleScr = (u32(e0, o0 + 4) - TITLE_SCRIPT) / 8
        repeat(200) { if (engine.find(title.script)!!.scrCur != idleScr) engine.run(engine.root(3)) }
        val ours = engine.find(title.script)!!
        assertEquals(idleScr, ours.scrCur, "title proc synced to the idle loop")
        // Sync the display and the proc fields to the first dump.
        val lcdAddr = sym["gLCDControlBuffer"].address - GbaMem.IWRAM
        hw.lcd.readFrom(iw(first), lcdAddr)
        shorts(e0, "gPaletteBuffer", 512).copyInto(hw.palette)
        (ours.state as TitleScreen.State).apply {
            timer = u16(e0, o0 + 0x2A)
            timerIdle = u32(e0, o0 + 0x2C)
        }
        val so = procAt(e0, SPRITES_SCRIPT)!!
        (engine.find(title.drawSpritesScript)!!.state as TitleScreen.SpritesState).apply {
            unk4c = u16(e0, so + 0x4C).toShort().toInt()
            unk52 = u16(e0, so + 0x52)
        }
        var idle = 0
        var fade = 0
        var ended = 0
        val last = run.dumps.values.max()
        for (f in first + 1..last) {
            // A game frame that overran its VBlank is dumped mid-frame and skips the next key update:
            // compare it only once it has finished (the next dump).
            if (f < last && keyStruct(f + 1).contentEquals(keyStruct(f))) continue
            keys.readFrom(keyStruct(f))
            title.sprites.clear()
            engine.run(engine.root(3))
            val e = ew(f)
            val o = procAt(e)
            val st = engine.find(title.script)
            assertEquals(o != null, st != null, "f$f title proc")
            if (o != null && st != null) {
                assertEquals((u32(e, o + 4) - TITLE_SCRIPT) / 8, st.scrCur, "f$f title scrCur")
                assertEquals(u16(e, o + 0x2A), (st.state as TitleScreen.State).timer, "f$f timer")
                assertEquals(u32(e, o + 0x2C), (st.state as TitleScreen.State).timerIdle, "f$f timer_idle")
            }
            assertEquals(procAt(e, SPRITES_SCRIPT) != null, engine.find(title.drawSpritesScript) != null,
                "f$f sprites proc")
            assertArrayEquals(shorts(e, "gPaletteBuffer", 512), hw.palette, "f$f gPaletteBuffer")
            val g = BgHardware().lcd.apply { readFrom(iw(f), lcdAddr) }
            assertEquals(listOf(g.dispcnt, g.bldcnt, g.blendCoeffA, g.blendCoeffB, g.blendY),
                listOf(hw.lcd.dispcnt, hw.lcd.bldcnt, hw.lcd.blendCoeffA, hw.lcd.blendCoeffB, hw.lcd.blendY),
                "f$f lcd")
            val fadeProc = procAt(e, FADE_SCRIPT)
            assertEquals(fadeProc != null, engine.find(title.fadeCore.script) != null, "f$f fade proc")
            if (fadeProc != null) {
                val fc = sym["gFadeComponents"].address - GbaMem.EWRAM
                assertArrayEquals(e.copyOfRange(fc, fc + 0x600), title.fadeCore.colorFade.components,
                    "f$f gFadeComponents")
                val fs = sym["gFadeComponentStep"].address - GbaMem.EWRAM
                assertArrayEquals(e.copyOfRange(fs, fs + 0x20), title.fadeCore.colorFade.step,
                    "f$f gFadeComponentStep")
                fade++
            } else if (o != null) {
                idle++
            } else {
                ended++
                break // GameControl starts the next screen here (not part of the title).
            }
        }
        return KeyPathChecks(idle, fade, ended)
    }

    /**
     * Draws the frame from our display state (VRAM, palette RAM and LCD registers after the VBlank sync) and
     * compares it with mGBA's [dump] frame.
     */
    private fun ourFrameDiffs(hw: BgHardware, oam: OamBuffer, r: ReferenceData.Run, dump: String): Int {
        val state = RenderState()
        val sheets = TileSheets()
        gbafe.render.HardwareScene.load(state, sheets, hw, oam)
        val fb = FrameBuffer()
        SoftwareRenderer(state, sheets, fb, MgbaColorMath).render(Camera())
        val out = File(System.getProperty("fe8.frameDiffDir") ?: "build/frame-diff", "title_ours_$dump.png")
        return HardwareScene.compare(fb, r.frameRgb(dump), out)
    }

    private companion object {
        const val PROC_SIZE = 0x6C
        const val G_OAM = 0x03003140 // reference/iwram_symbols.tsv (not in ram_symbols.tsv)
        const val G_OAM_HI_PUT_IT = 0x03003744
        const val TITLE_SCRIPT = 0x08AA6A50
        const val SKIP_SCRIPT = 0x08AA6814
        const val FADE_SCRIPT = 0x0859A0A4
        const val SPRITES_SCRIPT = 0x08AA67FC
        const val LAST_FRAME = 3600
        const val LAST_INDEX = 37 // the fade-out stand-in (label 2)
        fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}

/** The title screen with its ROM graphics (production [fe8.game.BootRom]), for the differential tests. */
internal fun titleScreenFor(
    rom: ByteArray, engine: ProcEngine, hw: BgHardware, sprites: SpriteLayers = SpriteLayers(),
    keys: KeyStatus = KeyStatus(), gameControl: GameControl? = null,
): TitleScreen = fe8.game.BootRom.titleScreen(fe8.game.RomView.ofRom(rom), engine, hw, sprites, keys, gameControl)
