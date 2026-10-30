# engine:text

- `TextTag`: readable tags (`{NL}`, `{A}`, `{PAUSE n}`, `{PLAYER}`...).
- `MsgHuffman`: decodes Huffman-compressed messages (port of `asm/arm.s: DecodeString`, `MsgHuffmanRomTest`);
  extract/load time only.
- `MessageCodec`: lossless raw message bytes ⇄ tagged text (`text/<lang>/messages.json`).
- Planned: localisation tables, glyph layout matching the original renderer.
