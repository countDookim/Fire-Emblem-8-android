package fe8.game.save

import fe8.game.RomView
import fe8.game.bm.BattleMapKind
import fe8.game.bm.BattleUnit
import fe8.game.bm.PlaySt
import fe8.game.bm.StructCodec

/**
 * Per-unit battle statistics and favour values (`gPidStatsData` / `gBWLDataArray`,
 * `src/bmsave-bwl.c`).
 *
 * FE8 tracks usage statistics for each playable character across a playthrough: battle count,
 * win count, loss count, experience gained, squares moved, actions taken, deployments,
 * stat-screen views and death details (turn, chapter location, cause and killer), along with
 * a 16-bit favour value (`favval`, initialized to `0x2000`) that changes across battle actions
 * and phase ends.
 *
 * Stored in EWRAM at 0x0203E894 as an array of 0x46 (70) packed 16-byte `UnitUsageStats` entries
 * ([SIZE] = 1120 bytes total). Characters are 1-indexed in FE8; characters without an affinity
 * in ROM are ignored by [getPidStats] and return null / -1.
 *
 * @param rom the game ROM for reading character data and affinity.
 * @param data the 1120-byte EWRAM backing array (`gPidStatsData`).
 */
public class PidStats(
    public val rom: RomView,
    public val data: ByteArray = ByteArray(SIZE),
) {
    /** Map kind provider (`GetBattleMapKind()`); defaults to story chapter. */
    public var getBattleMapKind: () -> Int = { BattleMapKind.STORY }

    /** Skirmish location provider (`gGMData.units[0].location`). */
    public var getSkirmishLocation: () -> Int = { 0 }

    /**
     * Transcribed from src/bmsave-bwl.c: PidStatsRecordLoseData (not verified: no player death in captures)
     *
     * Records a loss for [pid].
     */
    public var pidStatsRecordLoseData: (pid: Int) -> Unit = { pid ->
        val off = getPidStats(pid)
        if (off >= 0) {
            val cur = lossAmt(pid)
            if (cur < 200) {
                setLossAmt(pid, cur + 1)
            }
            pidStatsAddFavval(pid, -0x80)
        }
    }

    /**
     * Ported from src/bmsave-bwl.c: GetPidStats
     *
     * Returns the byte offset into [data] for character [pid], or -1 if [pid] is out of range
     * (less than 1 or >= [ARRAY_NUM]) or has no affinity in ROM.
     */
    public fun getPidStats(pid: Int): Int {
        if (pid < 1 || pid >= ARRAY_NUM) return -1
        val charData = CHARACTER_DATA + (pid - 1) * CHARACTER_DATA_SIZE
        if (rom.u8(charData + CHARACTER_AFFINITY_OFFSET) == 0) return -1
        return (pid - 1) * ENTRY_SIZE
    }

    /**
     * Increments `battleAmt` (capped at 4000) and adds 4 to `favval` for [pid].
     *
     * Helper used by [pidStatsAddBattleAmt].
     */
    public fun pidStatsAddBattleAmt(pid: Int) {
        val off = getPidStats(pid)
        if (off < 0) return
        val cur = battleAmt(pid)
        if (cur < 4000) {
            setBattleAmt(pid, cur + 1)
        }
        pidStatsAddFavval(pid, 4)
    }

    /**
     * Ported from src/bmsave-bwl.c: PidStatsAddBattleAmt
     *
     * Checks if the unit at [offset] in [unitBytes] is blue faction (`FACTION_BLUE == 0`); if so,
     * delegates to [pidStatsAddBattleAmt] for its character id.
     */
    public fun pidStatsAddBattleAmt(unitBytes: ByteArray, offset: Int) {
        if ((unitBytes[offset + 0x0B].toInt() and 0xC0) != 0) return
        val charPtr = StructCodec.u32(unitBytes, offset)
        val pid = rom.u8(charPtr + 4)
        pidStatsAddBattleAmt(pid)
    }

    /**
     * Ported from src/bmsave-bwl.c: PidStatsAddWinAmt
     *
     * Increments `winAmt` (capped at 1000) and adds 16 (0x10) to `favval` for [pid].
     */
    public fun pidStatsAddWinAmt(pid: Int) {
        val off = getPidStats(pid)
        if (off < 0) return
        val cur = winAmt(pid)
        if (cur < 1000) {
            setWinAmt(pid, cur + 1)
        }
        pidStatsAddFavval(pid, 0x10)
    }

    /**
     * Ported from src/bmsave-bwl.c: PidStatsAddExpGained
     *
     * Adds [expGain] to `expGained` (capped at 4000) and adds [expGain] to `favval` for [pid].
     */
    public fun pidStatsAddExpGained(pid: Int, expGain: Int) {
        val off = getPidStats(pid)
        if (off < 0) return
        var exp = expGained(pid) + expGain
        if (exp > 4000) exp = 4000
        setExpGained(pid, exp)
        pidStatsAddFavval(pid, expGain)
    }

    /**
     * Ported from src/bmsave-bwl.c: PidStatsAddFavval
     *
     * Adjusts `favval` by [delta] (clamped between 0 and 0x4000 = 16384) for [pid].
     */
    public fun pidStatsAddFavval(pid: Int, delta: Int) {
        val off = getPidStats(pid)
        if (off < 0) return
        var cur = favval(pid) + delta
        if (cur > 0x4000) {
            cur = 0x4000
        } else if (cur < 0) {
            cur = 0
        }
        setFavval(pid, cur)
    }

    /**
     * Ported from src/bmsave-bwl.c: PidStatsSubFavval08
     *
     * Lowers `favval` by 8 for [pid].
     */
    public fun pidStatsSubFavval08(pid: Int) {
        pidStatsAddFavval(pid, -0x08)
    }

    /**
     * Transcribed from src/bmsave-bwl.c: PidStatsSubFavval100 (not verified: prep screen undeployment not in prologue clear)
     *
     * Lowers `favval` by 0x100 (256) for [pid].
     */
    public fun pidStatsSubFavval100(pid: Int) {
        pidStatsAddFavval(pid, -0x100)
    }

    /**
     * Ported from src/bmsave-bwl.c: PidStatsAddActAmt
     *
     * Increments `actAmt` (capped at 200) and adds 2 to `favval` for [pid].
     */
    public fun pidStatsAddActAmt(pid: Int) {
        val off = getPidStats(pid)
        if (off < 0) return
        val cur = actAmt(pid)
        if (cur < 200) {
            setActAmt(pid, cur + 1)
        }
        pidStatsAddFavval(pid, 2)
    }

    /**
     * Transcribed from src/bmsave-bwl.c: PidStatsAddSquaresMoved (not verified: units in prologue clear do not move across turns)
     *
     * Adds [amount] to `moveAmt` (capped at 1000) and adds 2 to `favval` for [pid].
     */
    public fun pidStatsAddSquaresMoved(pid: Int, amount: Int) {
        val off = getPidStats(pid)
        if (off < 0) return
        var moveCount = moveAmt(pid) + amount
        if (moveCount > 1000) moveCount = 1000
        setMoveAmt(pid, moveCount)
        pidStatsAddFavval(pid, 2)
    }

    /**
     * Transcribed from src/bmsave-bwl.c: PidStatsAddStatViewAmt (not verified: status screen views not captured in differential tests)
     *
     * Increments `statViewAmt` (capped at 200) and adds 2 to `favval` for [pid].
     */
    public fun pidStatsAddStatViewAmt(pid: Int) {
        val off = getPidStats(pid)
        if (off < 0) return
        val cur = statViewAmt(pid)
        if (cur < 200) {
            setStatViewAmt(pid, cur + 1)
        }
        pidStatsAddFavval(pid, 2)
    }

    /**
     * Transcribed from src/bmsave-bwl.c: PidStatsAddDeployAmt (not verified: prep screen deployment not in prologue clear)
     *
     * Increments `deployAmt` (capped at 60) and adds 0x40 (64) to `favval` for [pid].
     */
    public fun pidStatsAddDeployAmt(pid: Int) {
        val off = getPidStats(pid)
        if (off < 0) return
        val cur = deployAmt(pid)
        if (cur < 60) {
            setDeployAmt(pid, cur + 1)
        }
        pidStatsAddFavval(pid, 0x40)
    }

    /**
     * Ported from src/bmsave-bwl.c: PidStatsRecordBattleRes
     *
     * Inspects combatants [actor] and [target] for battle resolution: if a combatant reached 0 HP,
     * awards a win to the opponent if blue ([pidStatsAddWinAmt] and [PlaySt.unk48] increment) and
     * records defeat info / loss if the fallen combatant is blue.
     */
    public fun pidStatsRecordBattleRes(
        actor: BattleUnit,
        target: BattleUnit,
        playSt: PlaySt,
    ) {
        var buA: BattleUnit? = null
        var buB: BattleUnit? = null
        if (actor.bytes[0x13].toInt() == 0) {
            buA = actor
            buB = target
        }
        if (target.bytes[0x13].toInt() == 0) {
            buA = target
            buB = actor
        }
        if (buA != null) {
            if (buB != null && (buB.bytes[0x0B].toInt() and 0xC0) == 0) { // FACTION_BLUE == 0
                val charPtr = StructCodec.u32(buB.bytes, 0)
                val pid = rom.u8(charPtr + 4)
                pidStatsAddWinAmt(pid)
                playSt.unk48++
            }
            if ((buA.bytes[0x0B].toInt() and 0xC0) == 0) { // FACTION_BLUE == 0
                val charPtr = StructCodec.u32(buA.bytes, 0)
                val pid = rom.u8(charPtr + 4)
                pidStatsRecordLoseData(pid)
            }
        }
    }

    /**
     * Transcribed from src/bmsave-bwl.c: PidStatsRecordDefeatInfo (not verified: no player death in captures)
     *
     * Records defeat metadata for [pid] killed by [killerPid] due to [deathCause].
     */
    public fun pidStatsRecordDefeatInfo(
        pid: Int,
        killerPid: Int,
        deathCause: Int,
        chapterIndex: Int = 0,
        chapterTurn: Int = 0,
    ) {
        val off = getPidStats(pid)
        if (off < 0) return
        val mapKind = getBattleMapKind()
        if (mapKind == BattleMapKind.SKIRMISH) {
            setDeathSkirm(pid, true)
            setDeathLoc(pid, getSkirmishLocation())
        } else {
            setDeathSkirm(pid, false)
            setDeathLoc(pid, chapterIndex)
        }
        setDeathTurn(pid, chapterTurn)
        setKillerPid(pid, killerPid)
        setDeathCause(pid, deathCause)
    }

    /**
     * Transcribed from src/bmsave-bwl.c: PidStatsGetTotalBattleAmt (not verified: ranking screen not in prologue clear)
     *
     * Sums `battleAmt` across all slots.
     */
    public fun pidStatsGetTotalBattleAmt(): Int {
        var ret = 0
        for (i in 0 until ARRAY_NUM) {
            ret += battleAmtBySlot(i)
        }
        return ret
    }

    /**
     * Transcribed from src/bmsave-bwl.c: PidStatsGetTotalWinAmt (not verified: ranking screen not in prologue clear)
     *
     * Sums `winAmt` across all slots.
     */
    public fun pidStatsGetTotalWinAmt(): Int {
        var ret = 0
        for (i in 0 until ARRAY_NUM) {
            ret += winAmtBySlot(i)
        }
        return ret
    }

    /**
     * Transcribed from src/bmsave-bwl.c: PidStatsGetTotalLossAmt (not verified: ranking screen not in prologue clear)
     *
     * Sums `lossAmt` across all slots.
     */
    public fun pidStatsGetTotalLossAmt(): Int {
        var ret = 0
        for (i in 0 until ARRAY_NUM) {
            ret += lossAmtBySlot(i)
        }
        return ret
    }

    /**
     * Transcribed from src/bmsave-bwl.c: PidStatsGetTotalLevel (not verified: ranking screen not in prologue clear)
     *
     * Sums `expGained / 100` across all slots.
     */
    public fun pidStatsGetTotalLevel(): Int {
        var ret = 0
        for (i in 0 until ARRAY_NUM) {
            ret += expGainedBySlot(i) / 100
        }
        return ret
    }

    /**
     * Transcribed from src/bmsave-bwl.c: PidStatsGetTotalExpGain (not verified: ranking screen not in prologue clear)
     *
     * Sums `expGained` across all slots.
     */
    public fun pidStatsGetTotalExpGain(): Int {
        var ret = 0
        for (i in 0 until ARRAY_NUM) {
            ret += expGainedBySlot(i)
        }
        return ret
    }

    /**
     * Transcribed from src/bmsave-bwl.c: PidStatsGetExpGain (not verified: ranking screen not in prologue clear)
     *
     * Returns `expGained` for [pid], or 0 if [pid] is invalid or has no affinity.
     */
    public fun pidStatsGetExpGain(pid: Int): Int {
        if (getPidStats(pid) < 0) return 0
        return expGained(pid)
    }

    /**
     * Transcribed from src/bmsave-bwl.c: PidStatsGetFavval (not verified: ending details not in prologue clear)
     *
     * Returns `favval >> 6` for [pid], or 0x2000 if [pid] is invalid or has no affinity.
     */
    public fun pidStatsGetFavval(pid: Int): Int {
        if (getPidStats(pid) < 0) return 0x2000
        return favval(pid) shr 6
    }

    /**
     * Transcribed from src/bmsave-bwl.c: ClearPidStats (not verified: new game / save init in differential tests)
     *
     * Clears all [data] bytes to zero.
     */
    public fun clearPidStats() {
        data.fill(0)
    }

    // --- Field getters and setters by character pid ---

    /** Returns the loss count for [pid]. */
    public fun lossAmt(pid: Int): Int {
        val off = getPidStats(pid)
        return if (off < 0) 0 else StructCodec.u8(data, off + 0)
    }

    /** Sets the loss count for [pid]. */
    public fun setLossAmt(pid: Int, value: Int) {
        val off = getPidStats(pid)
        if (off >= 0) StructCodec.put8(data, off + 0, value)
    }

    /** Returns the favour value for [pid]. */
    public fun favval(pid: Int): Int {
        val off = getPidStats(pid)
        return if (off < 0) 0 else StructCodec.u16(data, off + 1)
    }

    /** Sets the favour value for [pid]. */
    public fun setFavval(pid: Int, value: Int) {
        val off = getPidStats(pid)
        if (off >= 0) StructCodec.put16(data, off + 1, value)
    }

    /** Returns the action count for [pid]. */
    public fun actAmt(pid: Int): Int {
        val off = getPidStats(pid)
        return if (off < 0) 0 else StructCodec.u8(data, off + 3)
    }

    /** Sets the action count for [pid]. */
    public fun setActAmt(pid: Int, value: Int) {
        val off = getPidStats(pid)
        if (off >= 0) StructCodec.put8(data, off + 3, value)
    }

    /** Returns the stat-screen view count for [pid]. */
    public fun statViewAmt(pid: Int): Int {
        val off = getPidStats(pid)
        return if (off < 0) 0 else StructCodec.u8(data, off + 4)
    }

    /** Sets the stat-screen view count for [pid]. */
    public fun setStatViewAmt(pid: Int, value: Int) {
        val off = getPidStats(pid)
        if (off >= 0) StructCodec.put8(data, off + 4, value)
    }

    /** Returns the chapter index where [pid] died (bitfield 6 bits). */
    public fun deathLoc(pid: Int): Int {
        val off = getPidStats(pid)
        return if (off < 0) 0 else StructCodec.bits(StructCodec.u16(data, off + 5), 0, 6)
    }

    /** Sets the chapter index where [pid] died (bitfield 6 bits). */
    public fun setDeathLoc(pid: Int, value: Int) {
        val off = getPidStats(pid)
        if (off < 0) return
        val cur = StructCodec.u16(data, off + 5)
        val next = (cur and 0x3F.inv()) or StructCodec.field(value, 0, 6)
        StructCodec.put16(data, off + 5, next)
    }

    /** Returns the turn on which [pid] died (bitfield 10 bits). */
    public fun deathTurn(pid: Int): Int {
        val off = getPidStats(pid)
        return if (off < 0) 0 else StructCodec.bits(StructCodec.u16(data, off + 5), 6, 10)
    }

    /** Sets the turn on which [pid] died (bitfield 10 bits). */
    public fun setDeathTurn(pid: Int, value: Int) {
        val off = getPidStats(pid)
        if (off < 0) return
        val cur = StructCodec.u16(data, off + 5)
        val next = (cur and (0x3FF shl 6).inv()) or StructCodec.field(value, 6, 10)
        StructCodec.put16(data, off + 5, next)
    }

    /** Returns the deployment count for [pid] (bitfield 6 bits). */
    public fun deployAmt(pid: Int): Int {
        val off = getPidStats(pid)
        return if (off < 0) 0 else StructCodec.bits(StructCodec.u16(data, off + 7), 0, 6)
    }

    /** Sets the deployment count for [pid] (bitfield 6 bits). */
    public fun setDeployAmt(pid: Int, value: Int) {
        val off = getPidStats(pid)
        if (off < 0) return
        val cur = StructCodec.u16(data, off + 7)
        val next = (cur and 0x3F.inv()) or StructCodec.field(value, 0, 6)
        StructCodec.put16(data, off + 7, next)
    }

    /** Returns the total squares moved for [pid] (bitfield 10 bits). */
    public fun moveAmt(pid: Int): Int {
        val off = getPidStats(pid)
        return if (off < 0) 0 else StructCodec.bits(StructCodec.u16(data, off + 7), 6, 10)
    }

    /** Sets the total squares moved for [pid] (bitfield 10 bits). */
    public fun setMoveAmt(pid: Int, value: Int) {
        val off = getPidStats(pid)
        if (off < 0) return
        val cur = StructCodec.u16(data, off + 7)
        val next = (cur and (0x3FF shl 6).inv()) or StructCodec.field(value, 6, 10)
        StructCodec.put16(data, off + 7, next)
    }

    /** Returns the defeat cause code for [pid] (bitfield 4 bits). */
    public fun deathCause(pid: Int): Int {
        val off = getPidStats(pid)
        return if (off < 0) 0 else StructCodec.bits(StructCodec.u16(data, off + 9), 0, 4)
    }

    /** Sets the defeat cause code for [pid] (bitfield 4 bits). */
    public fun setDeathCause(pid: Int, value: Int) {
        val off = getPidStats(pid)
        if (off < 0) return
        val cur = StructCodec.u16(data, off + 9)
        val next = (cur and 0x0F.inv()) or StructCodec.field(value, 0, 4)
        StructCodec.put16(data, off + 9, next)
    }

    /** Returns the total experience gained for [pid] (bitfield 12 bits). */
    public fun expGained(pid: Int): Int {
        val off = getPidStats(pid)
        return if (off < 0) 0 else StructCodec.bits(StructCodec.u16(data, off + 9), 4, 12)
    }

    /** Sets the total experience gained for [pid] (bitfield 12 bits). */
    public fun setExpGained(pid: Int, value: Int) {
        val off = getPidStats(pid)
        if (off < 0) return
        val cur = StructCodec.u16(data, off + 9)
        val next = (cur and (0xFFF shl 4).inv()) or StructCodec.field(value, 4, 12)
        StructCodec.put16(data, off + 9, next)
    }

    /** Returns the total wins for [pid] (bitfield 10 bits). */
    public fun winAmt(pid: Int): Int {
        val off = getPidStats(pid)
        return if (off < 0) 0 else StructCodec.bits(StructCodec.u32(data, off + 11), 0, 10)
    }

    /** Sets the total wins for [pid] (bitfield 10 bits). */
    public fun setWinAmt(pid: Int, value: Int) {
        val off = getPidStats(pid)
        if (off < 0) return
        val cur = StructCodec.u32(data, off + 11)
        val next = (cur and 0x3FF.inv()) or StructCodec.field(value, 0, 10)
        StructCodec.put32(data, off + 11, next)
    }

    /** Returns the total battles for [pid] (bitfield 12 bits). */
    public fun battleAmt(pid: Int): Int {
        val off = getPidStats(pid)
        return if (off < 0) 0 else StructCodec.bits(StructCodec.u32(data, off + 11), 10, 12)
    }

    /** Sets the total battles for [pid] (bitfield 12 bits). */
    public fun setBattleAmt(pid: Int, value: Int) {
        val off = getPidStats(pid)
        if (off < 0) return
        val cur = StructCodec.u32(data, off + 11)
        val next = (cur and (0xFFF shl 10).inv()) or StructCodec.field(value, 10, 12)
        StructCodec.put32(data, off + 11, next)
    }

    /** Returns the character ID of the unit that defeated [pid] (bitfield 9 bits). */
    public fun killerPid(pid: Int): Int {
        val off = getPidStats(pid)
        return if (off < 0) 0 else StructCodec.bits(StructCodec.u32(data, off + 11), 22, 9)
    }

    /** Sets the character ID of the unit that defeated [pid] (bitfield 9 bits). */
    public fun setKillerPid(pid: Int, value: Int) {
        val off = getPidStats(pid)
        if (off < 0) return
        val cur = StructCodec.u32(data, off + 11)
        val next = (cur and (0x1FF shl 22).inv()) or StructCodec.field(value, 22, 9)
        StructCodec.put32(data, off + 11, next)
    }

    /** Returns true if [pid] was defeated in a world-map skirmish (bitfield 1 bit). */
    public fun deathSkirm(pid: Int): Boolean {
        val off = getPidStats(pid)
        return if (off < 0) false else StructCodec.bits(StructCodec.u32(data, off + 11), 31, 1) != 0
    }

    /** Sets whether [pid] was defeated in a world-map skirmish (bitfield 1 bit). */
    public fun setDeathSkirm(pid: Int, value: Boolean) {
        val off = getPidStats(pid)
        if (off < 0) return
        val cur = StructCodec.u32(data, off + 11)
        val next = (cur and (1 shl 31).inv()) or StructCodec.field(if (value) 1 else 0, 31, 1)
        StructCodec.put32(data, off + 11, next)
    }

    // --- Slot-based accessors (0 until ARRAY_NUM) ---

    /** Returns the loss count for the entry at [slot]. */
    public fun lossAmtBySlot(slot: Int): Int = StructCodec.u8(data, slot * ENTRY_SIZE + 0)

    /** Returns the favour value for the entry at [slot]. */
    public fun favvalBySlot(slot: Int): Int = StructCodec.u16(data, slot * ENTRY_SIZE + 1)

    /** Returns the action count for the entry at [slot]. */
    public fun actAmtBySlot(slot: Int): Int = StructCodec.u8(data, slot * ENTRY_SIZE + 3)

    /** Returns the stat-screen view count for the entry at [slot]. */
    public fun statViewAmtBySlot(slot: Int): Int = StructCodec.u8(data, slot * ENTRY_SIZE + 4)

    /** Returns the chapter index where the character at [slot] died. */
    public fun deathLocBySlot(slot: Int): Int =
        StructCodec.bits(StructCodec.u16(data, slot * ENTRY_SIZE + 5), 0, 6)

    /** Returns the turn on which the character at [slot] died. */
    public fun deathTurnBySlot(slot: Int): Int =
        StructCodec.bits(StructCodec.u16(data, slot * ENTRY_SIZE + 5), 6, 10)

    /** Returns the deployment count for the character at [slot]. */
    public fun deployAmtBySlot(slot: Int): Int =
        StructCodec.bits(StructCodec.u16(data, slot * ENTRY_SIZE + 7), 0, 6)

    /** Returns the total squares moved for the character at [slot]. */
    public fun moveAmtBySlot(slot: Int): Int =
        StructCodec.bits(StructCodec.u16(data, slot * ENTRY_SIZE + 7), 6, 10)

    /** Returns the defeat cause code for the character at [slot]. */
    public fun deathCauseBySlot(slot: Int): Int =
        StructCodec.bits(StructCodec.u16(data, slot * ENTRY_SIZE + 9), 0, 4)

    /** Returns the total experience gained for the character at [slot]. */
    public fun expGainedBySlot(slot: Int): Int =
        StructCodec.bits(StructCodec.u16(data, slot * ENTRY_SIZE + 9), 4, 12)

    /** Returns the total wins for the character at [slot]. */
    public fun winAmtBySlot(slot: Int): Int =
        StructCodec.bits(StructCodec.u32(data, slot * ENTRY_SIZE + 11), 0, 10)

    /** Returns the total battles for the character at [slot]. */
    public fun battleAmtBySlot(slot: Int): Int =
        StructCodec.bits(StructCodec.u32(data, slot * ENTRY_SIZE + 11), 10, 12)

    /** Returns the killer character ID for the character at [slot]. */
    public fun killerPidBySlot(slot: Int): Int =
        StructCodec.bits(StructCodec.u32(data, slot * ENTRY_SIZE + 11), 22, 9)

    /** Returns true if the character at [slot] died in a skirmish. */
    public fun deathSkirmBySlot(slot: Int): Boolean =
        StructCodec.bits(StructCodec.u32(data, slot * ENTRY_SIZE + 11), 31, 1) != 0

    /** Constants for [PidStats] array sizing and defeat cause codes. */
    public companion object {
        /** Size in bytes of a single `UnitUsageStats` entry. */
        public const val ENTRY_SIZE: Int = 16

        /** Maximum number of unit entries tracked in `gPidStatsData` (`BWL_ARRAY_NUM`). */
        public const val ARRAY_NUM: Int = 0x46

        /** Total size in bytes of `gPidStatsData` (`0x46 * 16 = 1120`). */
        public const val SIZE: Int = ARRAY_NUM * ENTRY_SIZE // 1120

        /** Defeat cause: none or default. */
        public const val DEFEAT_CAUSE_NONE: Int = 0

        /** Defeat cause: cause 1. */
        public const val DEFEAT_CAUSE_1: Int = 1

        /** Defeat cause: fallen in combat. */
        public const val DEFEAT_CAUSE_COMBAT: Int = 2

        /** Defeat cause: fallen to map trap or obstacle. */
        public const val DEFEAT_CAUSE_TRAP: Int = 3

        /** Defeat cause: fallen to poison damage. */
        public const val DEFEAT_CAUSE_POISON: Int = 4

        /** Defeat cause: cause 5. */
        public const val DEFEAT_CAUSE_5: Int = 5

        /** Defeat cause: fallen in the arena. */
        public const val DEFEAT_CAUSE_ARENA: Int = 6

        /** Defeat cause: cause 7. */
        public const val DEFEAT_CAUSE_7: Int = 7

        private const val CHARACTER_DATA = 0x08803D64
        private const val CHARACTER_DATA_SIZE = 0x34
        private const val CHARACTER_AFFINITY_OFFSET = 0x09
    }
}
