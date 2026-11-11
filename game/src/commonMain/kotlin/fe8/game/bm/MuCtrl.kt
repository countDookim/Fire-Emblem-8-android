package fe8.game.bm

import fe8.game.bm.StructCodec.put32
import fe8.game.bm.StructCodec.put8
import fe8.game.bm.StructCodec.u32
import fe8.game.bm.StructCodec.u8
import gbafe.core.rng.RngService
import gbafe.input.KeyStatus
import gbafe.proc.Proc
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcEngine
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts

/** One `struct REDA` (src/muctrl.h): a waypoint of an event walk. */
public class Reda(
    /** `x` (6 bits). */
    public val x: Int,
    /** `y` (6 bits). */
    public val y: Int,
    /** `flags` (6 bits): the MU speed config of this leg. */
    public val flags: Int,
    /** `a`: character id this leg waits for, or 0. */
    public val a: Int,
    /** `b`: with [a], the leg count of the waited-for walk after which this one may start. */
    public val b: Int,
    /** `delayFrames`. */
    public val delayFrames: Int,
) {
    /** Decoding from the ROM's packed form. */
    public companion object {
        /** Reads a REDA from its two little-endian words ([lo] bits 0-23 x/y/flags, 24-31 a). */
        public fun fromWords(lo: Int, hi: Int): Reda = Reda(
            lo and 0x3F, (lo ushr 6) and 0x3F, (lo ushr 12) and 0x3F, (lo ushr 24) and 0xFF,
            hi and 0xFFFF, hi ushr 16,
        )
    }
}

/** What `muctrl.c` reaches outside the MU, unit and map systems. Defaults are no-ops. */
public interface MuCtrlHooks {
    /** `HideUnitSprite(unit)`. */
    public fun hideUnitSprite(id: Int) {}

    /** `ShowUnitSprite(unit)`. */
    public fun showUnitSprite(id: Int) {}

    /** `RefreshEntityBmMaps`. */
    public fun refreshEntityBmMaps() {}

    /** `RefreshUnitSprites`. */
    public fun refreshUnitSprites() {}

    /** `GetUnitSpritePalette(unit)`. */
    public fun spritePalette(id: Int): Int = 0
}

/**
 * src/muctrl.c: the `E_MuCtr` proc that walks a unit along REDA waypoints with a MU, and the
 * helpers it calls (`MoveUnit_`, `AdjustNewUnitPosition`, the movement script generation of
 * bmidoten.c).
 *
 * Stand-ins: `SortMus` (only sets the sprite animation's `objLayer`; the sprite animation player
 * is not ported), `DisableAllLightRunes` / `EnableAllLightRunes` (traps are not ported), and
 * `CopyEventMoveREDAs` (the four fixed buffers are replaced by a list copy).
 */
