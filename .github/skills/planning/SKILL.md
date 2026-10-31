---
name: planning
description: Plan ConnectAllSpeakers Windows and Android features from project docs and implemented capabilities, including Bluetooth feasibility experiments.
---

# Plan from evidence

Read `docs/MainDocs.md`, `docs/AI_HowToWriteCode.md`, current source and existing
issues/PRs if accessible. Distinguish implemented behavior from roadmap and stubs.
Follow the requested scope; planning alone does not authorize implementation.

Prefer the smallest observable slice: simultaneous two-endpoint playback,
left/right assignment, controls, timing/drift correction, then more channels.
Separate OS API support from radio/driver capability. Record unverified assumptions.
For each slice give user value, owning source sets, contracts, failure cases,
acceptance checks and docs to update. Android remote control and Android audio
output support are separate features. Do not promise general multi-speaker output.
Use `issues` only when the user asks to create or manage GitHub issues.
