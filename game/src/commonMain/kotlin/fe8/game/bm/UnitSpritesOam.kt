package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.s16
import fe8.game.bm.StructCodec.u32
import fe8.game.bm.StructCodec.u8
import gbafe.core.flags.FlagStore
import gbafe.core.time.GameClock
import gbafe.render.OamBuffer

/**
 * The map sprite OAM pushers of src/bmudisp.c: the unit figures of the handle list, the status,
 * rescue, boss and protect icons over them, and the blinking chapter goal marker. Run every
 * frame by the map task proc (`PROC_CALL(PutUnitSpritesOam)` in `gProc_MapTask`, src/bmio.c).
 * Everything is pushed to the hi part of [oam] (`CallARM_PushToSecondaryOAM`).
 *
 * The `gObject_*` shapes and the status icon sprite tables are read from the [rom].
 */
public class UnitSpritesOam(
    /** ROM the object shapes and icon tables are read from. */
    private val rom: RomView,
    /** The unit arrays. */
    private val units: UnitTable,
    /** The sprite handle list (`gSMSHandleArray`). */
    private val handles: UnitSpriteHandles,
    /** `gBmSt` (camera). */
    private val bmSt: BmSt,
    /** `gPlaySt` (chapter index). */
    private val playSt: PlaySt,
    /** The ROM chapter table. */
    private val chapters: ChapterData,
    /** The event flags (`CheckFlag`). */
    private val flags: FlagStore,
    /** The OAM the objects are pushed to. */
    private val oam: OamBuffer,
) {
    /** `GetGameClock`: the frame counter all blink and animation timings below run on. */
    public var gameClock: () -> Int = { 0 }

    /**
     * The game clock itself; when set it is read instead of [gameClock] (a lambda result boxes
     * the clock every call, which the idle frame must not).
     */
    public var clockSource: GameClock? = null

    /** The current game clock tick: [clockSource] when set, otherwise [gameClock]. */
    private fun now(): Int = clockSource?.frames?.toInt() ?: gameClock()

    /**
     * `GetBattleMapKind` for the current chapter; a skirmish (`BATTLEMAP_KIND_SKIRMISH`, the
     * random encounter maps) has no protect character, so no protect icon.
     */
    public var battleMapKind: () -> Int = { BattleMapKind.STORY }

    /** `gBmMapFog[y][x]`: 0 where fog of war hides the tile. */
    public var fogAt: (x: Int, y: Int) -> Int = { _, _ -> 0 }

    /** `gBmMapTerrain[y][x]`: the terrain id of the tile. */
    public var terrainAt: (x: Int, y: Int) -> Int = { _, _ -> 0 }

    private val object8x8 by lazy { rom.objN(OBJECT_8X8).data }
    private val object16x16 by lazy { rom.objN(OBJECT_16X16).data }
    private val object16x32 by lazy { rom.objN(OBJECT_16X32).data }
    private val object32x32 by lazy { rom.objN(OBJECT_32X32).data }
    private val poisonIcons by lazy { iconTable(POISON_ICONS, 12) }
    private val sleepIcons by lazy { iconTable(SLEEP_ICONS, 7) }
    private val berserkIcons by lazy { iconTable(BERSERK_ICONS, 9) }
    private val silenceIcons by lazy { iconTable(SILENCE_ICONS, 18) }
    private val statBoosterIcon by lazy { rom.objN(STAT_BOOSTER_ICON).data }

    /** The object data of the first [count] icon pointers in the ROM table at [tableAddr]. */
    private fun iconTable(tableAddr: Int, count: Int): List<ShortArray> =
        List(count) { rom.objN(rom.u32(tableAddr + 4 * it)).data }

    /**
     * `CallARM_PushToSecondaryOAM(OAM1_X(0x200 + x), OAM0_Y(0x100 + y), data, oam2)`: adds the
     * object list [data] at screen position ([x], [y]) with OAM attribute 2 [oam2] (tile, palette
     * bank in bits 12-15, layer in bits 10-11). The 0x200 and 0x100 only keep the C expressions
     * positive; after the 9-bit x and 8-bit y masks they change nothing, so a negative [x] wraps
     * to the left screen edge as the hardware expects.
     */
    private fun push(x: Int, y: Int, data: ShortArray, oam2: Int) {
        oam.putHi((0x200 + x) and 0x1FF, (0x100 + y) and 0xFF, data, oam2)
    }

    /**
     * Ported from src/bmudisp.c: PutUnitSpritesOam
     *
     * Draws every figure on the chapter map: first the icons ([putUnitSpriteIconsOam]), then each
     * handle of the list ([UnitSpriteHandles]) in list order, skipping those more than a tile
     * off the left or right of the screen (x -16 to 240), more than 32 pixels above or below
     * (y -32 to 160; the 16x32 and 32x32 figures stick up 16 pixels above their tile) or hidden
     * (`config & 0x80`). `config & 0xF` selects the shape and layer: 0, 1, 2 are 16x16, 16x32
     * and 32x32 on OAM layer 2, 3, 4, 5 the same shapes on layer 3 (behind); the 32x32 shape is
     * drawn 8 pixels further left to center it on its tile. A handle with `config & 0x40`
     * jitters between 0 and 2 pixels to the right every 2 ticks (`GetGameClock() & 2`). The
     * jitter and the 32x32 configurations 2 and 5 are not exercised by the trace.
     */
    public fun putUnitSpritesOam() {
        val handleBytes = handles.handles
        putUnitSpriteIconsOam()
        var nextAddress = u32(handleBytes, 0)
        while (nextAddress != 0) {
            val handleOffset =
                (nextAddress - UnitSpriteHandles.ARRAY_ADDRESS) / UnitSpriteHandles.HANDLE_SIZE *
                    UnitSpriteHandles.HANDLE_SIZE
            nextAddress = u32(handleBytes, handleOffset)
            val config = u8(handleBytes, handleOffset + 0xB)
            val x = s16(handleBytes, handleOffset + 4) - bmSt.cameraX
            val y = s16(handleBytes, handleOffset + 6) - bmSt.cameraY
            if (x < -16 || x > DISPLAY_WIDTH) continue
            if (y < -32 || y > DISPLAY_HEIGHT) continue
            if (config and 0x80 != 0) continue
            val wiggle = if (config and 0x40 != 0) now() and 2 else 0
            val baseTile = StructCodec.u16(handleBytes, handleOffset + 8)
            when (config and 0xF) {
                0 -> push(x + wiggle, y, object16x16, baseTile + (2 shl 10))
                1 -> push(x + wiggle, y - 16, object16x32, baseTile + (2 shl 10))
                2 -> push(x - 8 + wiggle, y - 16, object32x32, baseTile + (2 shl 10))
                3 -> push(x + wiggle, y, object16x16, baseTile + (3 shl 10))
                4 -> push(x + wiggle, y - 16, object16x32, baseTile + (3 shl 10))
                5 -> push(x - 8 + wiggle, y - 16, object32x32, baseTile + (3 shl 10))
            }
        }
    }

    /**
     * Transcribed from src/bmudisp.c: PutChapterMarkedTileIconOam (not verified:
     *   the trace chapter has no goal tile (destPosX == 0xFF))
     *
     * The blinking marker on the chapter goal's destination tile (the ROM chapter struct's
     * `destPosX` / `destPosY`, bytes 0x8F and 0x90, from the chapter's `goal` settings; 0xFF =
     * none). Shown for the first 20 of every 32 ticks, and not when the tile is
     * fogged or a roof tile (`TERRAIN_ROOF`) or more than 16 pixels off screen. It is an 8x8
     * object at OBJ tile 0x51 (oam2 0xC51 = tile 0x51 on layer 3) drawn 4 pixels right and 7
     * below the tile's top-left corner.
     */
    public fun putChapterMarkedTileIconOam() {
        val chapterAddr = chapters.getROMChapterStruct(playSt.chapterIndex)
        val xTile = rom.u8(chapterAddr + OFF_DEST_POS_X)
        val yTile = rom.u8(chapterAddr + OFF_DEST_POS_Y)
        val shouldDisplay = (now() and 0x1F) < 0x14
        if (xTile == 0xFF) return
        if (!shouldDisplay) return
        if (fogAt(xTile, yTile) == 0) return
        if (terrainAt(xTile, yTile) == TERRAIN_ROOF) return
        val x = xTile * 16 - bmSt.cameraX
        val y = yTile * 16 - bmSt.cameraY
        if (x < -16 || x > DISPLAY_WIDTH) return
        if (y < -16 || y > DISPLAY_HEIGHT) return
        push(x + 4, y + 7, object8x8, 0xC51)
    }

    /**
     * Ported from src/bmudisp.c: PutUnitSpriteIconsOam
     *
     * Draws, over each unit with a visible figure, its status icon: poison (12 frames, 8 ticks
     * each, the last three blank), silence (18 frames, 4 ticks each), sleep (7 frames, 16 ticks
     * each) and berserk (9 frames, 8 ticks each) animate; the attack, defense, critical and
     * avoid boosts (the stat booster statuses, `UNIT_STATUS_ATTACK` to `UNIT_STATUS_AVOID`) show
     * a fixed arrow only while the blink is on. Then a small icon at the figure's lower right
     * (+9, +7), also only while the blink is on (the first 20 of every 32 ticks): the rescue icon
     * on a unit carrying someone, colored by the faction of the carried unit; else the boss icon
     * for a non-player unit with `CA_BOSS`; else the protect icon for the chapter's protect
     * character (`protectCharacterIndex`, byte 0x8E of the ROM chapter struct; none in a
     * skirmish). Also draws the goal marker. Does nothing at all while the event flag
     * `EVFLAG_HIDE_BLINKING_ICON` is set (an event hides the icons; its use is not documented in
     * the decomp). Only unit slots 1 to 0xBF are walked, not the fourth link arena team.
     * Observed by the trace: only the boss icon (with the blink on and off). The status icon
     * and rescue branches are transcribed only.
     */
    public fun putUnitSpriteIconsOam() {
        val protectCharacterId =
            if (battleMapKind() != BattleMapKind.SKIRMISH) {
                rom.u8(chapters.getROMChapterStruct(playSt.chapterIndex) + OFF_PROTECT_CHARACTER)
            } else {
                0
            }
        val clock = now()
        val displayRescueIcon = clock % 32 < 20
        val poisonFrame = clock / 8 % poisonIcons.size
        val sleepFrame = clock / 16 % sleepIcons.size
        val berserkFrame = clock / 8 % berserkIcons.size
        val silenceFrame = clock / 4 % silenceIcons.size
        if (flags.checkFlag(EVFLAG_HIDE_BLINKING_ICON)) return
        putChapterMarkedTileIconOam()
        // Unit slots 1 to 0xBF: player, NPC and enemy units.
        for (i in 1 until 0xC0) {
            if (!units.isValid(i)) continue
            if (units.state(i) and US_HIDDEN != 0) continue
            if (handles.getUnitSpriteHideFlag(i) != 0) continue
            val ux = units.xPos(i) * 16 - bmSt.cameraX
            val uy = units.yPos(i) * 16 - bmSt.cameraY
            val inView = ux in -16..DISPLAY_WIDTH && uy in -16..DISPLAY_HEIGHT
            // The status icon offsets (-2, -4 etc.) place each icon beside the figure's head.
            when (units.statusIndex(i)) {
                STATUS_POISON ->
                    if (inView) push(ux - 2, uy - 4, poisonIcons[poisonFrame], 0)
                STATUS_SILENCED ->
                    if (inView) push(ux - 2, uy - 4, silenceIcons[silenceFrame], 0)
                STATUS_SLEEP ->
                    if (inView) push(ux + 2, uy, sleepIcons[sleepFrame], 0)
                STATUS_BERSERK ->
                    if (inView) push(ux + 1, uy - 5, berserkIcons[berserkFrame], 0)
                STATUS_ATTACK, STATUS_DEFENSE, STATUS_CRIT, STATUS_AVOID -> {
                    if (!displayRescueIcon) continue
                    if (inView) push(ux - 1, uy - 5, statBoosterIcon, 0)
                }
            }
            if (!displayRescueIcon) continue
            if (!inView) continue
            if (units.state(i) and US_RESCUING != 0) {
                // `rescue` is the carried unit's index; its top two bits are its faction.
                val rescue = u8(units.bytes, units.unitOffset(i) + OFF_RESCUE)
                val paletteBank = RESCUE_PAL_LUT[rescue shr 6] and 0xF
                // OBJ tile 3 on layer 2 (0x800).
                push(ux + 9, uy + 7, object8x8, paletteBank * 0x1000 + 0x803)
            } else if (units.index(i) and 0xC0 != 0 && units.cAttributes(i) and CA_BOSS != 0) {
                // Boss icon: OBJ tile 0x10 on layer 2.
                push(ux + 9, uy + 7, object8x8, 0x810)
            } else if (protectCharacterId == units.charId(i)) {
                // Protect icon: OBJ tile 0x11 on layer 2.
                push(ux + 9, uy + 7, object8x8, 0x811)
            }
        }
    }

    private companion object {
        const val DISPLAY_WIDTH = 240
        const val DISPLAY_HEIGHT = 160
        /** `protectCharacterIndex` of the ROM chapter struct. */
        const val OFF_PROTECT_CHARACTER = 0x8E
        /** `destPosX` of the ROM chapter struct. */
        const val OFF_DEST_POS_X = 0x8F
        /** `destPosY` of the ROM chapter struct. */
        const val OFF_DEST_POS_Y = 0x90
        /** `rescue` in `struct Unit`. */
        const val OFF_RESCUE = 0x1B
        const val EVFLAG_HIDE_BLINKING_ICON = 132
        const val TERRAIN_ROOF = 0x22
        const val US_HIDDEN = 1 shl 0
        const val US_RESCUING = 1 shl 4
        const val CA_BOSS = 1 shl 15
        const val STATUS_POISON = 1
        const val STATUS_SLEEP = 2
        const val STATUS_SILENCED = 3
        const val STATUS_BERSERK = 4
        const val STATUS_ATTACK = 5
        const val STATUS_DEFENSE = 6
        const val STATUS_CRIT = 7
        const val STATUS_AVOID = 8
        /**
         * `rescuePalLut`: OBJ palette bank of the rescue icon by faction id (`FACTION_ID_BLUE` 12,
         * `FACTION_ID_GREEN` 14, `FACTION_ID_RED` 13).
         */
        val RESCUE_PAL_LUT = intArrayOf(0xC, 0xE, 0xD)
        // The shape tables `gObject_8x8`, `gObject_16x16`, `gObject_32x32`, `gObject_16x32`.
        const val OBJECT_8X8 = 0x08590F44
        const val OBJECT_16X16 = 0x08590F4C
        const val OBJECT_32X32 = 0x08590F54
        const val OBJECT_16X32 = 0x08590F6C
        // The status icon frame tables `sSleepIconSprites`, `sBerserkIconSprites`,
        // `sSilenceIconSprites`, `sPoisonIconSprites` and the one stat booster icon
        // `sSprite_0859B968`.
        const val SLEEP_ICONS = 0x0859B7F4
        const val BERSERK_ICONS = 0x0859B858
        const val SILENCE_ICONS = 0x0859B898
        const val POISON_ICONS = 0x0859B938
        const val STAT_BOOSTER_ICON = 0x0859B968
    }
}
