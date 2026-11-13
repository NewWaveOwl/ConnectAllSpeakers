# AllConnect virtual audio driver prototype

**Status — November 2, 2026: x64 Debug driver compiled and unsigned package generated.**
Native compilation passed with zero warnings/errors using VS2022 Build Tools
17.14, MSVC 14.44, SDK/WDK build 26100 and KMDF 1.33. Universal INF validation,
API validation and Inf2Cat signability checks passed. The package is under
`build/x64/Debug/package` (SYS, INF, unsigned CAT, notice and license).
Signing, installation, endpoint visibility and actual audio transfer remain
unverified. This is not yet a Windows-loadable signed package. Do not close #11.

## Architecture and declared lab

Target the initial lab at **Windows 11 x64 build 26100**, with **Visual Studio
2022 (17.x), C++ x64 tools, matching SDK/WDK 10.0.26100.0, WDK Visual Studio
extension and KMDF 1.33**. These are the proposed build/lab versions, not a
verified compatibility matrix. ARM64, older Windows, production signing, HVCI/
Secure Boot compatibility and shipping installers remain follow-ups.

The source uses Microsoft's SYSVAD WaveRT miniports, pinned and licensed in
[NOTICE.md](NOTICE.md). AllConnect's descriptor registers one **AllConnect
Speakers** render endpoint with one system stream and a topology bridge:

- Hardware ID `ROOT\AllConnectVirtualAudio`; service `AllConnectVirtualAudio`.
- Wire format: 48,000 Hz, stereo, signed PCM16, WAVEFORMATEXTENSIBLE. Default
  and RAW processing modes share the same format. No offload/hardware engine.
- No hardware loopback pin. SYSVAD's sample loopback/capture generates sine
  audio, so exposing it would not transfer the client application's samples.
  Instead the app requests **Windows software engine loopback on this exact
  render endpoint**. Actual sample fidelity/timestamps still need lab evidence.
- `TopologyAllConnect` / `WaveAllConnect` must match the INF reference strings.
  A device-relative MediaCategories name GUID labels the endpoint pin AllConnect
  Speakers without renaming the global Speakers category. Windows can append
  an adapter label in its full friendly-name presentation; verify the displayed
  name in the lab.
- Endpoint property `{A9CB07B6-15EA-4D40-96A6-0D7418CE92AD},1 = DWORD 1`
  identifies ownership through the endpoint property store. Verify that Windows
  propagates this marker before allowing the bridge. Never bypass the marker
  check or infer identity from the editable device name.
- This first build **cannot become the Windows system default**. The INF sets
  `PKEY_AudioDevice_NeverSetAsDefaultEndpoint` for all roles/flows, avoiding
  silent system-wide routing to a sink while the engine is absent. Use an
  application's explicit output selector. A future default-device workflow
  needs a deliberate absence/recovery policy and separate review.
- While AllConnect is stopped/absent, Windows consumes the virtual stream and
  discards it. There is no physical fallback, retained recording or replay.
- Sample PCM file recording is off and its registry override is removed in the
  generated adapter. No APO/keyword DLL, mixer service or external mixer app.

This is an architecture candidate with implemented source, **not an accepted
feasibility result**: signing, deployment and real engine-loopback behavior
remain unverified on this PC. If software loopback of the
actual mix fails, record a failed candidate and redesign the transfer interface;
do not route arbitrary physical/default output loopback as a substitute.

## Build without installing anything

Run from the repository root:

```powershell
& ./output_drivers/windows/virtualAudio/Build.ps1 -CheckTools
& ./output_drivers/windows/virtualAudio/Build.ps1 -PrepareOnly
& ./output_drivers/windows/virtualAudio/Build.ps1 -Configuration Debug
& ./output_drivers/windows/virtualAudio/Package.ps1 -Configuration Debug
```

