package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u32
import gbafe.core.flags.FlagStore
import gbafe.proc.Proc
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.yield
import gbafe.proc.TemporaryLock
import gbafe.proc.ProcScripts.whileTrue
import gbafe.render.BgHardware

/**
 * `struct MapAnimState gManimSt` (include/mapanim.h): the state of the map battle animation. The
 * four actors (0 actor, 1 target, 2 and 3 the triangle attack partners) are parallel arrays; an
 * actor's `unit` is either the unit copy inside a [BattleUnit] (offset 0 of its bytes) or an
 * entry of the unit table. Addresses are the GBA addresses the C structure holds.
 */
public class ManimSt {
    /** `actor[i].unit`: the bytes of the unit struct. */
    public val unitBytes: Array<ByteArray?> = arrayOfNulls(ACTORS)

    /** `actor[i].unit`: byte offset of the unit struct inside [unitBytes]. */
    public val unitOffset: IntArray = IntArray(ACTORS)

    /** `actor[i].unit`: its GBA address. */
    public val unitAddress: IntArray = IntArray(ACTORS)

    /** `actor[i].bu`: its GBA address (`gBattleActor` or `gBattleTarget`), 0 for none. */
    public val buAddress: IntArray = IntArray(ACTORS)

    /** `actor[i].bu`. */
    public val bu: Array<BattleUnit?> = arrayOfNulls(ACTORS)

    /** `actor[i].mu`: the `ProcScr_Mu` proc, `null` for none. */
    public val mu: Array<Proc?> = arrayOfNulls(ACTORS)

    /** `actor[i].hp_max`. */
    public val hpMax: IntArray = IntArray(ACTORS)

    /** `actor[i].hp_cur`. */
    public val hpCur: IntArray = IntArray(ACTORS)

    /** `actor[i].hp_displayed_q4`. */
    public val hpDisplayedQ4: IntArray = IntArray(ACTORS)

    /** `actor[i].hp_info_x`. */
    public val hpInfoX: IntArray = IntArray(ACTORS)

    /** `actor[i].hp_info_y`. */
    public val hpInfoY: IntArray = IntArray(ACTORS)

    /** `pCurrentRound` as an index into `gBattleHitArray`. */
    public var currentRound: Int = 0

    /** `specialProcScr`: the ROM address of the spell's map animation script, 0 for none. */
    public var specialProcScr: Int = 0

    /** `actorCount` (u8, 0x5E). */
    public var actorCount: Int = 0

    /** `hp_changing` (u8, 0x5F). */
    public var hpChanging: Int = 0

    /** `mapAnimKind` (u8, 0x62): `MANIM_KIND_*`. */
    public var mapAnimKind: Int = 0

    /** `subjectActorId` (u8, 0x58): round attacker actor index (0 or 1). */
    public var subjectActorId: Int = 0

    /** `targetActorId` (u8, 0x59): round defender actor index (0 or 1). */
    public var targetActorId: Int = 0

    /** `hitAttributes` (u16, 0x5A): `BATTLE_HIT_ATTR_*` for the current round. */
    public var hitAttributes: Int = 0

    /** `hitInfo` (u8, 0x5C): `BATTLE_HIT_INFO_*` for the current round. */
    public var hitInfo: Int = 0

    /** `hitDamage` (s8, 0x5D): damage dealt in the current round. */
    public var hitDamage: Int = 0

    /** `xTarget` (u8, 0x60): camera target tile X. */
    public var xTarget: Int = 0

    /** `yTarget` (u8, 0x61): camera target tile Y. */
    public var yTarget: Int = 0

    /** Layout constants. */
    public companion object {
        /** The actors of `gManimSt.actor`. */
        public const val ACTORS: Int = 4

        /** `MANIM_KIND_DAMAGE`. */
        public const val KIND_DAMAGE: Int = 0

        /** `MANIM_KIND_STEAL`. */
        public const val KIND_STEAL: Int = 1

        /** `MANIM_KIND_REFRESH`. */
        public const val KIND_REFRESH: Int = 2
    }
}

/**
 * The start of the map battle animation: `BeginBattleAnimations` (bmbattle.c) and the
 * `src/mapanim_api.c` functions it reaches (`BeginBattleMapAnims` down to the MOVEUNIT set-up),
 * which fill [state] (`gManimSt`), start one `ProcScr_Mu` per actor and the map animation proc
 * ([script]).
 *
 * [script] drives the map animation proc through talk reset, camera, battle quotes,
 * MOVEUNIT palette, info box and round execution. Not written (no capture runs them; they fail
 * loudly): the dance / refresh animation (`BeginMapAnimForDance`), the event battle script
 * (`ProcScr_MapAnimEventBattle`) and the link-arena style battle animations
 * (`BeginAnimsOnBattleAnimations`, EP-15).
 *
 * @param engine the proc engine the animation proc runs on (tree 3).
 * @param bm `gBmSt` (the game lock).
 * @param battle the battle units and `gBattleStats`.
 * @param generate the hit array (`gBattleHitArray`).
 * @param units the unit table (triangle attack partners).
 * @param loader the unit helpers (`GetUnitMaxHp`).
 * @param mu the MOVEUNIT (`Mu`) system.
 * @param rom `gSpellAssocData`.
 * @param hardware BG 2 and the palette buffer of `BeginBattleAnimations`, `null` headless.
 * @param camMove the map camera mover for [mapAnimMoveCameraOntoSubject], null headless.
 * @param flags the event flags `MapAnim_DisplayDeathQuote` clears `EVFLAG_GAMEOVER` in; null
 *   headless (a death of a lord then leaves the flag alone).
 * @param temporaryLock the `StartTemporaryLock` proc of the poison effect's pause.
 */
