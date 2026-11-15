package fe8.game.bm

import fe8.game.RomView
import fe8.game.bm.StructCodec.s8
import fe8.game.bm.StructCodec.u32
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.sleep
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

    /** Layout constants. */
    public companion object {
        /** The actors of `gManimSt.actor`. */
        public const val ACTORS: Int = 4

        /** `MANIM_KIND_DAMAGE`. */
        public const val KIND_DAMAGE: Int = 0
    }
}

/**
 * The start of the map battle animation: `BeginBattleAnimations` (bmbattle.c) and the
 * `src/mapanim_api.c` functions it reaches (`BeginBattleMapAnims` down to the MOVEUNIT set-up),
 * which fill [state] (`gManimSt`), start one `ProcScr_Mu` per actor and the map animation proc
 * ([script]).
 *
 * [script] holds only the commands the capture runs after the start (`LockGame`, the battle talk
 * reset, SLEEP 1, camera onto the subject, SLEEP 2); the animation itself (EP-17 and later) is the
 * stand-in [Hooks.restOfAnimation]. Not written (no capture runs them; they fail loudly): the
 * dance / refresh animation (`BeginMapAnimForDance`), the event battle script
 * (`ProcScr_MapAnimEventBattle`) and the link-arena style battle animations
 * (`BeginAnimsOnBattleAnimations`, EP-14/EP-15).
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

        /** `EventEngineExists`: an event engine is running (the event battle proc is not written). */
        public var eventEngineExists: () -> Boolean = { false }

        /**
         * Stand-in until EP-14/EP-15 for `sub_8055BC4` (banim-ekrbattle): whether the battle is
         * shown with battle animations. Always false here: the map animation runs.
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

        /** `MapAnim_PrepareBattleTalk`: `ResetText`. Display only: nothing happens. */
        public var prepareBattleTalk: ProcFunc = ProcFunc {}

        /** `MapAnim_MoveCameraOntoSubject` (EP-17). Stand-in until EP-17: the camera stays. */
        public var moveCameraOntoSubject: ProcFunc = ProcFunc {}

        /**
         * The rest of `ProcScr_MapAnimBattle` (EP-17 to EP-26). Stand-in until EP-17: ends the
         * MOVEUNITs, shows the sprites again and unlocks the game, so the animation lasts the few
         * frames of the start and the battle result applies right after.
         */
        public var restOfAnimation: ProcFunc = ProcFunc {}
    }

    /** The hooks, set by the game. */
    public val hooks: Hooks = Hooks().also { it.restOfAnimation = ProcFunc { endAnimation() } }

    /** `gManimSt`. */
    public val state: ManimSt = ManimSt()

    /**
     * `ProcScr_MapAnimBattle` (0x089A3508): CALL `LockGame`, CALL `MapAnim_PrepareBattleTalk`,
     * SLEEP 1, CALL `MapAnim_MoveCameraOntoSubject`, SLEEP 2, then the stand-in (EP-17)
     * [Hooks.restOfAnimation] and END. A data table: its position and sleep timer are compared
     * with the capture.
     */
    public val script: ProcScript = ProcScript(
        "ProcScr_MapAnimBattle",
        listOf(
            call(ProcFunc { bm.lock = (bm.lock + 1) and 0xFF }),
            call(ProcFunc { hooks.prepareBattleTalk.invoke(it) }),
            sleep(1),
            call(ProcFunc { hooks.moveCameraOntoSubject.invoke(it) }),
            sleep(2),
            call(ProcFunc { hooks.restOfAnimation.invoke(it) }),
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
     * [address]) and its battle unit [bu], and starts its MOVEUNIT. An obstacle's MOVEUNIT is hidden
     * and a petrified unit's gets the gray palette; neither occurs in a capture (not written: they
     * fail loudly).
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

    /** The stand-in end of the animation, EP-17 (see [Hooks.restOfAnimation]). */
    private fun endAnimation() {
        mu.endAllMus()
        if (state.actorCount > 1) hooks.showUnitSprite(s8(battle.target.bytes, OFF_INDEX) and 0xFF)
        bm.lock = (bm.lock - 1) and 0xFF
    }

    /** Constants. */
    public companion object {
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
        private const val STATUS_PETRIFY = 0xB
        private const val STATUS_13 = 0xD
        private const val OFF_CLASS = 0x04
        private const val OFF_INDEX = 0x0B
        private const val OFF_STATE = 0x0C
        private const val OFF_X = 0x10
        private const val OFF_Y = 0x11
        private const val OFF_STATUS = 0x30
        private val LAYERS = intArrayOf(10, 9, 8, 7)
    }
}
