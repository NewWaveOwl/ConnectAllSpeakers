# ConnectAllSpeakers main documentation

## Product and current state

Windows-first Kotlin Multiplatform app for independent audio to mixed Bluetooth
speakers, initially left/right stereo with individual volume, delay and EQ.
Android follows as a companion; independent Android multi-speaker output is a
separate capability investigation. Linux is outside the current product scope.

Implemented here: an interactive shared Compose/Material 3 frontend prototype
matching AllConnect's “02 / Main setup workspace” (Figma node `3:3901`). Rooms,
sample speakers, inspector edits, bounded dragging, snap/zoom/reset, setup dialogs
and session-only save snapshots work locally. Windows now supplies real active
playback endpoints through a Core Audio repository with queued device-change
notifications. Room settings stay separate from endpoint metadata. Samples are
isolated behind explicit Demo mode. Native playback, persistent profiles, EQ/delay,
synchronization and surround remain planned. See [FrontendPrototype](FrontendPrototype.md)
and [WindowsOutputDiscovery](WindowsOutputDiscovery.md) for behavior and validation.

Selected playback approach: [MainDesign — direct Windows playback](MainDesign.md).
Future system-audio input: [AllConnect virtual Windows output](WindowsVirtualOutput.md).

## Folder structure and ownership

```text
ConnectAllSpeakers/                 repository / Gradle root
  AGENTS.md                        agent routing and project boundaries
  .agents/skills/*/SKILL.md         Codex discovery entries
  .github/
    copilot-instructions.md        repository conventions
    skills/*/SKILL.md              canonical reusable skill bodies
  .codex/config.toml               GitHub HTTP MCP, environment credentials
  .vscode/mcp.json                 editor GitHub HTTP MCP
  docs/
    MainDocs.md                    architecture and implementation status
    MainDesign.md                  selected direct Windows playback design
    AI_HowToWriteCode.md           production coding standards
    DevelopmentTools.md            skill/MCP setup and reference provenance
  shared/src/
    commonMain/kotlin/com/example/connectallspeakers/
      App.kt                       shared UI assembly
      Platform.kt                  existing portable platform contract
      View/main/MainScreen.kt      stateless responsive prototype screen
      View/main/components/        canvas, inspector, controls and dialogs
      ViewModels/main/             MainViewModel session owner and typed actions
      model/                       immutable sample speakers and room setups
      state/                       immutable session snapshot and modal state
      layout/MainLayout.kt         shared scaffold/inset ownership
      ui/theme/Theme.kt            Figma palette and locally bundled Lato
    commonMain/composeResources/   local logo, grid, halo and licensed fonts
    commonTest/                    shared tests
    jvmMain/                       portable JVM actual implementations
    jvmTest/                       JVM shared tests
    androidMain/                   Android actual implementations
    androidHostTest/               Android host tests
    jsMain/, wasmJsMain/, webTest/ retained template targets, no audio commitment
  desktopApp/src/main/kotlin/com/example/connectallspeakers/
    main.kt                        Windows desktop composition/window lifecycle
    audio/windows/                 Core Audio COM calls and notification bindings
    data/audio/windows/            single-worker discovery repository and cleanup
  androidApp/src/main/             retained Android launcher
  webApp/src/webMain/              retained web launcher; not an active audio target
  gradle/libs.versions.toml        pinned dependencies and Android SDK levels
```

Create these packages **when their behavior is implemented**:

| Location beneath the Kotlin package root | Responsibility |
| --- | --- |
| shared/commonMain: `View/<screen>/components`, `previews` | Screen-local UI and explicit preview fixtures |
| shared/commonMain: `ViewModels/<screen>` | Screen operations, validation, immutable observable UI state |
| shared/commonMain: `model/audio` | Endpoint identities, channel assignments, formats and units |
| shared/commonMain: `data/audio`, `data/profiles` | Platform-neutral repository contracts and genuinely shared implementations |
| shared/commonMain: `state/audio` | UI/session snapshots; one authoritative owner |
| shared/commonMain: `navigation` | Typed destinations and navigation boundary when multiple screens exist |
| shared/commonMain: `ui/global_ui_ellements` | Reusable components, preserving reference spelling |
| shared/commonMain: `simulation` | Explicit simulations/preview fixtures only when useful |
| desktopApp: `data/audio/windows` | Windows endpoint repository and lifecycle/events |
| desktopApp: `audio/wasapi` | Native WASAPI rendering and COM/resource ownership |
| desktopApp: `audio/dsp` | Per-output processing that is native/JVM-specific |
| desktopApp: `data/profiles` | Desktop storage and migrations |