public class MuCtrl(
    private val procs: ProcEngine,
    private val mu: Mu,
    private val table: UnitTable,
    private val map: BmMap,
    private val loader: UnitLoader,
    private val rng: RngService,
    private val keys: KeyStatus,
    private val hooks: MuCtrlHooks,
) {
    /** The fields of `struct MuCtrlProc` past the header. */
    public class MuCtrlProc {
        /** `unit` as a unit id. */
        public var unit: Int = 0

        /** `muProc`. */
        public var muProc: MuProc? = null

        /** `redas`. */
        public var redas: List<Reda> = emptyList()

        /** `redaCount`. */
        public var redaCount: Int = 0

        /** `timer`. */
        public var timer: Int = 0

        /** `delayFrames`. */
        public var delayFrames: Int = 0

        /** `flags`. */
        public var flags: Int = 0

        /** `x` (s8). */
        public var x: Int = 0

        /** `y` (s8). */
        public var y: Int = 0

        /** `unk_42`. */
        public var unk42: Int = 0

        /** `unk_43`. */
        public var unk43: Int = 0
    }

    /** `ProcScr_MuCtrl`. */
    public val script: ProcScript = ProcScript(
        "ProcScr_MuCtrl",
        listOf(
            ProcScripts.name("E_MuCtr"),
            ProcScripts.setEndCb(ProcFunc { onEnd(it.state as MuCtrlProc) }),
            ProcScripts.yield(),
            ProcScripts.whileTrue(ProcBoolFunc { sub807A0E4(it) }),
            ProcScripts.label(0),
            ProcScripts.whileTrue(ProcBoolFunc { sub807A194(it) }),
            ProcScripts.whileTrue(ProcBoolFunc { sub807A0E4(it) }),
            ProcScripts.goto(0),
            ProcScripts.label(1),
            ProcScripts.end(),
        ),
        ::MuCtrlProc,
    )

    /** The movement script of the last generation (`gWorkingMovementScript`). */
    private val workingScript = IntArray(0x40)

    /**
     * Transcribed from src/muctrl.c: MuCtrExists (not verified: trivial Proc_Find)
     */
    public fun muCtrExists(): Boolean = procs.find(script) != null

    /**
     * Transcribed from src/muctrl.c: MuCtr_StartMoveTowards (not verified: the result is only
     * checked through the walk's end position)
     *
     * Starts a walk of [unit] to ([x], [y]); [speed] goes into the REDA flags (6 bits).
     */
    public fun startMoveTowards(unit: Int, x: Int, y: Int, speed: Int, flags: Int) {
        val proc = procs.start(script, 5)
        val reda = Reda(x and 0x3F, y and 0x3F, speed and 0x3F, 0, 0, 0)
        initDefinedMove(proc.state as MuCtrlProc, unit, listOf(reda), 1, flags)
    }

    /**
     * Transcribed from src/muctrl.c: MuCtr_StartDefinedMove (not verified: no trace walk)
     *
     * Starts a walk of [unit] through [redas] ([count] of them).
     */
    public fun startDefinedMove(unit: Int, redas: List<Reda>, count: Int, flags: Int) {
        val proc = procs.start(script, 5)
        initDefinedMove(proc.state as MuCtrlProc, unit, redas.take(count), count, flags)
    }

    /**
     * Transcribed from src/muctrl.c: MuCtr_InitDefinedMove (not verified: no trace walk)
     *
     * With `count == 0` the C reads before the REDA array (undefined); here that is an error.
     */
    private fun initDefinedMove(
        proc: MuCtrlProc,
        unit: Int,
        redas: List<Reda>,
        count: Int,
        flags: Int,
    ) {
        require(count >= 1) { "MuCtr_InitDefinedMove: empty REDA list" }
        val o = table.unitOffset(unit)
        val x = table.xPos(unit)
        val y = table.yPos(unit)
        val last = redas[count - 1]
        val pos = intArrayOf(last.x, last.y)
        adjustNewUnitPosition(unit, pos, flags)
        proc.unit = unit
        proc.muProc = mu.startMu(muUnit(unit, o)) ?.state as MuProc?
        proc.redas = redas
        proc.redaCount = count
        proc.timer = 0
        proc.delayFrames = redas[0].delayFrames
        proc.flags = flags and 0xFFFF
        proc.x = pos[0]
        proc.y = pos[1]
        proc.unk42 = x
        proc.unk43 = y
        hooks.hideUnitSprite(unit)
        table.setState(unit, table.state(unit) or US_HIDDEN)
        if (map.unit[table.yPos(unit), table.xPos(unit)] == (table.index(unit) and 0xFF)) {
            map.unit[table.yPos(unit), table.xPos(unit)] = 0
        }
        map.other[y, x] = 0
        map.other[pos[1], pos[0]] = table.charId(unit)
        proc.muProc?.hiddenB = 1
    }

    private fun muUnit(unit: Int, o: Int): MuUnit {
        val classPtr = u32(table.bytes, o + OFF_CLASS)
        return MuUnit(
            address = UnitTable.BLUE_ADDRESS + o,
            xPos = table.xPos(unit),
            yPos = table.yPos(unit),
            classId = (classPtr - UnitLoader.CLASS_DATA) / UnitLoader.CLASS_DATA_SIZE + 1,
            state = table.state(unit),
            spritePalette = hooks.spritePalette(unit),
        )
    }

    /**
     * Transcribed from src/muctrl.c: SetMuMaxWalkSpeed_ -> SetMuMaxWalkSpeed (not verified:
     * trivial setter)
     */
    public fun setMuMaxWalkSpeed() {
        procs.forEach(mu.script, ProcFunc { (it.state as MuProc).fastWalkB = true })
    }

    /**
     * Transcribed from src/muctrl.c: MoveUnit_, MoveUnitExt (not verified: the headless walk
     * only reaches it through the skip path of the event, which no trace exercises)
     *
     * Puts [unit] on ([x], [y]) at once (after [adjustNewUnitPosition]) and shows it.
     */
    public fun moveUnit(unit: Int, x: Int, y: Int, flags: Int) {
        val pos = intArrayOf(x and 0x3F, y and 0x3F)
        setPos(unit, pos[0], pos[1])
        adjustNewUnitPosition(unit, pos, flags)
        setPos(unit, pos[0], pos[1])
        unitFinalizeMovement(unit)
        if (table.state(unit) and US_UNDER_A_ROOF == 0) {
            table.setState(unit, table.state(unit) and US_HIDDEN.inv())
            hooks.refreshEntityBmMaps()
            hooks.refreshUnitSprites()
        }
    }

    private fun setPos(unit: Int, x: Int, y: Int) {
        val o = table.unitOffset(unit)
        put8(table.bytes, o + OFF_X, x)
        put8(table.bytes, o + OFF_Y, y)
    }

    /**
     * Transcribed from src/bmunit.c: UnitFinalizeMovement (not verified: rescuee only; no
     * ballistae).
     */
    private fun unitFinalizeMovement(unit: Int) {
        val o = table.unitOffset(unit)
        val state = table.state(unit)
        if (state and US_RESCUING != 0) {
            val r = table.unitOffset(u8(table.bytes, o + OFF_RESCUE))
            table.bytes[r + OFF_X] = table.bytes[o + OFF_X]
            table.bytes[r + OFF_Y] = table.bytes[o + OFF_Y]
        }
        if (state and UnitLoader.US_IN_BALLISTA != 0) {
            throw UnsupportedOperationException("UnitFinalizeMovement: ballista not ported")
        }
    }

    /**
     * Transcribed from src/muctrl.c: AdjustNewUnitPosition (not verified: flag 1 is the shared
     * [UnitLoader.closestValidPosition], flag 2 needs the sum position)
     *
     * Moves [pos] (x, y) to a free tile near it: a random free neighbour with flag 2, the closest
     * reachable free tile with flag 1 where the tile is blocked.
     */
    public fun adjustNewUnitPosition(unit: Int, pos: IntArray, flags: Int) {
        val o = table.unitOffset(unit)
        if (flags and 2 != 0) {
            loader.floodMovement(map, map.range, pos[0], pos[1], loader.unitMovementCost(o)) { x, y ->
                map.terrain[y, x]
            }
            val bx = IntArray(8)
            val by = IntArray(8)
            var n = 0
            for (iy in -1..1) {
                for (ix in -1..1) {
                    val xc = pos[0] + ix
                    val yc = pos[1] + iy
                    if (ix == 0 && iy == 0) continue
                    if (map.unit[yc, xc] != 0 || map.other[yc, xc] != 0) continue
                    if (map.hidden[yc, xc] != 0) continue
                    if (map.range[yc, xc].toByte() < 0) continue
                    bx[n] = xc
                    by[n] = yc
                    n++
                }
            }
            if (n != 0) {
                val r = rng.nextN(n)
                pos[0] = bx[r]
                pos[1] = by[r]
            }
        } else if (flags and 1 != 0) {
            if (map.terrain[pos[1], pos[0]] != 0 && loader.isOccupied(pos[0], pos[1])) {
                val p = loader.closestValidPosition(o, pos[0], pos[1]) { x, y -> map.terrain[y, x] }
                // As the C: a failed search leaves x = -1 and y untouched.
                if (p >= 0) {
                    pos[0] = p and 0xFF
                    pos[1] = p ushr 8
                } else {
                    pos[0] = -1
                }
            }
        }
    }

    /**
     * Transcribed from src/muctrl.c: sub_807A644 (not verified: the generated script is only
     * checked through the walk's end position)
     *
     * Builds the best movement script from the unit's tile to [pos] (into [workingScript]);
     * with [flag] set the target tile is blocked in the terrain map first (so the search does
     * not end on an occupied tile). `DisableAllLightRunes` / `EnableAllLightRunes` are omitted.
     */
    private fun buildMoveScript(unit: Int, pos: IntArray, flag: Boolean): IntArray {
        val o = table.unitOffset(unit)
        var x = pos[0]
        var y = pos[1]
        val terrain = { tx: Int, ty: Int -> map.terrain[ty, tx] }
        if (!flag) {
            loader.floodMovement(
                map, map.range, table.xPos(unit), table.yPos(unit), loader.classMovementCost0(o),
                terrain,
            )
            generateBestMovementScript(x, y)
        } else {
            val modified: Boolean
            if (map.terrain[y, x] == 0) {
                map.terrain[y, x] = 1
                modified = true
            } else {
                val p = loader.closestValidPosition(o, x, y, terrain)
                if (p >= 0) {
                    pos[0] = p and 0xFF
                    pos[1] = p ushr 8
                } else {
                    pos[0] = -1
                }
                x = pos[0]
                y = pos[1]
                modified = false
            }
            loader.floodMovement(
                map, map.range, table.xPos(unit), table.yPos(unit), loader.classMovementCost0(o),
                terrain,
            )
            generateBestMovementScript(x, y)
            if (modified) map.terrain[y, x] = 0
        }
        return workingScript
    }

    /**
     * Transcribed from src/bmidoten.c: GenerateBestMovementScript (not verified: the tie-break
     * draws from the RNG; the walk's end position does not depend on it)
     *
     * Walks back from ([x0], [y0]) along the lowest costs of [BmMap.range] (random among equal
     * neighbours) and writes the reversed directions and a HALT into [workingScript]. Deviation:
     * stops after 0x3F steps (the C buffer size) instead of looping on an unreachable target.
     */
    private fun generateBestMovementScript(x0: Int, y0: Int) {
        var x = x0
        var y = y0
        val dirs = IntArray(0x40)
        var count = 0
        val range = map.range
        val cost = IntArray(4)
        val best = IntArray(4)
        while (range[y, x].toByte().toInt() != 0 && count < 0x3F) {
            cost[Mu.MOVE_CMD_MOVE_LEFT] = if (x == map.sizeX - 1) 0xFF else range[y, x + 1]
            cost[Mu.MOVE_CMD_MOVE_RIGHT] = if (x == 0) 0xFF else range[y, x - 1]
            cost[Mu.MOVE_CMD_MOVE_UP] = if (y == map.sizeY - 1) 0xFF else range[y + 1, x]
            cost[Mu.MOVE_CMD_MOVE_DOWN] = if (y == 0) 0xFF else range[y - 1, x]
            var bestCost = 0x100
            for (i in 0 until 4) if (bestCost > cost[i]) bestCost = cost[i]
            var n = 0
            for (i in 0 until 4) if (bestCost == cost[i]) best[n++] = i
            val next = if (n == 1) best[0] else best[rng.nextN(n)]
            dirs[count++] = next
            when (next) {
                Mu.MOVE_CMD_MOVE_LEFT -> x++
                Mu.MOVE_CMD_MOVE_RIGHT -> x--
                Mu.MOVE_CMD_MOVE_UP -> y++
                Mu.MOVE_CMD_MOVE_DOWN -> y--
            }
        }
        for (i in 0 until count) workingScript[i] = dirs[count - 1 - i]
        workingScript[count] = Mu.MOVE_CMD_HALT
    }

    /**
     * Transcribed from src/muctrl.c: sub_807A294 (not verified: needs two walks that wait on each
     * other)
     *
     * True when the walk may start: no walk of character [pid] runs, or the one that runs is
     * past leg [b] (0xFFFF = no leg condition).
     */
    private fun canStartAfter(pid: Int, b: Int): Boolean {
        if (pid == 0) return true
        var ok = true
        procs.forEach(script, ProcFunc {
            val s = it.state as MuCtrlProc
            if (table.charId(s.unit) == pid) ok = false
        })
        if (!ok && b != 0xFFFF) {
            procs.forEach(script, ProcFunc {
                val s = it.state as MuCtrlProc
                if (table.charId(s.unit) == pid && b < s.timer) ok = true
            })
        }
        return ok
    }

    /**
     * Transcribed from src/muctrl.c: sub_807A0E4 (not verified: the walk is checked through its
     * end position and frame count only)
     *
     * Waits for the delay and the walks it depends on, then starts the leg. True = keep waiting.
     */
    private fun sub807A0E4(proc: Proc): Boolean {
        val s = proc.state as MuCtrlProc
        val reda = s.redas[s.timer]
        val mu = s.muProc!!
        if (mu.fastWalkB) {
            procs.goto(proc, 1)
            return false
        }
        if (canStartAfter(reda.a, reda.b)) {
            if (keys.heldKeys and A_BUTTON != 0) s.delayFrames -= 4 else s.delayFrames--
            if (s.delayFrames < 1) {
                execReda(s)
                return false
            }
        }
        if (s.timer != 0) {
            val u = s.unit
            hooks.showUnitSprite(u)
            table.setState(u, table.state(u) and US_HIDDEN.inv())
            mu.hiddenB = 1
            map.unit[table.yPos(u), table.xPos(u)] = table.index(u) and 0xFF
            hooks.refreshUnitSprites()
        }
        return true
    }

    /**
     * Transcribed from src/muctrl.c: sub_807A194 (not verified: as [sub807A0E4])
     *
     * Waits for the MU to finish the leg, then moves to the next one. True = keep waiting.
     */
    private fun sub807A194(proc: Proc): Boolean {
        val s = proc.state as MuCtrlProc
        val mu = s.muProc!!
        if (!isMuActive(mu)) {
            val u = s.unit
            setPos(u, s.unk42, s.unk43)
            s.timer++
            if (s.timer < s.redaCount && !mu.fastWalkB) {
                s.delayFrames = s.redas[s.timer].delayFrames
                return false
            }
            procs.goto(proc, 1)
            return false
        }
        return true
    }

    /** Transcribed from src/mu.c: IsMuActive (not verified: trivial). */
    private fun isMuActive(m: MuProc): Boolean =
        m.config!!.slot != 0 && m.state != Mu.MU_STATE_INACTIVE

    /**
     * Transcribed from src/muctrl.c: MuCtr_ExecREDA_807A358 (not verified: as [sub807A0E4])
     *
     * Starts the current leg: builds the movement script to its target and hands it to the MU.
     */
    private fun execReda(s: MuCtrlProc) {
        val u = s.unit
        val reda = s.redas[s.timer]
        val flagsA = s.flags
        var flagsB = flagsA
        map.other[s.y, s.x] = 0
        val pos = IntArray(2)
        if (s.timer < s.redaCount - 1) {
            pos[0] = reda.x
            pos[1] = reda.y
            flagsB = flagsB and 0xFFFE
        } else {
            pos[0] = s.x
            pos[1] = s.y
            if (flagsA and 2 != 0) flagsB = flagsB and 0xFFFE
        }
        val commands = buildMoveScript(u, pos, flagsB and 1 != 0)
        map.other[s.y, s.x] = table.charId(u)
        if (s.unk42 != pos[0] || s.unk43 != pos[1]) {
            val m = s.muProc!!
            m.hiddenB = 0
            mu.setMuMoveScript(m, commands)
            var config = reda.flags
            if (flagsB and 8 != 0) config = config or 0x40
            m.moveConfig = if (config > 0x100) 0x100 else config
            if (flagsB and 4 != 0) mu.enableMuCamera(m) else mu.disableMuCamera(m)
            hooks.hideUnitSprite(u)
            table.setState(u, table.state(u) or US_HIDDEN)
            if (map.unit[table.yPos(u), table.xPos(u)] == (table.index(u) and 0xFF)) {
                map.unit[table.yPos(u), table.xPos(u)] = 0
            }
            hooks.refreshUnitSprites()
            s.unk42 = pos[0]
            s.unk43 = pos[1]
        }
    }

    /**
     * Transcribed from src/muctrl.c: MuCtr_OnEnd (not verified: as [sub807A0E4])
     *
     * Puts the unit on its final tile, shows it and ends the MU.
     */
    private fun onEnd(s: MuCtrlProc) {
        val m = s.muProc!!
        val u = s.unit
        if (m.fastWalkB && s.redaCount != 0) {
            moveUnit(u, s.x, s.y, s.flags and 0xFFFE)
        } else {
            setPos(u, s.x, s.y)
            unitFinalizeMovement(u)
            hooks.showUnitSprite(u)
            table.setState(u, table.state(u) and US_HIDDEN.inv())
            map.unit[table.yPos(u), table.xPos(u)] = table.index(u) and 0xFF
            hooks.refreshEntityBmMaps()
            hooks.refreshUnitSprites()
        }
        mu.endMu(m)
    }

    private companion object {
        const val OFF_CLASS = 0x04
        const val OFF_X = 0x10
        const val OFF_Y = 0x11
        const val OFF_RESCUE = 0x1B
        const val US_HIDDEN = 1 shl 0
        const val US_RESCUING = 1 shl 4
        const val US_UNDER_A_ROOF = 1 shl 7
        const val A_BUTTON = 1
    }
}
