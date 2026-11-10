package fe8.game

import fe8.game.bm.BMapMain
import fe8.game.bm.BattleGenerate
import fe8.game.bm.BmBattle
import fe8.game.bm.ItemInfo
import fe8.game.bm.BMapMain.Companion.SAVE_ID_SUSPEND
import fe8.game.bm.BattleMapNodes
import fe8.game.bm.BattleMapStart
import fe8.game.bm.BmMap
import fe8.game.bm.BmSt
import fe8.game.bm.BmMapRender
import fe8.game.bm.CamMove
import fe8.game.bm.ChapterData
import fe8.game.bm.ChapterMapGfx
import fe8.game.bm.MapAnimations
import fe8.game.bm.Mu
import fe8.game.bm.MuCtrl
import fe8.game.bm.MuCtrlHooks
import fe8.game.bm.Reda
import fe8.game.bm.MuConfig
import fe8.game.bm.MuHooks
import fe8.game.bm.MuProc
import fe8.game.bm.MuSpriteAnim
import fe8.game.bm.MapCursor
import fe8.game.bm.MapMusic
import fe8.game.bm.EntityBmMaps
import fe8.game.bm.PLAY_FLAG_HARD
import fe8.game.bm.PhaseIntro
import fe8.game.bm.PlayerPhase
import fe8.game.bm.ResetCursorPosition
import fe8.game.bm.UnitLoader
import fe8.game.ui.GotItemPopup
import fe8.game.ui.ItemIcon
import fe8.game.ui.PopupScript
import fe8.game.bm.UnitSpriteHandles
import fe8.game.bm.UnitSprites
import fe8.game.bm.UnitSpritesOam
import fe8.game.bm.UnitTable
import fe8.game.event.EventConst
import fe8.game.event.EventEngine
import fe8.game.event.EventEngineHooks
import fe8.game.event.EventBgm
import fe8.game.event.EventAsmCall
import fe8.game.event.EventGiveItem
import fe8.game.event.bmGuideTextSetAllGreen
import fe8.game.event.EventFade
import fe8.game.event.EventHandler
import fe8.game.event.EventScrCore
import fe8.game.event.EventScriptBattle
import fe8.game.event.EventScriptBattleHost
import fe8.game.event.EventUnitState
import fe8.game.event.EventUnitStateHooks
import fe8.game.event.EventState
import fe8.game.event.EventText
import fe8.game.event.EventTextBg
import fe8.game.event.EventTextBgHost
import fe8.game.event.EventTextTalkHost
import fe8.game.event.EventCursorCamera
import fe8.game.event.EventCursorCameraHooks
import fe8.game.event.MapCursorProc
import fe8.game.event.createProcScrEventDisplayCursor
import fe8.game.event.EventEngineProc
import fe8.game.event.EventUnitLoads
import fe8.game.event.EventChangeMap
import fe8.game.event.EventInfoHooks
import fe8.game.event.EventInfos
import fe8.game.event.EventMoveUnit
import fe8.game.event.EventMoveUnitHost
import fe8.game.event.EventUnitLoadsHost
import fe8.game.event.EventPopup
import fe8.game.event.ReleaseEventStubs
import fe8.game.scene.BrownTextBox
import fe8.game.event.ScriptMemory
import fe8.game.event.ScriptWords
import fe8.game.flags.Fe8EventFlags
import fe8.game.opanim.EarlyStartUi
import fe8.game.opanim.OpAnim
import fe8.game.save.BmSaveLib
import fe8.game.save.BmSuspendSave
import fe8.game.save.NewGameRam
import fe8.game.save.SuspendRam
import fe8.game.scene.TalkScene
import fe8.game.title.TitleScreen
import gba.audio.M4aDriver
import gba.audio.M4aSound
import gba.audio.PcmStream
import gba.core.sram.CartSram
import gba.core.sram.SaveStorage
import gba.core.sram.flushTo
import gba.core.sram.loadFrom
import gba.render.Camera
import gba.render.ColorMath
import gba.render.FrameBuffer
import gba.render.GpuFrame
import gba.render.HBlankLines
import gba.render.RenderState
import gba.render.SoftwareRenderer
import gba.render.TileSheets
import gbafe.core.flags.EventFlags
import gbafe.core.mechanics.Mechanics
import gbafe.core.rng.RngService
import gbafe.core.time.GameClock
import gbafe.input.KeyStatus
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.render.BgTilemap
import gbafe.render.HardwareSceneLoader
import gbafe.render.ScreenFade
import gbafe.text.Font
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings

/**
 * The boot sequence wired for a device: `StartGame` -> game control -> health & safety screen ->
 * Nintendo / Intelligent Systems logos ([EarlyStartUi]) -> op anim ([OpAnim]) -> title
 * ([TitleScreen]), driven by the [GameLoop] (one [frame] per GBA frame) and drawn from the
 * synced display state ([HardwareSceneLoader] -> [SoftwareRenderer], or the GPU renderer on
 * device). With [m4a], the boot's BGM and sound effects play through [SoundWrapper], mixed in
 * the VBlank exactly as traced (`Fe8BootSoundTest`); CGB (PSG) channels are still silent.
 */
