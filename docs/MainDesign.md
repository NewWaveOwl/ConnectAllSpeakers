# Main design — direct Windows playback

## Decision

Start with Approach 1: one Windows PC connects directly to two Bluetooth speakers.
Our app processes and routes the audio using Windows APIs. No external mixer app,
receiver phones or custom hardware is required by this design.

**Status:** selected design; native playback is not implemented or hardware-verified.

The current deliverable is a preceding [frontend prototype](FrontendPrototype.md):
an interactive Windows workspace using explicitly labeled sample speakers and
in-memory state. Its controls do not connect hardware or produce audio. The
playback architecture and hardware milestones below remain future work.

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
Volume, mute, EQ and delay controls follow; saving groups requires later persistence.

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
does not correct clock drift; sustained synchronization needs measurement and
later drift correction. Speaker count and reliability depend on hardware/drivers.

After the first milestone: local-file stereo, individual controls, calibration,
saved groups, then additional outputs. System-audio capture, 2.1/7.1 and Android
playback are separate later features.

For other Windows apps, the later [AllConnect virtual output](WindowsVirtualOutput.md)
would feed audio into our engine through our own native driver. It is separate
from proving simultaneous Bluetooth playback and is not needed for the frontend.

Windows API reference: [stream management](https://learn.microsoft.com/windows/win32/coreaudio/stream-management).
