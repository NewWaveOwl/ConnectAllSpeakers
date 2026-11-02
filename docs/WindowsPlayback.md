# Windows local-file playback — experimental

## Implemented scope

On Windows 10/11, local WAV playback can target one or two explicitly assigned render
endpoints. The shared screen offers Choose WAV, Play/Resume, Pause, Stop, source
position and durable errors. Choosing a file never starts playback. Demo mode
never produces sound. Android and other launchers expose Unsupported.

Supported files: little-endian RIFF/WAVE, mono/stereo, 8–192 kHz, PCM16/24/32 or
IEEE float32, including standard extensible PCM/float. Sample data is limited to
64 MiB and decoded into worker-owned memory; files are closed after loading.
Compressed WAV, MP3, AAC, FLAC, RF64/RIFX, multichannel layouts, empty/truncated
data and non-finite float samples are rejected. A failed load retains the prior
valid file, with an explicit error; it never silently resumes that file.

WASAPI shared-mode float32 streams use each output's mono/stereo channel count
and Windows' automatic sample-rate conversion. More than two physical output
channels is an explicit unsupported-format error: set the output to stereo in
Windows or choose another output. Each route uses its endpoint ID; no route
falls back to Windows' default. Display-name changes/default changes do not
reroute an active session.

- Left copies the source's left sample to every physical channel of that output.
- Right copies the source's right sample to every physical channel.
- Mono averages `(left + right) / 2` and copies that average to every physical
  channel. A mono file supplies the same sample to Left, Right and Mono.
- App volume is linear gain from 0–100%, applied before queuing. It does not
  change Windows master volume. Samples are bounded to [-1, 1]; there is no boost,
  limiter, EQ, delay compensation or calibration. Already queued audio may take
  roughly a buffer plus device/Bluetooth latency to reflect an edit.
- Test speaker explicitly stops file playback and requests a one-second 440 Hz
  tone at -20 dBFS peak before app gain, with 10 ms ramps, on that card only.
  The previously loaded file remains available; the test does not resume it.
- Pause retains each native queue; Resume continues it. Stop releases all streams
  and resets file position. Play after Stop, completion or failure starts at zero.
- Changing rooms, demo mode or room endpoint membership stops playback. Renaming,
  selection, dragging, snap/zoom and Reset positions do not change audio routing.
- A confirmed removal, endpoint failure, invalid buffer or detected underrun
  stops the whole session and reports an error. Cards keep their endpoint IDs and
  edits. Reconnection never resumes automatically; press Play explicitly.
  A discovery loading/error state alone is not proof of disconnection. Native
  calls may still fail if Windows invalidates the endpoint before enumeration.

The outputs have independent clocks and are started sequentially. This milestone
does **not** provide synchronized stereo, drift correction, measured latency,
gapless playback, seeking/looping, system-audio capture, a virtual driver, pairing,
persistence or an Android playback service. Audible simultaneous output and
compatibility remain unverified until the user performs the checks below.

## Ownership and cleanup

`AudioPlaybackController` and immutable `PlaybackSnapshot`/`PlaybackRoute` are
portable shared contracts. `MainViewModel` validates the whole room and sends
explicit intents; stateless controls render state. Desktop owns the native file
chooser and `WindowsAudioPlaybackController`. No additional dependencies were
added; the existing Apache-licensed JNA 5.19.1 binding is reused.

The controller owns one dedicated MTA worker, a bounded conflated command queue,
command generations and an atomic latest-settings snapshot. File decoding and
native calls stay off the UI thread. Stale loads/preparation check cancellation
before opening/starting further outputs. Endpoint preparation and priming finish
for all outputs before any Start. A later Start failure stops/releases every
prepared output; sequential native starts cannot guarantee atomic audible onset.

Rendering is timer-driven (5 ms polling, approximately 100 ms native buffering),
with reusable PCM buffers rather than an application audio callback. A completely
empty running queue before source exhaustion is an explicit underrun failure.
This is an initial renderer, not a real-time scheduling/reliability claim.
Pause keeps queues and checks connection state on a 50 ms control interval.
GetBuffer/ReleaseBuffer remain on the worker; an unsuccessful copy releases the
block as silence. Stop/reset/release runs for every endpoint even if another
cleanup operation fails. COM task allocations are freed, COM is balanced on the
initializing thread, and normal window close awaits playback cleanup before
closing discovery. No native playback session is created before Play or Test.

## Manual checks — user operates the hardware

The implementation agent deliberately did **not** launch this version, enumerate
devices for playback validation, open a native playback stream or produce sound.
These are instructions and expected results, not recorded hardware passes.

From the repository root:

