# Windows playback-output discovery

## Current behavior

Windows starts with empty room layouts. Add speaker opens a picker of active
**render** endpoints reported by Core Audio: speakers, headphones, USB devices,
Bluetooth playback endpoints, HDMI/DisplayPort and existing virtual endpoints
that Windows exposes. Capture endpoints/microphones are never enumerated.
Active means present and enabled, not proof of playback or synchronization.
Pairing alone need not produce an active playback endpoint.

The picker exposes loading, successful empty, error, unsupported and manual refresh
states. The last successful list may remain visible during refresh/failure, but
stale entries cannot be added until a successful refresh. No failure path supplies
demo hardware. Demo mode explicitly switches to a separate sample workspace;
both workspaces retain independent room edits and session save snapshots.

Room instances store user name, explicit channel, normalized position, reset home
and app volume separately from `AudioOutputDevice` metadata. Metadata contains the
opaque endpoint ID, Windows friendly name, availability, multimedia-role default
flag and reliable endpoint form factor. Names are never identities, and transport
is never guessed from a friendly name. Multiple rooms can use the same endpoint;
a room cannot add that endpoint twice.

Missing endpoints keep their room cards with **Unavailable** status. The same ID
returning restores availability without changing user edits or selection. A list
that cannot currently be established marks availability **Unknown**, preserving
the distinction between a failure and a confirmed disconnect. Another endpoint,
even with the same name, never replaces a missing assignment.

Discovery itself creates no playback streams, pairing feature, virtual driver
or microphone access. The current launcher separately injects experimental
[local WAV/MP3 playback](WindowsPlayback.md), which applies app volume and can play
an explicit test tone. The discovery verification record below predates playback.

## Ownership and native lifetime

- Shared `model/audio/AudioOutputDevice.kt` and `data/audio/AudioOutputRepository.kt`
  define portable immutable metadata, lifecycle states and a read-only StateFlow.
- `MainViewModel` observes that flow and reconciles only OS metadata. The screen
  remains stateless and emits typed callbacks.
- Desktop `WindowsAudioOutputRepository` owns one executor-backed coroutine worker
  and a conflated refresh channel. COM initialization, enumeration, property reads,
  registration, unregistration and release all run on that one MTA thread.
- The small SDK-verified binding implements the complete eight-slot
  `IMMNotificationClient` ABI. Added/removed/state/property/default changes only
  signal the bounded channel. They do not enumerate, block, or release COM objects.
- Every acquired interface is released in `finally`; endpoint ID task allocations
  are freed with CoTaskMemFree and each PROPVARIANT is cleared. Cleanup disables
  callbacks, unregisters the listener, releases the enumerator and balances COM.
  Callback trampolines remain strongly reachable through unregistration. In the
  exceptional case that Windows rejects unregistration, they are retained until
  process exit to avoid an unsafe native callback into collected memory.
- The desktop application closes the queue and awaits cleanup before normal exit.
  A screen disposal does not own or terminate the application-wide repository.

## JNA dependency and packaging review

JNA and JNA Platform are pinned together at **5.19.1** in the catalog, used only by
desktopApp. Its published artifacts were inspected before integration. The core
jar bundles `jnidispatch.dll` for Windows x86, x64 and ARM64; both jars are included
in the Windows app-image classpath. No machine-wide native dependency is installed.
JNA's normal bundled-library loading/extraction is retained.

The Apache 2.0 licensing option is selected. JNA's LICENSE and AL2.0 notices and
the bundled native libffi license are included in desktop resource licenses.
The app-image build and native discovery launch succeeded on this Windows PC;
MSI installation, clean-machine distribution and ARM64 execution are separate,
unverified release checks.

## Verification — November 1, 2026

- Desktop compilation and Android `assembleDebug`: passed.
- Shared JVM tests: **19 passed**, including 7 controlled discovery tests and the
  10 existing frontend behavior tests. Desktop tests: **7 passed**. No failures,
  errors or skips in the final reports.
- Controlled tests cover duplicate names/IDs, stale picker protection, refresh
  failures, reconnect by ID, preserved edits, all rooms, mode isolation, retry,
  coalesced callback bursts and same-worker cleanup. Real JNA function-pointer
  tests exercise all notification slots, QueryInterface and by-value PROPERTYKEY.
- The packaged app launched and enumerated actual Windows outputs with names,
  form factors and the default flag. The updated app assigned JBL Flip 3 to a room
  and rendered the new bar at 80% and 50%; pointer input changed its app setting.
- All three Figma workspace mockups were updated and visually inspected. Their
  rail geometry, token bindings, 80% fill, Lato percentages and alignment were read
  back. The original source artwork remains intact.
- Physical Bluetooth disconnect/reconnect was not run. Comparison with Windows
  Sound settings, keyboard volume endpoints and a complete final close/reopen
  sequence were not completed after Computer Use was stopped. These are explicitly
  unverified manual checks, not passing hardware results.
- Builds used the repository's documented short temporary-directory workaround.
  Existing Android SDK XML/native-library stripping warnings remain.

## Primary references

- [Microsoft active endpoint enumeration](https://learn.microsoft.com/en-us/windows/win32/api/mmdeviceapi/nf-mmdeviceapi-immdeviceenumerator-enumaudioendpoints)
- [Microsoft notification callback contract](https://learn.microsoft.com/en-us/windows/win32/api/mmdeviceapi/nn-mmdeviceapi-immnotificationclient)
- [Microsoft callback unregistration and lifetime](https://learn.microsoft.com/en-us/windows/win32/api/mmdeviceapi/nf-mmdeviceapi-immdeviceenumerator-unregisterendpointnotificationcallback)
- [Microsoft endpoint form factors](https://learn.microsoft.com/en-us/windows/win32/api/mmdeviceapi/ne-mmdeviceapi-endpointformfactor)
- [JNA 5.19.1 license](https://github.com/java-native-access/jna/blob/5.19.1/LICENSE)
- [Figma slider source](https://www.figma.com/design/00yms4g5bXQxxiZVINVXo3/AllConnect?node-id=17-891)
- [Reusable slider component](https://www.figma.com/design/00yms4g5bXQxxiZVINVXo3/AllConnect?node-id=21-883)
