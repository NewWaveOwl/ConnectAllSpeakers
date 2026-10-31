# ConnectAllSpeakers repository instructions

Read `AGENTS.md`, `docs/MainDocs.md` and `docs/AI_HowToWriteCode.md`.
Use the relevant canonical skill under `.github/skills/`.
Use Kotlin, Compose Multiplatform and Material 3 with the versions already pinned
in `gradle/libs.versions.toml`. Windows ships first; Android support must be
capability-based. Preserve existing targets unless their removal is requested.

Follow the RNDTransitMTL responsibility split: `View/<screen>`,
`ViewModels/<screen>`, `model/<domain>`, `data/<domain>`, `state/<domain>`,
`layout`, `navigation`, `ui/theme`, and `ui/global_ui_ellements`.
Create a folder when it has real code, not to fill an architecture diagram.
Common code owns reusable contracts and presentation; `desktopApp` owns Windows
integration. `main.kt` is composition and window lifecycle only.

Use meaningful KDoc with parameter, unit, lifetime and error contracts.
Keep Compose layout settings immediately after imports and animation settings
there when animation exists. Use immutable settings and responsive layouts.
No native work, device discovery or file/network I/O in composable bodies.

Run `./gradlew.bat :desktopApp:compileKotlin :shared:jvmTest` for shared/desktop
changes, and `./gradlew.bat :androidApp:assembleDebug` when Android is affected.
Read actual test results; no tests and blocked tests are not a pass.
See `docs/MainDocs.md` for hardware validation and build prerequisites.
Never force push. Keep credentials, local SDK paths and generated output out of Git.
