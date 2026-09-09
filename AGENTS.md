# Repository command environment

- Invoke installed tools directly through the user `PATH`, using short command names such as `git`, `node`, `npm`, `java`, `gradle`, and `adb`. Invoke the repository wrapper as `.\gradlew.bat`. This is the default because it shortens commands and reduces path, quoting, and token errors.
- Use PowerShell only when a command actually needs PowerShell features such as `$env:PATH`, `Get-Command`, `Join-Path`, object pipelines, or PowerShell control flow. In that case use PowerShell 7 (`pwsh`), not Windows PowerShell 5.1 (`powershell`), unless a task specifically requires 5.1.
- Do not wrap a simple executable invocation in `pwsh -Command`; run the short command directly.
- Do not embed machine-specific executable paths in commands, scripts, or documentation.
- If the required version is not first on `PATH`, locate a user-level installation with `Get-Command -All` or under `$env:USERPROFILE` / `$env:LOCALAPPDATA`, prepend its directory only to the current process `$env:PATH`, and continue using the short command name.
- Wrangler requires Node.js 22 or newer. Verify `node --version` before cloud commands.
- Android builds require JDK 17 and the local Android SDK. Verify `java --version` and `adb version` before running Gradle tasks.
- See `docs/windows-tooling.md` for command-selection rules and the verified discovery/current-process PATH setup commands.

# Work ownership and subagents

- Delegate simple existing test execution and APK builds from already verified source versions to Luna (`gpt-5.6-luna`) by default. Complex testing, including real-device background synchronization, concurrency and lifecycle recovery, is designed and executed by the primary agent. The current real-device verification task is entirely primary-agent owned, including any builds. Other work stays with the primary agent unless separately authorized. If Luna is unavailable, report that limitation and let the primary agent continue.
- Follow `docs/stage4-production-runbook.md` (current workflow section). Keep the verified commit fixed, inspect existing runs before dispatching, and report completion only after the relevant workflow jobs finish successfully.
- Carry existing authorization through the current release without repeatedly asking. A model preference does not authorize unrelated or future production releases; a push-only request requires clarifying production scope before production changes.

# Debug APK handoff after full Android tests

- After each successful full Android test run, delegate Debug APK building to Luna (`gpt-5.6-luna`); the primary agent reviews delivery evidence. Build the tested variants; after the full two-variant matrix, deliver offline and production Debug APKs.
- Use the same tested Android source and build configuration. Do not repeat passing full tests solely to package APKs; targeted test runs do not trigger this handoff. Reuse verified outputs from the same source when available.
- Follow `docs/minimal-testing.md` for commands and handoff checks. Report actual APK paths, environments and SHA-256 values; do not report stale artifacts as new builds.
- Local Debug APK building does not itself authorize publishing a GitHub Release, production deployment, or installation on a device.

# Test delegation

- Android CI and release preparation use the `release` test profile once on production; use the `daily` profile or focused tests during development. See `docs/android-test-profiles.md` for the exact scope and affected-feature additions. Preserve both variants' builds, lint, screenshots, and the offline connectivity boundary check. CI expands coverage for changes outside the core profile. Unfiltered Gradle unit tasks and `full_unit_suite` retain the full independent suite; `full_android_matrix` adds offline coverage for flavor-specific changes. Do not repeat shared tests solely to package an APK.
- Delegate running simple existing tests to Luna (`gpt-5.6-luna`). The primary agent runs complex tests and owns test design, writing or changing tests, and fixing failures unless separately authorized.
- The primary agent defines the scope and acceptance criteria, designs concurrency and recovery scenarios, and reviews assertions.
- Follow `docs/minimal-testing.md`: reuse existing coverage, run the smallest relevant suite, and avoid repeating passing checks without a new reason. Report actual commands, tested source state, passed/failed/skipped counts, failures and coverage limits.
- Never hide failures by skipping tests or weakening assertions. Escalate unresolved failures to the primary agent.
- After successful full Android tests, continue with the Debug APK handoff rule.
