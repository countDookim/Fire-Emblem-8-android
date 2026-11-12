package fe8.game.worldmap

import fe8.game.RomView
import gbafe.proc.Proc
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.call2
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.mark
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.render.BgHardware
import kotlin.math.abs

/** The fields of `struct GmRouteProc` (`ProcScr_GMapRoute`). */
public class GmRouteProc {
    /** `chr` (+0x2C): VRAM byte offset of the route tiles. */
    public var chr: Int = 0

    /** `pal` (+0x30): palette bank of the route tiles. */
    public var pal: Int = 0

    /** `flags` (+0x31): bit 0 redraw all, bit 1 rebuild the buffer, bit 2 enabled. */
    public var flags: Int = 0

    /** `unk_32` (+0x32): bit 0 while a route transition runs. */
    public var unk32: Int = 0

    /** `unk_33` (+0x33, `u8`). */
    public var unk33: Int = 0

    /** `bgA` (+0x34): the BG the route is drawn on. */
    public var bgA: Int = 0

    /** `bgB` (+0x35): the BG the transition draws on. */
    public var bgB: Int = 0

    /** `x1` (+0x36, `s8`): the current camera tile column. */
    public var x1: Int = 0

    /** `y1` (+0x37, `s8`): the current camera tile row. */
    public var y1: Int = 0

    /** `x2` (+0x38, `s8`): the previous camera tile column. */
    public var x2: Int = 0

    /** `y2` (+0x39, `s8`): the previous camera tile row. */
    public var y2: Int = 0

    /** `bgPriority` (+0x3A). */
    public var bgPriority: Int = 0

    /** `unk_3c` (+0x3C, `s16`): transition length in frames. */
    public var unk3c: Int = 0

    /** `unk_3e` (+0x3E, `s16`): transition frame. */
    public var unk3e: Int = 0

    /** `pOpenPaths` (+0x40): address of `gGMData.openPaths`. */
    public var pOpenPaths: Int = 0
}

/**
 * The drawn paths of the world map (`src/worldmap_path.c`, `ProcScr_GMapRoute`): a proc under
 * `ProcScr_GMapScreen` that keeps a BG tilemap (BG2) in step with the scrolling map by copying
 * the visible part of the route tile buffer `gUnknown_02019D00`.
 *
 * @param engine the proc scheduler
 * @param hardware BG tilemap buffers, BG sync flags and the LCD registers
 * @param rom the ROM (`gWMPathData` and the path tile patches)
 * @param gmData `gGMData`
 * @param playSound `PlaySoundEffect(song)`
 */
