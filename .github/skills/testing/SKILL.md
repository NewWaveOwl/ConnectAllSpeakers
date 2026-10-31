---
name: testing
description: Design, run or investigate Kotlin shared and Windows audio tests, including routing, units, cancellation and hardware verification.
---

# Tests and audio evidence

Read the current contracts, source and diff. Establish existing failures before
editing behavior. Choose the lowest level that proves the requirement.

- Common tests: channel mapping, validated gain/delay, profile rules and state
  transitions. Use deterministic PCM samples and injected clocks where relevant.
- JVM/desktop tests: backend contracts, cancellation, endpoint removal, format
  mismatch and resource cleanup using explicit fakes. Fakes prove application
  behavior, not Bluetooth radio support.
- Native/hardware tests: independently render identifiable left/right signals to
  two speakers at once; check reconnect, underruns and measured timing drift.
  Record OS, adapter, drivers, speaker models, codecs, duration and measurement.
- No tests that only match documentation wording or duplicate implementation.
  No sleeps or live device requirements in ordinary unit tests.
- Keep tests in the matching source set and package; preserve useful existing
  tests. Test invalid input and failure paths when the change adds those behaviors.

Run `./gradlew.bat :shared:jvmTest :desktopApp:test` where applicable.
Inspect XML/HTML reports and give actual discovered/passed/failed/skipped counts.
If compilation fails, report tests blocked. Hardware behavior remains unverified
until exercised on actual hardware; unit tests cannot establish synchronization.
