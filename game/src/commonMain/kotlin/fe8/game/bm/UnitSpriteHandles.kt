package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.put32
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u16
import fe8.game.bm.StructCodec.u32
import fe8.game.bm.StructCodec.u8

/**
 * The map sprite handle list of src/bmudisp.c (`gSMSHandleArray`, `gSMSHandleIt`): one
 * `SMSHandle` per unit standing on the map and per ballista or light rune trap, linked in order
 * of descending `yDisplay`, so the figures lowest on the screen come first and
 * [UnitSpritesOam.putUnitSpritesOam] pushes them first (earlier OAM entries draw in front).
 * Rebuilt by [refreshUnitSprites] whenever units move, appear or change state.
 *
 * [handles] mirrors the EWRAM bytes (100 handles of 12 bytes, `struct SMSHandle` in
 * include/types.h: `pNext` u32 at 0, `xDisplay` s16 at 4 and `yDisplay` s16 at 6 (map pixels),
 * `oam2Base` u16 at 8 (tile and palette bank for OAM attribute 2), an unused byte at 0xA, `config`
 * s8 at 0xB); `pNext` and `pMapSpriteHandle` hold the real addresses ([ARRAY_ADDRESS] + 12 *
 * index) so the bytes compare with a dump. Handle 0 is the list head, a sentinel whose `yDisplay`
 * 0x400 is above any real position; it is never drawn.
 *
 * `config`: bits 0-3 the `UNIT_ICON_SIZE_*` shape (0 16x16, 1 16x32, 2 32x32) plus 3 when the
 * unit is drawn on the back layer (`US_BIT8`, which `SetAllUnitNotBackSprite` clears), bit 6 (0x40)
 * makes the figure jitter sideways (set from unit state bit 24, which the decomp does not name),
 * bit 7 (0x80) hides it (`HideUnitSprite`, for example while the moving unit `Mu` is shown).
 */
