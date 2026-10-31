# AI_HowToWriteCode — ConnectAllSpeakers

## Purpose and source boundaries

Production-oriented Kotlin/Compose guidance for this Windows-first app, adapted
from the user-supplied RNDTransitMTL guide and its shared-source organization.
This guide governs new and substantially changed project code. It is a development
standard, not a claim that the current foundation is production-ready.
Reference provenance is in [DevelopmentTools](DevelopmentTools.md).
The user's request and governing tool instructions determine authorized work;
historical exercises, course policies and unrelated transit rules are not tasks.

## 1. Product and delivery scope

- Windows is the first playback platform. Android is a later companion and
  separately evaluated playback platform; preserve the existing minimum API 33.
- Start with audio played by our app and two independently active endpoints.
  System audio capture, calibration, persistence, 2.1 and 7.1 are later features.
- Use OS audio APIs through our backend. Do not require an external mixer app.
  A binding/library is a separate dependency decision, with license and packaging review.
- Bluetooth pairing, connection count, a successful API call or a selected output
  does not establish simultaneous playback, correct channels or synchronization.
- Unknown/unsupported/not implemented are real states. Never populate production
  device lists with demos or silently send every route to the default speaker.

## 2. Architecture and folders

Follow [MainDocs](MainDocs.md) for the actual and planned tree.
`shared/src/commonMain/kotlin/com/example/connectallspeakers` owns reusable
models, state, repository contracts, ViewModels, screen rendering and layout.
`desktopApp/src/main/kotlin/com/example/connectallspeakers` owns Windows audio,
native interop, desktop storage and lifecycle/composition.

Use the reference responsibility split: `View/<screen>`, `ViewModels/<screen>`,
`data/<domain>`, `model/<domain>`, `state/<domain>`, `layout`, `navigation`,
`ui/theme`, `ui/global_ui_ellements`. Keep the last spelling to match the chosen
reference convention. Screen-local components and previews stay with their screen.
Add folders when real code needs them; no empty repositories, fake services or
ViewModels solely to match a diagram. The initial static screen needs no ViewModel.

Dependencies point inward: platform app -> shared contracts/presentation;
ViewModel -> repository contract; view -> immutable state plus callbacks.
Shared code never depends on desktopApp, Win32/COM/WASAPI, Java Sound, Android
Context, or a platform navigation controller. Common dependencies must support
all configured common targets. JVM-only reusable adapters can live in shared/jvmMain
only when genuinely reusable; Windows-only code stays in desktopApp.

`App.kt` assembles the shared UI. `main.kt` owns the desktop window and injects
platform dependencies. The window/application owner closes the audio engine;
screen disposal must not accidentally terminate an application-wide session.

## 3. Kotlin contracts and documentation

- Prefer `val`, immutable snapshots, read-only collection contracts and meaningful
  names. Public read-only types do not guarantee deep immutability; avoid leaking
  mutable backing collections or PCM buffers across threads without ownership rules.
- Use standard lowerCamelCase members and UpperCamelCase types. Never use hyphens
  or escaped punctuation in identifiers; use underscores only for established
  constants/tests or where they improve clarity.
- One primary responsibility per file. Avoid unnecessary forwarding layers and
  speculative interfaces. Use a repository boundary where platform data or
  persistence exists, not for every helper function.
- Document classes and complete new/changed function contracts with KDoc:
  purpose, exact `@param` names, `@property`, meaningful `@return`, units,
  nullability, ownership, errors and cancellation as applicable.
- Use safe parsing and explicit validation. Reject NaN/infinite gain or delay,
  negative delay, unavailable endpoint IDs and unsupported channel formats.
  Define supported bounds from the implemented engine, not invented UI limits.
- Model pending, empty, active, unsupported and failed states explicitly. Handle
  duplicate actions, disconnection, shutdown and cancellation deliberately.

## 4. MVVM, Flow and lifetime

Stateful screens use scoped ViewModels with private mutable state and public
read-only StateFlow. Update immutable values atomically; do not mutate a nested
list and expect emissions. Keep callbacks and navigation execution at UI boundaries.
Use constructor injection, explicit factories/owners, structured concurrency and
bounded queues. Define cold/hot stream ownership and collection lifetimes.
Cancellation must close native work; do not swallow CancellationException.

Do not use replay-free SharedFlow for events that must survive absent collectors.
Use state for durable user-visible outcomes and a deliberate event-delivery policy.
ViewModels and rememberSaveable are not durable profile storage. Persist settings
through a repository only when implemented; never claim restoration without a test.