This mirrors RNDTransitMTL's MVVM responsibility split in shared/commonMain.
Windows-only packages belong beneath desktopApp's package root, not commonMain.
Do not move all UI into desktopApp just because Windows ships first; that would
make later Android reuse harder. Do not create empty packages or artificial
ViewModels for a static screen. Kotlin packages and imports must match moved files.

## Data flow and native boundary

```text
View action -> ViewModel -> shared repository contract
                               -> Windows implementation -> OS audio endpoint
Repository state -> immutable UI state -> shared View
```

This flow is implemented for output discovery: `MainViewModel` observes the
platform-neutral `AudioOutputRepository` contract; Windows owns its Core Audio
implementation. App.kt assembles shared rendering and the scoped ViewModel.
main.kt creates the repository, begins nonblocking shutdown, awaits native cleanup
and exits. Common launchers without a Windows repository expose Unsupported;
they do not silently substitute sample devices.
For stereo, the engine opens two independent active outputs and maps source left
and right deliberately. Common code cannot import WASAPI, COM or Java Sound.

## Development and checks

Use Windows, a JDK satisfying the checked-in Gradle daemon criteria (21/Azul),
and an Android SDK matching the checked-in catalog for Android configuration.
The wrapper and catalog are inherited from the user's scaffold; do not silently
downgrade them. Gradle may download its pinned distribution/toolchain/dependencies.
Keep machine-local SDK paths in ignored local.properties. Android minimum is
API 33; minimum, target and compile SDK are separate decisions.

Run from this root in PowerShell:

```powershell
./gradlew.bat :desktopApp:run
./gradlew.bat :desktopApp:compileKotlin :shared:jvmTest :desktopApp:test
./gradlew.bat :androidApp:assembleDebug
git diff --check
```

The Android task is required for Android-affecting/common UI changes when the
SDK is available. Inspect test reports under each module's build/test-results
and build/reports; template arithmetic tests do not prove audio correctness.
Windows installers are limited to MSI for the first product target. Packaging
requires its own build/install/clean-machine verification before release.

## Delivery sequence and acceptance

The frontend prototype precedes the hardware sequence below. It validates local
interaction and presentation only, and does not establish any playback capability.

1. **Hardware feasibility:** enumerate real Windows audio endpoints, open two
   simultaneous render streams and play distinguishable signals. Pairing alone
   is insufficient. Record unsupported combinations rather than silently falling back.
2. **Stereo:** play an in-app audio file; assign left to A and right to B. Verify
   channel isolation, mono-device mapping and endpoint-removal failure handling.
3. **Controls:** add bounded volume, mute, delay and EQ, with deterministic DSP
   tests, saved profiles only when persistence is implemented, and clipping policy.
4. **Timing:** calibrate acoustic delay, measure clock drift, underruns and
   reconnect behavior over a declared test duration. Fixed delay is not drift correction.
5. **Expansion:** validate more outputs, then appropriate 2.1/7.1 source mapping.
   System-wide Windows capture and Android companion behavior are separate slices.
   The planned virtual Windows output and driver are described in
   [WindowsVirtualOutput](WindowsVirtualOutput.md).

One Bluetooth adapter may or may not maintain the intended streams; no universal
speaker count is guaranteed. Additional adapters require separate compatibility
experiments and are optional hardware, not a promise that Windows will use them.
No third-party mixer app is part of the product design. No claim of production
audio readiness is justified until native, hardware and packaging gates pass.

## Related standards

- [Coding guide](AI_HowToWriteCode.md)
- [Skills, MCP authentication and sources](DevelopmentTools.md)
- [Foundation validation and environment workaround](Validation.md)
- [Contribution workflow](../CONTRIBUTING.md)
- Windows render streams: https://learn.microsoft.com/windows/win32/coreaudio/stream-management
- Windows stream routing: https://learn.microsoft.com/windows/win32/coreaudio/stream-routing
- Android routing limits: https://source.android.com/docs/core/audio/combined-audio-routing
