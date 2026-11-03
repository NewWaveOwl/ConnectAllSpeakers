# Per-speaker EQ

Issue [#9](https://github.com/NewWaveOwl/ConnectAllSpeakers/issues/9) adds three
independent bell bands to each room card. Shared code owns immutable parameters,
validation and the stateless dialog; desktop owns the PCM filter state. This is
experimental processing, not an acoustic correction or synchronization claim.
No new dependency, audio service or hardware access is introduced.

## Controls and limits

Select a card, then choose **EQ · bypassed/enabled** in the inspector. The dialog
edits that card immediately and scrolls to all three bands. **Enable EQ** toggles
bypass while retaining band edits. **Reset EQ** restores bypassed, neutral defaults
(120 Hz, 1000 Hz, 3000 Hz; 0 dB and Q=1). Closing the dialog retains session edits;
**Save setup** makes them durable. Other cards and rooms are independent, even
when they reference the same endpoint. Demo samples stay silent.

| Setting | Supported range |
| --- | --- |
| Filter type/count | Three serial peaking-bell biquads; no shelves or automatic EQ |
| Gain | −12 to +12 dB per band; UI steps 0.5 dB |
| Center frequency | 40–16000 Hz globally; enabled bands must also be ≤0.45 × actual processing rate |
| Q | 0.3–2.0, dimensionless; larger means narrower; UI steps 0.1 |
| Processing rate | Actual decoded/generated source rate, 8000–192000 Hz |
| Defaults/reset | EQ bypassed; all gains 0 dB; default centers above |

Frequency gestures are logarithmic with whole-Hz values. Controls retain native
range/keyboard semantics and a visible focus outline. NaN, infinity, unsupported
centers, invalid band indices and out-of-range gain/Q are rejected with readable
feedback. No value is silently clamped into a different processing configuration.

The rate is the source rate supplied to the stream, **not** the endpoint's Windows
mix-format rate. For example, an 8000 Hz file permits centers up to 3600 Hz.
Bypassed high-frequency edits can be stored for a future source; enabling them
must validate again. An incompatible saved setup does not open streams: lower the
frequency or bypass/reset EQ before Play. A rejected live update leaves the last
safe routes/filters active. Changing the source rebuilds fresh rate-specific state.

## Processing, headroom and guard

```text
Decoded mono/stereo PCM
  -> deliberate Left / Right / Mono routing and optional cubic drift resampling
  -> existing linear app gain and normalized-input guard
  -> EQ headroom attenuation -> three bell filters -> final finite/-1..1 guard
  -> fixed manual-delay FIFO -> explicitly selected WASAPI output
```

Each output has its own filter banks/history. The assigned channel is duplicated
to its one/two physical channels only; EQ never changes channel assignment or
Windows master volume. Gain/volume, delay, canvas position and EQ remain separate.
The same EQ applies to local-file playback, Test speaker and calibration cues.
Cues/tests still exclude drift correction. Bypass EQ for initial delay comparison,
then recheck after enabling it because filters change phase and pulse shape.

The bell coefficients follow the [W3C Audio EQ Cookbook](https://www.w3.org/TR/audio-eq-cookbook/).
The implementation normalizes each denominator, checks finite coefficients and
second-order stability, and uses double-precision transposed-direct-form-II history.
Zero-gain sections are exact identity. Stable neutral/bypassed EQ is **bit-exact**
for finite normalized float32 input, including signed zero. Live transitions are
intentional blends and are not an identity comparison until settled.

For boosts, automatic pre-attenuation is **minus the sum of positive band gains**.
For example, +6 dB in one band applies −6 dB headroom to the whole signal; +12 dB
in all three applies −36 dB. Cuts alone reserve 0 dB. This conservative policy
does not add loudness; it raises the chosen range relative to other frequencies.
Bypassing restores the original app-gain level, which may sound louder overall.
The configured headroom is shown in the dialog. During a crossfade each complete
bank uses its own attenuation, so changing gain never temporarily bypasses it.

That frequency-domain bound is not a proof that every possible transient stays
inside full scale. The final guard clamps finite output to −1..1, substitutes
silence for non-finite input and counts affected frames before physical-channel
duplication. Counts appear in the EQ dialog and transport feedback. Reduce app
volume or reduce the EQ change if the guard is used. This is a safety clamp,
**not** a look-ahead limiter; limiting can distort transients. The existing
upstream source/cubic guard remains separate from this EQ counter.

## Smoothing and lifetime

There are two preallocated complete banks per output and fixed coefficient slots.
An edit validates/prepares coefficients at a block boundary, then blends the old
and new stable chains with a smoothstep envelope over approximately **100 ms**.
The target bank starts with zero history. Coefficients are never interpolated.
Rapid edits replace a single pending target and let the in-flight blend finish;
after the final request at most two transitions remain. Native buffering and
device transport add their usual control latency. There is no unbounded queue.

Pause freezes queues, source cursor, filter history and the transition position.
An edit while paused applies smoothly when source rendering resumes. Stop,
restart, source changes and disconnect cleanup discard all filter history.
EQ edits alone do not reopen streams. A failed coefficient update retains both
the active and pending safe banks. Unexpected non-finite filter output bypasses
that output and reports it; a valid update/restart reinitializes safe history.

A session that used non-neutral EQ processes up to **500 ms of zero-input decay**
at EOF, fading the last approximately 10 ms to silence, then drains the manual
delay and native queues. Neutral/bypassed-only playback adds no EQ tail. The
source-position label can reach the file duration while this bounded tail drains.
Tail-time EQ edits are stored but apply on the next Play. This drain adds no
steady-state buffering latency. Render work/storage remain bounded; per-frame
processing performs no allocation, locks, file I/O, logging or UI work.

## Saved settings and migration

[Profile storage](PersistentSetups.md) now writes **schema 2** with required typed
`eqEnabled` and three frequency/gain/Q field groups on every saved card. These
values participate in the selected-room dirty state and explicit-save snapshot.
Only parameters persist: histories, transition/pending state, applied coefficients,
guard counters, source files and autoplay intent never do.

Schema 1 still loads. Its cards get **neutral, bypassed** EQ while keeping all
previous room/card settings and opaque extension fields. Loading does not rewrite
the file; the next successful explicit save atomically upgrades it to schema 2.
A failed upgrade leaves the old bytes readable. Former optional `ext.eq.*`
examples remain opaque extensions and are not silently activated. Invalid/missing
typed EQ or an unsupported schema rejects the entire file with protected-file
recovery feedback. Older app versions cannot read schema 2; retain a backup when
testing a downgrade. No database or extra serializer was added.

## Offline verification and user checks

Generated impulse/sine tests compare measured response with the independently
evaluated bilinear-mapped analog bell transfer function. The declared test
tolerance is **0.03 dB** at DC, center and off-center probes. Coverage includes
40 Hz and the maximum allowed centers, ±12 dB, Q=0.3/2.0, and source rates 8000,
11025, 16000, 22050, 44100, 48000, 96000 and 192000 Hz. Overlapping three-band
boosts, exact identity, guard behavior, invalid updates, rapid changes and bounded
EOF decay are also checked. Fake endpoint captures verify source-channel/gain,
delay, physical duplication, pause/restart and fixed/fractional drift paths.
Portable tests cover selected-card/room isolation, reset, saved EQ, literal
schema-one migration and invalid EQ data. Filesystem tests cover failed upgrades.

Listening checks are user-operated; the agent does not start audio:

1. Start with low app/Windows volume. Choose a familiar file and two outputs.
   Open EQ for one card, enable it, set Band 2 around 1000 Hz to −6 dB.
   Expect only that output to change. The other output, channels, volume values
   and delay values must stay unchanged.
2. Bypass and enable repeatedly while playing. Expect a short smooth change,
   not instant switching or stream restarts. Compare at sensible levels: boosts
   reserve headroom and bypass can restore a higher overall level.
3. Move frequency/gain/Q rapidly, then stop moving. Expect the last setting to
   settle within two blends plus existing output latency, with no error/crackle
   or stuck transition. Watch the safety-clamp count; record any audible artifact.
4. Pause, edit, resume; stop/restart; choose a different-rate file. Expect retained
   parameters but fresh histories after restart/source change. With an 8000 Hz
   source, enabling a center above 3600 Hz must be rejected; bypass/reset recovers.
5. Reset EQ. Expect bypass, 0 dB, Q=1 and 120/1000/3000 Hz, with volume, channels,
   reference and delay untouched. Test speaker/cues use EQ too; initial calibration
   should be done bypassed and rechecked after EQ changes.
6. Save each edited room and reopen. Expect its EQ and other saved settings to
   return without any audio/file/stream starting. Unsaved EQ edits should revert
   to the last saved values. Opening an original schema-one file should be neutral.
7. Listen through EOF. Expect the short bounded filter/manual-delay decay to finish
   and all outputs to stop. Try disconnect/reconnect and shutdown separately;
   no endpoint replacement or automatic resume is expected.

Record source rate/file, speaker/driver combination, parameter values, guard
counts and observed artifacts. Native listening/installer acceptance and acoustic
timing remain separate from successful offline processing tests.

### Verification record — November 2, 2026

- Passed `:desktopApp:compileKotlin :shared:jvmTest :desktopApp:test
  :androidApp:assembleDebug :desktopApp:createDistributable` on the final source.
- **59 shared JVM tests and 116 desktop tests passed**: 175 total, zero failures,
  errors or skips. This adds 20 EQ state, DSP, route/lifetime and migration tests
  to the previous 155-test baseline. The temporary preview helper is excluded.
- Frequency-response coverage passed 480 comparisons across 96 rate/center/gain/Q
  cases at the declared 0.03 dB tolerance. The 8000 Hz rapid-DC-edit stress case
  stayed below its 0.01-full-scale adjacent-sample-change bound; this is not a
  universal listening-artifact guarantee.
- Silent offscreen fixtures covered normal/error dialog content, top and lower
  scroll positions at 1440×900 and 560×480. Titles, labels, all three bands,
  feedback, Reset and Close were readable/reachable. A temporary preview-helper
  collection conversion was corrected before successful rendering; no helper
  code remains in delivered test sources.
- Existing Android SDK XML compatibility and discovery-test unnecessary `!!`
  warnings remain. No dependency versions changed. Whitespace, shared/native
  import boundaries and local documentation links passed.
- No app assembly, endpoint enumeration, audio playback, user profile access or
  hardware/listening checks ran. Native keyboard/focus, real reopen, acoustic
  quality, disconnect/reconnect and installer acceptance remain user-operated.
