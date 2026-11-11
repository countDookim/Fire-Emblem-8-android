package fe8.testing

import fe8.game.RomView
import fe8.game.worldmap.GmData
import fe8.game.worldmap.GmNodeIconDisplayProc
import fe8.game.worldmap.GmScreenProc
import fe8.game.worldmap.GmapCursorProc
import fe8.game.worldmap.WorldMapCursor
import fe8.game.worldmap.WorldMapNodeIcons
import fe8.game.worldmap.WorldMapScreen
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
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
 * `ProcScr_GmNodeIconDisplay` (`src/worldmap_screen2.c`) and `gProcScr_GmapCursor`
 * (`src/worldmap_cursor.c`) against `ram_trace_prologue`: the proc fields and script positions
 * of the `procs` watch at every step from 4901 to the step both are gone, the animation handle
 * in `sAPArray` and the OBJ palette color of the cursor at the dumps.
 *
 * `WorldMap_Init` starts both at 4901; the test starts them (after the screen proc, as in the
 * original) and then runs root trees 0 to 7 once per step. Inputs fed from the trace, since the
 * procs that produce them are not ported: `Skip`/`UnskipGmNodeIconDisplay` are called when the
 * traced `skip` bit changes (4948 and 9286, the `GmapRM` procs). `gGMData` is the `r04920`
 * dump; the test asserts that `sprite_disp`, `ix`, `iy` and bit 0 of every node state are the
 * same in every dump of the run.
 *
 * Limits: in the whole capture no node is valid, `merge_next_node` is never set, `sprite_disp`
 * is 0 and `ix`/`iy` are 0. So no sprite is drawn (checked: the sprite layers stay empty and the
 * draw hooks are never called), the drawing bodies and the cursor blink are not verified, and
 * `unk_38`/`unk_3c` only show that they are copied from zero. No OAM entry belongs to either
 * proc at the dumps. The cursor's palette color is compared at the dumps 5100-8100 only: later
 * the unit sprites use that bank.
 */
class WorldMapNodeIconCursorTest {
    private val ref = ReferenceData.fromEnvironment()

    @Test
    @VerifiesPort(
        "StartGmNodeIconDisplay", "GmapScreen2_Init", "GmapScreen2_Loop",
        "SkipGmNodeIconDisplay", "UnskipGmNodeIconDisplay", "GmapScreen2_Destruct",
        "NewGmapCursor", "GmapCursor_Init", "GmapCursor_Loop",
    )
    fun `node icon and cursor procs follow the original every step`() {
        val romFile = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
            "baserom.gba",
        )
        val names = ProcArrayDump.romNames()
        assumeTrue(ref.available && romFile.isFile && names.isNotEmpty(), "data not present")
        val run = ref.run("ram_trace_prologue")
        val trace = run.watch("procs")
        fun dump(step: Int) = ProcArrayDump.ofWatch(trace, step, names)
        val iconAddr = names.entries.first { it.value == "ProcScr_GmNodeIconDisplay" }.key
        val cursorAddr = names.entries.first { it.value == "gProcScr_GmapCursor" }.key
        val screenAddr = names.entries.first { it.value == "ProcScr_GMapScreen" }.key

        val rom = RomView.ofRom(romFile.readBytes())
        val hardware = BgHardware()
        hardware.setupBackgrounds()
        val gm = GmData(run.region("r04920", "iwram").copyOf(0x8000), RngService())
        val engine = ProcEngine()
        val layers = SpriteLayers()
        val cache = HashMap<Int, SpriteObject>()
        val host = object : ApHost {
            override fun objectAt(addr: Int) = cache.getOrPut(addr) { rom.objN(addr) }
            override fun sin(a: Int) = 0
            override fun cos(a: Int) = 0x1000
            override fun setObjAffine(index: Int, pa: Int, pb: Int, pc: Int, pd: Int) {}
            override fun register2dChrMove(src: Int, dstOffset: Int, width: Int, height: Int) {}
            override fun obj1dMap() = false
        }
        val player = SpriteAnimPlayer({ rom.u16(it) }, layers, host)
        engine.init()
        player.clearAll()
        var drawCalls = 0
        val icons = WorldMapNodeIcons(
            engine, player, gm,
            WorldMapNodeIcons.Hooks(
                drawNodeIcon = { _, _ -> drawCalls++ },
                drawMergeFlag = { drawCalls++ },
            ),
        )
        val cursors = WorldMapCursor(engine, hardware, rom, gm, layers)
        val screenProc = WorldMapScreen(engine, hardware, rom, gm).newMapScreen(5)
        val screen = screenProc.state as GmScreenProc

        val iconProc = icons.startGmNodeIconDisplay(5, 0, 3, 10, screen)
        val icon = iconProc.state as GmNodeIconDisplayProc
        val cursorProc = cursors.newGmapCursor(5, 0x12C0, 4, screen)
        val cursor = cursorProc.state as GmapCursorProc
        val handle = checkNotNull(icon.ap)
        val handleIndex = player.handles.indexOf(handle)

        fun s8(v: Int) = v.toByte().toInt()

