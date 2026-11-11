package fe8.testing

import fe8.game.RomView
import fe8.game.worldmap.GmData
import fe8.game.worldmap.GmapMuPrimProc
import fe8.game.worldmap.GmapMuProc
import fe8.game.worldmap.WmNodeData
import fe8.game.worldmap.WorldMapMu
import fe8.game.worldmap.WorldMapUnit
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.end
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
 * The creation of `ProcScr_GMapMu` and its seven `ProcScr_GMapMuPrim` procs
 * (`src/worldmap_mapmu.c`) against the `procs` watch of `ram_trace_prologue`, at the first traced
 * step (4901) where all eight procs exist. Compared: the slot pointers of the GMapMu proc equal
 * the prim procs in creation order, and each prim's `unk_2b` is -1 (the only field
 * `GmMuPrim_OnInit` writes). Not compared: the later per-slot fields, which `GmMu_SetUnit` and the
 * movement routines write.
 */
class WorldMapMuTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val romFile = File(assets, "baserom.gba")

    @VerifiesPort("StartGmMu", "GmMu_OnInit", "GmMuPrim_OnInit")
    @Test
    fun `GMapMu starts seven prim procs with no linked map unit`() {
        assumeTrue(ref.available && romFile.isFile && ProcArrayDump.romNames().isNotEmpty())
        val names = ProcArrayDump.romNames()
        val trace = ref.run("ram_trace_prologue").watch("procs")
        val muAddr = names.entries.first { it.value == "ProcScr_GMapMu" }.key
        val primAddr = names.entries.first { it.value == "ProcScr_GMapMuPrim" }.key
        val step = (4890..9320).first { ProcArrayDump.ofWatch(trace, it, names).find(muAddr) != null }
        val now = ProcArrayDump.ofWatch(trace, step, names)
        val traceMu = now.findAll(muAddr).single()
        val tracePrims = now.findAll(primAddr)
        assertEquals(GmapMuProc.SLOTS, tracePrims.size, "prim procs at $step")

        val rom = RomView.ofRom(romFile.readBytes())
        val engine = ProcEngine()
        val host = object : ApHost {
            override fun objectAt(addr: Int): SpriteObject = rom.objN(addr)
            override fun sin(a: Int) = 0
            override fun cos(a: Int) = 0x1000
            override fun setObjAffine(index: Int, pa: Int, pb: Int, pc: Int, pd: Int) {}
            override fun register2dChrMove(src: Int, dstOffset: Int, width: Int, height: Int) {}
            override fun obj1dMap() = false
        }
        val player = SpriteAnimPlayer({ rom.u16(it) }, SpriteLayers(), host)
        engine.init()
        player.clearAll()
        val gmData = GmData(ByteArray(0x8000), RngService())
        val unit = WorldMapUnit(engine, rom, BgHardware(), player, gmData)
        val mu = WorldMapMu(engine, unit, gmData, WmNodeData(rom))
        val parent = engine.start(ProcScript("Parent", listOf(block(), end())) { }, 3)
        val proc = mu.startGmMu(parent)

        val state = proc.state as GmapMuProc
        for (i in 0 until GmapMuProc.SLOTS) {
            assertEquals(tracePrims[i].address, traceMu.u32(0x2C + 4 * i), "traced slot $i pointer")
            val sim = state.prims[i]!!.state as GmapMuPrimProc
            assertEquals(tracePrims[i].u8(0x2B).toByte().toInt(), sim.mapUnit, "unk_2b of prim $i")
        }
    }
}
