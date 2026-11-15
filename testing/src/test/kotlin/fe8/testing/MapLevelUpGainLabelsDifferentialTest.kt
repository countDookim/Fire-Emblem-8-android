package fe8.testing

import fe8.game.RomView
import fe8.game.bm.BattleUnit
import fe8.game.mapanim.ApProcs
import fe8.game.mapanim.MapLevelUpGainLabels
import fe8.game.worldmap.WorldMapApHost
import gba.core.VerifiesPort
import gba.testkit.RamSymbols
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteAnimPlayer
import gbafe.render.SpriteLayers
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/** The Prologue level-up gain labels and their animation procs, f6519..6729. */
class MapLevelUpGainLabelsDifferentialTest {
    @Test
    @VerifiesPort(
        "StartManimLevelUpStatGainLabels", "StartManimLevelUpStatGainLabelAnim",
        "EndManimLevelUpStatGainLabels", "ManimLevelUpStatGainLabel_Finish",
        "APProc_Create", "APProc_OnUpdate", "APProc_OnEnd", "APProc_DeleteAll",
    )
    fun `label proc animation procs handles tiles and palettes follow the original`() {
        val ref = ReferenceData.fromEnvironment()
        val assets = System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets"
        val romFile = File(assets, "baserom.gba")
        assumeTrue(ref.available && romFile.isFile)
        val rom = RomView.ofRom(romFile.readBytes())
        val run = ref.run("ram_trace_prologue_levelup")
        val trace = run.watch("procs")
        val symbols = RamSymbols.fromEnvironment()
        val paletteAddress = symbols["gPaletteBuffer"].address - EWRAM
        val battleActor = symbols["gBattleActor"].address - EWRAM

        fun dumpName(step: Int) = "%s%05d".format(if (step % 10 == 0) "f" else "r", step)
        val hardware = BgHardware()
        val engine = ProcEngine()
        val layers = SpriteLayers()
        val player = SpriteAnimPlayer(
            { rom.u16(it) }, layers, WorldMapApHost(rom, hardware, OamBuffer()),
        )
        // The handle pool is shared with the announcement, which left handle 0 in use.
        val seed = run.region(dumpName(6518), "iwram")
        for ((index, handle) in player.handles.withIndex()) {
            val at = AP_ARRAY + index * AP_SIZE
            handle.pDefinition = u32(seed, at)
            handle.pFrameData = u32(seed, at + 4)
            handle.pAnimDataStart = u32(seed, at + 8)
            handle.pAnimDataCurrent = u32(seed, at + 12)
            handle.pCurrentObjData = u32(seed, at + 16)
            handle.pCurrentRotScale = u32(seed, at + 20)
            handle.frameTimer = u16(seed, at + 24).toShort().toInt()
            handle.frameInterval = u16(seed, at + 26)
            handle.subframeTimer = u16(seed, at + 28)
            handle.objLayer = u16(seed, at + 30)
            handle.gfxNeedsUpdate = seed[at + 32].toInt() != 0
            handle.rotScaleIndex = seed[at + 33].toInt() and 255
            handle.tileBase = u16(seed, at + 34)
            handle.pGraphics = u32(seed, at + 36)
        }
        val aps = ApProcs(engine, player)
        val labels = MapLevelUpGainLabels(engine, aps, hardware, rom)
        val panel = engine.start(ProcScript("ProcScr_ManimLevelUp", listOf(block())), 2)

        fun procs(step: Int) = ProcArrayDump.ofWatch(trace, step, emptyMap())
        val gains = mapOf(6563 to 0, 6584 to 1, 6605 to 3, 6626 to 5, 6647 to 6)
        var previous = procs(6519).findAll(AP_SCRIPT).map { apKey(it) }

        for (step in 6519..6729) {
            val at = procs(step)
            val ewram = run.region(dumpName(step), "ewram")
            val iwram = run.region(dumpName(step), "iwram")
            layers.clear()
            if (step > 6519) engine.run(engine.root(3))
            if (step == 6519) labels.start(0x200, 3, 1, panel)
            val stat = gains[step]
            if (stat != null) {
                val battle = BattleUnit()
                ewram.copyInto(battle.bytes, 0, battleActor, battleActor + BattleUnit.SIZE)
                val gain = when (stat) {
                    0 -> 1
                    1 -> battle.changeHP
                    3 -> battle.changeSkl
                    5 -> battle.changeLck
                    else -> battle.changeDef
                }
                // The caller's coordinates are not recorded: recover them from the new procs.
                val now = at.findAll(AP_SCRIPT).map { apKey(it) }
                val fresh = now.filter { it !in previous }
                val x = if (stat == 0) fresh.single()[1] + 18 else fresh.maxOf { it[1] }
                val y = if (stat == 0) fresh.single()[2] + 4 else fresh.maxOf { it[2] }
                labels.startAnim(x, y, stat, gain)
            }
            if (step == 6729) labels.end()
            engine.run(engine.root(2))

            // Label and color procs.
            val label = at.find(LABEL_SCRIPT)
            val mine = engine.find(labelScript(labels))
            if (step in 6519..6728) {
                assertNotNull(label, "label f$step")
                assertNotNull(mine, "mine f$step")
                val state = mine!!.state as MapLevelUpGainLabels.State
                assertEquals(
                    listOf(label!!.u16(0x2A), label.u16(0x2C), label.u16(0x2E)),
                    listOf(state.chr, state.pal, state.spriteLayer), "label fields f$step",
                )
            } else {
                assertNull(label, "label gone f$step")
                assertNull(mine, "mine gone f$step")
            }
            // The color child is created in the same call as the label, yet the capture first
            // shows it at f6520; compare from there on.
            if (step in 6520..6728) assertNotNull(at.find(COLOR_SCRIPT), "color f$step")

            // Animation procs and the handle pool.
            val original = at.findAll(AP_SCRIPT)
                .map { apKey(it) }.sortedBy { it.toString() }
            val actual = engine.procs.filter { it.script?.name == "ProcScr_ApProc" }.map {
                val state = it.state as ApProcs.State
                listOf(
                    0x03000000 + AP_ARRAY + player.handles.indexOf(state.handle) * AP_SIZE,
                    state.x, state.y,
                )
            }.sortedBy { it.toString() }
            assertEquals(original, actual, "animation procs f$step")
            for ((index, handle) in player.handles.withIndex()) {
                val base = AP_ARRAY + index * AP_SIZE
                assertEquals(u32(iwram, base), handle.pDefinition, "handle $index def f$step")
                if (handle.pDefinition == 0) continue
                val got = listOf(
                    handle.pFrameData, handle.pAnimDataStart, handle.pAnimDataCurrent,
                    handle.pCurrentObjData, handle.pCurrentRotScale, handle.frameTimer and 0xFFFF,
                    handle.frameInterval, handle.subframeTimer, handle.objLayer,
                    if (handle.gfxNeedsUpdate) 1 else 0, handle.rotScaleIndex, handle.tileBase,
                    handle.pGraphics,
                )
                val want = listOf(
                    u32(iwram, base + 4), u32(iwram, base + 8), u32(iwram, base + 12),
                    u32(iwram, base + 16), u32(iwram, base + 20), u16(iwram, base + 24),
                    u16(iwram, base + 26), u16(iwram, base + 28), u16(iwram, base + 30),
                    iwram[base + 32].toInt() and 255, iwram[base + 33].toInt() and 255,
                    u16(iwram, base + 34), u32(iwram, base + 36),
                )
                assertEquals(want, got, "handle $index f$step")
            }
            previous = at.findAll(AP_SCRIPT).map { apKey(it) }

            // Label palette banks 0x13 and 0x14. The cycled colors 9..15 are compared only on the
            // steps that write them (the clock is a multiple of 4); another proc restores the
            // static colors in between.
            if (step in 6520..6728) {
                val cycled = (step - 6519) % 4 == 0
                val colors = if (cycled) (0..15) + (16..31) else (0..8) + (16..24)
                for (i in colors) {
                    assertEquals(
                        u16(ewram, paletteAddress + (0x13 * 16 + i) * 2).toShort(),
                        hardware.palette[0x13 * 16 + i], "palette[$i] f$step",
                    )
                }
            }
            // OBJ tiles at the full snapshots.
            if (step % 10 == 0 && step in 6520..6720) {
                val vram = run.region(dumpName(step), "vram")
                val from = 0x10000 + 0x200 * 32
                val length = 0x5E * 32
                assertEquals(
                    vram.copyOfRange(from, from + length).toList(),
                    hardware.vram.copyOfRange(from, from + length).toList(), "OBJ tiles f$step",
                )
            }
        }
    }

    private fun labelScript(labels: MapLevelUpGainLabels): ProcScript = labels.script

    private fun apKey(proc: ProcArrayDump.Proc) = listOf(proc.u32(0x50), proc.u32(0x54), proc.u32(0x58))

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Int = u16(bytes, at) or (u16(bytes, at + 2) shl 16)

    private companion object {
        const val EWRAM = 0x02000000
        const val AP_ARRAY = 0x108
        const val AP_SIZE = 0x28
        const val AP_SCRIPT = 0x0859168C
        const val LABEL_SCRIPT = 0x089A3F4C
        const val COLOR_SCRIPT = 0x089A3F5C
    }
}
