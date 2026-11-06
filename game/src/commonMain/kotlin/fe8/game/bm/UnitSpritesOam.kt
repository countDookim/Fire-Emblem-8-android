package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.s16
import fe8.game.bm.StructCodec.u32
import fe8.game.bm.StructCodec.u8
import gbafe.core.flags.FlagStore
import gbafe.core.time.GameClock
import gbafe.render.OamBuffer

/**
 * The map sprite OAM pushers of src/bmudisp.c: the unit sprites of the handle list, the status,
 * rescue, boss and protect icons, and the chapter goal marker. Everything is pushed to the hi
 * part of [oam] (`CallARM_PushToSecondaryOAM`).
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
    /** `GetGameClock`. */
    public var gameClock: () -> Int = { 0 }

    /**
     * The game clock itself; when set it is read instead of [gameClock] (a lambda result boxes
     * the clock every call, which the idle frame must not).
     */
    public var clockSource: GameClock? = null

    private fun now(): Int = clockSource?.frames?.toInt() ?: gameClock()

    /** `GetBattleMapKind` for the current chapter. */
    public var battleMapKind: () -> Int = { BattleMapKind.STORY }

    /** `gBmMapFog[y][x]`. */
    public var fogAt: (x: Int, y: Int) -> Int = { _, _ -> 0 }

    /** `gBmMapTerrain[y][x]`. */
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

    private fun iconTable(at: Int, count: Int): List<ShortArray> =
        List(count) { rom.objN(rom.u32(at + 4 * it)).data }

    /** `CallARM_PushToSecondaryOAM(OAM1_X(0x200 + x), OAM0_Y(0x100 + y), data, oam2)`. */
    private fun push(x: Int, y: Int, data: ShortArray, oam2: Int) {
        oam.putHi((0x200 + x) and 0x1FF, (0x100 + y) and 0xFF, data, oam2)
    }

    /**
     * Ported from src/bmudisp.c: PutUnitSpritesOam
     *
     * Pushes the icons ([putUnitSpriteIconsOam]), then every on-screen, unhidden handle of the
     * list in list order. The `config & 0x40` wiggle (`GetGameClock() & 2`) and the 32x32
     * configurations 2 and 5 are not exercised by the trace.
     */
    public fun putUnitSpritesOam() {
        val array = handles.handles
        putUnitSpriteIconsOam()
        var next = u32(array, 0)
        while (next != 0) {
            val o = (next - UnitSpriteHandles.ARRAY_ADDRESS) / UnitSpriteHandles.HANDLE_SIZE *
                UnitSpriteHandles.HANDLE_SIZE
            next = u32(array, o)
            val config = u8(array, o + 0xB)
            val x = s16(array, o + 4) - bmSt.cameraX
            val y = s16(array, o + 6) - bmSt.cameraY
            if (x < -16 || x > DISPLAY_WIDTH) continue
            if (y < -32 || y > DISPLAY_HEIGHT) continue
            if (config and 0x80 != 0) continue
            val wiggle = if (config and 0x40 != 0) now() and 2 else 0
            val base = StructCodec.u16(array, o + 8)
            when (config and 0xF) {
                0 -> push(x + wiggle, y, object16x16, base + (2 shl 10))
                1 -> push(x + wiggle, y - 16, object16x32, base + (2 shl 10))
                2 -> push(x - 8 + wiggle, y - 16, object32x32, base + (2 shl 10))
                3 -> push(x + wiggle, y, object16x16, base + (3 shl 10))
                4 -> push(x + wiggle, y - 16, object16x32, base + (3 shl 10))
                5 -> push(x - 8 + wiggle, y - 16, object32x32, base + (3 shl 10))
            }
        }
    }

    /**
     * Ported from src/bmudisp.c: PutChapterMarkedTileIconOam
     *
     * Blinking marker on the chapter's goal tile. Not verified: the trace chapter has no goal
     * tile (`destPosX == 0xFF`).
     */
    public fun putChapterMarkedTileIconOam() {
        val chapter = chapters.getROMChapterStruct(playSt.chapterIndex)
        val xTile = rom.u8(chapter + OFF_DEST_POS_X)
        val yTile = rom.u8(chapter + OFF_DEST_POS_Y)
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
     * Status icons (poison, silence, sleep, berserk, stat boosters) and the rescue, boss and
     * protect icons of every unit with a visible map sprite, blinking with the game clock.
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
        for (i in 1 until 0xC0) {
            if (!units.isValid(i)) continue
            if (units.state(i) and US_HIDDEN != 0) continue
            if (handles.getUnitSpriteHideFlag(i) != 0) continue
            val ux = units.xPos(i) * 16 - bmSt.cameraX
            val uy = units.yPos(i) * 16 - bmSt.cameraY
            val visible16 = ux in -16..DISPLAY_WIDTH && uy in -16..DISPLAY_HEIGHT
            when (units.statusIndex(i)) {
                STATUS_POISON ->
                    if (visible16) push(ux - 2, uy - 4, poisonIcons[poisonFrame], 0)
                STATUS_SILENCED ->
                    if (visible16) push(ux - 2, uy - 4, silenceIcons[silenceFrame], 0)
                STATUS_SLEEP ->
                    if (visible16) push(ux + 2, uy, sleepIcons[sleepFrame], 0)
                STATUS_BERSERK ->
                    if (visible16) push(ux + 1, uy - 5, berserkIcons[berserkFrame], 0)
                STATUS_ATTACK, STATUS_DEFENSE, STATUS_CRIT, STATUS_AVOID -> {
                    if (!displayRescueIcon) continue
                    if (visible16) push(ux - 1, uy - 5, statBoosterIcon, 0)
                }
            }
            if (!displayRescueIcon) continue
            if (!visible16) continue
            if (units.state(i) and US_RESCUING != 0) {
                val rescue = u8(units.bytes, units.unitOffset(i) + OFF_RESCUE)
                val pal = RESCUE_PAL_LUT[rescue shr 6] and 0xF
                push(ux + 9, uy + 7, object8x8, pal * 0x1000 + 0x803)
            } else if (units.index(i) and 0xC0 != 0 && units.cAttributes(i) and CA_BOSS != 0) {
                push(ux + 9, uy + 7, object8x8, 0x810)
            } else if (protectCharacterId == units.charId(i)) {
                push(ux + 9, uy + 7, object8x8, 0x811)
            }
        }
    }

    private companion object {
        const val DISPLAY_WIDTH = 240
        const val DISPLAY_HEIGHT = 160
        const val OFF_PROTECT_CHARACTER = 0x8E
        const val OFF_DEST_POS_X = 0x8F
        const val OFF_DEST_POS_Y = 0x90
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
        val RESCUE_PAL_LUT = intArrayOf(0xC, 0xE, 0xD)
        const val OBJECT_8X8 = 0x08590F44
        const val OBJECT_16X16 = 0x08590F4C
        const val OBJECT_32X32 = 0x08590F54
        const val OBJECT_16X32 = 0x08590F6C
        const val SLEEP_ICONS = 0x0859B7F4
        const val BERSERK_ICONS = 0x0859B858
        const val SILENCE_ICONS = 0x0859B898
        const val POISON_ICONS = 0x0859B938
        const val STAT_BOOSTER_ICON = 0x0859B968
    }
}
