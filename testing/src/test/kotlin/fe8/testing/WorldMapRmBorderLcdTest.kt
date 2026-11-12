package fe8.testing

import fe8.game.RomView
import fe8.game.worldmap.GmapRmBaPalAnimProc
import fe8.game.worldmap.GmapRmBorderProc
import fe8.game.worldmap.WorldMapRmBaPalAnim
import fe8.game.worldmap.WorldMapRmBorder
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
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
 * The display effects of `ProcScr_GmapRmBorder1` and `ProcScr_GmapRmBaPalAnim1`
 * (`src/worldmap_rm.c`) against the per-step `displayio` (BLDCNT, BLDALPHA, BLDY, WIN*), `pal`
 * (palette RAM) and `oam` watches of `ram_trace_prologue_wm_lcd` (steps 5500..8450), whose
 * `procs` watch equals the one of `ram_trace_prologue`.
 *
 * Every border run of the window (steps 5528..8016) is replayed on a fresh rig seeded with the
 * registers and palette RAM of the step before the run. The port frames follow the traced proc
 * fields (as in `WorldMapRmBorderTest`). The hardware shows the state of the end of the previous
 * step, except while the start's long game loop pass lasts (the registers keep their old values
 * until the first frame). Compared every step: BLDCNT, BLDALPHA, BLDY, WIN* bytes, the whole
 * palette RAM (so palette 0x19 and that nothing else is written), the port's sprites as a
 * contiguous run of OAM entries (attr0..2) and, after the end, no visible OBJ at the border's
 * tile base. The OBJ tiles are compared at the full dumps inside the runs. `GmapRm_GetPosition`
 * is fed from the traced `ProcScr_GmapRM` here (verified in WorldMapEventCameraTest); `GmapRmBorder1_End`, its pulse
 * end and `GmapRmBaPalAnim1_End` leave no trace (mutations of them pass), so they stay
 * transcribed too.
 */
class WorldMapRmBorderLcdTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val romFile = File(assets, "baserom.gba")

    /** What the port shows after step: blend registers, window registers, palette, sprites. */
    private class Shown(
        val lcd: List<Int>,
        val palette: List<Int>,
        val sprites: List<Int>,
    )

    @VerifiesPort(
        "GmapRmBorder1_80C2750",
        "GmapRmBorder1_PutSprite2",
        "GmapRmBorder1_NationMergeIn",
        "GmapRmBorder1_NationMergeOut",
        "GmapRmBorder1_PutSpriteAll",
        "StartGmapRmBorder1",
        "WmShowNationHighlightedMap",
        "DrawWmNationHighLightMapGfx",
        "GmapRmBaPalAnim1_Init",
        "GmapRmBaPalAnim1_Loop1",
        "GmapRmBaPalAnim1_Loop2",
        "StartGmapRmBaPalAnim1",
        "CheckGmapRmBaPalAnim1State",
    )
    @Test
    fun `border and pulse registers, palette 0x19 and sprites match the lcd watches every step`() {
        val run = ref.run("ram_trace_prologue_wm_lcd")
        assumeTrue(
            ref.available && romFile.isFile && ProcArrayDump.romNames().isNotEmpty() &&
                run.hasWatch("oam"),
        )
        val names = ProcArrayDump.romNames()
        val procs = run.watch("procs")
        val io = run.watch("displayio")
        val pal = run.watch("pal")
        val oam = run.watch("oam")
        fun dump(step: Int) = ProcArrayDump.ofWatch(procs, step, names)
        val address = names.entries.first { it.value == "ProcScr_GmapRmBorder1" }.key
        val rmAddress = names.entries.first { it.value == "ProcScr_GmapRM" }.key
        val palAddress = names.entries.first { it.value == "ProcScr_GmapRmBaPalAnim1" }.key
        val steps = (procs.firstStep..procs.lastStep).filter {
            dump(it).findAll(address).isNotEmpty()
        }
        val runs = ArrayList<IntRange>()
        for (step in steps) {
            val last = runs.lastOrNull()
            if (last != null && last.last + 1 == step) runs[runs.size - 1] = last.first..step
            else runs.add(step..step)
        }
        val rom = RomView.ofRom(romFile.readBytes())
        var compared = 0
        var spriteSteps = 0
        var vramChecked = 0
        val seenAlpha = HashSet<Int>()
        for (r in runs) {
            val rig = Rig(rom)
            // The state before the run: what other procs left in the registers and palette.
            rig.hardware.lcd.readRegisters(io.step(r.first - 1))
            val before = pal.step(r.first - 1)
            for (i in 0 until 512) rig.hardware.palette[i] = u16(before, 2 * i).toShort()
            // The hardware shows a frame's writes one frame later (the sync runs before the procs
            // of the next pass); the start's writes come out with the first frame.
            val pre = capture(rig)
            fun position(step: Int) = dump(step).find(rmAddress)?.let { it.u16(0x2E) to it.u16(0x30) }
            rig.position = position(r.first)
            val first = dump(r.first).findAll(address)
            val started = first.map { rig.border.wmShowNationHighlightedMap(it.u8(0x2A), null) }
            val slot = (started[0].state as GmapRmBorderProc).slot
            // `endOfPrevious`: the state at the end of the previous step; `begun`: the start's long
            // pass is over, so the hardware shows the state of the previous step.
            var endOfPrevious = capture(rig)
            var begun = false
            var previous = signature(dump(r.first), address, palAddress)
            for (step in r.first..r.last + 1) {
                val now = dump(step)
                val signature = signature(now, address, palAddress)
                for (e in now.findAll(address)) {
                    if (e.u8(0x29) == 1) rig.border.sub80C2C10(e.u8(0x2A))
                }
                rig.position = position(step)
                var ran = false
                fun frame() {
                    rig.layers.clear()
                    rig.engine.run(rig.engine.root(3))
                    ran = true
                }
                fun mine(): List<Int> {
                    val own = started.filter { it.script != null }.flatMap {
                        val s = it.state as GmapRmBorderProc
                        listOf(it.scrCur, s.flag, s.index, s.slot, s.timer)
                    }
                    val pulse = rig.engine.find(rig.baPal.script)
                    return own + listOfNotNull(
                        pulse?.scrCur, (pulse?.state as GmapRmBaPalAnimProc?)?.flag,
                        (pulse?.state as GmapRmBaPalAnimProc?)?.timer,
                    )
                }
                val theirs = now.findAll(address).flatMap {
                    listOf(it.scrCur, it.u8(0x29), it.u8(0x2A), it.u8(0x2B), it.u16(0x30))
                } + now.findAll(palAddress).flatMap {
                    listOf(it.scrCur, it.u8(0x29), it.u16(0x2A).toShort().toInt())
                }
                if (step > r.first && signature != previous) frame()
                var extra = 0
                while (step <= r.last && mine() != theirs && extra++ < 1) frame()
                previous = signature
                if (ran) begun = true
                val shown = if (begun) endOfPrevious else pre
                if (ran) endOfPrevious = capture(rig)
                val sprites = shown.sprites
                val lcd = ArrayList<Int>()
                for (o in LCD_OFFSETS) lcd.add(io.u8(step, o))
                val palette = ArrayList<Int>()
                for (i in 0 until 512) palette.add(pal.u16(step, 2 * i))
                assertEquals(lcd, shown.lcd, "BLDCNT/BLDALPHA/BLDY/WIN* at step $step ran=$ran mine=${mine()} theirs=$theirs")
                assertEquals(palette, shown.palette, "palette RAM at step $step")
                val reference = oam.step(step)
                if (sprites.isNotEmpty()) {
                    val count = sprites.size / 3
                    assertTrue(
                        (0..128 - count).any { start ->
                            (0 until count * 3).all { k ->
                                u16(reference, start * 8 + (k / 3) * 8 + (k % 3) * 2) ==
                                    sprites[k]
                            }
                        },
                        "border sprites in OAM at step $step: " + sprites.map { it.toString(16) } + " ref " + (0 until 16).map { u16(reference, it * 8).toString(16) + "," + u16(reference, it * 8 + 2).toString(16) + "," + u16(reference, it * 8 + 4).toString(16) },
                    )
                    spriteSteps++
                }
                if (sprites.isEmpty() && begun) {
                    // After the end both sprites are deleted: no visible OBJ at the border's
                    // tile base (ClearOam leaves attr2 behind and moves the object off screen).
                    for (i in 0 until 128) {
                        assertTrue(
                            u16(reference, i * 8 + 4) and 0xFC00 != 0x9C00 ||
                                u16(reference, i * 8) == 0xA0,
                            "border sprite $i still in OAM at step $step",
                        )
                    }
                }
                if (step in FULL_DUMPS) {
                    val vram = run.region("f%05d".format(step), "vram")
                    val chr = 0x10000 + slot * 0x1000
                    assertEquals(
                        vram.copyOfRange(chr, chr + 0x1000).toList(),
                        rig.hardware.vram.copyOfRange(chr, chr + 0x1000).toList(),
                        "OBJ tiles of the highlighted nation at step $step",
                    )
                    vramChecked++
                }
                seenAlpha.add(io.u16(step, 0x52))
                compared++
            }
        }
        assertTrue(compared > 1000, "steps compared: $compared")
        assertTrue(spriteSteps > 500, "steps with border sprites: $spriteSteps")
        assertTrue(vramChecked >= 2, "full dumps inside border runs: $vramChecked")
        assertTrue(seenAlpha.size > 20, "distinct BLDALPHA values: ${seenAlpha.size}")
    }

    private fun capture(rig: Rig): Shown {
        val io = ByteArray(0x60)
        rig.hardware.lcd.flushTo(io)
        val lcd = LCD_OFFSETS.map { io[it].toInt() and 0xFF }
        val oam = OamBuffer()
        rig.layers.pushLayerObjects(13) { oam.putHi(it.oam1, it.oam0, it.obj!!.data, it.oam2) }
        val sprites = ArrayList<Int>()
        for (i in 0 until oam.hiPut / 4) {
            for (a in 0..2) sprites.add(oam.buffer[i * 4 + a].toInt() and 0xFFFF)
        }
        return Shown(lcd, rig.hardware.palette.map { it.toInt() and 0xFFFF }, sprites)
    }

    private fun u16(bytes: ByteArray, at: Int) =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun signature(dump: ProcArrayDump, border: Int, pulse: Int): List<Int> =
        (dump.findAll(border) + dump.findAll(pulse)).flatMap {
            listOf(it.scrCur, it.u16(0x2A), it.u16(0x30))
        }

    private companion object {
        /** Bytes of WIN0H..WINOUT (0x40..0x4B) and BLDCNT, BLDALPHA, BLDY (0x50..0x54). */
        val FULL_DUMPS = setOf(5528, 5600, 5670, 8100, 8200, 8300, 8450)

        val LCD_OFFSETS = (0x40..0x4B).toList() + (0x50..0x54).toList()
    }

    private class Rig(rom: RomView) {
        val engine = ProcEngine()
        val hardware = BgHardware()
        private val cache = HashMap<Int, SpriteObject>()
        private val host = object : ApHost {
            override fun objectAt(addr: Int) = cache.getOrPut(addr) { rom.objN(addr) }
            override fun sin(a: Int) = 0
            override fun cos(a: Int) = 0x1000
            override fun setObjAffine(index: Int, pa: Int, pb: Int, pc: Int, pd: Int) {}
            override fun register2dChrMove(src: Int, dstOffset: Int, width: Int, height: Int) {}
            override fun obj1dMap() = false
        }
        val layers = SpriteLayers()
        private val player = SpriteAnimPlayer({ rom.u16(it) }, layers, host)
        val baPal = WorldMapRmBaPalAnim(engine, hardware.lcd) { 1 }

        /** `GmapRm_GetPosition`: the traced `ProcScr_GmapRM` position (the map is not ported here). */
        var position: Pair<Int, Int>? = null
        val border = WorldMapRmBorder(engine, hardware, rom, player, baPal) { position }

        init {
            engine.init()
            player.clearAll()
        }
    }
}
