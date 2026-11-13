package fe8.game.bm

import fe8.game.RomView
import fe8.game.scene.IntSupplier
import fe8.game.ui.ItemIcon
import fe8.game.ui.UiFrame
import gbafe.proc.Proc
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.name
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.setEndCb
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.whileTrue
import gbafe.render.BgHardware
import gbafe.render.BgTilemap
import gbafe.render.SpriteLayers
import gbafe.render.SpriteObject
import gbafe.text.MsgStrings
import gbafe.text.Text
import gbafe.text.TextSystem

/**
 * The battle forecast window of src/bksel.c: the proc `gProcScr_BKSEL`, its fields and the
 * numbers it caches (the hit counts and the effectiveness flags). The forecast battle itself is
 * [BattleGenerate.battleGenerateSimulation].
 *
 * Only the standard layout (`battleForecastType` 0) and the path the Prologue capture takes are
 * written. The panel is drawn into the two scratch tilemaps ([tilemapA], [tilemapB], the
 * original's `gUiTmScratchA` and `B`), the text strips and number tiles in VRAM and the frame and
 * icon palettes; the slide copies it onto BG 0 and 1; the display puts the weapon triangle
 * arrows and the multiplier marks as sprites and flashes the icon palettes. Never run by the
 * capture and not written: the extended layout
 * (`DrawBattleForecastContentsExtended`, `PutBattleForecastTilemaps`), the help box
 * (`StartBattleForecastHelpBox`), the ballista and obstacle forecasts.
 */
