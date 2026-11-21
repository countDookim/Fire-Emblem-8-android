package fe8.game.bm

import gbafe.core.flags.EventFlags
import gbafe.proc.Proc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.startChildBlocking

/**
 * The trap system of `src/bmtrick.c`: the trap pool array (`sTrapPool`, 64 slots of 8 bytes)
 * and the phase-end trap update script (`gProcScr_UpdateTraps`).
 *
 * Implements [EntityBmMaps.Traps] so the map refresh routines read this pool directly without
 * duplicate storage.
 */
public class MapTraps(
    /** The 512-byte raw trap pool array (`sTrapPool`), 64 slots of 8 bytes. */
    public val bytes: ByteArray = ByteArray(POOL_SIZE),
    private val map: BmMap,
    private val units: UnitTable,
    private val playSt: PlaySt,
    private val flags: EventFlags,
    private val targets: SelectTargets,
    private val engine: ProcEngine = ProcEngine(),
    private val action: ActionData = ActionData(),
    /** Unported or external calls made by this subsystem. */
    public val hooks: Hooks = Hooks(),
) : EntityBmMaps.Traps {

    /** Unported or external calls made by [MapTraps]. */
    public class Hooks {
        /**
         * `RefreshEntityBmMaps` (bmmap.c:59): refreshes the unit, fog and hidden maps.
         */
        public var refreshEntityBmMaps: () -> Unit = {}

        /**
         * `sub_8026414(3)` (bmtarget.c:1246): records defeat and loss in save statistics for
         * each trap damage target whose current HP is at most the damage. Stand-in until EP-1b.
         */
        public var prepareTrapDefeats: () -> Unit = {}

        /**
         * `gProcScr_TrapDamageDisplay` (bmusailment.c:938): displays animations and applies
         * hazard damage for trap targets. Defaults to [trapDamageDisplayScript].
         */
        public var trapDamageDisplay: ProcScript =
            ProcScript("gProcScr_TrapDamageDisplay", listOf(end()))

        /**
         * `TrapDamageDisplay_Watch` (bmusailment.c:856). Hook for camera centering onto trap target.
         */
        public var trapDamageDisplayWatch: (Proc) -> Unit = {}

        /**
         * `TrapDamageDisplay_Display` (bmusailment.c:866). Hook for displaying trap animation.
         */
        public var trapDamageDisplayDisplay: (Proc) -> Unit = {}

        /**
         * `FinishDamageDisplay` (bmusailment.c:558). Hook for finishing damage display.
         */
        public var finishDamageDisplay: () -> Unit = {}

        /**
         * `TrapDamageDisplay_Next` (bmusailment.c:919). Hook for applying hazard damage and advancing target.
         */
        public var trapDamageDisplayNext: (Proc) -> Unit = {}

        /**
         * `CallGameOverEvent` (event.c:401): calls the game over event engine script.
         */
        public var callGameOverEvent: () -> Unit = {}
    }

    /**
     * Script-specific proc state of `gProcScr_TrapDamageDisplay` (`struct UnknownBMUSAilmentProc`).
     */
    public class TrapDamageDisplayState(
        /** `unk_4C`: current target index in [targets]. */
        public var unk4C: Int = 0,
    )

    /**
     * `gProcScr_TrapDamageDisplay` (bmusailment.c:938): displays animations and applies
     * hazard damage for trap targets.
     */
    public val trapDamageDisplayScript: ProcScript = ProcScript(
        "gProcScr_TrapDamageDisplay",
        listOf(
            call(ProcFunc { trapDamageDisplayInit(it) }),
            label(0),
            sleep(0),
            call(ProcFunc { trapDamageDisplayCheck(it) }),
            call(ProcFunc { hooks.trapDamageDisplayWatch(it) }),
            sleep(0),
            call(ProcFunc { hooks.trapDamageDisplayDisplay(it) }),
            sleep(0),
            call(ProcFunc { hooks.finishDamageDisplay() }),
            label(1),
            call(ProcFunc { hooks.trapDamageDisplayNext(it) }),
            goto(0),
            end(),
        ),
    )

    init {
        hooks.trapDamageDisplay = trapDamageDisplayScript
    }

    /**
     * `gProcScr_UpdateTraps` (bmtrick.c:17): counts down traps, computes trap damage targets,
     * displays trap animations, resets counters, refreshes entity maps, and updates event flags.
     */
    public val updateTrapsScript: ProcScript = ProcScript(
        "gProcScr_UpdateTraps",
        listOf(
            call(ProcFunc { countDownTraps() }),
            call(ProcFunc { sub802EA00() }),
            call(ProcFunc { generateTrapDamageTargets() }),
            call(ProcFunc { sub802EA1C() }),
            call(ProcFunc { generateDisplayedTrapDamageTargets() }),
            startChildBlocking { hooks.trapDamageDisplay },
            call(ProcFunc { resetCountedDownTraps() }),
            call(ProcFunc { hooks.refreshEntityBmMaps() }),
            call(ProcFunc { postTrapExecFlag() }),
            end(),
        ),
    )

    // `GetTrap(id)` (bmtrick.c) is `sTrapPool + id`, a pointer into the pool. Transcribed from
    // src/bmtrick.c: GetTrap (not verified: no capture calls it with a trap in the pool). Here the
    // slot is its index and the four accessors below read its fields.
    override fun type(index: Int): Int = bytes[index * TRAP_SIZE + OFF_TYPE].toInt() and 0xFF
    override fun x(index: Int): Int = bytes[index * TRAP_SIZE + OFF_X].toInt() and 0xFF
    override fun y(index: Int): Int = bytes[index * TRAP_SIZE + OFF_Y].toInt() and 0xFF
    override fun extra(index: Int): Int = bytes[index * TRAP_SIZE + OFF_EXTRA].toInt() and 0xFF

    /** Unsigned byte in trap [index] at byte offset [field]. */
    public fun u8(index: Int, field: Int): Int =
        bytes[index * TRAP_SIZE + field].toInt() and 0xFF

    /** Signed byte data field [dataIndex] (0..3) in trap [index]. */
    public fun data(index: Int, dataIndex: Int): Int =
        bytes[index * TRAP_SIZE + OFF_DATA + dataIndex].toInt()

    /** Sets the x-coordinate of trap at [index]. */
    public fun setX(index: Int, value: Int) {
        bytes[index * TRAP_SIZE + OFF_X] = value.toByte()
    }

    /** Sets the y-coordinate of trap at [index]. */
    public fun setY(index: Int, value: Int) {
        bytes[index * TRAP_SIZE + OFF_Y] = value.toByte()
    }

    /** Sets the type identifier of trap at [index]. */
    public fun setType(index: Int, value: Int) {
        bytes[index * TRAP_SIZE + OFF_TYPE] = value.toByte()
    }

    /** Sets the extra data byte of trap at [index]. */
    public fun setExtra(index: Int, value: Int) {
        bytes[index * TRAP_SIZE + OFF_EXTRA] = value.toByte()
    }

    /** Sets data byte [dataIndex] (0..3) of trap at [index]. */
    public fun setData(index: Int, dataIndex: Int, value: Int) {
        bytes[index * TRAP_SIZE + OFF_DATA + dataIndex] = value.toByte()
    }

    /**
     * Transcribed from src/bmtrick.c: GetTrapAt (not verified: no trap exists in the Prologue)
     *
     * Finds the trap at tile ([x], [y]), or the topmost tile if on stacked damaged walls.
     * Returns the trap index, or -1 if no trap is found.
     */
    public fun getTrapAt(x: Int, y: Int): Int {
        var cy = y
        while (cy > 0 && map.terrain[cy, x] == TERRAIN_WALL_DAMAGED &&
            map.terrain[cy - 1, x] == TERRAIN_WALL_DAMAGED
        ) {
            cy--
        }
        var i = 0
        while (i < TRAP_MAX_COUNT && type(i) != TRAP_NONE) {
            if (x(i) == x && y(i) == cy) return i
            i++
        }
        return -1
    }

    /**
     * Transcribed from src/bmtrap.c: GetPickTrapType (not verified: no capture calls it, and the
     * Prologue pool is empty, so only the "no trap here" result can occur)
     *
     * The kind of trap the unit with id [unit] can pick or trigger on its own tile: none
     * (`TRAP_NONE`) when no trap is there or it is a ballista, `TRAP_FIRE_THIEF` for a thief on a
     * fire tile, `TRAP_MINE_ASSASSIN` for an assassin with a free item slot on a mine (none when
     * the inventory is full), none for a stealing unit on a mine, else the trap's own type.
     */
    public fun getPickTrapType(unit: Int): Int {
        val index = getTrapAt(units.xPos(unit), units.yPos(unit))
        if (index < 0) return TRAP_NONE
        val attributes = units.cAttributes(unit)
        when (type(index)) {
            TRAP_BALLISTA -> return TRAP_NONE
            TRAP_FIRETILE -> if ((attributes and UnitTable.CA_THIEF) != 0) return TRAP_FIRE_THIEF
            TRAP_MINE -> {
                if ((attributes and CA_ASSASSIN) != 0) {
                    return if (unitItemCount(unit) != UnitTable.UNIT_ITEM_COUNT) {
                        TRAP_MINE_ASSASSIN
                    } else {
                        TRAP_NONE
                    }
                } else if ((attributes and CA_STEAL) != 0) {
                    return TRAP_NONE
                }
            }
        }
        return type(index)
    }

    /** `GetUnitItemCount(unit)` (bmunit.c): one past the last filled item slot. */
    private fun unitItemCount(unit: Int): Int {
        for (slot in UnitTable.UNIT_ITEM_COUNT - 1 downTo 0) {
            if (units.item(unit, slot) != 0) return slot + 1
        }
        return 0
    }

    /**
     * Transcribed from src/bmtrick.c: DisableAllLightRunes (not verified: no Light Rune exists in
     * the Prologue, so no capture shows a toggle with a rune in the pool)
     *
     * Restores the true terrain under every Light Rune. The loop stops at the first empty slot.
     */
    public fun disableAllLightRunes() {
        var i = 0
        while (i < TRAP_MAX_COUNT && type(i) != TRAP_NONE) {
            if (type(i) == TRAP_LIGHT_RUNE) {
                map.terrain[y(i), x(i)] = map.getTrueTerrainAt(x(i), y(i))
            }
            i++
        }
    }

    /**
     * Transcribed from src/bmtrick.c: EnableAllLightRunes (not verified: no Light Rune exists in
     * the Prologue, so no capture shows a toggle with a rune in the pool)
     *
     * Sets the terrain under every Light Rune to `TERRAIN_NONE`.
     */
    public fun enableAllLightRunes() {
        var i = 0
        while (i < TRAP_MAX_COUNT && type(i) != TRAP_NONE) {
            if (type(i) == TRAP_LIGHT_RUNE) map.terrain[y(i), x(i)] = TERRAIN_NONE
            i++
        }
    }

    /**
     * Transcribed from src/bmtrick.c: GetTypedTrapAt (not verified: no trap exists in the Prologue)
     *
     * Finds the trap at tile ([x], [y]) with type [trapType], or searches the tile above if on
     * stacked damaged walls. Returns the trap index, or -1 if not found.
     */
    public fun getTypedTrapAt(x: Int, y: Int, trapType: Int): Int {
        var i = 0
        while (i < TRAP_MAX_COUNT && type(i) != TRAP_NONE) {
            if (x(i) == x && y(i) == y && type(i) == trapType) return i
            if (trapType == TERRAIN_WALL_DAMAGED && map.terrain[y, x] == TERRAIN_WALL_DAMAGED &&
                y > 0 && map.terrain[y - 1, x] == TERRAIN_WALL_DAMAGED
            ) {
                return getTrapAt(x, y - 1)
            }
            i++
        }
        return -1
    }

    /**
     * Transcribed from src/bmtrick.c: GetObstacleHpAt (not verified: no trap exists in
     * the Prologue)
     *
     * Returns the HP (extra) of the obstacle trap at tile ([x], [y]), or 0 if none.
     */
    public fun getObstacleHpAt(x: Int, y: Int): Int {
        val trapIndex = getTrapAt(x, y)
        if (trapIndex >= 0) return extra(trapIndex)
        if (y > 0 && map.terrain[y, x] == TERRAIN_WALL_DAMAGED &&
            map.terrain[y - 1, x] == TERRAIN_WALL_DAMAGED
        ) {
            val aboveIndex = getTrapAt(x, y - 1)
            if (aboveIndex >= 0) return extra(aboveIndex)
        }
        return 0
    }

    /**
     * Transcribed from src/bmtrick.c: ClearTraps (not verified: no trap exists in the Prologue)
     *
     * Clears all trap slots to `TRAP_NONE`.
     */
    public fun clearTraps() {
        bytes.fill(0)
    }

    /**
     * Transcribed from src/bmtrick.c: AddTrap (not verified: no trap added in the Prologue)
     *
     * Appends a trap to the first empty slot and returns its index.
     */
    public fun addTrap(x: Int, y: Int, trapType: Int, meta: Int): Int {
        var i = 0
        while (i < TRAP_MAX_COUNT && type(i) != TRAP_NONE) {
            i++
        }
        check(i < TRAP_MAX_COUNT) { "trap pool full" }
        setX(i, x)
        setY(i, y)
        setType(i, trapType)
        setExtra(i, meta)
        return i
    }

    /**
     * Transcribed from src/bmtrick.c: AddDamagingTrap (not verified: no trap in the Prologue)
     *
     * Appends a damaging trap with turn countdown, interval, and damage.
     */
    public fun addDamagingTrap(
        x: Int,
        y: Int,
        trapType: Int,
        meta: Int,
        turnCountdown: Int,
        turnInterval: Int,
        damage: Int,
    ): Int {
        val i = addTrap(x, y, trapType, meta)
        setData(i, TRAP_EXTDATA_TRAP_TURNFIRST, turnCountdown)
        setData(i, TRAP_EXTDATA_TRAP_TURNNEXT, turnInterval)
        setData(i, TRAP_EXTDATA_TRAP_COUNTER, turnCountdown)
        setData(i, TRAP_EXTDATA_TRAP_DAMAGE, damage)
        return i
    }

    /**
     * Transcribed from src/bmtrick.c: AddFireTile (not verified: no trap in the Prologue)
     *
     * Adds a fire tile damaging trap at ([x], [y]) with 10 damage.
     */
    public fun addFireTile(x: Int, y: Int, turnCountdown: Int, turnInterval: Int): Int =
        addDamagingTrap(x, y, TRAP_FIRETILE, 0, turnCountdown, turnInterval, 10)

    /**
     * Transcribed from src/bmtrick.c: AddGasTrap (not verified: no trap in the Prologue)
     *
     * Adds a gas damaging trap at ([x], [y]) facing [facing] with 3 damage.
     */
    public fun addGasTrap(
        x: Int,
        y: Int,
        facing: Int,
        turnCountdown: Int,
        turnInterval: Int,
    ): Int = addDamagingTrap(x, y, TRAP_GAS, facing, turnCountdown, turnInterval, 3)

    /**
     * Transcribed from src/bmtrick.c: AddArrowTrap (not verified: no trap in the Prologue)
     *
     * Adds an arrow damaging trap at column [x] with 10 damage.
     */
    public fun addArrowTrap(x: Int, turnCountdown: Int, turnInterval: Int): Int =
        addDamagingTrap(x, 0, TRAP_LIGHTARROW, 0, turnCountdown, turnInterval, 10)

    /**
     * Transcribed from src/bmtrick.c: AddLightRune (not verified: no trap in the Prologue)
     *
     * Adds a light rune trap at ([x], [y]) for 3 turns and replaces map terrain with
     * `TERRAIN_NONE`.
     */
    public fun addLightRune(x: Int, y: Int): Int {
        val i = addTrap(x, y, TRAP_LIGHT_RUNE, map.terrain[y, x])
        setData(i, TRAP_EXTDATA_RUNE_TURNSLEFT, 3)
        map.terrain[y, x] = TERRAIN_NONE
        return i
    }

    /**
     * Transcribed from src/bmtrick.c: RemoveTrap (not verified: no trap in the Prologue)
     *
     * Removes the trap at [index], shifting all subsequent traps down by one slot.
     */
    public fun removeTrap(index: Int) {
        var i = index
        while (i < TRAP_MAX_COUNT - 1 && type(i) != TRAP_NONE) {
            val src = (i + 1) * TRAP_SIZE
            val dst = i * TRAP_SIZE
            bytes.copyInto(bytes, dst, src, src + TRAP_SIZE)
            i++
        }
        bytes.fill(0, i * TRAP_SIZE, (i + 1) * TRAP_SIZE)
    }

    /**
     * Transcribed from src/bmtrick.c: RemoveLightRune (not verified: no trap in the Prologue)
     *
     * Restores original map terrain and removes the light rune trap at [index].
     */
    public fun removeLightRune(index: Int) {
        val x = x(index)
        val y = y(index)
        map.terrain[y, x] = map.getTrueTerrainAt(x, y)
        removeTrap(index)
    }

    /**
     * Transcribed from src/bmtrick.c: CountDownTraps (not verified: no trap exists in the
     * Prologue captures)
     *
     * Decrements the countdown counter for damaging traps (fire tile, gas, light arrow, map
     * change 2).
     */
    public fun countDownTraps() {
        var i = 0
        while (i < TRAP_MAX_COUNT && type(i) != TRAP_NONE) {
            when (type(i)) {
                TRAP_FIRETILE, TRAP_GAS, TRAP_LIGHTARROW, TRAP_MAPCHANGE2 -> {
                    setData(i, TRAP_EXTDATA_TRAP_COUNTER, data(i, TRAP_EXTDATA_TRAP_COUNTER) - 1)
                }
            }
            i++
        }
    }

    /**
     * Ported from src/bmtrick.c: sub_802EA00
     *
     * Temporarily sets the active faction to `FACTION_RED`, refreshes the entity battle maps
     * ([Hooks.refreshEntityBmMaps]), and restores the original faction. This ensures red
     * units appear on `gBmMapUnit` for trap detection even outside the red phase.
     */
    public fun sub802EA00() {
        val truePhase = playSt.faction
        playSt.faction = FACTION_RED
        hooks.refreshEntityBmMaps()
        playSt.faction = truePhase
    }

    /**
     * Transcribed from src/bmtrick.c: GenerateFireTileTrapTargets (not verified: no trap exists)
     *
     * Adds the unit at ([x], [y]) as a damage target.
     */
    public fun generateFireTileTrapTargets(x: Int, y: Int, damage: Int) {
        targets.addTarget(x, y, map.unit[y, x], damage)
    }

    /**
     * Transcribed from src/bmtrick.c: GenerateArrowTrapTargets (not verified: no trap exists)
     *
     * Adds all units in column [x] as damage targets.
     */
    public fun generateArrowTrapTargets(x: Int, y: Int, damage: Int) {
        for (iy in 0 until map.sizeY) {
            if (map.unit[iy, x] != 0) {
                targets.addTarget(x, iy, map.unit[iy, x], damage)
            }
        }
    }

    /**
     * Transcribed from src/bmtrick.c: GenerateGasTrapTargets (not verified: no trap exists)
     *
     * Adds units within 3 tiles in direction [facing] from ([x], [y]) as damage targets.
     */
    public fun generateGasTrapTargets(x: Int, y: Int, damage: Int, facing: Int) {
        var xInc = 0
        var yInc = 0
        when (facing) {
            FACING_UP -> yInc = -1
            FACING_DOWN -> yInc = 1
            FACING_LEFT -> xInc = -1
            FACING_RIGHT -> xInc = 1
        }
        var curX = x
        var curY = y
        for (i in 2 downTo 0) {
            curX += xInc
            curY += yInc
            if (curX in 0 until map.sizeX && curY in 0 until map.sizeY &&
                map.unit[curY, curX] != 0
            ) {
                targets.addTarget(curX, curY, map.unit[curY, curX], damage)
            }
        }
    }

    /**
     * Transcribed from src/bmtrick.c: ShouldSkipGasTrapDisplay (not verified: no trap exists)
     *
     * Returns true if no units stand within 3 tiles in direction [facing] from ([x], [y]).
     */
    public fun shouldSkipGasTrapDisplay(x: Int, y: Int, facing: Int): Boolean {
        var xInc = 0
        var yInc = 0
        when (facing) {
            FACING_UP -> yInc = -1
            FACING_DOWN -> yInc = 1
            FACING_LEFT -> xInc = -1
            FACING_RIGHT -> xInc = 1
        }
        var curX = x
        var curY = y
        var boolHasNoEffect = true
        for (i in 0 until 3) {
            curX += xInc
            curY += yInc
            if (curX in 0 until map.sizeX && curY in 0 until map.sizeY &&
                map.unit[curY, curX] != 0
            ) {
                boolHasNoEffect = false
            }
        }
        return boolHasNoEffect
    }

    /**
     * Transcribed from src/bmtrick.c: GenerateTrapDamageTargets (not verified: no trap exists in
     * the Prologue captures; the empty target list is verified)
     *
     * Initializes the target list to (0, 0) and adds targets for any active damaging traps
     * whose turn counter is 0.
     */
    public fun generateTrapDamageTargets() {
        targets.initTargets(0, 0)
        var i = 0
        while (i < TRAP_MAX_COUNT && type(i) != TRAP_NONE) {
            if (data(i, TRAP_EXTDATA_TRAP_COUNTER) == 0) {
                val dmg = data(i, TRAP_EXTDATA_TRAP_DAMAGE)
                when (type(i)) {
                    TRAP_FIRETILE -> generateFireTileTrapTargets(x(i), y(i), dmg)
                    TRAP_LIGHTARROW -> generateArrowTrapTargets(x(i), y(i), dmg)
                    TRAP_GAS -> generateGasTrapTargets(x(i), y(i), dmg, extra(i))
                }
            }
            i++
        }
    }

    /**
     * Transcribed from src/bmtrick.c: sub_802EA1C (not verified: target list is empty in the
     * Prologue captures)
     *
     * Calls `sub_8026414(3)` ([Hooks.prepareTrapDefeats]) to record defeat info for trap damage
     * targets.
     */
    public fun sub802EA1C() {
        hooks.prepareTrapDefeats()
    }

    /**
     * Transcribed from src/bmtrick.c: GenerateDisplayedTrapDamageTargets (not verified: no trap
     * exists in the Prologue captures; the empty target list is verified)
     *
     * Initializes the target list to (0, 0) and adds display and damage targets for any active
     * damaging traps whose turn counter is 0.
     */
    public fun generateDisplayedTrapDamageTargets() {
        var specialType = 0
        targets.initTargets(0, 0)
        var i = 0
        while (i < TRAP_MAX_COUNT && type(i) != TRAP_NONE) {
            if (data(i, TRAP_EXTDATA_TRAP_COUNTER) == 0) {
                val dmg = data(i, TRAP_EXTDATA_TRAP_DAMAGE)
                val tx = x(i)
                val ty = y(i)
                val tExtra = extra(i)
                when (type(i)) {
                    TRAP_FIRETILE -> {
                        if (map.unit[ty, tx] != 0) {
                            targets.addTarget(tx, ty, 0, TRAP_FIRETILE)
                            generateFireTileTrapTargets(tx, ty, dmg)
                        }
                    }
                    TRAP_GAS -> {
                        when (tExtra) {
                            FACING_UP -> specialType = 0x64
                            FACING_DOWN -> specialType = 0x65
                            FACING_LEFT -> specialType = 0x66
                            FACING_RIGHT -> specialType = 0x67
                        }
                        if (!shouldSkipGasTrapDisplay(tx, ty, tExtra)) {
                            targets.addTarget(tx, ty, 0, specialType)
                            generateGasTrapTargets(tx, ty, dmg, tExtra)
                        }
                    }
                    TRAP_LIGHTARROW -> {
                        targets.addTarget(tx, ty, 0, TRAP_LIGHTARROW)
                        generateArrowTrapTargets(tx, ty, dmg)
                    }
                    TRAP_MAPCHANGE2 -> {
                        targets.addTarget(if (tExtra != 0) tx else ty, i, 0, TRAP_MAPCHANGE2)
                    }
                }
            }
            i++
        }
    }

    /**
     * Transcribed from src/bmtrick.c: ResetCountedDownTraps (not verified: no trap exists in the
     * Prologue captures)
     *
     * For damaging traps whose turn counter has reached 0, resets the counter to the trap's
     * interval duration.
     */
    public fun resetCountedDownTraps() {
        var i = 0
        while (i < TRAP_MAX_COUNT && type(i) != TRAP_NONE) {
            when (type(i)) {
                TRAP_FIRETILE, TRAP_GAS, TRAP_LIGHTARROW, TRAP_MAPCHANGE2 -> {
                    if (data(i, TRAP_EXTDATA_TRAP_COUNTER) == 0) {
                        setData(i, TRAP_EXTDATA_TRAP_COUNTER, data(i, TRAP_EXTDATA_TRAP_TURNNEXT))
                    }
                }
            }
            i++
        }
    }

    /**
     * Ported from src/bmtrick.c: PostTrapExecFlag
     *
     * If `EVFLAG_GAMEOVER` is set or no player unit is available, calls
     * [Hooks.callGameOverEvent]. Then, if no living enemy unit remains, sets
     * `EVFLAG_DEFEAT_ALL`.
     */
    public fun postTrapExecFlag() {
        if (flags.checkFlag(EVFLAG_GAMEOVER) || units.countAvailableBlueUnits() == 0) {
            hooks.callGameOverEvent()
        }
        if (!units.areAnyEnemyUnitAlive()) {
            flags.setFlag(EVFLAG_DEFEAT_ALL)
        }
    }

    /**
     * Ported from src/bmusailment.c: TrapDamageDisplay_Init
     *
     * Initializes the target index (`unk_4C`) of [proc] to 0.
     */
    public fun trapDamageDisplayInit(proc: Proc) {
        val state = proc.state as? TrapDamageDisplayState ?: TrapDamageDisplayState().also { proc.state = it }
        state.unk4C = 0
    }

    /**
     * Ported from src/bmusailment.c: TrapDamageDisplay_Check
     *
     * Reads the current target at index `unk_4C` and updates `action.subjectIndex` with the
     * target's unit id (matching C reading `target->uid` before the count check). If all targets
     * have been checked (`unk_4C == GetSelectTargetCount()`), ends [proc]. Otherwise, if the target
     * has no unit (a hazard effect), returns to proceed to watch and display. If the unit is hidden
     * by fog of war or has 0 HP, skips to label 1 (`TrapDamageDisplay_Next`).
     */
    public fun trapDamageDisplayCheck(proc: Proc) {
        val state = proc.state as? TrapDamageDisplayState ?: TrapDamageDisplayState().also { proc.state = it }
        val uid = if (state.unk4C in 0 until SelectTargets.MAX_TARGET_COUNT) {
            targets.targetUid(state.unk4C)
        } else {
            0
        }

        action.subjectIndex = uid

        if (state.unk4C == targets.getSelectTargetCount()) {
            engine.end(proc)
            return
        }

        if (uid == 0) return

        val unitOffset = units.unitOffset(uid)
        if (unitOffset >= 0) {
            val uX = units.xPos(uid)
            val uY = units.yPos(uid)
            if (playSt.chapterVisionRange != 0 && map.fog[uY, uX] == 0) {
                engine.goto(proc, 1)
                return
            }
            if (units.currentHp(uid) == 0) {
                engine.goto(proc, 1)
                return
            }
        }
    }

    /**
     * Transcribed from src/bmtrick.c: DecayTraps (not verified: no trap exists in the Prologue
     * captures)
     *
     * Decrements the remaining duration of torchlight and light rune traps, removing them and
     * shifting subsequent traps down when their duration reaches 0.
     */
    public fun decayTraps() {
        var i = 0
        while (i < TRAP_MAX_COUNT && type(i) != TRAP_NONE) {
            when (type(i)) {
                TRAP_TORCHLIGHT -> {
                    val extraVal = extra(i) - 1
                    setExtra(i, extraVal)
                    if (extraVal == 0) {
                        removeTrap(i)
                        i--
                    }
                }
                TRAP_LIGHT_RUNE -> {
                    val turnsLeft = data(i, TRAP_EXTDATA_RUNE_TURNSLEFT) - 1
                    setData(i, TRAP_EXTDATA_RUNE_TURNSLEFT, turnsLeft)
                    if (turnsLeft == 0) {
                        removeLightRune(i)
                        i--
                    }
                }
            }
            i++
        }
    }

    /** Constants, trap IDs, data offsets, and flags for [MapTraps]. */
    public companion object {
        /** Maximum number of trap entries in `sTrapPool`. */
        public const val TRAP_MAX_COUNT: Int = 64
        /** Size in bytes of a single trap slot. */
        public const val TRAP_SIZE: Int = 8
        /** Total byte size of the trap pool array (512 bytes). */
        public const val POOL_SIZE: Int = TRAP_MAX_COUNT * TRAP_SIZE

        /** Byte offset of x coordinate. */
        public const val OFF_X: Int = 0
        /** Byte offset of y coordinate. */
        public const val OFF_Y: Int = 1
        /** Byte offset of trap type. */
        public const val OFF_TYPE: Int = 2
        /** Byte offset of extra/meta byte. */
        public const val OFF_EXTRA: Int = 3
        /** Byte offset of data bytes (4 bytes). */
        public const val OFF_DATA: Int = 4

        private const val CA_STEAL = 1 shl 2
        private const val CA_ASSASSIN = 1 shl 25

        /** Trap type: None / unused slot. */
        public const val TRAP_NONE: Int = 0
        /** Trap type: Ballista. */
        public const val TRAP_BALLISTA: Int = 1
        /** Trap type: Obstacle / breakable wall. */
        public const val TRAP_OBSTACLE: Int = 2
        /** Trap type: Map change trigger. */
        public const val TRAP_MAPCHANGE: Int = 3
        /** Trap type: Fire tile hazard. */
        public const val TRAP_FIRETILE: Int = 4
        /** Trap type: Gas hazard. */
        public const val TRAP_GAS: Int = 5
        /** Trap type: Map change 2 trigger. */
        public const val TRAP_MAPCHANGE2: Int = 6
        /** Trap type: Light arrow hazard. */
        public const val TRAP_LIGHTARROW: Int = 7
        /** Trap type: Trap 8. */
        public const val TRAP_8: Int = 8
        /** Trap type: Trap 9. */
        public const val TRAP_9: Int = 9
        /** Trap type: Torchlight duration. */
        public const val TRAP_TORCHLIGHT: Int = 10
        /** Trap type: Mine. */
        public const val TRAP_MINE: Int = 11
        /** Trap type: Gorgon egg. */
        public const val TRAP_GORGON_EGG: Int = 12
        /** Trap type: Light rune. */
        public const val TRAP_LIGHT_RUNE: Int = 13
        /** Trap type: Trap 14. */
        public const val TRAP_14: Int = 14
        /** Trap type: Fire thief. */
        public const val TRAP_FIRE_THIEF: Int = 15
        /** Trap type: Mine assassin. */
        public const val TRAP_MINE_ASSASSIN: Int = 16

        /** Data offset: ballista ridden flag. */
        public const val TRAP_EXTDATA_BLST_RIDDEN: Int = 1
        /** Data offset: ballista remaining uses. */
        public const val TRAP_EXTDATA_BLST_ITEMUSES: Int = 2
        /** Data offset: initial turn countdown for damaging traps. */
        public const val TRAP_EXTDATA_TRAP_TURNFIRST: Int = 0
        /** Data offset: turn interval for damaging traps. */
        public const val TRAP_EXTDATA_TRAP_TURNNEXT: Int = 1
        /** Data offset: remaining turn counter for damaging traps. */
        public const val TRAP_EXTDATA_TRAP_COUNTER: Int = 2
        /** Data offset: damage dealt by trap. */
        public const val TRAP_EXTDATA_TRAP_DAMAGE: Int = 3
        /** Data offset: remaining turns for light rune. */
        public const val TRAP_EXTDATA_RUNE_TURNSLEFT: Int = 2

        /** Facing direction: up. */
        public const val FACING_UP: Int = 0
        /** Facing direction: down. */
        public const val FACING_DOWN: Int = 1
        /** Facing direction: left. */
        public const val FACING_LEFT: Int = 2
        /** Facing direction: right. */
        public const val FACING_RIGHT: Int = 3

        /** Terrain ID: None. */
        public const val TERRAIN_NONE: Int = 0
        /** Terrain ID: Damaged wall. */
        public const val TERRAIN_WALL_DAMAGED: Int = 0x1B

        /** Event flag `EVFLAG_GAMEOVER` (0x65). */
        public const val EVFLAG_GAMEOVER: Int = 0x65
        /** Event flag `EVFLAG_DEFEAT_ALL` (6). */
        public const val EVFLAG_DEFEAT_ALL: Int = 6

        /** Faction ID: Red / enemy faction (0x80). */
        public const val FACTION_RED: Int = 0x80
    }
}
