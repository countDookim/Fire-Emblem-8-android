package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.put32
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u16
import fe8.game.bm.StructCodec.u32
import fe8.game.bm.StructCodec.u8

/**
 * The map sprite handle list of src/bmudisp.c (`gSMSHandleArray`, `gSMSHandleIt`): one
 * `SMSHandle` per visible unit and trap sprite, sorted by descending `yDisplay`.
 *
 * [handles] mirrors the EWRAM bytes (100 handles of 12 bytes: `pNext` u32, `xDisplay`, `yDisplay`,
 * `oam2Base` u16, pad, `config` s8); `pNext` and `pMapSpriteHandle` hold the real addresses
 * ([ARRAY_ADDRESS] + 12 * index) so the bytes compare with a dump.
 */
public class UnitSpriteHandles(
    /** ROM the unit icon table and class data are read from. */
    private val rom: RomView,
    /** The sprite slot loader `UseUnitSprite` is called on. */
    private val sprites: UnitSprites,
    /** The unit arrays (`gUnitArray*`). */
    private val units: UnitTable,
) {
    /** `gSMSHandleArray`. */
    public val handles: ByteArray = ByteArray(HANDLE_COUNT * HANDLE_SIZE)

    /** `gSMSHandleIt` as an index into [handles]. */
    public var handleIt: Int = 0

    private fun addr(index: Int): Int = ARRAY_ADDRESS + index * HANDLE_SIZE

    private fun index(address: Int): Int = (address - ARRAY_ADDRESS) / HANDLE_SIZE

    private fun pNext(h: Int): Int = u32(handles, h * HANDLE_SIZE)

    private fun yDisplay(h: Int): Int = s16(handles, h * HANDLE_SIZE + 6)

    private fun s16(b: ByteArray, o: Int): Int = u16(b, o).toShort().toInt()

    /**
     * Ported from src/bmudisp.c: GetUnitSpritePalette
     *
     * The OBJ palette bank of the faction of unit [id].
     */
    public fun getUnitSpritePalette(id: Int): Int =
        when (units.index(id) and 0xC0) {
            0x00 -> 0xC
            0x80 -> 0xD
            0x40 -> 0xE
            else -> 0xB
        }

    /**
     * Transcribed from src/bmudisp.c: GetUnitDisplayedSpritePalette (not verified: no traced
     * unit has `US_BIT27` or `US_UNSELECTABLE`)
     *
     * [getUnitSpritePalette], or 0xB / 0xF for units with `US_BIT27` / `US_UNSELECTABLE`.
     */
    public fun getUnitDisplayedSpritePalette(id: Int): Int {
        val state = units.state(id)
        if (state and (1 shl 27) != 0) return 0xB
        if (state and (1 shl 1) != 0) return 0xF
        return getUnitSpritePalette(id)
    }

    /**
     * Ported from src/bmudisp.c: AddUnitSprite
     *
     * Takes the next free handle, links it in front of the first handle whose successor has a
     * `yDisplay` below [y] (or at the end), and returns its index.
     */
    public fun addUnitSprite(y: Int): Int {
        var it = 0
        while (true) {
            val next = pNext(it)
            if (next == 0 || yDisplay(index(next)) < y) {
                put32(handles, handleIt * HANDLE_SIZE, next)
                put32(handles, it * HANDLE_SIZE, addr(handleIt))
                val result = handleIt
                handleIt += 1
                return result
            }
            it = index(next)
        }
    }

    /**
     * Ported from src/bmudisp.c: RefreshUnitSprites (the ballista, trap, hidden-by-fog and
     * petrify branches are not exercised by the trace)
     *
     * Rebuilds the handle list from the units that are on the map ([mapUnitAt] is `gBmMapUnit`)
     * and the ballista / light rune [traps] (the 8-byte `struct Trap` pool).
     */
    public fun refreshUnitSprites(mapUnitAt: (x: Int, y: Int) -> Int, traps: ByteArray) {
        handleIt = 0
        put32(handles, 0, 0)
        put16(0 * HANDLE_SIZE + 6, 0x400)
        handleIt = 1
        var oam2 = 0
        for (i in 1 until 0xC0 + 6) {
            if (!units.isValid(i)) continue
            val o = units.unitOffset(i)
            put32(units.bytes, o + OFF_HANDLE, 0)
            val state = units.state(i)
            if (state and ((1 shl 0) or (1 shl 9)) != 0) continue
            val x = units.xPos(i)
            val y = units.yPos(i)
            if (mapUnitAt(x, y) == 0) continue
            val status = units.statusIndex(i)
            if (status == STATUS_PETRIFY || status == STATUS_13) {
                units.setState(i, units.state(i) or (1 shl 1))
            }
            val h = addUnitSprite(y * 16)
            put16(h * HANDLE_SIZE + 6, y * 16)
            put16(h * HANDLE_SIZE + 4, x * 16)
            val smsId = unitSmsId(i, traps)
            val pal = getUnitDisplayedSpritePalette(i) and 0xF
            put16(h * HANDLE_SIZE + 8, sprites.useUnitSprite(smsId) + 0x80 + pal * 0x1000)
            var config = iconSize(smsId)
            if (units.state(i) and 0x100 != 0) config += 3
            if (units.state(i) and 0x1000000 != 0) config += 0x40
            handles[h * HANDLE_SIZE + 0xB] = config.toByte()
            put32(units.bytes, o + OFF_HANDLE, addr(h))
        }
        var t = 0
        while (t < traps.size && u8(traps, t + 2) != 0) {
            val type = u8(traps, t + 2)
            val tx = u8(traps, t)
            val ty = u8(traps, t + 1)
            if (type == 1 && s8(traps, t + 5) == 0) {
                when (u8(traps, t + 3)) {
                    0x35 -> oam2 = sprites.useUnitSprite(0x5B) - 0x4000 + 0x80
                    0x36 -> oam2 = sprites.useUnitSprite(0x5C) - 0x4000 + 0x80
                    0x37 -> oam2 = sprites.useUnitSprite(0x5D) - 0x4000 + 0x80
                }
                val h = addUnitSprite(ty * 16)
                put16(h * HANDLE_SIZE + 6, ty * 16)
                put16(h * HANDLE_SIZE + 4, tx * 16)
                put16(h * HANDLE_SIZE + 8, oam2)
                handles[h * HANDLE_SIZE + 0xB] = iconSize(0x5B).toByte()
            }
            if (type == 0xD) {
                val h = addUnitSprite(ty * 16)
                put16(h * HANDLE_SIZE + 6, ty * 16)
                put16(h * HANDLE_SIZE + 4, tx * 16)
                put16(h * HANDLE_SIZE + 8, sprites.useUnitSprite(0x66) - 0x5000 + 0x80)
                handles[h * HANDLE_SIZE + 0xB] = iconSize(0x66).toByte()
            }
            t += TRAP_SIZE
        }
        if (sprites.syncFlag != 0) sprites.forceSyncUnitSpriteSheet()
    }

    /**
     * Transcribed from src/bmudisp.c: GetUnitSpriteHideFlag (not verified: no traced unit lacks
     * a handle or has the hide bit set, so breaking either branch survives)
     *
     * 0x80 if unit [id] has no map sprite handle, else the `0x80` bit of the handle's `config`.
     */
    public fun getUnitSpriteHideFlag(id: Int): Int {
        val handle = u32(units.bytes, units.unitOffset(id) + OFF_HANDLE)
        if (handle == 0) return 0x80
        return u8(handles, index(handle) * HANDLE_SIZE + 0xB) and 0x80
    }

    private fun put16(o: Int, v: Int) {
        handles[o] = v.toByte()
        handles[o + 1] = (v shr 8).toByte()
    }

    private fun iconSize(smsId: Int): Int = rom.u16(ICON_TABLE + 8 * (smsId and 0x7F) + 2)

    /** `GetUnitSMSId` (src/bmunit.c): the class sprite, or a ballista sprite by its trap item. */
    private fun unitSmsId(id: Int, traps: ByteArray): Int {
        val o = units.unitOffset(id)
        if (units.state(id) and (1 shl 11) == 0) {
            return rom.u8(u32(units.bytes, o + 4) + 6)
        }
        val trap = u8(units.bytes, o + 0x1C) * TRAP_SIZE
        return when (u8(traps, trap + 3)) {
            BALLISTA_REGULAR -> 0x58
            BALLISTA_LONG -> 0x59
            BALLISTA_KILLER -> 0x5A
            else -> 0
        }
    }

    internal companion object {
        const val ARRAY_ADDRESS = 0x0203A018
        const val HANDLE_SIZE = 12
        const val HANDLE_COUNT = 100
        const val TRAP_SIZE = 8
        const val OFF_HANDLE = 0x3C
        const val ICON_TABLE = 0x088AF880
        const val STATUS_PETRIFY = 11
        const val STATUS_13 = 13
        const val BALLISTA_REGULAR = 0x35
        const val BALLISTA_LONG = 0x36
        const val BALLISTA_KILLER = 0x37
    }
}
