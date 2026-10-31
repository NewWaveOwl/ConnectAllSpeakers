---
name: commit
description: Commit requested ConnectAllSpeakers changes on a task branch after validation, staging only relevant code, skills and docs.
---

# Focused commits

Read `CONTRIBUTING.md` and run `validation`. A request to create a PR authorizes
the necessary task branch, commits and push. Inspect existing user changes first.
Never reset, overwrite or stash unrelated work without authorization.

Use a task branch, never commit directly to the default branch. Stage named paths
after reviewing them; exclude secrets, local.properties, build/cache/IDE output
and research checkouts. Existing untracked scaffold required for the requested
foundation can be included after inspection and disclosed in the PR.
Use a clear imperative subject. Preserve hooks and signing settings.
Inspect staged diff and `git diff --cached --check`, then commit and report its hash.
Never force push. Committing does not authorize an unrequested merge or release.
