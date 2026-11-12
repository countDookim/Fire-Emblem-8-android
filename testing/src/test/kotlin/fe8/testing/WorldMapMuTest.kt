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

    @VerifiesPort("GmMuPrim_80BD9D8", "GmMuPrim_80BDA78")
    @Test
    fun `linear prim movement matches the traced procs`() {
        assumeTrue(ref.available && romFile.isFile && ProcArrayDump.romNames().isNotEmpty())
        val names = ProcArrayDump.romNames()
        val trace = ref.run("ram_trace_prologue").watch("procs")
        val primAddr = names.entries.first { it.value == "ProcScr_GMapMuPrim" }.key
        fun prims(step: Int) = ProcArrayDump.ofWatch(trace, step, names).findAll(primAddr)
        val start = (4902..9309).first { s ->
            prims(s).any { it.u8(0x2C) == 2 && it.u8(0x29) == 0x11 }
        }
        val moving = prims(start).withIndex().filter { it.value.u8(0x2C) == 2 }.map { it.index }
        val traceStart = prims(start)

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
        unit.newGmapUnitContainer(parent, 0, 0)
        val proc = mu.startGmMu(parent)
        val state = proc.state as GmapMuProc
        // sub_80BDFA4 from the traced fields: the move inputs sit in the proc at the start.
        for (i in moving) {
            val t = traceStart[i]
            mu.mapMuSetUnitClass(proc, i, 1, 0, 0)
            val p = state.prims[i]!!.state as GmapMuPrimProc
            // The unit stands one step before its first traced position (the original start is
            // set by the caller, not by the prim).
            val first = prims(start + 1)[i]
            unit.mapUnitCSetPosition(
                p.mapUnit,
                first.u16(0x44) - first.u8(0x34).toByte(),
                first.u16(0x46) - first.u8(0x35).toByte(),
            )
            p.fromX = t.u16(0x48).toShort().toInt()
            p.fromY = t.u16(0x4A).toShort().toInt()
            p.toX = t.u16(0x4C).toShort().toInt()
            p.toY = t.u16(0x4E).toShort().toInt()
            p.duration = t.u16(0x50)
            p.easePower = t.u8(0x2F)
            p.delay = t.u16(0x42)
            p.moving = true
            p.animateWalk = true
            p.moveKind = 2
            p.anim1 = t.u8(0x31).toByte().toInt()
            p.anim2 = t.u8(0x32).toByte().toInt()
            engine.goto(state.prims[i]!!, 2)
        }
        var compared = 0
        for (step in start + 1..9309) {
            val now = prims(step)
            // Only the first movement of the prims: stop when new move inputs are written.
            if (moving.any { now[it].u16(0x50) != traceStart[it].u16(0x50) }) break
            val ahead = moving.all { now[it].u16(0x54) >= 1 }
            if (!ahead) continue
            val traceK = now[moving[0]].u16(0x54)
            var guard = 0
            while (
                (state.prims[moving[0]]!!.state as GmapMuPrimProc).timer < traceK &&
                guard++ < 400
            ) {
                engine.run(engine.root(3))
            }
            for (i in moving) {
                val t = now[i]
                val p = state.prims[i]!!.state as GmapMuPrimProc
                if (t.u8(0x2C) != 2 || (t.u8(0x29) and 1) == 0) continue
                val tag = "prim $i at step $step"
                assertEquals(t.u16(0x54), p.timer, "unk_54 of $tag")
                assertEquals(t.u16(0x44), p.lastX, "unk_44 of $tag")
                assertEquals(t.u16(0x46), p.lastY, "unk_46 of $tag")
                assertEquals(t.u16(0x40), p.trailCount, "unk_40 of $tag")
                for (j in 0 until 3) {
                    assertEquals(t.u8(0x34 + 4 * j), p.trailA[j] and 0xFF, "unk_34[$j].a of $tag")
                    assertEquals(t.u8(0x35 + 4 * j), p.trailB[j] and 0xFF, "unk_34[$j].b of $tag")
                }
                compared++
            }
        }
        assertEquals(true, compared > 100, "compared $compared prim steps")
    }
}
