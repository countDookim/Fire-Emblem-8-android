package fe8.testing

import fe8.game.RomView
import fe8.game.ui.HelpPromptSprState
import fe8.game.ui.HelpPromptSprite
import gba.core.VerifiesPort
import gba.testkit.GbaMem
import gba.testkit.ReferenceData
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.block
import gbafe.render.BgHardware
import gbafe.render.OamBuffer
import gbafe.render.SpriteLayers
import java.io.File
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * `PC-11d` against `ram_trace_title_continue` (prompt proc created `f1049`, checked at `f1093`)
 * and `ram_trace_title_resume` (created `f1017`, checked at `f1031`): the proc's row (script
 * position, parent, `xDisplay` / `yDisplay` / `tileref`) every frame, the layer 0 sprite pool,
 * `gOam`, the hardware OAM and the `Pal_MapBattleInfoNum` bank.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(180)
class HelpPromptSpriteDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private var rom: RomView? = null

    @AfterAll
    fun releaseReferenceData() {
        rom = null
    }

    private class Rig(val run: ReferenceData.Run, val rom: RomView) {
        val engine = ProcEngine()
        val hardware = BgHardware()
        val layers = SpriteLayers()
        val oam = OamBuffer()
        val prompt = HelpPromptSprite(engine, layers, hardware, rom)
        val parent: Proc = engine.start(
            ProcScript("ProcScr_SaveMenu", listOf(block())) { Any() },
            3,
        )

        fun runFrame() {
            oam.syncHi()
            layers.clear()
            oam.hiPut = 0
            engine.run(engine.root(3))
            layers.pushLayerObjects(HelpPromptSprite.PROMPT_LAYER) { entry ->
                oam.putHi(entry.oam1, entry.oam0, entry.obj!!.data, entry.oam2)
            }
        }
    }

    private fun rig(name: String, step: Int): Rig {
        assumeTrue(ref.available, "mGBA reference data not present (run tools/setup.sh)")
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        assumeTrue(romFile.isFile, "ROM not present")
        assumeTrue(ProcArrayDump.romNames().isNotEmpty(), "decomp nm file not present")
        val run = ref.run(name)
        assumeTrue(run.dumps.containsKey("r%05d".format(step)), "$name f$step not present")
        val view = rom ?: RomView.ofRom(romFile.readBytes()).also { rom = it }
        return Rig(run, view)
    }

    private fun check(name: String, created: Int, last: Int, endCheck: String) {
        val r = rig(name, created)
        val names = ProcArrayDump.romNames()
        val watch = r.run.watch("procs")
        assertNull(
            ProcArrayDump.ofWatch(watch, created - 1, names).find(PROC),
            "$name no prompt proc at f${created - 1}",
        )
        var proc: Proc? = null
        for (step in created..last) {
            if (step == created) {
                proc = r.prompt.startHelpPromptSprite(X, Y, PALID, r.parent)
                assertEquals(proc, r.prompt.startHelpPromptSprite(X, Y, PALID, r.parent))
            }
            r.runFrame()
            val dump = ProcArrayDump.ofWatch(watch, step, names)
            val row = dump.find(PROC)
            assertNotNull(row, "$name proc row f$step")
            assertEquals(row!!.scrCur, proc!!.scrCur, "$name scrCur f$step")
            val parentRow = dump.live.first { it.address == row.u32(PARENT) }
            assertEquals("ProcScr_SaveMenu", parentRow.name, "$name parent f$step")
            val state = proc.state as HelpPromptSprState
            assertEquals(
                listOf(row.u32(0x2C), row.u32(0x30), row.u32(0x34)),
                listOf(state.xDisplay, state.yDisplay, state.tileref),
                "$name proc fields f$step",
            )
            val dumpName = listOf("r%05d", "f%05d").map { it.format(step) }
                .firstOrNull { r.run.dumps.containsKey(it) } ?: continue
            val ew = r.run.region(dumpName, "ewram")
            val iw = r.run.region(dumpName, "iwram")
            assertEquals(poolLayer0(ew, iw), ownLayer0(r.layers), "$name layer 0 pool f$step")
            assertEquals(gOam(iw), ownOam(r.oam), "$name gOam prompt entries f$step")
        }
        // Palette bank and hardware OAM at the capture step.
        val ew = r.run.region(endCheck, "ewram")
        for (i in 0 until 16) {
            val idx = (PALID + 0x10) * 16 + i
            assertEquals(
                u16(ew, GPALETTE - GbaMem.EWRAM + idx * 2),
                r.hardware.palette[idx].toInt() and 0xFFFF,
                "$name palette entry $idx",
            )
        }
        val hw = r.run.region(endCheck, "oam")
        val hwObjects = (0 until OamBuffer.OBJ_COUNT).map {
            listOf(u16(hw, it * 8), u16(hw, it * 8 + 2), u16(hw, it * 8 + 4))
        }
        val own = ownOam(r.oam)
        assertEquals(2, own.size)
        for (o in own) assertEquals(true, o in hwObjects, "$name hardware OAM $endCheck has $o")
    }

    @Test
    @VerifiesPort("StartHelpPromptSprite", "HelpPrompt_OnIdle")
    fun `continue run prompt proc and OAM match f1049 to f1093`() {
        check("ram_trace_title_continue", 1049, 1093, "f01093")
    }

    @Test
    @VerifiesPort("StartHelpPromptSprite", "HelpPrompt_OnIdle")
    fun `resume run prompt proc and OAM match f1017 to f1031`() {
        check("ram_trace_title_resume", 1017, 1031, "f01031")
    }

    /** Tracked prompt entries of layer 0 in the capture's `sSpritePool`: oam1, oam0, oam2. */
    private fun poolLayer0(ewram: ByteArray, iwram: ByteArray): List<List<Int>> {
        val allocIt = GbaMem.u32(iwram, GbaMem.IWRAM, ADDR_G_SPRITE_ALLOC_IT)
        val poolEnd = ADDR_S_SPRITE_POOL + (allocIt - ADDR_S_SPRITE_POOL)
        val out = ArrayList<List<Int>>()
        var addr = u32(ewram, ADDR_S_SPRITE_LAYERS - GbaMem.EWRAM)
        while (addr in ADDR_S_SPRITE_POOL until poolEnd) {
            val base = addr - GbaMem.EWRAM
            if (u32(ewram, base + 0x0C) == HelpPromptSprite.SPRITE_META_HELP) {
                out += listOf(s16(ewram, base + 4), s16(ewram, base + 6), u16(ewram, base + 8))
            }
            addr = u32(ewram, base)
        }
        return out
    }

    /** Pool entries between head 0 and head 1, as the capture's walk stops at a layer head. */
    private fun ownLayer0(layers: SpriteLayers): List<List<Int>> {
        val out = ArrayList<List<Int>>()
        var e = layers.layers[HelpPromptSprite.PROMPT_LAYER].next
        while (e != null && e in layers.pool) {
            if (e.obj!!.id == HelpPromptSprite.SPRITE_META_HELP) {
                out += listOf(e.oam1, e.oam0, e.oam2)
            }
            e = e.next
        }
        return out
    }

    private fun isPrompt(a0: Int, a1: Int, a2: Int): Boolean =
        a0 and 0xFF == Y && a1 and 0x1FF in setOf(X, X + 32) && a2 and 0x3FF in setOf(0xB, 0xF)

    private fun gOam(iwram: ByteArray): List<List<Int>> {
        val base = ADDR_G_OAM - GbaMem.IWRAM
        return (0 until OamBuffer.OBJ_COUNT).map {
            listOf(u16(iwram, base + it * 8), u16(iwram, base + it * 8 + 2),
                u16(iwram, base + it * 8 + 4))
        }.filter { isPrompt(it[0], it[1], it[2]) }
    }

    private fun ownOam(oam: OamBuffer): List<List<Int>> =
        (0 until oam.hiPut / 4).map {
            listOf(
                oam.buffer[it * 4].toInt() and 0xFFFF,
                oam.buffer[it * 4 + 1].toInt() and 0xFFFF,
                oam.buffer[it * 4 + 2].toInt() and 0xFFFF,
            )
        }

    private companion object {
        const val PROC = "gProcScr_HelpPromptSpr"
        const val PARENT = 0x14
        const val X = 0xC0
        const val Y = 8
        const val PALID = 8
        const val GPALETTE = 0x020228A8
        const val ADDR_S_SPRITE_POOL = 0x02029D8C
        const val ADDR_S_SPRITE_LAYERS = 0x0202A58C
        const val ADDR_G_OAM = 0x03003140
        const val ADDR_G_SPRITE_ALLOC_IT = 0x03004970

        fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF
        fun u16(b: ByteArray, o: Int): Int = u8(b, o) or (u8(b, o + 1) shl 8)
        fun s16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()
        fun u32(b: ByteArray, o: Int): Int = u16(b, o) or (u16(b, o + 2) shl 16)
    }
}