public class MapAnimApi(
    private val engine: ProcEngine,
    private val bm: BmSt,
    private val battle: BmBattle,
    private val generate: BattleGenerate,
    private val units: UnitTable,
    private val loader: UnitLoader,
    private val mu: Mu,
    private val rom: RomView,
    private val hardware: BgHardware? = null,
    camMove: CamMove? = null,
    private val flags: FlagStore? = null,
    private val temporaryLock: TemporaryLock = TemporaryLock(engine),
) {
    /** What the start calls outside the ported functions. */
    public class Hooks {
        /** `RenderBmMap`. Display only: nothing in the capture's compared values depends on it. */
        public var renderBmMap: () -> Unit = {}

        /**
         * `HideUnitSprite(unit)` for the unit table entry with id `unit`: hides its map sprite
         * while the MOVEUNIT stands in for it.
         */
        public var hideUnitSprite: (Int) -> Unit = {}

        /** `ShowUnitSprite(unit)` for the unit table entry with id `unit`. */
        public var showUnitSprite: (Int) -> Unit = {}

        /** `GetUnitSpritePalette(unit)` for the unit with id `unit` (its faction's OBJ bank). */
        public var spritePalette: (Int) -> Int = { 0 }

        /** `EventEngineExists`: event engine is running (the event battle proc is not written). */
        public var eventEngineExists: () -> Boolean = { false }

        /**
         * Stand-in until M6-G for `BeginAnimsOnBattleAnimations` / `sub_8055BC4` (banim-ekrbattle):
         * whether the battle is shown with battle animations ([BanimPrepare.sub8055BC4] exists in
         * fe8.game.bm, left unwired until M6-G). Always false here: the map animation runs.
         */
        public var battleAnimationsOn: () -> Boolean = { false }

        /**
         * Sprite animation (`ap.c`) fields the MOVEUNIT start sets on a `MuProc.sprite_anim`:
         * `frameTimer`, `frameInterval` (0) and `objLayer` (`gUnknown_08205714[i]`). Not ported
         * (the AP structures are the display's): nothing happens.
         */
        public var setMuAnimLayer: (mu: Proc, layer: Int) -> Unit = { _, _ -> }

        /** `SetDefaultColorEffects`: resets the blend registers. Display only: nothing happens. */
        public var setDefaultColorEffects: () -> Unit = {}

        /** `ResetText`: resets text systems. Display only: nothing happens. */
        public var resetText: () -> Unit = {}

        /** `EnsureCameraOntoPosition`: moves camera smoothly onto the subject. */
        public var ensureCameraOntoPosition: (parent: Proc?, x: Int, y: Int) -> Boolean =
            { _, _, _ -> false }

        /** `CallBattleQuoteEventsIfAny`: checks and triggers battle quotes. */
        public var callBattleQuoteEventsIfAny: (pidA: Int, pidB: Int) -> Unit = { _, _ -> }

        /** `BattleEventEngineExists`: true while battle quote event is running. */
        public var battleEventEngineExists: () -> Boolean = { false }

        /**
         * `StartMapAnimInfoWindow` (src/mapanim_infobox.c), [MapAnimInfoBox.startMapAnimInfoWindow]
         * (wired by the game; nothing happens headless).
         */
        public var startMapAnimInfoWindow: (x: Int, y: Int, proc: Proc) -> Unit = { _, _, _ -> }

        /**
         * `MapAnim_GetRoundProcScript` (src/mapanim_spellassoc.c): the script of the proc that
         * animates the current round, which [mapAnimDisplayRoundAnim] starts as a blocking child.
         * Stand-in until EP-23 and EP-24: null, so no round animation runs and the round takes no
         * time.
         */
        public var roundProcScript: () -> ProcScript? = { null }

        /**
         * `NewMapPoisonEffect(unit)` (the poison hit's map effect) for the unit at GBA address
         * `unit`. Stand-in until the poison effect is ported (no capture holds a poisoned hit):
         * nothing happens.
         */
        public var newMapPoisonEffect: (unit: Int) -> Unit = {}

        /**
         * `NewPopup_ItemStealing(item, proc)`: the stolen item popup. Stand-in until the popups
         * are ported (D09-3; no capture holds a steal): nothing happens.
         */
        public var newPopupItemStealing: (item: Int, proc: Proc) -> Unit = { _, _ -> }

        /**
         * `CheckBattleDefeatTalk(pid)` ([EventInfos.checkBattleDefeatTalk]): whether character
         * [pid] has a defeat quote to show. No quote without the event system.
         */
        public var checkBattleDefeatTalk: (pid: Int) -> Boolean = { false }

        /** `DisplayDefeatTalkForPid(pid)` ([EventInfos.displayDefeatTalkForPid]). */
        public var displayDefeatTalkForPid: (pid: Int) -> Unit = {}

        /**
         * `EndMapAnimInfoWindow` (src/mapanim_infobox.c), [MapAnimInfoBox.endMapAnimInfoWindow]
         * (wired by the game; nothing happens headless).
         */
        public var endMapAnimInfoWindow: () -> Unit = {}

        /**
         * `MU_StartDeathFade(mu)` (src/mu.c) for the `ProcScr_Mu` proc `mu`; defaults to
         * [Mu.muStartDeathFade].
         */
        public var muStartDeathFade: (mu: Proc) -> Unit = {}

        /**
         * `PROC_WHILE_EXISTS(ProcScr_MuDeathFade)`: a death fade proc runs; defaults to
         * [Mu.muDeathFadeExists].
         */
        public var muDeathFadeExists: () -> Boolean = { false }

        /**
         * `ProcScr_MapAnimExpBar` started as a blocking child of the animation proc with
         * `MAExpBarProc.expFrom`, `expTo` and `actorId` set ([MapAnimExpBar.start], wired in
         * `PlayerPhaseWiring`). The default (no exp bar) is for tests that do not run it.
         */
        public var startMapAnimExpBar: (parent: Proc, expFrom: Int, expTo: Int, actorId: Int) ->
            Unit = { _, _, _, _ -> }

        /**
         * `DisplayWpnBrokePopup` (src/mapanim.c). Stand-in until EP-19: nothing happens (the
         * popup checks and the popup procs are not written).
         */
        public var displayWpnBrokePopup: ProcFunc = ProcFunc {}

        /** `DisplayWRankUpPopup` (src/mapanim.c). Stand-in until EP-19: nothing happens. */
        public var displayWRankUpPopup: ProcFunc = ProcFunc {}

        /** `ResetTextFont` ([TextSystem.resetTextFont], wired by the game). */
        public var resetTextFont: () -> Unit = {}

        /** `InitBmBgLayers` ([ChapterMapGfx.initBmBgLayers], wired by the game). */
        public var initBmBgLayers: () -> Unit = {}

        /** `LoadUiFrameGraphics` ([UiFrame.loadUiFrameGraphics], wired by the game). */
        public var loadUiFrameGraphics: () -> Unit = {}

        /** `LoadObjUIGfx` ([ChapterMapGfx.loadObjUiGfx], wired by the game). */
        public var loadObjUiGfx: () -> Unit = {}

        /**
         * Not in the original: what the game still needs when the animation has ended. The
         * original leaves the MOVEUNITs and the hidden target sprite to later map code (the
         * cursor hover and the next action). Stand-in until those are ported: [MapAnimApi] sets
         * it to end all MOVEUNITs and show the target's sprite.
         */
        public var afterCleanup: () -> Unit = {}

        /** `SetBattleMuPaletteByIndex`: `SetMuPal`. */
        public var setMuPal: (mu: Proc, pal: Int) -> Unit = { _, _ -> }

        /** `SetBattleMuPaletteByIndex`: `SetMuSpecialSprite`. */
        public var setMuSpecialSprite: (mu: Proc, classId: Int, palAddress: Int) -> Unit =
            { _, _, _ -> }

        /** `MapAnim_PrepareBattleTalk`: calls [mapAnimPrepareBattleTalk]. */
        public var prepareBattleTalk: ProcFunc = ProcFunc {}

        /** `MapAnim_MoveCameraOntoSubject`: calls [mapAnimMoveCameraOntoSubject]. */
        public var moveCameraOntoSubject: ProcFunc = ProcFunc {}

    }

    /** The hooks, set by the game. */
    public val hooks: Hooks = Hooks().also { h ->
        if (camMove != null) {
            h.ensureCameraOntoPosition = { parent, x, y ->
                camMove.ensureCameraOntoPosition(parent, x, y)
            }
        }
        h.prepareBattleTalk = ProcFunc { mapAnimPrepareBattleTalk() }
        h.moveCameraOntoSubject = ProcFunc { mapAnimMoveCameraOntoSubject(it) }
        h.afterCleanup = { endAnimation() }
        h.muStartDeathFade = { p -> mu.muStartDeathFade(p.state as MuProc) }
        h.muDeathFadeExists = { mu.muDeathFadeExists() }
    }

    /** `gManimSt`. */
    public val state: ManimSt = ManimSt()

    /**
     * `ProcScr_MapAnimBattle` (0x089A3508): 21 commands driving the map battle animation from lock
     * through battle quotes, MOVEUNIT palettes, info box, round loop and ending jump.
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_MapAnimBattle",
        listOf(
            call(ProcFunc { bm.lock = (bm.lock + 1) and 0xFF }),
            call(ProcFunc { hooks.prepareBattleTalk.invoke(it) }),
            sleep(1),
            call(ProcFunc { hooks.moveCameraOntoSubject.invoke(it) }),
            sleep(2),
            call(ProcFunc { mapAnimCallBattleQuoteEvents() }),
            whileTrue(ProcBoolFunc { hooks.battleEventEngineExists() }),
            sleep(5),
            call(ProcFunc { setBattleMuPalette() }),
            call(ProcFunc { setupBattleMoveUnits() }),
            sleep(1),
            call(ProcFunc { mapAnimInitInfoBox(it) }),
            sleep(15),
            label(0),
            repeat(ProcFunc { mapAnimPrepareNextBattleRound(it) }),
            call(ProcFunc { mapAnimDisplayRoundAnim(it) }),
            sleep(1),
            call(ProcFunc { mapAnimShowPoisonEffectIfAny(it) }),
            sleep(1),
            sleep(5),
            goto(0),
        ),
    )

    /**
     * `ProcScr_MapAnimEnd` (0x089A35B0): the end of the animation, 19 commands: the defeat
     * quote, the death fade, the info box end, the steal popup, the exp bar, the popups, the
     * camera, the game unlock and the clean-up.
     */
    public val endScript: ProcScript = ProcScript(
        "ProcScr_MapAnimEnd",
        listOf(
            call(ProcFunc { mapAnimDisplayDeathQuote() }),
            whileTrue(ProcBoolFunc { hooks.battleEventEngineExists() }),
            call(ProcFunc { mapAnimDisplayDeathFade() }),
            whileTrue(ProcBoolFunc { hooks.muDeathFadeExists() }),
            call(ProcFunc { hooks.endMapAnimInfoWindow() }),
            sleep(1),
            call(ProcFunc { mapAnimStoleItemPopup(it) }),
            yield(),
            call(ProcFunc { mapAnimDisplayExpBar(it) }),
            yield(),
            call(ProcFunc { hooks.displayWpnBrokePopup.invoke(it) }),
            sleep(8),
            call(ProcFunc { hooks.displayWRankUpPopup.invoke(it) }),
            yield(),
            call(ProcFunc { hooks.moveCameraOntoSubject.invoke(it) }),
            sleep(2),
            call(ProcFunc { bm.lock = (bm.lock - 1) and 0xFF }),
            call(ProcFunc { mapAnimCleanup() }),
            end(),
        ),
    )

    /** The `struct Unit` fields `StartMu` reads, refilled for each MOVEUNIT. */
    private val muUnit = MuUnit(address = 0, xPos = 0, yPos = 0, classId = 0, state = 0)

    /**
     * Transcribed from src/bmbattle.c: BeginBattleAnimations
     *   (not verified: the display writes (BG 2 clear, backdrop colour, the two map renders) are
     *   not compared; the branch it takes is verified through its callees)
     *
     * Clears BG 2 and the backdrop colour, redraws the map, then (battle animations off, the
     * only case seen) ends all MOVEUNITs, redraws the map, starts the map animation and sets
     * `BATTLE_CONFIG_MAPANIMS`. With battle animations on (`sub_8055BC4`, EP-15) it fails loudly.
     */
    public fun beginBattleAnimations() {
        hardware?.let {
            it.fill(it.tilemaps[2], 0)
            it.enableBgSyncByMask(1 shl 2)
            it.palette[0] = 0
            it.enablePaletteSync()
        }
        hooks.renderBmMap()
        check(!hooks.battleAnimationsOn()) { "BeginAnimsOnBattleAnimations is not written" }
        mu.endAllMus()
        hooks.renderBmMap()
        beginBattleMapAnims()
        battle.stats.config = battle.stats.config or BattleStats.CONFIG_MAPANIMS
    }

    /**
     * Ported from src/mapanim_api.c: BeginBattleMapAnims
     *
     * Sets up `gManimSt` and the MOVEUNITs and starts [script]. The dance and refresh battles
     * (`BeginMapAnimForDance`) and the event battle script are not written.
     */
    public fun beginBattleMapAnims() {
        check((battle.stats.config and (CONFIG_REFRESH or CONFIG_DANCERING)) == 0) {
            "BeginMapAnimForDance is not written"
        }
        state.hpChanging = 0
        state.mapAnimKind = ManimSt.KIND_DAMAGE
        setupMapAnimSpellData(battle.actor)
        setupMapBattleAnim(battle.actor, battle.target)
        check(!hooks.eventEngineExists()) { "ProcScr_MapAnimEventBattle is not written" }
        engine.start(script, 3)
    }

    /**
     * Ported from src/mapanim_api.c: SetupMapAnimSpellData
     *
     * (The special script is 0 in every capture, so that field is not break-checked.)
     *
     * Sets the actor count (1 or 2), the current round (the start of the hit array) and the
     * spell's map animation script from the actor's weapon at battle start.
     */
    public fun setupMapAnimSpellData(actor: BattleUnit) {
        state.actorCount = spells.getSpellAssocCharCount(actor.weaponBefore)
        state.currentRound = 0
        state.specialProcScr = spells.getSpellAssocMapAnimProcScript(actor.weaponBefore)
    }

    /**
     * Ported from src/mapanim_api.c: SetupMapBattleAnim
     *
     * Starts the MOVEUNIT of the actor and, in a two-actor animation, hides the target's map
     * sprite and starts the target's; a triangle attack also starts those of its two partners
     * (and hides their sprites). Then faces them, orders their sprite layers and sets each
     * actor's HP (`hpInitial`, `GetUnitMaxHp`).
     */
    public fun setupMapBattleAnim(actor: BattleUnit, target: BattleUnit) {
        makeBattleMoveUnit(0, actor, actor.bytes, 0, GBATTLE_ACTOR)
        if (state.actorCount > 1) {
            hooks.hideUnitSprite(s8(battle.target.bytes, OFF_INDEX) and 0xFF)
            makeBattleMoveUnit(1, target, target.bytes, 0, GBATTLE_TARGET)
        }
        if (tattack()) {
            val a = battle.stats.taUnitA
            val b = battle.stats.taUnitB
            makeBattleMoveUnit(2, actor, units.bytes, a - UnitTable.BLUE_ADDRESS, a)
            makeBattleMoveUnit(3, actor, units.bytes, b - UnitTable.BLUE_ADDRESS, b)
            hooks.hideUnitSprite(s8(units.bytes, a - UnitTable.BLUE_ADDRESS + OFF_INDEX) and 0xFF)
            hooks.hideUnitSprite(s8(units.bytes, b - UnitTable.BLUE_ADDRESS + OFF_INDEX) and 0xFF)
        }
        setupBattleMoveUnits()
        for (i in 0 until state.actorCount) {
            state.hpCur[i] = state.bu[i]!!.hpInitial
            state.hpMax[i] = loader.getUnitMaxHp(state.unitBytes[i]!!, state.unitOffset[i])
        }
        hooks.setDefaultColorEffects()
    }

    /**
     * Ported from src/mapanim_api.c: MakeBattleMOVEUNIT
     *
     * Fills actor [index] of `gManimSt` with the unit at [offset] of [bytes] (GBA address
     * [address]) and its battle unit [bu], and starts its MOVEUNIT. An obstacle's MOVEUNIT is
     * hidden and a petrified unit's gets the gray palette; neither occurs in a capture (not
     * written: they fail loudly).
     */
    public fun makeBattleMoveUnit(
        index: Int,
        bu: BattleUnit,
        bytes: ByteArray,
        offset: Int,
        address: Int,
    ) {
        state.unitBytes[index] = bytes
        state.unitOffset[index] = offset
        state.unitAddress[index] = address
        state.bu[index] = bu
        state.buAddress[index] = if (bu === battle.actor) GBATTLE_ACTOR else GBATTLE_TARGET
        val classPtr = u32(bytes, offset + OFF_CLASS)
        muUnit.address = address
        muUnit.xPos = s8(bytes, offset + OFF_X)
        muUnit.yPos = s8(bytes, offset + OFF_Y)
        muUnit.classId = (classPtr - UnitLoader.CLASS_DATA) / UnitLoader.CLASS_DATA_SIZE + 1
        muUnit.state = u32(bytes, offset + OFF_STATE)
        muUnit.spritePalette = hooks.spritePalette(s8(bytes, offset + OFF_INDEX) and 0xFF)
        val proc = mu.startMu(muUnit)
        state.mu[index] = proc
        val terrain = bu.terrainId
        check(terrain != TERRAIN_WALL_DAMAGED && terrain != TERRAIN_SNAG) {
            "MakeBattleMOVEUNIT: HideMu of an obstacle is not written"
        }
        val status = bytes[offset + OFF_STATUS].toInt() and 0xF
        check(status != STATUS_PETRIFY && status != STATUS_13) {
            "MakeBattleMOVEUNIT: SetMuPal of a petrified unit is not written"
        }
    }

    /**
     * Ported from src/mapanim_api.c: SetBattleAnimFacing
     *
     * Turns the MOVEUNIT of actor [actor] toward actor [opponent] (`MA_FACING_OPPONENT`), to its
     * class's default facing (`MA_FACING_DEFAULT`) or toward the map origin (`MA_FACING_UNK`);
     * `MA_FACING_STANDING` changes nothing.
     */
    public fun setBattleAnimFacing(actor: Int, opponent: Int, facing: Int) {
        val proc = state.mu[actor]?.state as MuProc? ?: return
        when (facing) {
            MA_FACING_OPPONENT -> mu.setMuFacing(
                proc, UnitFacing.getFacingDirection(x(actor), y(actor), x(opponent), y(opponent)),
            )
            MA_FACING_DEFAULT -> mu.setMuDefaultFacing(proc)
            MA_FACING_UNK -> mu.setMuFacing(
                proc, UnitFacing.getFacingDirection(x(actor), y(actor), 0, 0),
            )
        }
    }

    private fun x(actor: Int): Int = s8(state.unitBytes[actor]!!, state.unitOffset[actor] + OFF_X)

    private fun y(actor: Int): Int = s8(state.unitBytes[actor]!!, state.unitOffset[actor] + OFF_Y)

    /**
     * Ported from src/mapanim_api.c: SetupBattleMOVEUNITs
     *
     * Orders the MOVEUNITs' sprite layers ([sub807B4D0]) and faces them as the actor's weapon
     * asks (`GetSpellAssocFacing`): the actor toward the target and, with two actors, the target
     * toward the actor; a triangle attack's partners face the target too.
     */
    public fun setupBattleMoveUnits() {
        val facing = spells.getSpellAssocFacing(state.bu[0]!!.weaponBefore)
        sub807B4D0()
        when (state.actorCount) {
            2 -> {
                if (tattack()) {
                    setBattleAnimFacing(2, 1, facing)
                    setBattleAnimFacing(3, 1, facing)
                }
                setBattleAnimFacing(1, 0, facing)
                setBattleAnimFacing(0, 1, facing)
            }
            1 -> setBattleAnimFacing(0, 1, facing)
        }
    }

    private val spells = SpellAssoc(rom)

    private val order = IntArray(ManimSt.ACTORS)

    /**
     * Transcribed from src/mapanim_api.c: sub_807B4D0 (not verified: its only effect is the
     *   sprite animation layer, the display hook [Hooks.setMuAnimLayer], in no captured value)
     *
     * Sorts the actors by map position (row first, then column, the upper left first) and gives
     * their MOVEUNIT sprite animations the layers 10, 9, 8, 7 in that order.
     */
    public fun sub807B4D0() {
        var count = state.actorCount
        if (count == 2 && tattack()) count += 2
        for (i in 0 until count) order[i] = i
        for (i in 0 until count - 1) {
            for (j in i + 1 until count) {
                val a = order[i]
                val b = order[j]
                val swap = if (y(a) == y(b)) x(a) >= x(b) else y(a) >= y(b)
                if (swap) {
                    order[i] = b
                    order[j] = a
                }
            }
        }
        for (i in 0 until count) {
            state.mu[order[i]]?.let { hooks.setMuAnimLayer(it, LAYERS[i]) }
        }
    }

    private fun tattack(): Boolean = (generate.hitArray[0] and ATTR_TATTACK) != 0

    /**
     * Transcribed from src/mapanim.c: MapAnim_PrepareBattleTalk (not verified: text display only).
     *
     * Resets active text for battle talk.
     */
    public fun mapAnimPrepareBattleTalk() {
        hooks.resetText()
    }

    /**
     * Ported from src/mapanim.c: MapAnim_MoveCameraOntoSubject
     *
     * Ensures camera is centered on actor 0 (the battle subject).
     */
    public fun mapAnimMoveCameraOntoSubject(proc: Proc) {
        hooks.ensureCameraOntoPosition(proc, x(0), y(0))
    }

    /**
     * Ported from src/mapanim.c: MapAnim_CallBattleQuoteEvents
     *
     * Triggers pre-battle quotes if both combatants are present.
     */
    public fun mapAnimCallBattleQuoteEvents() {
        if (state.actorCount == 2) {
            hooks.callBattleQuoteEventsIfAny(charId(0), charId(1))
        }
    }

    /**
     * Transcribed from src/mapanim.c: SetBattleMuPaletteByIndex (not verified: no Manakete/Myrrh
     * actor appears in any Prologue capture, so the non-Myrrh return leaves MU palettes unchanged)
     *
     * Configures the MOVEUNIT palette and special sprite for transformed Manaketes.
     */
    public fun setBattleMuPaletteByIndex(actorNum: Int) {
        val b0 = state.unitBytes[0] ?: return
        val o0 = state.unitOffset[0]
        val b1 = state.unitBytes[1] ?: return
        val o1 = state.unitOffset[1]
        val faction0 = b0[o0 + OFF_INDEX].toInt() and 0xC0
        val faction1 = b1[o1 + OFF_INDEX].toInt() and 0xC0
        if (faction0 == faction1) {
            val isObstacle0 = u32(b0, o0 + OFF_CHARACTER) == 0
            val isObstacle1 = u32(b1, o1 + OFF_CHARACTER) == 0
            if (!isObstacle0 && !isObstacle1) return
        }

        val bActor = state.unitBytes[actorNum] ?: return
        val oActor = state.unitOffset[actorNum]
        val classPtr = u32(bActor, oActor + OFF_CLASS)
        val classId = if (classPtr < UnitLoader.CLASS_DATA) {
            0
        } else {
            (classPtr - UnitLoader.CLASS_DATA) / UnitLoader.CLASS_DATA_SIZE + 1
        }
        if (classId != CLASS_MANAKETE_MYRRH) return

        val status = bActor[oActor + OFF_STATUS].toInt() and 0x0F
        when (status) {
            STATUS_SLEEP, STATUS_PETRIFY, STATUS_13 -> return
        }

        val otherActor = actorNum xor 1
        val otherBu = state.bu[otherActor] ?: return
        val otherWeapon = otherBu.weaponBefore and 0xFF
        when (otherWeapon) {
            ITEM_STAFF_SILENCE, ITEM_STAFF_SLEEP, ITEM_STAFF_BERSERK, ITEM_MONSTER_STONE -> return
        }

        val actorBu = state.bu[actorNum] ?: return
        val actorWeapon = actorBu.weaponBefore and 0xFF
        if (actorWeapon != ITEM_DIVINESTONE) return

        state.mu[actorNum]?.let { muProc ->
            hooks.setMuPal(muProc, BM_OBJPAL_BANIM_SPECIALMU + actorNum)
            hooks.setMuSpecialSprite(muProc, CLASS_MANAKETE, PAL_MAP_ANIM_MANAKETE_MU)
        }
    }

    /**
     * Transcribed from src/mapanim.c: SetBattleMuPalette (not verified: no Manakete/Myrrh actor
     * appears in any Prologue capture, so the non-Myrrh return leaves MU palettes unchanged)
     *
     * Sets MOVEUNIT palettes for participating actors.
     */
    public fun setBattleMuPalette() {
        when (state.actorCount) {
            2 -> {
                setBattleMuPaletteByIndex(1)
                setBattleMuPaletteByIndex(0)
            }
            1 -> {
                setBattleMuPaletteByIndex(0)
            }
        }
    }

    /**
     * Ported from src/mapanim.c: MapAnim_InitInfoBox
     *
     * Initializes the map animation battle info box window position.
     */
    public fun mapAnimInitInfoBox(proc: Proc) {
        hooks.setDefaultColorEffects()

        when (state.mapAnimKind) {
            ManimSt.KIND_STEAL, ManimSt.KIND_REFRESH -> return
            else -> {}
        }

        if (spells.getSpellAssocReturnBool(state.bu[0]!!.weaponBefore) != 0) {
            val y: Int
            if (state.actorCount == 1) {
                val y0 = y(0) * 16 - bm.cameraY
                y = if (y0 >= 112) y0 - 40 else y0 + 24
            } else {
                val array0 = y(0) * 16 - bm.cameraY
                val array1 = y(1) * 16 - bm.cameraY
                if (kotlin.math.abs(array0 - array1) >= 80) {
                    y = 64
                } else {
                    val actorNum = if (array0 > array1) 0 else 1
                    val chosen = if (actorNum == 0) array0 else array1
                    val other = if (actorNum == 0) array1 else array0
                    y = if (chosen >= 112) other - 40 else chosen + 24
                }
            }
            hooks.startMapAnimInfoWindow(15, y / 8, proc)
        }
    }

    /**
     * Ported from src/mapanim.c: MapAnim_AdvanceBattleRound
     *
     * Advances to the next battle round and sets attacker/defender and hit fields.
     */
    public fun mapAnimAdvanceBattleRound() {
        val hit = generate.hitArray[state.currentRound]
        val info = BattleGenerate.info(hit)
        val attributes = BattleGenerate.attributes(hit)
        val hpChange = BattleGenerate.hpChange(hit)

        state.subjectActorId = if ((info and BattleGenerate.INFO_RETALIATION) != 0) 1 else 0
        state.targetActorId = 1 - state.subjectActorId

        state.hitAttributes = attributes and 0xFFFF
        state.hitInfo = info
        state.hitDamage = hpChange

        if (state.actorCount == 1) {
            state.subjectActorId = 0
            state.targetActorId = 0
        }

        state.currentRound++
    }

    /**
     * Ported from src/mapanim.c: MapAnim_PrepareNextBattleRound
     *
     * Prepares next battle round or ends the battle if terminating entry reached.
     */
    public fun mapAnimPrepareNextBattleRound(proc: Proc) {
        val hit = generate.hitArray[state.currentRound]
        val info = BattleGenerate.info(hit)
        if ((info and BattleGenerate.INFO_END) != 0) {
            engine.breakProc(proc)
            engine.gotoScript(proc, endScript)
            return
        }

        mapAnimAdvanceBattleRound()
        state.specialProcScr = spells.getSpellAssocMapAnimProcScript(
            state.bu[state.subjectActorId]!!.weaponBefore,
        )

        engine.breakProc(proc)
    }

    /**
     * Ported from src/mapanim.c: MapAnim_DisplayRoundAnim
     *
     * Starts the round's animation proc ([Hooks.roundProcScript]) as a blocking child of [proc].
     */
    public fun mapAnimDisplayRoundAnim(proc: Proc) {
        hooks.roundProcScript()?.let { engine.startBlocking(it, proc) }
    }

    /**
     * Transcribed from src/mapanim.c: MapAnim_ShowPoisonEffectIfAny (not verified: no hit of the
     * capture poisons, so the taken branch is a no-op the capture cannot tell from a stand-in)
     *
     * Starts the poison effect on the round's target and holds [proc] for 100 frames when the hit
     * poisons.
     */
    public fun mapAnimShowPoisonEffectIfAny(proc: Proc) {
        if ((state.hitAttributes and BattleGenerate.ATTR_POISON) != 0) {
            hooks.newMapPoisonEffect(state.unitAddress[state.targetActorId])
            temporaryLock.start(proc, 100)
        }
    }

    /**
     * Transcribed from src/mapanim.c: MapAnim_StoleItemPopup (not verified: no steal in the
     * capture; the branch is the stolen item popup, a stand-in hook)
     *
     * Shows the stolen item's popup for a steal animation.
     */
    public fun mapAnimStoleItemPopup(proc: Proc) {
        if (state.mapAnimKind == ManimSt.KIND_STEAL) {
            hooks.newPopupItemStealing(state.bu[1]!!.weapon, proc)
        }
    }

    /**
     * The actor whose HP reached 0 (the target wins a tie, then the actor overrides it), or -1:
     * the `actorNum` search of `MapAnim_DisplayDeathQuote` and `MapAnim_DisplayDeathFade`.
     */
    private fun deadActor(): Int {
        var actorNum = -1
        when (state.actorCount) {
            2 -> {
                if (state.hpCur[1] == 0) actorNum = 1
                if (state.hpCur[0] == 0) actorNum = 0
            }
            1 -> if (state.hpCur[0] == 0) actorNum = 0
        }
        return actorNum
    }

    /**
     * Ported from src/mapanim.c: MapAnim_DisplayDeathQuote
     *
     * When an actor died, clears `EVFLAG_GAMEOVER` for Eirika and Ephraim (the lord's death is
     * decided later) and, if the dead character has a defeat quote, ends the info box and shows it.
     */
    public fun mapAnimDisplayDeathQuote() {
        val actorNum = deadActor()
        if (actorNum == -1) return
        val pid = charId(actorNum)
        when (pid) {
            CHARACTER_EIRIKA, CHARACTER_EPHRAIM -> {
                if (flags?.checkFlag(EVFLAG_GAMEOVER) == true) flags.clearFlag(EVFLAG_GAMEOVER)
            }
        }
        if (hooks.checkBattleDefeatTalk(pid)) {
            hooks.endMapAnimInfoWindow()
            hooks.displayDefeatTalkForPid(pid)
        }
    }

    /**
     * Ported from src/mapanim.c: MapAnim_DisplayDeathFade
     *
     * Starts the death fade of the dead actor's MOVEUNIT.
     */
    public fun mapAnimDisplayDeathFade() {
        val actorNum = deadActor()
        if (actorNum != -1) hooks.muStartDeathFade(state.mu[actorNum]!!)
    }

    /**
     * Ported from src/mapanim.c: MapAnim_DisplayExpBar
     *
     * Starts the exp bar for the actor that gained exp (the target if both did, then the actor
     * overrides it) from its exp before the battle by the exp gained, blocking [proc].
     */
    public fun mapAnimDisplayExpBar(proc: Proc) {
        var actorNum = -1
        when (state.actorCount) {
            2 -> {
                if (state.bu[1]!!.expGain != 0) actorNum = 1
                if (state.bu[0]!!.expGain != 0) actorNum = 0
            }
            1 -> if (state.bu[0]!!.expGain != 0) actorNum = 0
        }
        if (actorNum >= 0) {
            val bu = state.bu[actorNum]!!
            hooks.startMapAnimExpBar(proc, bu.expPrevious, bu.expPrevious + bu.expGain, actorNum)
        }
    }

    /**
     * Transcribed from src/mapanim.c: MapAnim_Cleanup (not verified: its calls are display
     * set-up, and the capture's only difference is the one skipped branch: `EndAllMus` runs only
     * while an event engine exists, none does at the end of the capture's animation)
     *
     * Resets the MOVEUNIT animations, text font, info box and BG layers and UI graphics after the
     * animation.
     */
    public fun mapAnimCleanup() {
        mu.resetMuAnims()
        hooks.resetTextFont()
        hooks.endMapAnimInfoWindow()
        hooks.initBmBgLayers()
        hooks.loadUiFrameGraphics()
        hooks.loadObjUiGfx()
        if (hooks.eventEngineExists()) mu.endAllMus()
        hooks.afterCleanup()
    }

    private fun charId(actor: Int): Int {
        val bytes = state.unitBytes[actor] ?: return 0
        val offset = state.unitOffset[actor]
        val charPtr = u32(bytes, offset + OFF_CHARACTER)
        if (charPtr == 0) return 0
        return rom.u8(charPtr + 4)
    }

    /** The default [Hooks.afterCleanup]. */
    private fun endAnimation() {
        mu.endAllMus()
        if (state.actorCount > 1) hooks.showUnitSprite(s8(battle.target.bytes, OFF_INDEX) and 0xFF)
    }

    /** Constants. */
    public companion object {
        private const val CHARACTER_EIRIKA = 0x01
        private const val CHARACTER_EPHRAIM = 0x0F
        private const val EVFLAG_GAMEOVER = 0x65
        private const val MA_FACING_OPPONENT = 0
        private const val MA_FACING_DEFAULT = 1
        private const val MA_FACING_UNK = 2
        private const val GBATTLE_ACTOR = 0x0203A4EC
        private const val GBATTLE_TARGET = 0x0203A56C
        private const val ATTR_TATTACK = 1 shl 10
        private const val CONFIG_REFRESH = 1 shl 6
        private const val CONFIG_DANCERING = 1 shl 9
        private const val TERRAIN_WALL_DAMAGED = 0x1B
        private const val TERRAIN_SNAG = 0x33
        private const val STATUS_SLEEP = 2
        private const val STATUS_PETRIFY = 0xB
        private const val STATUS_13 = 0xD
        private const val OFF_CHARACTER = 0x00
        private const val OFF_CLASS = 0x04
        private const val OFF_INDEX = 0x0B
        private const val OFF_STATE = 0x0C
        private const val OFF_X = 0x10
        private const val OFF_Y = 0x11
        private const val OFF_STATUS = 0x30
        private const val CLASS_MANAKETE = 0x0E
        private const val CLASS_MANAKETE_MYRRH = 0x3C
        private const val ITEM_STAFF_SILENCE = 0x51
        private const val ITEM_STAFF_SLEEP = 0x52
        private const val ITEM_STAFF_BERSERK = 0x53
        private const val ITEM_DIVINESTONE = 0xAA
        private const val ITEM_MONSTER_STONE = 0xB5
        private const val BM_OBJPAL_BANIM_SPECIALMU = 8
        private const val PAL_MAP_ANIM_MANAKETE_MU = 0x089A8F74
        private val LAYERS = intArrayOf(10, 9, 8, 7)
    }
}
