# engine:audio

m4a (MP2K) sound driver, ported from the decomp's `src/m4a.c` / `src/m4a_1.s` and verified against per-frame mGBA
RAM traces (track/channel state and the whole PCM buffer byte-exact: `M4aNoteTest`, `M4aSequencerTest`).
- `M4aSound` (`m4aSoundInit`, `gMPlayTable`), `M4aSequencer` (`MPlayMain`, song starts), `M4aCommands` (`ply_*`,
  fades, stop), `M4aMixer` (`SoundMain` DirectSound mixing, reverb, envelopes), `M4aDriver` (one VBlank of sound).
- `PcmStream`: lock-free ring from the game thread to the audio thread, resampled to the device rate.
- `AudioSource` / `AudioSink`: `:app` supplies the low-latency `AudioTrack` sink.
- `M4aCgb` (`CgbSound`, `CgbModVol`) steps CGB channels into `M4aPsg` registers. `PsgSynth` plays them (approximate level; NR50/SOUNDCNT_H todo).
