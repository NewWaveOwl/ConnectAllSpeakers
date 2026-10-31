# Contributing

Read `docs/MainDocs.md`, `docs/AI_HowToWriteCode.md` and `AGENTS.md`.
Work on a task branch from the repository default branch (`main` initially).
Preserve existing local work. A requested PR includes the necessary branch,
commit and push; merging remains a separate action.

Before a PR, run the validation skill, inspect the entire diff and report actual
results. Never force push or bypass hooks. Use a normal push to a new branch if
history would otherwise need rewriting. Keep changes focused and disclose
environment blockers in a draft PR. Do not claim native audio functionality from
the application shell or a fake backend.

Do not commit credentials, local SDK paths, build/IDE caches, recordings or
reference-repository clones. Add new dependencies only for a concrete requirement,
pin their versions in the catalog, and review their license and native packaging.