public class BattleForecast(
    private val engine: ProcEngine,
    private val battle: BmBattle,
    private val gen: BattleGenerate,
    private val items: ItemInfo,
    private val units: UnitTable,
    private val active: ActiveUnit,
    private val playSt: PlaySt,
    private val bm: BmSt,
    private val map: BmMap,
    private val hardware: BgHardware,
    private val rom: RomView,
    private val text: TextSystem,
    private val messages: MsgStrings,
    /** `DrawnIconLookupTable` and `IconGFXIDLookupTable` ([ItemIcon]). */
    private val drawnIconLookupTable: ByteArray,
    private val iconGfxIdLookupTable: ByteArray,
    /** The sprite lists the arrows and multipliers are put on (`PutSprite`). */
    private val sprites: SpriteLayers,
    /** `GetGameClock`. */
    private val clock: IntSupplier,
) {
    /** `gUiTmScratchA` (0x500 bytes): the panel's BG 0 tilemap, 32 entries a row. */
    public val tilemapA: ShortArray = ShortArray(SCRATCH_ENTRIES)

    /** `gUiTmScratchB`: the panel's BG 1 tilemap (the frame). */
    public val tilemapB: ShortArray = ShortArray(SCRATCH_ENTRIES)

    /**
     * `gBkselPals`: 8 palettes of 16 colours, the icon palette brightened a step more each (the
     * palette flash of [updateBattleForecastEffectivenessPalettes]).
     */
    public val bkselPals: ShortArray = ShortArray(BKSEL_PAL_COLORS)

    /** `gaBattleForecastTextStructs`: the six stat labels. */
    public val labels: Array<Text> = Array(LABEL_COUNT) { Text() }

    private val tsa = ByteArray(TSA_BYTES)
    private val gfx = ByteArray(GFX_BYTES)

    /** `struct BattleForecastProc` past `PROC_HEADER` (the text structs are drawing). */
    public class State {
        /** `unk_2C` (+0x2C): frames the display has run. */
        public var frameCount: Int = 0

        /** `x` (+0x30, s8). */
        public var x: Int = 0

        /** `y` (+0x31, s8). */
        public var y: Int = 0

        /** `frameKind` (+0x32): 1 standard, 2 extended. */
        public var frameKind: Int = 0

        /** `ready` (+0x33, s8): [forecastInit] ran. */
        public var ready: Int = 0

        /** `needContentUpdate` (+0x34, s8). */
        public var needContentUpdate: Int = 0

        /** `side` (+0x35, s8): -1 is left, +1 is right. */
        public var side: Int = 0

        /** `slide_offset` (+0x36, s8). */
        public var slideOffset: Int = 0

        /** `hitCountA` (+0x50, s8): the actor's hits, a brave weapon counting twice. */
        public var hitCountA: Int = 0

        /** `hitCountB` (+0x51, s8). */
        public var hitCountB: Int = 0

        /** `isEffectiveA` (+0x52, s8). */
        public var isEffectiveA: Int = 0

        /** `isEffectiveB` (+0x53, s8). */
        public var isEffectiveB: Int = 0

        /** `unitNameTextA` (+0x38). */
        public val unitNameTextA: Text = Text()

        /** `unitNameTextB` (+0x40). */
        public val unitNameTextB: Text = Text()

        /** `itemNameText` (+0x48). */
        public val itemNameText: Text = Text()
    }

    /** Services outside the ported file. */
    public class Hooks {
        /** `EventEngineExists` (`MapEventEngineExists_`): the proc waits while an event runs. */
        public var eventEngineExists: () -> Boolean = { false }

        /**
         * `CheckBattleForecastTutorialEvent` is 1 (the first forecast of the tutorial chapter).
         * Stand-in until PC-10 (the Easy-mode tutorial): no tutorial event runs.
         */
        public var tutorialPending: () -> Boolean = { false }
    }

    /** The hooks, set by the game. */
    public val hooks: Hooks = Hooks()

    private var usesLeft = 0

    /** `gObject_16x16` (ROM), the multiplier marks' object. */
    private val object16x16: SpriteObject by lazy { rom.objN(OBJECT_16X16) }

    /** `sSprite_SysUpArrowA`..`C` (the frames of `gSysUpArrowSpriteLut`). */
    private val upArrows: Array<SpriteObject> by lazy { arrowLut(UP_ARROW_LUT) }

    /** `sSprite_SysDownArrowA`..`C`. */
    private val downArrows: Array<SpriteObject> by lazy { arrowLut(DOWN_ARROW_LUT) }

    private fun arrowLut(at: Int): Array<SpriteObject> =
        Array(3) { rom.objN(rom.u32(at + 4 * it)) }

    /**
     * `gProcScr_BKSEL` (0x0859E520): NAME, end callback [forecastOnEnd], CALL `ClearBg0Bg1`,
     * SLEEP 0, CALL [forecastInit], LABEL 0, WHILE an event runs, CALL [onNewBattle], REPEAT
     * [loopSlideIn], CALL the tutorial event, REPEAT [loopDisplay], REPEAT [loopSlideOut], GOTO
     * 0, LABEL 1, REPEAT [loopSlideOut], END.
     */
    public val script: ProcScript = ProcScript(
        "gProcScr_BKSEL",
        listOf(
            name("BKSEL"),
            setEndCb(ProcFunc { forecastOnEnd() }),
            call(ProcFunc { UiFrame.clearBg0Bg1(hardware) }),
            sleep(0),
            call(ProcFunc { forecastInit(it.state as State) }),
            label(0),
            whileTrue(ProcBoolFunc { mapEventEngineExists() }),
            call(ProcFunc { onNewBattle(it.state as State) }),
            repeat(ProcFunc { loopSlideIn(it) }),
            call(ProcFunc { triggerTutorial() }),
            repeat(ProcFunc { loopDisplay(it) }),
            repeat(ProcFunc { loopSlideOut(it) }),
            goto(0),
            label(1),
            repeat(ProcFunc { loopSlideOut(it) }),
            end(),
        ),
        newState = { State() },
    )

    /**
     * Ported from src/bksel.c: MapEventEngineExists_
     *
     * Whether an event runs ([Hooks.eventEngineExists]); the proc waits while one does.
     */
    private fun mapEventEngineExists(): Boolean = hooks.eventEngineExists()

    /** `Proc_Find(gProcScr_BKSEL)`. */
    public fun find(): Proc? = engine.find(script)

    /**
     * Ported from src/bksel.c: NewBattleForecast
     *
     * Starts the forecast proc (tree 3) for the chosen layout (0 standard, 1 extended; 2 shows
     * none: only the text font is reset, not verified: the capture uses the standard one) and
     * sets every tile of the movement map to -1. The tutorial event's key mask is not written
     * (no capture has the event).
     */
    public fun newBattleForecast() {
        if (playSt.battleForecastType == 2) {
            text.resetTextFont()
            return
        }

        val proc = engine.start(script, PROC_TREE_3)
        val state = proc.state as State
        state.ready = 0

        when (playSt.battleForecastType) {
            0 -> state.frameKind = 1
            1 -> state.frameKind = 2
        }

        map.bmMapFill(map.movement, -1)
    }

    /**
     * Ported from src/bksel.c: BattleForecast_Init
     *
     * Loads the panel's two-by-four tile graphic (OBJ VRAM) and palette, restarts the text font
     * and the icon bookkeeping, sets up the labels and the three name strips (two double-buffered
     * strips for the names, one for the item) and BG 1's position, and marks the proc ready for
     * content updates.
     */
    private fun forecastInit(state: State) {
        for (i in 0 until TSA_BYTES) tsa[i] = rom.u8(TSA_ADDRESS + i).toByte()
        rom.decompressInto(GFX_ADDRESS, gfx, 0)
        copy2dChr(gfx, GFX_VRAM, 4, 2)
        applyPalette(PAL_X2X4_ADDRESS, 0x12)

        text.resetTextFont()

        ItemIcon.resetIconGraphics(drawnIconLookupTable, iconGfxIdLookupTable)

        initBattleForecastIconPaletteBuffer()

        initBattleForecastLabels()

        text.initTextDb(state.unitNameTextA, 6)
        text.initTextDb(state.unitNameTextB, 6)
        text.initTextDb(state.itemNameText, 7)

        hardware.lcd.setBgPosition(1, 0, -1)

        state.ready = 1
    }

    /**
     * Ported from src/bmlib.c: Copy2dChr
     *
     * Copies [height] rows of [width] tiles from [src] to VRAM at [dst] (a byte offset of VRAM),
     * the rows a tile map row (32 tiles) apart.
     */
    private fun copy2dChr(src: ByteArray, dst: Int, width: Int, height: Int) {
        val lineSize = width * CHR_SIZE
        for (row in 0 until height) {
            src.copyInto(
                hardware.vram, dst + row * CHR_SIZE * 0x20, row * lineSize, (row + 1) * lineSize,
            )
        }
    }

    /** `ApplyPalette`: 16 colours from ROM [at] to palette slot [palId]. */
    private fun applyPalette(at: Int, palId: Int) {
        for (i in 0 until 16) hardware.palette[palId * 16 + i] = rom.u16(at + 2 * i).toShort()
        hardware.enablePaletteSync()
    }

    /**
     * Transcribed from src/bksel.c: BattleForecast_OnEnd (not verified: the frame palette it
     *   restores is the window's own in the capture, so no mutation changes captured data)
     *
     * The end callback: puts the window frame graphics and palette back (`UnpackUiFrameBuffered`).
     */
    private fun forecastOnEnd() {
        UiFrame.loadUiFrameGraphics(hardware, rom, playSt.windowColor)
    }

    /**
     * Ported from src/bksel.c: InitBattleForecastIconPaletteBuffer
     *
     * Loads icon palette 0 into palette 3 and makes [bkselPals]: for each colour of it eight
     * steps, each three levels brighter per channel (capped at 31).
     */
    private fun initBattleForecastIconPaletteBuffer() {
        ItemIcon.loadIconPalette(hardware, rom, 0, 3)

        for (i in 1 until 16) {
            val color = hardware.palette[3 * 16 + i].toInt()

            var red = color and 31
            var green = (color shr 5) and 31
            var blue = (color shr 10) and 31

            for (j in 0 until 8) {
                bkselPals[j * 16 + i] = ((blue shl 10) + (green shl 5) + red).toShort()

                red += 3
                if (red > 31) red = 31

                green += 3
                if (green > 31) green = 31

                blue += 3
                if (blue > 31) blue = 31
            }
        }
    }

    /**
     * Ported from src/bksel.c: InitBattleForecastLabels
     *
     * Draws the six stat labels (Mt, Hit, Crit, Atk, Def, AS), centred in four tiles, into
     * [labels].
     */
    private fun initBattleForecastLabels() {
        for (i in 0 until LABEL_COUNT) {
            val label = labels[i]
            text.initText(label, 4)

            val str = messages.getStringFromIndex(LABEL_STRINGS[i])

            text.textInsertDrawString(label, text.getStringTextCenteredPos(0x20, str), 3, str)
        }
    }

    /**
     * Ported from src/bksel.c: PutBattleForecastUnitName
     *
     * Draws the name of [unit] centred in 48 pixels into [strip] and puts it at [dest] of
     * [tilemapA].
     */
    private fun putBattleForecastUnitName(dest: Int, strip: Text, unit: BattleUnit) {
        val str = messages.getStringFromIndex(rom.u16(u32(unit.bytes, 0)))
        val position = text.getStringTextCenteredPos(48, str)

        text.clearText(strip)
        text.putDrawText(strip, tilemapA, dest, 0, position, str)
    }

    /**
     * Ported from src/bksel.c: PutBattleForecastItemName
     *
     * Draws the name of [item] centred in 56 pixels into [strip] and puts it at [dest] of
     * [tilemapA].
     */
    private fun putBattleForecastItemName(dest: Int, strip: Text, item: Int) {
        val str = items.getItemName(item, messages)
        val position = text.getStringTextCenteredPos(56, str)

        text.clearText(strip)
        text.putDrawText(strip, tilemapA, dest, 0, position, str)
    }

    /**
     * Ported from src/bksel.c: GetFactionBattleForecastFramePalette
     *
     * The ROM address of the frame palette of [faction] (blue, red, green, purple).
     */
    private fun getFactionBattleForecastFramePalette(faction: Int): Int = when (faction) {
        FACTION_BLUE -> FRAME_PALETTE_A
        FACTION_RED -> FRAME_PALETTE_B
        FACTION_GREEN -> FRAME_PALETTE_C
        else -> FRAME_PALETTE_D
    }

    /**
     * Ported from src/bksel.c: InitBattleForecastFramePalettes
     *
     * Colours the frame: palette 1 for the actor's faction, palette 2 for the target's (purple
     * for a wall or trap, which has no unit index).
     */
    private fun initBattleForecastFramePalettes() {
        applyPalette(
            getFactionBattleForecastFramePalette(battle.actor.bytes[0x0B].toInt() and 0xC0), 1,
        )

        if (battle.target.bytes[0x0B].toInt() != 0) {
            applyPalette(
                getFactionBattleForecastFramePalette(battle.target.bytes[0x0B].toInt() and 0xC0),
                2,
            )
        } else {
            applyPalette(getFactionBattleForecastFramePalette(FACTION_PURPLE), 2)
        }
    }

    /**
     * Ported from src/bksel.c: DrawBattleForecastContentsStandard
     *
     * Draws the standard panel: the frame into [tilemapB]; into [tilemapA] the names, the HP,
     * damage, hit and crit numbers of both sides (a dash when the target cannot counter), the
     * labels and the two weapon icons.
     */
    private fun drawBattleForecastContentsStandard(state: State) {
        val actor = battle.actor
        val target = battle.target
        var damage: Int
        var critRate: Int

        BgTilemap.applyTsa(tilemapB, 0, tsa, 0, 0x1000)

        fillRect(tilemapA, 10, 15, 0)

        putBattleForecastUnitName(0x23, state.unitNameTextA, actor)

        putBattleForecastUnitName(0x161, state.unitNameTextA, target)

        putBattleForecastItemName(0x1A1, state.itemNameText, target.weaponBefore)

        if (target.weapon == 0 && target.weaponBroke == 0) {
            damage = -1

            target.battleEffectiveHitRate = 0xFF
            target.battleEffectiveCritRate = 0xFF
        } else {
            damage = target.battleAttack - actor.battleDefense

            if (damage < 0) damage = 0
        }

        text.putNumberTwoChr(
            tilemapA, 0x62, 2, if (target.hpInitial > 99) 0xFF else target.hpInitial,
        )

        text.putNumberTwoChr(tilemapA, 0xA2, 2, damage)
        text.putNumberTwoChr(tilemapA, 0xA2 + 0x40, 2, target.battleEffectiveHitRate)
        text.putNumberTwoChr(tilemapA, 0xA2 + 0x80, 2, target.battleEffectiveCritRate)

        damage = actor.battleAttack - target.battleDefense

        if (items.getItemIndex(actor.weapon) == ITEM_MONSTER_STONE) damage = 0xFF

        if (damage < 0) damage = 0

        critRate = actor.battleEffectiveCritRate

        if (items.getItemIndex(actor.weapon) == ITEM_MONSTER_STONE) critRate = 0xFF

        if (critRate < 0) critRate = 0

        text.putNumberTwoChr(
            tilemapA, 0xA8 - 0x40, 2, if (actor.hpInitial > 99) 0xFF else actor.hpInitial,
        )

        text.putNumberTwoChr(tilemapA, 0xA8, 2, damage)
        text.putNumberTwoChr(tilemapA, 0xA8 + 0x40, 2, actor.battleEffectiveHitRate)
        text.putNumberTwoChr(tilemapA, 0xA8 + 0x80, 2, critRate)

        text.putTwoSpecialChar(
            tilemapA, 0xA8 - 0x44, TEXT_COLOR_SYSTEM_GOLD, TextSystem.SPECIAL_HP_A,
            TextSystem.SPECIAL_HP_B,
        )

        text.putText(labels[0], tilemapA, 0xA8 - 5)
        text.putText(labels[1], tilemapA, 0xA8 + 0x3B)
        text.putText(labels[2], tilemapA, 0xA8 + 0x7B)

        ItemIcon.drawIcon(
            tilemapA, 0xA8 + 0xBF, items.getItemIconId(target.weaponBefore), 0x4000,
            drawnIconLookupTable, iconGfxIdLookupTable, hardware, rom,
        )

        ItemIcon.drawIcon(
            tilemapA, 0xA8 - 0x87, items.getItemIconId(actor.weaponBefore), 0x3000,
            drawnIconLookupTable, iconGfxIdLookupTable, hardware, rom,
        )
    }

    /**
     * `TileMap_FillRect` (`TmFillRect`, asm/arm.s): [value] into the entries of [tm] from column
     * 0 to [w] and row 0 to [h] inclusive (its counters run down to -1), a row 32 entries apart.
     */
    private fun fillRect(tm: ShortArray, w: Int, h: Int, value: Int) {
        for (y in 0..h) {
            for (x in 0..w) tm[y * 0x20 + x] = value.toShort()
        }
    }

    private fun u32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)

    /**
     * Ported from src/bksel.c: GetBattleForecastPanelSide
     *
     * The side of the screen the panel goes on, opposite the target: 1 (right) when the target
     * is left of the middle column, -1 when right of it, 0 on it.
     */
    public fun getBattleForecastPanelSide(): Int {
        val x = battle.target.unitX * 16 - bm.cameraX

        if (x < MIDDLE_X) return 1

        if (x > MIDDLE_X) return -1

        return 0
    }

    /**
     * Ported from src/bksel.c: BattleForecastHitCountUpdate
     *
     * One strike of [battleUnit]: [hits] grows by one (two for a brave weapon) while the weapon
     * has uses left; [usesLeft] counts them down (not verified: a brave weapon is in no capture).
     * Returns the new hit count.
     */
    private fun battleForecastHitCountUpdate(battleUnit: BattleUnit, hits: Int): Int {
        var hitsCounter = hits
        if (usesLeft > 0) {
            hitsCounter = (hitsCounter + 1) and 0xFF
            usesLeft -= 1

            if (battleUnit.weaponAttributes and ItemInfo.IA_BRAVE != 0) {
                hitsCounter = (hitsCounter + 1) and 0xFF
                usesLeft -= 1
            }
        }
        return hitsCounter
    }

    /**
     * Ported from src/bksel.c: InitBattleForecastBattleStats
     *
     * Counts each side's hits (a follow-up strike for the one that strikes first, as many as its
     * weapon has uses) and whether each side's attack is effective or reverts the weapon
     * triangle (not verified: no capture has an effective attack).
     */
    private fun initBattleForecastBattleStats(state: State) {
        val actor = battle.actor
        val target = battle.target

        usesLeft = items.getItemUses(actor.weaponBefore)

        val followUp = gen.battleGetFollowUpOrder()
        val first = gen.firstStriker

        state.hitCountA = 0
        state.isEffectiveA = 0

        if (actor.weapon != 0 || actor.weaponBroke != 0) {
            state.hitCountA = battleForecastHitCountUpdate(actor, state.hitCountA)

            if (followUp && first === actor) {
                state.hitCountA = battleForecastHitCountUpdate(first, state.hitCountA)
            }

            if (battle.isUnitEffectiveAgainst(actor.bytes, 0, target.bytes, 0)) {
                state.isEffectiveA = 1
            }

            if (battle.isItemEffectiveAgainst(actor.weaponBefore, target.bytes, 0)) {
                state.isEffectiveA = 1
            }

            if (actor.wTriangleHitBonus > 0 &&
                actor.weaponAttributes and ItemInfo.IA_REVERTTRIANGLE != 0
            ) {
                state.isEffectiveA = 1
            }
        }

        state.hitCountB = 0
        state.isEffectiveB = 0
        usesLeft = items.getItemUses(target.weaponBefore)

        if (target.weapon != 0 || target.weaponBroke != 0) {
            state.hitCountB = battleForecastHitCountUpdate(target, state.hitCountB)

            if (followUp && first === target) {
                state.hitCountB = battleForecastHitCountUpdate(first, state.hitCountB)
            }

            if (battle.isUnitEffectiveAgainst(target.bytes, 0, actor.bytes, 0)) {
                state.isEffectiveB = 1
            }

            if (battle.isItemEffectiveAgainst(target.weaponBefore, actor.bytes, 0)) {
                state.isEffectiveB = 1
            }

            if (target.wTriangleHitBonus > 0 &&
                target.weaponAttributes and ItemInfo.IA_REVERTTRIANGLE != 0
            ) {
                state.isEffectiveB = 1
            }
        }
    }

    /**
     * Ported from src/bksel.c: DrawBattleForecastContents
     *
     * Clears the frame count and the update flag, counts the hits and draws the standard panel.
     * The extended layout (frame kind 2, `DrawBattleForecastContentsExtended`) is not written: no
     * capture runs it.
     */
    private fun drawBattleForecastContents(state: State) {
        state.frameCount = 0
        state.needContentUpdate = 0

        when (state.frameKind) {
            1 -> {
                initBattleForecastBattleStats(state)
                drawBattleForecastContentsStandard(state)
            }
        }
    }

    /**
     * Ported from src/bksel.c: BattleForecast_OnNewBattle
     *
     * The proc's fields for a new forecast: contents, the side (the panel slides in from it),
     * the slide counter and the position, and colours the frame (not verified: the right side's
     * position 20, the capture's panel is on the left).
     */
    private fun onNewBattle(state: State) {
        drawBattleForecastContents(state)

        state.side = getBattleForecastPanelSide()
        state.slideOffset = 0

        state.x = if (state.side < 0) 0 else 20

        state.y = 0

        initBattleForecastFramePalettes()
    }

    /**
     * Transcribed from src/bksel.c: PutBattleForecastTilemaps (not verified: only a redraw of
     *   the contents puts the whole panel, and the capture never changes the target)
     *
     * Copies the whole panel from the scratch tilemaps onto BG 0 and 1 at its side and flags
     * both for sync.
     */
    private fun putBattleForecastTilemaps(state: State) {
        val height = if (state.frameKind == 1) 16 else 20
        val at = if (state.side < 0) 0 else 20

        copyRect(tilemapA, 0, hardware.mapBuffer(0), at, 10, height)
        copyRect(tilemapB, 0, hardware.mapBuffer(1), at, 10, height)

        hardware.enableBgSyncByMask(3)
    }

    /**
     * `TileMap_CopyRect`: copies [w] by [h] entries from [src] at [from] to [dst] at [to], the
     * rows 32 entries apart.
     */
    private fun copyRect(src: ShortArray, from: Int, dst: ShortArray, to: Int, w: Int, h: Int) {
        for (row in 0 until h) {
            val start = from + row * 0x20
            src.copyInto(dst, to + row * 0x20, start, start + w)
        }
    }

    /**
     * The step of `BattleForecast_LoopSlideIn` and `_LoopSlideOut` (they differ in [lut] only):
     * clears BG 0 and 1, puts as many columns of the panel as the [lut] entry says at the screen
     * edge of its side (the panel's last columns at the left edge, its first at the right), and
     * counts the four steps (not verified: the right side, the capture's panel is on the left).
     */
    private fun slideStep(proc: Proc, lut: IntArray) {
        val state = proc.state as State
        val height = if (state.frameKind == 1) 16 else 20

        val bg0 = hardware.mapBuffer(0)
        val bg1 = hardware.mapBuffer(1)

        hardware.fill(bg0, 0)
        hardware.fill(bg1, 0)

        hardware.enableBgSyncByMask(3)

        val offset = lut[state.slideOffset]

        if (state.side < 0) {
            copyRect(tilemapA, 10 - offset, bg0, 0, offset, height)
            copyRect(tilemapB, 10 - offset, bg1, 0, offset, height)
        } else {
            copyRect(tilemapA, 0, bg0, 30 - offset, offset, height)
            copyRect(tilemapB, 0, bg1, 30 - offset, offset, height)
        }

        state.slideOffset++

        if (state.slideOffset and 0xFF == SLIDE_STEPS) {
            state.slideOffset = 0
            engine.breakProc(proc)
        }
    }

    /**
     * Ported from src/bksel.c: BattleForecast_LoopSlideIn
     *
     * Four steps: the panel comes in from its side, 6, 8, 9 and 10 columns wide.
     */
    private fun loopSlideIn(proc: Proc) {
        slideStep(proc, SLIDE_IN_LUT)
    }

    /**
     * Ported from src/bksel.c: BattleForecast_LoopSlideOut
     *
     * Four steps: the panel goes out again, 9, 8, 6 and 0 columns wide.
     */
    private fun loopSlideOut(proc: Proc) {
        slideStep(proc, SLIDE_OUT_LUT)
    }

    /**
     * Ported from src/bm.c: UpdateStatArrowSprites
     *
     * Puts the stat arrow (up, or down when [isDown]) at ([x], [y]) on layer 4; its frame
     * changes every 8 clock ticks.
     */
    private fun updateStatArrowSprites(x: Int, y: Int, isDown: Boolean) {
        val frame = clock() / 8 % 3

        sprites.put(4, x, y, if (isDown) downArrows[frame] else upArrows[frame], 0)
    }

    /**
     * Ported from src/bksel.c: PutBattleForecastWeaponTriangleArrows
     *
     * Puts an arrow by each side's stat block when its weapon has the triangle's advantage (up)
     * or disadvantage (down).
     */
    private fun putBattleForecastWeaponTriangleArrows(state: State) {
        var wtArrowA = 0
        var wtArrowB = 0

        if (battle.actor.wTriangleHitBonus > 0) wtArrowA = 1
        if (battle.actor.wTriangleHitBonus < 0) wtArrowA = 2
        if (battle.target.wTriangleHitBonus > 0) wtArrowB = 1
        if (battle.target.wTriangleHitBonus < 0) wtArrowB = 2

        if (wtArrowB != 0) {
            updateStatArrowSprites((state.x + 8) * 8 + 3, (state.y + 11) * 8, wtArrowB == 2)
        }

        if (wtArrowA != 0) {
            updateStatArrowSprites((state.x + 2) * 8 + 3, (state.y + 1) * 8, wtArrowA == 2)
        }
    }

    /**
     * Ported from src/bksel.c: PutBattleForecastMultipliers
     *
     * Puts the x2 (x3, x4) mark, swaying, by each side that strikes more than once (not
     * verified: the target's mark and the panel's y, the capture's target strikes once and the
     * panel's y is 0).
     */
    private fun putBattleForecastMultipliers(state: State) {
        val angle = (state.frameCount * 4) and 0xFF

        var x = sin(angle) * 4 shr 12
        var y = cos(angle) * 2 shr 12

        x += state.x * 8 - 3
        y += state.y * 8

        if (state.hitCountA > 1) {
            sprites.put(4, x + 72, y + 40, object16x16, state.hitCountA + MULTIPLIER_OAM2)
        }

        if (state.hitCountB > 1) {
            sprites.put(4, x + 24, y + 40, object16x16, state.hitCountB + MULTIPLIER_OAM2)
        }
    }

    /** `SIN`: `gSinLookup[angle]`. */
    private fun sin(angle: Int): Int = rom.u16(SIN_LOOKUP + 2 * (angle and 0xFF)).toShort().toInt()

    /** `COS`: `gSinLookup[angle + 0x40]`. */
    private fun cos(angle: Int): Int = sin(angle + 0x40)

    /**
     * Ported from src/bksel.c: UpdateBattleForecastEffectivenessPalettes
     *
     * Flashes icon palettes 3 and 4 (the two weapon icons) through the brightened steps of
     * [bkselPals] while that side's attack is effective (not verified: the flash steps, nothing
     * is effective in the capture, so palettes 3 and 4 stay at step 0).
     */
    private fun updateBattleForecastEffectivenessPalettes(state: State) {
        val step = PAL_ANIM_LUT[state.frameCount and 0x1F]

        applyBkselPalette(if (state.isEffectiveA != 0) step else 0, 3)
        applyBkselPalette(if (state.isEffectiveB != 0) step else 0, 4)
    }

    /** `ApplyPalette(gBkselPals[step], palId)`. */
    private fun applyBkselPalette(step: Int, palId: Int) {
        for (i in 0 until 16) hardware.palette[palId * 16 + i] = bkselPals[step * 16 + i]
        hardware.enablePaletteSync()
    }

    /**
     * Ported from src/bksel.c: BattleForecast_LoopDisplay
     *
     * Every frame: counts it, and when the contents need an update (not verified: the capture
     * never changes the target) redraws them and puts the panel, or ends the step when the
     * panel must change side. The sprites and the palette flash follow.
     */
    private fun loopDisplay(proc: Proc) {
        val state = proc.state as State
        state.frameCount++

        if (state.needContentUpdate != 0) {
            val side = getBattleForecastPanelSide()

            if (side != 0 && side != state.side) {
                engine.breakProc(proc)
                return
            }

            drawBattleForecastContents(state)
            putBattleForecastTilemaps(state)
            initBattleForecastFramePalettes()
        }

        if (state.frameKind == 1) {
            putBattleForecastWeaponTriangleArrows(state)
            putBattleForecastMultipliers(state)
            updateBattleForecastEffectivenessPalettes(state)
        }
    }

    /**
     * Transcribed from src/bksel.c: TriggerBattleForcastToturialEvent (not verified: the capture
     *   starts no tutorial event, so no mutation changes captured data)
     *
     * Starts the forecast tutorial event when it is due ([Hooks.tutorialPending]); the event
     * itself (and the key mask change before it) is not written.
     */
    private fun triggerTutorial() {
        hooks.tutorialPending()
    }

    /**
     * `BattleGenerateSimulation(gActiveUnit, unit, -1, -1, gActionData.itemSlotIndex)` then
     * [updateBattleForecastContents], the forecast part of `AttackMapSelect_SwitchIn` for the
     * unit [unitId] (the ballista and obstacle forecasts are not written: no capture has them).
     */
    public fun simulateAndUpdate(unitId: Int) {
        gen.battleGenerateSimulation(
            units.bytes, units.unitOffset(active.unit), units.unitOffset(unitId), -1, -1,
            active.action.itemSlotIndex,
        )
        updateBattleForecastContents()
    }

    /**
     * Transcribed from src/bksel.c: UpdateBattleForecastContents (not verified: the capture's one
     *   call comes before the proc is ready, and the contents are redrawn at the start anyway)
     *
     * Asks a ready forecast to redraw its contents.
     */
    public fun updateBattleForecastContents() {
        val proc = find() ?: return
        val state = proc.state as State

        if (state.ready != 0) state.needContentUpdate = 1
    }

    /**
     * Ported from src/bksel.c: CloseBattleForecast
     *
     * Ends the forecast: a proc that is not ready yet is ended at once (not verified: the
     * capture closes it when ready) with BG 0 and 1 cleared; a ready one slides out
     * (`Proc_Goto` label 1).
     */
    public fun closeBattleForecast() {
        val proc = find() ?: return
        val state = proc.state as State

        if (state.ready == 0) {
            UiFrame.clearBg0Bg1(hardware)
            engine.end(proc)
            return
        }
        engine.goto(proc, 1)
    }

    /** `Proc_EndEach(gProcScr_BKSEL)`: the forecast ends at once (A on a target). */
    public fun endForecast() {
        engine.endEach(script)
    }

    private companion object {
        const val PROC_TREE_3 = 3
        const val SCRATCH_ENTRIES = 0x280
        const val BKSEL_PAL_COLORS = 8 * 16
        const val LABEL_COUNT = 6
        const val TSA_BYTES = 0x200
        const val GFX_BYTES = 0x400
        const val CHR_SIZE = 0x20
        const val GFX_ADDRESS = 0x085A0C80
        const val GFX_VRAM = 0x15D00
        const val PAL_X2X4_ADDRESS = 0x085A0D2C
        const val TSA_ADDRESS = 0x085A09A8
        const val FRAME_PALETTE_A = 0x085B6BB4
        const val FRAME_PALETTE_B = 0x085B6BD4
        const val FRAME_PALETTE_C = 0x085B6BF4
        const val FRAME_PALETTE_D = 0x085B6C14
        const val FACTION_BLUE = 0x00
        const val FACTION_GREEN = 0x40
        const val FACTION_RED = 0x80
        const val FACTION_PURPLE = 0xC0
        const val ITEM_MONSTER_STONE = 0xB5
        const val TEXT_COLOR_SYSTEM_GOLD = 3

        /** Text indexes of Mt, Hit, Crit, Atk, Def and AS. */
        val LABEL_STRINGS = intArrayOf(0x503, 0x4F4, 0x501, 0x4F3, 0x4EF, 0x504)
        const val MIDDLE_X = 0x70
        const val SLIDE_STEPS = 4
        const val OBJECT_16X16 = 0x08590F4C
        const val UP_ARROW_LUT = 0x0859A530
        const val DOWN_ARROW_LUT = 0x0859A53C
        const val SIN_LOOKUP = 0x080D751C
        const val MULTIPLIER_OAM2 = 0x22E6
        val PAL_ANIM_LUT = intArrayOf(
            0, 1, 1, 2, 3, 4, 5, 5, 4, 4, 4, 3, 3, 3, 2, 2,
            2, 2, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        )
        val SLIDE_IN_LUT = intArrayOf(6, 8, 9, 10)
        val SLIDE_OUT_LUT = intArrayOf(9, 8, 6, 0)
    }
}
