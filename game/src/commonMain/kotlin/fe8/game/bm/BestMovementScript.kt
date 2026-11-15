package fe8.game.bm

import gbafe.core.rng.RngService

/**
 * Transcribed from src/bmidoten.c: GenerateBestMovementScript (not verified: the enemy phase walk
 * calls it (`CpPerform_BeginUnitMovement`) and its script is compared there, but no
 * `@VerifiesPort` test names it; the player's path arrow does not call it in the captures, it only
 * re-routes when the cursor leaves the path; `PlayerPhaseMoveDifferentialTest` runs it on the
 * dumped working map and compares with the script the game built from the path)
 *
 * Builds the walk from the unit's tile to ([x0], [y0]) from the movement cost map [working]
 * (`gWorkingBmMap`, `sizeX` by `sizeY` tiles, cost 0 on the unit's own tile). It starts at the
 * destination and repeatedly steps to the neighbour of the lowest cost (a random one among
 * equal ones, drawn with [rng]; no random number is consumed when only one is lowest) until it
 * reaches cost 0. Tiles outside the map count as cost 0xFF. The steps are collected from the
 * destination back, so each step is written as the opposite move, and then reversed into
 * [output] using [revertMovementScript], followed by `MOVE_CMD_HALT`. Callers fill
 * `gWorkingMovementScript` with it for the AI walk, the path arrow and the player's walk.
 * Deviation: stops after 0x3F steps (the C buffer size) instead of looping on an unreachable
 * target.
 */
public fun generateBestMovementScript(
    working: BmMap.Pool,
    sizeX: Int,
    sizeY: Int,
    rng: RngService,
    x0: Int,
    y0: Int,
    output: IntArray,
) {
    var x = x0
    var y = y0
    val dirs = IntArray(0x40)
    var count = 0
    val cost = IntArray(4)
    val best = IntArray(4)
    while (working[y, x].toByte().toInt() != 0 && count < 0x3F) {
        cost[Mu.MOVE_CMD_MOVE_LEFT] = if (x == sizeX - 1) 0xFF else working[y, x + 1]
        cost[Mu.MOVE_CMD_MOVE_RIGHT] = if (x == 0) 0xFF else working[y, x - 1]
        cost[Mu.MOVE_CMD_MOVE_UP] = if (y == sizeY - 1) 0xFF else working[y + 1, x]
        cost[Mu.MOVE_CMD_MOVE_DOWN] = if (y == 0) 0xFF else working[y - 1, x]
        var bestCost = 0x100
        for (i in 0 until 4) if (bestCost > cost[i]) bestCost = cost[i]
        var n = 0
        for (i in 0 until 4) if (bestCost == cost[i]) best[n++] = i
        val next = if (n == 1) best[0] else best[rng.nextN(n)]
        dirs[count++] = next
        // The walk goes back from the end, so the neighbour at x + 1 is reached by moving LEFT.
        when (next) {
            Mu.MOVE_CMD_MOVE_LEFT -> x++
            Mu.MOVE_CMD_MOVE_RIGHT -> x--
            Mu.MOVE_CMD_MOVE_UP -> y++
            Mu.MOVE_CMD_MOVE_DOWN -> y--
        }
    }
    revertMovementScript(dirs, count, output)
}

/**
 * Ported from src/bmidoten.c: RevertMovementScript
 *
 * Reverses the first [count] movement commands from [input] into [output] and terminates with
 * [Mu.MOVE_CMD_HALT]. When [output] is the same array as [input], the reversal is performed
 * in-place.
 */
public fun revertMovementScript(
    input: IntArray,
    count: Int,
    output: IntArray = input,
) {
    if (input === output) {
        var i = 0
        var j = count - 1
        while (i < j) {
            val t = input[i]
            input[i] = input[j]
            input[j] = t
            i++
            j--
        }
    } else {
        for (i in 0 until count) {
            output[i] = input[count - 1 - i]
        }
    }
    output[count] = Mu.MOVE_CMD_HALT
}