        fun compare(step: Int) {
            val now = dump(step)
            val screenAddress = checkNotNull(now.find(screenAddr)).address
            val ti = checkNotNull(now.find(iconAddr), { "icon proc at $step" })
            assertEquals(ti.scrCur, iconProc.scrCur, "icon script position at $step")
            assertEquals(ti.u32(0x14), iconProc.rootIndex, "icon root tree at $step")
            assertEquals(
                listOf(
                    ti.u32(0x2C), ti.u8(0x30), ti.u8(0x31), ti.u8(0x32) and 1 != 0,
                    ti.u8(0x32) and 2 != 0, ti.u8(0x33), ti.u32(0x34),
                    SAP_ARRAY + 0x28 * handleIndex, screenAddress,
                ),
                listOf(
                    icon.chr, icon.pal, icon.unk31, icon.skip, icon.mergeNextNode, icon.nodeId,
                    icon.unk34, SAP_ARRAY + 0x28 * handleIndex, screenAddress,
                ),
                "icon fields at $step",
            )
            assertEquals(ti.u32(0x38), SAP_ARRAY + 0x28 * handleIndex, "icon ap at $step")
            val tc = checkNotNull(now.find(cursorAddr), { "cursor proc at $step" })
            assertEquals(tc.scrCur, cursorProc.scrCur, "cursor script position at $step")
            assertEquals(tc.u32(0x14), cursorProc.rootIndex, "cursor root tree at $step")
            assertEquals(
                listOf(
                    tc.u32(0x2C), tc.u8(0x30), tc.u8(0x31), s8(tc.u8(0x32)), s8(tc.u8(0x33)),
                    tc.u8(0x34), tc.u8(0x35), tc.u32(0x38), tc.u32(0x3C), tc.u32(0x40),
                    tc.u32(0x44), tc.u16(0x48), tc.u32(0x4C),
                ),
                listOf(
                    cursor.chr, cursor.pal, cursor.unk31, cursor.unk32, cursor.frameIdx,
                    cursor.unk34, cursor.unk35, cursor.unk38, cursor.unk3c, cursor.unk40,
                    cursor.unk44, cursor.unk48, screenAddress,
                ),
                "cursor fields at $step",
            )
        }

        fun compareDumps(step: Int) {
            val dumpName = (if (step % 300 == 0) "f%05d" else "r%05d").format(step)
            val iwram = run.region(dumpName, "iwram")
            val dumped = GmData(iwram.copyOf(0x8000), RngService())
            assertEquals(gm.spriteDisp, dumped.spriteDisp, "sprite_disp at $dumpName")
            assertEquals(gm.ix to gm.iy, dumped.ix to dumped.iy, "ix/iy at $dumpName")
            for (node in 0 until 0x1C) {
                assertEquals(0, dumped.nodeState(node) and 1, "node $node valid at $dumpName")
            }
            val base = HANDLE_BASE + 0x28 * handleIndex
            fun u16(at: Int) =
                (iwram[at].toInt() and 0xFF) or ((iwram[at + 1].toInt() and 0xFF) shl 8)
            fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
            assertEquals(
                listOf(
                    handle.pDefinition, handle.pFrameData, handle.pAnimDataStart,
                    handle.pAnimDataCurrent, handle.pCurrentObjData, handle.pCurrentRotScale,
                    handle.frameTimer, handle.frameInterval, handle.subframeTimer, handle.objLayer,
                    if (handle.gfxNeedsUpdate) 1 else 0, handle.rotScaleIndex, handle.tileBase,
                    handle.pGraphics,
                ),
                listOf(
                    u32(base), u32(base + 4), u32(base + 8), u32(base + 0xC), u32(base + 0x10),
                    u32(base + 0x14), u16(base + 0x18).toShort().toInt(), u16(base + 0x1A),
                    u16(base + 0x1C), u16(base + 0x1E), iwram[base + 0x20].toInt() and 0xFF,
                    iwram[base + 0x21].toInt() and 0xFF, u16(base + 0x22), u32(base + 0x24),
                ),
                "node flag animation handle at $dumpName",
            )
            // From f08400 on the map unit sprites (8100-9280) rewrite OBJ palette 4.
            if (step % 300 != 0 || step > LAST_PALETTE_STEP) return
            hardware.flushBackgrounds()
            val pal = run.region(dumpName, "pal")
            val index = 0x100 + 4 * 16 + 14
            assertEquals(
                (pal[2 * index].toInt() and 0xFF) or ((pal[2 * index + 1].toInt() and 0xFF) shl 8),
                hardware.paletteRam[index].toInt() and 0xFFFF,
                "cursor blink color at $dumpName",
            )
        }

        compare(4901)
        var step = 4901
        var ended = false
        while (step < 9330) {
            step++
            val now = dump(step)
            val ti = now.find(iconAddr)
            if (ti == null) {
                assertEquals(null, now.find(cursorAddr), "cursor gone with the icon at $step")
                engine.end(iconProc)
                engine.end(cursorProc)
                assertEquals(0, handle.pDefinition, "flag animation freed at $step")
                ended = true
                break
            }
            val skip = ti.u8(0x32) and 1 != 0
            if (skip && !icon.skip) icons.skipGmNodeIconDisplay(icon)
            if (!skip && icon.skip) icons.unskipGmNodeIconDisplay(icon)
            layers.clear()
            for (tree in 0..7) engine.run(engine.root(tree))
            assertEquals(0, layers.allocIt, "no sprite drawn at $step")
            compare(step)
            if (step % 30 == 0) compareDumps(step)
        }
        assertEquals(true, ended, "the capture ends both procs")
        assertEquals(0, drawCalls, "no node or flag is drawn in the capture")
        val after = run.region("r09330", "iwram")
        assertEquals(
            0,
            (after[HANDLE_BASE + 0x28 * handleIndex].toInt() and 0xFF) or
                (after[HANDLE_BASE + 0x28 * handleIndex + 1].toInt() and 0xFF),
            "flag animation freed in the dump",
        )
    }

    private companion object {
        /** `sAPArray` (`0x03000108`). */
        const val SAP_ARRAY = 0x03000108
        const val HANDLE_BASE = 0x108

        /** Last `f` dump whose OBJ palette 4 color 14 is still the cursor's. */
        const val LAST_PALETTE_STEP = 8100
    }
}
