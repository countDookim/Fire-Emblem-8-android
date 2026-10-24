# engine:render

`FrameBuffer` (offscreen ARGB frame, BGR555 conversion identical to mGBA dumps), `Camera` (viewport not
tied to 240×160), `Renderer` contract. Planned (M3): semantic render state (tile layers, sprite batches,
palettes, effects) + software compositor for frame comparison. Design: `docs/decisions/0008-render-model.md`.
