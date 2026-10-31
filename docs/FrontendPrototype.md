# AllConnect frontend prototype

This is an interactive Windows Compose Multiplatform/Material 3 prototype, not an
audio engine. It implements the [Main setup workspace Figma frame](https://www.figma.com/design/00yms4g5bXQxxiZVINVXo3/AllConnect?node-id=3-3901)
(“02 / Main setup workspace”, node `3:3901`, reference 1440 × 900).

## Session behavior

- Living room starts with Studio Left and Studio Right; Studio Right is selected
  at 80%. Desk stereo and Evening listening have independent sample instances.
- Click a card to inspect it. Name, explicit Left/Right/Mono channel and volume
  edits update that sample immediately. Moving it never reassigns its channel.
- Drag cards within the canvas. Positions are normalized to available card travel,
  so resizing preserves the arrangement and keeps the complete card in bounds.
  Focus a card and use arrow keys for keyboard movement by one visible grid step.
- Snap rounds completed moves to the visible grid; intermediate dragging stays
  continuous. Toggle it to retain arbitrary positions. Zoom (75–150%) scales cards,
  dots and room markers, fitting positions into the canvas without a pan mode.
- Reset positions restores each sample's original placement and 100% zoom in the
  current room. It retains names, volumes, channel assignments, selection and snap.
  Samples added later also have their own reset positions.
- Add/Edit/Delete use small dialogs. Setup names are trimmed; blank and duplicate
  names are rejected. Deleting the last room shows a recoverable empty state.
- Add speaker offers three explicitly identified sample types. Every pick creates
  a distinct session instance; repeated types cannot create duplicate IDs.
- Save setup captures an independent in-memory snapshot and confirms “saved for
  this session.” It does not write files or restore data after closing the app.
- Test speaker shows a temporary visual highlight (1.4 seconds) and “Audio testing
  is not available yet.” A later test cancels the previous highlight timer.
- Settings stays disabled, with a focusable accessible explanation. Narrow windows
  stack the inspector below the room, with vertical scrolling and horizontal
  scrolling for overflowing setup/title/tool rows.

`MainViewModel` owns a read-only StateFlow of immutable models. The screen and its
components render that snapshot and emit typed callbacks. `App.kt` scopes the owner
to its lifecycle. `desktopApp` owns the reference-size window and platform entry.
No Bluetooth, audio, microphone, EQ, networking, database or disk services exist.

## Local design resources

All runtime resources are bundled under `shared/src/commonMain/composeResources`.
The logo is the exact exported `8:821` 44 × 52 dp logo slot, including the clipping
and offsets of the linked `8:822` artwork. It is rendered at that slot's size. The
grid dot (`3:3945`) and listening halo (`3:4871`) are Figma exports. Portable PNG
exports are used by Compose; source SVG exports are retained for provenance.

Typography is Lato Regular/Bold from the Google Fonts Lato distribution, under
the included OFL license. Icons use the same Material Icons Round codepoints as
the design, with the bundled Google Material font and Apache license. There are
no runtime font downloads or temporary Figma URLs. Colors and typography live in
`ui/theme`; layout tuning values sit after imports in rendering files.

## Verification

The common `MainViewModelTest` suite checks setup isolation, selected-speaker
edits, bounded/non-finite moves, snap completion/toggle, reset semantics, immutable
saved snapshots, setup validation, empty-room recovery and unique sample IDs.
The two existing template arithmetic tests remain separate from those checks.

Run from the repository root, using the temporary-path workaround in
[Validation](Validation.md) if this machine requires it:

```powershell
./gradlew.bat :desktopApp:compileKotlin :shared:jvmTest :desktopApp:test :androidApp:assembleDebug
./gradlew.bat :desktopApp:run
git diff --check
```

Visual checks concern only this screen. No hardware, installer or native playback
verification is implied. The prototype intentionally adds sample indicators,
real slider interaction, and accessible focus behavior to the static design.

### Windows verification record — October 31, 2026

- Desktop Kotlin compilation passed.
- Shared JVM tests: 12 passed, 0 failed/errors/skipped; 10 frontend behavior tests
  and the 2 existing template tests. Desktop tests reported `NO-SOURCE`.
- Android `assembleDebug` passed as a shared-UI compatibility check; Android was
  not launched or visually verified.
- Windows app launched successfully. Compared the 1440 × 900 client area with the
  exact Figma frame, including the logo slot, local grid dots, halo, typography,
  card placements, inspector, toolbar and tray. Native window decorations sit
  outside the design's client area.
- Exercised speaker selection, immediate renaming, arrow-key movement and visible
  field focus, dragging, independent setup switching, sample picker/addition,
  visual-only test feedback and highlight expiry, session save, zoom and reset.
- Narrowed the window to about 840 px: normalized placements stayed bounded, the
  inspector stacked below the canvas, and scrolling reached all inspector/tray
  controls with readable labels. Setup overflow remains horizontally scrollable.
- Blank-name feedback was also checked in the live setup dialog. Setup validation,
  deleting the last setup and duplicate instance prevention were verified by the
  focused state tests. Screen-reader integration was not
  independently verified; the views expose labels, selection and state semantics.
- Used the documented short temporary-directory workaround after the baseline
  Gradle invocation hit the existing loopback error. Dependency versions stayed
  pinned. Existing Android SDK XML and native-library stripping warnings remain.
- `git diff --check` passed.
