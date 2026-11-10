package fe8.testing

import fe8.game.RomView
import fe8.game.worldmap.GmData
import fe8.game.worldmap.GmapUnitProc
import fe8.game.worldmap.WorldMapUnit
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import gbafe.proc.Proc
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
 * The map sprite container and sprites of `src/worldmap_gmapunit.c` against the `procs` watch of
 * `ram_trace_prologue` (steps 4890..9320: one container, ten sprites created over the run).
 *
 * The container is created from its traced `layer`/`chr`; every sprite is created the first step
 * it is traced (`NewMapUnit` with the traced class and faction) and then driven by the setter its
 * traced field change implies (position, animation, layer, display flag, faction or palette).
 * Compared at every step: the container's fields and slot pointers (each equals the sprite proc's
 * address) and the sprite fields `flags` (without `UPDATEGFX`, which `GmapUnit_Loop` clears),
 * `unk_2c`, `unk_2e`, `unk_30`, `index`, `faction`, `pal`, `animId`, `unk_38`, `unk_3a`, `x`,
 * `y` and whether `ap` is set. Limits: a setter's argument is taken from the trace, so what is
 * verified is the stored fields and the derived ones (`pal` from the faction, `s8` wrapping, `u16`
 * wrapping); the OAM output, `ap->tileBase`, the fades and `GmapUnit_Loop` are not compared.
 */
class WorldMapUnitTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val romFile = File(assets, "baserom.gba")

    @VerifiesPort(
        "src/worldmap_gmapunit.c: NewGmapUnitContainer",
        "src/worldmap_gmapunit.c: GmapUnitContainer_Init",
        "src/worldmap_gmapunit.c: NewMapUnit",
        "src/worldmap_gmapunit.c: GmapUnit_Init",
        "src/worldmap_gmapunit.c: GetPaletteIdForAllegience",
        "src/worldmap_gmapunit.c: MapUnitC_DeleteGmapUnit",
        "src/worldmap_gmapunit.c: MapUnitC_SetPosition",
        "src/worldmap_gmapunit.c: MapUnitC_GetPosition",
        "src/worldmap_gmapunit.c: MapUnitC_SetAnimId",
        "src/worldmap_gmapunit.c: MapUnitC_GetAnimId",
        "src/worldmap_gmapunit.c: MapUnitC_SetDisplayEnabled",
        "src/worldmap_gmapunit.c: MapUnitC_SetLayer",
        "src/worldmap_gmapunit.c: MapUnitC_SetFaction",
        "src/worldmap_gmapunit.c: MapUnitC_SetPalette",
        "src/worldmap_gmapunit.c: sub_80BB194",
    )
    @Test
    fun `container and sprites follow the traced fields every step`() {
        assumeTrue(ref.available && romFile.isFile && ProcArrayDump.romNames().isNotEmpty())
        val names = ProcArrayDump.romNames()
        val trace = ref.run("ram_trace_prologue").watch("procs")
        fun dump(step: Int) = ProcArrayDump.ofWatch(trace, step, names)
        val containerAddr = names.entries.first { it.value == "ProcScr_GmapUnitContainer" }.key
        val unitAddr = names.entries.first { it.value == "ProcScr_GmapUnit" }.key

        val rom = RomView.ofRom(romFile.readBytes())
        val engine = ProcEngine()
        val hardware = BgHardware()
        val cache = HashMap<Int, SpriteObject>()
        val host = object : ApHost {
            override fun objectAt(addr: Int) = cache.getOrPut(addr) { rom.objN(addr) }
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
        val unit = WorldMapUnit(engine, rom, hardware, player, gmData)
        val parent = engine.start(ProcScript("Parent", listOf(block(), end())) { }, 3)

        var container: Proc? = null
        val sim = HashMap<Int, Proc>()
        val previous = HashMap<Int, List<Int>>()
        var seen = 0
        for (step in 4890..9320) {
            val now = dump(step)
            val traceContainer = now.findAll(containerAddr).firstOrNull()
            if (traceContainer == null) {
                if (container != null) break // the capture ends the container at its last step
                continue
            }
            if (container == null) {
                container = unit.newGmapUnitContainer(
                    parent, traceContainer.u16(0x2C), traceContainer.u8(0x30),
                )
                for (i in 0 until 7) {
                    assertEquals(0, traceContainer.u32(0x34 + 4 * i), "slot $i empty at creation")
                }
            }
            val state = container.state as fe8.game.worldmap.GmapUnitContainerProc
            assertEquals(traceContainer.u16(0x2C), state.layer, "container layer at $step")
            assertEquals(traceContainer.u8(0x30), state.chr, "container chr at $step")
            val traced = now.findAll(unitAddr).associateBy { it.u8(0x34).toByte().toInt() }
            for (index in sim.keys.toList()) {
                if (index !in traced) {
                    unit.mapUnitCDeleteGmapUnit(index)
                    sim.remove(index)
                    previous.remove(index)
                }
            }
            var created = false
            for ((index, p) in traced.toSortedMap()) {
                if (index !in sim) {
                    val slot = unit.newMapUnit(container, p.u16(0x38), p.u8(0x35))
                    assertEquals(index, slot, "slot of the new sprite at $step")
                    sim[index] = state.units[slot]!!
                    val s = sim[index]!!.state as GmapUnitProc
                    // The first trace of a new sprite can be taken before NewMapUnit finished.
                    assertEquals(
                        listOf(0x800, p.u8(0x35), p.u8(0x36), p.u8(0x37), p.u16(0x38), p.u16(0x3A)),
                        listOf(s.oam2Base, s.faction, s.pal, s.animId, s.classId, s.currentClassId),
                        "NewMapUnit fields at $step",
                    )
                    previous[index] = simFields(s)
                    created = true
                    seen++
                    if (p.u32(0x40) == 0) continue
                }
                val old = previous.getValue(index)
                val new = fields(p)
                if (new[X] != old[X] || new[Y] != old[Y]) {
                    unit.mapUnitCSetPosition(index, new[X], new[Y])
                }
                if (new[ANIM] != old[ANIM]) unit.mapUnitCSetAnimId(index, new[ANIM])
                if (new[LAYER] != old[LAYER]) unit.mapUnitCSetLayer(index, new[LAYER])
                if (new[FLAGS] and 1 != old[FLAGS] and 1) {
                    unit.mapUnitCSetDisplayEnabled(index, new[FLAGS] and 1 != 0)
                }
                if (new[FACTION] != old[FACTION]) {
                    unit.mapUnitCSetFaction(index, new[FACTION])
                } else if (new[PAL] != old[PAL]) {
                    if (new[PAL] == unit.getPaletteIdForAllegience(new[FACTION])) {
                        unit.mapUnitCSetFaction(index, -1)
                    } else {
                        unit.mapUnitCSetPalette(index, new[PAL])
                    }
                }
                previous[index] = new
                val s = sim.getValue(index).state as GmapUnitProc
                assertEquals(
                    new, simFields(s),
                    "sprite $index at step $step",
                )
                assertEquals(listOf(s.x, s.y), unit.mapUnitCGetPosition(index).toList())
                assertEquals(new[ANIM].toByte().toInt(), unit.mapUnitCGetAnimId(index))
                assertEquals(new[CLASS2], unit.sub80BB194(index))
            }
            if (!created) {
                for (i in 0 until 7) {
                    val address = traceContainer.u32(0x34 + 4 * i)
                    val live = traced[i]
                    assertEquals(
                        if (live == null) 0 else live.address,
                        if (address == 0 && live != null) 0 else address,
                        "slot $i pointer at $step",
                    )
                }
            }
        }
        assertEquals(10, seen, "sprites the capture creates")
        assertEquals(true, container != null)
    }

    private fun simFields(s: GmapUnitProc): List<Int> = listOf(
        s.flags and 4.inv(), s.layer, s.oam2Base, s.chr, s.index, s.faction, s.pal,
        s.animId and 0xFF, s.classId, s.currentClassId, s.x, s.y, if (s.ap != null) 1 else 0,
    )

    private fun fields(p: ProcArrayDump.Proc): List<Int> = listOf(
        p.u16(0x2A) and 4.inv(), p.u16(0x2C), p.u16(0x2E), p.u16(0x30), p.u8(0x34).toByte().toInt(),
        p.u8(0x35), p.u8(0x36), p.u8(0x37), p.u16(0x38), p.u16(0x3A), p.u16(0x3C), p.u16(0x3E),
        if (p.u32(0x40) != 0) 1 else 0,
    )

    private companion object {
        const val FLAGS = 0
        const val LAYER = 1
        const val INDEX = 4
        const val FACTION = 5
        const val PAL = 6
        const val ANIM = 7
        const val CLASS2 = 9
        const val X = 10
        const val Y = 11
    }
}
