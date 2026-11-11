package fe8.game.bm

import gbafe.core.rng.RngService

/**
 * Transcribed from src/bmidoten.c: GenerateBestMovementScript and RevertMovementScript (not
 * verified: no frame of the captures calls it, the path arrow only re-routes when the cursor
 * leaves the path; `PlayerPhaseMoveDifferentialTest` runs it on the dumped working map and
 * compares with the script the game built from the path)
 *
 * Walks back from ([x0], [y0]) over the cost map [working] (`gWorkingBmMap`, `sizeX` by `sizeY`
 * tiles) to the tile of cost 0: each step goes to the neighbour of the lowest cost (random among
 * equal ones, [rng]), then the directions are reversed into [output] and a `MOVE_CMD_HALT`
 * follows. Deviation: stops after 0x3F steps (the C buffer size) instead of looping on an
 * unreachable target.
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
        // The directions are reversed: the walk goes back from the end.
        when (next) {
            Mu.MOVE_CMD_MOVE_LEFT -> x++
            Mu.MOVE_CMD_MOVE_RIGHT -> x--
            Mu.MOVE_CMD_MOVE_UP -> y++
            Mu.MOVE_CMD_MOVE_DOWN -> y--
        }
    }
    for (i in 0 until count) output[i] = dirs[count - 1 - i]
    output[count] = Mu.MOVE_CMD_HALT
}
