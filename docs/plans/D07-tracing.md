# D-07 Android audio trace

Use a debug APK containing these diagnostics on the affected device. Import the ROM, start the
castle sequence, and capture both hitches with a system trace. For example, on a host with adb:

```sh
adb shell perfetto -o /data/misc/perfetto-traces/fe8-d07.perfetto-trace -t 60s -b 64mb \
  sched freq idle am wm gfx view audio dalvik -a io.github.countdookim.fe8
adb pull /data/misc/perfetto-traces/fe8-d07.perfetto-trace ~/.cache/fe8-tmp/fe8-d07.perfetto-trace
```

Check `adb shell atrace --list_categories` and omit categories unsupported by the device. See
the [Perfetto CLI reference](https://perfetto.dev/docs/reference/perfetto-cli) and
[Android Perfetto guide](https://developer.android.com/tools/perfetto).

Open the trace in Perfetto UI. Debug builds emit `FE8 draw frame` and nested `FE8 game loop`
sections on the GL thread. Nested `FE8 tick` and `FE8 present` sections separate simulation
work from presentation; the outer draw section includes GPU drawing. The trace also has
`FE8 audio render` and `FE8 audio write` sections on the audio thread. On Android 10/API 29 or
later, counters show game ticks, ticks per callback, draw callbacks, queued PCM input frames,
cumulative rendered output frames, startup prefill silence frames, repeated PCM underrun frames,
and native AudioTrack underruns. Counters reset when their stream or track is recreated.

Compare the hitch time with the threads and counters. A long game-loop interval followed by falling
PCM queue and rising repeated frames suggests producer starvation; rising AudioTrack underruns with
no PCM starvation suggests a native output or audio-thread stall. Inspect scheduler tracks and write
intervals too: correlation alone does not establish the cause. Prefill silence at startup is expected
and is counted separately. API 26–28 provide sections but not these counters. Recording a trace
adds overhead; compare captured timing with normal playback. No device trace has yet been captured,
and D-07 remains open.