```powershell
python tools/generate_playback_test_files.py
./gradlew.bat :desktopApp:run
```

The generator only writes files under ignored `build/manual-tests`; it never
opens audio devices. Use the temporary-path workaround in [Validation](Validation.md)
if needed. Begin with a comfortable Windows volume and low app volume, increasing
it deliberately. For Bluetooth, pair/connect in Windows first. Record Windows
version, output models, driver/adapter, endpoint formats and the result of each
check. An available endpoint is not proof that its speaker is audible.

An updated app image is also built at
`desktopApp/build/compose/binaries/main/app/com.example.connectallspeakers/com.example.connectallspeakers.exe`.
Open that executable yourself instead of using Gradle if preferred. The whole
app-image directory must stay together; this is not an MSI installation.

| Check | What to do | Expected result |
| --- | --- | --- |
| Discovery | Leave Demo mode off. Compare Add speaker with Windows Sound outputs; connect the Bluetooth speaker and refresh if needed. | Active playback outputs appear; microphones do not. No samples appear as hardware. |
| Single output | Create a fresh room and add one real output. Set Mono and 20% app volume. Choose `stereo-identification-48000.wav`. | Ready and `0:00 / 0:12`; choosing the file is silent. No audio until Play/Test. |
| File identity | Press Play. | Low 440 Hz tone at 0–2 s, silence 2–4 s, higher 880 Hz at 4–6 s, silence 6–8 s, both at 8–10 s, silence 10–12 s, then finished. With Mono each single-channel tone is halved in amplitude. Only the assigned output should sound. |
| Two outputs | Add a second real output to that room. Assign A Left and B Right; replay the same file. | A alone at 0–2 s; B alone at 4–6 s; both at 8–10 s. Other outputs stay silent. Any output-open failure must stop both and show an error, not reroute. |
| Channel edits | During a tone, change one card's Left/Right/Mono. Replay if needed to hear each interval. | That output follows the new source assignment after queued audio drains. Moving its card never changes the assignment. |
| Volume | During playback change A to 0%, then 50%, then 100%; keep B fixed. Compare with Windows volume. | A is muted at 0%; 50% is half the sample amplitude, not a promise of half perceived loudness. B and Windows master volume stay unchanged. Edits may lag by queued/device latency. |
| Pause/resume | Pause in the middle of a tone; wait a few seconds; Resume. | All output consumption pauses, possibly with a short device-buffer tail. Position stops advancing and resumes from the retained queue, not the beginning. |
| Stop/finish | Stop, then Play; also let the file finish naturally. | Stop resets to zero and releases streams. Final buffers drain before finished. Play after Stop/finish starts at zero. No automatic replay. |
| Test speaker | Select B and press Test speaker while the file is playing. | File stops. Only B emits a one-second 440 Hz tone scaled by B's app volume. File remains selected and does not resume. At 0% the test is silent. |
| Mono/resampling | Repeat with `mono-identification-48000.wav` on Left, Right and Mono, and with `stereo-identification-44100.wav`. | Mono source is present on either channel assignment. The 44.1 kHz file has the same pitch/timeline through Windows conversion, or an explicit format error; never a different output. |
| Disconnect | During two-output playback, turn off/disconnect B. Repeat while paused. | Both streams stop with an error; B's card becomes Unavailable after notification/refresh. Names, channels, positions and app volumes remain. Some buffered sound may trail the disconnect. |
| Reconnect | Reconnect B; wait for its card to become available, then press Play. | No sound resumes automatically. Same endpoint card keeps edits; explicit Play restarts at zero. If Windows creates a different endpoint ID, select it explicitly in a new room; it must not replace the old ID automatically. |
| Rooms/demo | During playback switch rooms or enable Demo mode. Then return. | Playback stops. Each room retains independent edits. Demo cannot produce sound or choose files; Test explains that samples cannot play. Real rooms are retained. Return requires explicit Play. |
| Layout | During playback rename/select/drag a card, toggle snap, zoom and reset positions. Resize and use Tab/Enter/Space on transport. | Audio route is unchanged by geometry/name edits; controls stay reachable via scrolling, with visible keyboard focus. |
| Bad input | Choose `invalid-header.wav`, a compressed file renamed `.wav`, or a WAV over 64 MiB of sample data. Cancel the chooser separately. | Clear error, no audio/new native session; the prior valid source remains selected. Cancel does not change source/state. No silent fallback to another file or output. |
| Too many outputs | In a separate room add three outputs and press Play. | Whole-room validation rejects the request; it must not silently play just two. Use a fresh room with at most two outputs to continue. |
| Close/reopen | Close during playback, pause and file loading, then reopen. | App exits without hanging/crashing; audio stops after any hardware-buffer tail. New launch has empty real rooms and no selected file, with no automatic playback. |

