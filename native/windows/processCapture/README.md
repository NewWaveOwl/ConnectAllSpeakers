# User-mode process audio activation

This small x64 DLL activates Windows process-tree loopback and returns an owned
`IAudioClient` reference to the Kotlin audio worker. It neither records nor starts
audio. The worker initializes, reads and releases the client through the existing
WASAPI capture path. The DLL uses documented Windows APIs, WRL and the Windows
SDK; it is not a virtual audio driver and requires no WDK, service or elevation.

Build automatically through `:desktopApp:processResources`, or from the repo root:

```powershell
& ./native/windows/processCapture/Build.ps1 -ResourceDirectory ./desktopApp/build/generated/processCaptureResources
```

Development requires VS2022 C++ x64 build tools and SDK build 26100. Output and
intermediates stay in ignored `build/`. The helper is bundled as
`win32-x86-64/AllConnectProcessCapture.dll` for JNA; release installers must still
undergo clean-machine verification and normal application-signing review.

See [behavior and acceptance](../../../docs/WindowsAppCapture.md).
