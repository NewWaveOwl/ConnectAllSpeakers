# Explicitly saved room setups

Issue [#8](https://github.com/NewWaveOwl/ConnectAllSpeakers/issues/8) adds per-user
Windows storage for explicitly saved rooms. Storage is separate from discovery
and playback. Startup restores settings and selection, **never starts audio**,
and does not restore an audio file. Android/web launchers remain session-only.

## Save and unsaved edits

- **Save setup** captures the selected room and its selection when clicked. The
  button shows Saving until the asynchronous write completes. Only then does the
  app say **saved on this PC** and replace that room's saved snapshot.
- Edits made during or after that write stay unsaved. Saving another room keeps
  all other rooms at their last explicitly saved versions. New rooms that have
  never been saved disappear when closing; default empty rooms are not silently
  added to a saved workspace on reopening.
- Room/card selection, names, exact endpoint IDs, source channels, app volume,
  current/home normalized positions, snap/zoom, manual delay/reference and the
  experimental drift opt-in are retained. The last successful Save remembers the
  active mode and room selection. Switching rooms/modes alone does not write.
- **Delete** of a saved room also deletes its durable snapshot. The confirmation
  explains this. Removal completes after storage succeeds; a failure leaves the
  room intact. Deleting the final saved room restores an intentional empty bank
  next time. Deleting a never-saved room only removes its session edits.
- Real and demo banks are separate. Restoring a demo bank shows the existing
  **Demo — sample speakers** indicator; samples cannot enter real discovery.
  Saving one bank does not save the other bank's unsaved edits. After a profile
  exists, an empty demo bank stays empty; use Add setup/Add sample speaker.
- Fresh Windows metadata does not count as an unsaved user edit. Missing endpoint
  IDs keep their cards and settings and become Unavailable after a successful
  enumeration. A discovery failure means Unknown. Returning the **same ID**
  restores availability; an identically named different ID is never substituted.

No native handles, endpoint availability/default/type, audio-file paths, decoded
samples, pending playback intent, clock readings or drift estimator/correction
state are saved. The drift switch is a saved preference; processing starts fresh
only after an explicit Play. Bluetooth startup latency can change, so stored
manual calibration still needs rechecking by the user.

## Location, atomic writes and recovery

The Windows launcher uses **`%LOCALAPPDATA%\AllConnect\setups.acp`**. This is local
per-user configuration, with no database or network. It contains names and opaque
endpoint IDs; do not post it publicly without reviewing its contents.

All file work runs on the IO dispatcher. A short-lived `setups.lock` coordinates
app instances. Each save validates the snapshot, checks the file still matches
the last successful load/save, writes a unique same-directory `.pending` file,
forces its contents/metadata to storage and requests atomic replacement. There
is **no delete-first or non-atomic fallback**. Filesystem/permission/space errors
are reported; the previous file remains the source of truth. Ordinary cleanup
removes owned pending files. A crash can leave an orphan, which is never loaded
as a profile. Normal window shutdown awaits active storage work.

This implementation uses Java's [atomic move](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Files.html#move(java.nio.file.Path,java.nio.file.Path,java.nio.file.CopyOption...))
and [file force](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileChannel.html#force(boolean))
guarantees on the local filesystem. Unsupported atomic replacement is a save
failure. Filesystem/controller power-loss behavior is outside an app-level
guarantee; clean-machine/installer and actual abrupt-power-loss checks are not
claimed by temporary-file tests.

An unreadable, malformed, truncated, oversized or unsupported file leaves a
persistent error with **Retry loading saved setups…**. Startup remains usable
with unsaved rooms; Save is disabled until loading succeeds. The app does not
overwrite a file it could not load. For recovery:

1. Close other AllConnect windows. Correct folder permissions/free space if needed.
2. For a damaged/unsupported file, preserve a copy and move `setups.acp` aside.
   Use a newer app instead if the file belongs to a newer schema.
3. Choose Retry loading and confirm. A successful reload discards session edits;
   a failed reload retains them. No existing file means a fresh workspace.
4. Save the intended rooms again. A normal write failure can be retried with
   Save after correcting its cause; old snapshots and current edits remain intact.

If another window or external editor changed the file, saving is rejected rather
than overwriting its changes. Reload explicitly (with the unsaved-edit warning)
before writing again. This is conflict detection, not a live multi-window merge.

## Schema and evolution

`SetupProfileRepository` and immutable `SetupProfile`/`ProfileBank`/`RoomProfile`/
`SpeakerProfile` models live in shared code. `DesktopSetupProfileRepository`
owns the Windows directory, bounded reads, locks, flushed temporary files and
atomic replacement. No new dependencies were added.

Format **version 1** is strict UTF-8, at most **1 MiB**, with header
`ALLCONNECT-PROFILES<TAB>1`. Named-key records are separated by newlines:
workspace (`W`), ordered Real/Demo banks (`B`), room (`R`) and card (`S`). Bank
room counts and room card counts are explicit. A final `END` line and terminating
newline are mandatory. Field values escape backslash, tab, newline and carriage
return. Unknown/duplicate required keys, invalid escapes/UTF-8, duplicate
room/card identities, duplicate endpoint assignments within a room, invalid
references/selections, non-finite/out-of-range settings and mixed demo/hardware
records reject the entire document. Nothing is silently clamped or redirected.

Limits: 64 rooms per bank, 64 cards per room, IDs up to 256 characters, endpoint
IDs/labels up to 4096, room names 1–60 and nonblank speaker names 1–80 characters.
Settings retain current engine ranges: 0–100% gain, 0–500 ms delay, normalized
positions 0–1 and zoom 0.75–1.5. The effective reference must have zero delay.

Optional future room/card fields use bounded **`ext.*`** keys (up to 32, key up to
80/value up to 4096 characters). The codec and editing/save mapping retain them
by stable identity. A tested `ext.eq.lowGainDb` fixture survives an older editor
changing volume/name without losing room settings. This is storage compatibility,
**not EQ processing**. A future EQ implementation must validate its own parameters
before applying them. Required semantic changes need a new schema version and an
explicit migration with old-to-new fixture tests; older apps reject newer versions
and protect their files. There is no pre-v1 on-disk schema to migrate.

## Verification and user checks

Automated checks use in-memory discovery/playback and isolated temporary
directories only. They cover Unicode/escaped names, same-named endpoints,
settings/selection/empty-bank round trips, optional EQ-field preservation, invalid
values/identities/versions/size/UTF-8, failed/interrupted saves, atomic replacement
of existing files, stale-window conflicts, orphan temporary files, blocked storage,
normal storage shutdown and restored missing/reconnected endpoints. Restore tests
assert zero load-file/play/test/calibration requests and no restored timing state.

User-operated checks (no sound required):

1. Edit two rooms independently, including name/channel/volume, layout, selected
   card, manual delay/reference and drift switch. Save each; expect success only
   after Saving ends. Close/reopen: both saved configurations return, with the
   last saved selection, no audio file and no automatic playback.
2. Edit a saved room without saving; select another room/mode without saving.
   Reopen: expect the previous saved edits, mode and selection. Save a different
   room and repeat: it must not save those other unsaved edits.
3. Save a demo room, reopen and confirm the Demo indicator. Switch to real mode:
   no samples appear as discovered hardware.
4. When convenient, reopen with one assigned device absent. Expect its exact card
   and custom settings to remain Unavailable. Reconnect it: only that endpoint ID
   becomes available; no playback starts. This live check is left to the user.
5. Delete a saved room and reopen. It stays deleted. Delete the last room and
   reopen: the empty state permits creating a new setup.
6. With a backup and the app closed, temporarily replace the profile with
   truncated text. Reopen: expect protected-file feedback, no crash and no save
   success. Move the damaged file aside and retry loading; expect recovery.

Hardware/acoustic checks remain user-owned and separate from this storage slice.

### Verification record — November 2, 2026

- Desktop compilation, shared JVM tests, desktop tests, Android debug assembly
  and Windows app-image packaging passed: `:desktopApp:compileKotlin
  :shared:jvmTest :desktopApp:test :androidApp:assembleDebug
  :desktopApp:createDistributable`.
- **53 shared tests and 102 desktop tests passed**, zero failures/errors/skips.
  This includes 17 new portable profile/restore tests and 8 temporary-filesystem
  tests. An initial duplicate Stop request on deletion and a new test's JUnit
  return type were corrected before this final full successful run.
- No storage/serialization dependency was added. The existing Android SDK XML
  compatibility warning and discovery-test unnecessary `!!` warning remain.
- Six silent offscreen fixtures checked loading, unsaved, saved, saving, protected
  load failure and reload confirmation. Labels/status/actions were readable at
  1440×900, 840×1100/900 and 560×480. Temporary rendering code is excluded from
  the delivered tests. Native desktop keyboard/focus, actual app reopen, live
  devices and installer behavior are not claimed by this visual check.
- No app assembly, real endpoint enumeration, audio playback or hardware test was
  run. User profiles under LocalAppData were not opened or changed by verification.