public class UnitSpriteHandles(
    /** ROM the unit icon table and class data are read from. */
    private val rom: RomView,
    /** The sprite slot loader `UseUnitSprite` is called on. */
    private val sprites: UnitSprites,
    /** The unit arrays (`gUnitArray*`). */
    private val units: UnitTable,
) {
    /** `gSMSHandleArray`: handle 0 is the list head, handles 1 on are handed out in order. */
    public val handles: ByteArray = ByteArray(HANDLE_COUNT * HANDLE_SIZE)

    /** `gSMSHandleIt` as an index into [handles]: the next unused handle. */
    public var handleIt: Int = 0

    /** The real address of the handle at [handleIndex] (the value a `pNext` field holds). */
    private fun handleAddress(handleIndex: Int): Int = ARRAY_ADDRESS + handleIndex * HANDLE_SIZE

    /** The index of the handle at the real address [address]. */
    private fun handleIndexOf(address: Int): Int = (address - ARRAY_ADDRESS) / HANDLE_SIZE

    /** The `pNext` field of the handle at [handleIndex]: the address of the next handle. */
    private fun nextAddressOf(handleIndex: Int): Int = u32(handles, handleIndex * HANDLE_SIZE)

    /** The `yDisplay` (s16 at +6) of the handle at [handleIndex]. */
    private fun yDisplayOf(handleIndex: Int): Int =
        readS16(handles, handleIndex * HANDLE_SIZE + 6)

    /** Signed 16-bit read at [offset] of [bytes]. */
    private fun readS16(bytes: ByteArray, offset: Int): Int = u16(bytes, offset).toShort().toInt()

    /**
     * Ported from src/bmudisp.c: GetUnitSpritePalette
     *
     * The OBJ palette bank of the faction of unit [id] (`UNIT_FACTION`, unit index bits 6-7):
     * player (`FACTION_BLUE`) 12, enemy (`FACTION_RED`) 13, NPC (`FACTION_GREEN`) 14, fourth
     * link arena team (`FACTION_PURPLE`) 11; see `OBJPAL_UNITSPRITE_*`.
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
     * The palette bank a unit's map sprite is drawn with: the purple bank 0xB for units with
     * `US_BIT27` (no other use of that bit in the decomp, so its meaning is unknown), the gray
     * bank 0xF (`OBJPAL_UNITSPRITE_GRAY`) for `US_UNSELECTABLE` units (those that have already
     * acted this turn, or are petrified), otherwise the faction color of [getUnitSpritePalette].
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
     * Takes the next free handle (`gSMSHandleIt`), links it after the first handle whose
     * successor has a `yDisplay` below [y] (or at the end), which keeps the list sorted by
     * descending `yDisplay`, and returns its index. Handles with equal `yDisplay` keep the order
     * they were added. The C does not check [HANDLE_COUNT].
     */
    public fun addUnitSprite(y: Int): Int {
        var cursor = 0
        while (true) {
            val nextAddress = nextAddressOf(cursor)
            if (nextAddress == 0 || yDisplayOf(handleIndexOf(nextAddress)) < y) {
                put32(handles, handleIt * HANDLE_SIZE, nextAddress)
                put32(handles, cursor * HANDLE_SIZE, handleAddress(handleIt))
                val result = handleIt
                handleIt += 1
                return result
            }
            cursor = handleIndexOf(nextAddress)
        }
    }

    /**
     * Transcribed from src/bmudisp.c: HideUnitSprite (not verified: no trace hides a sprite)
     *
     * Sets the hide bit (0x80) of the handle config of unit [id], so its figure is not drawn
     * (`PutUnitSpritesOam`, `PutUnitSpriteIconsOam`); does nothing without a handle. Used while
     * a `Mu` moving unit stands in for the figure and by the player phase hover switch
     * (src/mu.c, src/bmusailment.c). The C takes a `Unit*` and, for a null unit, calls
     * `RefreshUnitSprites` and then reads through the null pointer; that is not reproduced.
     */
    public fun hideUnitSprite(id: Int) {
        val handleAddr = u32(units.bytes, units.unitOffset(id) + OFF_HANDLE)
        if (handleAddr == 0) return
        val configOffset = handleIndexOf(handleAddr) * HANDLE_SIZE + 0xB
        handles[configOffset] = (handles[configOffset].toInt() or 0x80).toByte()
    }

    /**
     * Transcribed from src/bmudisp.c: ShowUnitSprite (not verified: no trace shows a sprite)
     *
     * Clears the hide bit (0x80) of the handle config of unit [id], showing its figure again;
     * does nothing without a handle.
     */
    public fun showUnitSprite(id: Int) {
        val handleAddr = u32(units.bytes, units.unitOffset(id) + OFF_HANDLE)
        if (handleAddr == 0) return
        val configOffset = handleIndexOf(handleAddr) * HANDLE_SIZE + 0xB
        handles[configOffset] = (handles[configOffset].toInt() and 0x80.inv()).toByte()
    }

    /**
     * Ported from src/bmudisp.c: RefreshUnitSprites (the ballista, trap, hidden-by-fog and
     * petrify branches are not exercised by the trace)
     *
     * Rebuilds the handle list so the next frame draws the right figures: every valid unit that
     * is not hidden (`US_HIDDEN`) or hidden by fog (`US_BIT9`) and stands on a nonzero tile of
     * `gBmMapUnit` ([mapUnitAt]) gets a handle (the previous handle pointer is cleared for all
     * units), then every ballista that nobody rides and every light rune in [traps] (the 8-byte
     * `struct Trap` pool, `TRAP_BALLISTA`, `TRAP_LIGHT_RUNE`). Petrified (`UNIT_STATUS_PETRIFY`)
     * and `UNIT_STATUS_13` units are marked `US_UNSELECTABLE` here, which turns them gray. New
     * sprites are loaded on the way ([UnitSprites.useUnitSprite]); if that raised the sync flag
     * the sheet is copied to VRAM at once. Called by the player phase procs (src/playerphase.c),
     * after unit moves (src/bmunit.c, src/bmusailment.c), by event commands and by many screens.
     */
    public fun refreshUnitSprites(mapUnitAt: (x: Int, y: Int) -> Int, traps: ByteArray) {
        handleIt = 0
        // Head sentinel: no successor yet, yDisplay 0x400 above every real position.
        put32(handles, 0, 0)
        putHandleU16(0 * HANDLE_SIZE + 6, 0x400)
        handleIt = 1
        var trapOam2 = 0
        // Unit slots 1 to FACTION_PURPLE + 5: player, NPC and enemy units, and the link arena
        // team (the C loop runs to FACTION_PURPLE + 6).
        for (i in 1 until 0xC0 + 6) {
            if (!units.isValid(i)) continue
            val offset = units.unitOffset(i)
            put32(units.bytes, offset + OFF_HANDLE, 0)
            val state = units.state(i)
            // US_HIDDEN or US_BIT9 (hidden by fog).
            if (state and ((1 shl 0) or (1 shl 9)) != 0) continue
            val x = units.xPos(i)
            val y = units.yPos(i)
            if (mapUnitAt(x, y) == 0) continue
            val status = units.statusIndex(i)
            if (status == STATUS_PETRIFY || status == STATUS_13) {
                // US_UNSELECTABLE
                units.setState(i, units.state(i) or (1 shl 1))
            }
            val handleIndex = addUnitSprite(y * 16)
            putHandleU16(handleIndex * HANDLE_SIZE + 6, y * 16)
            putHandleU16(handleIndex * HANDLE_SIZE + 4, x * 16)
            val smsId = unitSmsId(i, traps)
            val paletteBank = getUnitDisplayedSpritePalette(i) and 0xF
            // OAM attribute 2: the sprite's tile (VRAM +0x1000 is OBJ tile 0x80) and the palette
            // bank in bits 12-15.
            putHandleU16(
                handleIndex * HANDLE_SIZE + 8,
                sprites.useUnitSprite(smsId) + 0x80 + paletteBank * 0x1000,
            )
            var config = iconSize(smsId)
            // US_BIT8: draw on the layer behind (OAM layer 3 instead of 2).
            if (units.state(i) and 0x100 != 0) config += 3
            // Unit state bit 24 (unnamed in the decomp): the 0x40 jitter flag.
            if (units.state(i) and 0x1000000 != 0) config += 0x40
            handles[handleIndex * HANDLE_SIZE + 0xB] = config.toByte()
            put32(units.bytes, offset + OFF_HANDLE, handleAddress(handleIndex))
        }
        var trapOffset = 0
        while (trapOffset < traps.size && u8(traps, trapOffset + 2) != 0) {
            val trapType = u8(traps, trapOffset + 2)
            val tx = u8(traps, trapOffset)
            val ty = u8(traps, trapOffset + 1)
            // TRAP_BALLISTA with data[1] (TRAP_EXTDATA_BLST_RIDDEN) 0: a ballista nobody sits in.
            if (trapType == 1 && s8(traps, trapOffset + 5) == 0) {
                // `extra` is the ballista item: ITEM_BALLISTA_REGULAR / _LONG / _KILLER, drawn with
                // sprite 0x5B / 0x5C / 0x5D. The - 0x4000 wraps in the u16 to palette bank 12
                // (the blue map sprite palette). Any other item keeps the previous trapOam2, as
                // in the C.
                when (u8(traps, trapOffset + 3)) {
                    0x35 -> trapOam2 = sprites.useUnitSprite(0x5B) - 0x4000 + 0x80
                    0x36 -> trapOam2 = sprites.useUnitSprite(0x5C) - 0x4000 + 0x80
                    0x37 -> trapOam2 = sprites.useUnitSprite(0x5D) - 0x4000 + 0x80
                }
                val handleIndex = addUnitSprite(ty * 16)
                putHandleU16(handleIndex * HANDLE_SIZE + 6, ty * 16)
                putHandleU16(handleIndex * HANDLE_SIZE + 4, tx * 16)
                putHandleU16(handleIndex * HANDLE_SIZE + 8, trapOam2)
                handles[handleIndex * HANDLE_SIZE + 0xB] = iconSize(0x5B).toByte()
            }
            // TRAP_LIGHT_RUNE: sprite 0x66 with palette bank 11 (the - 0x5000 wraps to 0xB000),
            // the bank `ApplyUnitSpritePalettes` fills with the light rune palette.
            if (trapType == 0xD) {
                val handleIndex = addUnitSprite(ty * 16)
                putHandleU16(handleIndex * HANDLE_SIZE + 6, ty * 16)
                putHandleU16(handleIndex * HANDLE_SIZE + 4, tx * 16)
                val runeOam2 = sprites.useUnitSprite(0x66) - 0x5000 + 0x80
                putHandleU16(handleIndex * HANDLE_SIZE + 8, runeOam2)
                handles[handleIndex * HANDLE_SIZE + 0xB] = iconSize(0x66).toByte()
            }
            trapOffset += TRAP_SIZE
        }
        if (sprites.syncFlag != 0) sprites.forceSyncUnitSpriteSheet()
    }

    /**
     * Transcribed from src/bmudisp.c: GetUnitSpriteHideFlag (not verified: no traced unit lacks
     * a handle or has the hide bit set, so breaking either branch survives)
     *
     * 0x80 (counts as hidden) if unit [id] has no map sprite handle, else the hide bit (0x80) of
     * the handle's `config`. `PutUnitSpriteIconsOam` skips the status, rescue and boss icons of
     * a unit whose figure is hidden.
     */
    public fun getUnitSpriteHideFlag(id: Int): Int {
        val handle = u32(units.bytes, units.unitOffset(id) + OFF_HANDLE)
        if (handle == 0) return 0x80
        return u8(handles, handleIndexOf(handle) * HANDLE_SIZE + 0xB) and 0x80
    }

    /** Writes [value] as a little-endian u16 at [offset] of [handles]. */
    private fun putHandleU16(offset: Int, value: Int) {
        handles[offset] = value.toByte()
        handles[offset + 1] = (value shr 8).toByte()
    }

    /**
     * The `size` (u16 at +2; `UNIT_ICON_SIZE_*`: 0 16x16, 1 16x32, 2 32x32) of the
     * `unit_icon_wait_table` entry for [smsId] (`GetInfo`, id & 0x7F).
     */
    private fun iconSize(smsId: Int): Int = rom.u16(ICON_TABLE + 8 * (smsId and 0x7F) + 2)

    /**
     * `GetUnitSMSId` (src/bmunit.c): the sprite id of the unit's class (`SMSId`, class byte 6),
     * or, for a unit sitting in a ballista (`US_IN_BALLISTA`, bit 11), the rider-on-ballista
     * sprite of the ballista's item (0x58 / 0x59 / 0x5A for the regular, long and killer ones;
     * 0 for any other item).
     */
    private fun unitSmsId(id: Int, traps: ByteArray): Int {
        val offset = units.unitOffset(id)
        if (units.state(id) and (1 shl 11) == 0) {
            return rom.u8(u32(units.bytes, offset + 4) + 6)
        }
        val trapOffset = u8(units.bytes, offset + 0x1C) * TRAP_SIZE
        return when (u8(traps, trapOffset + 3)) {
            BALLISTA_REGULAR -> 0x58
            BALLISTA_LONG -> 0x59
            BALLISTA_KILLER -> 0x5A
            else -> 0
        }
    }

    internal companion object {
        /** Where `gSMSHandleArray` lives in EWRAM on the GBA. */
        const val ARRAY_ADDRESS = 0x0203A018
        /** `sizeof(struct SMSHandle)`. */
        const val HANDLE_SIZE = 12
        const val HANDLE_COUNT = 100
        /** `sizeof(struct Trap)`: x, y, type, extra, data[4]. */
        const val TRAP_SIZE = 8
        /** `pMapSpriteHandle` in `struct Unit`. */
        const val OFF_HANDLE = 0x3C
        /** `unit_icon_wait_table`. */
        const val ICON_TABLE = 0x088AF880
        /** `UNIT_STATUS_PETRIFY`. */
        const val STATUS_PETRIFY = 11
        /** `UNIT_STATUS_13` (unnamed in the decomp; grays the unit like petrify). */
        const val STATUS_13 = 13
        /** `ITEM_BALLISTA_REGULAR`. */
        const val BALLISTA_REGULAR = 0x35
        /** `ITEM_BALLISTA_LONG`. */
        const val BALLISTA_LONG = 0x36
        /** `ITEM_BALLISTA_KILLER`. */
        const val BALLISTA_KILLER = 0x37
    }
}
