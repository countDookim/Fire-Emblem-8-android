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
import gbafe.render.SpriteAnimPlayer
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * `ProcScr_GmapRmBorder1` (`src/worldmap_rm.c`) against the `procs` watch of
 * `ram_trace_prologue`: script position, `flag`, `index`, `unk_2b`, `timer` of every border proc
 * and the existence of the coexisting `ProcScr_GmapRmBaPalAnim1` at every step of each run. The
 * OAM, palette and VRAM are only dumped outside the runs, so what they would show stays
 * "transcribed".
 */
class WorldMapRmBorderTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val romFile = File(assets, "baserom.gba")

    @VerifiesPort(
        "GmapRmBorder1_80C28C4",
        "GmapRmBorder1_80C2964",
        "GmapRmBorder1_80C29F8",
        "GmapRmBorder1_80C2A1C",
    )
    @Test
    fun `border procs follow the original script position and fields every step`() {
        assumeTrue(ref.available && romFile.isFile && ProcArrayDump.romNames().isNotEmpty())
        val names = ProcArrayDump.romNames()
        val trace = ref.run("ram_trace_prologue").watch("procs")
        fun dump(step: Int) = ProcArrayDump.ofWatch(trace, step, names)
        val address = names.entries.first { it.value == "ProcScr_GmapRmBorder1" }.key
        val palAddress = names.entries.first { it.value == "ProcScr_GmapRmBaPalAnim1" }.key
        val steps = (5000..9000).filter { dump(it).findAll(address).isNotEmpty() }
        assertEquals(true, steps.isNotEmpty(), "the trace holds a border proc")
        val runs = ArrayList<IntRange>()
        for (step in steps) {
            val last = runs.lastOrNull()
            if (last != null && last.last + 1 == step) runs[runs.size - 1] = last.first..step
            else runs.add(step..step)
        }
        val rom = RomView.ofRom(romFile.readBytes())
        var resyncs = 0
        for (run in runs) {
            val rig = Rig(rom)
            val first = dump(run.first).findAll(address)
            val procs = first.map {
                rig.border.wmShowNationHighlightedMap(it.u8(0x2A), null)
            }
            var previous = signature(dump(run.first), address, palAddress)
            for (step in run) {
                val now = dump(step)
                val signature = signature(now, address, palAddress)
                // The event that ends the pulse sets the flag between frames.
                for (e in now.findAll(address)) {
                    if (e.u8(0x29) == 1) rig.border.sub80C2C10(e.u8(0x2A))
                }
                fun frame() {
                    rig.layers.clear()
                    rig.engine.run(rig.engine.root(3))
                }
                fun mine(): List<Int> {
                    val own = procs.filter { it.script != null }.flatMap {
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
                // The game loop skips some steps: a frame runs when a traced field moved. A frame
                // that changes no traced field is not visible, so at most one more is tried; the
                // number of such resyncs is pinned below so a behaviour change fails the test.
                if (step > run.first && signature != previous) frame()
                var extra = 0
                while (mine() != theirs && extra++ < 1) {
                    frame()
                    resyncs++
                }
                previous = signature
                assertEquals(theirs, mine(), "borders then pulse (script, flag, ...) at step $step")
            }
        }
        assertEquals(EXPECTED_RESYNCS, resyncs, "frames the traced fields did not show")
    }

    private fun signature(dump: ProcArrayDump, border: Int, pulse: Int): List<Int> =
        (dump.findAll(border) + dump.findAll(pulse)).flatMap {
            listOf(it.scrCur, it.u16(0x2A), it.u16(0x30))
        }

    private companion object {
        const val EXPECTED_RESYNCS = 18
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
        val border = WorldMapRmBorder(engine, hardware, rom, player, baPal) { null }

        init {
            engine.init()
            player.clearAll()
        }
    }
}
