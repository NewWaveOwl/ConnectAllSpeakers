# Experimental two-output clock-drift correction

Issue [#7](https://github.com/NewWaveOwl/ConnectAllSpeakers/issues/7) builds on
[manual timing/calibration](TimingCalibration.md). The implementation is an
**explicit experimental opt-in, off by default**. Its parameters are provisional
and validated with synthetic clocks/PCM only. User acoustic measurement evidence
for #6 and sustained hardware results for #7 are still pending. This is not a
claim of calibrated or production-synchronized Bluetooth playback.

## Using it

1. Leave Demo mode off, add exactly two active outputs and set their channels/gain.
2. In **Manual calibration → Configure**, choose the slower speaker as the
   zero-delay reference. Calibrate the faster speaker's initial manual delay
   using the [measurement procedure](TimingCalibration.md#manual-checks--user-operates-the-hardware).
3. Enable **Experimental drift correction · file playback**, choose a WAV/MP3
   and explicitly press Play. Changing the option, reference, delay or room
   stops playback; it never silently changes a running session's timing mode.
4. Expand **Show engine timing**. Both outputs have copied drift diagnostics,
   and transport feedback reports warming, tracking or degraded state.

The reference keeps its original source rate. Only the other output is resampled.
Per-output gain/channel and manual delay remain independent. Calibration cues
and Test speaker deliberately use manual delay only, even when the room's drift
option is enabled. One-output correction is rejected with feedback; disable the
option to use ordinary one-output playback. Rooms and Save setup snapshots retain
their own option/reference for this session only. Closing the app discards them.

## Measurement and controller

`IAudioClock` position is divided by its matching `GetFrequency()`; clock units
are never assumed to be source frames. Each native position carries Windows'
correlated QPC timestamp in 100-ns units. The estimator accepts accurate clocks
no more than 50 ms old and no more than 50 ms apart, then extrapolates them to
the later common QPC. This short extrapolation uses nominal rate; its error is
included in the reported sampling uncertainty. Unreliable samples are not used
to invent an offset or correction.

After source progress has passed initial manual-delay silence, clocks settle
for two seconds. At that point the follower-minus-reference source position
becomes the **initial engine offset**. It includes the existing app delay and
native start/pipeline offset. It is preserved as a baseline, not interpreted
as sound arrival or automatically removed. Subsequent **source skew** is the
change from that baseline, using actual historical resampling ratios. This
separates accumulated engine-clock drift from initial latency calibration.

The estimator retains at most 51 observations (about ten seconds at the existing
200 ms cadence). It requires at least five seconds/20 observations before
tracking, then performs centered linear regression of reference and follower
clock positions against common QPC time. The relative rate is
`(followerSlope / referenceSlope - 1) * 1,000,000` ppm. Positive means faster.
Rate estimates use a five-second exponential smoothing time constant; source
skew uses two seconds. The initial learning period is usually about seven
seconds after source onset, not a promise that every clock will be usable.

The follower's target source-frames/output-frame ratio is:

```text
feedForwardPpm = (1 / (1 + smoothedRelativeRatePpm / 1,000,000) - 1) * 1,000,000
phaseTrimPpm   = -100 * smoothedSourceSkewMillis
targetPpm      = feedForwardPpm + phaseTrimPpm
sourceStep    = 1 + appliedPpm / 1,000,000
```

Rate correction has a deadband of the larger of 10 ppm or the estimated rate
uncertainty. Phase trim has a deadband of `0.25 ms + sampling uncertainty`.
Both avoid chasing neutral-clock jitter. The controller has no unbounded
integrator. Total correction is capped at **±1,000 ppm (±0.1%)**, slewed at at
most **100 ppm/second** (20 ppm per normal 200 ms observation). Positive applied
correction advances the source faster; a faster physical follower normally
needs a negative applied correction. The supported measured relative-clock
range is **±500 ppm**, leaving headroom for gentle phase recovery.

The fixed window, frame history, correction limit and source cursor cannot grow
with session duration. The estimator's residual/uncertainty is an engine estimate;
it cannot establish Bluetooth transport delay, room acoustics or sound quality.

## Audio processing, frames and bounds

The follower uses four-point Catmull–Rom cubic interpolation from the already
decoded immutable mono/stereo source. A fractional source cursor advances by
`sourceStep` per output-format frame. It is never reset, rewound or jumped by
the servo. Interpolation remains within the explicitly assigned source channel;
Mono still averages Left/Right, and app gain/clamping follows the existing rules.
Output samples are finite and bounded to [-1, 1]. At zero correction and integer
positions the original sample is preserved exactly; neutral-clock tests do not
request rate correction. With the option off the existing renderer is unchanged.

The existing fixed manual-delay FIFO follows interpolation, still using the
prepared source-format frame count and the same ceiling rule from #6. Delay
values are never altered to conceal clock drift. At EOF fractional source input
finishes, the complete manual-delay tail is flushed, and both native queues drain.
Drift estimates become Unknown while draining; the last bounded ratio is held.
There is no repeated source block, large sample skip or ever-growing audio queue.

A fixed ring maps output-frame boundaries to the actual fractional source
positions used during rendering. Queue-derived source progress and clock-based
phase comparison use that history, rather than incorrectly treating submitted
output frames as source frames after resampling. The follower retains
`capacityFrames + delayFrames + 2 * sampleRate + 2` boundaries. At the existing
192 kHz / two-second native-capacity limit / 500 ms delay limit this is at most
864,002 doubles (6,912,016 bytes). Normal 100 ms native buffers use less. There
is only one corrected follower. The reference needs no resampling history.
This is additional to #6's fixed delay/render buffers and bounded decoded file.
The estimator uses three 51-entry double arrays. DSP/history storage is allocated
at preparation and reused, not allocated per rendered frame.

Cubic interpolation is a deliberate lightweight experimental tradeoff. It is
not a band-limited, studio-quality sample-rate converter: near-Nyquist content
can alias/change amplitude, sharp transients can overshoot before clamping, and
small rate corrections can alter pitch. ±0.1% is about ±1.73 cents at the limit.
Hardware/quality listening remains user-run. No new dependency, native API,
virtual driver or extra output count is introduced. Windows' existing automatic
format conversion remains after app processing. `IAudioClockAdjustment` was
reviewed; this slice keeps correction and source-history accounting in the app's
controlled DSP path instead of changing native stream-rate semantics.

## Diagnostics and safe policy

| Field/state | Meaning |
| --- | --- |
| Reference / Follower | Exact existing endpoint role; reference correction stays zero |
| Relative clock rate, ppm | Smoothed follower rate relative to reference; positive means faster |
| Correction, ppm | Actual bounded fractional source-rate adjustment; historical when packet is inactive |
| Initial engine offset, ms | Post-warmup source-position baseline; not acoustic latency |
| Source skew since baseline, ms | Smoothed accumulated engine source-progress error; positive means follower ahead |
| Estimated uncertainty, ms | Regression residual RMS + one source frame + conservative short extrapolation allowance; not an acoustic confidence interval |
| WarmingUp | Settling/learning clocks; no new correction until enough history exists |
| Tracking | Estimates are usable within the declared synthetic operating limits |
| Degraded | Estimates Unknown and the last bounded rate briefly held, or demand capped; reason shown |
| Paused / Draining | No clock comparison; buffers/rate retained or finishing normally |
| Inactive / Failed | No live estimates; global playback feedback explains stop/failure |

Unavailable/invalid/S_FALSE clocks, missing history, >50 ms clock age/pair
separation, duplicate timestamps and transient >2 ms interval outliers do not
enter regression. During initial acquisition the grace is five seconds; after
the baseline exists it is two seconds. Persistent loss stops the whole session
with an explicit restart/disable-correction instruction. A slow >1-second timing
batch is also rejected. The #6 presentation watchdog separately blanks stale
runtime fields even if other playback updates keep arriving.

Backward clock position/QPC, changed native frequency, >10 ms interval clock
jumps, native absolute rates outside 0.995–1.005 times nominal, confidently
measured relative rate beyond ±500 ppm, or accumulated source skew over 25 ms
stop immediately with feedback. Demand above ±1,000 ppm is capped briefly and
stops if it persists for five seconds. Engine sampling uncertainty above 2 ms
uses the degraded/grace policy. Native queue counts are validated; refill stays
within fixed capacity. An empty running queue before source/tail exhaustion is
an explicit underrun failure, not a request to grow buffers or skip audio.

Pause retains PCM queues, manual-delay FIFO, fractional cursor/history and last
bounded correction. It clears clock regression/phase baseline and excludes paused
wall time/deadlines. Resume settles/rebaselines again and seeds its rate prior
from the held ratio. It does not insert another delay or return the source to
zero. Stop/restart, new file/format, reference/mode change and failures create
fresh processing state on the next explicit Play. A confirmed disconnect stops
both outputs, preserves room edits/identities, and never resumes on reconnection.
No failure substitutes the Windows default output.

## Offline evidence and remaining acoustic acceptance

The predeclared synthetic target is **absolute source-progress skew ≤1 ms from
120 seconds onward**, with fixed queues/memory and correction inside the stated
limits. Automated virtual-time tests exercise declared ±50/±200/±500 ppm clocks
for twenty simulated minutes, neutral clocks, jitter, transient outliers,
duplicates, bad timestamps/discontinuities, excessive demand, paused time and
rebaselining. Separate five-minute tests run actual PCM interpolation, delay,
native-style queue consumption and clock observation at ±200 ppm, rather than
only testing the estimator equations. Detailed actual results/build counts are
recorded in [Windows playback](WindowsPlayback.md#offline-verification).

Actual offline results on November 2, 2026 (source-progress measurements only):

| Declared fake follower rate | Virtual duration | Maximum absolute residual from 120 s onward |
| --- | --- | --- |
| -500 ppm | 20 minutes | 0.283 ms |
| -200 ppm | 20 minutes | 0.304 ms |
| -50 ppm | 20 minutes | 0.374 ms |
| +50 ppm | 20 minutes | 0.374 ms |
| +200 ppm | 20 minutes | 0.304 ms |
| +500 ppm | 20 minutes | 0.281 ms |

The table rounds measured maxima upward to three decimals. All cases met the
predeclared ≤1 ms target; final-minute correction variation was <0.01 ppm.
The largest applied correction during these runs was below 751 ppm, within the
±1,000 ppm bound and 100 ppm/second slew limit. Independent five-minute actual
PCM/queue simulations at -200/+200 ppm measured maxima below 0.435/0.186 ms,
respectively, after 120 seconds, with queue capacity fixed at 800 frames/8 kHz.
Neutral clocks, including alternating ±0.15 ms jitter, requested zero correction.
These are actual results of deterministic offline tests, not speaker measurements.

These tests do not operate Windows speakers or measure sound. Acoustic
calibration evidence for #6 and sustained acoustic/quality results for #7 remain
tracked in [stability issue #10](https://github.com/NewWaveOwl/ConnectAllSpeakers/issues/10)
after closing the implementation issues. The provisional experimental parameters
must be reviewed against that evidence before calling this production
synchronization or expanding its range.

Shared changes live in `OutputDriftTiming.kt`, room settings, the playback
contract, `MainViewModel`/actions and the existing calibration/transport views.
Desktop processing lives in `DriftController.kt`, `AdaptivePcmRenderer.kt`,
`SourceFrameHistory.kt`, `PlaybackRenderSession.kt` and the Windows controller.
`OutputClockTracker` exposes discontinuities before invalid units can obscure
them. All processing/history/native lifetime remains on the existing playback
worker; shared state contains copied scalars only. The signal-generation tool
adds `--endurance`; no dependency versions were changed.

## User-operated twenty-minute check

No implementation-agent app launch, live discovery, native playback, microphone
capture, Bluetooth operation or audible test is performed. To generate a suitable
file without playing anything:

```powershell
python tools/generate_playback_test_files.py --endurance
```

Use `build/manual-tests/stereo-drift-20min-8000.wav`: 1,200 seconds, 8 kHz stereo
PCM16, 38,400,000 sample-data bytes (38,400,044 including the WAV header). This
fits the current 64 MiB WAV sample-data limit; a 20-minute 48 kHz stereo file
would not. The channels are identical, with a quiet three-second warmup, then
80 ms matching pulses each second. It preserves an identifiable common onset
for external measurement. File generation never opens an audio device.

1. **Declare your acoustic target first**, along with the measurement tool's
   resolution/uncertainty. For example, ≤10 ms change between settled early and
   late acoustic offset over twenty minutes is a possible user target, not a
   measured result or a universal guarantee. Keep this separate from the ≤1 ms
   synthetic engine target above.
2. Record Windows build, app revision, adapter/drivers, both speaker models,
   source/mix formats, enhancements, gain/volume, known codecs, reference and
   manual delays. Keep speaker/recorder positions fixed. Use an external common-
   timebase recording/measurement method; the app does not record a microphone.
   Listening alone is qualitative and cannot supply numeric ms results.
3. Perform #6's initial/residual calibration and keep that report. Run the
   endurance file once with correction off as a control, then with correction
   on. Recheck initial alignment after every new Bluetooth/native session.
4. For each run record initial 3–10-second pulse offsets, settled 2–3-minute
   offsets, and final 19–20-minute offsets (median/range of several pulses).
   Report actual elapsed duration, start/end skew and accumulated change. Keep
   source-progress diagnostics distinct from externally measured acoustic arrival.
5. During the enabled run copy drift phase, initial engine offset, relative rate,
   uncertainty, min/max applied correction, native queues and underruns. Expect
   WarmingUp then Tracking only for reliable clocks. If Degraded/Error appears,
   record the exact reason; no false synchronization success should be reported.
6. Listen for pitch changes, clicks, distortion, warble or clipping, especially
   with your normal higher-rate WAV/MP3 material. Record actual observations,
   not just whether APIs accepted the streams. Compare correction off/on.
7. Check gain/channel isolation, repeated Pause/Resume (no accumulating delay),
   Stop/restart, EOF drain and switching mode/reference. Pause intentionally
   rebaselines; repeated starts may need fresh acoustic calibration.
8. Disconnect either output while playing and paused. Expect both to stop,
   live estimates to become Unknown, the card/settings to remain, and no
   automatic playback after reconnect. Explicit Play opens fresh processing.
   Close/reopen to check shutdown; room/calibration/option remain session-only.

### Sustained-playback report

```text
Date / app revision / Windows build:
Adapter and driver / speakers and drivers / known Bluetooth codecs:
Source format / both endpoint mix formats / enhancements:
Reference / manual delays / channels / app gain and Windows volume:
Initial calibration report and recording geometry:
Measurement method / timebase / resolution / uncertainty:
Declared acoustic target (before the run):
Correction off: duration, initial / settled / final acoustic skew (median/range):
Correction on: duration, initial / settled / final acoustic skew (median/range):
Acoustic start-to-end change / target met? Actual evidence:
Engine initial offset / settled/final source skew / uncertainty:
Relative rate / correction min-max ppm / queue range / underruns:
Warming / degraded / error messages and elapsed time:
Audio quality with correction off/on:
Pause/resume / Stop/restart / EOF / channel-gain isolation:
Disconnect/reconnect / shutdown results:
Limitations / recording reference:
```

## Primary references

- [IAudioClock::GetPosition, stream-relative positions and correlated QPC](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudioclock-getposition)
- [IAudioClock::GetFrequency, compatible units and real clock variations](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudioclock-getfrequency)
- [Native queue padding and output-format frames](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudioclient-getcurrentpadding)
- [Windows automatic conversion and quality flags](https://learn.microsoft.com/en-us/windows/win32/coreaudio/audclnt-streamflags-xxx-constants)
- [Reviewed native rate-adjustment alternative](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nn-audioclient-iaudioclockadjustment)
