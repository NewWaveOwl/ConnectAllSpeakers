# Foundation validation

Validation performed for the Windows-first foundation on October 30, 2026.
This records development checks, not native Bluetooth playback certification.

## Results

- `:desktopApp:compileKotlin`: passed.
- `:shared:jvmTest`: 2 existing template tests passed, 0 failures/errors/skips.
  They assert arithmetic and do not validate audio or synchronization.
- `:desktopApp:test`: NO-SOURCE; there are no desktop-specific tests yet.
- `:androidApp:assembleDebug`: passed, preserving the Android launcher/shared UI.
- `:desktopApp:run`: launched successfully; the window was visually checked for
  readable text and layout, then closed normally. The task completed successfully.
- Skill-creator validator: all 7 canonical skills and 7 Codex discovery entries passed.
- MCP TOML and editor JSON parsed successfully. Authentication is separate;
  these syntax checks do not establish an active MCP session.
- `git diff --check`: passed before delivery.

Existing toolchain output included an Android SDK XML-version mismatch warning
and an unstripped `libandroidx.graphics.path.so` notice. The build succeeded;
release packaging must assess its toolchain and native library handling separately.

The initial baseline invocation failed before compilation with
`Unable to establish loopback connection` / `UnixDomainSockets.connect`.
Using a shorter temporary-directory path for Java fixed this environment issue.
No Gradle, Kotlin or Compose versions were changed to obtain the successful build.

## Reproducing the temporary-path workaround

Only use this if Java fails to create loopback sockets in a long temporary path.
From the repository root in PowerShell:

```powershell
$audioBuildTemp = Join-Path $PWD '.local-tmp'
New-Item -ItemType Directory -Path $audioBuildTemp -Force | Out-Null
$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$previousJavaToolOptions -Djava.io.tmpdir=$audioBuildTemp -Djdk.net.unixdomain.tmpdir=$audioBuildTemp"
    ./gradlew.bat --no-daemon :desktopApp:compileKotlin :shared:jvmTest :desktopApp:test :androidApp:assembleDebug --console=plain
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
}
```

Choose a short, writable path without spaces for this workaround. The command
changes only the current process environment and restores it afterward.

## Remaining verification

No Bluetooth devices were connected or exercised. Endpoint enumeration, distinct
left/right streams, per-speaker controls, clock drift, reconnection, shutdown of
native resources, MSI creation/install and clean-machine playback remain future
implementation and validation work. The foundation must not be described as a
working multi-speaker engine.