public class WorldMapRoute(
    private val engine: ProcEngine,
    private val hardware: BgHardware,
    private val rom: RomView,
    private val gmData: GmData,
    private val playSound: (song: Int) -> Unit = {},
) {
    private val pathData = WmPathData(rom)

    /** `gUnknown_02019D00`: the 60 x 40 route tilemap. */
    public val buffer: ShortArray = ShortArray(BUFFER_ENTRIES)

    /**
     * `ProcScr_GMapRoute`.
     *
     * Transcribed from src/worldmap_path.c: ProcScr_GMapRoute (not verified: script order
     * checked against the `procs` capture in WorldMapRouteTest, which cannot tag a script)
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_GMapRoute",
        listOf(
            name("GmapRoute"),
            mark(PROC_MARK_WMSTUFF),
            setEndCb(ProcFunc { nullsub38() }),
            sleep(0),
            call(ProcFunc { sub80BBEB8(state(it), it) }),
            label(LABEL_0),
            repeat(ProcFunc { sub80BBF60(it) }),
            goto(LABEL_2),
            label(LABEL_1),
            call2(ProcBoolFunc { mapRouteStartTransition(state(it)) }),
            call2(ProcBoolFunc { sub80BC0F4(state(it), it) }),
            call2(ProcBoolFunc { mapRouteEnableBgSyncs(state(it)) }),
            repeat(ProcFunc { mapRouteTransitionLoop(it) }),
            call2(ProcBoolFunc { mapRouteTransitionEnd(state(it)) }),
            goto(LABEL_0),
            label(LABEL_2),
            end(),
        ),
    ) { GmRouteProc() }

    private fun state(proc: Proc): GmRouteProc = proc.state as GmRouteProc

    private fun parent(proc: Proc): GmScreenProc =
        checkNotNull(proc.parent).state as GmScreenProc

    /**
     * Ported from src/worldmap_path.c: StartGMapRoute
     *
     * Starts the route proc under [parent] (`gGMData.openPaths` is the path list).
     */
    public fun startGMapRoute(parent: Proc, chr: Int, pal: Int): Proc {
        val proc = engine.start(script, parent)
        val s = state(proc)
        s.chr = chr
        s.pal = pal
        s.pOpenPaths = OPEN_PATHS_ADDRESS
        return proc
    }

    /**
     * Transcribed from src/worldmap_path.c: sub_80BBBF4 (not verified: the Prologue has no open
     * paths, so the route buffer is all zero in every dump)
     *
     * Copies the tile patches at ROM address [data] (x, y, width, rows, then `width * rows`
     * halfwords; terminated by 0xFF) into a tilemap [buf] [size] entries wide, adding [oam2].
     */
    public fun sub80BBBF4(data: Int, buf: ShortArray, size: Int, oam2: Int) {
        var at = data
        while (rom.u8(at) != 0xFF) {
            var dst = rom.u8(at + 1) * size + rom.u8(at)
            val numTiles = rom.u8(at + 2)
            val check = rom.u8(at + 3)
            at += 4
            for (i in 0 until check) {
                for (j in 0 until numTiles) {
                    buf[dst] = (rom.u16(at) + oam2).toShort()
                    at += 2
                    dst++
                }
            }
        }
    }

    /**
     * Transcribed from src/worldmap_path.c: sub_80BBC54 (not verified: only the buffer clear is
     * observable, the open path list is empty in the Prologue)
     *
     * Rebuilds the route tile buffer from the open paths' tile patches.
     */
    public fun sub80BBC54(proc: GmRouteProc) {
        val oam2Base = ((proc.chr / CHR_SIZE) or (proc.pal shl 12)) and 0xFFFF
        buffer.fill(0)
        for (i in 0 until gmData.openPathsLength) {
            val path = gmData.openPath(i)
            sub80BBBF4(pathData.gfxData(path), buffer, 60, oam2Base)
        }
    }

    /**
     * Transcribed from src/worldmap_path.c: sub_80BBCC8 (not verified: the route buffer is all
     * zero in the Prologue, so the BG2 tilemap cannot tell a wrong copy from none)
     *
     * Copies the 32 x 21 visible tiles at tile position ([a], [b]) from the route buffer into the
     * tilemap of BG [bg], wrapping at 32 columns.
     */
    public fun sub80BBCC8(a: Int, b: Int, bg: Int) {
        val terminator = if (b < 19) b + 21 else 40
        val unkA = a and 0x1F
        val unkB = 0x20 - unkA
        val bgBuf = hardware.mapBuffer(bg)
        var buf = b * 60 + a
        if (unkB >= 0x1F) {
            for (i in b until terminator) {
                copy(buffer, buf, bgBuf, (i % 0x20) * 0x20 + unkA, 0x1F)
                buf += 60
            }
        } else {
            for (i in b until terminator) {
                val row = (i and 0x1F) * 0x20
                copy(buffer, buf, bgBuf, row + unkA, unkB)
                copy(buffer, buf + unkB, bgBuf, row, 0x1F - unkB)
                buf += 60
            }
        }
    }

    /**
     * Transcribed from src/worldmap_path.c: sub_80BBDA4 (not verified: the route buffer is all
     * zero in the Prologue and the camera never moves one tile at a time)
     *
     * Draws the one new column ([a1] against [r4]) and row ([r8] against [r9]) that scrolling by
     * one tile exposes into the tilemap of BG [sp20].
     */
    public fun sub80BBDA4(a1: Int, r8: Int, r4: Int, r9: Int, sp20: Int) {
        val bgBuf = hardware.mapBuffer(sp20)
        val r8u = r8 and 0xFFFF
        if (a1 != r4) {
            val r1: Int
            val r2: Int
            if (a1 > r4) {
                r1 = (a1 + 0x1E) and 0xFFFF
                r2 = r8u
            } else {
                r1 = a1 and 0xFFFF
                r2 = r8u
            }
            val r7 = r1 and 0x1F
            var p = r2 * 0x3C + r1
            for (i in 0 until 0x15) {
                bgBuf[((r2 + i) and 0x1F) * 0x20 + r7] = buffer[p]
                p += 0x3C
            }
        }
        if (r8 != r9) {
            val r1: Int
            val r2: Int
            if (r8 > r9) {
                r1 = a1
                r2 = (r8u + 0x14) and 0xFFFF
            } else {
                r1 = a1
                r2 = r8u
            }
            val r7 = r1 and 0x1F
            val r3 = r2 and 0x1F
            val p = r2 * 0x3C + r1
            val r6 = (0x20 - r7) and 0xFFFF
            if (r6 > 0x1E) {
                copy(buffer, p, bgBuf, r3 * 0x20 + r7, 0x1F)
            } else {
                copy(buffer, p, bgBuf, r3 * 0x20 + r7, r6)
                copy(buffer, p + r6, bgBuf, r3 * 0x20, 0x1F - r6)
            }
        }
    }

    /**
     * Transcribed from src/worldmap_path.c: nullsub_38 (not verified: it does nothing)
     */
    public fun nullsub38() {
    }

    /**
     * Ported from src/worldmap_path.c: sub_80BBEB8
     *
     * Route proc init: enables it, reads the BG1 priority and the screen position and builds the
     * tile buffer.
     */
    public fun sub80BBEB8(s: GmRouteProc, proc: Proc) {
        s.flags = 4
        s.unk32 = 0
        s.unk33 = 0xFF
        s.bgA = 2
        s.bgB = 1
        s.bgPriority = hardware.lcd.bgCnt[1] and 3
        s.x1 = parent(proc).x.toByte().toInt()
        s.y1 = parent(proc).y.toByte().toInt()
        s.y2 = -2
        s.x2 = -2
        sub80BBC54(s)
        s.flags = s.flags or 3
    }

    /**
     * Ported from src/worldmap_path.c: sub_80BBF60
     *
     * Per frame: follows the screen position, draws the newly visible column and row (or all if
     * it moved two or more tiles), and honours the rebuild (bit 1) and redraw-all (bit 0) flags.
     * The proc fields and flags are verified per step; which draw runs and the BG2 scroll write
     * are not observable in the Prologue (zero route buffer, BG2 scroll reset by `GmapRm`).
     */
    public fun sub80BBF60(proc: Proc) {
        val s = state(proc)
        if (s.flags and 4 == 0) return
        s.x2 = s.x1
        s.y2 = s.y1
        val x = parent(proc).x.toShort().toInt()
        val y = parent(proc).y.toShort().toInt()
        s.x1 = (x / 8).toByte().toInt()
        s.y1 = (y / 8).toByte().toInt()
        if (s.x1 != s.x2 || s.y1 != s.y2) {
            if (abs(s.x1 - s.x2) >= 2 || abs(s.y1 - s.y2) >= 2) {
                sub80BBCC8(s.x1, s.y1, s.bgA)
            } else {
                sub80BBDA4(s.x1, s.y1, s.x2, s.y2, s.bgA)
            }
            hardware.enableBgSyncByMask(1 shl s.bgA)
        }
        if (s.flags and 2 != 0) {
            sub80BBC54(s)
            s.flags = s.flags and 2.inv()
        }
        if (s.flags and 1 != 0) {
            sub80BBCC8(s.x1, s.y1, s.bgA)
            hardware.enableBgSyncByMask(1 shl s.bgA)
            s.flags = s.flags and 1.inv()
        }
        hardware.lcd.setBgPosition(s.bgA, x and 0x1FF, y and 0xFF)
    }

    /**
     * Transcribed from src/worldmap_path.c: MapRoute_StartTransition (not verified: the
     * transition never starts in the Prologue capture)
     */
    public fun mapRouteStartTransition(s: GmRouteProc): Boolean {
        hardware.fill(hardware.mapBuffer(s.bgB), 0)
        hardware.enableBgSyncByMask(1 shl s.bgB)
        playSound(SONG_310)
        return false
    }

    /**
     * Transcribed from src/worldmap_path.c: sub_80BC0F4 (not verified: the transition never
     * starts in the Prologue capture)
     */
    public fun sub80BC0F4(s: GmRouteProc, proc: Proc): Boolean {
        val lcd = hardware.lcd
        lcd.setBlendConfig(1, 0, 0x10, 0)
        lcd.bldcnt = lcd.bldcnt and 0x3F.inv() or (1 shl s.bgB)
        lcd.bldcnt = lcd.bldcnt and 0x3F00.inv() or BLDCNT_TGT2_BG3
        sub80BBC54(s)
        sub80BBCC8(s.x1, s.y1, s.bgB)
        val p = parent(proc)
        lcd.setBgPosition(s.bgB, p.x.toShort().toInt() and 0x1FF, p.y.toShort().toInt() and 0xFF)
        return false
    }

    /**
     * Transcribed from src/worldmap_path.c: MapRoute_EnableBGSyncs (not verified: the transition
     * never starts in the Prologue capture)
     */
    public fun mapRouteEnableBgSyncs(s: GmRouteProc): Boolean {
        hardware.lcd.bgCnt[s.bgB] =
            (hardware.lcd.bgCnt[s.bgB] and 3.inv()) or (hardware.lcd.bgCnt[3] and 3)
        hardware.enableBgSyncByMask((1 shl s.bgB) or (1 shl s.bgA))
        return false
    }

    /**
     * Transcribed from src/worldmap_path.c: MapRoute_TransitionLoop (not verified: the
     * transition never starts in the Prologue capture)
     */
    public fun mapRouteTransitionLoop(proc: Proc) {
        val s = state(proc)
        s.unk3e = (s.unk3e + 1).toShort().toInt()
        if (s.unk3e < s.unk3c) {
            val coeff = (s.unk3e * 0x10) / s.unk3c
            hardware.lcd.setBlendConfig(1, coeff, 0x10 - coeff, 0)
        } else {
            hardware.mapBuffer(s.bgB).copyInto(hardware.mapBuffer(s.bgA), 0, 0, 0x400)
            hardware.enableBgSyncByMask(1 shl s.bgA)
            engine.breakProc(proc)
        }
    }

    /**
     * Transcribed from src/worldmap_path.c: MapRoute_TransitionEnd (not verified: the
     * transition never starts in the Prologue capture)
     */
    public fun mapRouteTransitionEnd(s: GmRouteProc): Boolean {
        val lcd = hardware.lcd
        lcd.setBgPosition(s.bgB, 0, 0)
        hardware.fill(hardware.mapBuffer(s.bgB), 0)
        lcd.bgCnt[s.bgB] = (lcd.bgCnt[s.bgB] and 3.inv()) or s.bgPriority
        hardware.enableBgSyncByMask((1 shl s.bgA) or (1 shl s.bgB))
        lcd.setBlendConfig(0, 0x10, 0, 0)
        lcd.bldcnt = lcd.bldcnt and 0x3F.inv()
        lcd.bldcnt = lcd.bldcnt and 0x3F00.inv()
        s.unk32 = s.unk32 and 1.inv()
        return false
    }

    private fun copy(src: ShortArray, from: Int, dst: ShortArray, to: Int, count: Int) {
        src.copyInto(dst, to, from, from + count)
    }

    /** Layout constants. */
    public companion object {
        /** `PROC_MARK_WMSTUFF`. */
        public const val PROC_MARK_WMSTUFF: Int = 8

        /** `SONG_310`. */
        public const val SONG_310: Int = 0x310

        /** Address of `gGMData.openPaths`, the `pOpenPaths` field of the route proc. */
        public const val OPEN_PATHS_ADDRESS: Int = 0x03000000 + GmData.BASE + 0xA4

        private const val LABEL_0 = 0
        private const val LABEL_1 = 1
        private const val LABEL_2 = 2
        private const val CHR_SIZE = 0x20
        private const val BUFFER_ENTRIES = 60 * 40
        private const val BLDCNT_TGT2_BG3 = 1 shl 11
    }
}
