# AllConnect output drivers

This folder owns the native components that expose AllConnect as an operating
system audio output. The Kotlin desktop app discovers and reads an installed
endpoint; launching it does not install a driver.

- [Windows x64 prototype](windows/virtualAudio/README.md): SYSVAD-derived WaveRT
  render driver, INF, pinned dependencies and build script.
- [Audio architecture and acceptance](../docs/WindowsVirtualOutput.md).

From the repository root:

```powershell
& ./output_drivers/windows/virtualAudio/Build.ps1 -CheckTools
& ./output_drivers/windows/virtualAudio/Build.ps1 -PrepareOnly
& ./output_drivers/windows/virtualAudio/Build.ps1 -Configuration Debug
& ./output_drivers/windows/virtualAudio/Package.ps1 -Configuration Debug
```

`-CheckTools` reports all missing prerequisites without preparing or compiling
sources. Build outputs stay in the ignored `build` directory. Signing keys and
certificates must stay outside the source tree. A successful build alone does
not install an endpoint or prove audio forwarding.

## Other ways to receive audio

| Approach | Creates a Windows output? | Tradeoff |
| --- | --- | --- |
| Owned virtual driver (current candidate) | Yes | Requires native build, signing, installation and driver maintenance. |
| Windows process loopback | No | Captures a selected process tree without a custom driver; requires Windows build 20348 or later and a new app capture backend. Original playback remains a separate routing concern. |
| Existing endpoint loopback | No | Captures the endpoint mix, including our own rendered streams if routed there; requires an explicit feedback-prevention design. |
| Third-party virtual cable | Yes, supplied by that vendor | Adds an external driver dependency, installation and licensing requirements; our current ownership checks deliberately reject it as an AllConnect input. |

Process loopback is a useful separate feature if selecting an app inside
AllConnect is sufficient. It does not fulfill the requirement to select
AllConnect Speakers in Windows. None of these alternatives is implemented by
this folder reorganization, and none is an automatic fallback.

References: [Microsoft process-loopback sample](https://learn.microsoft.com/en-us/samples/microsoft/windows-classic-samples/applicationloopbackaudio-sample/),
[WASAPI loopback](https://learn.microsoft.com/en-us/windows/win32/coreaudio/loopback-recording).
