package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u32
import fe8.game.mapanim.ApProcs
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
import java.util.function.IntConsumer

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
 * `PlaySeSpacial(song, x)`: a sound at screen x. A `fun interface`, so the ints are not boxed on
 * the per-round call.
 */
public fun interface SeSpacial {
    /** Plays [song] panned by screen x [x]. */
    public fun play(song: Int, x: Int)
}

/**
 * The map battle animation (a fight shown on the map, not with battle animations):
 * `BeginBattleAnimations` (bmbattle.c) and the `src/mapanim_api.c` functions it reaches
 * (`BeginBattleMapAnims` down to the MOVEUNIT set-up), which fill [state] (`gManimSt`) and start
 * one `ProcScr_Mu` per actor and the map animation proc ([script]), plus the per-round and
 * end steps of src/mapanim.c and src/mapanim_spellassoc.c that [script], [endScript] and
 * [roundScript] run.
 *
 * [script] drives the map animation proc through the text reset, camera, battle quotes,
 * MOVEUNIT palette, info box and round execution, then jumps to [endScript].
 * Not written (no capture runs them; they fail
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
    private val spells: SpellAssoc = SpellAssoc(rom),
    private val apProcs: ApProcs? = null,
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
         * Wired to [mapAnimGetRoundProcScript] by [MapAnimApi].
         */
        public var roundProcScript: () -> ProcScript? = { null }

        /**
         * `PlaySeSpacial(song, x)` (src/bmlib.c, [fe8.game.SpacialSe]) for the hit sounds of
         * [mapAnimBeginRoundSpecificAnims]; `x` is the screen x. Nothing happens headless.
         */
        public var playSeSpacial: SeSpacial = SeSpacial { _, _ -> }

        /**
         * `MapAnim_MoveSubjectsAwayFromTarget` (src/mapanim_spellassoc.c), the command of
         * `ProcScr_MapAnimDefaultItemEffect` that steps the subjects back.
         */
        public var moveSubjectsAwayFromTarget: ProcFunc = ProcFunc {}

        /**
         * `MapAnim_BeginMISSAnim(unit)` (src/mapanim_specialeffect.c) for the unit at GBA address
         * `unit`: the miss effect.
         */
        public var beginMissAnim: IntConsumer = IntConsumer {}

        /**
         * `StartMuCritFlash(mu, flashType)` (src/mu.c) for the MOVEUNIT `mu`. Stand-in until
         * EP-23b: nothing happens, so a critical hit shows no crit flash.
         */
        public var startMuCritFlash: (mu: MuProc, flashType: Int) -> Unit = { _, _ -> }

        /**
         * `NewBG0Shaker` (the critical hit's screen shake). Stand-in until EP-23b: nothing
         * happens, so the screen does not shake.
         */
        public var newBg0Shaker: () -> Unit = {}

        /**
         * `MapAnim_BeginWallBreakAnim(unit, finishes)` (src/mapanim_specialeffect.c) for the unit
         * at GBA address `unit`. Stand-in until EP-23b: nothing happens, so no wall break shows.
         */
        public var beginWallBreakAnim: (unit: Int, finishes: Int) -> Unit = { _, _ -> }

        /**
         * `MapAnim_BeginNODAMAGEAnim(unit)` (src/mapanim_specialeffect.c) for the unit at GBA
         * address `unit`: the no-damage effect. Stand-in until EP-24b (needs a 0-damage hit
         * capture): nothing happens.
         */
        public var beginNoDamageAnim: IntConsumer = IntConsumer {}

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
         * `NewPopup_WeaponBroke(weapon, parent)`: broken weapon popup. Stand-in until M6-H
         * (popup is unwritten): nothing happens.
         */
        public var newPopupWeaponBroke: (weapon: Int, parent: Proc) -> Unit = { _, _ -> }

        /**
         * `NewPopup_WRankIncrease(weaponType, parent)`: weapon rank increase popup. Stand-in
         * until M6-H (popup is unwritten): nothing happens.
         */
        public var newPopupWRankIncrease: (weaponType: Int, parent: Proc) -> Unit = { _, _ -> }

        /** `DisplayWpnBrokePopup` (src/mapanim.c): calls [displayWpnBrokePopup]. */
        public var displayWpnBrokePopup: ProcFunc = ProcFunc {}

        /** `DisplayWRankUpPopup` (src/mapanim.c): calls [displayWRankUpPopup]. */
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
        h.roundProcScript = { mapAnimGetRoundProcScript() }
        h.moveSubjectsAwayFromTarget = ProcFunc { mapAnimMoveSubjectsAwayFromTarget() }
        h.beginMissAnim = IntConsumer { mapAnimBeginMissAnim(it) }
        h.muStartDeathFade = { p -> mu.muStartDeathFade(p.state as MuProc) }
        h.muDeathFadeExists = { mu.muDeathFadeExists() }
        h.displayWpnBrokePopup = ProcFunc { displayWpnBrokePopup(it) }
        h.displayWRankUpPopup = ProcFunc { displayWRankUpPopup(it) }
    }

    /** `gManimSt`. */
    public val state: ManimSt = ManimSt()

    /**
     * `ProcScr_MapAnimBattle` (0x089A3508): locks the game, then (with the frame waits of the
     * decomp) the text reset, camera onto the subject, battle quotes (waiting for the event
     * engine), MOVEUNIT palettes and facing, info box, and the round loop: advance the round
     * (jumping to [endScript] at the end entry), show the round's animation, show the poison
     * effect, wait, repeat.
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
     * `ProcScr_MapAnimEnd` (0x089A35B0): the end of the animation: the defeat quote, the death
     * fade, the info box end, the steal popup, the exp bar, the weapon-broke and weapon-rank
     * popups, the camera back onto the subject, the game unlock and the clean-up.
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

    /** The `struct Unit` fields `StartMu` reads, refilled for each MOVEUNIT (allocation-free). */
    private val muUnit = MuUnit(address = 0, xPos = 0, yPos = 0, classId = 0, state = 0)

    /**
     * Transcribed from src/bmbattle.c: BeginBattleAnimations
     *   (not verified: the display writes (BG 2 clear, backdrop colour, the two map renders) are
     *   not compared; the branch it takes is verified through its callees)
     *
     * Clears BG 2 and the backdrop colour (palette entry 0), redraws the map, then (battle
     * animations off, the only case seen) ends all MOVEUNITs, redraws the map, starts the map
     * animation and sets `BATTLE_CONFIG_MAPANIMS`. With battle animations on (`sub_8055BC4`
     * true, then `BeginAnimsOnBattleAnimations`, EP-15) it fails loudly.
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
     * Sets up `gManimSt` and the MOVEUNITs and starts [script] in `PROC_TREE_3`. A refresh or
     * dance-ring battle (`BATTLE_CONFIG_REFRESH`, `BATTLE_CONFIG_DANCERING`:
     * `BeginMapAnimForDance`) and the event battle script (`ProcScr_MapAnimEventBattle`, used
     * while an event engine runs) are not written.
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
     * Sets the actor count (1 or 2: `GetSpellAssocCharCount`), the current round (the start of
     * the hit array; the decomp's `hit` argument is always `gBattleHitArray`) and the spell's
     * map animation script (0 when the weapon has none) from the actor's weapon at battle start
     * (`weaponBefore`).
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
     * sprite and starts the target's; a triangle attack (`BATTLE_HIT_ATTR_TATTACK` on the first
     * hit) also starts those of its two partners (`taUnitA`, `taUnitB`, which attack as the
     * actor's battle unit) and hides their sprites. Then faces them, orders their sprite layers
     * and sets each actor's HP (`hpInitial`, `GetUnitMaxHp`) and the default colour effects.
     */
    public fun setupMapBattleAnim(actor: BattleUnit, target: BattleUnit) {
        makeBattleMoveUnit(0, actor, actor.bytes, 0, GBATTLE_ACTOR)
        if (state.actorCount > 1) {
            // The decomp hides gBattleTarget's sprite here, not the `target` argument's.
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
     * [address]) and its battle unit [bu], and starts its MOVEUNIT. The decomp then hides the
     * MOVEUNIT of an obstacle (`BUNIT_IS_OBSTACLE`: terrain `TERRAIN_WALL_DAMAGED` or
     * `TERRAIN_SNAG`) and gives a unit with status `UNIT_STATUS_PETRIFY` or `UNIT_STATUS_13` the
     * palette `BM_OBJPAL_UNIT_GRAYED`; neither occurs in a capture (not written: they fail
     * loudly).
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
     * class's default facing (`MA_FACING_DEFAULT`) or toward the map origin (`MA_FACING_UNK`:
     * the decomp passes tile (0, 0), purpose unknown); `MA_FACING_STANDING` has no case in the
     * decomp and changes nothing.
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
     * Orders the MOVEUNITs' sprite layers ([orderActorSpriteLayers]) and faces them as the
     * actor's weapon asks (`GetSpellAssocFacing`, one of the `MA_FACING_*` modes): the actor
     * toward the target and, with two actors, the target toward the actor; a triangle attack's
     * partners face the target too.
     */
    public fun setupBattleMoveUnits() {
        val facing = spells.getSpellAssocFacing(state.bu[0]!!.weaponBefore)
        orderActorSpriteLayers()
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

    /** `array` of `sub_807B4D0`: the actor indices in drawing order. */
    private val order = IntArray(ManimSt.ACTORS)

    /**
     * Transcribed from src/mapanim_api.c: sub_807B4D0 (not verified: its only effect is the
     *   sprite animation layer, the display hook [Hooks.setMuAnimLayer], in no captured value)
     *
     * Sorts the actors by map position (row first, then column, the upper left first) and gives
     * their MOVEUNIT sprite animations the object layers 10, 9, 8, 7 (`gUnknown_08205714`) in that
     * order, so the unit nearest the top of the map is drawn on top (the lower number is assumed
     * to be the front; the decomp does not say). `sub_807B4D0` in the decomp.
     */
    public fun orderActorSpriteLayers() {
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
     * Resets the text system (`ResetText`) before the battle quote events.
     */
    public fun mapAnimPrepareBattleTalk() {
        hooks.resetText()
    }

    /**
     * Ported from src/mapanim.c: MapAnim_MoveCameraOntoSubject
     *
     * Moves the camera onto actor 0's tile (the attacker; `EnsureCameraOntoPosition`).
     */
    public fun mapAnimMoveCameraOntoSubject(proc: Proc) {
        hooks.ensureCameraOntoPosition(proc, x(0), y(0))
    }

    /**
     * Ported from src/mapanim.c: MapAnim_CallBattleQuoteEvents
     *
     * With two actors, runs the battle quote events (`CallBattleQuoteEventsIfAny`) for the
     * characters of actor 0 and actor 1.
     */
    public fun mapAnimCallBattleQuoteEvents() {
        if (state.actorCount == 2) {
            hooks.callBattleQuoteEventsIfAny(charId(0), charId(1))
        }
    }

    /**
     * Transcribed from src/mapanim.c: SetBattleMuPaletteByIndex (not verified: no
     * `CLASS_MANAKETE_MYRRH` actor appears in any Prologue capture, so the early return leaves MU
     * palettes unchanged)
     *
     * Gives actor [actorNum]'s MOVEUNIT the special palette (`BM_OBJPAL_BANIM_SPECIALMU` +
     * [actorNum]) and special sprite (`CLASS_MANAKETE`, `Pal_MapAnimManaketeMu`) when it is a
     * `CLASS_MANAKETE_MYRRH` unit that is not asleep or petrified (`UNIT_STATUS_SLEEP`,
     * `_PETRIFY`, `_13`), holds `ITEM_DIVINESTONE` and the opposing weapon is not one of
     * `ITEM_STAFF_SILENCE`, `_SLEEP`, `_BERSERK` or `ITEM_MONSTER_STONE`. The early return for
     * two units of one faction differs from the C: the C returns unless one of the two battle
     * units is an obstacle (`BUNIT_IS_OBSTACLE`, by terrain), this code tests for a missing
     * character instead (behaviour left unchanged here).
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
     * Transcribed from src/mapanim.c: SetBattleMuPalette (not verified: no
     * `CLASS_MANAKETE_MYRRH` actor appears in any Prologue capture, so the early return leaves MU
     * palettes unchanged)
     *
     * Runs [setBattleMuPaletteByIndex] for the target (when there are two actors) and then the
     * actor.
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
     * Resets the colour effects and, unless this is a steal or refresh animation or the weapon
     * shows no HP change (`GetSpellAssocReturnBool` false), opens the info box at x tile 15
     * (`StartMapAnimInfoWindow`) with a y chosen to keep clear of the fighters. Screen y is
     * tile y * 16 minus the camera. One actor: 40 above it when its screen y is 112 or more,
     * else 24 below it. Two actors 80 or more apart: 64. Otherwise the lower one on the screen
     * (greater y) decides: if its screen y is 112 or more, 40 above the other one, else 24
     * below it. The
     * window gets y / 8, a tile row.
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
     * Reads the hit at `pCurrentRound` into `gManimSt`: the subject is actor 1 when the hit is
     * a retaliation (`BATTLE_HIT_INFO_RETALIATION`) else actor 0, the target the other one (both
     * 0 with one actor), plus the hit's attributes (truncated to 16 bits, the field is a u16),
     * info and HP change; then moves to the next hit.
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
     * The repeat step of [script]: at the terminating hit (`BATTLE_HIT_INFO_END`) it breaks and
     * jumps to [endScript]; otherwise it takes the next round ([mapAnimAdvanceBattleRound]) and
     * the spell script of the new subject's weapon, then breaks to the next command.
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
     * `ProcScr_MapAnimDefaultItemEffect`: the round's proc for a weapon without a map animation
     * script of its own. Four steps toward the target, the hit (flash, HP), a wait for the HP
     * drain, four steps back.
     */
    public val roundScript: ProcScript = ProcScript(
        "ProcScr_MapAnimDefaultItemEffect",
        listOf(
            call(ProcFunc { mapAnimBeginSubjectFastAnim() }),
            call(ProcFunc { mapAnimMoveSubjectsTowardsTarget() }),
            sleep(1),
            call(ProcFunc { mapAnimMoveSubjectsTowardsTarget() }),
            sleep(1),
            call(ProcFunc { mapAnimMoveSubjectsTowardsTarget() }),
            sleep(1),
            call(ProcFunc { mapAnimMoveSubjectsTowardsTarget() }),
            sleep(1),
            call(ProcFunc { mapAnimMoveCameraOnTarget(it) }),
            sleep(2),
            call(ProcFunc { mapAnimBeginRoundSpecificAnims() }),
            sleep(8),
            repeat(ProcFunc { mapAnimWaitForHpToEndChangingMaybe(it) }),
            call(ProcFunc { hooks.moveSubjectsAwayFromTarget.invoke(it) }),
            sleep(1),
            call(ProcFunc { hooks.moveSubjectsAwayFromTarget.invoke(it) }),
            sleep(1),
            call(ProcFunc { hooks.moveSubjectsAwayFromTarget.invoke(it) }),
            sleep(1),
            call(ProcFunc { hooks.moveSubjectsAwayFromTarget.invoke(it) }),
            sleep(20),
            end(),
        ),
    )

    private fun muProc(actor: Int): MuProc = state.mu[actor]!!.state as MuProc

    /**
     * Ported from src/mapanim_spellassoc.c: MapAnim_GetRoundProcScript
     *
     * The script of the current round: [roundScript] unless the weapon has a map animation script
     * of its own (`gManimSt.specialProcScr`, a spell; none in the Prologue's windows, so that
     * branch is a `TODO`, EP-23b).
     */
    public fun mapAnimGetRoundProcScript(): ProcScript {
        if (state.specialProcScr != 0) {
            TODO("EP-23b: spell map animation")
        }
        return roundScript
    }

    /**
     * Ported from src/mapanim_spellassoc.c: MapAnim_BeginSubjectFastAnim
     *
     * Speeds up the sprite animation of the round's subject ([Mu.startMuSpeedUpAnim]).
     */
    public fun mapAnimBeginSubjectFastAnim() {
        mu.startMuSpeedUpAnim(muProc(state.subjectActorId))
    }

    /**
     * Ported from src/mapanim_spellassoc.c: MapAnimMoveUnitTowardsTargetExt
     *
     * Moves [mu1] 16 units (1 px; the positions are Q4 fixed point) toward [mu2] on each axis
     * where they differ.
     */
    public fun mapAnimMoveUnitTowardsTargetExt(mu1: MuProc, mu2: MuProc) {
        mu1.xQ4 += Integer.signum(mu2.xQ4 - mu1.xQ4) * 0x10
        mu1.yQ4 += Integer.signum(mu2.yQ4 - mu1.yQ4) * 0x10
    }

    /**
     * Ported from src/mapanim_spellassoc.c: MapAnim_MoveSubjectsTowardsTarget
     *
     * Moves the subject's MOVEUNIT toward the target's and, in a triangle attack
     * (`BATTLE_HIT_ATTR_TATTACK`), the two partners' (actors 2 and 3) too. Run four times at the
     * start of a round ([roundScript]).
     */
    public fun mapAnimMoveSubjectsTowardsTarget() {
        val target = muProc(state.targetActorId)
        mapAnimMoveUnitTowardsTargetExt(muProc(state.subjectActorId), target)
        if ((state.hitAttributes and ATTR_TATTACK) != 0) {
            mapAnimMoveUnitTowardsTargetExt(muProc(2), target)
            mapAnimMoveUnitTowardsTargetExt(muProc(3), target)
        }
    }

    /**
     * Ported from src/mapanim_spellassoc.c: MapAnimMoveUnitAwayFromTargetExt
     *
     * Moves [mu1] 16 units (1 px; Q4 fixed point) away from [mu2] on each axis where they
     * differ.
     */
    public fun mapAnimMoveUnitAwayFromTargetExt(mu1: MuProc, mu2: MuProc) {
        val dx = mu1.xQ4 - mu2.xQ4
        if (dx < 0) {
            mu1.xQ4 -= 0x10
        } else if (dx > 0) {
            mu1.xQ4 += 0x10
        }
        val dy = mu1.yQ4 - mu2.yQ4
        if (dy < 0) {
            mu1.yQ4 -= 0x10
        } else if (dy > 0) {
            mu1.yQ4 += 0x10
        }
    }

    /**
     * Ported from src/mapanim_spellassoc.c: MapAnim_MoveSubjectsAwayFromTarget
     *
     * Moves the subject away from the target and, in a triangle attack, its two partners too
     * (the [ATTR_TATTACK] triangle-attack partner branch is transcribed; no capture exercises it).
     */
    public fun mapAnimMoveSubjectsAwayFromTarget() {
        val target = muProc(state.targetActorId)
        mapAnimMoveUnitAwayFromTargetExt(muProc(state.subjectActorId), target)
        if ((state.hitAttributes and ATTR_TATTACK) != 0) {
            mapAnimMoveUnitAwayFromTargetExt(muProc(2), target)
            mapAnimMoveUnitAwayFromTargetExt(muProc(3), target)
        }
    }

    /**
     * Ported from src/mapanim_spellassoc.c: MapAnim_MoveCameraOnTarget
     *
     * Moves the camera onto the round's target tile (`targetActorId`; `EnsureCameraOntoPosition`).
     */
    public fun mapAnimMoveCameraOnTarget(proc: Proc) {
        hooks.ensureCameraOntoPosition(proc, x(state.targetActorId), y(state.targetActorId))
    }

    /**
     * Ported from src/mapanim_spellassoc.c: RegisterMapHpChangeAnim
     *
     * Lowers actor [actor]'s `hpCur` by [damage] (a negative [damage] heals), within 0..`hpMax`.
     */
    public fun registerMapHpChangeAnim(actor: Int, damage: Int) {
        val hp = state.hpCur[actor]
        state.hpCur[actor] = if (hp <= damage) 0 else hp - damage
        if (state.hpCur[actor] > state.hpMax[actor]) state.hpCur[actor] = state.hpMax[actor]
    }

    /**
     * Ported from src/mapanim_spellassoc.c: MapAnim_WaitForHPToEndChangingMaybe
     *
     * The repeat step of [roundScript]: ends the wait once `hp_changing` is 0 again. The info
     * box clears it when its displayed HP has caught up with `hp_cur`
     * (src/mapanim_infobox.c line 165); the decomp's name has "Maybe".
     */
    public fun mapAnimWaitForHpToEndChangingMaybe(proc: Proc) {
        if (state.hpChanging == 0) engine.breakProc(proc)
    }

    /**
     * Ported from src/mapanim_spellassoc.c: MapAnim_BeginRoundSpecificAnims
     *
     * The hit of the round, on the round's target (the subject itself for `BATTLE_HIT_ATTR_DEVIL`):
     * with no HP-changing weapon only the miss effect; otherwise sets `hp_changing`, registers
     * the HP change (on the subject, healed, instead of the target when actor 0 has
     * `UNIT_STATUS_RECOVER`; on the subject too for `BATTLE_HIT_ATTR_HPSTEAL`) and then plays
     * the miss, no-damage
     * (unless `BATTLE_HIT_ATTR_PETRIFY`), wall break (target on `TERRAIN_WALL_DAMAGED` or
     * `TERRAIN_SNAG`), critical or normal hit effect with its sound.
     * Verified: the hit and the miss round. The critical hit and the damaged wall / snag
     * branches are 1:1 from the C but no capture runs them (their callees are
     * [Hooks.startMuCritFlash], [Hooks.newBg0Shaker] and [Hooks.beginWallBreakAnim], stand-ins
     * until EP-23b); the miss effect is [Hooks.beginMissAnim] and the uncaptured no-damage
     * effect is [Hooks.beginNoDamageAnim] (EP-24b).
     */
    public fun mapAnimBeginRoundSpecificAnims() {
        val actor = state.subjectActorId
        val target = if ((state.hitAttributes and ATTR_DEVIL) != 0) {
            state.subjectActorId
        } else {
            state.targetActorId
        }
        val weapon = state.bu[actor]!!.weaponBefore
        val targetX = x(target) * 0x10 - bm.cameraX

        if (spells.getSpellAssocReturnBool(weapon) == 0) {
            if ((state.hitAttributes and BattleGenerate.ATTR_MISS) != 0) {
                hooks.beginMissAnim.accept(state.unitAddress[target])
            }
            return
        }

        state.hpChanging = 1
        val recover = (state.unitBytes[0]!![state.unitOffset[0] + OFF_STATUS].toInt() and 0xF) ==
            STATUS_RECOVER
        if (recover) {
            registerMapHpChangeAnim(actor, -state.hitDamage)
        } else {
            registerMapHpChangeAnim(target, state.hitDamage)
        }
        if ((state.hitAttributes and ATTR_HPSTEAL) != 0) {
            registerMapHpChangeAnim(actor, -state.hitDamage)
        }
        if (state.hitDamage < 0) return

        if ((state.hitAttributes and BattleGenerate.ATTR_MISS) != 0) {
            hooks.playSeSpacial.play(SONG_C8, targetX)
            hooks.beginMissAnim.accept(state.unitAddress[target])
            return
        }
        if (state.hitDamage == 0) {
            if ((state.hitAttributes and ATTR_PETRIFY) == 0) {
                hooks.playSeSpacial.play(SONG_2CE, targetX)
                hooks.beginNoDamageAnim.accept(state.unitAddress[target])
            }
            return
        }

        val terrain = state.bu[target]!!.terrainId
        val finishes = (state.hitInfo and BattleGenerate.INFO_FINISHES) != 0
        val sfx: Int
        if (terrain == TERRAIN_WALL_DAMAGED || terrain == TERRAIN_SNAG) {
            sfx = if (finishes) SONG_AF else SONG_B0
            hooks.beginWallBreakAnim(state.unitAddress[target], if (finishes) 1 else 0)
        } else {
            sfx = when {
                recover -> SONG_3C9
                finishes -> SONG_D5
                else -> SONG_D2
            }
        }
        if ((state.hitAttributes and BattleGenerate.ATTR_CRIT) != 0) {
            hooks.playSeSpacial.play(sfx, targetX)
            hooks.startMuCritFlash(muProc(target), spells.getSpellAssocFlashColor(weapon))
            hooks.newBg0Shaker()
            hooks.playSeSpacial.play(SONG_D8, targetX)
            mu.startMuSpeedUpAnim(muProc(actor))
            return
        }
        hooks.playSeSpacial.play(sfx, targetX)
        mu.startMuHitFlash(muProc(target), spells.getSpellAssocFlashColor(weapon))
    }

    /**
     * Ported from src/mapanim_specialeffect.c: MapAnim_BeginMISSAnim
     *
     * Decompresses the miss graphic (`Img_MapAnimMISS`) into OBJ VRAM at tile
     * `BM_OBJCHR_BANIM_EFFECT` and starts its sprite animation (`Obj_MapAnimMISS`, via
     * [ApProcs]) at the unit's screen position (tile * 16 + 8, + 16), object layer 2.
     */
    public fun mapAnimBeginMissAnim(unitAddress: Int) {
        val hw = hardware
        if (hw != null) {
            rom.decompressInto(
                IMG_MAP_ANIM_MISS, hw.vram, OBJ_VRAM0 + BM_OBJCHR_BANIM_EFFECT * CHR_SIZE,
            )
        }
        val aps = apProcs ?: return
        var actor = -1
        for (i in 0 until state.actorCount) {
            if (state.unitAddress[i] == unitAddress) {
                actor = i
                break
            }
        }
        val unitX: Int
        val unitY: Int
        if (actor >= 0) {
            unitX = x(actor)
            unitY = y(actor)
        } else {
            val offset = unitAddress - UnitTable.BLUE_ADDRESS
            if (offset in 0 until UnitTable.TOTAL_SIZE) {
                unitX = units.bytes[offset + OFF_X].toInt()
                unitY = units.bytes[offset + OFF_Y].toInt()
            } else {
                unitX = 0
                unitY = 0
            }
        }
        val x = 16 * (unitX - (bm.cameraX shr 4)) + 8
        val y = 16 * (unitY - (bm.cameraY shr 4)) + 16
        aps.create(
            definition = OBJ_MAP_ANIM_MISS,
            x = x,
            y = y,
            tileBase = BM_OBJCHR_BANIM_EFFECT,
            anim = 0,
            objLayer = 2,
        )
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
     * When an actor died ([deadActor]), clears `EVFLAG_GAMEOVER` if the dead character is
     * `CHARACTER_EIRIKA` or `CHARACTER_EPHRAIM` (the decomp does not say why) and, if the dead
     * character has a defeat quote (`CheckBattleDefeatTalk`), ends the info box and shows it.
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
     * Starts the death fade (`MU_StartDeathFade`) of the dead actor's MOVEUNIT ([deadActor]).
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
     * Ported from src/mapanim.c: BattleUnit_ShouldDisplayWpnBroke
     *
     * Returns true if [bu] belongs to the player faction (`FACTION_BLUE`) and broke its weapon
     * in battle (`DidBattleUnitBreakWeapon`; the true branch is not exercised by any Prologue
     * capture).
     */
    public fun battleUnitShouldDisplayWpnBroke(bu: BattleUnit): Boolean {
        if ((s8(bu.bytes, OFF_INDEX) and 0xC0) == UnitLoader.FACTION_BLUE) {
            return battle.didBattleUnitBreakWeapon(bu)
        }
        return false
    }

    /**
     * Ported from src/mapanim.c: DisplayWpnBrokePopup
     *
     * Shows the broken weapon popup if either battle unit is a player unit that broke its weapon
     * ([Hooks.newPopupWeaponBroke] stays a stand-in until M6-H; no Prologue capture breaks a
     * weapon).
     */
    public fun displayWpnBrokePopup(proc: Proc) {
        var unit: BattleUnit? = null
        if (battleUnitShouldDisplayWpnBroke(battle.actor)) {
            unit = battle.actor
        }
        if (battleUnitShouldDisplayWpnBroke(battle.target)) {
            unit = battle.target
        }
        if (unit != null) {
            hooks.newPopupWeaponBroke(unit.weaponBefore, proc)
        }
    }

    /**
     * Ported from src/mapanim.c: BattleUnit_ShouldDisplayWRankUp
     *
     * Returns true if [bu] belongs to the player faction (`FACTION_BLUE`) and gained a weapon
     * level in battle (`HasBattleUnitGainedWeaponLevel`; the true branch is not exercised by any
     * Prologue capture).
     */
    public fun battleUnitShouldDisplayWRankUp(bu: BattleUnit): Boolean {
        if ((s8(bu.bytes, OFF_INDEX) and 0xC0) == UnitLoader.FACTION_BLUE) {
            if (battle.hasBattleUnitGainedWeaponLevel(bu)) {
                return true
            }
        }
        return false
    }

    /**
     * Ported from src/mapanim.c: DisplayWRankUpPopup
     *
     * Shows the weapon rank up popup if either battle unit is a player unit that gained a rank
     * ([Hooks.newPopupWRankIncrease] stays a stand-in until M6-H; no Prologue capture gains a
     * rank).
     */
    public fun displayWRankUpPopup(proc: Proc) {
        var bu: BattleUnit? = null
        if (battleUnitShouldDisplayWRankUp(battle.actor)) {
            bu = battle.actor
        }
        if (battleUnitShouldDisplayWRankUp(battle.target)) {
            bu = battle.target
        }
        if (bu != null) {
            hooks.newPopupWRankIncrease(bu.weaponType, proc)
        }
    }

    /**
     * Transcribed from src/mapanim.c: MapAnim_Cleanup (not verified: its calls are display
     * set-up, and the capture's only difference is the one skipped branch: `EndAllMus` runs only
     * while an event engine exists, none does at the end of the capture's animation)
     *
     * Resets the MOVEUNIT animations, text font, info box, BG layers, UI frame graphics and
     * object UI graphics after the animation, and ends all MOVEUNITs when an event engine runs.
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

    /** `UNIT_CHAR_ID` of actor [actor]'s unit (0 for no character): the character number. */
    private fun charId(actor: Int): Int {
        val bytes = state.unitBytes[actor] ?: return 0
        val offset = state.unitOffset[actor]
        val charPtr = u32(bytes, offset + OFF_CHARACTER)
        if (charPtr == 0) return 0
        return rom.u8(charPtr + 4)
    }

    /** The default [Hooks.afterCleanup]: ends the MOVEUNITs and shows the target's sprite again. */
    private fun endAnimation() {
        mu.endAllMus()
        if (state.actorCount > 1) hooks.showUnitSprite(s8(battle.target.bytes, OFF_INDEX) and 0xFF)
    }

    /** Decomp constants (include/ and constants/ headers) and ROM addresses used above. */
    public companion object {
        /** `CHARACTER_EIRIKA` (constants/characters.h). */
        private const val CHARACTER_EIRIKA = 0x01

        /** `CHARACTER_EPHRAIM`. */
        private const val CHARACTER_EPHRAIM = 0x0F

        /** `EVFLAG_GAMEOVER` (constants/event-flags.h). */
        private const val EVFLAG_GAMEOVER = 0x65

        /** `MA_FACING_OPPONENT` (include/mapanim.h). `MA_FACING_STANDING` (3) has no case. */
        private const val MA_FACING_OPPONENT = 0

        /** `MA_FACING_DEFAULT`. */
        private const val MA_FACING_DEFAULT = 1

        /** `MA_FACING_UNK`. */
        private const val MA_FACING_UNK = 2

        /** GBA address of `gBattleActor`. */
        private const val GBATTLE_ACTOR = 0x0203A4EC

        /** GBA address of `gBattleTarget`. */
        private const val GBATTLE_TARGET = 0x0203A56C

        /** `BATTLE_HIT_ATTR_TATTACK` (bmbattle.h): triangle attack. */
        private const val ATTR_TATTACK = 1 shl 10

        /** `BATTLE_HIT_ATTR_DEVIL`. */
        private const val ATTR_DEVIL = 1 shl 7

        /** `BATTLE_HIT_ATTR_HPSTEAL`. */
        private const val ATTR_HPSTEAL = 1 shl 8

        /** `BATTLE_HIT_ATTR_PETRIFY`. */
        private const val ATTR_PETRIFY = 1 shl 13

        /** `UNIT_STATUS_RECOVER` (bmunit.h). */
        private const val STATUS_RECOVER = 10

        /** `SONG_C8` (constants/songs.h). */
        private const val SONG_C8 = 0xC8

        /** `SONG_2CE`. */
        private const val SONG_2CE = 0x2CE

        /** The raw sound id `0x3C9` the decomp passes (no `SONG_` constant). */
        private const val SONG_3C9 = 0x3C9

        /** `SONG_D2`: the sound of a normal hit (the decomp passes the literal `0xD2`). */
        private const val SONG_D2 = 0xD2

        /** `SONG_D5`: the sound of a hit with `BATTLE_HIT_INFO_FINISHES` (literal `0xD5` in C). */
        private const val SONG_D5 = 0xD5

        /** `SONG_D8`. */
        private const val SONG_D8 = 0xD8

        /** `SONG_AF`: obstacle destroyed (end). */
        private const val SONG_AF = 0xAF

        /** `SONG_B0`: obstacle destroyed (mid). */
        private const val SONG_B0 = 0xB0

        /** `BATTLE_CONFIG_REFRESH` (bmbattle.h). */
        private const val CONFIG_REFRESH = 1 shl 6

        /** `BATTLE_CONFIG_DANCERING`. */
        private const val CONFIG_DANCERING = 1 shl 9

        /** `TERRAIN_WALL_DAMAGED` (constants/terrains.h). */
        private const val TERRAIN_WALL_DAMAGED = 0x1B

        /** `TERRAIN_SNAG`. */
        private const val TERRAIN_SNAG = 0x33

        /** `UNIT_STATUS_SLEEP`. */
        private const val STATUS_SLEEP = 2

        /** `UNIT_STATUS_PETRIFY`. */
        private const val STATUS_PETRIFY = 0xB

        /** `UNIT_STATUS_13`. */
        private const val STATUS_13 = 0xD

        /** `struct Unit` offset of `pCharacterData`. */
        private const val OFF_CHARACTER = 0x00

        /** `struct Unit` offset of `pClassData`. */
        private const val OFF_CLASS = 0x04

        /** `struct Unit` offset of `index` (faction in bits 6-7). */
        private const val OFF_INDEX = 0x0B

        /** `struct Unit` offset of `state`. */
        private const val OFF_STATE = 0x0C

        /** `struct Unit` offset of `xPos`. */
        private const val OFF_X = 0x10

        /** `struct Unit` offset of `yPos`. */
        private const val OFF_Y = 0x11

        /** `struct Unit` offset of `statusIndex` (low nibble). */
        private const val OFF_STATUS = 0x30

        /** `CLASS_MANAKETE` (constants/classes.h). */
        private const val CLASS_MANAKETE = 0x0E

        /** `CLASS_MANAKETE_MYRRH`. */
        private const val CLASS_MANAKETE_MYRRH = 0x3C

        /** `ITEM_STAFF_SILENCE` (constants/items.h). */
        private const val ITEM_STAFF_SILENCE = 0x51

        /** `ITEM_STAFF_SLEEP`. */
        private const val ITEM_STAFF_SLEEP = 0x52

        /** `ITEM_STAFF_BERSERK`. */
        private const val ITEM_STAFF_BERSERK = 0x53

        /** `ITEM_DIVINESTONE`. */
        private const val ITEM_DIVINESTONE = 0xAA

        /** `ITEM_MONSTER_STONE`. */
        private const val ITEM_MONSTER_STONE = 0xB5

        /** `BM_OBJPAL_BANIM_SPECIALMU` (bmio.h): first of its two OBJ palette banks. */
        private const val BM_OBJPAL_BANIM_SPECIALMU = 8

        /** ROM address of `Pal_MapAnimManaketeMu`. */
        private const val PAL_MAP_ANIM_MANAKETE_MU = 0x089A8F74

        /** ROM address of `Img_MapAnimMISS`. */
        private const val IMG_MAP_ANIM_MISS = 0x089AC0A4

        /** ROM address of `Obj_MapAnimMISS`. */
        private const val OBJ_MAP_ANIM_MISS = 0x089AC194

        /** `BM_OBJCHR_BANIM_EFFECT` (bmio.h): first OBJ tile of the effect graphics. */
        private const val BM_OBJCHR_BANIM_EFFECT = 384

        /** `OBJ_VRAM0` as an offset into VRAM. */
        private const val OBJ_VRAM0 = 0x10000

        /** Bytes of one 4bpp tile (`0x20`). */
        private const val CHR_SIZE = 0x20

        /** `gUnknown_08205714`: the sprite object layers handed out by [orderActorSpriteLayers]. */
        private val LAYERS = intArrayOf(10, 9, 8, 7)
    }
}
