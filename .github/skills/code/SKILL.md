---
name: code
description: Write or review ConnectAllSpeakers Kotlin and Compose code, architecture and KDoc using shared MVVM boundaries and a native Windows audio backend.
---

# Kotlin and Windows audio code

Read `docs/AI_HowToWriteCode.md`, `docs/MainDocs.md`, the affected source and
Gradle catalog first. The coding guide is the production adaptation of the
supplied Kotlin/Compose guide; transit behavior and coursework rules do not apply.

- Use `shared/commonMain` for models, state, repositories, ViewModels and reusable
  Compose UI. Use `desktopApp` for Windows APIs, native audio, desktop persistence
  and composition. Do not duplicate screens across platform roots.
- Follow `View/<screen>` and `ViewModels/<screen>` responsibility separation.
  Keep common components in `ui/global_ui_ellements`, theme in `ui/theme`,
  scaffold in `layout`, typed destinations in `navigation` when needed.
- Use constructor injection and immutable state. Views render state and emit
  callbacks. Add ViewModels only for actual state/operations; no static-screen boilerplate.
- One primary production responsibility per file. KDoc documents parameters,
  properties, units, ownership, cancellation and meaningful errors.
- Put layout and applicable animation settings after imports. Preserve responsive
  behavior and accessible labels; use existing resources before adding assets.
- Native handles, buffers and audio threads belong to the Windows backend.
  Never do blocking work or allocate/log on the real-time callback path.
- Requesting an endpoint is not proof it played. Fail explicitly on unsupported
  routing. A mono speaker may need its assigned channel copied into both output
  channels; define the mapping rather than relying on hardware downmix behavior.
- Preserve pinned dependencies and supported targets. Verify unfamiliar APIs in
  primary docs. Do not add an audio mixer application as a runtime dependency.

Apply `testing` for behavior changes and `validation` before the requested PR.
