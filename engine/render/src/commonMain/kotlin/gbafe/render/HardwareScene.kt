package gbafe.render

import gba.render.RenderState
import gba.render.SpriteBatch
import gba.render.TileSheets

/**
 * Builds the semantic [RenderState] + [TileSheets] the [SoftwareRenderer] draws from raw GBA display
 * hardware state (VRAM, palette RAM, OAM, I/O registers), as the game leaves it after its VBlank sync
 * ([BgHardware] + [OamBuffer]) or as an mGBA dump holds it. Video modes 0..2 (text and affine
 * backgrounds); bitmap modes 3..5 are not supported. [HardwareScene] allocates fresh sheets
 * on every load; a [HardwareSceneLoader] reuses its own (allocation-free, for the per-frame device path).
 *
 * Sheet ids: 0..3 = BG character base blocks (VRAM 0x0000, 0x4000, 0x8000, 0xC000, each up to the end
 * of BG VRAM), 4 = OBJ tiles (VRAM 0x10000).
 */
public object HardwareScene {
    /** Sheet id of the OBJ tile area. */
    public const val OBJ_SHEET: Int = 4

    /**
     * Fills [state] and [sheets] (fresh arrays) from one frame's hardware state: [vram] (0x18000 bytes),
     * [pal] (palette RAM, 0x400 bytes), [oam] (0x400 bytes) and [io] (I/O registers from 0x04000000).
     */
    public fun load(state: RenderState, sheets: TileSheets, vram: ByteArray, pal: ByteArray, oam: ByteArray, io: ByteArray) {
        HardwareSceneLoader().load(state, sheets, vram, pal, oam, io)
    }

    /** Like [HardwareSceneLoader.load] from the game's synced display state, into fresh sheet arrays. */
    public fun load(state: RenderState, sheets: TileSheets, hw: BgHardware, oam: OamBuffer, io: ByteArray? = null) {
        HardwareSceneLoader().load(state, sheets, hw, oam, io)
    }
}

/**
 * Allocation-free [HardwareScene] loader: owns the tile sheet arrays it hands to [TileSheets] and the
 * scratch register/palette/OAM buffers, so one instance can load every frame without garbage.
 */
public class HardwareSceneLoader {
    private val bgSheets = Array(4) { ByteArray(0x10000 - it * 0x4000) }
    private val objSheet = ByteArray(0x8000)
    private val lcd = LcdControl()
    private val palBytes = ByteArray(0x400)
    private val oamBytes = ByteArray(0x400)
    private val ioBytes = ByteArray(0x60)

    private val objSizes = arrayOf(
        intArrayOf(8, 8, 16, 16, 32, 32, 64, 64),
        intArrayOf(16, 8, 32, 8, 32, 16, 64, 32),
        intArrayOf(8, 16, 8, 32, 16, 32, 32, 64),
    )

