# ConnectAllSpeakers

A Windows-first Kotlin Multiplatform project for investigating independent
playback to several Bluetooth speakers, starting with left/right stereo.
Android is a later companion and capability-dependent playback target.

**Current state:** project foundation and UI shell. Bluetooth discovery, WASAPI
playback, per-speaker DSP, synchronization and surround playback are not implemented.
No third-party mixer application is required by the design; the Windows backend
will use operating-system APIs. Hardware support must be proven experimentally.

- [Main documentation and folder structure](docs/MainDocs.md)
- [Production coding guide](docs/AI_HowToWriteCode.md)
- [Skills and MCP setup](docs/DevelopmentTools.md)
- [Contributing](CONTRIBUTING.md)

From this root, use `./gradlew.bat :desktopApp:run` on a configured Windows
development machine. See MainDocs for prerequisites and validation commands.
