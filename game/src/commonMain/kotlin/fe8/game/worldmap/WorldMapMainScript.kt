package fe8.game.worldmap

import gbafe.proc.ProcArgFunc
import gbafe.proc.ProcBoolFunc
import gbafe.proc.ProcCmd
import gbafe.proc.ProcFunc
import gbafe.proc.ProcScripts.block
import gbafe.proc.ProcScripts.call
import gbafe.proc.ProcScripts.call2
import gbafe.proc.ProcScripts.callArg
import gbafe.proc.ProcScripts.end
import gbafe.proc.ProcScripts.goto
import gbafe.proc.ProcScripts.label
import gbafe.proc.ProcScripts.repeat
import gbafe.proc.ProcScripts.sleep
import gbafe.proc.ProcScripts.whileTrue
import gbafe.proc.ProcScripts.yield

/**
 * The commands of `ProcScr_WorldMapMain` after [WorldMapMain.mainScriptPrefix] (command 10 on),
 * each callee looked up by its decomp name in [funcs], [bools] or [argFuncs] when it runs. A
 * name that is not registered does nothing (`PROC_REPEAT` then never ends, a `PROC_WHILE` and a
 * `PROC_CALL_2` are false). `WorldMapBoot.mainScript` registers the callees the Prologue runs
 * (`WorldMap_CallIntroEvent`, `WorldMap_WaitForChapterIntroEvents`, `WorldMap_HideEverything`,
 * `ResetWorldMapScreen`); the free-roam ones are stand-ins until M6-I.
 *
 * Transcribed from src/worldmap_main.c: ProcScr_WorldMapMain (not verified: only the path
 * label 15 -> label 25 -> `PROC_END` of the Prologue exit is observed, by WorldMapExitTest)
 */
public class WorldMapMainScript {
    /** `PROC_CALL` / `PROC_REPEAT` callees by decomp name. */
    public val funcs: MutableMap<String, ProcFunc> = HashMap()

    /** `PROC_WHILE` / `PROC_CALL_2` callees by decomp name. */
    public val bools: MutableMap<String, ProcBoolFunc> = HashMap()

    /** `PROC_CALL_ARG` callees by decomp name. */
    public val argFuncs: MutableMap<String, ProcArgFunc> = HashMap()

    /** A `PROC_CALL` / `PROC_REPEAT` callee looked up by [name] in [funcs] when it runs. */
    private fun f(name: String) = ProcFunc { funcs[name]?.invoke(it) }

    /** A `PROC_WHILE` / `PROC_CALL_2` callee by [name] in [bools]; false if not registered. */
    private fun fb(name: String) = ProcBoolFunc { bools[name]?.invoke(it) ?: false }

    /** A `PROC_CALL_ARG` callee by [name] in [argFuncs]; does nothing if not registered. */
    private fun fa(name: String) =
        ProcArgFunc { arg, proc -> argFuncs[name]?.invoke(arg, proc) ?: true }

