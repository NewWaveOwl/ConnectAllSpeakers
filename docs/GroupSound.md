# Group Sound — Listening lounge

Implements issue #16's first one/two-output slice using the recommended
[Figma Listening lounge](https://www.figma.com/design/00yms4g5bXQxxiZVINVXo3/AllConnect?node-id=53-1758).
This is experimental software; acoustic synchronization and hardware acceptance
are pending. The other four design concepts are alternatives, not extra screens.

## Use

1. Select **Group Sound**, then **Manage devices**. Connect/pair devices in Windows
   and refresh. Add one or two available physical endpoints. Identical names are
   allowed; the picker shows their distinct endpoint identities.
2. Use **Change source** to select a local WAV/MP3, App audio, or Virtual input.
   Source selection and navigation never start playback.
3. For a local file, use Play / Pause / Resume / Stop. Live sources have explicit
   Start / Stop; live audio is not recorded or retained while stopped.
4. Adjust each device's level or Mute. Mute preserves its level and membership
   and silences PCM after EQ/delay. It does not change Windows master volume.
5. **Adjust timing** exposes existing 0–500 ms manual-delay controls. Changes stop
   playback and require explicit restart. Delaying the faster device can reduce
   an observed offset; it does not measure or prove acoustic synchronization.
6. **Test · 10 s** replaces the active source with the existing bounded speaker
   tone. Press Stop test to cancel. Prior audio does not resume automatically.

Every stereo destination receives the source's original left and right channels.
A mono destination receives `(L + R) / 2`; a mono source duplicates its single
channel on stereo destinations. Room setup retains its existing Left/Right/Mono
assignment behavior. Independent EQ histories prevent left/right crosstalk.

Group membership, volume, mute, delay and source intent are session-only. Room
saves serialize rooms only. The source picker and one application-owned controller
are shared between the two views; switching views stops that controller. There
is no competing audio owner, default-output fallback or automatic restart.

## Source and failure boundaries

- App audio uses the existing process-tree loopback path and user-mode DLL, now
  bundled by the desktop build. It needs Windows build 20348+ x64 and no virtual
  driver. The source application's original output continues playing, potentially
  causing echo. Process IDs/creation times and capture intent are never saved.
- Virtual input needs the separately signed/installed AllConnect prototype driver.
  Group Sound does not install it or change Windows security/default outputs.
- Unknown, missing, duplicate/ambiguous and owned-virtual target identities fail
  closed. The two-output engine limit is unchanged. Availability means Ready,
  never proof of audible output or synchronization.
- Source exit, selected-output loss and unrecoverable capture/render errors stop
  the whole session and discard live backlog. Settings survive; reconnection
  requires explicit restart and never substitutes the default endpoint.
- File, adaptive-rate and live paths all preserve stereo through gain, independent
  per-channel EQ and bounded per-device delay. Muted destinations keep consuming
  the live timeline, so unmuting cannot replay an accumulated recording.
- No accounts, network listeners, phones, invitations, microphone capture,
  Bluetooth pairing controls, surround or three-plus-device support are included.

## Verification and remaining acceptance

Automated checks use generated PCM and fake endpoints; they do not play speakers.
Group-specific tests cover navigation without autoplay, file/live source intent,
exact endpoint validation, independent volume/mute, delay restart, test cancellation,
stereo pass-through, mono downmix, EQ isolation and cleanup. Existing capture,
disconnection, cancellation, persistence and drift tests are retained.

A 30-minute synthetic two-output run with independent clocks and a +175 ppm
source retained at most **7,230 frames** of the 96,000-frame live-history limit.
Final source-rate corrections were approximately +525.12 ppm and −75.04 ppm,
within the ±2,000 ppm limit. This measures the simulated engine, not acoustic skew.

Fresh shared/desktop Kotlin compilation and **218 JUnit tests** passed.
Offscreen layouts at 1440×900 and 560×720 were rendered and visually inspected. The normal Gradle daemon cannot connect over loopback in this chat's
restricted execution environment. An independent compiler check uses the installed
Kotlin 2.4.21/Compose compiler and the prior app image's resolved dependencies,
plus unchanged compiled platform/resource accessors. It is supplemental evidence;
it does not validate Gradle configuration, Android assembly or packaging. The
user-mode process-capture DLL builds directly with MSBuild. Re-run these gates
from a normal PowerShell session before treating the PR as ready:

```powershell
.\gradlew.bat :desktopApp:compileKotlin :shared:jvmTest :desktopApp:test :androidApp:assembleDebug
.\gradlew.bat :desktopApp:createDistributable
```

Before hardware acceptance, agree a numeric inter-device skew/latency tolerance
for the actual setup. Record Windows/toolchain/app/source versions, endpoint
formats, device/adapter/driver details, initial skew and drift for at least
30 minutes. Check source idle/resume/exit, per-device gain/mute, channel identity,
Stop/Test/source replacement, disconnect/reconnect and application exit. Record
queue/memory observations and pass/fail against the agreed tolerance. Until that
passes, the view deliberately says **Timing not checked**. See
[virtual-output lab acceptance](WindowsVirtualOutput.md) for issue #11's additional
install, real cross-application PCM-transfer and removal gates.