    /**
     * Fills [state] and [sheets] (with this loader's reused sheet arrays) from one frame's hardware state: [vram] (0x18000 bytes), [pal] (palette
     * RAM, 0x400 bytes), [oam] (0x400 bytes) and [io] (I/O registers from 0x04000000).
     */
    public fun load(state: RenderState, sheets: TileSheets, vram: ByteArray, pal: ByteArray, oam: ByteArray, io: ByteArray) {
        state.reset()
        val dispcnt = u16(io, 0)
        for (b in 0 until 4) {
            vram.copyInto(bgSheets[b], 0, b * 0x4000, 0x10000)
            sheets[b] = bgSheets[b]
        }
        vram.copyInto(objSheet, 0, 0x10000, 0x18000)
        sheets[HardwareScene.OBJ_SHEET] = objSheet
        for (i in 0 until 256) {
            state.palettes.bg[i] = u16(pal, 2 * i).toShort()
            state.palettes.obj[i] = u16(pal, 0x200 + 2 * i).toShort()
        }
        lcd.readRegisters(io)
        lcd.applyTo(state)
        for (bg in 0 until 4) {
            val layer = state.layers[bg]
            val screenBase = ((lcd.bgCnt[bg] shr 8) and 0x1F) * 0x800
            if (layer.affine) { // one byte per entry, row-major
                for (ty in 0 until layer.heightTiles) for (tx in 0 until layer.widthTiles) {
                    val entryOffset = screenBase + ty * layer.widthTiles + tx
                    layer.setEntry(tx, ty, if (entryOffset < 0x10000) vram[entryOffset].toInt() and 0xFF else 0)
                }
                continue
            }
            for (ty in 0 until layer.heightTiles) for (tx in 0 until layer.widthTiles) {
                var block = 0
                if (tx >= 32) block += 1
                if (ty >= 32) block += if (layer.widthTiles == 64) 2 else 1
                val entryOffset = screenBase + block * 0x800 + ((ty and 31) * 32 + (tx and 31)) * 2
                layer.setEntry(tx, ty, if (entryOffset + 1 < 0x10000) u16(vram, entryOffset) else 0)
            }
        }
        val sprites = state.sprites
        for (m in 0 until SpriteBatch.AFFINE_MATRICES) {
            sprites.setAffine(m, s16(oam, m * 32 + 6), s16(oam, m * 32 + 14), s16(oam, m * 32 + 22), s16(oam, m * 32 + 30))
        }
        if (dispcnt and 0x1000 == 0) return
        for (i in 0 until 128) {
            val attr0 = u16(oam, i * 8)
            val attr1 = u16(oam, i * 8 + 2)
            val attr2 = u16(oam, i * 8 + 4)
            val affine = attr0 and 0x100 != 0
            if (!affine && attr0 and 0x200 != 0) continue // disabled
            val shape = (attr0 shr 14) and 3
            if (shape == 3) continue
            val size = (attr1 shr 14) and 3
            val width = objSizes[shape][size * 2]
            val height = objSizes[shape][size * 2 + 1]
            val doubleSize = affine && attr0 and 0x200 != 0
            var y = attr0 and 0xFF
            if (y + (if (doubleSize) 2 * height else height) > 256) y -= 256
            var x = attr1 and 0x1FF
            if (x >= 256) x -= 512
            var flags = 0
            if (affine) flags = flags or SpriteBatch.AFFINE
            if (doubleSize) flags = flags or SpriteBatch.DOUBLE_SIZE
            if (!affine && attr1 and 0x1000 != 0) flags = flags or SpriteBatch.HFLIP
            if (!affine && attr1 and 0x2000 != 0) flags = flags or SpriteBatch.VFLIP
            if (attr0 and 0x1000 != 0) flags = flags or SpriteBatch.MOSAIC
            if (attr0 and 0x2000 != 0) flags = flags or SpriteBatch.BPP8
            when ((attr0 shr 10) and 3) {
                1 -> flags = flags or SpriteBatch.MODE_SEMI
                2 -> flags = flags or SpriteBatch.MODE_WINDOW
                3 -> continue // prohibited mode
            }
            sprites.add(x, y, width, height, HardwareScene.OBJ_SHEET, attr2 and 0x3FF, (attr2 shr 12) and 0xF, (attr2 shr 10) and 3, flags, (attr1 shr 9) and 0x1F)
        }
    }

    /**
     * Fills [state] and [sheets] from the game's synced display state ([BgHardware.vram],
     * [BgHardware.paletteRam], [OamBuffer.oam]) and the I/O registers [io] as `FlushLCDControl` last wrote
     * them (null: flushes [BgHardware.lcd] now). Allocates nothing.
     */
    public fun load(state: RenderState, sheets: TileSheets, hw: BgHardware, oam: OamBuffer, io: ByteArray? = null) {
        val regs = io ?: ioBytes.also { hw.lcd.flushTo(it) }
        load(state, sheets, hw.vram, bytes(hw.paletteRam, palBytes), bytes(oam.oam, oamBytes), regs)
    }

    private fun bytes(shorts: ShortArray, dstBytes: ByteArray): ByteArray {
        for (i in shorts.indices) {
            dstBytes[2 * i] = shorts[i].toByte()
            dstBytes[2 * i + 1] = (shorts[i].toInt() shr 8).toByte()
        }
        return dstBytes
    }

    private fun u16(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun s16(bytes: ByteArray, offset: Int): Int = u16(bytes, offset).toShort().toInt()
}
