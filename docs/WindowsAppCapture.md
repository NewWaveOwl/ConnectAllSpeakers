# Selected app audio capture

The Windows desktop app now offers **App audio · experimental** alongside
Local file and Virtual input. It uses Windows process-loopback capture and
the existing bounded live renderer. It creates no virtual device, installs no
driver and changes no Windows default output. Native compilation, automated
tests and user-operated audio acceptance are separate evidence.

## Use

1. Open the audio-producing app and add one or two real physical outputs to
   the AllConnect room with Demo mode off.
2. Choose **App audio · experimental**, then **Refresh apps** if necessary.
3. Select the app's executable/PID. For apps with multiple processes, select
   the main process to include its children. Only processes whose executable
   and creation time can be read are shown. This is a process list, not a list
   proving which apps currently produce audio.
4. Press **Start app audio**. Stop explicitly when finished.

The source app's original output continues playing: this capture API does not
mute or redirect it. Hearing the original path alongside delayed AllConnect
outputs can cause echo. No automatic endpoint muting or source routing is
implemented. Silence can mean an idle app, a wrong process, or restricted
content; Forwarding status is not proof of audible sound.

Requires Windows build **20348 or newer**, x64 and the bundled user-mode helper.
Other platforms expose unsupported state. On older Windows or activation
failure, capture stops with a readable error and does not capture the default
endpoint instead. Protected content and unsupported client modes may prevent
capture. Process-loopback compatibility still needs real-app testing.

## Implementation and lifetime

- `native/windows/processCapture`: ordinary C++ DLL for
  `ActivateAudioInterfaceAsync` with INCLUDE_TARGET_PROCESS_TREE. No WDK or
  administrator access is required to build/use this helper. VS2022 C++ tools
  and Windows SDK 26100 are required for development.
- `desktopApp:buildProcessCapture` builds and bundles the DLL as a JNA resource
  in the desktop jar, including app-image/MSI assembly. It uses the static C++
  runtime so the helper adds no separate runtime installer.
- The shared UI stores only executable labels, PIDs and creation times for this
  session. Command lines, paths, selections and audio are not persisted.
- Discovery and selection never activate capture. The worker validates the
  selected process and checks its creation time natively before activation,
  rejecting reused PIDs. Own process, ancestors and descendants are excluded
  to avoid capturing AllConnect's rendered streams.
- Activation has a five-second limit. Stop/source replacement cancels pending
  startup before native capture or physical output Start. The async completion
  object retains its event/activation parameters until completion even after
  timeout. Once started, process exit ends capture; relaunch does not restart it.
- The worker initializes a fixed 48 kHz stereo PCM16 stream with Windows
  conversion, then uses the existing packet validation, bounded stereo history,
  gain/channel/EQ/delay, independent output cursors and source-clock correction.
  It retains no recording. Pause, Stop, errors, physical-output loss, source
  changes and exit discard live buffers and release capture/render resources.

## User-operated audio acceptance (pending)

Test a known left/right fixture in a selected real player, with a second app
playing a distinguishable signal. Confirm only the selected process tree is
forwarded, correct channels reach the chosen physical outputs, and gain/EQ/delay
apply. Record the original app output separately so it is not mistaken for
forwarded audio. Test idle/resume, Stop, changing source, app exit/relaunch,
physical disconnect/reconnect and closing AllConnect. Repeated starts must not
retain streams, replay backlog or automatically resume. Measure memory/queues,
input-to-output latency and acoustic alignment over a declared duration; fake
tests and engine timestamps do not establish these results.

Reference: [Microsoft application-loopback sample](https://learn.microsoft.com/en-us/samples/microsoft/windows-classic-samples/applicationloopbackaudio-sample/).

## Development verification

The results below were previously recorded in the original checkout. Current
Group Sound checks and remaining Gradle/packaging gates are documented in
[GroupSound](GroupSound.md#verification-and-remaining-acceptance).

The x64 native helper compiled successfully. Automated validation passed with
69 shared JVM tests and 140 desktop tests, zero failures/errors/skips. New tests
cover explicit selection/start, process-identity replacement, mode changes,
Demo rejection, missing targets, superseded startup and capture failure/cleanup.
A native boundary test loads the bundled DLL and rejects zero/self PIDs without
opening any audio stream. Android debug assembly and desktop app-image creation
also passed. UI inspection was unavailable because desktop-control access to
AllConnect was not approved. The real-app audio acceptance above remains pending.