public class Fe8Boot(
    /** ROM data of the boot screens (whole ROM, or the extracted [RomView.BOOT_WINDOWS]). */
    private val rom: RomView,
    /** Raw pressed GBA keys (`REG_KEYINPUT`, active high) for the next step. */
    keyInput: () -> Int,
    /** The game's RNG ([Fe8Game.rng]); the battle map start path draws from it. */
    public val rng: RngService,
    /** Colour-effect arithmetic of [render]; hardware by default. */
    colorMath: ColorMath = ColorMath.Hardware,
    /** The picture [render] draws into. */
    public val screen: FrameBuffer = FrameBuffer(),
    /** The game's m4a state ([Fe8Sound.create], already `m4aSoundInit`ed), or null: silent. */
    public val m4a: M4aSound? = null,
    /** Receives each frame's mixed samples (null: mix only). */
    pcm: PcmStream? = null,
    /**
     * Where [sram] is loaded from at boot and flushed to after each save write; null: kept in
     * memory only.
     */
    private val saveStorage: SaveStorage? = null,
) {
    /** Test hook: told the command code of every event command that runs a no-op release stub. */
    public var onEventStub: ((code: Int) -> Unit)? = null

    /** `gKeyStatusPtr`. */
    public val keys: KeyStatus = KeyStatus()

    /** `gGameClock`. */
    public val clock: GameClock = GameClock()

    /** `gMapCursor` sprite renderer (`PutMapCursor`). */
    public val mapCursor: MapCursor by lazy {
        MapCursor(rom, loop.bmSt, loop.sprites).also { it.clockSource = clock }
    }

    /** Proc trees. */
    public val engine: ProcEngine = ProcEngine()

    /** The early start screens and their display state. */
    public val ui: EarlyStartUi = EarlyStartUi(
        engine,
        EarlyStartUi.Env(
            ShortArray(512), ShortArray(512), gbafe.render.LcdControl(), keys,
            rom.pal(0x08B125F4, 16), rom.pal(0x08B10ABC, 16), rom.pal(0x08B11864, 48),
            gfx = EarlyStartUi.Gfx(
                rom.blob(0x08B11D0C),
                rom.blob(0x08B118C4),
                rom.blob(0x08B11C0C),
                rom.blob(0x08B10630),
                rom.blob(0x08B104D8), rom.blob(0x08B10CA4), rom.blob(0x08B10ADC),
            ),
            fadeOutBgm = { control.sound.fadeOutBgm(it) },
        ),
    )

    private val hw = ui.env.hardware

    /** The blend fades of `src/bmlib.c` (`StartMidFadeFromBlack`, the event fade command). */
    public val screenFade: ScreenFade = ScreenFade(engine, hw.lcd)
    private val io = ByteArray(0x60)

    private lateinit var opAnimRef: OpAnim
    private lateinit var titleRef: TitleScreen

    /** The game control proc's owner. */
    public val control: GameControl =
        GameControl(engine, keys, { ui.script }, { opAnimRef.script }, { titleRef }, m4a)

    /** The cartridge save RAM of this boot (empty until a save is written). */
    public val sram: CartSram = CartSram().also { s -> saveStorage?.let { s.loadFrom(it) } }

    /** The save routines on [sram]. */
    public val saveLib: BmSaveLib by lazy { BmSaveLib(sram).also { it.sramInit() } }

    /** The suspend save's RAM, shared by the write hook and the resume read (`actionData`). */
    private val suspendRam: SuspendRam by lazy {
        SuspendRam(control.playSt, loop.bmSt, rng, units, flags, clock, eventState)
    }

    /** Flushes the cartridge SRAM contents to [saveStorage] if configured. */
    private fun flushSram() {
        saveStorage?.let { sram.flushTo(it) }
    }

    /**
     * Stand-in for `StartSaveMenu` (the save menu is not ported). With a valid suspend save in
     * [sram] it takes the menu's "Resume" choice: reads the suspend save
     * ([BmSuspendSave.readSuspendSave]) and sets `nextAction` to `GAME_ACTION_4`, so the game
     * control proc continues at the resumed game. Otherwise it is the dev "New Game" choice: writes
     * a fresh game to slot 0 ([BmSaveLib.writeNewGameSave], the save menu's difficulty 1 "Normal":
     * `SaveMenuWriteNewGame` passes mode 1, isTutorial 1, not hard; ram_trace_prologue has
     * `config.controller` = 1, so Event19 CHECK_TUTORIAL is false) and leaves `nextAction` at event
     * return, so the game control proc continues to chapter 0.
     */
    private fun newGameSaveMenu() {
        if (saveLib.isValidSuspendSave(SAVE_ID_SUSPEND)) {
            BmSuspendSave(saveLib, rom).readSuspendSave(suspendRam, SAVE_ID_SUSPEND)
            control.setNextGameActionId(GameControl.GAME_ACTION_4)
            return
        }
        saveLib.writeNewGameSave(
            NewGameRam(
                control.playSt, flags, ByteArray(200), initUnits = { units.initUnits() },
            ),
            0, false, 1, 1,
        )
        flushSram()
        control.setNextGameActionId(GameControl.GAME_ACTION_EVENT_RETURN)
    }

    /** The units the battle map start path touches (raw-unit stand-in). */
    public val units: UnitTable by lazy { UnitTable(rom) }

    /** The event engine's globals, shared by [eventEngine] and the suspend save. */
    public val eventState: EventState = EventState()

    /** `gChapterFlagBits` and friends. */
    public val flags: EventFlags = Fe8EventFlags.create()

    /**
     * The event engine (`EventEngine`) with the ported command handlers of [EventScrCore],
     * [EventUnitState] (0x34) and [EventScriptBattle] (0x3F) registered; the other commands stay
     * unknown. Hooks are the defaults (stand-ins), except the map accessors and entity map refresh.
     */
    public val eventEngine: EventEngine by lazy {
        val state = eventState
        val memory = ScriptWords({ rom.u16(it) })
        // `gKeyStatusPtr->newKeys & START_BUTTON`: the game's single KeyStatus.
        val hooks = object : EventEngineHooks {
            override fun startPressed(): Boolean = keys.newKeys and KeyStatus.START_BUTTON != 0
            override fun fadeExists(): Boolean = screenFade.fadeExists()
            override fun blendY(): Int = hw.lcd.blendY
            override fun lockGame() { loop.lockGame() }
            override fun resumeGameplay() { loop.unlockGame() }
            // The talk needs the whole ROM (messages, portraits); with boot windows only the
            // chapter data of the events is missing as well, so the stand-in stays.
            override fun endEventTalk() { if (rom.isWholeRom) eventTextHost.endTalk() }
            override fun eventEndCleanup(proc: EventEngineProc) {
                if (rom.isWholeRom) eventText.sub800E640(proc)
            }
        }
        EventEngine(engine, state, memory, hooks).also {
            EventScrCore(
                state, memory, flags = flags, rng = rng, playSt = control.playSt, keys = keys,
            ).registerAll(it)
            val loader = UnitLoader(
                units, rom,
                // Stand-in: only unk1C[0..1] are modelled in PlaySt.
                { type ->
                    when (type) {
                        0 -> control.playSt.unk1C0
                        1 -> control.playSt.unk1C1
                        else -> 0
                    }
                },
                rng, { control.playSt.chapterStateBits and PLAY_FLAG_HARD != 0 },
            ).apply {
                chapterLevelBonus = {
                    val ps = control.playSt
                    val levels = rom.u16(
                        battleChapters.getROMChapterStruct(ps.chapterIndex) + 0x14,
                    )
                    val hard = ps.chapterStateBits and PLAY_FLAG_HARD != 0
                    when {
                        ps.controller == 0 && !hard -> -(levels and 0xF)
                        hard -> levels ushr 4 and 0xF
                        else -> -(levels ushr 8 and 0xF)
                    }
                }
                bmMap = battleBmMap
                weatherId = { control.playSt.chapterWeatherId }
            }
            // Cursor / camera commands: the camera moves, the cursor proc and SetSomeRealCamPos
            // keep the default no-op stand-ins.
            val unitLookup = EventUnitState(
                state, memory, units, loader, object : EventUnitStateHooks {},
            )
            val cursorScript = createProcScrEventDisplayCursor(
                putMapCursor = { x, y, kind -> mapCursor.put(x, y, kind) },
            )
            EventCursorCamera(
                state, memory, loop.bmSt,
                object : EventCursorCameraHooks {
                    override fun unitPosition(pid: Int): Int {
                        val id = unitLookup.getUnitStructFromEventParameter(pid)
                        if (id < 0) return -1
                        return (units.xPos(id) shl 16) or (units.yPos(id) and 0xFFFF)
                    }

                    override fun startDisplayCursor(x: Int, y: Int, subcmd: Int) {
                        val proc = engine.start(cursorScript, 3)
                        val s = proc.state as MapCursorProc
                        s.x = x
                        s.y = y
                        s.subcmd = subcmd
                        s.timer = 0
                    }

                    override fun endDisplayCursor() {
                        engine.endEach(cursorScript)
                    }

                    override fun ensureCameraOntoPosition(proc: EventEngineProc, x: Int, y: Int) {
                        camMove.ensureCameraOntoPosition(proc.proc, x, y)
                    }

                    override fun cameraOntoPositionCentred(proc: EventEngineProc, x: Int, y: Int) {
                        camMove.startCameraOntoPositionCentred(
                            proc.proc, x, y, battleBmMap.sizeX, battleBmMap.sizeY,
                        )
                    }
                },
            ).registerOn(it)
            EventUnitLoads(
                state, memory, rom, units, loader, rng,
                { x, y -> battleBmMap.terrain[y, x] },
                { x, y -> battleBmMap.unit[y, x] },
                { battleBmMap.bmMapFill(battleBmMap.other, 0) },
                object : EventUnitLoadsHost {
                    override fun camMoveExists(): Boolean =
                        this@Fe8Boot.engine.find(camMove.script) != null

                    override fun ensureCameraOntoPosition(
                        proc: EventEngineProc,
                        x: Int,
                        y: Int,
                    ) = camMove.ensureCameraOntoPosition(proc.proc, x, y)

                    override fun canStartMu(): Boolean = mu.configs.any { c -> c.slot == 0 }
                },
            ).also { loads ->
                loads.registerOn(it)
            }
            EventChangeMap(
                state, memory, control.playSt, loop.bmSt, hw,
                { battleMap.restartBattleMap() },
                { battleEntityMaps.refreshEntityBmMaps() },
                { bmapRender.renderBmMap() },
            ).registerOn(it)
            val unitState = EventUnitState(
                state, memory, units, loader,
                object : EventUnitStateHooks {
                    override fun unitAt(x: Int, y: Int): Int = battleBmMap.unit[y, x]

                    override fun refreshMapAndSprites() {
                        battleEntityMaps.refreshEntityBmMaps()
                        refreshSprites()
                        bmapRender.renderBmMap()
                    }
                },
            ).also { us -> us.registerOn(it) }
            EventScriptBattle(
                engine, state, memory, units,
                BattleGenerate(
                    BmBattle(rom, units, loader, control.playSt, loop.bmSt) { y, x ->
                        battleBmMap.terrain[y, x]
                    },
                    rom, loader, control.playSt, rng, Mechanics(),
                ),
                object : EventScriptBattleHost {
                    override fun getUnitStructFromEventParameter(pid: Int): Int =
                        unitState.getUnitStructFromEventParameter(pid)

                    override fun gameLock(): Int = loop.gameLock()

                    override fun refreshEntityBmMaps() {
                        battleEntityMaps.refreshEntityBmMaps()
                    }
                },
            ).registerOn(it)
            EventFade(memory, screenFade).registerOn(it)
            EventBgm(state, memory, control.sound).registerOn(it)
            registerMoveUnit(it, state, memory, loader) { pid ->
                unitState.getUnitStructFromEventParameter(pid)
            }
            val itemInfo = ItemInfo(rom)
            val giveItemAdd: (Int, Int) -> Boolean =
                { u, item -> loader.unitAddItem(units.unitOffset(u), item) }
            EventGiveItem(
                state, memory, { pid -> unitState.getUnitStructFromEventParameter(pid) },
                giveItemAdd,
                itemInfo::makeNewItem,
                { control.playSt.partyGoldAmount }, { control.playSt.partyGoldAmount = it },
                // The popup machinery is built on the first GIVEITEMTO of an item.
                { p, u, item ->
                    gotItemPopup(giveItemAdd, itemInfo::makeNewItem).start(p, u, item)
                },
            ).registerOn(it)
            EventPopup(
                state, memory,
                BrownTextBox(
                    engine, hw, loop.sprites, rom,
                    textWidth = { id -> if (rom.isWholeRom) brownTextBoxWidth(id) else 0 },
                    drawText = { id, chr, pal, centerWidth ->
                        if (rom.isWholeRom) brownTextBoxDraw(id, chr, pal, centerWidth)
                    },
                ),
            ).registerOn(it)
            if (rom.isWholeRom) {
                eventText.registerOn(it)
                EventAsmCall(
                    memory,
                    mapOf(0x080CF460 to { _ -> bmGuideTextSetAllGreen(memory, flags) }),
                ).registerOn(it)
                eventTextBg.registerOn(it)
            }
            ReleaseEventStubs.registerMissing(it) { code -> onEventStub?.invoke(code) }
        }
    }

    /**
     * `GetStringTextLen(GetStringFromIndex(msg))` for the brown text box: the width in pixels
     * with the system glyphs (`TextGlyphs_System`, English ASCII font), measured on a scratch
     * font so the talk font stays untouched.
     */
    internal fun brownTextBoxWidth(msg: Int): Int {
        val scene = talkScene
        val text = scene.text
        val saved = text.activeFont
        val font = Font().also { it.lang = 1 }
        text.setTextFont(font)
        text.setTextFontGlyphs(0)
        try {
            return text.getStringTextLen(scene.messages.getStringFromIndex(msg))
        } finally {
            text.setTextFont(saved)
        }
    }

    /** The sprite text calls of `StartBrownTextBoxCore` after the `Decompress`. */
    internal fun brownTextBoxDraw(msg: Int, chr: Int, pal: Int, centerWidth: Int) {
        val scene = talkScene
        val text = scene.text
        val font = Font()
        val t = gbafe.text.Text()
        val str = scene.messages.getStringFromIndex(msg)
        text.initSpriteTextFont(font, 0x06010400 + chr, pal + 0x12)
        text.initSpriteText(t)
        text.spriteTextDrawBackgroundExt(t, 0)
        text.setTextFontGlyphs(0)
        text.textInsertDrawString(t, text.getStringTextCenteredPos(centerWidth, str), 0, str)
        text.setTextFont(null)
    }

    /**
     * The talk window on the game's BG buffers, VRAM, fonts, procs, keys and the ROM messages
     * (see [TalkScene] for its stand-ins). Built on first use; needs the whole ROM.
     */
    private val talkSceneDelegate = lazy {
        val bytes = rom.wholeRomBytes()
        val root = rom.u32(MSG_HUFFMAN_ROOT) - RomView.ROM_BASE
        val huffman = MsgHuffman(bytes, MSG_HUFFMAN_TABLE - RomView.ROM_BASE, root)
        TalkScene(
            rom, bytes, hw, engine, loop.sprites, keys, { clock.frames.toInt() },
            MsgStrings(bytes, huffman, MSG_TABLE - RomView.ROM_BASE),
            { song -> control.sound.playSoundEffect(song) },
            { control.playSt.textSpeed },
            // SetDialogueSkipEvBit: EV_STATE_0008 on the standard event engine.
            {
                (eventEngine.findStd()?.state as? EventEngineProc)?.let { p ->
                    p.evStateBits = p.evStateBits or EventConst.EV_STATE_0008
                }
            },
            rng.lcg,
        )
    }

    /** `gPopupItem`. */
    private var popupItem = 0

    /** `gPopupNumber`. */
    private var popupNumber = 0

    /** `DrawnIconLookupTable` (`MAX_ICON_COUNT` 2-byte entries). */
    private val drawnIconLookupTable = ByteArray(0x200)

    /** `IconGFXIDLookupTable`. */
    private val iconGfxIdLookupTable = ByteArray(0x100)

    private var gotItemPopupInstance: GotItemPopup? = null

    /**
     * The "got item" popup (`NewPopup_ItemGot`) on the game's proc engine, built on first use
     * (needs the whole ROM through [talkScene]). Stand-ins: `gpPopupUnit` is not tracked, so the
     * unit-name text id is 0 (the got-item and pilfered scripts do not use the unit name), and
     * [addItem] with a full inventory (the item-drop menu) throws [NotImplementedError].
     * `LoadIconPalette(slot, palId)` maps to [ItemIcon.loadIconPalette] (icon palette `slot` into
     * palette bank `palId`).
     */
    private fun gotItemPopup(
        addItem: (unit: Int, item: Int) -> Boolean,
        makeNewItem: (Int) -> Int,
    ): GotItemPopup {
        gotItemPopupInstance?.let { return it }
        val scene = talkScene
        val popupIconObject = rom.objN(0x08590F4C) // gObject_16x16
        val popupScript = PopupScript(
            engine,
            PopupScript.Dependencies(
                rom = rom,
                messages = scene.messages,
                text = scene.text,
                hardware = hw,
                drawnIconLookupTable = drawnIconLookupTable,
                iconGfxIdLookupTable = iconGfxIdLookupTable,
                popupItem = { popupItem },
                popupNumber = { popupNumber },
                unitNameTextId = { 0 },
                loadIconPalette = { slot, palId -> ItemIcon.loadIconPalette(hw, rom, slot, palId) },
                startBgmVolumeChange = { proc, v, e, t ->
                    control.sound.startBgmVolumeChange(v, e, t, proc)
                },
                playSoundEffect = { song -> control.sound.playSoundEffect(song) },
                newKeys = { keys.newKeys },
                pushToSecondaryOam = { x, y, _, oam2 ->
                    loop.oam.putHi(x, y, popupIconObject.data, oam2)
                },
            ),
        )
        val popup = GotItemPopup(
            engine,
            GotItemPopup.Dependencies(
                newPopupSimple = { parent, def, clock, style ->
                    popupScript.start(parent, def, clock, style)
                },
                setPopupItem = { popupItem = it },
                unitFaction = { u -> units.index(u) and 0xC0 },
                setUnitDropItem = { u ->
                    units.setState(u, units.state(u) or UnitLoader.US_DROP_ITEM)
                },
                makeNewItem = makeNewItem,
                handleNewItemGetFromDrop = { u, item, _ ->
                    if (!addItem(u, item)) {
                        throw NotImplementedError(
                            "GIVEITEMTO with a full inventory (item-drop menu) is not ported",
                        )
                    }
                },
            ),
        )
        gotItemPopupInstance = popup
        return popup
    }

    /** The talk scene, created on first use. */
    public val talkScene: TalkScene by talkSceneDelegate

    private val eventTextHost: EventTextTalkHost by lazy {
        val scene = talkScene
        EventTextTalkHost(
            engine, scene.talk, scene.bubble, scene.face, scene, scene,
            readTextSpeed = { control.playSt.textSpeed },
            writeTextSpeed = { control.playSt.textSpeed = it },
            stringFromIndex = scene::stringFromIndex,
            refreshMapGraphics = { if (rom.isWholeRom) refreshMapGraphics() },
            suspendMapDisp = { if (rom.isWholeRom) battleMap.bMapDispSuspend() },
            resumeMapDisp = { if (rom.isWholeRom) battleMap.bMapDispResume() },
        )
    }

    /**
     * Restores the available parts of `RefreshBMapGraphics` after a conversation background.
     * Text, UI frame, face and icon resets, weather particles and system font setup are stand-ins.
     */
    private fun refreshMapGraphics() {
        val gfx = ChapterMapGfx(rom, battleChapters, hw, control.playSt)
        hw.setupBackgrounds(
            cloudsWeather = control.playSt.chapterWeatherId == ChapterMapGfx.WEATHER_CLOUDS,
        )
        gfx.loadObjUiGfx()
        gfx.unpackChapterMapGraphics(control.playSt.chapterIndex)
        bmapRender.renderBmMap()
        refreshSprites()
        unitSprites?.applyUnitSpritePalettes()
        unitSprites?.forceSyncUnitSpriteSheet()
    }

    /** The talk event commands 0x1A to 0x1D (`Event1A_TEXTSTART` ...) over [eventTextHost]. */
    private val eventText: EventText by lazy {
        EventText(eventState, ScriptWords({ rom.u16(it) }), eventTextHost)
    }

    /**
     * The text background command 0x21 (`Event21_TextBg`). Real: BG positions, the random
     * background, the BG3 load of subcode 0; the fade procs and `DisplayCGfx` are no-op
     * stand-ins.
     */
    private val eventTextBg: EventTextBg by lazy {
        EventTextBg(
            eventState, ScriptWords({ rom.u16(it) }),
            object : EventTextBgHost {
                override fun resetBgPositions() {
                    for (bg in 0..3) hw.lcd.setBgPosition(bg, 0, 0)
                }

                override fun randomBackground(): Int = rng.nextN(BG_BLANK)

                override fun loadConvoBackground(bgIndex: Int) {
                    val entry = CONVO_BACKGROUND_DATA + 12 * bgIndex
                    rom.decompressInto(rom.u32(entry), hw.vram, hw.tileDataOffset(3))
                    // The tsa is stored raw (header: width - 1, height - 1) and read in place.
                    val tsaAt = rom.u32(entry + 4)
                    val tsaSize = 2 + 2 * (rom.u8(tsaAt) + 1) * (rom.u8(tsaAt + 1) + 1)
                    val tsa = ByteArray(tsaSize) { rom.u8(tsaAt + it).toByte() }
                    BgTilemap.applyTsa(hw.mapBuffer(3), 0, tsa, 0, 0x8000)
                    rom.pal(rom.u32(entry + 8), 16 * 8)
                        .copyInto(hw.palette, 8 * 16)
                    hw.enableBgSyncByMask(1 shl 3)
                    hw.enablePaletteSync()
                    hw.palette[0] = 0
                }

                override fun displayCg(bgIndex: Int) {}

                override fun startBackgroundFade(
                    fadeType: Int,
                    unkType: Int,
                    bgIndex: Int,
                    fadeSpeed: Int,
                    proc: EventEngineProc,
                ) {}

                override fun clearScreen(proc: EventEngineProc): Int =
                    eventText.event22ClearScreen.run(proc)

                override fun colorFadeIn(speed: Int, color: Int, proc: EventEngineProc) {}

                override fun colorFadeOut(speed: Int, color: Int, proc: EventEngineProc) {}
            },
        )
    }

    /**
     * The battle map start path (`StartBattleMap`, `StartBMapMain`), built when the game control
     * proc first reaches it (it reads chapter data from the ROM, which the boot windows may lack).
     */
    public val battleMap: BattleMapStart by lazy {
        bmapMainParts.second
    }

    /**
     * The `BMapMain` of [battleMap] with its player phase and event hooks wired (player phase
     * script, `EventEngine.callEvent`).
     */
    public val bmapMain: BMapMain by lazy { bmapMainParts.first }

    /** The player phase script wired into [bmapMain]. */
    public val playerPhaseScript: ProcScript get() = bmapMain.hooks.playerPhase

    /** `gSMSHandleArray` of the battle map, set by [wireUnitSprites]. */
    private var spriteHandles: UnitSpriteHandles? = null

    /**
     * The unit sprite sheet state (`gUnitSpriteSlots`, index counters), set by
     * [wireUnitSprites].
     */
    public var unitSprites: UnitSprites? = null
        private set

    /** `RefreshUnitSprites` of the battle map, set by [wireUnitSprites]. */
    private var refreshSprites: () -> Unit = {}

    /** MU animation owner retained to flush its registered tile moves during VBlank. */
    private var muSpriteAnim: MuSpriteAnim? = null

    /**
     * The MU system (`mu.c`) with class lookups from the ROM; walking MUs are drawn by the ap.c
     * sprite animation player ([MuSpriteAnim]); the standing-MU SMS hooks are still no-ops.
     */
    public val mu: Mu by lazy {
        val spriteAnim = MuSpriteAnim(rom, hw, loop.oam, loop.sprites)
        muSpriteAnim = spriteAnim
        lateinit var self: Mu
        self = Mu(
            engine, loop.bmSt, control.playSt, keys, camMove.script,
            object : MuHooks {
                private fun classU8(jid: Int, o: Int) =
                    rom.u8(UnitLoader.CLASS_DATA + (jid - 1) * UnitLoader.CLASS_DATA_SIZE + o)

                override fun classAttributes(jid: Int): Int =
                    rom.u32(UnitLoader.CLASS_DATA + (jid - 1) * UnitLoader.CLASS_DATA_SIZE + 0x28)

                override fun classSlowWalking(jid: Int): Int = classU8(jid, 0x07)

                override fun createMuAnim(proc: MuProc, config: MuConfig): Int =
                    spriteAnim.create(self, proc, config)

                override fun switchMuAnim(proc: MuProc, facing: Int) =
                    spriteAnim.switchAnimation(proc.spriteAnim, facing)

                override fun apUpdate(anim: Int, x: Int, y: Int) = spriteAnim.update(anim, x, y)

                override fun resetSpriteAnimClock(anim: Int) = spriteAnim.resetClock(anim)

                override fun spriteAnimObjLayer(anim: Int): Int = spriteAnim.objLayer(anim)

                override fun deleteMuAnim(proc: MuProc) = spriteAnim.delete(proc.spriteAnim)
            },
            rom,
        )
        self
    }

    /**
     * Registers the event commands 0x2F (MOVEUNIT) and 0x30 (ENUN) on [engine]: the units walk
     * with [MuCtrl] and the MU sprite animation player.
     */
    private fun registerMoveUnit(
        engine: EventEngine,
        state: EventState,
        memory: ScriptMemory,
        loader: UnitLoader,
        unitFromParameter: (Int) -> Int,
    ) {
        val muCtrl = MuCtrl(
            this.engine, mu, units, battleBmMap, loader, rng, keys,
            object : MuCtrlHooks {
                override fun hideUnitSprite(id: Int) { spriteHandles?.hideUnitSprite(id) }

                override fun showUnitSprite(id: Int) { spriteHandles?.showUnitSprite(id) }

                override fun refreshEntityBmMaps() { battleEntityMaps.refreshEntityBmMaps() }

                override fun refreshUnitSprites() { refreshSprites() }

                override fun spritePalette(id: Int): Int =
                    spriteHandles?.getUnitSpritePalette(id) ?: 0
            },
        )
        loader.startDefinedMove = { id, redas, count, flags ->
            val list = List(count) {
                Reda.fromWords(rom.u32(redas + 8 * it), rom.u32(redas + 8 * it + 4))
            }
            muCtrl.startDefinedMove(id, list, count, flags)
        }
        EventMoveUnit(
            state, memory, units, battleBmMap, mu, muCtrl, unitFromParameter,
            object : EventMoveUnitHost {
                override fun camMoveExists(): Boolean =
                    this@Fe8Boot.engine.find(camMove.script) != null

                override fun ensureCameraOntoPosition(proc: EventEngineProc, x: Int, y: Int) =
                    camMove.ensureCameraOntoPosition(proc.proc, x, y)

                override fun refreshEntityBmMaps() { battleEntityMaps.refreshEntityBmMaps() }

                override fun refreshUnitSprites() { refreshSprites() }

                override fun renderBmMap() { bmapRender.renderBmMap() }
            },
        ).registerOn(engine)
    }

    private val camMove: CamMove by lazy { CamMove(engine, loop.bmSt) }

    private val battleChapters: ChapterData by lazy { ChapterData(rom) }

    /** The battle map's `gBmMap*` pools, shared by the battle map path and the unit loads. */
    private val battleBmMap: BmMap by lazy { BmMap(rom, battleChapters, loop.bmSt) }

    private val bmapRender: BmMapRender by lazy { BmMapRender(loop.bmSt, battleBmMap, hw) }

    private val battleEntityMaps: EntityBmMaps by lazy {
        EntityBmMaps(battleBmMap, units, control.playSt)
    }

    private val bmapMainParts: Pair<BMapMain, BattleMapStart> by lazy {
        val chapters = battleChapters
        val bm = loop.bmSt
        val map = battleBmMap
        val camMove = this.camMove
        val reset = ResetCursorPosition(
            engine, bm, control.playSt, units, camMove, NO_WORLD_MAP_NODES,
        )
        val main = BMapMain(
            engine, control.playSt, units, flags, chapters, rom, reset, camMove,
        )
        val render = bmapRender
        val music = MapMusic(
            control.playSt, chapters, units, flags, control.sound,
        ) { battleMap.hooks.getBattleMapKind() }
        val entityBmMaps = battleEntityMaps
        val phase = PlayerPhase(
            engine, bm, control.playSt, map, units, camMove,
            entityBmMaps, render, keys,
            eventEngine::eventEngineExists,
        ).also { pp -> pp.hooks.startMapSongBgm = ProcFunc { music.startMapSongBgm() } }
        main.hooks.playerPhase = phase.script
        main.hooks.phaseIntro = PhaseIntro(
            engine, bm, control.playSt, units, rom, hw, loop.oam, loop.sprites,
        ).also {
            it.hooks.currentBgmSong = { control.sound.songId }
            it.hooks.currentMapMusicIndex = { music.getCurrentMapMusicIndex() }
            it.hooks.fadeOutBgm = { speed -> control.sound.fadeOutBgm(speed) }
            it.hooks.playSoundEffect = { song -> control.sound.playSoundEffect(song) }
            it.hooks.startMapSongBgm = { music.startMapSongBgm() }
        }.script
        main.hooks.startMapSongBgm = { music.startMapSongBgm() }
        main.hooks.startMidFadeFromBlack = { screenFade.startMidFadeFromBlack() }
        main.hooks.waitForFade = { !screenFade.fadeExists() }
        main.hooks.callEvent = { events, execType, _ ->
            eventEngine.callEvent(events, execType)
        }
        val eventInfos = EventInfos(eventEngine.memory, flags, control.playSt)
        eventInfos.hooks = object : EventInfoHooks {
            override fun callEvent(script: Int, execType: Int) {
                eventEngine.callEvent(script, execType)
            }

            override fun getBattleMapKind(): Int = battleMap.hooks.getBattleMapKind()

            override fun chapterEventGroup(chapter: Int): Int =
                chapters.getChapterEventDataPointer(chapter)

            override fun goalWindowDataType(chapter: Int): Int =
                rom.u8(chapters.getROMChapterStruct(chapter) + GOAL_WINDOW_DATA_TYPE_OFFSET)
        }
        main.hooks.runPhaseSwitchEvents = { eventInfos.runPhaseSwitchEvents() }
        val start = BattleMapStart(
            engine, control.playSt, bm, hw, chapters, map,
            ChapterMapGfx(rom, chapters, hw, control.playSt), render,
            flags, rng, clock, units,
        ).also {
            main.hooks.writeSuspendSave = suspendSaveHook(main, rom)
            main.hooks.getBattleMapKind = { it.hooks.getBattleMapKind() }
            it.hooks.bmapMainScript = main.script
            it.hooks.refreshEntityBmMaps = { entityBmMaps.refreshEntityBmMaps() }
            it.hooks.suspendPointType = {
                suspendRam.actionData[ACTION_SUSPEND_POINT_TYPE].toInt() and 0xFF
            }
        }
        wireUnitSprites(main, start, phase, map, rom)
        main to start
    }

    /**
     * Replaces the unit sprite stand-ins of [main] and [start] with the ported `bmudisp.c`
     * loaders and the `PutUnitSpritesOam` of the map task, on the shared state.
     */
    private fun wireUnitSprites(
        main: BMapMain,
        start: BattleMapStart,
        phase: PlayerPhase,
        map: BmMap,
        rom: RomView,
    ) {
        val bm = loop.bmSt
        val sprites = UnitSprites(rom, hw, bm).also { it.clockSource = clock }
        unitSprites = sprites
        val handles = UnitSpriteHandles(rom, sprites, units)
        val oam = UnitSpritesOam(
            rom, units, handles, bm, control.playSt, battleChapters, flags, loop.oam,
        ).also {
            it.clockSource = clock
            it.battleMapKind = { start.hooks.getBattleMapKind() }
            it.fogAt = { x, y -> map.fog[y, x] }
            it.terrainAt = { x, y -> map.terrain[y, x] }
        }
        // Stand-in: the trap pool is not ported (no ballistae or light runes).
        val traps = ByteArray(TRAP_POOL_SIZE)
        val mapUnitAt = { x: Int, y: Int -> map.unit[y, x] }
        val refresh = { handles.refreshUnitSprites(mapUnitAt, traps) }
        spriteHandles = handles
        refreshSprites = refresh
        val anims = MapAnimations(rom, battleChapters, hw, control.playSt)
        start.hooks.initMapAnimations = { anims.initMapAnimations() }
        start.hooks.updateMapImgAnimations = { anims.updateMapImgAnimations() }
        start.hooks.updateMapPalAnimations = { anims.updateMapPalAnimations() }
        start.hooks.applyUnitSpritePalettes = { sprites.applyUnitSpritePalettes() }
        start.hooks.resetUnitSprites = { sprites.resetUnitSprites() }
        start.hooks.refreshUnitSprites = refresh
        start.hooks.syncUnitSpriteSheet = { sprites.syncUnitSpriteSheet() }
        start.hooks.putUnitSpritesOam = { oam.putUnitSpritesOam() }
        main.hooks.refreshUnitSprites = refresh
        val refreshProc = ProcFunc { refresh() }
        phase.hooks.refreshUnitSprites = refreshProc
        phase.hooks.putMapCursor = PlayerPhase.PutMapCursor {
            x, y, kind -> mapCursor.put(x, y, kind)
        }
    }

    /**
     * `WriteSuspendSave` for [BMapMain.Hooks.writeSuspendSave], on [saveLib].
     *
     * Stand-ins, until the state exists: `gActionData` is the [SuspendRam.actionData] buffer
     * (zeros apart from the RN state the save stores and `suspendPointType`, which is merged in
     * from [BMapMain.suspendPointType] at offset 0x16); the traps, convoy, menu override, world
     * map info and dungeon state are the zeroed buffers of [SuspendRam] (their owners are not
     * ported).
     */
    private fun suspendSaveHook(main: BMapMain, rom: RomView): (Int) -> Unit {
        val save = BmSuspendSave(saveLib, rom)
        return { slot ->
            val ram = suspendRam
            ram.actionData[ACTION_SUSPEND_POINT_TYPE] = main.suspendPointType.toByte()
            save.writeSuspendSave(ram, slot)
            flushSram()
        }
    }

    private val driver: M4aDriver? = m4a?.driver(pcm)

    /** The op anim. */
    public val opAnim: OpAnim =
        BootRom.opAnim(rom, engine, hw, keys, control).also { opAnimRef = it }

    /** The title screen. */
    public val title: TitleScreen =
        BootRom.titleScreen(rom, engine, hw, opAnim.sprites, keys, control).also { titleRef = it }

    /**
     * The main loop. A screen load overruns its frame on hardware (one missed VBlank), detected by
     * the BG sync request a load leaves.
     */
    public val loop: GameLoop = GameLoop(
        engine, keys, clock, keyInput,
        sprites = opAnim.sprites,
        overran = {
            val missedVBlanks = opAnim.missedVBlanks + title.missedVBlanks
            opAnim.missedVBlanks = 0
            title.missedVBlanks = 0
            if (missedVBlanks == 0 && hw.modifiedBgs != 0 && engine.find(ui.script) != null) {
                1
            } else {
                missedVBlanks
            }
        },
        flush = {
            hw.lcd.flushTo(io)
            hw.flushBackgrounds()
            muSpriteAnim?.flushTileMoves()
            if (talkSceneDelegate.isInitialized()) talkScene.flushTileMoves()
        },
        soundVSync = { driver?.vSync() },
        soundMain = { driver?.main() },
    )

    private val state = RenderState()
    private val sheets = TileSheets()

    /** Packed per-frame data for the GPU backend, drawn from the same state as [screen]. */
    public val gpuFrame: GpuFrame = GpuFrame(state, sheets)

    /** Tile graphics the GPU backend uploads (sheet ids as in [gpuFrame]'s state). */
    public val tileSheets: TileSheets get() = sheets
    private val loader = HardwareSceneLoader()

    private val renderer = SoftwareRenderer(state, sheets, screen, colorMath)
    private val camera = Camera()

    init {
        opAnim.afterStep = loop::afterStep
        control.hooks.startBattleMap = ProcFunc { battleMap.startBattleMap(it) }
        control.hooks.startResumedGame = ProcFunc { battleMap.startResumedGame(it) }
        // The New Game stand-in leads into the battle map, which reads ROM data outside the boot
        // windows: with windows only (debug APK without an imported ROM) the save menu holds, as
        // before M5-58, instead of crashing on the first chapter read.
        if (rom.isWholeRom) control.hooks.startSaveMenu = ProcFunc { newGameSaveMenu() }
        control.sound.setDefaultMaxNumChannels() // AgbMain, after m4aSoundInit
        control.startGame()
    }

    /** Runs one GBA frame (VBlank + main step). */
    public fun frame() {
        loop.frame()
    }

    private val hblankIo = ByteArray(io.size)
    private val hblankLine: (Int, ByteArray) -> Unit = {
        lineIndex, regs -> hw.hblank.active?.onHBlank(lineIndex, regs)
    }

    /**
     * Draws the display state synced by the last VBlank into [screen], with the active HBlank
     * handler's per-line register writes (run here, once per drawn frame).
     */
    public fun render() {
        prepare()
        renderer.render(camera)
    }

    /** Like [render], but packs [gpuFrame] for the GPU backend instead of drawing [screen]. */
    public fun pack() {
        prepare()
        gpuFrame.pack()
    }

    /** Prepares the hardware state and captures HBlank line effects before rendering. */
    private fun prepare() {
        loader.load(state, sheets, hw, loop.oam, io)
        if (hw.hblank.irqEnabled && hw.hblank.active != null) {
            HBlankLines.capture(io, state, hblankIo, hblankLine)
        }
    }
}

/** Offset of `suspendPointType` in `struct ActionData`. */
private const val ACTION_SUSPEND_POINT_TYPE = 0x16

/** `gTrapArray`: 0x40 entries of `struct Trap` (8 bytes). */
private const val TRAP_POOL_SIZE = 0x40 * 8

/** World map stand-in until the world map is ported: no node holds a chapter. */
private val NO_WORLD_MAP_NODES = object : BattleMapNodes {
    override fun chapterId(node: Int): Int = -1

    override fun isCleared(node: Int): Boolean = false

    override fun encounters(node: Int): Int = 0

    override fun placementFlag(node: Int): Int = 0
}

private const val GOAL_WINDOW_DATA_TYPE_OFFSET = 0x8C

/** `gMsgHuffmanTable`, `gMsgHuffmanTableRoot` and `gMsgTable` of FE8U. */
/** `gConvoBackgroundData`: gfx, tsa, pal pointers per background. */
private const val CONVO_BACKGROUND_DATA = 0x0895DD1C
private const val BG_BLANK = 0x35
private const val MSG_HUFFMAN_TABLE = 0x0815A72C
private const val MSG_HUFFMAN_ROOT = 0x0815D488
private const val MSG_TABLE = 0x0815D48C
