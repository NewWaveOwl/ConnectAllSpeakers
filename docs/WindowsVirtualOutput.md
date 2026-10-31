# Planned Windows virtual audio output

## Purpose

Eventually expose **AllConnect Speakers** in Windows' output-device selector.
Users could choose it as their default output or select it for an individual app.
Audio sent there would enter our engine and be distributed to physical speakers.

**Status:** future design, not implemented. The current frontend and first
in-app playback experiment do not require a virtual device.

```text
Browser / game / music app
    -> AllConnect Speakers (virtual Windows output)
    -> Our audio engine
        -> Left  -> Volume / EQ / Delay -> Physical speaker A
        -> Right -> Volume / EQ / Delay -> Physical speaker B
```

## Components and responsibilities

- **Kotlin/Compose app:** speaker selection, channel assignments, settings and
  engine control; reusable presentation stays in shared/commonMain.
- **Windows audio engine:** receives samples, processes independent streams,
  manages timing and renders to the selected physical outputs.
- **Our native Windows virtual audio driver:** exposes the output endpoint and
  provides an implemented audio-transfer path to the engine. This is a separate
  native component, not a device created by a Compose screen or ordinary WASAPI call.

Microsoft's SYSVAD sample is a starting point for driver development, not a
finished routing product. Driver work requires native tooling, installation,
production signing and compatibility testing. Kotlin Multiplatform does not
replace those Windows-specific requirements. No third-party mixer app is part
of this design.

## Delivery order

1. Finish the interactive frontend using labeled sample data.
2. Prove simultaneous independent playback to two real Windows outputs.
3. Add local-file stereo, per-speaker controls and measured synchronization.
4. Implement the virtual output and route other applications' audio into the engine.

As an earlier system-audio experiment, evaluate WASAPI loopback capture of an
existing output. It does **not** create an AllConnect output device. Protected
content and capture restrictions apply; keep captured source audio separate from
our rendered streams to avoid recapture/feedback.

The virtual device solves **audio input to our engine**. It does not create extra
Bluetooth connections, guarantee simultaneous speaker playback, remove latency
or correct clock drift. Those remain separate backend and hardware milestones.
Before shipping, define behavior when the engine stops or a physical speaker
disconnects, and verify driver install/update/uninstall and resource cleanup.

## References

- [Main playback design](MainDesign.md)
- [Microsoft audio driver samples / SYSVAD](https://learn.microsoft.com/en-us/windows-hardware/drivers/audio/sample-audio-drivers)
- [Windows driver signing](https://learn.microsoft.com/en-us/windows-hardware/drivers/install/windows-driver-signing-tutorial)
- [WASAPI loopback capture](https://learn.microsoft.com/windows/win32/coreaudio/loopback-recording)
