package fe8.testing

import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.bm.BmSt
import fe8.game.bm.ManimSt
import fe8.game.mapanim.ApProcs
import fe8.game.mapanim.MapLevelUpAnnouncement
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.repeat
import gbafe.render.ApHost
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteAnimPlayer
import gbafe.render.SpriteLayers
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The announcement part of `ProcScr_ManimLevelUp` against `ram_trace_prologue_levelup`,
 * f6413..6518:
 * the proc cursor and `actor_id` of the `procs` watch every step, the music and AP proc lifetimes,
 * `gLCDControlBuffer` (window state) every step, and at the full snapshots the I/O window
 * registers, OBJ VRAM text graphics, OBJ palette banks and the text sprite's OAM entries.
 *
 * Seeded from the r06412 dump (gBmSt camera, the actor's unit, the LCD buffer). The sprite
 * animation proc (`APProc_Create`, `src/ap.c`) is not part of the port: the test supplies a proc
 * over [SpriteAnimPlayer] that draws like the original. Audio is not recorded, so the volume
 * calls are checked only as the lifetime of the `sMusicProc2Script` children.
 */
class MapLevelUpAnnouncementDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort(
        "StartManimLevelUp", "InitManimLevelUpWindow", "ManimLevelUp_StartLevelUpText",
        "ManimLevelUp_EndLevelUpText",
    )
    fun `announcement follows the original every step`() {
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        val names = ProcArrayDump.romNames()
        assumeTrue(ref.available && romFile.isFile && names.isNotEmpty(), "data not present")
        val run = ref.run("ram_trace_prologue_levelup")
        val trace = run.watch("procs")
        val symbols = RamSymbols.fromEnvironment()
        val rom = RomView.ofRom(romFile.readBytes())

        val seed = run.region("r06412", "ewram")
        val seedIwram = run.region("r06412", "iwram")
        val bm = BmSt().also { it.decode(seed, symbols["gBmSt"].address - EWRAM) }
        val manim = ManimSt()
        val manimAt = symbols["gManimSt"].address - EWRAM
        val unitPointer = GbaMem.u32(seed, GbaMem.EWRAM, symbols["gManimSt"].address)
        manim.unitBytes[0] = seed
        manim.unitOffset[0] = unitPointer - EWRAM
        assertTrue(manimAt >= 0 && unitPointer in EWRAM until EWRAM + seed.size)

        val engine = ProcEngine().apply { init() }
        val hardware = BgHardware()
        val sound = SoundWrapper(engine, null) { 0 }
        val layers = SpriteLayers()
        val host = object : ApHost {
            private val cache = HashMap<Int, gbafe.render.SpriteObject>()
            override fun objectAt(addr: Int) = cache.getOrPut(addr) { rom.objN(addr) }
            override fun sin(a: Int) = 0
            override fun cos(a: Int) = 0x1000
            override fun setObjAffine(index: Int, pa: Int, pb: Int, pc: Int, pd: Int) {}
            override fun register2dChrMove(src: Int, dstOffset: Int, width: Int, height: Int) {}
            override fun obj1dMap() = false
        }
        val player = SpriteAnimPlayer({ rom.u16(it) }, layers, host)
        player.clearAll()

        val apProcs = ApProcs(engine, player)
        val hooks = MapLevelUpAnnouncement.Hooks()
        val announcement =
            MapLevelUpAnnouncement(engine, hardware, rom, sound, bm, manim, apProcs, hooks)
        hardware.lcd.readFrom(seedIwram, symbols["gLCDControlBuffer"].address - GbaMem.IWRAM)

        // ProcMAExpBar_LevelUpIfPossible starts the level-up in f6413; the proc's children then
        // run in the same frame (its one-frame sleep ends in f6413).
        var levelUp: Proc? = null
        val expBar = ProcScript(
            "ProcScr_MapAnimExpBar",
            listOf(
                repeat(
                    ProcFunc {
                        if (levelUp == null) levelUp = announcement.startManimLevelUp(0, it)
                    },
                ),
            ),
        )
        val parent = engine.start(expBar, 3)

        fun at(step: Int) = ProcArrayDump.ofWatch(trace, step, names)
        val lcdAddress = symbols["gLCDControlBuffer"].address - GbaMem.IWRAM
        var musicSeen = 0
        var apSeen = 0
        var fullChecked = 0
        var drawnBefore = emptyList<List<Int>>()
        val expectedMusic = (6414..6429) + (6502..6517)
        for (step in 6413..6518) {
            layers.clear()
            engine.run(engine.root(3))
            val now = at(step)
            val original = checkNotNull(now.find("ProcScr_ManimLevelUp"), { "level-up f$step" })
            val mine = checkNotNull(levelUp)
            assertEquals(original.scrCur, mine.scrCur, "cursor f$step")
            // proc_lockCnt (+0x28): the level-up blocks the expbar proc and its music children.
            assertEquals(
                checkNotNull(now.find("ProcScr_MapAnimExpBar")).u8(0x28), parent.lockCnt,
                "expbar lockCnt f$step",
            )
            assertEquals(original.u8(0x28), mine.lockCnt, "level-up lockCnt f$step")
            val actor = (mine.state as MapLevelUpAnnouncement.State).actorId
            assertEquals(original.u16(0x2E), actor, "actor_id f$step")
            // sMusicProc2Script children of the level-up proc.
            val music = now.live.count { it.name == "sMusicProc2Script" }
            val ownMusic = countProcs(engine, sound.bgmVolumeChangeScript)
            assertEquals(music, ownMusic, "music procs f$step")
            assertEquals(step in expectedMusic, ownMusic == 1, "music window f$step")
            if (ownMusic > 0) musicSeen++
            val ap = now.live.count { it.name == "ProcScr_ApProc" }
            assertEquals(ap, countProcs(engine, apProcs.script), "AP procs f$step")
            assertEquals(step in 6431..6500, ap == 1, "AP window f$step")
            if (ap > 0) apSeen++

            // Window state in gLCDControlBuffer after the step.
            val dump = "%s%05d".format(if (step % 10 == 0) "f" else "r", step)
            val iwram = run.region(dump, "iwram")
            val expectedLcd = gbafe.render.LcdControl().also { it.readFrom(iwram, lcdAddress) }
            val lcd = hardware.lcd
            assertEquals(
                expectedLcd.dispcnt and 0xE000, lcd.dispcnt and 0xE000, "WIN enable f$step",
            )
            assertEquals(expectedLcd.wincnt, lcd.wincnt, "wincnt f$step")
            assertEquals(expectedLcd.winH.toList(), lcd.winH.toList(), "winH f$step")
            assertEquals(expectedLcd.winV.toList(), lcd.winV.toList(), "winV f$step")

            // OAM reaches the dump at VBlank: the entries of this step show in the next dump.
            val buffer = OamBuffer()
            layers.pushLayerObjects(2) { buffer.putHi(it.oam1, it.oam0, it.obj!!.data, it.oam2) }
            val drawn = (0 until buffer.hiPut / 4).map {
                listOf(
                    buffer.buffer[4 * it].toInt() and 0xFFFF,
                    buffer.buffer[4 * it + 1].toInt() and 0xFFFF,
                    buffer.buffer[4 * it + 2].toInt() and 0xFFFF,
                )
            }.sortedBy { it.joinToString() }
            if (step % 10 == 0) {
                fullChecked++
                val io = run.region(dump, "io")
                val ours = ByteArray(io.size)
                lcd.flushTo(ours)
                for (i in listOf(0x41, 0x40, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B)) {
                    assertEquals(io[i], ours[i], "IO[0x%02x] f$step".format(i))
                }
                assertEquals(
                    io[1].toInt() and 0xE0, ours[1].toInt() and 0xE0, "DISPCNT win bits f$step",
                )
                if (step >= 6431) {
                    val vram = run.region(dump, "vram")
                    val start = 0x10000 + 0x1C0 * 0x20
                    val size = rom.decompressInto(0x089AC794, ByteArray(0x4000), 0)
                    for (i in 0 until size) {
                        assertEquals(
                            vram[start + i], hardware.vram[start + i], "OBJ VRAM[$i] f$step",
                        )
                    }
                    val pal = run.region(dump, "pal")
                    for (i in 0x13 * 16 until 0x16 * 16) {
                        val colors = GbaMem.u16(pal, 0x05000000, 0x05000000 + 2 * i)
                        assertEquals(
                            colors, hardware.palette[i].toInt() and 0xFFFF, "OBJ pal[$i] f$step",
                        )
                    }
                }
                // The text sprite's OAM entries: OBJ palette bank 3, tiles from 0x1C0.
                val oam = run.region(dump, "oam")
                val expected = ArrayList<List<Int>>()
                for (i in 0 until 128) {
                    val a0 = GbaMem.u16(oam, 0x07000000, 0x07000000 + 8 * i)
                    val a1 = GbaMem.u16(oam, 0x07000000, 0x07000000 + 8 * i + 2)
                    val a2 = GbaMem.u16(oam, 0x07000000, 0x07000000 + 8 * i + 4)
                    // Skips the cleared entries (y 160, x 0) and disabled ones.
                    val cleared = a1 == 0 && a0 and 0xFF == 160
                    if (!cleared && a2 shr 12 == 3 && (a2 and 0x3FF) >= 0x1C0 &&
                        a0 and 0x300 != 0x200
                    ) {
                        expected += listOf(a0, a1, a2)
                    }
                }
                assertEquals(
                    drawnBefore, expected.sortedBy { it.joinToString() }, "text OAM f$step",
                )
                if (step in 6440..6490) assertTrue(expected.isNotEmpty(), "OAM present f$step")
            }
            drawnBefore = drawn
        }
        assertEquals(32, musicSeen)
        assertEquals(70, apSeen)
        assertEquals(10, fullChecked)
    }

    private fun countProcs(engine: ProcEngine, script: ProcScript): Int {
        var count = 0
        var found = engine.find(script)
        while (found != null) {
            count++
            found = engine.findAfter(script, found)
        }
        return count
    }

    private companion object {
        const val EWRAM = 0x02000000
        const val MAX_PROCS = 1
    }
}
