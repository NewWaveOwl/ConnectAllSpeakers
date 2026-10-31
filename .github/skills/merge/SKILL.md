---
name: merge
description: Push requested ConnectAllSpeakers task branches and create PRs, or perform explicitly requested integration work, without force pushing or automatic merging.
---

# Branch and PR delivery

Read `CONTRIBUTING.md`, repository state and `validation` results.
Resolve the real remote default branch; this project starts on `main`, not the
reference project's `master`. Fetch and inspect divergence before integration.

Creating a requested PR includes a branch, focused commit and normal push.
Never force push (including --force-with-lease), push directly to the default
branch, auto-merge, or delete branches as a side effect of PR creation.
Preserve user work and resolve conflicts only when both intents are clear.

Use available GitHub MCP, CLI or authenticated API capabilities. Describe the
final change, actual checks and hardware limitations. Use a structured body or
body file. Open a draft if validation is blocked, explain the blocker, and do
not call it release-ready. Read back the PR to verify branch, title and URL.
Merge only when explicitly requested and repository checks/approvals permit it.
