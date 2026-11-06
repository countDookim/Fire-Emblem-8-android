package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BmSt
import fe8.game.bm.CamMove
import fe8.game.bm.Mu
import fe8.game.bm.MuHooks
import fe8.game.bm.MuProc
import fe8.game.bm.MuUnit
import fe8.game.bm.PlaySt
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The MU display half of `mu.c` against the prologue-talk walk (`ram_trace_prologue_talk`, full
 * dumps every frame f10575..f10635).
 *
 * The MU is started and walked by the verified [Mu] code (as in [MuMoveDifferentialTest]); the
 * sprite animation player (ap.c) is not ported and is a hook, so what is compared is what mu.c
 * hands to it: the position `PutMu` passes to `AP_Update`, against the walking MU's object in
 * `oam.bin` (the 32x32 object with the MU's tile and palette, which is not a map unit sprite).
 * The animation frame's object offset (x -8, y -16 from the position, read from the first
 * visible frame) is the player's, not mu.c's, so it is a constant fitted once and then held for
 * every later frame, while the position changes by up to 16 pixels per tile.
 */
class MuDisplayDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    // Not @VerifiesPort: GetNewMuConfig (only the default config slot is traced), ResetMuAnims
    // (never called in the trace), PutMuSMS (the traced MU never stands; sub_8026FF4/sub_8027DB4
    // are hooks).
    @Test
    @VerifiesPort("PutMu", "GetMuDisplayPosition")
    fun `PutMu position matches the MU object in OAM on every frame f10580 to f10633`() {
        assumeAvailable()
        val first = Walk(10579, 10621)
        first.run()
        assertEquals(41, first.compared, "frames compared")
        assertTrue(first.hiddenFrames > 0, "the walk leaves the screen")
        val second = Walk(10623, 10633)
        second.run()
        assertEquals(9, second.compared, "frames compared")
    }

    @Test
    @VerifiesPort("GetMuAnimForJid", "GetMuImg")
    fun `animation definition and sheet of the MU match the AP handle and sMu buffer at f10580`() {
        assumeAvailable()
        val walk = Walk(10579, 10580)
        val proc = checkNotNull(walk.mu.startMu(walk.unit()))
        val s = proc.state as MuProc
        val e = walk.ewram(10580)
        val handle = walk.muDump(10580)!!.u32(0x30)
        assertEquals(
            GbaMem.u32(walk.iwram(10580), GbaMem.IWRAM, handle),
            walk.mu.getMuAnimForJid(s.jid),
            "APHandle.pDefinition",
        )
        val want = ByteArray(0x2000)
        val n = RomView.ofRom(walk.rom).decompressInto(walk.mu.getMuImg(s), want, 0)
        assertTrue(n > 0, "sheet size")
        var found = false
        for (off in 0..MU_GFX_BUFFER_SIZE - n step n) {
            var same = true
            for (i in 0 until n) {
                if (e[MU_GFX_BUFFER - GbaMem.EWRAM + off + i] != want[i]) {
                    same = false
                    break
                }
            }
            if (same) found = true
        }
        assertTrue(found, "decompressed sheet is in gMUGfxBuffer")
    }

    private fun assumeAvailable() {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        assumeTrue(romFile.isFile, "ROM not present (run tools/setup.sh)")
    }

    private inner class Walk(val seed: Int, val last: Int) {
        val data = ref.run(RUN)
        val rom = romFile.readBytes()
        val engine = ProcEngine()
        val bm = BmSt()
        val playSt = PlaySt()
        val keys = KeyStatus()
        val camMove = CamMove(engine, bm)
        val hooks = Hooks()
        val mu = Mu(engine, bm, playSt, keys, camMove.script, hooks, RomView.ofRom(rom))
        var compared = 0
        var hiddenFrames = 0

        init {
            val e = ewram(seed)
            bm.decode(e, BM_ST - GbaMem.EWRAM)
            playSt.decode(e, PLAY_ST - GbaMem.EWRAM)
        }

        fun name(f: Int) = "f%05d".format(f)

        fun ewram(f: Int): ByteArray = data.region(name(f), "ewram")

        fun iwram(f: Int): ByteArray = data.region(name(f), "iwram")

        fun oam(f: Int): ByteArray = data.region(name(f), "oam")

        fun muDump(f: Int) = ProcArrayDump.ofEwram(ewram(f)).find(PROC_SCR_MU)

        fun unit(): MuUnit {
            val e = ewram(seed)
            val addr = ProcArrayDump.ofEwram(e).find(PROC_SCR_MU)!!.u32(0x2C)
            fun b(o: Int) = e[addr - GbaMem.EWRAM + o].toInt() and 0xFF
            return MuUnit(
                address = addr,
                xPos = b(0x10).toByte().toInt(),
                yPos = b(0x11).toByte().toInt(),
                classId = (GbaMem.u32(e, GbaMem.EWRAM, addr + 4) - GCLASS_DATA) / CLASS_SIZE + 1,
                state = GbaMem.u32(e, GbaMem.EWRAM, addr + 0x0C),
                spritePalette = iwram(seed)[CFG - GbaMem.IWRAM + 1].toInt() and 0xFF,
            )
        }

        fun script(f: Int): IntArray {
            val iw = iwram(f)
            return IntArray(0x40) { iw[CFG - GbaMem.IWRAM + 5 + it].toInt() }
        }

        fun run() {
            val proc = checkNotNull(mu.startMu(unit()))
            val s = proc.state as MuProc
            var prev = s.state
            for (f in seed + 1..last) {
                val d = muDump(f) ?: return
                keys.readFrom(ewram(f), KEY_STATUS - GbaMem.EWRAM)
                if (prev == Mu.MU_STATE_INACTIVE && d.u8(0x3F) == Mu.MU_STATE_MOVEMENT) {
                    mu.setMuMoveScript(s, script(f))
                    if (d.u8(0x3E) == 0) mu.disableMuCamera(s)
                }
                hooks.called = false
                mu.onLoop(s)
                prev = s.state
                // The dump's OAM holds the previous frame's objects (VBlank copy).
                val o = oam(f + 1)
                var count = 0
                for (i in 0 until 128) {
                    val a2 = GbaMem.u16(o, 0, i * 8 + 4)
                    if (a2 and 0x3FF != MU_TILE) continue
                    count++
                    val a0 = GbaMem.u16(o, 0, i * 8)
                    val a1 = GbaMem.u16(o, 0, i * 8 + 2)
                    assertTrue(hooks.called, "f$f: MU object in OAM but no AP_Update")
                    assertEquals((hooks.x + OBJ_DX) and 0x1FF, a1 and 0x1FF, "f$f: x")
                    assertEquals((hooks.y + OBJ_DY) and 0xFF, a0 and 0xFF, "f$f: y")
                }
                assertEquals(if (hooks.called) 1 else 0, count, "f$f: MU objects in OAM")
                if (!hooks.called) hiddenFrames++
                compared++
            }
        }
    }

    private class Hooks : MuHooks {
        var called = false
        var x = 0
        var y = 0

        override fun apUpdate(anim: Int, x: Int, y: Int) {
            called = true
            this.x = x
            this.y = y
        }
    }

    private companion object {
        const val RUN = "ram_trace_prologue_talk"
        const val PROC_SCR_MU = 0x089A2C48
        const val GCLASS_DATA = 0x08807164
        const val CLASS_SIZE = 0x54
        const val CFG = 0x03001900
        const val BM_ST = 0x0202BCB0
        const val PLAY_ST = 0x0202BCF0
        const val KEY_STATUS = 0x02024CC0
        const val MU_GFX_BUFFER = 0x02004BE0
        const val MU_GFX_BUFFER_SIZE = 0x8800
        const val MU_TILE = 0x380

        /** The AP frame's object offset from the `AP_Update` position (ap.c, not mu.c). */
        const val OBJ_DX = -16
        const val OBJ_DY = -32
    }
}
