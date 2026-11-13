# Windows virtual audio output prototype

## Purpose

Eventually expose **AllConnect Speakers** in Windows' output-device selector.
Users could choose it as their default output or select it for an individual app.
Audio sent there would enter our engine and be distributed to physical speakers.

**Status — November 2, 2026:** app-side live capture, bounded fan-out, source
controls and loop-prevention code are implemented, with offline fake PCM/device
tests. A pinned SYSVAD-derived render-only driver source project is supplied in
[`output_drivers/windows/virtualAudio`](../output_drivers/windows/virtualAudio/README.md).
**Native x64 Debug compilation, universal INF/API validation and unsigned catalog
generation now pass on this PC.** The package is in the driver's ignored
`build/x64/Debug/package` directory. Signing, installation, real endpoint and
actual cross-application sample transfer have not been verified. This
is an unaccepted feasibility candidate; [issue #11](https://github.com/NewWaveOwl/ConnectAllSpeakers/issues/11)
remains open. The earlier local-file features keep their existing behavior.

```text
Browser / game / music app
    -> AllConnect Speakers (virtual Windows output)
    -> Our audio engine
        -> Left  -> Volume / EQ / Delay -> Physical speaker A
        -> Right -> Volume / EQ / Delay -> Physical speaker B
```

## Components and responsibilities

- **Kotlin/Compose app:** speaker selection, channel assignments, settings and
  engine control; reusable presentation stays in shared/commonMain.
- **Windows audio engine:** receives samples, processes independent streams,
  manages timing and renders to the selected physical outputs.
- **Our native Windows virtual audio driver:** exposes the output endpoint and
  provides an implemented audio-transfer path to the engine. This is a separate
  native component, not a device created by a Compose screen or ordinary WASAPI call.

Microsoft's SYSVAD sample is a starting point for driver development, not a
finished routing product. Driver work requires native tooling, installation,
production signing and compatibility testing. Kotlin Multiplatform does not
replace those Windows-specific requirements. No third-party mixer app is part
of this design.

## Delivery order

1. Finish the interactive frontend using labeled sample data.
2. Prove simultaneous independent playback to two real Windows outputs.
3. Add local-file stereo, per-speaker controls and measured synchronization.
4. Implement the virtual output and route other applications' audio into the engine.

As an earlier system-audio experiment, evaluate WASAPI loopback capture of an
existing output. It does **not** create an AllConnect output device. Protected
content and capture restrictions apply; keep captured source audio separate from
our rendered streams to avoid recapture/feedback.

The virtual device solves **audio input to our engine**. It does not create extra
Bluetooth connections, guarantee simultaneous speaker playback, remove latency
or correct clock drift. Those remain separate backend and hardware milestones.
Before shipping, define behavior when the engine stops or a physical speaker
disconnects, and verify driver install/update/uninstall and resource cleanup.

## Implemented candidate and transfer contract

The candidate uses a real render-only WaveRT driver and software **engine
loopback on that exact owned render endpoint**. The app never captures a
microphone or an arbitrary/default physical output. The driver has no sample
hardware-loopback pin: SYSVAD's generated-tone capture would not transfer the
other application's real samples. Native source, pinned revision/license,
proposed lab/toolchain, INF identity and signing/removal procedure are in the
[driver guide](../output_drivers/windows/virtualAudio/README.md). They need native build
and lab validation before this architecture can be accepted.

- The driver's wire format is 48 kHz stereo PCM16. The reader negotiates the
  actual Windows mix and accepts only 48 kHz stereo PCM16 or float32, including
  validated WAVEFORMATEXTENSIBLE. Anything else stops with a readable error.
- The prototype INF prevents system-default selection. Another application
  must support explicit output selection; compatibility of its selector is a
  lab check. No default switching API or automatic fallback exists in the app.
- The native property `{A9CB07B6-15EA-4D40-96A6-0D7418CE92AD},1 = DWORD 1`
  identifies AllConnect inputs. Names are irrelevant. Property-read errors or
  unexpected values mean Unknown. Owned/Unknown devices cannot be added as
  room targets, tested or rendered to, including after profile restoration.
  The Windows renderer checks the real property again before activation.
- The audio worker owns capture and all render streams. Borrowed capture PCM
  is validated/copied before ReleaseBuffer; all COM resources are closed on
  that worker, including partial preparation and cancellation. Windows packet
  callbacks are not blocked by UI work; this reader polls without waiting.
- A fixed **96,000-frame/two-second** stereo ring has independent fractional
  output cursors. Capture packets are capped at one second; native physical
  queues at 500 ms each; output histories include bounded queues/manual delay
  and a fixed reserve. There is no session-duration-sized recording/file.
- Capture starts only after both physical streams have finished preparation,
  so slow output initialization cannot accumulate stale input packets. Both
  targets start after fresh prebuffering. The common timestamp-derived
  source lag is `largest native queue + 100 ms + 4 frames`. Source channels,
  app gain, three-band EQ and 0–500 ms manual delay use the existing processing
  path. Gain/channel/EQ edits apply live; route/delay/source changes stop and
  require explicit restart. AllConnect input is never a render target.
- Each output has a queue/source-clock PI rate controller with a one-second
  error filter, ±2000 ppm bound and ≤100 ppm/s slew. A >50 ms position error,
  five seconds at the correction demand limit, >250 ms worker stall, invalid
  timestamps, mid-epoch discontinuity, native underrun or ring under/overflow
  fails the whole session. No frames are silently overwritten to recover.
  This is experimental engine timing control, **not acoustic alignment**.
- A capture gap over 100 ms stops/resets native queues and discards the epoch;
  the live session waits idle for fresh input and prebuffers again. Windows may
  supply silent packets continuously instead; these are valid zero-valued PCM,
  so Forwarding does not prove audible sound. An output/source disappearance
  ends the session and never auto-restarts when the device returns.
- Stop, Pause in live mode, mode/file/test/calibration replacement, failure and
  exit release capture/targets and discard backlog. Pause/resume and recording
  are unavailable for live input. Driver absence/stopped engine means the
  virtual sink discards audio; it never routes to a default physical output.

## Controls and persistence

Select **Virtual input · experimental**, choose the driver-identified input,
add one or two physical outputs to the room, and explicitly press **Start live
input**. Missing driver, unsupported platform, discovery loading/error, idle,
buffering, forwarding and capture/playback failures have separate feedback.
The original Local file controls and selected file remain available separately.
Samples never substitute for live input. A virtual endpoint called something
else still counts as an input; a physical device named AllConnect Speakers does
not become an input merely because its name matches.

Saved room routes/gain/channel/delay/EQ retain the existing profile schema.
Live mode, selected virtual input, PCM, capture/queue state and Start intent are
session-only and never persisted. Startup/reconnect/profile restore are silent.
The Manual calibration drift toggle applies to **file playback only**; live
input uses its own per-output rate control and labels native clock readings
Unknown. No acoustic latency or production synchronization is inferred.

## User-operated end-to-end acceptance

**Pending, not performed by the agent.** First complete the driver guide's
native build, INF validation, lab signing/install and exact identity checks.
Do not try to install anything from this checkout before that gate passes.
Maintain the verified playback foundation tracked in #10.

| Check | What you do | Expected result |
| --- | --- | --- |
| Missing driver | Open the normal app, choose Virtual input and refresh. | Driver-not-detected message; Start disabled; physical/file playback remains usable. |
| Endpoint and identity | In the isolated lab, install the reviewed driver and refresh. | Exactly one owned playback input named AllConnect Speakers, no microphone; input cannot be added/tested as a physical speaker. Prior Windows defaults remain unchanged. |
| Actual PCM transfer | Use another application's explicit output selector with a generated stereo WAV: distinct left/right tones and timed pulses. Start with one physical target at low app gain. | Real source audio reaches the selected target, not a generated replacement. Left/Right/Mono select the intended source channel; no sound sent to another/default target. Record format, channels and timing. |
| Two independent outputs | Add two physical outputs; select Left/Right; Start. Change one card's volume/channel/EQ. | Both receive their deliberate channels; edits affect only the selected route. Verify audible behavior yourself; the fake tests do not establish it. |
| Delay and sustained operation | Set manual delay, restart and run at least 20 minutes. Monitor queues, memory, errors and observed rate correction. | Applied delay survives restart; memory/queues remain bounded; either continuous forwarding or a clear bounded error. Record acoustic offset/quality separately. |
| Idle and explicit stop | Stop/start the source application, then press Stop in AllConnect while source continues. Start again later. | A packet gap clears old buffers and re-primes fresh input; valid silent packets can remain Forwarding. Stop releases native streams; restart plays current audio, never the old backlog. |
| Device changes | Disconnect/disable a physical target, then the virtual input; restore each. Change the Windows default separately. | Whole session stops on proven assigned-device loss; cards/settings preserved. No substitutions/restart on reconnect/default change; explicit source/targets retained. |
| Names, format and errors | Rename the input; use duplicate target names; attempt an unsupported mix format or interrupt audio service. | Identity stays tied to endpoint/property, not name. Unsupported/invalid stream state produces an error and releases resources; no fallback/retained audio. |
| Exit/reopen | Close during waiting, forwarding and startup; reopen repeatedly. | No stuck process/streams or automatic capture/playback. Saved room edits restore; live mode/input intent do not. Record any existing physical hardware-buffer tail. |
| Remove/rollback | Remove only the recorded AllConnect lab device/package as in the driver guide. | Virtual endpoint disappears, prior defaults remain intact, app reports input missing and continues to support explicit local-file playback. |

Record Windows/toolkit/build/package identity, device models/driver/codec/format,
source signal, exact routes, duration, queue/memory/quality observations and
pass/fail/cleanup results. Production signing/distribution, security/HLK,
system-default support, update/rollback, sleep/service recovery, clean-machine
installation and acoustic tuning are dependent follow-ups, not this evidence.

## Verification on the development PC

Source preparation verified all 46 pinned upstream hashes and removed the sample
recording registry override. After installing WDK 26100, VS2022 C++ tools,
driver-build integration and Spectre libraries, x64 Debug compilation passed
with zero warnings/errors. The generated engine-node variant retains ordinary
volume/mute behavior without the disabled sideband references. Universal INF
and API validation passed using 64-bit MSBuild. `Package.ps1` also passed
InfVerif and Inf2Cat signability checks and generated an unsigned catalog.
No driver was signed, installed or loaded; no real audio transfer was tested.
Offline tests exercise format rejection, frame/timestamp errors, ring wrap/
overflow, independent cursors, gain/channel/EQ/delay, idle/reset, cancellation,
partial failure/cleanup, source/target loss and reconnect without autoplay.
A 20-minute synthetic run uses a +175 ppm input clock and physical fake queues
at −350/+250 ppm; separate servo tests cover ±100/±700 ppm. These tests prove
software behavior under the declared model, not native sample transfer.

Final verification: **63 shared JVM + 135 desktop tests passed**, with zero
failures/errors/skips. Desktop compilation, Android debug assembly and Windows
app-image packaging passed. The final run used one worker and the in-process
Kotlin compiler after a transient stale compiler-cache lock; no dependency or
toolchain version was changed:

```powershell
./gradlew.bat --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' :desktopApp:compileKotlin :shared:jvmTest :desktopApp:test :androidApp:assembleDebug :desktopApp:createDistributable --console=plain
```

Use the existing [short temporary-path workaround](Validation.md#reproducing-the-temporary-path-workaround)
when needed. The pre-existing Android SDK XML-version warning remains.
Offscreen stateless Compose fixtures were rendered/inspected at 1440×900 and
600×700 for missing-input, ready and forwarding states. Scrolling, focus
traversal and the Start callback passed. The temporary fixture was removed;
the 198-test total excludes it. This was not an app launch or native UI/hardware
acceptance check. No commit/push, driver installation, boot/security change,
real output enumeration or audible session was performed for this feature.

## Earlier implementation plan — November 2, 2026

Keep #11 as the parent feasibility issue, with the seven proposed child slices
below. These are planning identifiers **V1–V7**, not existing GitHub issue numbers.
This historical plan preceded the source implementation described above. No
external child issues or driver deployment were created. Each child can become a focused PR with its
own failure tests; do not close the parent merely because children compile.

### Prerequisite and first prototype boundary

[Issue #10](https://github.com/NewWaveOwl/ConnectAllSpeakers/issues/10) remains
the playback-reliability gate. Planning, source abstractions and fake-clock/PCM
tests can proceed now. User-operated virtual-to-physical acceptance and parent
closure require its recorded playback foundation; a new driver is not a workaround
for missing acoustic/stability evidence. Carry existing limitations forward.

Start on one explicitly declared x64 Windows test build, in an isolated test
environment. Driver-only checks may use a disposable VM; Bluetooth/two-physical-
output acceptance needs a suitable user-operated Windows machine and devices.
Pin compatible Visual Studio/C++/SDK/WDK versions, the SYSVAD source revision and
the test-signing procedure in V1. Build success does not establish deployability.
Installation/signing/boot-setting changes are separate user-operated steps;
this plan does not execute them or change the main development PC.

Proposed initial audio scope is **48 kHz, two-channel shared-mode PCM**, normalized
to interleaved float32 in the app. V1 must pin the driver wire encoding and prove
the negotiated capture format; do not assume it is float32 or the source app's
original rate. Use Windows' supported shared-mode conversion where verified.
Other rates/layouts, exclusive/offload/protected-content paths, surround,
automatic default-device changes and Android are outside the first proof.

### Architecture decision to validate first

The preferred *candidate*, pending V1 evidence, is a minimal SYSVAD-derived real
render endpoint plus a user-mode WASAPI loopback reader bound to **that endpoint's
exact ID**. Microsoft's [loopback documentation](https://learn.microsoft.com/en-us/windows/win32/coreaudio/loopback-recording) describes capture of a render
endpoint's engine mix even without a hardware loopback pin. This suggests a
standard user-mode transfer path may be sufficient, but does not prove compatibility
with our driver: verify it with distinguishable generated PCM in the isolated lab.

This still requires our actual virtual render driver. Capturing an existing
physical/default output alone cannot satisfy #11. If loopback on our endpoint
fails the format/timestamp/sample-transfer checks, record the failed candidate
and choose a versioned bounded native transfer interface in the ADR before
implementing it. Do not automatically add a custom kernel shared-memory protocol,
paired recording endpoint or privileged background service without that need.
All choices must preserve the same portable live-input contract and safe behavior.

The intended ownership is:

| Owner | Responsibility |
| --- | --- |
| Proposed `output_drivers/windows/virtualAudio/` | Minimal driver source, INF/device identity, native build/test package and notices |
| `desktopApp/.../audio/input/windows/` | Exact-endpoint capture/transport, format conversion, timestamps and native cleanup |
| `desktopApp/.../audio/dsp/` and `audio/wasapi/` | Bounded live fan-out, routing, gain/EQ/delay and source/output clock control |
| `shared/.../model/audio`, `data/audio`, `state` | Immutable live-source format, lifecycle, ownership classification and diagnostics contracts |
| Existing shared ViewModel/components | Source choice and explicit Start/Stop; driver absent/idle/error feedback |

Native handles, callbacks, packets and mutable PCM buffers never enter common
UI state. Existing local-file playback remains a separate supported source mode.

### Proposed child issues

| ID / proposed title | Depends on | Deliverable and observable acceptance |
| --- | --- | --- |
| **V1 — Decide virtual-output architecture and establish a repeatable driver lab** | Existing #11 scope | ADR records sample revision/license obligations, supported toolchain/OS, wire format, driver identity, isolation/signing route, bridge alternatives and failure policy. User-operated smoke evidence establishes whether our endpoint + loopback candidate can transfer generated PCM; otherwise record a blocker/no-go. No production signing claim. |
| **V2 — Add a bounded continuous-PCM source contract and deterministic producer** | V1 contract decision | Replace the assumption that every source is a retained finite `DecodedAudio` array with an explicit live-source boundary. Fixed-capacity blocks, frame sequence/format epochs, timestamps, release ownership and cancellation are tested with fake producers. Slow readers never grow memory, steal another output's frames or retain obsolete audio after Stop. Existing file tests stay green. |
| **V3 — Render live input to independent outputs with bounded clock control** | V2 | Adapt fixed and fractional rendering to streaming windows, including cubic look-behind/look-ahead, EQ/delay state and per-output read cursors. Fake independent input/reference/follower clocks prove bounded queues and declared rate/skew limits during a sustained virtual run. Distinguish source silence from a native underrun; test resets/discontinuities without changing file behavior. |
| **V4 — Build the minimal AllConnect Speakers virtual render endpoint** | V1 | Add the actual native driver/package with pinned identity and supported format. In the user-operated lab it installs, appears as a selectable playback output and advances a valid stream clock. Record absence/disabled/stopped-engine behavior. Endpoint visibility is this child's result, not completion of audio routing or #11. |
| **V5 — Deliver real endpoint PCM through the chosen user-mode input bridge** | V2, V4 | Implement the ADR-selected reader/bridge. Another app's generated distinguishable left/right PCM reaches the live-source boundary with validated format, frame counts/timestamps, silence/discontinuity handling and bounded buffering. Prove cancellation/restart/version or format rejection and exact resource release. A fake source cannot satisfy this child. |
| **V6 — Integrate live-input controls and enforce virtual-output loop prevention** | V3, V5 | Add an explicit file/live source choice and Start/Stop with missing-driver, idle, running and failure states. Exclude all reliably identified AllConnect virtual inputs from physical target selection and reject them again in backend route validation, including restored profiles. Tests cover renamed/duplicate-name endpoints and default-device changes. No capture, playback or OS default switching starts automatically. |
| **V7 — Record end-to-end feasibility, install/remove and lifecycle results** | V6, verified #10 foundation | User operates the isolated lab: another app selects AllConnect Speakers, synthetic left/right audio reaches one then two exact physical outputs, controls remain independent, and declared duration/queue/latency/resource targets have recorded pass/fail results. Cover absence/crash/disconnect/format changes, repeated start/stop, restart and driver removal. Link evidence and separate production follow-ups before closing #11. |

V1's smoke test is a bounded investigation using the sample/candidate and a small
native diagnostic harness, not a production integration. V4/V5 turn the accepted
approach into owned, reproducible components. If V1 cannot establish sample transfer
in the declared lab, stop driver-dependent implementation and record the blocker;
portable V2 work can still be useful independently.

### Required live-source and clock rules

The present `AdaptivePcmRenderer` interpolates from a complete decoded array,
and `PlaybackRenderSession` treats starvation before EOF as a failure. A live
source has neither unlimited history nor normal file EOF. V2/V3 must explicitly
model these differences rather than append incoming samples to an ever-growing file.

- Use fixed frame/block capacities, monotonic 64-bit sequence positions and a
  generation/format epoch. Copy borrowed native packets before releasing them;
  validate packet sizes/flags before reading. Define exactly which worker owns
  every buffer. No callback waits for the UI, disk or a slow downstream consumer.
- Fan out through bounded retained history with independent output cursors. One
  physical stream must not consume data needed by another. Include interpolation
  look-around, maximum manual delay and native queue sizes in the memory budget.
- Treat the live producer/virtual stream as an additional clock domain. Existing
  relative two-output drift correction alone cannot keep the entire system from
  drifting against a continuous input. Define source-to-reference rate/occupancy
  control as well as follower alignment, with bounded correction and safe failure
  when limits or timestamp confidence are exceeded. Reuse tested DSP where valid;
  do not assume the file servo is sufficient unchanged.
- Pin target occupancy, maximum backlog, correction limits and measurable test
  targets in V1/V3 before validation. Measure added input-to-output latency and
  source/follower skew separately; engine timestamps are not acoustic measurements.
- On source idle, produce bounded silence while keeping a valid session, with
  clear idle status. Distinguish ordinary absence of idle-source packets from
  expired queued timestamps, and rebaseline clock control when fresh input returns.
  On overflow, unrecoverable underflow, stale data, invalid
  timestamps or a format epoch change, stop/reset the affected live session with
  feedback and require explicit restart. Do not replay old queued audio to catch up.
- Pause/Stop for live mode stops forwarding and discards backlog, rather than
  accumulating a paused recording. Restart starts at the current live timeline
  with fresh histories. The originating app can continue rendering to the virtual
  endpoint; this must not block Windows or grow retained audio.

### Safety and observable failure behavior

| Situation | Required prototype behavior |
| --- | --- |
| Engine absent, stopped or closed | The virtual stream remains bounded and consumes/discards input as established by the driver design; no physical forwarding. Document that selecting it while AllConnect is off can mean silence. No silent fallback to Windows' default. |
| Physical output disappears | Stop the whole live session, preserve room settings and report the missing exact endpoint. Reconnection does not resume automatically. |
| Capture/bridge/driver disappears or changes format | Stop forwarding, invalidate the epoch, discard backlog and release resources; require a validated explicit restart. |
| User chooses or restores our virtual endpoint as a physical target | Reject before stream opening using reliable driver/device ownership plus exact endpoint identity, not its friendly name. Unknown ownership must not silently permit an ambiguous live route. |
| Windows default output changes | Do not follow it implicitly; capture the chosen virtual endpoint and render only to explicitly selected physical IDs. |
| Capture is restricted or the client uses an unsupported mode | Report the limitation and retain a safe stopped/unsupported state; no alternative source, recording or external mixer is substituted. |
| Native custom bridge is necessary | Version/length validation, bounded requests, cancellable I/O and access control are part of V5. A read-only audio path must not become arbitrary kernel memory access. |

Keep raw PCM out of logs/files; diagnostics carry counters, formats and timestamps
only. For the prototype, driver installation/removal remains explicit and separate
from the ordinary app installer. No microphone endpoint or pairing feature is needed.

### Parent completion and production follow-ups

Close #11 only after the original issue's user-operated evidence shows **actual
sample transfer through the real virtual endpoint to the selected physical
outputs**, correct channels, bounded queues and repeated install/remove/start/stop
cleanup. Record the exact OS/toolchain/driver/app versions, source fixture, physical
devices, duration, limits and unsupported combinations. Endpoint visibility,
fake-only success and a successful driver build are not substitutes.

After a positive feasibility result, create separate release work for driver
security/stress hardening, supported Windows/architecture expansion, applicable
Microsoft production-signing/certification requirements, and coordinated app/driver
install/update/rollback/uninstall with clean-machine and saved-profile policies.
Do not fold a production driver release into this prototype or silently replace
the existing app-installer acceptance in #10.

## References

- [Main playback design](MainDesign.md)
- [Microsoft audio driver samples / SYSVAD](https://learn.microsoft.com/en-us/windows-hardware/drivers/audio/sample-audio-drivers)
- [Windows driver signing](https://learn.microsoft.com/en-us/windows-hardware/drivers/install/windows-driver-signing-tutorial)
- [WASAPI loopback capture](https://learn.microsoft.com/windows/win32/coreaudio/loopback-recording)
- [Current WDK/toolchain guidance](https://learn.microsoft.com/en-us/windows-hardware/drivers/download-the-wdk)
- [Test-signed driver deployment constraints](https://learn.microsoft.com/en-us/windows-hardware/drivers/install/the-testsigning-boot-configuration-option)
- [Windows driver samples license](https://github.com/microsoft/Windows-driver-samples/blob/main/LICENSE) (verify the pinned sample and per-file notices before reuse)

## November 12 review

The implementation has been consolidated for issue #11, preserving the pinned
SYSVAD source bytes and exact-endpoint loopback path. The 46 vendored SHA-256
hashes were verified again. The previously recorded November 2 unsigned driver
build is historical evidence, not a fresh installation test.

Fresh Gradle validation is blocked before compilation: this execution environment
cannot connect to the Gradle daemon over loopback. Driver build scripts are also
blocked by the environment's script authorization policy. No new tests or live
endpoint/audio checks passed in this session. Keep #11 open until the isolated
lab install, synthetic PCM transfer, repeated start/stop and removal checklist
above is recorded. No Windows security or driver configuration was changed.
