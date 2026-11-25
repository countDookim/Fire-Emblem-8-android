package fe8.testing

import fe8.game.Fe8Boot
import fe8.game.RomView
import fe8.game.bm.BmMap
import gba.core.sram.SaveStorage
import gba.testkit.ReferenceData
import gbafe.core.rng.RngService
import java.io.File
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Timeout

/**
 * D-23 investigation: Prologue turn 1, Eirika waits on the tile above the enemy standing on a
 * forest tile; after enemy phase 1 she waits again; the woods enemy must act in enemy phase 2.
 * Positions and terrain are read from the loaded state at runtime.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(600)
class D23EnemyAiOffReproTest {
    private var boot: Fe8Boot? = null

    @AfterAll
    fun cleanup() {
        boot = null
    }

    @Test
    fun `woods enemy acts in enemy phase 2`() {
        val ref = ReferenceData.fromEnvironment()
        val assets = File(
            System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        )
        val rom = File(assets, "baserom.gba")
        assumeTrue(ref.available && rom.isFile, "reference data / ROM")
        val save = ref.run("ram_trace_prologue_resume").sram("s01001")
        var key = 0
        var frame = 0
        val b = Fe8Boot(
            RomView.ofRom(rom.readBytes()),
            @Suppress("UNCHECKED_CAST")
            ({ key } as () -> Int),
            RngService(),
            saveStorage = object : SaveStorage {
                override fun load(dest: ByteArray): Boolean {
                    save.copyInto(dest)
                    return true
                }
                override fun store(src: ByteArray) {}
            },
        )
        boot = b
        val log = StringBuilder()
        fun tick() {
            b.frame()
            frame++
        }
        fun idle() = b.engine.find(b.playerPhaseScript)?.scrCur == 16
        fun press(k: Int, hold: Int = 2, gap: Int = 12) {
            key = k
            repeat(hold) { tick() }
            key = 0
            repeat(gap) { tick() }
        }
        while (frame < 24000 && !(frame > 100 && idle())) {
            key = if (frame % 20 in 5..8) 1 else 0
            tick()
        }
        key = 0
        repeat(30) { tick() }
        val bmMapField = Fe8Boot::class.java.getDeclaredField("battleBmMap\$delegate")
        bmMapField.isAccessible = true
        val map = (bmMapField.get(b) as Lazy<*>).value as BmMap
        val u = b.units
        val ids = (1..0x3F).toList() + (0x81..0xB0)
        fun d(id: Int) = ("u%02x pos=%d,%d hp=%d st=%08x ai=%d/%d fl=%02x cfg=%04x pc=%d/%d " +
            "cnt=%d t=%02x").format(id, u.xPos(id), u.yPos(id), u.currentHp(id), u.state(id),
            u.ai1(id), u.ai2(id), u.aiFlags(id), u.aiConfig(id), u.aiAPc(id), u.aiBPc(id),
            u.aiCounter(id), map.terrain[u.yPos(id), u.xPos(id)])
        fun all(tag: String) {
            log.append("== $tag f=$frame turn=${b.control.playSt.chapterTurnNumber} " +
                "fac=${b.control.playSt.faction}\n")
            for (id in ids) if (u.isValid(id)) log.append(" ${d(id)}\n")
        }
        all("start")
        val eirika = 1
        var woods = -1
        for (id in 0x81..0xB0) {
            if (u.isValid(id) && map.terrain[u.yPos(id), u.xPos(id)] == 0x0C) woods = id
        }
        log.append("woods enemy = %02x\n".format(woods))
        // Everyone survives the exchanges (the owner's outcome: both sides survive).
        for (id in listOf(1, 2, 0x82, 0x83)) {
            u.bytes[u.unitOffset(id) + 0x12] = 99
            u.bytes[u.unitOffset(id) + 0x13] = 99
        }
        System.err.println(log)
        if (woods < 0) return
        val tx = u.xPos(woods)
        val ty = u.yPos(woods) - 1
        fun cursorTo(x: Int, y: Int) {
            var guard = 0
            while ((b.loop.bmSt.playerCursorX != x || b.loop.bmSt.playerCursorY != y) &&
                guard++ < 200) {
                val cx = b.loop.bmSt.playerCursorX
                val cy = b.loop.bmSt.playerCursorY
                val k = when {
                    cx < x -> 0x10
                    cx > x -> 0x20
                    cy < y -> 0x80
                    else -> 0x40
                }
                press(k, 1, 6)
            }
        }
        var combats = 0
        fun runUntilPlayerIdle(limit: Int) {
            var n = 0
            var inCombat = false
            var last = ""
            while (n++ < limit && !(b.control.playSt.faction == 0 && idle())) {
                tick()
                val combat = b.engine.procs.any { it.script?.name == "sProcScr_CombatAction" }
                if (combat && !inCombat) combats++
                inCombat = combat
                val now = ids.filter { u.isValid(it) }.joinToString("\n") { d(it) }
                if (now != last) {
                    log.append("-- f=$frame\n$now\n")
                    last = now
                }
            }
        }
        fun playerTurn() {
            cursorTo(u.xPos(eirika), u.yPos(eirika))
            press(1) // select
            cursorTo(tx, ty)
            press(1) // move
            repeat(40) { tick() }
            log.append("after move: ${d(eirika)}\n")
            press(0x40, 1, 10) // UP wraps to the last item (Wait)
            press(1, 2, 60)
            log.append("after wait: ${d(eirika)}\n")
            press(0x20, 1, 8) // off Eirika, then the map menu
            press(1, 2, 30)
            press(0x40, 1, 10) // UP wraps to End
            press(1, 2, 20)
        }
        playerTurn()
        runUntilPlayerIdle(8000)
        all("after EP1")
        val combatsEp1 = combats
        System.err.println(log)
        log.setLength(0)
        playerTurn()
        runUntilPlayerIdle(8000)
        all("after EP2")
        System.err.println(log)
        org.junit.jupiter.api.Assertions.assertTrue(combatsEp1 >= 2, "enemies fought in EP1")
        // D-23: an enemy that acted in EP1 left ai_a_pc at the script's AI_GOTO_START, which was
        // not ported, so its AI stopped for good. Both survivors must attack again in EP2.
        org.junit.jupiter.api.Assertions.assertTrue(combats - combatsEp1 >= 2,
            "enemies fought in EP2: ${combats - combatsEp1}")
    }
}
