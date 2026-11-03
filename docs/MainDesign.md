# Main design — direct Windows playback

## Decision

Start with Approach 1: one Windows PC connects directly to two Bluetooth speakers.
Our app processes and routes the audio using Windows APIs. No external mixer app,
receiver phones or custom hardware is required by this design.

**Status:** experimental local WAV/MP3 rendering, engine timing diagnostics,
manual per-speaker delay, per-speaker EQ and opt-in clock-drift correction implemented. The user reports working playback on their
Windows PC (November 1, 2026). This update's acoustic calibration measurements,
broader hardware compatibility and release checks remain unverified.

The current deliverable builds on the shared [frontend prototype](FrontendPrototype.md):
an interactive Windows workspace with active Core Audio output discovery and
explicitly saved room profiles and session-only unsaved edits. Samples remain available in explicit Demo mode. Enumeration
does not pair devices or produce audio; see [discovery](WindowsOutputDiscovery.md). The
playback implementation and user-run checks are described in
[WindowsPlayback](WindowsPlayback.md). The milestone checklist below still needs
detailed device/driver and timing records beyond the user's working-playback report.

## How it works

```text
Audio file played inside our app
    -> Decode into audio samples
    -> Create independent speaker streams
        -> Left channel  -> Volume / EQ / Delay -> WASAPI -> Speaker A
        -> Right channel -> Volume / EQ / Delay -> WASAPI -> Speaker B
```

Windows handles Bluetooth transport. Our engine opens a render stream for each
selected output and maps channels explicitly, including mono-speaker behavior.
Per-speaker processing is introduced after simultaneous playback is proven.

The user pairs speakers in Windows, selects two outputs in our app, assigns
Left/Right, checks each with a test sound, then plays a local audio file.
Linear app volume (including 0% mute) and bounded manual delay are implemented.
Choose the slower output as a zero-delay reference and delay the faster one by
0–500 ms; changes stop playback and require a new Play. See
[TimingCalibration](TimingCalibration.md) for rounding, reset behavior, repeatable
cues and measurement reports. [Explicit setup storage](PersistentSetups.md) retains
saved rooms, reference, delays and drift opt-in across reopening, without restoring
an audio file or starting playback. Unsaved edits remain session-only.
[Per-speaker EQ](PerSpeakerEq.md) now provides three adjustable bell bands,
bypass/reset, 100 ms crossfades and conservative boost headroom before manual delay.

## Code ownership

- `shared/commonMain`: Compose screens, ViewModels, immutable state, channel
  models and platform-neutral audio contracts.
- `desktopApp`: Windows endpoint discovery, WASAPI streams, audio processing,
  timing, native resource cleanup and desktop dependency assembly.
- Android later: remote control over Wi-Fi; Windows remains the playback hub.

Use the package responsibilities in [MainDocs](MainDocs.md) and the
[coding guide](AI_HowToWriteCode.md).

## First milestone: prove two independent outputs

1. Enumerate actual Windows audio outputs and select two speakers.
2. Play distinct test signals through both simultaneously.
3. Verify left/right isolation, stop behavior and cleanup after disconnection.

Record Windows version, adapter/driver, speaker models, audio formats and the
observed result. Paired devices or successful API calls alone do not establish
working playback. If simultaneous output fails, report the unsupported setup;
do not silently duplicate audio or switch to the default speaker.

## Timing and later work

Delay buffers can hold back a faster speaker to match a slower one. Fixed delay
does not correct clock drift. [Experimental drift correction](ClockDriftCorrection.md)
now tracks native clocks relative to the same reference and applies bounded,
smoothed cubic resampling to the follower. It preserves the initial engine
offset/manual delay, stops on unreliable clocks and remains off by default.
Its provisional parameters have synthetic evidence only; sustained acoustic
alignment and sound quality still need user measurements before final tuning.
Speaker count and reliability depend on hardware/drivers.

Local-file stereo, individual gain/channel controls, per-speaker EQ and manual delay are now
implemented. Acoustic validation and production hardening remain before any
sustained synchronization claim; EQ listening/quality checks and additional outputs follow.
System-audio capture, 2.1/7.1 and Android
playback are separate later features.

For other Windows apps, the later [AllConnect virtual output](WindowsVirtualOutput.md)
would feed audio into our engine through our own native driver. It is separate
from proving simultaneous Bluetooth playback and is not needed for the frontend.

Windows API reference: [stream management](https://learn.microsoft.com/windows/win32/coreaudio/stream-management).
