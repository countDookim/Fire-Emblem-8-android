package fe8.testing

import fe8.game.GameControl
import fe8.game.opanim.OpAnimHs
import fe8.game.title.TitleScreen
import gbafe.core.VerifiesPort
import gbafe.data.Decompress
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.render.BgHardware
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The title screen's start after the op anim (`StartTitleScreen_FlagTrue`, mode 1) against
 * `ram_trace_opanim4` (RAM every frame to f3000) and `ram_trace_title1` (to f3600): state from
 * the frame before the title proc appears, then frame by frame through the intro fx to the idle
 * title.
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
    @VerifiesPort("StartTitleScreen_FlagTrue", "Title_Init", "Title_SetupMainGraphics", "Title_SetupSpecialEffectGraphics",
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
            assertEquals(procAt(e, SKIP_SCRIPT) != null, engine.find(title.skipListenerScript) != null, "f$f skip listener")
            assertEquals(procAt(e, SPRITES_SCRIPT) != null, engine.find(title.drawSpritesScript) != null, "f$f title sprites")
            assertEquals(u32(e, o + 0x2C), (ours.state as TitleScreen.State).timerIdle, "f$f timer_idle")
            if (f % 10 == 0) { // full dump: VRAM written by the loaders so far (exact decompressed lengths)
                val v = run(f).region("f%05d".format(f), "vram")
                for ((at, dst) in listOf(0x08AA7760 to 0, 0x08AA9708 to 0x3000, 0x08AAB414 to 0x5000, 0x08AAC5AC to 0x10000,
                    0x08AACEDC to 0x13000, 0x08AAFD14 to 0x8000, 0x08AAFF10 to 0xB000, 0x08AADC08 to 0xC000, 0x08AB0B44 to 0x8000, 0x08AB0134 to 0x8000)) {
                    if (dst == 0xB000 && scr > 15) continue // BG2's map from sub_80C5BD4 on (map sync not modelled)
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
        assertTrue(fxChecked >= 4, "Title_SetupSpecialEffectGraphics checked on $fxChecked frames")
    }

    private companion object {
        const val PROC_SIZE = 0x6C
        const val TITLE_SCRIPT = 0x08AA6A50
        const val SKIP_SCRIPT = 0x08AA6814
        const val SPRITES_SCRIPT = 0x08AA67FC
        const val LAST_FRAME = 3600
        const val LAST_INDEX = 37 // the fade-out stand-in (label 2)
        fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}

/** The title screen with its ROM graphics (addresses from fireemblem8.nm.txt), for the differential tests. */
internal fun titleScreenFor(
    rom: ByteArray, engine: ProcEngine, hw: BgHardware, sprites: SpriteLayers = SpriteLayers(),
    keys: KeyStatus = KeyStatus(), gameControl: GameControl? = null,
): TitleScreen {
    fun u16(o: Int) = (rom[o].toInt() and 0xFF) or ((rom[o + 1].toInt() and 0xFF) shl 8)
    fun blob(at: Int) = OpAnimHs.Blob(rom, at - 0x08000000)
    fun pal(at: Int, n: Int) = ShortArray(n) { u16(at - 0x08000000 + 2 * it).toShort() }
    return TitleScreen(engine, hw, TitleScreen.Gfx(
            listOf(blob(0x08AA7760), blob(0x08AA9708), blob(0x08AAAE90)), pal(0x08AAB3F4, 16),
            blob(0x08AAB414) to blob(0x08AAC2A4), pal(0x08AAC58C, 16),
            blob(0x08AAC5AC), blob(0x08AACEDC), pal(0x08AADB68, 64),
            listOf(0x08AAFD14, 0x08AAFF10, 0x08AADC08, 0x08AAE61C, 0x08AAE8EC, 0x08AAF928, 0x08AB1C24, 0x08AB216C).map { blob(it) },
            listOf(pal(0x08AB0114, 16), pal(0x08AAE8CC, 16), pal(0x08AAFCF4, 16), pal(0x08AB210C, 48), pal(0x08AB21B8, 16)),
            Triple(blob(0x08AB0B44), blob(0x08AB1890), pal(0x08AB1C04, 16)),
            Triple(blob(0x08AB0134), blob(0x08AB0A20), pal(0x08AB0B24, 16))),
            SpriteObject(0x08590F54, pal(0x08590F54, 4)), sprites, object8x8 = SpriteObject(0x08590F44, pal(0x08590F44, 4)),
            bannerSprite = SpriteObject(0x08AA6794, pal(0x08AA6794, 13)),
            menuSprites = listOf(SpriteObject(0x08590F4C, pal(0x08590F4C, 4)), SpriteObject(0x08AA6774, pal(0x08AA6774, 16)),
                SpriteObject(0x08AA67E0, pal(0x08AA67E0, 13)), SpriteObject(0x08AA67AE, pal(0x08AA67AE, 25))),
            glowPal = pal(0x08AADBE8, 16), keys = keys, gameControl = gameControl)
}
