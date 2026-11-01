# ConnectAllSpeakers

A Windows-first Kotlin Multiplatform project for investigating independent
playback to several Bluetooth speakers, starting with left/right stereo.
Android is a later companion and capability-dependent playback target.

**Current state:** interactive Compose workspace with real Windows playback-output
discovery through Core Audio. Available endpoints can be assigned to room cards;
Windows changes update their metadata and availability without replacing missing
outputs. Sample speakers are isolated behind an explicit Demo mode. Room edits and
save snapshots remain session-only. App volume uses the aligned New Slder_ v2a bar.
Playback, Bluetooth pairing, DSP, synchronization and surround are not implemented.
JNA is bundled for Windows interop; no external mixer or virtual driver is installed.

- [Main documentation and folder structure](docs/MainDocs.md)
- [Frontend prototype behavior and verification](docs/FrontendPrototype.md)
- [Windows output discovery and current validation](docs/WindowsOutputDiscovery.md)
- [Production coding guide](docs/AI_HowToWriteCode.md)
- [Skills and MCP setup](docs/DevelopmentTools.md)
- [Contributing](CONTRIBUTING.md)

From this root, use `./gradlew.bat :desktopApp:run` on a configured Windows
development machine. See MainDocs for prerequisites and validation commands.