`-CheckTools` checks the WDK/KMDF files, Visual Studio 2022 C++ tools,
MSBuild and kernel-driver toolset integration together. It writes no sources
and installs nothing. Install the build-26100 WDK for this VS2022 project;
Microsoft's latest WDK targets VS2026 instead. The SDK/WDK build numbers must
match, but their servicing revision numbers do not have to match.
See [Microsoft's toolchain guidance](https://learn.microsoft.com/en-us/windows-hardware/drivers/download-the-wdk).
For Visual Studio 2022 Build Tools, import [BuildTools.vsconfig](BuildTools.vsconfig)
in Visual Studio Installer to select C++ and driver-build integration. The
matching SDK/WDK installation is still required separately.

Preparation verifies all 46 vendored source hashes and writes the reviewed
adapter and engine-node variants under ignored `build/sources`. The latter
retains ordinary-device volume/mute branches and removes eight unguarded
sideband-only branches that do not compile with sideband disabled. Building checks local tooling,
then invokes the x64 driver project. Output is under `build/x64/Debug`.
Signing/catalog creation is disabled; the script never downloads tools,
creates certificates, changes Windows boot/security configuration or installs,
loads, starts or removes a driver. Keep the vendored source byte-for-byte;
record a new upstream revision/hash manifest when updating it.

`Package.ps1` builds, copies SYS/INF and license notices into `build/x64/<configuration>/package`,
runs universal INF validation, and creates an unsigned catalog for Windows 11
build 26100 x64. It never creates/trusts a certificate, signs a package, changes
boot/security settings or installs a driver. The verified build used 64-bit
MSBuild; 32-bit MSBuild failed loading the WDK's INF-verification DLL.

## User-operated isolated lab checklist

1. Use a disposable Windows test environment with a recovery snapshot and the
   declared toolchain. First build the driver, review compiler/INF errors and
   run WDK INF validation. Resolve failures before making an installation package.
2. Follow Microsoft's [driver signing guidance](https://learn.microsoft.com/en-us/windows-hardware/drivers/install/windows-driver-signing-tutorial)
   and [test-signing documentation](https://learn.microsoft.com/en-us/windows-hardware/drivers/install/the-testsigning-boot-configuration-option)
   to create a catalog and a package accepted by **that lab's** security policy.
   Use only lab certificates. This repository does not automate security changes
   or claim Secure Boot/production acceptance.
3. In the lab, install the reviewed package as a **root-enumerated device** with
   hardware ID `ROOT\AllConnectVirtualAudio` using the WDK device installation
   tools. Merely staging an INF in Driver Store does not create a root device.
   Record its device instance ID and published INF name for removal.
4. Expected: one playback endpoint named AllConnect Speakers; no microphone;
   Windows default outputs unchanged. Confirm 48 kHz stereo and the endpoint
   ownership marker. If the marker is missing, the app must not offer it as a
   virtual input: stop and fix the package rather than renaming another device.
5. Follow the [engine/PCM acceptance procedure](../../../docs/WindowsVirtualOutput.md#user-operated-end-to-end-acceptance).
   Compare a distinguishable synthetic left/right source in another app with
   what the engine forwards; visibility alone is insufficient. Use fake tests
   only as software evidence, not a replacement for this check.
6. Repeat engine Start/Stop, client start/stop, app exit/reopen and virtual/
   physical endpoint disable/re-enable. Expected: no stuck streams, no old-audio
   replay or automatic substitution/restart. Record any buffered hardware tail.
7. Remove the **recorded AllConnect device instance** and its **recorded published
   driver package** using the Windows/WDK removal tools. Expected: endpoint gone,
   prior defaults intact, app reports missing input and still plays local files
   to explicit physical outputs. Do not delete unrelated audio drivers.

Record Windows build, toolkit/compiler version, package hash/signing method,
endpoint IDs/formats, device/driver models, duration, resource/queue observations,
pass/fail results and cleanup outcome. The agent performed none of these live
checks. First-stage playback evidence in #10 is still a dependency.

## Production follow-ups

Driver Verifier/HLK and security review; native/distribution code signing;
protected-content behavior; declared build compatibility; client format changes;
multiple instances; power/sleep/service restart; update/rollback/uninstall and
clean-machine installer verification; deliberate system-default support; acoustic
latency/drift tuning. No automatic driver deployment is included in the app.
