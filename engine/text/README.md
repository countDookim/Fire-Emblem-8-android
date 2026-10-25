# engine:text

Readable text tags (`TextTag`: `{NL}`, `{A}`, `{PAUSE n}`, `{PLAYER}`, ...). Planned: localisation tables,
Unicode font/glyph layout matching the original's text renderer in vanilla mode.

`MsgHuffman` decodes the original's Huffman-compressed message strings (port of `asm/arm.s: DecodeString`,
verified by `MsgHuffmanRomTest` on known names). Extract/load time only; output is raw message bytes with
control codes, converted to `TextTag`s in M2-09.