For a longer timing observation, run
`python tools/generate_playback_test_files.py --long` and use
`stereo-timing-180s.wav`. Its 12-second pattern repeats 15 times. Listen/record
the both-output 8–10 s interval at the start and end, noting initial offset,
changes, stutters or errors. Offset/drift is a known uncorrected limitation;
do not label outputs synchronized based on this renderer. Report underrun/error
text and which output combinations/formats fail. Longer reliability and acoustic
timing measurements remain separate release work.

## Offline verification

Run `:desktopApp:compileKotlin :shared:jvmTest :desktopApp:test :androidApp:assembleDebug`.
Playback tests inject fake endpoints/decoders and virtual coroutine time; they
never instantiate the WASAPI implementation or produce sound. They cover known
channel samples/gain, supported/malformed WAVs, cancellation, exact IDs, partial
initialization/start failure, pause/resume, EOF draining, underruns, cleanup,
disconnect/reconnect, discovery failures and shared room validation/actions.
Build/test results are recorded in the delivery report. Native playback, current
visual UI, MSI installation and clean-machine execution remain user checks.

### Verification record — November 1, 2026

- `:desktopApp:compileKotlin`, `:shared:jvmTest`, `:desktopApp:test`,
  `:androidApp:assembleDebug` and `:desktopApp:createDistributable`: **passed**.
- Shared JVM: **26 tests passed** across 5 suites; desktop: **41 passed** across
  6 suites. Zero failures, errors or skipped tests. Playback adds 7 shared and
  34 desktop tests to the existing baseline. These are offline/fake-device tests.
- Generated 48 kHz stereo/mono and 44.1 kHz stereo WAV fixtures. Independently
  checked PCM16 headers, 12-second durations, channel-specific tone/silence
  intervals and the -20 dBFS peak bound by reading their bytes only.
- Inspected the Windows app image: the controller, WAV decoder and WASAPI classes
  are present, alongside the existing JNA jars. The app image was **not launched**.
- `git diff --check`: passed. Native calls remain under desktopApp; no Windows
  imports were added to common code and no dependency versions changed.
- Used the existing short temporary-directory workaround. Existing Android SDK
  XML-version and unnecessary non-null assertion warnings remain. Native-library
  stripping was up-to-date in the final build; its earlier warning remains a
  separate release-toolchain concern.
- No hardware, audible playback, current visual UI, MSI install, clean-machine,
  real endpoint cleanup or acoustic synchronization checks were performed.

### Changed implementation areas

| Area | Files |
| --- | --- |
| Shared contract/models | `data/audio/AudioPlaybackController.kt`, `model/audio/Playback.kt`, `model/RoomSetup.kt`, `state/MainUiState.kt` |
| Shared screen/actions | `App.kt`, `ViewModels/main/MainViewModel.kt`, `MainAction.kt`, `View/main/MainScreen.kt`, components `PlaybackControls.kt`, `SpeakerInspector.kt`, `VolumeSlider.kt`, `AudioOutputPicker.kt` |
| Windows assembly/control | desktop `main.kt`, `data/audio/windows/WindowsAudioPlaybackController.kt` |
| Decoder/processing | desktop `audio/dsp/DecodedAudio.kt`, `WavFileDecoder.kt`, `ChannelRenderer.kt` |
| Native rendering | desktop `audio/wasapi/WasapiDeviceSession.kt`, `PlaybackDeviceSession.kt`, `PlaybackRenderSession.kt` |
| Offline coverage | shared `PlaybackStateTest.kt`; desktop `ChannelRendererTest.kt`, `WavFileDecoderTest.kt`, `PlaybackRenderSessionTest.kt`, `WindowsAudioPlaybackControllerTest.kt`, test-only `PlaybackFakes.kt` |
| Manual fixtures/documentation | `tools/generate_playback_test_files.py`, this checklist, `MainDocs.md`, `MainDesign.md`, `FrontendPrototype.md`, `WindowsOutputDiscovery.md` |

## Primary API references

- [Microsoft render-stream lifecycle and buffers](https://learn.microsoft.com/en-us/windows/win32/coreaudio/rendering-a-stream)
- [Microsoft IAudioClient Initialize](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudioclient-initialize)
- [Microsoft automatic PCM conversion and quality flags](https://learn.microsoft.com/en-us/windows/win32/coreaudio/audclnt-streamflags-xxx-constants)
- [Microsoft IAudioRenderClient GetBuffer ownership](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudiorenderclient-getbuffer)
- [Microsoft IAudioClient Stop](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudioclient-stop)
