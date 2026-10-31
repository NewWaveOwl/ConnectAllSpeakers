---
name: validation
description: Validate ConnectAllSpeakers changes before commit or PR with Gradle checks, source boundaries, documentation and honest audio capability evidence.
---

# Validation before delivery

Read `git status`, the complete diff (including new files), `docs/MainDocs.md`
and the relevant guide sections. Run from the root.

1. For desktop/shared changes run
   `./gradlew.bat :desktopApp:compileKotlin :shared:jvmTest :desktopApp:test`.
   For Android-affecting changes also run `:androidApp:assembleDebug`.
   Verify task availability against the checked-in Gradle configuration.
2. Inspect results and test counts. Separate baseline failures, regressions,
   warnings and environment blockers. Never treat a failed build as passing tests.
3. Run `git diff --check`; inspect staged changes for secrets, local paths,
   generated output and unrelated edits. Check package/import consistency.
4. Check doc/skill links, configuration syntax and source-set responsibilities.
   For docs-only changes those consistency checks may be sufficient.
5. New skills: run the installed skill-creator `quick_validate.py` when available;
   review discovery descriptions and resolve all local reference paths.
6. Verify visible UI when changed if the environment supports it. Record hardware
   checks separately from compile/unit checks. No real audio claims from stubs.

Report commands and actual outcomes. A blocked build is a blocker, not a green
release gate. A requested draft PR may document an unresolved environment blocker.
Do not change dependency versions merely to hide local tooling problems.
