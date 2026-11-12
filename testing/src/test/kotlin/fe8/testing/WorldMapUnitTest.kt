package fe8.testing

import fe8.game.RomView
import fe8.game.worldmap.GmData
import fe8.game.worldmap.GmapUnitFadeProc
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
import org.junit.jupiter.api.Assertions.assertTrue
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
 * wrapping); the OAM output, `ap->tileBase` and `GmapUnit_Loop` are not compared.
 *
 * Fade palettes: the OBJ palettes of `ram_trace_prologue_wm_lcd` at step 5500 seed the palette
 * buffer, then OBJ palette 0xA (the fade target) is compared at every step 8185..8450 with the
 * palette RAM watch (which shows the buffer of the end of the previous step), and OBJ palettes
 * 0..2 (the factions the fades read) stay equal to the watch. Unit OAM entries need the
 * `SMS_DisplayOne` hook and are not compared.
 */
class WorldMapUnitTest {
    private val ref = ReferenceData.fromEnvironment()
    private val assets = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
    )
    private val romFile = File(assets, "baserom.gba")

    @VerifiesPort(
        "NewGmapUnitContainer",
        "GmapUnitContainer_Init",
        "NewMapUnit",
        "GmapUnit_Init",
        "GetPaletteIdForAllegience",
        "MapUnitC_DeleteGmapUnit",
        "MapUnitC_SetPosition",
        "MapUnitC_GetPosition",
        "MapUnitC_SetAnimId",
        "MapUnitC_GetAnimId",
        "MapUnitC_SetDisplayEnabled",
        "MapUnitC_SetLayer",
        "MapUnitC_SetFaction",
        "MapUnitC_SetPalette",
        "sub_80BB194",
        "GmapUnit_Loop",
        "MapUnitC_SetGfxNeedsUpdate",
        "sub_80BB26C",
        "GmapUnitFade_Init",
        "GmapUnitFade_Loop",
        "GmapUnitFade_Destruct",
        "StartGmapUnitFade",
        "GetGmapUnitFade",
        "sub_80BB49C",
        "sub_80BB4C0",
        "sub_80BB538",
    )
    @Test
    fun `container and sprites follow the traced fields every step`() {
        assumeTrue(ref.available && romFile.isFile && ProcArrayDump.romNames().isNotEmpty())
        val names = ProcArrayDump.romNames()
        val trace = ref.run("ram_trace_prologue").watch("procs")
        fun dump(step: Int) = ProcArrayDump.ofWatch(trace, step, names)
        val containerAddr = names.entries.first { it.value == "ProcScr_GmapUnitContainer" }.key
        val unitAddr = names.entries.first { it.value == "ProcScr_GmapUnit" }.key
        val fadeAddr = names.entries.first { it.value == "ProcScr_GmapUnitFade" }.key

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
        val layers = SpriteLayers()
        val player = SpriteAnimPlayer({ rom.u16(it) }, layers, host)
        engine.init()
        player.clearAll()
        val gmData = GmData(ByteArray(0x8000), RngService())
        var camX = 0
        var camY = 0
        val unit = WorldMapUnit(
            engine, rom, hardware, player, gmData,
            WorldMapUnit.Hooks(screenPosition = { camX to camY }),
        )
        val parent = engine.start(ProcScript("Parent", listOf(block(), end())) { }, 3)
        // OBJ palettes from `ram_trace_prologue_wm_lcd` (per-step palette RAM from step 5500): the
        // faction palettes (OBJ 0..2) the fades read, then OBJ palette 0xA compared per step.
        val palRun = ref.run("ram_trace_prologue_wm_lcd")
        assumeTrue(palRun.hasWatch("pal"), "no per-step palette watch")
        val palTrace = palRun.watch("pal")
        for (i in 0 until 512) hardware.palette[i] = palTrace.u16(palTrace.firstStep, 2 * i).toShort()
        var palChecked = 0
        var fadePalChanges = 0
        var palBefore = fadePalette(hardware)

        var container: Proc? = null
        val sim = HashMap<Int, Proc>()
        val previous = HashMap<Int, List<Int>>()
        var seen = 0
        var fadeAge = 0
        var lastTimer = -1
        var lastFadeUnits: Set<Int> = emptySet()
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
            val fresh = HashSet<Int>()
            for ((index, p) in traced.toSortedMap()) {
                if (index in sim) continue
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
                if (p.u32(0x40) == 0) fresh.add(index)
            }
            // The fade: started by sub_80BB4C0 (from black) or sub_80BB538 (to black); later
            // sprites join it through sub_80BB49C, which those two call while a fade runs.
            val traceFade = now.findAll(fadeAddr).firstOrNull()
            val fadeUnits = HashSet<Int>()
            if (traceFade != null) {
                for (i in 0 until traceFade.u8(0x37)) fadeUnits.add(traceFade.u8(0x30 + i))
            }
            for ((index, p) in traced.toSortedMap()) {
                if (index in fresh) continue
                val old = previous.getValue(index)
                val new = fields(p)
                if (new[X] != old[X] || new[Y] != old[Y]) {
                    unit.mapUnitCSetPosition(index, new[X], new[Y])
                }
                if (new[ANIM] != old[ANIM]) unit.mapUnitCSetAnimId(index, new[ANIM])
                if (new[LAYER] != old[LAYER]) unit.mapUnitCSetLayer(index, new[LAYER])
                if (new[FLAGS] and 1 != old[FLAGS] and 1 && index !in fadeUnits &&
                    index !in lastFadeUnits
                ) {
                    unit.mapUnitCSetDisplayEnabled(index, new[FLAGS] and 1 != 0)
                }
                if (new[FLAGS] and 4 != 0 && old[FLAGS] and 4 == 0) {
                    unit.mapUnitCSetGfxNeedsUpdate(index)
                }
                if (new[FACTION] != old[FACTION]) {
                    unit.mapUnitCSetFaction(index, new[FACTION])
                } else if (new[PAL] != old[PAL] && index !in fadeUnits &&
                    index !in lastFadeUnits
                ) {
                    if (new[PAL] == unit.getPaletteIdForAllegience(new[FACTION])) {
                        unit.mapUnitCSetFaction(index, -1)
                    } else {
                        unit.mapUnitCSetPalette(index, new[PAL])
                    }
                }
                previous[index] = new
            }
            lastFadeUnits = fadeUnits
            val live = sim.values.map { it.state as GmapUnitProc }.filter { it.flags and 1 != 0 }
            camX = live.minOfOrNull { it.x } ?: 0
            camY = live.minOfOrNull { it.y } ?: 0
            layers.clear()
            // A trace step can repeat a frame (the fade timer does not move): run no frame then.
            val repeated = traceFade != null && fadeAge > 0 && traceFade.u16(0x2E) == lastTimer
            lastTimer = traceFade?.u16(0x2E) ?: -1
            if (!repeated) engine.run(engine.root(3))
            if (traceFade != null) {
                val count = traceFade.u8(0x37)
                val from = traceFade.u8(0x29).toByte().toInt()
                val first = unit.getGmapUnitFade() == null
                val running = unit.getGmapUnitFade()
                val have = if (first) 0 else (running!!.state as GmapUnitFadeProc).count
                for (i in have until count) {
                    val index = traceFade.u8(0x30 + i)
                    val duration = traceFade.u16(0x2C)
                    if (from < 0) unit.sub80BB4C0(index, duration, null)
                    else unit.sub80BB538(index, duration, null)
                }
            }
            for ((index, p) in traced.toSortedMap()) {
                if (index in fresh) continue
                val new = fields(p)
                val s = sim.getValue(index).state as GmapUnitProc
                assertEquals(new, simFields(s), "sprite $index at step $step")
                assertEquals(listOf(s.x, s.y), unit.mapUnitCGetPosition(index).toList())
                assertEquals(new[ANIM].toByte().toInt(), unit.mapUnitCGetAnimId(index))
                assertEquals(new[CLASS2], unit.sub80BB194(index))
            }
            if (step in FADE_PAL_FIRST..palTrace.lastStep) {
                // The palette RAM shows the buffer as it stood at the end of the previous step.
                val shown = List(0x10) { palTrace.u16(step, 2 * (0x100 + FADE_PAL * 0x10 + it)) }
                assertEquals(shown, palBefore, "OBJ palette 0xA at step $step")
                for (pal in 0..2) {
                    assertEquals(
                        List(0x10) { palTrace.u16(step, 2 * (0x100 + pal * 0x10 + it)) },
                        List(0x10) { hardware.palette[0x100 + pal * 0x10 + it].toInt() and 0xFFFF },
                        "faction OBJ palette $pal at step $step",
                    )
                }
                if (shown != fadePalette(hardware)) fadePalChanges++
                palChecked++
            }
            palBefore = fadePalette(hardware)
            val simFade = unit.getGmapUnitFade()
            assertEquals(traceFade != null, simFade != null, "fade exists at $step")
            if (traceFade != null && simFade != null) {
                val f = simFade.state as GmapUnitFadeProc
                val count = traceFade.u8(0x37)
                assertEquals(
                    listOf(
                        traceFade.u8(0x29).toByte().toInt(), traceFade.u8(0x2A).toByte().toInt(),
                        traceFade.u16(0x2C), count,
                        List(count) { traceFade.u8(0x30 + it) },
                    ),
                    listOf(f.palFrom, f.palTo, f.duration, f.count, List(count) { f.units[it] }),
                    "fade fields at $step",
                )
                fadeAge++
                if (fadeAge > 1) assertEquals(traceFade.u16(0x2E), f.timer, "fade timer at $step")
            } else {
                fadeAge = 0
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
        assertTrue(palChecked > 250, "steps with a palette compare: $palChecked")
        assertTrue(fadePalChanges > 60, "steps where the fade palette moved: $fadePalChanges")
        assertEquals(10, seen, "sprites the capture creates")
        assertEquals(true, container != null)
    }

    private fun fadePalette(hardware: BgHardware): List<Int> = List(0x10) {
        hardware.palette[0x100 + FADE_PAL * 0x10 + it].toInt() and 0xFFFF
    }

    private fun simFields(s: GmapUnitProc): List<Int> = listOf(
        s.flags, s.layer, s.oam2Base, s.chr, s.index, s.faction, s.pal,
        s.animId and 0xFF, s.classId, s.currentClassId, s.x, s.y, if (s.ap != null) 1 else 0,
    )

    private fun fields(p: ProcArrayDump.Proc): List<Int> = listOf(
        p.u16(0x2A), p.u16(0x2C), p.u16(0x2E), p.u16(0x30), p.u8(0x34).toByte().toInt(),
        p.u8(0x35), p.u8(0x36), p.u8(0x37), p.u16(0x38), p.u16(0x3A), p.u16(0x3C), p.u16(0x3E),
        if (p.u32(0x40) != 0) 1 else 0,
    )

    private companion object {
        const val FADE_PAL = 0xA
        const val FADE_PAL_FIRST = 8185
        const val FLAGS = 0
        const val LAYER = 1
        const val FACTION = 5
        const val PAL = 6
        const val ANIM = 7
        const val CLASS2 = 9
        const val X = 10
        const val Y = 11
    }
}
