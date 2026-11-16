package fe8.testing

import fe8.game.RomView
import fe8.game.SoundWrapper
import fe8.game.Fe8Sound
import fe8.game.bm.MAExpBarProc
import fe8.game.bm.ManimSt
import fe8.game.bm.MapAnimExpBar
import fe8.game.bm.MapAnimInfoBox
import fe8.game.text.Fe8Text
import gba.core.VerifiesPort
import gba.testkit.ReferenceData
import gbafe.proc.ProcEngine
import gbafe.proc.ProcScript
import gbafe.proc.ProcScripts
import gbafe.render.BgHardware
import gbafe.text.MsgHuffman
import gbafe.text.MsgStrings
import gbafe.text.TextHost
import gbafe.text.TextSystem
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Differential test of the map battle exp bar (EP-26, `src/mapanim_expbar.c`): the real
 * `ProcScr_MapAnimExpBar`, started as a blocking child with the captured proc's own exp and
 * actor, follows the captured proc frame by frame: script position, sleep timer, the
 * `MAExpBarProc` fields, BG 0 tilemap, window band, palette bank 5 and the `SONG_74` player.
 * Windows: Seth's 0 -> 45 in `ram_trace_prologue_clear` (f4776..4878), and the two Soldier
 * battles of `ram_trace_prologue_enemy_phase` (f1909..1998, f2297..2387).
 */
class MapAnimExpBarDifferentialTest {
    private val ref = ReferenceData.fromEnvironment()
    private val romFile = File(
        System.getProperty("fe8.assets") ?: System.getenv("FE8_ASSETS") ?: ".assets",
        "baserom.gba",
    )

    @Test
    @VerifiesPort(
        "DrawMAExpBar", "ProcMAExpBar_FrameAdvance", "ProcMAExpBar_InitDisplay",
        "ProcMAExpBar_OnDraw", "ProcMAExpBar_PlaySound74", "ProcMAExpBar_OnIncrement",
    )
    fun `exp bar follows the capture while Seth's exp goes 0 to 45 f4776 to f4878`() {
        val frames = follow(RUN_CLEAR, 4776, 4878)
        assertTrue(frames > 90, "frames compared: $frames")
    }

    @Test
    fun `exp bar follows the capture of the first Soldier battle f1909 to f1998`() {
        assertTrue(follow(RUN_ENEMY, 1909, 1998) > 80)
    }

    @Test
    fun `exp bar follows the capture of the second Soldier battle f2297 to f2387`() {
        assertTrue(follow(RUN_ENEMY, 2297, 2387) > 80)
    }

