# Output timing and manual delay calibration

Issue [#6](https://github.com/NewWaveOwl/ConnectAllSpeakers/issues/6) adds engine
diagnostics and bounded manual delay to the existing one/two-output local-file
renderer. The implementation is experimental. **Acoustic acceptance is pending
the user's measurements.** No automatic measurement, microphone access, clock-drift
correction, persistence or new driver is introduced.

## Controls and reference

Leave Demo mode off, add two active Windows outputs to a room and assign their
channels. Expand **Manual calibration → Configure** and choose the slower audible output as
the **Reference**. The first added output is initially the reference. Reference
identity is the room-card ID, which retains its exact endpoint ID; duplicate
Windows names do not merge outputs. Choosing a reference sets that card's delay
to zero without editing other cards' delay, gain, channel or position.

Select the faster card and use **Manual delay** in the inspector. The supported
range is **0–500 ms**, in whole-millisecond steps. Negative/out-of-range values
are rejected. The reference remains at zero. If the required difference exceeds
500 ms, report it as outside this implementation's range; no value is silently
wrapped, and no output is substituted. Switching reference is an explicit user
choice, never a guess based on the device name or engine clock.

Delay is separate from channel, gain and normalized canvas position. Moving a
card, resetting positions, selecting, renaming or refreshing device metadata
does not edit delay. Each room has independent calibration, including when the
same endpoint is assigned in another room. **Reset** by the inspector slider
resets only that selected output; **Reset delays** resets all delays in the
current room and keeps its reference. Saved setup snapshots include calibration
for this session. Closing the app still discards all setups/calibration (#8).

Changing delay or reference **stops playback**, including paused or pending
starts. Press Play or Play calibration cues again to apply it from the start.
There is no live buffer resizing or automatic playback after an edit.

## Delay buffering and lifecycle

Each endpoint has a fixed, preallocated float32 FIFO for its prepared delay:

```text
delayFrames = ceil(delayMillis * sourceSampleRate / 1000)
effectiveDelayMs = delayFrames * 1000 / sourceSampleRate
```

The request is never shortened; rounding adds strictly less than one source
frame. At 44.1 kHz, 1 ms is 45 frames (about 1.0204 ms); at 48 kHz it is 48 frames.
Windows performs shared-mode rate conversion afterward. Zero delay bypasses the
FIFO byte-for-byte. The 500 ms bound is enforced in shared routes and the engine;
at the maximum supported 192 kHz it is 96,000 frames. A stereo FIFO uses at most
768,000 bytes, plus at most that much preallocated swap scratch. Across two
outputs those delay allocations are bounded to 3,072,000 bytes, excluding the
existing native/render buffers and decoded source. Rendering allocates no new
delay storage per block.

Initial FIFO contents are silence. At EOF exactly `delayFrames` of trailing
silence are fed through it, preserving every source frame; completion waits for
both native queues to drain. Source progress excludes leading delay and follows
the slowest output. Gain/channel mapping is applied before the FIFO, so edits to
those live settings can take the prepared delay, native queue and device latency
to become audible.

Pause stops native consumption while retaining queue, FIFO and cursors. Resume
uses those same buffers; repeated pause/resume does not accumulate another
delay. Stop, completion, errors and shutdown release the entire session. The
next Play primes fresh silence from source frame zero. A confirmed disconnect,
native queue failure or detected underrun stops both streams. Reconnection keeps
the card and delay but requires an explicit new Play. Exact endpoint IDs remain
mandatory; there is no default-device fallback.

## Engine diagnostics and units

Use **Show engine timing** while playing. Text is selectable for reports.
Packets refresh about every 200 ms on the playback worker, including while
paused. These values describe the engine, **not measured sound arrival**:

| Field | Meaning |
| --- | --- |
| Source rate | Requested source frames/second, independent of the device mix rate |
| Prepared delay | Whole-ms request and rounded source-rate frames for this session |
| Submitted | Stream frames submitted, including leading silence and EOF drain |
| Buffer / queued | Native capacity/current padding in source-rate frames; queue ms = frames × 1000 / source rate |
| Source position | `clamp(submitted - queued - delayFrames, 0, sourceFrames)`; an engine progress estimate |
| Engine clock | `IAudioClock` position ticks × 1000 / `GetFrequency()`; clock units need not be frames |
| Clock QPC timestamp | Correlated timestamp already expressed by Windows in 100-ns QPC units; it is not a frame count or wall-clock date |
| Windows stream latency | `GetStreamLatency()` converted from 100-ns units to ms; a native stream latency bound, not complete Bluetooth/acoustic delay |
| Underruns | Count of detected empty running queues before all stream frames were submitted; the first detection stops the session |

Streams start sequentially. Their clock readings are also sequential and carry
their own correlated QPC timestamps; directly subtracting displayed stream
positions is not an acoustic offset measurement or proof of synchronized onset.
The clock and queue progress can differ because they describe different pipeline
stages. Do not use either as an automatic calibration value.

Clock reads that fail, return S_FALSE, have invalid signed values/zero frequency,
future or >1-second-old correlated timestamps, backward position/QPC, changed
frequency, implausible position (> submitted duration + 1 second), or no position
advance for >1 second are shown as **Unknown**, with a reason. Paused clocks are
Unknown for comparison; pause does not reset monotonic history. A new session
creates new clock history. Invalid queue counts fail the session rather than
invent progress. Native clock-read failure alone does not imply disconnection.

The presentation watchdog makes all runtime position/queue/latency fields
Unknown if no new timing packet arrives for one second, even if file-position
updates continue. A measurement batch that itself takes over one second also
publishes Unknown queue/position data rather than reviving stale samples.
Fresh packets recover the display. Stop, failure and completion
also invalidate those fields; the last prepared configuration, submitted count
and underrun count remain explicitly labeled historical. No native pointers or
sample buffers enter shared state. The optional IAudioClock reference is released
on its acquiring worker before the render service/client and COM lifetime.

## Reproducible calibration cues

**Play calibration cues** explicitly replaces any active playback with a generated
15-second, 48 kHz stereo float signal. It does not replace the loaded file:

- 0–3 seconds: a 440 Hz warmup at 3% peak (about -30.5 dBFS).
- At 3, 4, …, 14 seconds: twelve 80 ms, 1 kHz pulses at 10% peak (-20 dBFS),
  with 5 ms ramps and silence between pulses.
- Both source channels are identical. Existing route channel, gain and delay
  still apply; the final delay/queue drains before completion.

Start with comfortable Windows volume and low app gain, then use comparable gain
on both outputs. The warmup gives slow-starting Bluetooth paths time to settle;
measure the later pulses rather than the initial startup. Stop ends the cues
early. Normal Play afterward starts the previously loaded WAV/MP3 from zero.
For controlled timing, use these cues or PCM WAV; MP3 retains encoder padding.

## Manual checks — user operates the hardware

The implementation agent did not launch the app, enumerate outputs for validation,
open native playback sessions, connect/disconnect hardware or produce sound.
Offline PCM/fake-clock results cannot establish acoustic synchronization.

1. **Declare a target before measuring.** For example, choose a residual absolute
   offset of ≤10 ms for settled pulses; this is an example target, not a result or
   a universal product guarantee. Record the actual target and method resolution.
2. Record the Windows build, app revision, adapter/model/driver, speaker models,
   endpoint names/IDs if comfortable sharing them, channel/gain, source format,
   Windows endpoint mix formats, enhancements/spatial-audio settings, distance
   from each speaker to the recording position, and Bluetooth codec if known.
   Mark information Windows does not expose as Unknown.
3. Add exactly two real outputs, select **Reset delays**, assign Left/Right and
   run the cues. Record which speaker arrives later. Use an external recording
   or measurement tool to compare pulse onsets against a common timebase. The
   app does not record a microphone. Keep speakers/recorder stationary and account
   for unequal acoustic travel distance if measuring transport rather than room
   arrival. Listening alone can guide adjustment but must be reported as
   qualitative, not as a numeric millisecond measurement.
4. For at least five settled pulses, measure the initial signed difference
   `arrival B - arrival A` in ms and report median/range. Choose the later output
   as the zero-delay reference. Delay the earlier output by the measured
   difference rounded to a supported whole-ms setting. If the difference is
   negative, switch reference; do not enter a negative delay.
5. Replay and record the residual signed difference and absolute error across
   the same pulses, with method/measurement uncertainty. Adjust and repeat until
   the declared target is met or record the remaining error/range limitation.
   Keep initial and residual evidence together. Copy engine diagnostics, including
   Unknown reasons and underruns; these supplement the acoustic record.
6. Repeat several Stop/Play cycles. Expect one initial delay each time and no
   automatic audio when editing delay. Pause/Resume multiple times during a
   longer WAV and check that the offset does not gain another configured delay.
   Reset the selected delay and verify the original offset returns; Reset
   positions must preserve delay. Switch rooms and verify independent edits.
   Use Tab to focus the delay slider and check arrow keys change it in 1 ms
   steps, Home selects zero and End selects 500 ms. Expect a visible focus border,
   labeled units, and the reference's slider to be disabled. These live desktop
   keyboard/focus checks remain user-run.
7. Disconnect one assigned output while playing and while paused. Expect the
   whole session to stop, its card to become Unavailable, live timings to become
   Unknown, and the endpoint/reference/delay edits to remain. Reconnect and expect
   no automatic playback. Press Play explicitly and remeasure: Bluetooth startup
   latency may change between sessions and require recalibration.
8. Play the existing 180-second WAV from the [playback checklist](WindowsPlayback.md#manual-checks--user-operates-the-hardware).
   Compare early/late offsets and record drift instead of attributing it to fixed
   calibration. Fixed delay cannot correct independent-clock drift (#7).
9. Stop, close and reopen the app. Expect no remaining playback; calibration and
   rooms are currently session-only and must be entered again. Native cleanup on
   actual hardware and installer behavior still require user verification.

### Measurement report template

Copy this into a report or issue comment after testing; do not replace Unknown
fields with engine-derived acoustic estimates.

```text
Date / app revision:
Windows edition/build:
Adapter / driver:
Speaker A / driver / endpoint identity:
Speaker B / driver / endpoint identity:
Bluetooth codecs (if known):
Source format / endpoint mix formats / enhancements:
Channels / app gains / Windows volume:
Speaker-to-recorder distances / recording geometry:
Measurement method, sample rate, resolution and uncertainty:
Declared residual tolerance:
Initial B-minus-A offset: pulses, median, range (ms):
Reference / A delay / B delay (requested ms and prepared frames):
Residual B-minus-A offset: pulses, median, range (ms):
Target met? Actual evidence / recording reference:
Repeated-start results:
Pause/resume / selected reset / room isolation results:
Disconnect/reconnect / shutdown results:
Early/late offset and elapsed time (drift observation):
Engine diagnostics / Unknown reasons / underruns:
Remaining limitations or unsupported cases:
```

## Implementation and validation

Shared models/contract: `OutputTiming.kt`, `Playback.kt`, `RoomSetup.kt`,
`AudioPlaybackController.kt`; shared owner/actions and stateless calibration/
inspector controls remain in the existing common packages. Desktop owns
`PcmDelayLine`, `CalibrationSignal`, `OutputClockTracker`, `WasapiClock`, rendering
and controller lifecycle. No dependency versions changed.

Offline tests cover exact bypass, delay rounding/bounds, multiple sample rates
(8/44.1/48/192 kHz), mono/stereo channels and gain, delay wrap/EOF flush,
pause/resume/restart, queue validation/underrun cleanup, room/reference isolation,
metadata reconciliation, stale packets, invalid/backward/nonadvancing clocks,
native clock ABI/64-bit units/release, cancellation, calibration reference
validation and loaded-file retention. Automated evidence and packaging are
recorded in [Windows playback](WindowsPlayback.md#offline-verification).

No initial/residual acoustic measurement has been recorded for this implementation.
Issue #6's acoustic acceptance remains open until the user supplies actual
measurements against a declared target. Long-term alignment is separate work.

## Primary Windows references

- [IAudioClock::GetPosition and correlated QPC units](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudioclock-getposition)
- [IAudioClock::GetFrequency and position conversion](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudioclock-getfrequency)
- [IAudioClock acquisition and reference lifetime](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nn-audioclient-iaudioclock)
- [IAudioClient::GetStreamLatency](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudioclient-getstreamlatency)
- [QueryPerformanceCounter](https://learn.microsoft.com/en-us/windows/win32/api/profileapi/nf-profileapi-queryperformancecounter)
