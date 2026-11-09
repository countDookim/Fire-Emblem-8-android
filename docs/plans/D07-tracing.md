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
adds overhead; compare captured timing with normal playback.

On WSL with a Windows-connected phone, `adb.exe` may need the Windows ADB server: a Linux ADB
server on port 5037 can mask the USB device. Stop that server before starting the Windows one.

## First device capture

On 2026-11-09 a Samsung A54 captured normal gameplay with the diagnostic build based on 0.1.488.
The private local trace is `~/.cache/fe8-tmp/fe8-d07-20261109.perfetto-trace`. At 42.615 and
56.333 seconds from trace start, game ticks took 91.95 and 88.84 ms respectively, almost entirely
running on the CPU. The PCM queue emptied and repeated-frame counts rose, while the AudioTrack
underrun counter remained zero throughout. The owner heard both castle hitches during the capture;
their exact alignment with these spikes was not independently timestamped. A later ART sampling
attempt froze and produced an empty profile, so it provides no normal-gameplay attribution.

## Frontier retest

The owner replayed both castle events on 2026-11-09 using debug build 0.1.488 (`0a73f11`)
with the bounded movement frontier and reported no audible stutter. The normal retest trace is
`~/.cache/fe8-tmp/fe8-d07-frontier-20261109.perfetto-trace`; it reached 2,745 game ticks
(the baseline reached 2,008). Repeated PCM underrun frames fell from 4,495 to zero, and native
AudioTrack underruns remained zero in both captures. The longest recorded game tick fell from
91.95 ms to 43.59 ms; the candidate still has long ticks. The owner heard both original hitches
in the baseline capture, but their exact timestamps were not marked. Manual timing can shift the
scenes, so tick numbers alone do not identify matching events, and the trace does not isolate
how much tick time was spent in the movement flood.
