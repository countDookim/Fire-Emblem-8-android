# engine:script

`gbafe.script`: `FeScriptParser` + `ScriptAssembler` turn `.fe8s` text into the original FE8 event words with
32-bit virtual addresses (ADR 0001 amendment); `ScriptImage` (words + entry table), `ScriptConstants` (name ↔
value, mods add entries), `ScriptVm` contract. The command set is injected (no FE8 names here); the FE8
interpreter is `fe8.game.event`.
