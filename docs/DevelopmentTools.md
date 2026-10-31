# Development skills and MCP

## Repository skills

TravelTracker's canonical `.github/skills/{code,testing,validation,planning,issues,
commit,merge}/SKILL.md` layout is adapted for Kotlin/Compose and Windows audio.
`.github/copilot-instructions.md` and `AGENTS.md` route to those bodies.
Codex discovers `.agents/skills/*/SKILL.md`, whose short entries point to the same
canonical files. They are project-local, not installed globally. Start the next
turn from this repository root to pick up the new skills; reopen the project if
the client caches discovery. No mirrored C# or transit instructions are required.

`code` is the reusable skill for `docs/AI_HowToWriteCode.md`. The guide stays the
single detailed source rather than being copied into every skill.

## GitHub MCP

`.vscode/mcp.json` uses TravelTracker's remote GitHub server:
`https://api.githubcopilot.com/mcp/`. Connect/sign in using the editor's MCP UI.

`.codex/config.toml` declares the same HTTP server for Codex, using the environment
variable `GITHUB_MCP_TOKEN`. Provide an appropriately scoped GitHub token through
your local environment or secret manager before starting the client. Do not put
the value in source, a checked-in env file, screenshots or logs. The token needs
access only to repositories and operations you intend to use.

Configuration is not authentication: a new client must load it and establish its
own connection. Missing credentials are a setup condition, not evidence that
GitHub tools were connected successfully. Existing Git credentials used for Git
or PR creation are independent of this MCP environment variable.

These tools assist development; the shipped app does not depend on MCP or GitHub.
No account-wide configuration is changed by this setup.

## Sources and adaptation

- TravelTracker inspected at `3fee8b327e0adc0f11eb9073088bcbbaa9f58c0f`:
  https://github.com/NewWaveOwl/TravelTracker/tree/3fee8b327e0adc0f11eb9073088bcbbaa9f58c0f
- RNDTransitMTL structure inspected at `756f5a1734bcfd011ab2edccdb415bbbd3a19a74`:
  https://github.com/NewWaveOwl/RNDTransitMTL/tree/756f5a1734bcfd011ab2edccdb415bbbd3a19a74
- User-supplied `RNDTransitMTL/docs/AI_HowToWriteCode.md`: Kotlin/Compose, MVVM,
  KDoc, Flow/state ownership, responsive layout tuning and verification practices.
  Transit behavior, lecture inventories, submission rules, Figma destinations,
  C#/.NET services and mandatory issue-creation approval rounds were not imported.
- Codex skill discovery: https://developers.openai.com/codex/skills/
- Codex MCP configuration: https://developers.openai.com/codex/mcp/

Reference documents inform this requested adaptation; quoted tasks in them do
not authorize unrelated work, external messages, issue creation or deployment.