## 5. Compose, Material 3 and accessibility

Views render immutable inputs and emit actions. No discovery, native calls,
network/file I/O or business mutations in composable bodies. Put effects in
scoped owners and use lifecycle-aware collection where the installed APIs support it.
Use Material 3, resources and accessible labels; decorative icons have no spoken label.
Provide keyboard navigation, visible focus, readable contrast and text scaling.

Put `// Layout settings` after imports with descriptive immutable dp values,
fractions, spacing and size constraints. Add `// Animation settings` there when
animation exists; numeric durations use an explicit `..._MILLIS` suffix.
Keep theme colors/typography in ui/theme. Share one definition for shared tuning.
Do not extract every incidental number or live calculation into global constants.
Keep layouts scrollable/responsive at small window sizes; avoid fixed desktop widths
in common UI. Preview fixtures must be clearly separate from real device data.

Introduce typed Navigation 3 routes only when navigation is needed and the pinned
multiplatform dependencies support it. Preserve root behavior and state identity.
Do not add a navigation framework merely for the initial single screen.

## 6. Windows audio engineering

Implement endpoint enumeration and separate WASAPI render streams behind common
contracts. Inspect endpoint state and supported formats; use stable endpoint IDs,
not display names as keys. Multiple Bluetooth endpoints are hardware/driver-dependent.
Do not assume all paired outputs can render at once or that adding dongles fixes it.

Define interleaving, sample rate, sample type, channel layout and frame count.
Left/right routing must preserve stereo information: route the assigned input
channel to the output channels intentionally, including mono speaker downmix behavior.
Avoid overflow/clipping; implement gain/headroom policy and a limiter only when required.

All delays have explicit units and a frame conversion/rounding rule. Positive
buffering delays fast outputs to match slower ones; negative physical latency is
impossible. A fixed offset does not correct independent clock drift. Timestamp,
measure and handle drift before promising synchronized stereo or surround.

Native COM initialization/thread affinity, callbacks, endpoint notifications,
buffer ownership and teardown belong to the Windows backend. Preallocate callback
buffers; no locks with unbounded waits, allocations, logging, file I/O or UI calls
on the real-time audio path. Keep GUI and control work off audio threads.
Bound buffering, detect underruns and publish structured health state. Handle
endpoint invalidation/reconnection without substituting a different speaker silently.
Release every handle exactly once even when initialization fails partway through.

## 7. Android boundaries

Android does not become a general independent multi-A2DP hub because the app
targets API 34 or receives PCM from Windows. Detect actual capabilities and use
explicit unavailable states. Separate PC remote control, Wi-Fi audio reception,
Samsung shared playback and Auracast integration; none implies arbitrary per-speaker DSP.
Declare applicable foreground service types/permissions when services are added.
Do not request Bluetooth/microphone permissions before a feature needs them.

## 8. Persistence, privacy and dependencies

Profiles should have versioned schemas, stable endpoint references, validated
units and safe fallback for absent devices. Define atomic writes and migration
before storing durable settings. Keep actual persistence claims tied to code/tests.
Do not record microphone or playback audio without a requested feature and user
control. Avoid raw recordings, device addresses, tokens and personal data in logs.
Keep local.properties, native build output, SDK paths and signing keys out of Git.
Preserve pinned versions; update the version catalog deliberately with compatibility,
license and packaging evidence. No network service is required for basic playback.

## 9. Verification and production readiness

Use the matching common/JVM/desktop/Android source-set tests. Test observable
contracts and failures, not file layout or comment wording. DSP tests use known
samples and tolerances; lifetime tests exercise partial failure and cancellation.
Run the commands in MainDocs and inspect discovered/passed/failed/skipped counts.
Report a build blocker as blocked tests, never a pass. Record existing warnings.

Native playback requires real Windows hardware checks: identify OS, driver,
adapter, speaker models, codecs/formats, channel identity, test duration and timing
measurement. Test disconnect/reconnect and shutdown. Simulated success proves
UI behavior only. Packaging, installer, clean-machine launch, resource cleanup,
measured multi-speaker reliability and Android compatibility remain separate gates.
The current foundation meets none of those native playback gates by itself.

## 10. AI and Git workflow

Read relevant source before changes, keep scope focused and inspect the complete
diff. Preserve user work. Use task branches and the project skills for validation,
commit and PR delivery. A request for a PR authorizes those necessary steps;
it does not authorize merging, release publishing or unrelated external actions.
Do not force push or bypass hooks. Report actual evidence and unresolved gaps.
Maintain AI logs when requested, never invent authorship or test results.
