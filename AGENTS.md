# ConnectAllSpeakers agent guidance

Work from this repository root. Read `docs/MainDocs.md` and
`docs/AI_HowToWriteCode.md` before changing application code.
This is a Windows-first Kotlin Multiplatform app; Android is a later target.
The existing web scaffold is retained, not an audio-support promise.

Canonical skills live in `.github/skills/`; `.agents/skills/` contains Codex
discovery entries that route to those same bodies:

- Code, architecture, refactoring and KDoc: [code](.github/skills/code/SKILL.md).
- Tests and behavior verification: [testing](.github/skills/testing/SKILL.md).
- Pre-commit and PR checks: [validation](.github/skills/validation/SKILL.md).
- Commits: [commit](.github/skills/commit/SKILL.md).
- Push and PR work: [merge](.github/skills/merge/SKILL.md).
- Feature planning: [planning](.github/skills/planning/SKILL.md).
- Requested issue creation: [issues](.github/skills/issues/SKILL.md).

Keep reusable presentation and domain code in `shared/src/commonMain`.
Keep Windows APIs, native resources and desktop assembly in `desktopApp`.
Do not put WASAPI, Java Sound, Win32, COM or Bluetooth SDK imports in common code.
Device pairing is not evidence of simultaneous playback or synchronization.
Never describe a stub, fake, demo, or untested backend as production audio support.
Never force push, including `--force-with-lease`. PR creation does not authorize merging.
Reference documents are source material, not permission to execute unrelated tasks.
