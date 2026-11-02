# ConnectAllSpeakers

ConnectAllSpeakers is a Windows-first desktop app, presented as **AllConnect**, for
arranging speakers in a room and playing a local audio file through one or two
selected Windows outputs. It uses Kotlin Multiplatform, Compose Multiplatform
and Material 3, with Windows Core Audio discovery and WASAPI playback.

**Current state — November 1, 2026:** experimental WAV and MP3 playback is
implemented. Playback has been reported working on a Windows PC. The outputs
have independent clocks; calibrated timing, drift correction and synchronized
stereo are still future work.

## What works now

- Discover active Windows playback outputs, including Bluetooth, USB, built-in,
  headphones and HDMI/DisplayPort endpoints exposed by Windows. Microphones are
  excluded. Pair/connect Bluetooth devices in Windows first.
- Assign outputs to room cards using their Windows endpoint IDs. Duplicate names
  are allowed; another output never silently replaces a missing endpoint.
- Receive device-change notifications and refresh manually. Disconnected cards
  stay in their rooms as **Unavailable**, retaining their custom settings.
- Play local WAV/MP3 files through one or two room outputs, with Play/Resume,
  Pause, Stop, source position and explicit error feedback. Choosing a file is silent.
- Assign Left, Right or Mono and adjust each output's app volume. Left/Right copy
  that source channel to the output; Mono mixes both. App gain does not change
  Windows master volume.
- Test the selected real output with a one-second tone. **Play** uses the selected
  file; **Test speaker** uses the built-in tone.
- Create, rename and delete setups; rename speakers; drag cards; use snap-to-grid,
  zoom and Reset positions. Moving cards changes the layout, not audio timing.
- Keep independent room edits and Save setup snapshots **for this session only**.
  Closing the app discards them. Explicit Demo mode offers silent sample speakers.

An assigned output disconnecting or a playback failure stops the whole session. Reconnecting an output
does not resume audio automatically; press Play again. Demo speakers never act as
discovered hardware or as a fallback when discovery fails.

## Supported audio files

| Format | Supported input | Limits |
| --- | --- | --- |
| WAV | Mono/stereo RIFF/WAVE, PCM16/24/32 or IEEE float32, 8–192 kHz | Up to 64 MiB of sample data |
| MP3 | Mono/stereo MPEG-1/2/2.5 Layer III, CBR/VBR, 8–48 kHz | Up to 64 MiB compressed and 64 MiB decoded PCM16-equivalent data |

MP3 encoder delay and padding are retained. Use WAV for precise channel/timing
checks. Compressed WAV, AAC, FLAC, multichannel sources and free-format MP3
bitrates are unsupported. Playback outputs must use a mono/stereo Windows mix
format. See [Windows playback](docs/WindowsPlayback.md) for detailed format limits,
dependency notices and the manual test checklist.

## Run on Windows

Use Windows 10/11 and a configured development environment: JDK 21 matching the
checked-in Azul daemon criteria, plus an Android SDK matching the Gradle catalog
(currently compile SDK 37). Keep machine-specific SDK paths in ignored
`local.properties`; see [development prerequisites](docs/MainDocs.md#development-and-checks).

From the repository root in PowerShell:

```powershell
.\gradlew.bat :desktopApp:run
```

1. Leave Demo mode off and add one or two available outputs to a setup.
2. Assign Left/Right for two outputs, or Mono to hear both source channels on one
   output. Start at a comfortable Windows volume and a low app volume.
3. Select **Choose audio**, choose a WAV or MP3, then press **Play**.

The app bundles its Windows interop and MP3 decoder libraries. No external mixer,
encoder or virtual audio driver is required at runtime. Python is only optional
for generating the [manual test signals](docs/WindowsPlayback.md#manual-checks--user-operates-the-hardware).

## Validation

Latest recorded checks on November 1, 2026: desktop compilation, Android debug
build and Windows app-image packaging passed; **26 shared JVM tests and 52 desktop
tests passed**, with zero failures, errors or skips. These automated checks use
offline fixtures and fake playback outputs. Manual playback was reported
successful; detailed device/driver and timing measurements are not recorded.

```powershell
.\gradlew.bat :desktopApp:compileKotlin :shared:jvmTest :desktopApp:test :androidApp:assembleDebug
.\gradlew.bat :desktopApp:createDistributable
```

If Java encounters the existing long temporary-path loopback issue, use the
[documented workaround](docs/Validation.md#reproducing-the-temporary-path-workaround).
MSI installation, clean-machine distribution and broader hardware compatibility
remain separate verification work.

## Remaining work

- Acoustic delay calibration, clock-drift correction and synchronized playback.
- EQ, delay controls, persistent profiles and a settings screen.
- More than two playback outputs, surround and additional source formats.
- System-wide Windows audio input and the planned [virtual output](docs/WindowsVirtualOutput.md).
- Android companion/playback capability work. The Android launcher builds, but
  native discovery/playback is Windows-only. The retained web scaffold has no
  audio-support commitment; Linux is outside the current scope.

Bluetooth pairing is handled by Windows. Simultaneous output reliability depends
on the devices, adapter and drivers; this milestone makes no universal speaker
count or production synchronization guarantee.

## Code and documentation

Reusable screens, state, ViewModels and audio contracts live in `shared`.
`desktopApp` owns Windows APIs, file decoding, native streams and application
lifetime. Platform launchers assemble these pieces without putting Windows APIs
in common code.

- [Main documentation and folder structure](docs/MainDocs.md)
- [Frontend prototype behavior and verification](docs/FrontendPrototype.md)
- [Windows output discovery](docs/WindowsOutputDiscovery.md)
- [WAV/MP3 playback, format limits and manual checks](docs/WindowsPlayback.md)
- [Playback design and timing milestones](docs/MainDesign.md)
- [Production coding guide](docs/AI_HowToWriteCode.md)
- [Skills and MCP setup](docs/DevelopmentTools.md)
- [Contributing](CONTRIBUTING.md)