    /**
     * Commands 10..183, the last being `PROC_END`: the world map's screens and sub-flows as
     * labels (`PROC_LABEL(n)`, jumped to by `Proc_Goto`); the trailing `// n` is the command index.
     */
    public fun commands(): List<ProcCmd> = listOf(
            call(f("WorldMap_GenerateRandomMonsters")), // 10
            call(f("WorldMap_SetMonsterMergedState")), // 11
            goto(3), // 12
            // Monsters merge in (`_WmMergeMonsters`), then falls through to label 3.
            label(2), // 13
            call(f("sub_80B9A34")), // 14
            whileTrue(fb("FadeInExists")), // 15
            call(f("_WmMergeMonsters")), // 16
            repeat(f("WorldMap_WaitMonsterMerging")), // 17
            call(f("WorldMap_SetMonsterMergedState")), // 18
            // The common return point: waits for the fade-in, then falls through into the map loop.
            label(3), // 19
            sleep(1), // 20
            call(f("WorldMap_UpdateBgm")), // 21
            call(f("sub_80B9A34")), // 22
            whileTrue(fb("FadeInExists")), // 23
            call(f("sub_80BF13C")), // 24
            call(f("StartWorldmapMinimap")), // 25
            sleep(1), // 26
            // The world map main loop (`WorldMap_Loop`); `PROC_BLOCK` after it waits for a Goto.
            label(4), // 27
            call(f("sub_80B97F8")), // 28
            repeat(f("WorldMap_Loop")), // 29
            block(), // 30
            // General menu (`StartWMGeneralMenu`).
            label(5), // 31
            call(f("sub_80BF15C")), // 32
            call(f("EndWorldmapMinimap")), // 33
            sleep(1), // 34
            call(f("StartWMGeneralMenu")), // 35
            goto(3), // 36
            label(6), // 37
            call(f("sub_80BF15C")), // 38
            call(f("EndWorldmapMinimap")), // 39
            sleep(1), // 40
            call(f("sub_80B8A7C")), // 41
            repeat(f("sub_80B8B3C")), // 42
            label(7), // 43
            repeat(f("sub_80B8BA4")), // 44
            sleep(1), // 45
            goto(3), // 46
            label(8), // 47
            repeat(f("sub_80B96DC")), // 48
            goto(4), // 49
            label(9), // 50
            call(f("StartFastFadeToBlack")), // 51
            repeat(f("WaitForFade")), // 52
            call(f("sub_80B9810")), // 53
            call(f("sub_80B9820")), // 54
            yield(), // 55
            call(f("WorldMap_Init")), // 56
            goto(23), // 57
            // Guide screen (`WorldMap_CallGuide`) between fades to and from black.
            label(10), // 58
            call(f("StartFastFadeToBlack")), // 59
            repeat(f("WaitForFade")), // 60
            call(f("sub_80B9810")), // 61
            call2(fb("WorldMap_CallGuide")), // 62
            call(f("WorldMap_Init")), // 63
            call(f("sub_80B989C")), // 64
            sleep(1), // 65
            call(f("sub_80B98A8")), // 66
            call(f("StartFastFadeFromBlack")), // 67
            repeat(f("WaitForFade")), // 68
            goto(3), // 69
            // Config screen (`WorldMap_StartConfigScreen`) between fades.
            label(11), // 70
            call(f("StartFastFadeToBlack")), // 71
            repeat(f("WaitForFade")), // 72
            call(f("sub_80B9810")), // 73
            call2(fb("WorldMap_StartConfigScreen")), // 74
            call(f("WorldMap_Init")), // 75
            call(f("WorldMap_ResumeFromConfigScreen")), // 76
            sleep(1), // 77
            call(f("sub_80B9924")), // 78
            call(f("StartFastFadeFromBlack")), // 79
            repeat(f("WaitForFade")), // 80
            goto(3), // 81
            // Status screen (`StartWorldmapStatusScreen`) between fades.
            label(12), // 82
            call(f("StartFastFadeToBlack")), // 83
            repeat(f("WaitForFade")), // 84
            call(f("sub_80B9810")), // 85
            call2(fb("StartWorldmapStatusScreen")), // 86
            call(f("WorldMap_Init")), // 87
            call(f("sub_80B9994")), // 88
            sleep(1), // 89
            call(f("sub_80B99A0")), // 90
            call(f("StartFastFadeFromBlack")), // 91
            repeat(f("WaitForFade")), // 92
            goto(3), // 93
            // Post-chapter save menu (`Make6C_SaveMenuPostChapter`).
            label(13), // 94
            call(f("StartFastFadeToBlack")), // 95
            repeat(f("WaitForFade")), // 96
            call(f("sub_80B9FD4")), // 97
            call(f("sub_80B9810")), // 98
            call(f("Make6C_SaveMenuPostChapter")), // 99
            yield(), // 100
            call(f("WorldMap_Init")), // 101
            goto(23), // 102
            // Leaving the map: ends the minimap, falls through to label 15.
            label(14), // 103
            call(f("sub_80BF15C")), // 104
            call(f("EndWorldmapMinimap")), // 105
            // Exit to the chapter intro event (`WorldMap_CallIntroEvent`); the Prologue ends here.
            label(15), // 106
            call(f("WorldMap_CallIntroEvent")), // 107
            repeat(f("WorldMap_WaitForChapterIntroEvents")), // 108
            call(f("WorldMap_HideEverything")), // 109
            goto(25), // 110
            // Skirmish animation (`Worldmap_WaitForSkirmishAnim`), then label 25.
            label(16), // 111
            call(f("sub_80BF15C")), // 112
            call(f("EndWorldmapMinimap")), // 113
            sleep(30), // 114
            call(f("sub_80B9A58")), // 115
            repeat(f("Worldmap_WaitForSkirmishAnim")), // 116
            sleep(20), // 117
            call(f("sub_80B9AB0")), // 118
            callArg(fa("NewFadeOut"), 16), // 119
            whileTrue(fb("FadeOutExists")), // 120
            call(f("sub_80B9AEC")), // 121
            goto(25), // 122
            // `Worlmap_StartGmapSogu` effect, then back to label 3.
            label(17), // 123
            call(f("Worlmap_StartGmapSogu")), // 124
            repeat(f("Worlmap_WaitGmapSogu")), // 125
            sleep(8), // 126
            goto(3), // 127
            // Node menu (`StartWMNodeMenu`).
            label(18), // 128
            call(f("sub_80BF15C")), // 129
            call(f("EndWorldmapMinimap")), // 130
            sleep(1), // 131
            call(f("StartWMNodeMenu")), // 132
            sleep(6), // 133
            goto(3), // 134
            // Armory (`WorldMap_StartArmory`) after a fade out; back via label 24.
            label(19), // 135
            callArg(fa("NewFadeOut"), 16), // 136
            whileTrue(fb("FadeOutExists")), // 137
            call(f("sub_80B9810")), // 138
            call(f("WorldMap_StartArmory")), // 139
            yield(), // 140
            call(f("WorldMap_Init")), // 141
            goto(24), // 142
            // Vendor (`WorldMap_StartVendor`); returns through label 24.
            label(20), // 143
            callArg(fa("NewFadeOut"), 16), // 144
            whileTrue(fb("FadeOutExists")), // 145
            call(f("sub_80B9810")), // 146
            call(f("WorldMap_StartVendor")), // 147
            yield(), // 148
            call(f("WorldMap_Init")), // 149
            goto(24), // 150
            // Secret shop (`WorldMap_StartSecretShop`); returns through label 24.
            label(21), // 151
            callArg(fa("NewFadeOut"), 16), // 152
            whileTrue(fb("FadeOutExists")), // 153
            call(f("sub_80B9810")), // 154
            call(f("WorldMap_StartSecretShop")), // 155
            yield(), // 156
            call(f("WorldMap_Init")), // 157
            goto(24), // 158
            // Item management (`WorldMap_StartManageItems`); returns through label 24.
            label(22), // 159
            callArg(fa("NewFadeOut"), 16), // 160
            whileTrue(fb("FadeOutExists")), // 161
            call(f("sub_80B9810")), // 162
            call(f("WorldMap_StartManageItems")), // 163
            yield(), // 164
            call(f("WorldMap_Init")), // 165
            goto(24), // 166
            // Fade back in after a shop, then to the node menu (label 18).
            label(24), // 167
            sleep(1), // 168
            call(f("sub_80B982C")), // 169
            call(f("StartFastFadeFromBlack")), // 170
            repeat(f("WaitForFade")), // 171
            goto(18), // 172
            // Fade back in after a screen, then to label 3.
            label(23), // 173
            sleep(1), // 174
            call(f("sub_80B982C")), // 175
            call(f("StartFastFadeFromBlack")), // 176
            repeat(f("WaitForFade")), // 177
            goto(3), // 178
            // The end: waits 16 frames, `ResetWorldMapScreen`, then `PROC_END`.
            label(25), // 179
            sleep(16), // 180
            call(f("ResetWorldMapScreen")), // 181
            call(f("NULL_080B9F08")), // 182
            end(), // 183
    )
}
