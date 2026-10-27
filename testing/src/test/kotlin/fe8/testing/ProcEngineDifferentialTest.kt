package fe8.testing

import fe8.game.opanim.EarlyStartUi
import fe8.game.opanim.OpAnimHs
import fe8.game.GameControl
import fe8.game.opanim.OpAnim
import gbafe.core.VerifiesPort
import gbafe.core.time.GameClock
import gbafe.input.KeyComboReset
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.render.LcdControl
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

    private fun palette(ew: ByteArray) = ShortArray(512) { u16(ew, sym["gPaletteBuffer"].address - GbaMem.EWRAM + 2 * it).toShort() }

    private fun lcdDispcnt(iw: ByteArray) = LcdControl().apply { readFrom(iw, sym["gLCDControlBuffer"].address - GbaMem.IWRAM) }.dispcnt

    @Test
    @VerifiesPort("Proc_Start", "Proc_Run", "Proc_Break", "Proc_Goto", "Proc_End", "Proc_Find", "EnableKeyComboResetEN", "CheckCanKeyComboReset", "IncrementGameClock", "GetGameClock", "GameControl_EnableSoundEffects", "Proc_StartBlocking",
        "SetNextGameActionId", "CopyToPalOpAnim",
        "OpAnimBldAlphaUpdateBgPalette", "OpAnimBldAlphaExists", "OpAnimBldAlphaDelay", "OpAnimBldAlphaMain",
        "OpAnimUpdateScreen1", "sub_80C6F70", "sub_80C69B0", "NewProcOpAnimFadeIn", "OpAnimAllBlack", "OpAnimFadeInMain", "sub_80C7050")
    fun `intro proc runs frame by frame like the game`() {
        assumeTrue(ref.available && romFile.isFile, "ROM / mGBA reference data not present (run tools/setup.sh)")
        val rom = romFile.readBytes()
        val run = ref.run("ram_trace_logo_skip")
        val run2 = ref.run("ram_trace_opanim") // same inputs, f515..f1100
        fun ew(f: Int) = (if (f <= 520) run else run2).region("r%05d".format(f), "ewram")
        fun iw(f: Int) = (if (f <= 520) run else run2).region("r%05d".format(f), "iwram")
        fun romPal(at: Int, n: Int) = ShortArray(n) { u16(rom, at - 0x08000000 + 2 * it).toShort() }
        fun blob(at: Int) = OpAnimHs.Blob(rom, at - 0x08000000)
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
        val ctrl = GameControl(engine, keys, { ui.script }, { opPort.script })
        val opGfx = OpAnim.Gfx(
            listOf(0x08AF1AE8, 0x08AF1B38, 0x08AF1FD0, 0x08AF2654).map { blob(it) },
            listOf(0x08AF1B18, 0x08AF1FB0, 0x08AF2634, 0x08AF310C).map { romPal(it, 16) },
        )
        val worldMap = OpAnim.WorldMapGfx(blob(0x08AB21D8), blob(0x08AB5D90), blob(0x08AB632C), blob(0x08AB7AC0), romPal(0x08AB7DB0, 16), romPal(0x08AB630C, 16))
        opPort = OpAnim(engine, env.hardware, keys, ctrl, opGfx, worldMap)
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
        var enabledSeen = false
        for (f in 401..LAST) {
            val e = ew(f)
            keys.readFrom(e, sym["sKeyStatusBuffer"].address - GbaMem.EWRAM)
            clock.increment()
            // SetupOpAnimWorldMapfx (two large decompressions) overruns: while the previous dump shows the op anim
            // inside that call (scrCur 4), the game is still finishing that step and starts none.
            val prevOp = procAt(ew(f - 1), OP_ANIM_SCRIPT)
            val lagging = prevOp != null && (u32(ew(f - 1), prevOp + 4) - OP_ANIM_SCRIPT) / 8 == 4
            if (!lagging) engine.run(engine.root(rootIndex))
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
                val ram = op?.let { (u32(e, it + 4) - OP_ANIM_SCRIPT) / 8 }
                if (ram != null && ram != 4 && ram <= 10) {
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
                    if (ram >= 9) assertEquals(listOf(0x38, 0x3A, 0x3C, 0x3E, 0x46).map { u16(e, op + it) },
                        (ours.state as OpAnim.State).unk38to46.toList(), "f$f op anim unk38..46")
                    if (ram >= 7) assertEquals(listOf(u16(e, op + 0x2C), u16(e, op + 0x2E)),
                        listOf((ours.state as OpAnim.State).unk2C and 0xFFFF, (ours.state as OpAnim.State).unk2E and 0xFFFF), "f$f op anim unk2C/2E")
                    if (bld != null) {
                        assertEquals(listOf((u32(e, bld + 4) - BLDALPHA_SCRIPT) / 8, u16(e, bld + 0x2A)),
                            listOf(ourBld!!.scrCur, (ourBld.state as OpAnim.BldAlphaState).timer), "f$f BLDALPHA scrCur, timer")
                    }
                    assertEquals(u16(e, sym["gOpAnimSt"].address - GbaMem.EWRAM + 0x0C), opPort.pal, "f$f gOpAnimSt.pal")
                    assertArrayEquals(palette(e), env.paletteBuffer, "f$f gPaletteBuffer (op anim)")
                    val lcd = LcdControl().apply { readFrom(iw(f), sym["gLCDControlBuffer"].address - GbaMem.IWRAM) }
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
        assertEquals(LAST - 475, opStarted) // f476..
        assertTrue(opChecked >= 300, "op anim checked on $opChecked frames")
        assertEquals(true, enabledSeen, "key combo reset enabled after the Nintendo logo")
    }

    private companion object {
        const val PROC_SCRIPT = 0x08AA7680 // ProcScr_GameEarlyStartUI
        const val GAME_CTRL_SCRIPT = 0x085916D4 // gProcScr_GameControl
        const val OP_ANIM_SCRIPT = 0x08AA71C8 // ProcScr_OpAnim
        const val TERMINATOR_SCRIPT = 0x08AA6CA4 // ProcScr_OpAnimfxTerminator
        const val BLDALPHA_SCRIPT = 0x08AA6CDC // ProcScr_OpAnimBLDALPHA
        const val FADE_IN_SCRIPT = 0x08AA6CBC // ProcScr_OpAnimFadeIn
        const val LAST = 1100
        const val PROC_SIZE = 0x6C

        fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}