    /** Steps [first]..[last] of [runName]; returns the number of steps compared. */
    private fun follow(runName: String, first: Int, last: Int): Int {
        assumeTrue(ref.available && romFile.isFile, "reference data or ROM not present")
        val romBytes = romFile.readBytes()
        val rom = RomView.ofRom(romBytes)
        val names = ProcArrayDump.romNames()
        val barScript = names.entries.first { it.value == "ProcScr_MapAnimExpBar" }.key

        val run = ref.run(runName)
        val dumpOfStep = run.dumps.entries.associate { (dump, step) -> step to dump }
        val procs = run.watch("procs")
        fun barAt(step: Int) = ProcArrayDump.ofWatch(procs, step, names).find(barScript)

        val captured = checkNotNull(barAt(first)) { "captured exp bar at $first" }
        fun s16(v: Int) = v.toShort().toInt()
        val from = s16(captured.u16(0x64))
        val to = s16(captured.u16(0x66))
        val actor = s16(captured.u16(0x68))

        val hw = BgHardware()
        val engine = ProcEngine()
        val m4a = Fe8Sound.create(rom)
        val sound = SoundWrapper(engine, m4a) { 0 }
        val infoBox = MapAnimInfoBox(
            engine, ManimSt(), rom, TextSystem(romBytes, NoTextHost, Fe8Text.CONFIG),
            MsgStrings(romBytes, MsgHuffman(romBytes, 0, 0), 0), hw,
        )
        val expBar = MapAnimExpBar(engine, rom, infoBox, sound, hw)

        val levelUps = ArrayList<Int>()
        expBar.hooks.startManimLevelUp = { id, _ -> levelUps.add(id) }
        var blocked = false
        engine.start(
            ProcScript(
                "parent (test)",
                listOf(
                    ProcScripts.call(
                        gbafe.proc.ProcFunc { expBar.start(it, from, to, actor) },
                    ),
                    ProcScripts.sleep(1),
                    ProcScripts.call(gbafe.proc.ProcFunc { blocked = true }),
                    ProcScripts.end(),
                ),
            ),
            2,
        )

        val header74 = m4a.songs.header(MapAnimExpBar.SONG_74)
        val playerIndex = m4a.songs.player(MapAnimExpBar.SONG_74)
        val (playerAddress, playerSlots) = M4aRam.PLAYERS.values.toList()[playerIndex]

        var compared = 0
        var vramChecked = 0
        var drawnAt = -1
        var ended = false
        for (step in first..last + 1) {
            for (tree in 0 until 8) engine.run(engine.root(tree))
            val at = "$runName step $step"
            val original = barAt(step)
            val mine = engine.find(expBar.script)
            if (original == null) {
                assertEquals(null, mine, "$at proc ended")
                ended = true
                break
            }
            val proc = checkNotNull(mine) { "$at my proc" }
            val state = proc.state as MAExpBarProc
            assertEquals(original.scrCur, proc.scrCur, "$at script position")
            assertEquals(original.u8(0x24), proc.sleepTime, "$at sleep timer")
            assertEquals(s16(original.u16(0x64)), state.expFrom, "$at expFrom")
            assertEquals(s16(original.u16(0x66)), state.expTo, "$at expTo")
            assertEquals(s16(original.u16(0x68)), state.actorId, "$at actorId")
            // The timer is uninitialised until InitDisplay has run.
            if (original.scrCur >= 4) {
                assertEquals(s16(original.u16(0x6A)), state.timer, "$at timer")
            }
            assertTrue(!blocked, "$at the parent waits")

            // `RegisterDataMove` queues the copies until VBlank, so the capture shows the tiles
            // one step after OnDraw ran.
            if (original.scrCur >= 3 && drawnAt < 0) drawnAt = step
            val dump = dumpOfStep[step] ?: continue
            val ewram = run.region(dump, "ewram")
            val iwram = run.region(dump, "iwram")
            compared++
            for (c in 0 until 32 * 32) {
                assertEquals(
                    u16(ewram, BG0_TILEMAP + 2 * c), hw.tilemaps[0][c].toInt() and 0xFFFF,
                    "$at BG0 row ${c / 32} col ${c % 32}",
                )
            }
            if (original.scrCur >= 4) {
                fun lcd8(offset: Int) = iwram[LCD_BUFFER + offset].toInt() and 0xFF
                assertEquals(lcd8(LCD_WIN0_V + 1), hw.lcd.winV[1], "$at top")
                assertEquals(lcd8(LCD_WIN0_V), hw.lcd.winV[0], "$at bottom")
                assertEquals(lcd8(LCD_WIN0_H + 1), hw.lcd.winH[1], "$at left")
                assertEquals(lcd8(LCD_WIN0_H), hw.lcd.winH[0], "$at right")
                assertEquals(
                    u16(iwram, LCD_BUFFER) and DISPCNT_WIN_MASK,
                    hw.lcd.dispcnt and DISPCNT_WIN_MASK, "$at DISPCNT window bits",
                )
                val wincnt = u32(iwram, LCD_BUFFER + LCD_WINCNT)
                assertEquals(
                    wincnt and WIN_LAYERS, hw.lcd.wincnt and WIN_LAYERS, "$at win0 layers",
                )
                assertEquals(
                    (wincnt shr 16) and WIN_LAYERS, (hw.lcd.wincnt shr 16) and WIN_LAYERS,
                    "$at wout layers",
                )
                for (i in 0 until 16) {
                    assertEquals(
                        u16(ewram, PALETTE_BUFFER + 5 * 32 + 2 * i).toShort(),
                        hw.palette[5 * 16 + i], "$at palette bank 5 color $i",
                    )
                }
            }
            if (dump.startsWith("f") && drawnAt in 0 until step) {
                val vram = run.region(dump, "vram")
                for ((tile, count) in listOf(512 to 13, 525 to 24, 549 to 11)) {
                    assertEquals(
                        vram.copyOfRange(tile * 0x20, (tile + count) * 0x20).toList(),
                        hw.vram.copyOfRange(tile * 0x20, (tile + count) * 0x20).toList(),
                        "$at tile data at tile $tile",
                    )
                }
                vramChecked++
            }
            val captured74 = M4aRam.player(iwram, playerAddress, playerSlots)
            assertEquals(
                captured74.songHeader == header74,
                m4a.players[playerIndex].songHeader == header74, "$at SONG_74 started",
            )
            if (captured74.songHeader == header74) {
                assertEquals(
                    captured74.status and STATUS_PAUSE,
                    m4a.players[playerIndex].status and STATUS_PAUSE, "$at SONG_74 stopped",
                )
            }
        }
        assertTrue(ended, "the capture ends the proc")
        assertTrue(levelUps.isEmpty(), "no level-up display for exp $from -> $to")
        assertTrue(vramChecked >= 5, "VRAM dumps compared: $vramChecked")
        for (tree in 0 until 8) engine.run(engine.root(tree))
        for (tree in 0 until 8) engine.run(engine.root(tree))
        assertTrue(blocked, "$runName: the parent resumes once the proc has ended")
        return compared
    }

    private object NoTextHost : TextHost {
        override fun language(): Int = 1
        override fun applyTextPalette(talk: Boolean, palid: Int) {}
        override fun readVram32(addr: Int): Int = 0
        override fun writeVram32(addr: Int, value: Int) {}
        override fun clearVram(dest: Int, byteCount: Int) {}
    }

    private companion object {
        const val RUN_CLEAR = "ram_trace_prologue_clear"
        const val RUN_ENEMY = "ram_trace_prologue_enemy_phase"
        const val PALETTE_BUFFER = 0x228A8 // gPaletteBuffer
        const val BG0_TILEMAP = 0x22CA8 // gBG0TilemapBuffer
        const val LCD_BUFFER = 0x3080 // gLCDControlBuffer, IWRAM
        const val LCD_WIN0_H = 0x2C
        const val LCD_WIN0_V = 0x30
        const val LCD_WINCNT = 0x34
        const val WIN_LAYERS = 0x1F
        const val DISPCNT_WIN_MASK = 0xE000
        const val STATUS_PAUSE = Int.MIN_VALUE

        fun u16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        fun u32(bytes: ByteArray, offset: Int): Int =
            u16(bytes, offset) or (u16(bytes, offset + 2) shl 16)
    }
}
