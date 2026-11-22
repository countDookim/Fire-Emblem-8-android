package fe8.game.save

import fe8.game.bm.ChapterData
import fe8.game.bm.PLAY_FLAG_HARD
import fe8.game.bm.PlaySt
import fe8.game.bm.UnitTable

/**
 * The per-chapter ranking computation of `src/gamerankings.c`, run when a chapter is left
 * (`GameControl_ChapterSwitch`): tactics, survival, funds, combat and experience ranks of the
 * cleared chapter and the overall rank added to `gPlaySt.unk_2C_04`.
 *
 * The whole-game ranks (`GetGameTacticsRank` etc.) are not part of this class.
 *
 * @property playSt `gPlaySt`, read and written (the ranking fields at 0x2C and 0x30..0x3F).
 * @property chapterStats `gChapterStats`, the records of the cleared chapters.
 * @property pidStats `gPidStatsData`, summed for battle, win and experience totals.
 * @property units the unit arrays (`GetUnit`).
 * @property chapterData `gChapterDataTable`.
 * @property getPartyTotalGoldValue `GetPartyTotalGoldValue` (src/bmitem.c, PC-3d): party gold
 *   plus convoy and unit item values.
 */
public class ChapterRankings(
    private val playSt: PlaySt,
    private val chapterStats: ChapterStats,
    private val pidStats: PidStats,
    private val units: UnitTable,
    private val chapterData: ChapterData,
    private val getPartyTotalGoldValue: () -> Int = { 0 },
) {
    private val hard: Boolean get() = playSt.chapterStateBits and PLAY_FLAG_HARD != 0

    /**
     * Transcribed from src/gamerankings.c: ComputeChapterRankings (not verified: the `unk_2C_04`
     *   0xFF clamp, the skipped `chapterModeIndex` arms and the swapped `expRank`/`combatRank`
     *   argument order survive mutation (both ranks are 4))
     *
     * Stores the five chapter ranks in [playSt] for the common, Eirika and Ephraim modes and adds
     * the overall rank to `unk_2C_04` (capped at 0xFF), when a chapter stats slot exists. The C
     * passes `expRank` and `combatRank` to `GetOverallRank` in the order of its parameters
     * `combatRank`, `expRank` (swapped); this keeps that order.
     */
    public fun computeChapterRankings() {
        if (chapterStats.getNextChapterStatsSlot() <= 0) return
        when (playSt.chapterModeIndex) {
            CHAPTER_MODE_COMMON, CHAPTER_MODE_EIRIKA, CHAPTER_MODE_EPHRAIM -> {
                playSt.tacticsRank = getChapterTacticsRank()
                playSt.survivalRank = getChapterSurvivalRank()
                playSt.fundsRank = getChapterFundsRank()
                playSt.combatRank = getChapterCombatRank()
                playSt.expRank = getChapterExpRank()
            }
        }
        val overallRank = getOverallRank(
            playSt.tacticsRank,
            playSt.survivalRank,
            playSt.fundsRank,
            playSt.expRank,
            playSt.combatRank,
        )
        playSt.unk2C04 = minOf(playSt.unk2C04 + overallRank, 0xFF)
    }

    /**
     * Transcribed from src/gamerankings.c: GetChapterTacticsRank (not verified: only the "turn <=
     *   every threshold" result 4 occurs (turn 3), so the compare operator and the D/C/B/A
     *   thresholds survive mutation)
     *
     * Returns 0..4: the first of the thresholds D, C, B, A of the current chapter that the turn
     * number exceeds, else 4.
     */
    public fun getChapterTacticsRank(): Int {
        val turn = playSt.chapterTurnNumber and 0xFFFF
        for (i in 0 until 4) {
            if (turn > chapterData.turnsForTacticsRank(playSt.chapterIndex, i, hard)) return i
        }
        return 4
    }

    /**
     * Transcribed from src/gamerankings.c: GetChapterSurvivalRank (not verified: only death count 0
     *   (rank 4) occurs, so `>=` vs `>` survives mutation)
     *
     * Returns 0..4: the first of the death count thresholds 4, 3, 2, 1 that
     * [getChapterDeathCount] reaches, else 4.
     */
    public fun getChapterSurvivalRank(): Int {
        val deathCount = getChapterDeathCount()
        for (i in 0 until 4) {
            if (deathCount >= SURVIVAL_THRESHOLDS[i]) return i
        }
        return 4
    }

    /**
     * Transcribed from src/gamerankings.c: GetChapterDeathCount (not verified: no unit is dead at
     *   r09443 and chapterIndex is 0, so the `deathLoc` compare survives mutation)
     *
     * Counts the valid units with ids 1..0x3F that are dead (`US_DEAD` without `US_BIT16`) and
     * whose character died in the current chapter (`deathLoc == chapterIndex`).
     */
    public fun getChapterDeathCount(): Int {
        var count = 0
        for (id in 1 until 0x40) {
            if (!units.isValid(id)) continue
            if ((units.state(id) and (US_DEAD or US_BIT16)) != US_DEAD) continue
            if (pidStats.deathLoc(units.charId(id)) != playSt.chapterIndex) continue
            count++
        }
        return count
    }

    /**
     * Transcribed from src/gamerankings.c: GetChapterFundsRank (not verified: the rank is the top 4, so
     *   the lower ranks survive mutation; the gold stored, 7910 over a previous 0, matches the
     *   capture through [getPartyTotalGoldValue])
     *
     * Stores the party gold in `unk_30.total_gold` and returns 0..4 from the gold gained since the
     * last call against 20/40/60/80 percent of the cleared chapter's `goldForFundsRank`.
     */
    public fun getChapterFundsRank(): Int {
        val totalGold = getPartyTotalGoldValue()
        val goldInChapter = totalGold - playSt.totalGold
        playSt.totalGold = totalGold
        val ent = chapterStats.getNextChapterStatsSlot() - 1
        val required = chapterData.goldForFundsRank(chapterStats.chapterIndex(ent), hard)
        return when {
            goldInChapter * 100 >= required * 80 -> 4
            goldInChapter * 100 >= required * 60 -> 3
            goldInChapter * 100 >= required * 40 -> 2
            goldInChapter * 100 >= required * 20 -> 1
            else -> 0
        }
    }

    /**
     * Transcribed from src/gamerankings.c: GetChapterCombatRank (not verified: only rank 4 occurs,
     *   so `<` vs `<=` survives mutation; PC-3d)
     *
     * Returns 0..4: the first of the thresholds 15, 25, 35, 40 above [getChapterWinPerc], else 4.
     */
    public fun getChapterCombatRank(): Int {
        val winPercentage = getChapterWinPerc()
        for (i in 0 until 4) {
            if (winPercentage < COMBAT_THRESHOLDS[i]) return i
        }
        return 4
    }

    /**
     * Transcribed from src/gamerankings.c: GetChapterWinPerc (not verified: the percentage is only
     *   seen through the combat rank 4, so the `* 100`, the 100 clamp, the `unk_8_1` split and the
     *   no-battle 40 survive mutation)
     *
     * Returns the win percentage (0..100) over the battles since the last call, or 40 when none
     * were fought, and records the new battle and win totals in `unk_30` (both capped at 0xFFFFF).
     * The previous win total is stored split: low 12 bits in `unk_4_14`, the rest in `unk_8_1`.
     */
    public fun getChapterWinPerc(): Int {
        val totalBattles = minOf(pidStats.pidStatsGetTotalBattleAmt(), 0xFFFFF)
        val totalWins = minOf(pidStats.pidStatsGetTotalWinAmt(), 0xFFFFF)
        val chapterTotalBattles = playSt.unk30Unk400
        if (totalBattles == chapterTotalBattles) return 40
        val a = playSt.unk30Unk414
        val b = playSt.unk30Unk81 shl 12
        val num = (totalWins - (b or a)) * 100
        val percentage = minOf(num / (totalBattles - chapterTotalBattles), 100)
        playSt.unk30Unk400 = totalBattles
        playSt.unk30Unk414 = totalWins and 0xFFF
        playSt.unk30Unk81 = totalWins ushr 12
        return percentage
    }

    /**
     * Transcribed from src/gamerankings.c: GetChapterExpRank (not verified: the previous `unk_8_2`
     *   is 0 and only rank 4 occurs, so the difference, the 0xFFFFF clamp and the thresholds
     *   survive mutation)
     *
     * Stores the total experience gained (capped at 0xFFFFF) in `unk_30.unk_8_2` and returns 0..4:
     * the first of the cleared chapter's D, C, B, A experience thresholds that the experience
     * gained since the last call is below, else 4.
     */
    public fun getChapterExpRank(): Int {
        val totalExp = minOf(pidStats.pidStatsGetTotalExpGain(), 0xFFFFF)
        val expInChapter = totalExp - playSt.unk30Unk82
        playSt.unk30Unk82 = totalExp
        val ent = chapterStats.getNextChapterStatsSlot() - 1
        val chapterIndex = chapterStats.chapterIndex(ent)
        for (i in 0 until 4) {
            if (expInChapter < chapterData.gainedExpForExpRank(chapterIndex, i, hard)) return i
        }
        return 4
    }

    /**
     * Transcribed from src/gamerankings.c: GetOverallRank (not verified: only all-4 ranks (sum 550,
     *   result 5) occur, so most weights and limits survive mutation)
     *
     * Sums the weights of the five ranks (each 0..4) and returns the first of the 5 limits above
     * the sum, else 5.
     */
    public fun getOverallRank(
        tacticsRank: Int,
        survivalRank: Int,
        fundsRank: Int,
        combatRank: Int,
        expRank: Int,
    ): Int {
        val tmp = (
            OVERALL_WEIGHTS[0][tacticsRank] + OVERALL_WEIGHTS[1][survivalRank] +
                OVERALL_WEIGHTS[2][fundsRank] + OVERALL_WEIGHTS[3][combatRank] +
                OVERALL_WEIGHTS[4][expRank]
            ) and 0xFFFF
        for (i in 0 until 5) {
            if (tmp < OVERALL_LIMITS[i]) return i
        }
        return 5
    }

    private companion object {
        const val CHAPTER_MODE_COMMON = 1
        const val CHAPTER_MODE_EIRIKA = 2
        const val CHAPTER_MODE_EPHRAIM = 3
        const val US_DEAD = 1 shl 2
        const val US_BIT16 = 1 shl 16
        val SURVIVAL_THRESHOLDS = intArrayOf(4, 3, 2, 1)
        val COMBAT_THRESHOLDS = intArrayOf(15, 25, 35, 40)

        /** `gOverallRankWeightLookup[5][5]`. */
        val OVERALL_WEIGHTS = arrayOf(
            intArrayOf(40, 80, 120, 160, 200),
            intArrayOf(15, 35, 55, 75, 100),
            intArrayOf(0, 20, 40, 60, 80),
            intArrayOf(0, 20, 40, 60, 80),
            intArrayOf(10, 30, 50, 70, 90),
        )

        /** `gOverallRankLookup`. */
        val OVERALL_LIMITS = intArrayOf(100, 200, 300, 450, 550)
    }
}
